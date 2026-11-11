package h.Hchat.hooks.api.media;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.net.WeChatNetworkApi;
import h.Hchat.hooks.items.fakevoiceduration.FakeVoiceDurationFeature;
import h.Hchat.utils.HLog;
import h.Hchat.utils.KavaReflector;
import me.yun.silk.AacCodec;
import me.yun.silk.SilkCodec;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信语音发送与原生播放 API。
 *
 * 走微信内部 voiceinfo + uploadvoice 链路，不经过录音 UI。
 * 发送链路按真实时长判断，微信显示时长按语音 UI 规则最多写 60 秒。
 */
public final class WeChatVoiceApi {
    public interface Logger {
        void log(String message);
    }

    public interface PlaybackListener {
        void onCompletion();

        void onError(String message);
    }

    private interface BooleanTask {
        boolean run();
    }

    private final DexFinder dexFinder;
    private final Context hostContext;
    private final Logger logger;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final int LONG_VOICE_DURATION_MS = 60_000;
    private static final long NORMAL_VOICE_MAX_BYTES = 460_000L;
    private static final int SILK_SAMPLE_RATE = 24_000;
    private static final int FILE_TYPE_UNKNOWN = 0;
    private static final int FILE_TYPE_SILK = 1;
    private static final int FILE_TYPE_MP3 = 2;
    private static final int FILE_TYPE_WAV = 3;
    private static final int FILE_TYPE_FLAC = 4;
    private static final int FILE_TYPE_OGG = 5;
    private static final int FILE_TYPE_M4A = 7;
    private static final int FILE_TYPE_MP4 = 8;
    private volatile Method voiceInfoValuesMethod;
    private final Object playbackLock = new Object();
    private Object playbackPlayer;
    private PlaybackListener playbackListener;
    private long playbackGeneration;

    public WeChatVoiceApi(Context hostContext, DexFinder dexFinder, Logger logger) {
        this.hostContext = hostContext;
        this.dexFinder = dexFinder;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return canSendSilently();
    }

    public boolean canSendSilently() {
        return canPrepareMassSend()
                && dexFinder.voiceUploadClass != null;
    }

    public boolean canPrepareMassSend() {
        return dexFinder != null
                && dexFinder.voiceStartRecordMethod != null
                && dexFinder.voiceFullPathMethod != null
                && dexFinder.voiceFinishRecordMethod != null;
    }

    public boolean send(String talker, String voicePath) {
        int durationMillis = TextUtils.isEmpty(voicePath) ? 0 : detectDurationMillis(voicePath);
        return send(talker, voicePath, durationMillis);
    }

    public boolean send(String talker, String voicePath, int durationMillis) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(voicePath)) {
            log("发送语音失败: talker/voicePath为空");
            return false;
        }
        File rawSource = new File(voicePath);
        if (!rawSource.isFile()) {
            log("发送语音失败: 文件不存在 " + voicePath);
            return false;
        }
        if (!canSendSilently()) {
            log("发送语音失败: API未就绪");
            return false;
        }
        PreparedVoice prepared = prepareVoiceSource(rawSource);
        if (prepared == null || !prepared.source.isFile()) {
            log("发送语音失败: 音频转换失败 " + voicePath);
            return false;
        }
        try {
            return runOnMainThread(() -> sendPrepared(talker, prepared, durationMillis));
        } finally {
            prepared.deleteTemp();
        }
    }

    public MassSendPayload prepareMassSendPayload(String voicePath, int durationMillis) {
        if (TextUtils.isEmpty(voicePath) || !canPrepareMassSend()) return null;
        File rawSource = new File(voicePath);
        if (!rawSource.isFile()) return null;
        PreparedVoice prepared = prepareVoiceSource(rawSource);
        if (prepared == null || !prepared.source.isFile()) {
            log("准备群发语音失败: 音频转换失败 " + voicePath);
            return null;
        }
        File source = prepared.source;
        try {
            String fileName = startRecord("masssendapp", voicePrefix(source.getAbsolutePath(), prepared.fileType));
            if (TextUtils.isEmpty(fileName)) {
                log("准备群发语音失败: 创建voiceinfo失败");
                return null;
            }
            String targetPath = fullPath(fileName, true);
            if (TextUtils.isEmpty(targetPath)) {
                log("准备群发语音失败: 获取语音目标路径失败");
                return null;
            }
            if (!copyFile(source, new File(targetPath))) {
                log("准备群发语音失败: 复制语音文件失败 " + targetPath);
                return null;
            }
            int actualDuration = actualVoiceDuration(
                    durationMillis > 0 ? durationMillis : detectDurationMillis(voicePath));
            int displayDuration = displayVoiceDuration(actualDuration);
            if (!finishRecord(fileName, displayDuration, 0)) {
                log("准备群发语音失败: 完成voiceinfo失败");
                return null;
            }
            return new MassSendPayload(fileName, displayDuration);
        } catch (Throwable e) {
            log("准备群发语音异常: " + e.getMessage());
            return null;
        } finally {
            prepared.deleteTemp();
        }
    }

    public MassSendPayload existingMassSendPayload(String fileName, int durationMillis) {
        if (TextUtils.isEmpty(fileName) || dexFinder == null || dexFinder.voiceFullPathMethod == null) {
            return null;
        }
        String path = resolvePath(fileName);
        if (TextUtils.isEmpty(path) || !new File(path).isFile()) return null;
        return new MassSendPayload(fileName, displayVoiceDuration(durationMillis));
    }

    private boolean sendPrepared(String talker, PreparedVoice prepared, int durationMillis) {
        File source = prepared.source;
        try {
            String fileName = startRecord(talker, voicePrefix(source.getAbsolutePath(), prepared.fileType));
            if (TextUtils.isEmpty(fileName)) {
                log("发送语音失败: 创建voiceinfo失败");
                return false;
            }
            String targetPath = fullPath(fileName, true);
            if (TextUtils.isEmpty(targetPath)) {
                log("发送语音失败: 获取语音目标路径失败");
                return false;
            }
            if (!copyFile(source, new File(targetPath))) {
                log("发送语音失败: 复制语音文件失败 " + targetPath);
                return false;
            }
            int actualDuration = actualVoiceDuration(durationMillis);
            int displayDuration = displayVoiceDuration(actualDuration);
            if (!finishRecord(fileName, displayDuration, 0)) {
                log("发送语音失败: 完成voiceinfo失败");
                return false;
            }
            boolean useCdn = shouldUseCdn(source, actualDuration);
            Object request = newUploadRequest(fileName, useCdn);
            if (request == null) {
                log("发送语音失败: 创建上传请求失败 uploadClass="
                        + voiceUploadClassName() + " ctors=" + voiceUploadConstructors());
                return false;
            }
            WeChatNetworkApi network = WeChatApis.network();
            if (network == null || !network.sendRequest(request)) {
                log("发送语音失败: 网络发包失败");
                return false;
            }
            return true;
        } catch (Throwable e) {
            log("发送语音异常: " + e.getMessage());
            return false;
        }
    }

    private boolean runOnMainThread(BooleanTask task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return task.run();
        }
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        CountDownLatch latch = new CountDownLatch(1);
        boolean posted = MAIN_HANDLER.post(() -> {
            try {
                result.set(task.run());
            } catch (Throwable e) {
                log("语音主线程任务执行异常: " + e.getMessage());
            } finally {
                latch.countDown();
            }
        });
        if (!posted) {
            log("语音主线程任务投递失败");
            return false;
        }
        try {
            if (!latch.await(90, TimeUnit.SECONDS)) {
                log("语音主线程任务执行超时");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("语音主线程任务等待被中断");
            return false;
        }
        Boolean value = result.get();
        return value != null && value;
    }

    public String resolvePath(String fileName) {
        if (TextUtils.isEmpty(fileName) || dexFinder == null || dexFinder.voiceFullPathMethod == null) {
            return "";
        }
        try {
            return fullPath(fileName, false);
        } catch (Throwable e) {
            log("解析语音路径异常: " + e.getMessage());
            return "";
        }
    }

    /** Resolve a downloaded voice using its native message, including WeChat's new file storage. */
    public String resolvePath(Object nativeMessage, String fileName) {
        Method method = dexFinder != null ? dexFinder.voiceMessagePathMethod : null;
        if (nativeMessage != null && method != null
                && method.getParameterTypes()[0].isInstance(nativeMessage)) {
            try {
                Object target = voicePathTarget(method);
                if (target != null) {
                    Object value = KavaReflector.invokeOrThrow(method, target, nativeMessage, fileName, false);
                    if (value instanceof String && !TextUtils.isEmpty((String) value)
                            && new File((String) value).isFile()) {
                        return (String) value;
                    }
                }
            } catch (Throwable error) {
                logReflectionFailure("解析语音消息路径", method, error);
            }
        }
        return resolvePath(fileName);
    }

    public int storedDurationMillis(String fileName) {
        Method queryMethod = dexFinder != null ? dexFinder.voiceInfoQueryMethod : null;
        if (TextUtils.isEmpty(fileName) || queryMethod == null || !KavaReflector.isStatic(queryMethod)) {
            return 0;
        }
        try {
            Object voiceInfo = KavaReflector.invoke(queryMethod, null, fileName);
            if (voiceInfo == null) return 0;
            Method valuesMethod = voiceInfoValuesMethod(voiceInfo.getClass());
            ContentValues values = (ContentValues) KavaReflector.invoke(valuesMethod, voiceInfo);
            if (values == null) return 0;
            Object raw = values.get("VoiceLength");
            long duration = raw instanceof Number
                    ? ((Number) raw).longValue()
                    : Long.parseLong(String.valueOf(raw));
            return duration > 0L
                    ? (int) Math.min(Integer.MAX_VALUE, duration)
                    : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** Returns whether WeChat's native SceneVoicePlayer was located. */
    public boolean canPlayOriginal() {
        return dexFinder != null
                && dexFinder.voicePlaybackStartMethod != null
                && dexFinder.voicePlaybackStopMethod != null;
    }

    /** Plays an existing WeChat voice file through WeChat's native Silk/AMR player. */
    public boolean playOriginal(String voicePath, PlaybackListener listener) {
        if (TextUtils.isEmpty(voicePath) || !new File(voicePath).isFile()) {
            log("播放原语音失败: 文件不存在 " + voicePath);
            return false;
        }
        if (!canPlayOriginal()) {
            log("播放原语音失败: 原生播放器未就绪");
            return false;
        }
        return runOnMainThread(() -> playOriginalOnMain(voicePath, listener));
    }

    public boolean pauseOriginalPlayback() {
        return runOnMainThread(() -> invokeActivePlaybackBoolean(dexFinder.voicePlaybackPauseMethod, true));
    }

    public boolean resumeOriginalPlayback() {
        return runOnMainThread(() -> invokeActivePlaybackBoolean(dexFinder.voicePlaybackResumeMethod));
    }

    public void stopOriginalPlayback() {
        runOnMainThread(() -> {
            stopOriginalPlaybackOnMain();
            return true;
        });
    }

    private boolean playOriginalOnMain(String voicePath, PlaybackListener listener) {
        stopOriginalPlaybackOnMain();
        Method start = dexFinder.voicePlaybackStartMethod;
        Class<?> playerClass = start != null ? start.getDeclaringClass() : null;
        Constructor<?> constructor = KavaReflector.findConstructor(playerClass, Context.class, int.class);
        Object player = KavaReflector.newInstance(constructor, hostContext, 0);
        if (player == null) {
            log("播放原语音失败: 创建原生播放器失败");
            return false;
        }

        final long generation;
        synchronized (playbackLock) {
            generation = ++playbackGeneration;
            playbackPlayer = player;
            playbackListener = listener;
        }
        installPlaybackCallbacks(player, generation);
        try {
            Object result = KavaReflector.invokeOrThrow(start, player, voicePath, true, true, -1);
            if (result instanceof Boolean && (Boolean) result) return true;
            clearPlayback(generation);
            stopPlaybackPlayer(player);
            log("播放原语音失败: 微信原生播放器拒绝播放");
        } catch (Throwable error) {
            clearPlayback(generation);
            stopPlaybackPlayer(player);
            log("播放原语音异常: " + error.getMessage());
        }
        return false;
    }

    private boolean invokeActivePlaybackBoolean(Method method, Object... args) {
        Object player;
        synchronized (playbackLock) {
            player = playbackPlayer;
        }
        if (player == null || method == null || method.getDeclaringClass() != player.getClass()) return false;
        try {
            Object result = KavaReflector.invokeOrThrow(method, player, args);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable error) {
            log("控制原语音播放失败: " + error.getMessage());
            return false;
        }
    }

    private void stopOriginalPlaybackOnMain() {
        Object player;
        Method stop = dexFinder != null ? dexFinder.voicePlaybackStopMethod : null;
        synchronized (playbackLock) {
            playbackGeneration++;
            player = playbackPlayer;
            playbackPlayer = null;
            playbackListener = null;
        }
        if (player == null || stop == null || stop.getDeclaringClass() != player.getClass()) return;
        stopPlaybackPlayer(player);
    }

    private void stopPlaybackPlayer(Object player) {
        Method stop = dexFinder != null ? dexFinder.voicePlaybackStopMethod : null;
        if (player == null || stop == null || stop.getDeclaringClass() != player.getClass()) return;
        try {
            KavaReflector.invokeOrThrow(stop, player, true);
        } catch (Throwable error) {
            log("停止原语音播放失败: " + error.getMessage());
        }
    }

    private void installPlaybackCallbacks(Object player, long generation) {
        for (Field field : KavaReflector.declaredFields(player.getClass())) {
            Class<?> callbackType = field.getType();
            if (!callbackType.isInterface()) continue;
            Method callback = singleVoidCallback(callbackType);
            if (callback == null) continue;
            String callbackName = callback.getName();
            CallbackKind kind;
            if ("onCompletion".equals(callbackName)) {
                kind = CallbackKind.COMPLETION;
            } else if ("onStop".equals(callbackName)) {
                kind = CallbackKind.STOP;
            } else if (callback.getParameterTypes().length == 0) {
                kind = CallbackKind.ERROR;
            } else {
                continue;
            }
            Object proxy = newPlaybackCallback(callbackType, callbackName, generation, kind);
            KavaReflector.writeField(field, player, proxy);
        }
    }

    private Method singleVoidCallback(Class<?> callbackType) {
        Method selected = null;
        for (Method method : KavaReflector.declaredMethods(callbackType)) {
            if (method.getReturnType() != void.class) continue;
            if (selected != null) return null;
            selected = method;
        }
        return selected;
    }

    private Object newPlaybackCallback(Class<?> callbackType,
                                       String callbackName,
                                       long generation,
                                       CallbackKind kind) {
        ClassLoader loader = callbackType.getClassLoader();
        if (loader == null) loader = WeChatVoiceApi.class.getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == (args != null && args.length > 0 ? args[0] : null);
                if ("toString".equals(method.getName())) return "HchatVoicePlaybackCallback";
                return null;
            }
            if (callbackName.equals(method.getName())) {
                MAIN_HANDLER.post(() -> finishPlayback(generation, kind));
            }
            return defaultValue(method.getReturnType());
        });
    }

    private void finishPlayback(long generation, CallbackKind kind) {
        PlaybackListener listener = clearPlayback(generation);
        if (listener == null) return;
        try {
            if (kind == CallbackKind.ERROR) {
                listener.onError("微信原生语音播放器播放失败");
            } else {
                listener.onCompletion();
            }
        } catch (Throwable error) {
            log("原语音播放回调执行失败: " + error.getMessage());
        }
    }

    private PlaybackListener clearPlayback(long generation) {
        synchronized (playbackLock) {
            if (generation != playbackGeneration) return null;
            PlaybackListener listener = playbackListener;
            playbackPlayer = null;
            playbackListener = null;
            return listener;
        }
    }

    private Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    private enum CallbackKind {
        COMPLETION,
        STOP,
        ERROR
    }

    private Method voiceInfoValuesMethod(Class<?> voiceInfoClass) {
        Method cached = voiceInfoValuesMethod;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(voiceInfoClass)) {
            return cached;
        }
        for (Method method : KavaReflector.declaredMethods(voiceInfoClass)) {
            if (method.getParameterTypes().length != 0 || method.getReturnType() != ContentValues.class) continue;
            voiceInfoValuesMethod = KavaReflector.accessible(method);
            return voiceInfoValuesMethod;
        }
        return null;
    }

    private String startRecord(String talker, String prefix) throws Exception {
        Method method = dexFinder.voiceStartRecordMethod;
        return (String) KavaReflector.invoke(method, null, talker, prefix);
    }

    private String fullPath(String fileName, boolean create) throws Exception {
        Method method = dexFinder.voiceFullPathMethod;
        Object target = voicePathTarget(method);
        if (!KavaReflector.isStatic(method) && target == null) {
            log("发送语音失败: 获取语音路径服务失败 " + method.getDeclaringClass().getName());
            return "";
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 2) {
            return (String) KavaReflector.invoke(method, target, fileName, create);
        }
        Object resourceKey = defaultResourceKey(params[0]);
        if (resourceKey == null) return "";
        return (String) KavaReflector.invoke(method, target, resourceKey, fileName, create);
    }

    private Object voicePathTarget(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return null;
        return WeChatInternalServices.getService(dexFinder, method.getDeclaringClass());
    }

    private boolean finishRecord(String fileName, int durationMillis, int scene) throws Exception {
        Method method = dexFinder.voiceFinishRecordMethod;
        try {
            Object result;
            switch (method.getParameterTypes().length) {
                case 3:
                    result = KavaReflector.invokeOrThrow(method, null, fileName, durationMillis, scene);
                    break;
                case 4:
                    result = KavaReflector.invokeOrThrow(method, null, fileName, durationMillis, scene, null);
                    break;
                case 5:
                    // The added msgSource argument follows WeChat's own voice-copy call.
                    result = KavaReflector.invokeOrThrow(method, null, fileName, durationMillis, scene, null, null);
                    break;
                default:
                    throw new NoSuchMethodException("unsupported finishRecord signature");
            }
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable error) {
            logReflectionFailure("完成语音记录", method, error);
            return false;
        }
    }

    private void logReflectionFailure(String stage, Method method, Throwable error) {
        String version = "unknown";
        try {
            if (WeChatApis.version() != null) version = WeChatApis.version().current().displayVersion();
        } catch (Throwable ignored) {}
        HLog.e("[WeChatVoiceApi] " + stage + "失败: version=" + version
                + " method=" + (method != null ? method.toGenericString() : "null"), error);
    }

    private boolean shouldUseCdn(File source, int durationMillis) {
        return dexFinder.voiceUploadCdnCtor != null
                && (durationMillis > LONG_VOICE_DURATION_MS
                || (source != null && source.length() >= NORMAL_VOICE_MAX_BYTES)
                || dexFinder.voiceUploadCtor == null);
    }

    private int actualVoiceDuration(int durationMillis) {
        return Math.max(1, durationMillis);
    }

    private int displayVoiceDuration(int durationMillis) {
        if (FakeVoiceDurationFeature.isEnabled(hostContext)) {
            return FakeVoiceDurationFeature.durationMillis(hostContext);
        }
        return Math.min(LONG_VOICE_DURATION_MS, actualVoiceDuration(durationMillis));
    }

    private Object newUploadRequest(String fileName, boolean useCdn) {
        try {
            Constructor<?> ctor = useCdn ? dexFinder.voiceUploadCdnCtor : dexFinder.voiceUploadCtor;
            if (ctor == null && useCdn) {
                ctor = dexFinder.voiceUploadCtor;
                useCdn = false;
            }
            if (ctor == null) {
                ctor = dexFinder.voiceUploadCdnCtor;
                useCdn = true;
            }
            if (ctor == null) return newUploadRequestFallback(fileName);
            if (useCdn) {
                return KavaReflector.newInstance(ctor, fileName, true);
            }
            Object request = KavaReflector.newInstance(ctor, fileName, 0);
            return request != null ? request : newUploadRequestFallback(fileName);
        } catch (Throwable e) {
            log("创建语音上传请求异常: " + e.getMessage());
            return newUploadRequestFallback(fileName);
        }
    }

    private Object newUploadRequestFallback(String fileName) {
        Class<?> clazz = dexFinder != null ? dexFinder.voiceUploadClass : null;
        if (clazz == null) return null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            try {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 0 || types[0] != String.class) continue;
                KavaReflector.accessible(ctor);
                Object[] args = new Object[types.length];
                args[0] = fileName;
                for (int i = 1; i < types.length; i++) {
                    args[i] = defaultCtorArg(types[i]);
                }
                Object request = KavaReflector.newInstance(ctor, args);
                if (request != null) {
                    log("语音上传请求使用兜底构造: " + constructorSignature(ctor));
                    return request;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private Object defaultCtorArg(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    private String voiceUploadClassName() {
        return dexFinder != null && dexFinder.voiceUploadClass != null
                ? dexFinder.voiceUploadClass.getName()
                : "null";
    }

    private String voiceUploadConstructors() {
        Class<?> clazz = dexFinder != null ? dexFinder.voiceUploadClass : null;
        if (clazz == null) return "[]";
        StringBuilder builder = new StringBuilder("[");
        Constructor<?>[] ctors = clazz.getDeclaredConstructors();
        for (int i = 0; i < ctors.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(constructorSignature(ctors[i]));
        }
        return builder.append(']').toString();
    }

    private String constructorSignature(Constructor<?> ctor) {
        return ctor == null ? "null" : ctor.getName() + Arrays.toString(ctor.getParameterTypes());
    }

    private Object defaultResourceKey(Class<?> clazz) {
        try {
            Field field = KavaReflector.findDeclaredField(clazz, "j");
            Object value = KavaReflector.readField(field, (Object) null);
            if (value != null) return value;
        } catch (Throwable ignored) {
        }
        try {
            for (Field field : KavaReflector.declaredFields(clazz)) {
                if (!KavaReflector.isStatic(field)) continue;
                if (!clazz.isAssignableFrom(field.getType())) continue;
                Object value = KavaReflector.readField(field, (Object) null);
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String voicePrefix(String path, int fileType) {
        if (fileType == FILE_TYPE_SILK) return "silk_";
        String lower = path != null ? path.toLowerCase() : "";
        if (lower.endsWith(".silk") || lower.endsWith(".slk")) return "silk_";
        if (lower.endsWith(".spx") || lower.endsWith(".speex")) return "spx_";
        return "amr_";
    }

    private PreparedVoice prepareVoiceSource(File source) {
        int fileType = detectFileType(source.getAbsolutePath());
        if (fileType == FILE_TYPE_SILK) {
            return new PreparedVoice(source, fileType, null);
        }
        if (fileType > FILE_TYPE_SILK) {
            File converted = createTempSilkFile(source);
            if (converted == null) return null;
            int result = convertToSilk(source, converted);
            if (result == 0 && converted.isFile() && converted.length() > 0L) {
                return new PreparedVoice(converted, FILE_TYPE_SILK, converted);
            }
            if (!converted.delete()) {
                converted.deleteOnExit();
            }
            log("发送语音失败: 转 Silk 失败 code=" + result);
            return null;
        }
        if (fileType == FILE_TYPE_UNKNOWN && isDecodableAudio(source.getAbsolutePath())) {
            File converted = createTempSilkFile(source);
            if (converted == null) return null;
            int result = convertToSilk(source, converted);
            if (result == 0 && converted.isFile() && converted.length() > 0L) {
                return new PreparedVoice(converted, FILE_TYPE_SILK, converted);
            }
            if (!converted.delete()) {
                converted.deleteOnExit();
            }
            log("发送语音失败: 未知音频转 Silk 失败 code=" + result);
            return null;
        }
        return new PreparedVoice(source, fileType, null);
    }

    private int detectFileType(String path) {
        try {
            return new SilkCodec().getFileType(path);
        } catch (Throwable ignored) {
            return FILE_TYPE_UNKNOWN;
        }
    }

    private int convertToSilk(File source, File target) {
        try {
            SilkCodec codec = new SilkCodec();
            int fileType = codec.getFileType(source.getAbsolutePath());
            switch (fileType) {
                case FILE_TYPE_MP3:
                case FILE_TYPE_WAV:
                case FILE_TYPE_FLAC:
                case FILE_TYPE_OGG:
                    return AacCodec.autoToSilkCompat(
                        source.getAbsolutePath(),
                        target.getAbsolutePath(),
                        codec,
                        SILK_SAMPLE_RATE
                    );
                case FILE_TYPE_M4A:
                case FILE_TYPE_MP4:
                    return AacCodec.mp4ToSilk(source.getAbsolutePath(), target.getAbsolutePath(), codec, SILK_SAMPLE_RATE);
                default:
                    int result = AacCodec.autoToSilkCompat(
                        source.getAbsolutePath(),
                        target.getAbsolutePath(),
                        codec,
                        SILK_SAMPLE_RATE
                    );
                    if (result == 0) return result;
                    return AacCodec.mp4ToSilk(source.getAbsolutePath(), target.getAbsolutePath(), codec, SILK_SAMPLE_RATE);
            }
        } catch (Throwable e) {
            log("音频转 Silk 异常: " + e.getMessage());
            return -1;
        }
    }

    private File createTempSilkFile(File source) {
        try {
            File parent = source.getParentFile();
            File dir = parent != null && parent.isDirectory() && parent.canWrite() ? parent : null;
            return dir != null
                    ? File.createTempFile("hchat_voice_", ".silk", dir)
                    : File.createTempFile("hchat_voice_", ".silk");
        } catch (Throwable e) {
            log("创建临时 Silk 文件失败: " + e.getMessage());
            return null;
        }
    }

    private boolean isDecodableAudio(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return !TextUtils.isEmpty(duration) && Long.parseLong(duration) > 0L;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private int detectDurationMillis(String path) {
        int codecDuration = detectCodecDurationMillis(path);
        if (codecDuration > 0) return codecDuration;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (TextUtils.isEmpty(duration)) return 1000;
            long millis = Long.parseLong(duration);
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, millis));
        } catch (Throwable ignored) {
            return 1000;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private int detectCodecDurationMillis(String path) {
        try {
            long duration = new SilkCodec().getDuration(path);
            if (duration <= 0) return 0;
            return (int) Math.min(Integer.MAX_VALUE, duration);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean copyFile(File source, File target) {
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            in = new FileInputStream(source);
            out = new FileOutputStream(target, false);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable e) {
            log("复制语音文件异常: " + e.getMessage());
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatVoiceApi] " + message);
    }

    public static final class MassSendPayload {
        private final String fileName;
        private final int durationMillis;

        MassSendPayload(String fileName, int durationMillis) {
            this.fileName = fileName;
            this.durationMillis = durationMillis;
        }

        public String getFileName() {
            return fileName;
        }

        public int getDurationMillis() {
            return durationMillis;
        }
    }

    private static final class PreparedVoice {
        final File source;
        final int fileType;
        final File tempFile;

        PreparedVoice(File source, int fileType, File tempFile) {
            this.source = source;
            this.fileType = fileType;
            this.tempFile = tempFile;
        }

        void deleteTemp() {
            if (tempFile == null) return;
            if (!tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }
}
