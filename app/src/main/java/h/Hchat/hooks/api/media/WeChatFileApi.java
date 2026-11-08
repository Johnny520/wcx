package h.Hchat.hooks.api.media;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.utils.KavaReflector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 微信文件发送 API。
 *
 * 走微信内部 AppMsgLogic 高层入口，静默插入 type=49 文件消息、创建 appattach
 * 记录并触发 uploadappattach，不经过文件选择/分享 UI。
 */
public final class WeChatFileApi {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final Logger logger;

    public WeChatFileApi(DexFinder dexFinder, Logger logger) {
        this.dexFinder = dexFinder;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return canSendSilently();
    }

    public boolean canSendSilently() {
        return dexFinder != null && dexFinder.sendFileMethod != null;
    }

    public boolean send(String talker, String filePath) {
        return send(talker, filePath, "");
    }

    public boolean send(String talker, String filePath, String title) {
        return send(talker, filePath, title, "");
    }

    public boolean send(String talker, String filePath, String title, String description) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(filePath)) {
            log("发送文件失败: talker/filePath为空");
            return false;
        }
        File file = new File(filePath);
        if (!file.isFile()) {
            log("发送文件失败: 文件不存在 " + filePath);
            return false;
        }
        if (!canSendSilently()) {
            log("发送文件失败: API未就绪");
            return false;
        }
        try {
            Method method = dexFinder.sendFileMethod;
            File attachFile = prepareAttachFile(method, file);
            if (attachFile == null || !attachFile.isFile()) {
                log("发送文件失败: 准备附件失败");
                return false;
            }
            Object mediaMessage = newWxMediaMessage(method, attachFile, title, description);
            if (mediaMessage == null) {
                log("发送文件失败: WXMediaMessage创建失败");
                return false;
            }
            String session = "Hchat_file_" + System.currentTimeMillis();
            Object result = KavaReflector.invoke(method, null, mediaMessage, "wx4310bbd51be7d979",
                    "WeChat", talker, 2, session);
            if (result instanceof Number && ((Number) result).intValue() < 0) {
                log("发送文件失败: AppMsgLogic返回 " + result
                        + " talker=" + talker
                        + " size=" + attachFile.length()
                        + " attach=" + attachFile.getAbsolutePath());
                return false;
            }
            return true;
        } catch (Throwable e) {
            log("发送文件异常: " + e.getMessage());
            return false;
        }
    }

    public boolean sendMediaMessage(String talker, Object mediaMessage, String appId) {
        if (TextUtils.isEmpty(talker) || mediaMessage == null) {
            log("发送媒体消息失败: talker/mediaMessage为空");
            return false;
        }
        if (!canSendSilently()) {
            log("发送媒体消息失败: API未就绪");
            return false;
        }
        try {
            Method method = dexFinder.sendFileMethod;
            Class<?> messageClass = method.getParameterTypes()[0];
            if (!messageClass.isInstance(mediaMessage)) {
                log("发送媒体消息失败: mediaMessage类型不匹配 " + mediaMessage.getClass().getName());
                return false;
            }
            String safeAppId = TextUtils.isEmpty(appId) ? "wx4310bbd51be7d979" : appId;
            String session = "Hchat_media_" + System.currentTimeMillis();
            Object result = KavaReflector.invoke(
                    method,
                    null,
                    mediaMessage,
                    safeAppId,
                    "WeChat",
                    talker,
                    2,
                    session
            );
            if (result instanceof Number && ((Number) result).intValue() < 0) {
                log("发送媒体消息失败: AppMsgLogic返回 " + result + " talker=" + talker);
                return false;
            }
            return true;
        } catch (Throwable e) {
            log("发送媒体消息异常: " + e.getMessage());
            return false;
        }
    }

    public boolean shareFile(String talker, String title, String filePath, String appId) {
        return send(talker, filePath, title, "");
    }

    public boolean shareText(String talker, String text, String appId) {
        Object mediaMessage = newMediaMessageFromObject(
                "com.tencent.mm.opensdk.modelmsg.WXTextObject",
                fields(
                        "text", text
                ),
                fields(
                        "description", text
                )
        );
        return mediaMessage != null && sendMediaMessage(talker, mediaMessage, appId);
    }

    public boolean shareWebpage(String talker,
                                String title,
                                String description,
                                String webpageUrl,
                                byte[] thumbData,
                                String appId) {
        Object mediaMessage = newMediaMessageFromObject(
                "com.tencent.mm.opensdk.modelmsg.WXWebpageObject",
                fields(
                        "webpageUrl", webpageUrl
                ),
                fields(
                        "title", title,
                        "description", description,
                        "thumbData", thumbData
                )
        );
        return mediaMessage != null && sendMediaMessage(talker, mediaMessage, appId);
    }

    public boolean shareVideo(String talker,
                              String title,
                              String description,
                              String videoUrl,
                              byte[] thumbData,
                              String appId) {
        Object mediaMessage = newMediaMessageFromObject(
                "com.tencent.mm.opensdk.modelmsg.WXVideoObject",
                fields(
                        "videoUrl", videoUrl
                ),
                fields(
                        "title", title,
                        "description", description,
                        "thumbData", thumbData
                )
        );
        return mediaMessage != null && sendMediaMessage(talker, mediaMessage, appId);
    }

    public boolean shareMusic(String talker,
                              String title,
                              String description,
                              String musicUrl,
                              String musicDataUrl,
                              byte[] thumbData,
                              String appId) {
        return shareMusicWithMetadata(
                talker, title, description, musicUrl, musicDataUrl, "", "", thumbData, appId);
    }

    public boolean shareMusicWithMetadata(String talker,
                                          String title,
                                          String description,
                                          String musicUrl,
                                          String musicDataUrl,
                                          String songLyric,
                                          String songAlbumUrl,
                                          byte[] thumbData,
                                          String appId) {
        Object mediaMessage = newMediaMessageFromObject(
                "com.tencent.mm.opensdk.modelmsg.WXMusicObject",
                fields(
                        "musicUrl", musicUrl,
                        "musicDataUrl", musicDataUrl,
                        "songLyric", songLyric,
                        "songAlbumUrl", songAlbumUrl
                ),
                fields(
                        "title", title,
                        "description", description,
                        "thumbData", thumbData
                )
        );
        return mediaMessage != null && sendMediaMessage(talker, mediaMessage, appId);
    }

    public boolean shareMusicVideo(String talker,
                                   String title,
                                   String description,
                                   String musicUrl,
                                   String musicDataUrl,
                                   String singerName,
                                   int duration,
                                   String songLyric,
                                   byte[] thumbData,
                                   String appId) {
        Object mediaMessage = newMediaMessageFromObject(
                "com.tencent.mm.opensdk.modelmsg.WXMusicVideoObject",
                fields(
                        "musicUrl", musicUrl,
                        "musicDataUrl", musicDataUrl,
                        "singerName", singerName,
                        "duration", duration,
                        "songLyric", songLyric
                ),
                fields(
                        "title", title,
                        "description", description,
                        "thumbData", thumbData
                )
        );
        return mediaMessage != null && sendMediaMessage(talker, mediaMessage, appId);
    }

    public boolean shareMiniProgram(String talker,
                                    String title,
                                    String description,
                                    String userName,
                                    String path,
                                    byte[] thumbData,
                                    String appId) {
        Object mediaMessage = newMediaMessageFromObject(
                "com.tencent.mm.opensdk.modelmsg.WXMiniProgramObject",
                fields(
                        "userName", userName,
                        "path", path,
                        "webpageUrl", fallbackMiniProgramWebpage(path, userName),
                        "miniprogramType", 0
                ),
                fields(
                        "title", title,
                        "description", description,
                        "thumbData", thumbData
                )
        );
        return mediaMessage != null && sendMediaMessage(talker, mediaMessage, appId);
    }

    private File prepareAttachFile(Method sendMethod, File source) {
        try {
            String attachDir = defaultAttachDir(sendMethod.getDeclaringClass());
            if (TextUtils.isEmpty(attachDir)) {
                return source;
            }
            String targetPath = attachPath(sendMethod.getDeclaringClass(), attachDir,
                    source.getName(), extension(source.getName()));
            if (TextUtils.isEmpty(targetPath)) {
                targetPath = appendPath(attachDir, source.getName());
            }
            File target = new File(targetPath);
            if (sameFile(source, target)) {
                return source;
            }
            if (!copyFile(source, target)) {
                return null;
            }
            return target;
        } catch (Throwable e) {
            log("准备附件异常: " + e.getMessage());
            return null;
        }
    }

    private String defaultAttachDir(Class<?> appMsgLogicClass) {
        Method cached = dexFinder.sendFileAttachDirMethod;
        String value = invokeString(cached);
        if (looksLikeAttachDir(value)) return ensureSlash(value);
        try {
            for (Method method : KavaReflector.declaredMethods(appMsgLogicClass)) {
                if (!KavaReflector.isStatic(method)
                        || method.getReturnType() != String.class
                        || method.getParameterTypes().length != 0) {
                    continue;
                }
                value = invokeString(method);
                if (looksLikeAttachDir(value)) {
                    return ensureSlash(value);
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String attachPath(Class<?> appMsgLogicClass, String dir, String name, String ext) {
        Method cached = dexFinder.sendFileAttachPathMethod;
        String value = invokeAttachPath(cached, dir, name, ext);
        if (!TextUtils.isEmpty(value)) return value;
        try {
            for (Method method : KavaReflector.declaredMethods(appMsgLogicClass)) {
                Class<?>[] params = method.getParameterTypes();
                if (!KavaReflector.isStatic(method)
                        || method.getReturnType() != String.class
                        || params.length != 3
                        || params[0] != String.class
                        || params[1] != String.class
                        || params[2] != String.class) {
                    continue;
                }
                value = invokeAttachPath(method, dir, name, ext);
                if (!TextUtils.isEmpty(value)) return value;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private String invokeString(Method method) {
        if (method == null) return "";
        try {
            Object result = KavaReflector.invoke(method, null);
            return result instanceof String ? (String) result : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String invokeAttachPath(Method method, String dir, String name, String ext) {
        if (method == null) return "";
        try {
            Object result = KavaReflector.invoke(method, null, ensureSlash(dir), name, ext);
            return result instanceof String ? (String) result : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private boolean looksLikeAttachDir(String value) {
        if (TextUtils.isEmpty(value)) return false;
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("attachment") || lower.contains("appattach") || lower.contains("app_attach");
    }

    private String ensureSlash(String value) {
        if (TextUtils.isEmpty(value)) return "";
        return value.endsWith("/") ? value : value + "/";
    }

    private String appendPath(String dir, String name) {
        return ensureSlash(dir) + name;
    }

    private String extension(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot >= name.length() - 1) return "";
        return name.substring(dot + 1);
    }

    private boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (Throwable ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
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
            if (target.isFile() && target.length() == source.length()) {
                return true;
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
            log("复制附件异常: " + e.getMessage());
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

    private Object newWxMediaMessage(Method sendMethod, File file, String title, String description) {
        try {
            ClassLoader loader = sendMethod.getDeclaringClass().getClassLoader();
            Class<?> messageClass = sendMethod.getParameterTypes()[0];
            Class<?> fileObjectClass = KavaReflector.loadClass(
                    "com.tencent.mm.opensdk.modelmsg.WXFileObject", loader);
            Object fileObject = newWxFileObject(fileObjectClass, file.getAbsolutePath());
            if (fileObject == null) return null;

            Object message = null;
            for (Constructor<?> ctor : KavaReflector.declaredConstructors(messageClass)) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 1 || !params[0].isAssignableFrom(fileObjectClass)) continue;
                message = KavaReflector.newInstance(ctor, fileObject);
                break;
            }
            if (message == null) {
                message = KavaReflector.newInstance(KavaReflector.findConstructor(messageClass));
                setField(message, "mediaObject", fileObject);
            }

            String safeTitle = !TextUtils.isEmpty(title) ? title : file.getName();
            setField(message, "title", limit(safeTitle, 512));
            if (!TextUtils.isEmpty(description)) {
                setField(message, "description", limit(description, 1024));
            }
            return message;
        } catch (Throwable e) {
            log("创建WXMediaMessage异常: " + e.getMessage());
            return null;
        }
    }

    private Object newMediaMessageFromObject(String objectClassName,
                                             Object[][] objectFields,
                                             Object[][] messageFields) {
        if (dexFinder == null || dexFinder.sendFileMethod == null) {
            log("创建媒体消息失败: sendFileMethod未就绪");
            return null;
        }
        try {
            Method method = dexFinder.sendFileMethod;
            ClassLoader loader = method.getDeclaringClass().getClassLoader();
            Class<?> objectClass = KavaReflector.loadClass(objectClassName, loader);
            if (objectClass == null) {
                log("创建媒体消息失败: 未找到 " + objectClassName);
                return null;
            }
            Object mediaObject = newOpenSdkObject(objectClass, objectFields);
            if (mediaObject == null) {
                log("创建媒体消息失败: 构造mediaObject失败 " + objectClassName);
                return null;
            }
            return newWxMediaMessage(method, mediaObject, messageFields);
        } catch (Throwable e) {
            log("创建媒体消息异常: " + e.getMessage());
            return null;
        }
    }

    private Object newWxMediaMessage(Method sendMethod, Object mediaObject, Object[][] messageFields) {
        if (sendMethod == null || mediaObject == null) return null;
        try {
            Class<?> messageClass = sendMethod.getParameterTypes()[0];
            Object message = null;
            for (Constructor<?> ctor : KavaReflector.declaredConstructors(messageClass)) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != 1 || !params[0].isAssignableFrom(mediaObject.getClass())) continue;
                message = KavaReflector.newInstance(ctor, mediaObject);
                break;
            }
            if (message == null) {
                message = KavaReflector.newInstance(KavaReflector.findConstructor(messageClass));
                setField(message, "mediaObject", mediaObject);
            }
            applyFields(message, messageFields);
            return message;
        } catch (Throwable e) {
            log("构造WXMediaMessage失败: " + e.getMessage());
            return null;
        }
    }

    private Object newOpenSdkObject(Class<?> objectClass, Object[][] fields) {
        if (objectClass == null) return null;
        Object instance = KavaReflector.newInstance(KavaReflector.findConstructor(objectClass));
        if (instance == null) {
            instance = KavaReflector.newInstanceByArgs(objectClass, new Object[0]);
        }
        if (instance == null) return null;
        applyFields(instance, fields);
        return instance;
    }

    private void applyFields(Object target, Object[][] fields) {
        if (target == null || fields == null) return;
        for (Object[] entry : fields) {
            if (entry == null || entry.length < 2) continue;
            String name = entry[0] instanceof String ? (String) entry[0] : null;
            Object value = sanitizeFieldValue(entry[1]);
            if (name == null || value == SkipValue.INSTANCE) continue;
            setField(target, name, value);
        }
    }

    private Object sanitizeFieldValue(Object value) {
        if (value == null) return SkipValue.INSTANCE;
        if (value instanceof String && TextUtils.isEmpty((String) value)) return SkipValue.INSTANCE;
        if (value instanceof byte[] && ((byte[]) value).length == 0) return SkipValue.INSTANCE;
        return value;
    }

    private String fallbackMiniProgramWebpage(String path, String userName) {
        if (!TextUtils.isEmpty(path)) return "https://servicewechat.com/" + safeMiniProgramUser(userName) + "/0/page-frame.html";
        if (!TextUtils.isEmpty(userName)) return "https://servicewechat.com/" + safeMiniProgramUser(userName) + "/0/page-frame.html";
        return "https://weixin.qq.com/";
    }

    private String safeMiniProgramUser(String userName) {
        if (TextUtils.isEmpty(userName)) return "";
        String value = userName.trim();
        return value.endsWith("@app") ? value.substring(0, value.length() - 4) : value;
    }

    private Object[][] fields(Object... values) {
        if (values == null || values.length == 0) return new Object[0][0];
        int count = values.length / 2;
        Object[][] pairs = (Object[][]) Array.newInstance(Object.class, count, 2);
        for (int i = 0; i < count; i++) {
            pairs[i][0] = values[i * 2];
            pairs[i][1] = values[i * 2 + 1];
        }
        return pairs;
    }

    private Object newWxFileObject(Class<?> fileObjectClass, String path) {
        try {
            return KavaReflector.newInstance(
                    KavaReflector.findConstructor(fileObjectClass, String.class), path);
        } catch (Throwable ignored) {
        }
        try {
            Object fileObject = KavaReflector.newInstance(KavaReflector.findConstructor(fileObjectClass));
            setField(fileObject, "filePath", path);
            return fileObject;
        } catch (Throwable e) {
            log("创建WXFileObject异常: " + e.getMessage());
            return null;
        }
    }

    private void setField(Object target, String name, Object value) {
        if (target == null) return;
        try {
            Field field = KavaReflector.findField(target.getClass(), name);
            KavaReflector.writeField(field, target, value);
        } catch (Throwable ignored) {
        }
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatFileApi] " + message);
    }

    private enum SkipValue {
        INSTANCE
    }
}
