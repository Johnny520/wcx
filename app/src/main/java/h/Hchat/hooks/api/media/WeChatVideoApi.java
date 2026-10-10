package h.Hchat.hooks.api.media;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.AsyncTask;
import android.text.TextUtils;
import android.util.Xml;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.ui.WeChatCurrentActivityApi;
import h.Hchat.utils.KavaReflector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.xmlpull.v1.XmlPullParser;

/**
 * 微信视频发送 API。
 *
 * 直接执行微信内部 CopyVideoTask，不经过会创建 Dialog 的 SendMsgMgr 高层入口。
 */
public final class WeChatVideoApi {
    public static final class DownloadInfo {
        private final String md5;
        private final String cdnUrl;
        private final String aesKey;
        private final long totalLength;
        private final long currentLength;

        private DownloadInfo(String md5,
                             String cdnUrl,
                             String aesKey,
                             long totalLength,
                             long currentLength) {
            this.md5 = md5;
            this.cdnUrl = cdnUrl;
            this.aesKey = aesKey;
            this.totalLength = totalLength;
            this.currentLength = currentLength;
        }

        public String getMd5() {
            return md5;
        }

        public String getCdnUrl() {
            return cdnUrl;
        }

        public String getAesKey() {
            return aesKey;
        }

        public long getTotalLength() {
            return totalLength;
        }

        public long getCurrentLength() {
            return currentLength;
        }
    }

    public interface Logger {
        void log(String message);
    }

    public interface DownloadCallback {
        void onSuccess(File file);

        void onError(String message);
    }

    private final Context hostContext;
    private final DexFinder dexFinder;
    private final WeChatCurrentActivityApi currentActivityApi;
    private final WeChatImageApi cdnDownloadApi;
    private final Logger logger;
    private volatile Method videoInfoValuesMethod;

    public WeChatVideoApi(Context hostContext,
                          DexFinder dexFinder,
                          WeChatCurrentActivityApi currentActivityApi,
                          WeChatImageApi cdnDownloadApi,
                          Logger logger) {
        this.hostContext = hostContext;
        this.dexFinder = dexFinder;
        this.currentActivityApi = currentActivityApi;
        this.cdnDownloadApi = cdnDownloadApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return canSendSilently();
    }

    public boolean canSendSilently() {
        return hostContext != null
                && dexFinder != null
                && dexFinder.sendVideoTaskClass != null;
    }

    public String resolvePathToken(String token) {
        if (TextUtils.isEmpty(token)) {
            return "";
        }
        File direct = new File(token);
        if (direct.isFile()) {
            return direct.getAbsolutePath();
        }
        Method method = dexFinder != null ? dexFinder.videoPathMethod : null;
        if (method == null) {
            return "";
        }
        try {
            boolean isStatic = KavaReflector.isStatic(method);
            Object receiver = isStatic ? null : videoPathReceiver(method.getDeclaringClass());
            if (!isStatic && receiver == null) return "";
            Object value = KavaReflector.invoke(method, receiver, token);
            if (!(value instanceof String)) {
                return "";
            }
            String resolvedPath = (String) value;
            return materializeVideoPath(
                    resolvedPath,
                    method.getDeclaringClass().getClassLoader());
        } catch (Throwable e) {
            log("解析视频消息路径失败: " + e.getMessage());
            return "";
        }
    }

    public DownloadInfo resolveDownloadInfo(String imgPath) {
        Method query = dexFinder != null ? dexFinder.videoInfoByFileNameMethod : null;
        if (TextUtils.isEmpty(imgPath) || query == null || !KavaReflector.isStatic(query)) {
            return null;
        }
        try {
            Object videoInfo = KavaReflector.invoke(query, null, imgPath);
            if (videoInfo == null) return null;
            Method valuesMethod = videoInfoValuesMethod(videoInfo.getClass());
            ContentValues values = (ContentValues) KavaReflector.invoke(valuesMethod, videoInfo);
            if (values == null) return null;

            Map<String, String> xmlValues = parseVideoXml(values.getAsString("reserved4"));
            String md5 = firstNotEmpty(
                    xmlValues.get("md5"),
                    xmlValues.get("newmd5"),
                    values.getAsString("videomd5"));
            String cdnUrl = firstNotEmpty(xmlValues.get("cdnvideourl"));
            String aesKey = firstNotEmpty(xmlValues.get("aeskey"));
            long totalLength = firstPositive(
                    positiveLong(xmlValues.get("length")),
                    positiveLong(xmlValues.get("totallen")),
                    positiveLong(values.getAsLong("totallen")));
            long currentLength = positiveLong(values.getAsLong("filenowsize"));
            if (TextUtils.isEmpty(md5)
                    && TextUtils.isEmpty(cdnUrl)
                    && TextUtils.isEmpty(aesKey)
                    && totalLength <= 0L
                    && currentLength <= 0L) {
                return null;
            }
            return new DownloadInfo(md5, cdnUrl, aesKey, totalLength, currentLength);
        } catch (Throwable e) {
            log("读取视频下载信息失败: " + e.getMessage());
            return null;
        }
    }

    public boolean downloadCdn(
            String md5,
            String cdnUrl,
            String aesKey,
            String savePath,
            DownloadCallback callback
    ) {
        if (cdnDownloadApi == null) {
            if (callback != null) callback.onError("CDN API未就绪");
            return false;
        }
        return cdnDownloadApi.downloadCdn(
                md5,
                cdnUrl,
                aesKey,
                savePath,
                VIDEO_CDN_FILE_TYPE,
                new WeChatImageApi.DownloadCallback() {
                    @Override
                    public void onSuccess(File file) {
                        if (callback != null) callback.onSuccess(file);
                    }

                    @Override
                    public void onError(String message) {
                        if (callback != null) callback.onError(message);
                    }
                });
    }

    private Object videoPathReceiver(Class<?> owner) {
        Object service = WeChatInternalServices.getService(dexFinder, owner);
        if (service != null) {
            return service;
        }
        Method getter = dexFinder != null ? dexFinder.videoPathOwnerGetterMethod : null;
        if (getter == null
                || !KavaReflector.isStatic(getter)
                || getter.getParameterTypes().length != 0
                || !owner.isAssignableFrom(getter.getReturnType())) {
            return null;
        }
        Object receiver = KavaReflector.invoke(getter, null);
        return owner.isInstance(receiver) ? receiver : null;
    }

    private Method videoInfoValuesMethod(Class<?> videoInfoClass) {
        Method cached = videoInfoValuesMethod;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(videoInfoClass)) {
            return cached;
        }
        Method selected = null;
        for (Method method : KavaReflector.declaredMethods(videoInfoClass)) {
            if (method.getParameterTypes().length != 0
                    || method.getReturnType() != ContentValues.class) {
                continue;
            }
            if (selected != null) return null;
            selected = KavaReflector.accessible(method);
        }
        videoInfoValuesMethod = selected;
        return selected;
    }

    private Map<String, String> parseVideoXml(String source) {
        Map<String, String> values = new LinkedHashMap<>();
        if (TextUtils.isEmpty(source)) return values;
        int xmlStart = source.indexOf('<');
        if (xmlStart < 0) return values;
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(new StringReader(source.substring(xmlStart)));
            String currentTag = null;
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    currentTag = normalizeXmlName(parser.getName());
                    for (int index = 0; index < parser.getAttributeCount(); index++) {
                        putXmlValue(
                                values,
                                normalizeXmlName(parser.getAttributeName(index)),
                                parser.getAttributeValue(index));
                    }
                } else if (event == XmlPullParser.TEXT && currentTag != null) {
                    putXmlValue(values, currentTag, parser.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    currentTag = null;
                }
                event = parser.next();
            }
        } catch (Throwable e) {
            log("解析视频reserved4失败: " + e.getMessage());
        }
        return values;
    }

    private void putXmlValue(Map<String, String> values, String name, String value) {
        String normalizedValue = value != null ? value.trim() : "";
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(normalizedValue) || values.containsKey(name)) return;
        values.put(name, normalizedValue);
    }

    private String normalizeXmlName(String name) {
        return name != null ? name.trim().toLowerCase(java.util.Locale.ROOT) : "";
    }

    private String firstNotEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private long firstPositive(long... values) {
        if (values == null) return 0L;
        for (long value : values) {
            if (value > 0L) return value;
        }
        return 0L;
    }

    private long positiveLong(Object value) {
        if (value instanceof Number) return Math.max(0L, ((Number) value).longValue());
        if (value == null) return 0L;
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value).trim()));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private synchronized String materializeVideoPath(String path, ClassLoader classLoader) {
        if (TextUtils.isEmpty(path)) return "";
        File direct = new File(path);
        if (direct.isFile()) return direct.getAbsolutePath();

        File dir = new File(hostContext.getCacheDir(), "Hchat_message_video");
        if (!dir.isDirectory() && !dir.mkdirs()) return "";
        File target = new File(dir, "video_" + Integer.toHexString(path.hashCode()) + ".mp4");
        if (target.isFile() && target.length() > 0L) return target.getAbsolutePath();

        InputStream input = openVfsInputStream(classLoader, path);
        if (input == null) {
            return "";
        }
        try (InputStream stream = input; FileOutputStream output = new FileOutputStream(target, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        } catch (Throwable e) {
            if (target.exists()) target.delete();
            log("读取视频VFS路径异常: " + e.getMessage());
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

    public boolean send(String talker, String videoPath) {
        return send(talker, videoPath, "");
    }

    public boolean send(String talker, String videoPath, String thumbPath) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(videoPath)) {
            log("发送视频失败: talker/videoPath为空");
            return false;
        }
        File file = new File(videoPath);
        if (!file.isFile()) {
            log("发送视频失败: 文件不存在 " + videoPath);
            return false;
        }
        if (!isAvailable()) {
            log("发送视频失败: API未就绪");
            return false;
        }
        try {
            String realThumbPath = ensureThumbPath(videoPath, thumbPath);
            if (TextUtils.isEmpty(realThumbPath)) {
                log("发送视频失败: 缩略图不可用");
                return false;
            }
            Object task = newTask();
            if (task == null) return false;

            setField(task, "a", null);
            setField(task, "b", sendContext());
            setField(task, "c", false);
            setField(task, "d", 0);
            setField(task, "e", videoDurationSeconds(videoPath));
            setField(task, "f", videoPath);
            setField(task, "g", realThumbPath);
            setField(task, "h", talker);
            setField(task, "i", "");
            setField(task, "p", "");
            setField(task, "r", "");
            setField(task, "s", "");
            setField(task, "j", false);
            setField(task, "l", false);
            setField(task, "m", false);

            executeTask(task);
            return true;
        } catch (Throwable e) {
            log("发送视频异常: " + e.getMessage());
            return false;
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatVideoApi] " + message);
    }

    private Context sendContext() {
        Activity activity = currentActivityApi != null ? currentActivityApi.currentActivity() : null;
        return activity != null ? activity : hostContext;
    }

    private Object newTask() {
        Class<?> clazz = dexFinder != null ? dexFinder.sendVideoTaskClass : null;
        if (clazz == null) return null;
        try {
            Object task = KavaReflector.newInstance(KavaReflector.findConstructor(clazz));
            if (!(task instanceof AsyncTask)) {
                log("发送视频失败: Task类型不匹配 " + clazz.getName());
                return null;
            }
            return task;
        } catch (Throwable e) {
            log("创建视频发送Task失败: " + clazz.getName() + " " + e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void executeTask(Object task) {
        ((AsyncTask) task).execute(new Object[0]);
    }

    private void setField(Object target, String name, Object value) {
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        try {
            Class<?> type = field.getType();
            Object next = value;
            if (type == boolean.class) next = value instanceof Boolean && (Boolean) value;
            else if (type == int.class) next = value instanceof Number ? ((Number) value).intValue() : 0;
            else if (type == long.class) next = value instanceof Number ? ((Number) value).longValue() : 0L;
            if (next == null || type.isPrimitive() || type.isInstance(next)) KavaReflector.writeField(field, target, next);
        } catch (Throwable ignored) {
        }
    }

    private Field findField(Class<?> clazz, String name) {
        return KavaReflector.findFieldRecursive(clazz, name);
    }

    private String ensureThumbPath(String videoPath, String thumbPath) {
        if (!TextUtils.isEmpty(thumbPath) && new File(thumbPath).isFile()) {
            return thumbPath;
        }
        return generateThumb(videoPath);
    }

    private int videoDurationSeconds(String videoPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(videoPath);
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (TextUtils.isEmpty(duration)) return 0;
            long millis = Long.parseLong(duration);
            return (int) Math.max(0, (millis + 999L) / 1000L);
        } catch (Throwable ignored) {
            return 0;
        } finally {
            releaseRetriever(retriever);
        }
    }

    private String generateThumb(String videoPath) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap bitmap = null;
        FileOutputStream out = null;
        try {
            retriever.setDataSource(videoPath);
            bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bitmap == null) return "";

            File dir = new File(hostContext.getCacheDir(), "Hchat_media");
            if (!dir.isDirectory() && !dir.mkdirs()) return "";
            File file = new File(dir, "video_thumb_" + Integer.toHexString(videoPath.hashCode()) + ".jpg");
            out = new FileOutputStream(file);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return "";
            out.flush();
            return file.getAbsolutePath();
        } catch (Throwable e) {
            log("生成视频缩略图失败: " + e.getMessage());
            return "";
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
            releaseRetriever(retriever);
        }
    }

    private void releaseRetriever(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Throwable ignored) {
        }
    }

    private static final int VIDEO_CDN_FILE_TYPE = 4;
}
