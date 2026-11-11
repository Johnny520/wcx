package h.Hchat.hooks.api.media;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 微信图片发送 API。
 */
public final class WeChatImageApi {
    private static final String ASYNC_DEFAULT_SOURCE = "msg_raw_img_send";
    private static final String ASYNC_EXTERNAL_SOURCE = "send_wx_media_message_helper";

    public interface Logger {
        void log(String message);
    }

    public interface DownloadCallback {
        void onSuccess(File file);

        void onError(String message);
    }

    private final Context hostContext;
    private final DexFinder dexFinder;
    private final Logger logger;
    private static final ConcurrentMap<Class<?>, Boolean> marsHookedManagerClasses =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Object> marsCdnManagers =
            new ConcurrentHashMap<>();

    public WeChatImageApi(Context hostContext, DexFinder dexFinder, Logger logger) {
        this.hostContext = hostContext;
        this.dexFinder = dexFinder;
        this.logger = logger;
        if (dexFinder != null) {
            installMarsCdnManagerHook(dexFinder.marsCdnManagerClass, logger);
        }
    }

    public boolean isAvailable() {
        return canSendSilently();
    }

    public boolean canSendSilently() {
        return hostContext != null
                && dexFinder != null
                && (dexFinder.sendImageMethod != null || canSendAsyncAppInfo());
    }

    public boolean canDownloadCdn() {
        installMarsCdnManagerHook(dexFinder != null ? dexFinder.marsCdnManagerClass : null, logger);
        return dexFinder != null && dexFinder.isMarsCdnReady();
    }

    public String cdnDiagnostics() {
        installMarsCdnManagerHook(dexFinder != null ? dexFinder.marsCdnManagerClass : null, logger);
        if (dexFinder == null) return "dexFinder=null";
        return "marsReady=" + dexFinder.isMarsCdnReady()
                + " managerClass=" + (dexFinder.marsCdnManagerClass != null)
                + " requestClass=" + (dexFinder.marsCdnDownloadRequestClass != null)
                + " callbackClass=" + (dexFinder.marsCdnDownloadCallbackClass != null)
                + " startMethod=" + (dexFinder.marsCdnStartDownloadMethod != null)
                + " managerInstance="
                + (resolveMarsCdnManager(dexFinder.marsCdnManagerClass) != null);
    }

    public boolean send(String talker, String imagePath) {
        return sendInternal(talker, imagePath, "", false);
    }

    public boolean send(String talker, String imagePath, String appId) {
        return sendInternal(talker, imagePath, appId, false);
    }

    public boolean sendOriginal(String talker, String imagePath) {
        return sendInternal(talker, imagePath, "", true);
    }

    private boolean sendInternal(String talker, String imagePath, String appId, boolean original) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(imagePath)) {
            log("发送图片失败: talker/imagePath为空");
            return false;
        }
        File file = new File(imagePath);
        if (!file.isFile()) {
            log("发送图片失败: 文件不存在 " + imagePath);
            return false;
        }
        if (!canSendSilently()) {
            log("发送图片失败: API未就绪");
            return false;
        }
        try {
            if (!TextUtils.isEmpty(appId) && shouldUseAsyncAppInfo() && canSendAsyncAppInfo()) {
                if (sendAsyncAppInfo(talker, imagePath, appId)) {
                    return true;
                }
                log("新版图片appid链路失败，回退短签名");
            }
            Method method = dexFinder.sendImageMethod;
            if (method == null) {
                log("发送图片失败: 短签名API未就绪");
                return false;
            }
            Object target = targetFor(method);
            if (!KavaReflector.isStatic(method) && target == null) {
                log("发送图片失败: 无法创建 " + method.getDeclaringClass().getName());
                return false;
            }
            KavaReflector.invoke(method, target,
                    buildSendImageArgs(method, talker, imagePath, appId, original));
            return true;
        } catch (Throwable e) {
            log("发送图片异常: " + e.getMessage());
            return false;
        }
    }

    public boolean downloadCdn(String md5, String cdnUrl, String aesKey, String savePath, int fileType) {
        return downloadCdn(md5, cdnUrl, aesKey, savePath, fileType, 0, null);
    }

    public boolean downloadCdn(
            String md5,
            String cdnUrl,
            String aesKey,
            String savePath,
            int fileType,
            DownloadCallback callback
    ) {
        return downloadCdn(md5, cdnUrl, aesKey, savePath, fileType, 0, callback);
    }

    public boolean downloadCdn(
            String md5,
            String cdnUrl,
            String aesKey,
            String savePath,
            int fileType,
            int totalLen
    ) {
        return downloadCdn(md5, cdnUrl, aesKey, savePath, fileType, totalLen, null);
    }

    public boolean downloadCdn(
            String md5,
            String cdnUrl,
            String aesKey,
            String savePath,
            int fileType,
            int totalLen,
            DownloadCallback callback
    ) {
        DownloadCallbackState callbackState = new DownloadCallbackState(callback, savePath);
        if (TextUtils.isEmpty(cdnUrl) || TextUtils.isEmpty(aesKey) || TextUtils.isEmpty(savePath)) {
            log("下载图片失败: cdnUrl/aesKey/savePath为空");
            callbackState.error("cdnUrl/aesKey/savePath为空");
            return false;
        }
        if (!canDownloadCdn()) {
            log("下载图片失败: CDN API未就绪");
            callbackState.error("CDN API未就绪");
            return false;
        }
        try {
            installMarsCdnManagerHook(dexFinder.marsCdnManagerClass, logger);
            File target = new File(savePath);
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();

            if (!dexFinder.isMarsCdnReady()) {
                log("下载图片失败: Mars CDN API未就绪");
                callbackState.error("Mars CDN API未就绪");
                return false;
            }

            boolean submitted = downloadByMarsCdn(cdnUrl, aesKey, savePath, fileType, callbackState);
            if (!submitted) callbackState.error("CDN任务提交失败");
            return submitted;
        } catch (Throwable e) {
            log("下载图片异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            callbackState.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    public String resolveBestAvailablePath(Object nativeMessage) {
        Method method = dexFinder != null ? dexFinder.imageBestPathMethod : null;
        if (nativeMessage == null || method == null) return "";
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 || !params[0].isInstance(nativeMessage)) return "";
        try {
            Object storage = imageStorage(method.getDeclaringClass());
            if (storage == null) return "";
            String path = (String) KavaReflector.invoke(method, storage, nativeMessage);
            if (TextUtils.isEmpty(path)) return "";
            return materializeImagePath(path, method.getDeclaringClass().getClassLoader());
        } catch (Throwable e) {
            log("解析图片原图路径异常: " + e.getMessage());
            return "";
        }
    }

    public String resolvePathToken(String token) {
        Method method = dexFinder != null ? dexFinder.imageTokenPathMethod : null;
        if (TextUtils.isEmpty(token) || method == null) return "";
        try {
            Object target = imageStorage(method.getDeclaringClass());
            if (target == null) return "";
            String best = "";
            for (boolean original : new boolean[]{true, false}) {
                String path = (String) KavaReflector.invoke(method, target, token, original);
                if (TextUtils.isEmpty(path)) continue;
                String readable = materializeImagePath(
                        path,
                        method.getDeclaringClass().getClassLoader());
                if (!readable.isEmpty()
                        && (best.isEmpty() || new File(readable).length() > new File(best).length())) {
                    best = readable;
                }
            }
            return best;
        } catch (Throwable e) {
            log("解析图片路径标识异常: " + e.getMessage());
            return "";
        }
    }

    private synchronized String materializeImagePath(String path, ClassLoader classLoader) {
        if (TextUtils.isEmpty(path)) return "";
        File direct = new File(path);
        if (direct.isFile()) return direct.getAbsolutePath();

        File dir = new File(hostContext.getCacheDir(), "Hchat_message_image");
        if (!dir.isDirectory() && !dir.mkdirs()) return "";
        File target = new File(dir, "image_" + Integer.toHexString(path.hashCode()) + ".jpg");
        if (target.isFile() && target.length() > 0L) return target.getAbsolutePath();

        InputStream input = openVfsInputStream(classLoader, path);
        if (input == null) return "";
        try (InputStream stream = input; FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } catch (Throwable e) {
            if (target.exists()) target.delete();
            log("读取图片VFS路径异常: " + e.getMessage());
            return "";
        }
        return target.isFile() && target.length() > 0L ? target.getAbsolutePath() : "";
    }

    private InputStream openVfsInputStream(ClassLoader classLoader, String path) {
        if (classLoader == null || TextUtils.isEmpty(path)) return null;
        for (String className : new String[]{"com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6"}) {
            Class<?> clazz = KavaReflector.loadClass(className, classLoader);
            if (clazz == null) continue;
            for (String methodName : new String[]{"E", "F"}) {
                Method method = KavaReflector.findMethod(clazz, methodName, String.class);
                Object value = KavaReflector.invoke(method, null, path);
                if (value instanceof InputStream) return (InputStream) value;
            }
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                if (!Modifier.isStatic(method.getModifiers())) continue;
                if (method.getReturnType() != InputStream.class) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != String.class) continue;
                Object value = KavaReflector.invoke(method, null, path);
                if (value instanceof InputStream) return (InputStream) value;
            }
        }
        return null;
    }

    private Object imageStorage(Class<?> storageClass) {
        Method getter = dexFinder != null ? dexFinder.imageStorageGetterMethod : null;
        if (getter != null && KavaReflector.isStatic(getter)
                && getter.getParameterTypes().length == 0
                && getter.getReturnType() == storageClass) {
            Object storage = KavaReflector.invoke(getter, null);
            if (storageClass.isInstance(storage)) return storage;
        }
        return WeChatInternalServices.getService(dexFinder, storageClass);
    }

    private Object newInstance(Class<?> clazz) {
        if (clazz == null) return null;
        try {
            return KavaReflector.newInstance(KavaReflector.findConstructor(clazz));
        } catch (Throwable e) {
            log("创建图片发送器失败: " + clazz.getName() + " " + e.getMessage());
            return null;
        }
    }

    public static void installMarsCdnManagerHook(ClassLoader classLoader, Logger logger) {
        Class<?> clazz = KavaReflector.loadClass("com.tencent.mars.cdn.CdnManager", classLoader);
        installMarsCdnManagerHook(clazz, logger);
    }

    public static void installMarsCdnManagerHook(Class<?> managerClass, Logger logger) {
        if (managerClass == null
                || marsHookedManagerClasses.putIfAbsent(managerClass, Boolean.TRUE) != null) {
            return;
        }
        try {
            XC_MethodHook captureConstructedManager = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    rememberMarsCdnManager(managerClass, param != null ? param.thisObject : null);
                }
            };
            Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllConstructors(
                    managerClass,
                    captureConstructedManager);
            for (XC_MethodHook.Unhook unhook : unhooks) {
                HookRegistry.get().add(unhook);
            }
            Set<XC_MethodHook.Unhook> startUnhooks = XposedBridge.hookAllMethods(
                    managerClass,
                    "startC2CDownload",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            rememberMarsCdnManager(
                                    managerClass,
                                    param != null ? param.thisObject : null);
                        }
                    });
            for (XC_MethodHook.Unhook unhook : startUnhooks) {
                HookRegistry.get().add(unhook);
            }
            resolveMarsCdnManager(managerClass);
        } catch (Throwable e) {
            marsHookedManagerClasses.remove(managerClass);
            logStatic(logger, "Mars CDN实例捕获Hook安装失败: " + e.getMessage());
        }
    }

    private static Object resolveMarsCdnManager(Class<?> managerClass) {
        if (managerClass == null) return null;
        Object cached = marsCdnManagers.get(managerClass);
        if (managerClass.isInstance(cached)) return cached;
        if (cached != null) marsCdnManagers.remove(managerClass, cached);

        ClassLoader classLoader = managerClass.getClassLoader();
        Object manager = managerFromStaticMarsContext(managerClass, classLoader);
        if (manager == null) {
            manager = managerFromLegacyMarsContext(managerClass, classLoader);
        }
        if (manager == null) {
            manager = KavaReflector.staticInstance(managerClass);
        }
        return rememberMarsCdnManager(managerClass, manager);
    }

    private static Object managerFromStaticMarsContext(
            Class<?> managerClass,
            ClassLoader classLoader
    ) {
        Class<?> marsContextClass = KavaReflector.loadClass(
                "com.tencent.mars.MarsContext",
                classLoader);
        Method getManager = KavaReflector.findMethod(
                marsContextClass,
                "getManager",
                Class.class);
        if (!KavaReflector.isStatic(getManager)) return null;
        Object manager = KavaReflector.invoke(getManager, null, managerClass);
        return managerClass.isInstance(manager) ? manager : null;
    }

    private static Object managerFromLegacyMarsContext(
            Class<?> managerClass,
            ClassLoader classLoader
    ) {
        Class<?> marsClass = KavaReflector.loadClass("com.tencent.mars.Mars2", classLoader);
        Method getContext = KavaReflector.findMethod(marsClass, "getContext");
        if (!KavaReflector.isStatic(getContext)) return null;
        Object context = KavaReflector.invoke(getContext, null);
        if (context == null) return null;
        Method getManager = KavaReflector.findMethod(
                context.getClass(),
                "getManager",
                Class.class);
        Object manager = KavaReflector.invoke(getManager, context, managerClass);
        return managerClass.isInstance(manager) ? manager : null;
    }

    private static Object rememberMarsCdnManager(Class<?> managerClass, Object manager) {
        if (managerClass == null || !managerClass.isInstance(manager)) return null;
        marsCdnManagers.put(managerClass, manager);
        return manager;
    }

    private boolean downloadByMarsCdn(
            String cdnUrl,
            String aesKey,
            String savePath,
            int fileType,
            DownloadCallbackState callbackState
    ) {
        Object manager = resolveMarsCdnManager(dexFinder.marsCdnManagerClass);
        if (manager == null) {
            log("Mars CDN未提交: 尚未捕获CdnManager实例");
            return false;
        }
        try {
            Object request = KavaReflector.newInstance(KavaReflector.findConstructor(dexFinder.marsCdnDownloadRequestClass));
            if (request == null) {
                log("Mars CDN未提交: 无法创建C2CDownloadRequest");
                return false;
            }
            String fileKey = generateFkStyleFileKey(cdnUrl);
            callSetter(request, "setFileKey", fileKey);
            callSetter(request, "setFileid", cdnUrl);
            callSetter(request, "setAeskey", aesKey);
            callSetter(request, "setFileType", fileType > 0 ? fileType : 2);
            callSetter(request, "setSavePath2", savePath);
            callSetter(request, "setBizid", 1);
            callSetter(request, "setApptype", 1);
            Method build = KavaReflector.findMethod(request.getClass(), "build");
            if (build != null) KavaReflector.invoke(build, request);

            Object callback = Proxy.newProxyInstance(
                    dexFinder.marsCdnDownloadCallbackClass.getClassLoader(),
                    new Class<?>[]{dexFinder.marsCdnDownloadCallbackClass},
                    marsCallbackHandler(fileKey, callbackState));
            Object result = KavaReflector.invoke(
                    dexFinder.marsCdnStartDownloadMethod,
                    manager,
                    request,
                    callback);
            if (result instanceof Boolean) return (Boolean) result;
            if (result instanceof Number) return ((Number) result).intValue() >= 0;
            return true;
        } catch (Throwable e) {
            log("Mars CDN提交异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            callbackState.error(e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }

    private InvocationHandler marsCallbackHandler(String fileKey, DownloadCallbackState callbackState) {
        return (proxy, method, args) -> {
            String name = method != null ? method.getName() : "";
            if ("toString".equals(name)) return "HchatMarsCdnCallback(" + fileKey + ")";
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("equals".equals(name)) {
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            if ("onC2CDownloadCompleted".equals(name)) {
                Object result = args != null && args.length > 1 ? args[1] : null;
                Object errorValue = result != null
                        ? KavaReflector.readField(result, "errorCode") : null;
                int errorCode = errorValue instanceof Number ? ((Number) errorValue).intValue() : 0;
                if (errorCode == 0) {
                    callbackState.success();
                } else {
                    callbackState.error("CDN下载失败 errorCode=" + errorCode);
                }
            } else if ("onDownloadCanceled".equals(name) || "onCanceled".equals(name)) {
                callbackState.error("CDN下载已取消");
            }
            Class<?> returnType = method != null ? method.getReturnType() : void.class;
            if (returnType == boolean.class) return false;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            if (returnType == double.class) return 0d;
            return null;
        };
    }

    private static final class DownloadCallbackState {
        private final DownloadCallback callback;
        private final String savePath;
        private final AtomicBoolean delivered = new AtomicBoolean(false);

        private DownloadCallbackState(DownloadCallback callback, String savePath) {
            this.callback = callback;
            this.savePath = savePath;
        }

        private void success() {
            if (callback == null || !delivered.compareAndSet(false, true)) return;
            File file = new File(savePath != null ? savePath : "");
            if (!file.isFile() || file.length() <= 0L) {
                delivered.set(false);
                error("CDN下载完成但目标文件未落盘");
                return;
            }
            try {
                callback.onSuccess(file);
            } catch (Throwable ignored) {
            }
        }

        private void error(String message) {
            if (callback == null || !delivered.compareAndSet(false, true)) return;
            try {
                callback.onError(message != null ? message : "CDN下载失败");
            } catch (Throwable ignored) {
            }
        }
    }

    private void callSetter(Object request, String name, Object value) {
        Method method = KavaReflector.findCompatibleMethod(request.getClass(), name, value);
        if (method != null) {
            KavaReflector.invoke(method, request, value);
        }
    }

    private Object imageCdnReceiver(Method method) {
        try {
            Method getter = dexFinder != null ? dexFinder.imageCdnServiceGetterMethod : null;
            if (getter != null && KavaReflector.isStatic(getter)) {
                Object value = KavaReflector.invoke(getter, null);
                if (value != null && method.getDeclaringClass().isInstance(value)) return value;
            }
        } catch (Throwable e) {
            log("获取图片CDN服务异常: " + e.getMessage());
        }
        Object service = WeChatInternalServices.getService(dexFinder, method.getDeclaringClass());
        if (service != null) return service;
        return null;
    }

    private String generateFileKey(String fileId) {
        if (TextUtils.isEmpty(fileId)) {
            return "hchat_cdn_empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(fileId.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("hchat_cdn_");
            for (byte b : bytes) {
                builder.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "hchat_cdn_" + Integer.toHexString(fileId.hashCode()).toLowerCase(Locale.US);
        }
    }

    private String generateFkStyleFileKey(String fileId) {
        if (TextUtils.isEmpty(fileId)) {
            return "fk_dl_0_" + System.currentTimeMillis();
        }
        return "fk_dl_" + Math.abs(fileId.hashCode()) + "_" + System.currentTimeMillis();
    }

    private Object targetFor(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return null;
        Object service = WeChatInternalServices.getService(dexFinder, method.getDeclaringClass());
        if (service != null) return service;
        return newInstance(method.getDeclaringClass());
    }

    private void writeTaskField(Object task, String name, Object value) {
        Field field = KavaReflector.findFieldRecursive(task.getClass(), name);
        if (field == null) return;
        Class<?> type = field.getType();
        Object finalValue = value;
        if ((type == int.class || type == Integer.class) && !(value instanceof Integer)) {
            finalValue = value instanceof Number ? ((Number) value).intValue() : 0;
        } else if ((type == long.class || type == Long.class) && !(value instanceof Long)) {
            finalValue = value instanceof Number ? ((Number) value).longValue() : 0L;
        } else if ((type == boolean.class || type == Boolean.class) && !(value instanceof Boolean)) {
            finalValue = false;
        }
        KavaReflector.writeField(field, task, finalValue);
    }

    private boolean canSendAsyncAppInfo() {
        return dexFinder != null
                && dexFinder.sendImageAsyncParamsClass != null
                && dexFinder.sendImageCrossParamsClass != null
                && dexFinder.sendImageAppInfoClass != null
                && dexFinder.sendImageAsyncSubmitMethod != null;
    }

    private boolean shouldUseAsyncAppInfo() {
        if (isWeChatAtLeast(8, 0, 66)) return true;
        long versionCode = hostVersionCode();
        return versionCode >= 2980L;
    }

    private boolean sendAsyncAppInfo(String talker, String imagePath, String appId) {
        try {
            Object crossParams = newInstance(dexFinder.sendImageCrossParamsClass);
            if (crossParams == null) {
                log("新版图片appid链路失败: crossParams创建失败");
                return false;
            }
            if (!writeIntField(crossParams, "a", 6)) {
                log("新版图片appid链路失败: crossParams类型字段写入失败");
                return false;
            }
            Object appInfo = newImageAppInfo(appId);
            if (appInfo == null) {
                log("新版图片appid链路失败: appinfo创建失败");
                return false;
            }
            if (!writeFieldByType(crossParams, dexFinder.sendImageAppInfoClass, appInfo)) {
                log("新版图片appid链路失败: appinfo字段写入失败");
                return false;
            }
            Constructor<?> ctor = findAsyncParamsCtor();
            Object params = KavaReflector.newInstance(ctor, imagePath, 0, currentSelfWxId(), talker, crossParams);
            if (params == null) {
                log("新版图片appid链路失败: params创建失败");
                return false;
            }
            if (!writeStringFieldByCurrentValue(params, ASYNC_DEFAULT_SOURCE, ASYNC_EXTERNAL_SOURCE)) {
                log("新版图片appid链路失败: 外部来源字段写入失败");
                return false;
            }
            Method submit = dexFinder.sendImageAsyncSubmitMethod;
            Object service = WeChatInternalServices.getService(dexFinder, submit.getDeclaringClass());
            if (!KavaReflector.isStatic(submit) && service == null) {
                log("新版图片appid链路失败: 服务不可用 " + submit.getDeclaringClass().getName());
                return false;
            }
            KavaReflector.invokeOrThrow(submit, KavaReflector.isStatic(submit) ? null : service, params);
            return true;
        } catch (Throwable e) {
            log("新版图片appid链路异常: " + e.getMessage());
            return false;
        }
    }

    private String currentSelfWxId() {
        try {
            return WeChatApis.account() != null ? safeString(WeChatApis.account().selfWxId()) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean isWeChatAtLeast(int major, int minor, int patch) {
        String version = hostVersionName();
        if (TextUtils.isEmpty(version)) return false;
        String[] parts = version.split("\\.");
        int[] target = new int[]{major, minor, patch};
        for (int i = 0; i < target.length; i++) {
            int value = i < parts.length ? parseLeadingInt(parts[i]) : 0;
            if (value > target[i]) return true;
            if (value < target[i]) return false;
        }
        return true;
    }

    private int parseLeadingInt(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) return 0;
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String hostVersionName() {
        try {
            if (WeChatApis.version() != null) {
                String version = WeChatApis.version().versionName();
                if (!TextUtils.isEmpty(version)) return version;
            }
        } catch (Throwable ignored) {}
        try {
            PackageInfo info = hostContext != null
                    ? hostContext.getPackageManager().getPackageInfo(hostContext.getPackageName(), 0)
                    : null;
            return info != null && info.versionName != null ? info.versionName : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private long hostVersionCode() {
        try {
            if (WeChatApis.version() != null) return WeChatApis.version().versionCode();
        } catch (Throwable ignored) {}
        try {
            PackageInfo info = hostContext != null
                    ? hostContext.getPackageManager().getPackageInfo(hostContext.getPackageName(), 0)
                    : null;
            if (info == null) return 0L;
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private Constructor<?> findAsyncParamsCtor() {
        Class<?> paramsClass = dexFinder.sendImageAsyncParamsClass;
        Class<?> crossClass = dexFinder.sendImageCrossParamsClass;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(paramsClass)) {
            Class<?>[] types = ctor.getParameterTypes();
            if (types.length == 5
                    && types[0] == String.class
                    && (types[1] == int.class || types[1] == Integer.class)
                    && types[2] == String.class
                    && types[3] == String.class
                    && types[4] == crossClass) {
                return KavaReflector.accessible(ctor);
            }
        }
        return null;
    }

    private Object newImageAppInfo(String appId) {
        try {
            Class<?> clazz = dexFinder.sendImageAppInfoClass;
            Object appInfo = KavaReflector.newInstance(KavaReflector.findConstructor(clazz));
            if (appInfo == null) return null;
            if (writeIndexedAppInfo(appInfo, appId)) return appInfo;
            if (writeDirectAppInfoFields(appInfo, appId)) return appInfo;
            return null;
        } catch (Throwable e) {
            log("创建图片appinfo异常: " + e.getMessage());
            return null;
        }
    }

    private boolean writeIndexedAppInfo(Object appInfo, String appId) {
        Class<?> clazz = appInfo != null ? appInfo.getClass() : null;
        Field offsetField = firstIntField(clazz);
        Object offsetValue = KavaReflector.readField(offsetField, appInfo);
        if (!(offsetValue instanceof Integer)) return false;
        Method setMethod = findIndexedSetter(clazz);
        if (setMethod == null) return false;
        int offset = ((Integer) offsetValue).intValue();
        try {
            KavaReflector.invokeOrThrow(setMethod, appInfo, offset, appId != null ? appId : "");
            KavaReflector.invokeOrThrow(setMethod, appInfo, offset + 4, "");
            KavaReflector.invokeOrThrow(setMethod, appInfo, offset + 5, "");
            KavaReflector.invokeOrThrow(setMethod, appInfo, offset + 6, "");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean writeDirectAppInfoFields(Object appInfo, String appId) {
        if (appInfo == null) return false;
        Field[] stringFields = new Field[5];
        int stringCount = 0;
        for (Field field : KavaReflector.declaredFields(appInfo.getClass())) {
            if (KavaReflector.isStatic(field)) continue;
            if (field.getType() != String.class) continue;
            if (stringCount < stringFields.length) {
                stringFields[stringCount] = KavaReflector.accessible(field);
            }
            stringCount++;
        }
        if (stringCount < 5) return false;
        return KavaReflector.writeField(stringFields[0], appInfo, appId != null ? appId : "")
                && KavaReflector.writeField(stringFields[1], appInfo, "")
                && KavaReflector.writeField(stringFields[2], appInfo, "")
                && KavaReflector.writeField(stringFields[3], appInfo, "")
                && KavaReflector.writeField(stringFields[4], appInfo, "");
    }

    private Field firstIntField(Class<?> clazz) {
        for (Field field : KavaReflector.declaredFields(clazz)) {
            if (!KavaReflector.isStatic(field)
                    && (field.getType() == int.class || field.getType() == Integer.class)) {
                return KavaReflector.accessible(field);
            }
        }
        return null;
    }

    private Method findIndexedSetter(Class<?> clazz) {
        Method fallback = null;
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : KavaReflector.declaredMethods(current)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && (params[0] == int.class || params[0] == Integer.class)
                        && params[1] == Object.class) {
                    if ("set".equals(method.getName())) {
                        return KavaReflector.accessible(method);
                    }
                    if (fallback == null) fallback = KavaReflector.accessible(method);
                }
            }
            current = current.getSuperclass();
        }
        return fallback;
    }

    private boolean writeIntField(Object target, String name, int value) {
        Field field = KavaReflector.findField(target.getClass(), name);
        if (field == null || (field.getType() != int.class && field.getType() != Integer.class)) {
            field = firstIntField(target.getClass());
        }
        return KavaReflector.writeField(field, target, value);
    }

    private boolean writeFieldByType(Object target, Class<?> type, Object value) {
        if (target == null || type == null) return false;
        for (Field field : KavaReflector.declaredFields(target.getClass())) {
            if (field.getType() == type) {
                return KavaReflector.writeField(field, target, value);
            }
        }
        return false;
    }

    private boolean writeStringFieldByCurrentValue(Object target, String currentValue, String newValue) {
        if (target == null) return false;
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                if (KavaReflector.isStatic(field) || field.getType() != String.class) continue;
                Object value = KavaReflector.readField(field, target);
                if (currentValue.equals(value)) {
                    return KavaReflector.writeField(field, target, newValue);
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private Object[] buildSendImageArgs(Method method, String talker, String imagePath, String appId) {
        return buildSendImageArgs(method, talker, imagePath, appId, false);
    }

    private Object[] buildSendImageArgs(Method method, String talker, String imagePath, String appId, boolean original) {
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        String appInfoXml = imageAppInfoXml(appId);
        int appInfoIndex = imageAppInfoIndex(params);
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i];
            if (i == 0 && Context.class.isAssignableFrom(type)) {
                args[i] = hostContext;
            } else if (i == 1 && type == String.class) {
                args[i] = talker;
            } else if (i == 2 && type == String.class) {
                args[i] = imagePath;
            } else if (i == appInfoIndex) {
                args[i] = appInfoXml;
            } else if (type == int.class || type == Integer.class) {
                args[i] = original && i == 3 ? 1 : 0;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == String.class) {
                args[i] = "";
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private int imageAppInfoIndex(Class<?>[] params) {
        return params.length == 8 && params[5] == String.class ? 5 : -1;
    }

    private String imageAppInfoXml(String appId) {
        if (TextUtils.isEmpty(appId)) return "";
        return "<msg><appinfo><appid>" + escapeXml(appId) + "</appid></appinfo></msg>";
    }

    private String escapeXml(String value) {
        if (value == null || value.length() == 0) return "";
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&') {
                builder.append("&amp;");
            } else if (c == '<') {
                builder.append("&lt;");
            } else if (c == '>') {
                builder.append("&gt;");
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private String safeString(String value) {
        return value != null ? value : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatImageApi] " + message);
    }

    private static void logStatic(Logger logger, String message) {
        if (logger != null) logger.log("[WeChatImageApi] " + message);
    }
}
