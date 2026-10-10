package h.Hchat.hooks.api.media;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.utils.KavaReflector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/**
 * 微信表情发送 API。
 *
 * 走微信内部 EmojiFeatureService#send 链路，插入 type=47 消息并发起 NetSceneUploadEmoji。
 * 传入本地文件路径时，会复制到微信表情目录并创建 EmojiInfo 后静默发送。
 */
public final class WeChatEmojiApi {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final Logger logger;
    private final Context hostContext;
    private Method wxamToGifMethod;
    private boolean wxamToGifResolved;
    private static final int MESSAGE_EMOJI_CATALOG = 0;
    private static final int TRANSIENT_SEND_EMOJI_CATALOG = 65;
    private static final int EMOJI_TYPE_STATIC = 1;
    private static final int EMOJI_TYPE_GIF = 2;
    private static final String EMOTION_PROVIDER = ".storage.provider.emotion";

    public WeChatEmojiApi(Context hostContext, DexFinder dexFinder, Logger logger) {
        this.hostContext = hostContext;
        this.dexFinder = dexFinder;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return canSendSilently();
    }

    public boolean canSendSilently() {
        return dexFinder != null
                && dexFinder.emojiSendMethod != null
                && (dexFinder.emojiGetByMd5Method != null || hostContext != null);
    }

    public boolean canSendLocalPathSilently() {
        return canSendSilently()
                && (dexFinder.emojiAccPathMethod != null || hostContext != null);
    }

    public boolean send(String talker, String emojiPathOrMd5) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(emojiPathOrMd5)) {
            log("发表情失败: talker/emoji为空");
            return false;
        }
        if (!canSendSilently()) {
            log("发表情失败: API未就绪");
            return false;
        }
        try {
            File localFile = new File(emojiPathOrMd5);
            if (localFile.isFile()) {
                return sendLocalFile(talker, localFile);
            }
            String md5 = normalizeMd5(emojiPathOrMd5);
            if (TextUtils.isEmpty(md5)) {
                log("发表情失败: 不是有效md5，且文件不存在");
                return false;
            }
            Object emojiInfo = getEmojiInfo(md5);
            if (emojiInfo == null) {
                log("发表情失败: 微信表情库不存在 " + md5);
                return false;
            }
            if (sendByManager(talker, md5)) return true;
            return sendEmojiInfo(talker, emojiInfo);
        } catch (Throwable e) {
            log("发表情异常: " + e.getMessage());
            return false;
        }
    }

    public boolean sendByMd5(String talker, String md5) {
        return send(talker, md5);
    }

    public boolean sendLocalPath(String talker, String path) {
        if (TextUtils.isEmpty(path)) return false;
        return send(talker, path);
    }

    /** Resolves an emoji message path or md5 to the original local emoji file. */
    public String resolvePath(String emojiPathOrMd5) {
        if (TextUtils.isEmpty(emojiPathOrMd5)) return "";
        File direct = new File(emojiPathOrMd5);
        if (direct.isFile() && direct.length() > 0L) return direct.getAbsolutePath();
        String md5 = normalizeMd5(emojiPathOrMd5);
        if (TextUtils.isEmpty(md5)) return "";
        try {
            String basePath = normalizedEmojiBasePath();
            if (TextUtils.isEmpty(basePath)) return "";
            String path = emojiFilePath(basePath, "", md5);
            if (!TextUtils.isEmpty(path)) {
                File file = new File(path);
                if (file.isFile() && file.length() > 0L) return file.getAbsolutePath();
            }
            Object emojiInfo = getEmojiInfo(md5);
            String groupId = emojiInfo != null
                    ? readString(emojiInfo, "getGroupId", "field_groupId") : "";
            if (!TextUtils.isEmpty(groupId)) {
                path = emojiFilePath(basePath, groupId, md5);
                File file = new File(path);
                if (file.isFile() && file.length() > 0L) return file.getAbsolutePath();
            }
        } catch (Throwable e) {
            log("解析表情文件路径失败: " + e.getMessage());
        }
        return "";
    }

    /** Decodes a cached emoji to usable image bytes, including encrypted WXAM stickers. */
    public byte[] decodeData(String emojiPathOrMd5) {
        if (TextUtils.isEmpty(emojiPathOrMd5)) return null;
        File direct = new File(emojiPathOrMd5);
        byte[] directBytes = readFileBytes(direct);
        if (isKnownImage(directBytes)) return directBytes;

        String md5 = normalizeMd5(emojiPathOrMd5);
        if (TextUtils.isEmpty(md5) && direct.isFile()) {
            md5 = normalizeMd5(direct.getName());
        }
        try {
            if (!TextUtils.isEmpty(md5)) {
                Object emojiInfo = getEmojiInfo(md5);
                byte[] decoded = decodeEmojiInfo(emojiInfo);
                if (decoded != null && decoded.length > 0) {
                    byte[] converted = convertWxamToGif(decoded);
                    return converted != null && converted.length > 0 ? converted : decoded;
                }
            }

            String resolved = resolvePath(emojiPathOrMd5);
            byte[] resolvedBytes = readFileBytes(new File(resolved));
            if (resolvedBytes != null && resolvedBytes.length > 0) {
                byte[] converted = convertWxamToGif(resolvedBytes);
                return converted != null && converted.length > 0 ? converted : resolvedBytes;
            }
        } catch (Throwable e) {
            log("解码表情数据失败: " + e.getMessage());
        }
        return directBytes != null && directBytes.length > 0 ? directBytes : null;
    }

    public MassSendPayload prepareMassSendPayload(String emojiPathOrMd5) {
        if (TextUtils.isEmpty(emojiPathOrMd5) || !canSendSilently()) return null;
        try {
            File localFile = new File(emojiPathOrMd5);
            String md5;
            Object emojiInfo;
            if (localFile.isFile()) {
                if (!canSendLocalPathSilently()) return null;
                md5 = fileMd5(localFile);
                if (TextUtils.isEmpty(md5)) return null;
                emojiInfo = getEmojiInfo(md5);
                if (emojiInfo == null) emojiInfo = prepareLocalEmojiInfo(localFile, md5);
            } else {
                md5 = normalizeMd5(emojiPathOrMd5);
                if (TextUtils.isEmpty(md5)) return null;
                emojiInfo = getEmojiInfo(md5);
            }
            if (emojiInfo == null) return null;
            String resolvedMd5 = readString(emojiInfo, "getMd5", "field_md5");
            int size = readInt(emojiInfo, "getSize", "field_size");
            int type = readInt(emojiInfo, "getType", "field_type");
            String content = readString(emojiInfo, "getContent", "field_content");
            if (TextUtils.isEmpty(resolvedMd5)) resolvedMd5 = md5;
            if (size <= 0 && localFile.isFile()) size = safeFileSize(localFile);
            if (type <= 0 && localFile.isFile()) {
                type = isGif(localFile.getAbsolutePath()) ? EMOJI_TYPE_GIF : EMOJI_TYPE_STATIC;
            }
            if (TextUtils.isEmpty(resolvedMd5) || size <= 0 || type <= 0) return null;
            return new MassSendPayload(resolvedMd5, size, type, content);
        } catch (Throwable e) {
            log("准备群发表情异常: " + e.getMessage());
            return null;
        }
    }

    private boolean sendLocalFile(String talker, File source) {
        if (!canSendLocalPathSilently()) {
            log("发表情失败: 本地路径API未就绪");
            return false;
        }
        String md5 = fileMd5(source);
        if (TextUtils.isEmpty(md5)) {
            log("发表情失败: 计算本地文件md5失败");
            return false;
        }
        try {
            Object emojiInfo = getEmojiInfo(md5);
            if (emojiInfo == null) emojiInfo = prepareLocalEmojiInfo(source, md5);
            if (emojiInfo == null) return false;
            if (sendByManager(talker, md5)) return true;
            return sendEmojiInfo(talker, emojiInfo);
        } catch (Throwable e) {
            log("发表情本地路径异常: " + e.getMessage());
            return false;
        }
    }

    private Object prepareLocalEmojiInfo(File source, String md5) throws Exception {
        String basePath = normalizedEmojiBasePath();
        if (TextUtils.isEmpty(basePath)) {
            log("发表情失败: 获取微信表情目录失败");
            return null;
        }
        String targetPath = emojiFilePath(basePath, "", md5);
        if (TextUtils.isEmpty(targetPath)) {
            log("发表情失败: 获取微信表情目标路径失败");
            return null;
        }
        if (!copyFile(source, new File(targetPath))) {
            log("发表情失败: 复制到微信表情目录失败 " + targetPath);
            return null;
        }
        int type = isGif(source.getAbsolutePath()) ? EMOJI_TYPE_GIF : EMOJI_TYPE_STATIC;
        int size = safeFileSize(source);
        Object localEmojiInfo = createEmojiInfo(md5, MESSAGE_EMOJI_CATALOG, type, size);
        prepareLocalEmojiInfo(localEmojiInfo, md5, type, size);
        updateEmojiInfo(localEmojiInfo);
        Object transientEmojiInfo = newTransientEmojiInfo(
                basePath, md5, TRANSIENT_SEND_EMOJI_CATALOG, type, size);
        Object result = transientEmojiInfo != null ? transientEmojiInfo : localEmojiInfo;
        if (result == null) log("发表情失败: 创建临时EmojiInfo失败 " + md5);
        return result;
    }

    private boolean sendEmojiInfo(String talker, Object emojiInfo) throws Exception {
        Method method = dexFinder.emojiSendMethod;
        Object target = targetFor(method);
        if (!KavaReflector.isStatic(method) && target == null) {
            log("发表情失败: 无法创建发送器 " + method.getDeclaringClass().getName());
            return false;
        }
        KavaReflector.invoke(method, target, buildSendArgs(method, talker, emojiInfo));
        return true;
    }

    private boolean sendByManager(String talker, String md5) {
        Method method = dexFinder.emojiManagerSendMethod;
        if (method == null || hostContext == null || TextUtils.isEmpty(md5)) return false;
        try {
            Object target = targetFor(method);
            if (!KavaReflector.isStatic(method) && target == null) return false;
            Object msgIdTalker = zeroMsgIdTalker(method.getParameterTypes()[3]);
            Object result = KavaReflector.invoke(method, target, hostContext, talker, md5, msgIdTalker, 0);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable e) {
            log("原生表情管理发送失败: " + e.getMessage());
            return false;
        }
    }

    private Object getEmojiInfo(String md5) throws Exception {
        Method method = dexFinder.emojiGetByMd5Method;
        if (method == null) {
            return providerGetEmojiInfo(md5);
        }
        Object target = targetFor(method);
        if (!KavaReflector.isStatic(method) && target == null) {
            return providerGetEmojiInfo(md5);
        }
        Object value = KavaReflector.invoke(method, target, md5);
        return value != null ? value : providerGetEmojiInfo(md5);
    }

    private byte[] decodeEmojiInfo(Object emojiInfo) throws Exception {
        Method decode = dexFinder.emojiDecodeDataMethod;
        Method getter = dexFinder.emojiDecodeManagerGetterMethod;
        if (emojiInfo == null || decode == null || getter == null) return null;
        Object manager = KavaReflector.invoke(getter, null);
        if (manager == null) return null;
        Object value = KavaReflector.invoke(decode, manager, emojiInfo);
        return value instanceof byte[] ? (byte[]) value : null;
    }

    private byte[] convertWxamToGif(byte[] data) {
        if (data == null || data.length == 0 || isKnownImage(data)) return data;
        Method method = resolveWxamToGifMethod();
        if (method == null) return null;
        try {
            Object value = KavaReflector.invoke(method, null, data);
            return value instanceof byte[] ? (byte[]) value : null;
        } catch (Throwable e) {
            log("转换WXAM表情失败: " + e.getMessage());
            return null;
        }
    }

    private synchronized Method resolveWxamToGifMethod() {
        if (wxamToGifResolved) return wxamToGifMethod;
        wxamToGifResolved = true;
        try {
            ClassLoader loader = dexFinder.emojiDecodeDataMethod != null
                    ? dexFinder.emojiDecodeDataMethod.getDeclaringClass().getClassLoader()
                    : getClass().getClassLoader();
            Class<?> clazz = KavaReflector.loadClass("com.tencent.mm.plugin.gif.MMWXGFJNI", loader);
            Method method = KavaReflector.findDeclaredMethod(clazz, "nativeWxamToGif", byte[].class);
            if (method != null && KavaReflector.isStatic(method) && method.getReturnType() == byte[].class) {
                wxamToGifMethod = KavaReflector.accessible(method);
            }
        } catch (Throwable e) {
            log("定位WXAM转换方法失败: " + e.getMessage());
        }
        return wxamToGifMethod;
    }

    private Object newTransientEmojiInfo(String basePath, String md5, int catalog, int type, int size) {
        Class<?> emojiClass = dexFinder.emojiSendMethod.getParameterTypes()[1];
        try {
            Object emojiInfo = KavaReflector.newInstance(
                    KavaReflector.findConstructor(emojiClass, String.class), basePath);
            setField(emojiInfo, "field_md5", md5);
            setField(emojiInfo, "field_catalog", catalog);
            setField(emojiInfo, "field_type", type);
            setField(emojiInfo, "field_size", size);
            setField(emojiInfo, "field_start", 0);
            setField(emojiInfo, "field_state", 0);
            setField(emojiInfo, "field_groupId", "");
            setField(emojiInfo, "field_name", "");
            setField(emojiInfo, "field_content", "");
            setField(emojiInfo, "field_reserved4", 0);
            setField(emojiInfo, "field_temp", 1);
            return emojiInfo;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object createEmojiInfo(String md5, int catalog, int type, int size) throws Exception {
        Method method = dexFinder.emojiCreateInfoMethod;
        if (method != null) {
            Object target = targetFor(method);
            if (KavaReflector.isStatic(method) || target != null) {
                Object value = KavaReflector.invoke(method, target, md5, catalog, type, size);
                if (value != null) return value;
            }
        }
        return providerCreateEmojiInfo(md5, catalog, type, size);
    }

    private void updateEmojiInfo(Object emojiInfo) {
        Method method = dexFinder.emojiUpdateInfoMethod;
        if (method == null || emojiInfo == null) return;
        try {
            Object target = targetFor(method);
            if (KavaReflector.isStatic(method) || target != null) {
                KavaReflector.invoke(method, target, emojiInfo);
            }
        } catch (Throwable e) {
            log("更新本地表情信息失败: " + e.getMessage());
        }
    }

    private void prepareLocalEmojiInfo(Object emojiInfo, String md5, int type, int size) {
        if (emojiInfo == null) return;
        try {
            setField(emojiInfo, "field_md5", md5);
            setField(emojiInfo, "field_catalog", MESSAGE_EMOJI_CATALOG);
            setField(emojiInfo, "field_type", type);
            setField(emojiInfo, "field_size", size);
            setField(emojiInfo, "field_start", 0);
            setField(emojiInfo, "field_state", 0);
            setField(emojiInfo, "field_needupload", 1);
            setField(emojiInfo, "field_groupId", "");
            setField(emojiInfo, "field_width", 320);
            setField(emojiInfo, "field_height", 320);
            setField(emojiInfo, "field_temp", 1);
        } catch (Throwable e) {
            log("准备本地表情信息失败: " + e.getMessage());
        }
    }

    private String emojiFilePath(String basePath, String groupId, String md5) throws Exception {
        Method pathMethod = dexFinder.emojiFilePathMethod;
        if (pathMethod != null) {
            Object result = KavaReflector.invoke(pathMethod, null, basePath, groupId, md5);
            return result instanceof String ? (String) result : "";
        }
        return basePath + md5;
    }

    private String normalizedEmojiBasePath() throws Exception {
        Method method = dexFinder.emojiAccPathMethod;
        if (method == null) {
            return providerAccPath();
        }
        Object target = targetFor(method);
        if (!KavaReflector.isStatic(method) && target == null) {
            return providerAccPath();
        }
        Object result = KavaReflector.invoke(method, target);
        if (!(result instanceof String)) return providerAccPath();
        String path = (String) result;
        if (TextUtils.isEmpty(path)) return providerAccPath();
        return path.endsWith("/") ? path : path + "/";
    }

    private boolean isGif(String path) {
        Method method = dexFinder.emojiCheckGifMethod;
        if (method != null) {
            try {
                Object target = targetFor(method);
                if (KavaReflector.isStatic(method) || target != null) {
                    Object result = KavaReflector.invoke(method, target, path);
                    if (result instanceof Boolean) return (Boolean) result;
                }
            } catch (Throwable e) {
                log("检测GIF失败: " + e.getMessage());
            }
        }
        Boolean providerResult = providerCheckGif(path);
        if (providerResult != null) return providerResult;
        return path != null && path.toLowerCase().endsWith(".gif");
    }

    private Object[] buildSendArgs(Method method, String talker, Object emojiInfo) {
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i];
            if (i == 0 && type == String.class) {
                args[i] = talker;
            } else if (i == 1 && type.isInstance(emojiInfo)) {
                args[i] = emojiInfo;
            } else if (type == long.class || type == Long.class) {
                args[i] = 0L;
            } else if (type == int.class || type == Integer.class) {
                args[i] = 0;
            } else if (type == boolean.class || type == Boolean.class) {
                args[i] = false;
            } else if (type == String.class) {
                args[i] = "";
            } else if (i == 3) {
                args[i] = zeroMsgIdTalker(type);
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private Object zeroMsgIdTalker(Class<?> type) {
        if (type == null || type.isPrimitive()) return null;
        try {
            return KavaReflector.newInstance(
                    KavaReflector.findConstructor(type, long.class, String.class), 0L, null);
        } catch (Throwable ignored) {
        }
        try {
            for (java.lang.reflect.Field field : KavaReflector.declaredFields(type)) {
                if (!KavaReflector.isStatic(field)) continue;
                if (!type.isAssignableFrom(field.getType())) continue;
                Object value = KavaReflector.readField(field, (Object) null);
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object newInstance(Class<?> clazz) {
        if (clazz == null) return null;
        try {
            return KavaReflector.newInstance(KavaReflector.findConstructor(clazz));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object targetFor(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return null;
        Object service = WeChatInternalServices.getService(dexFinder, method.getDeclaringClass());
        if (service != null) return service;
        return newInstance(method.getDeclaringClass());
    }

    private String providerAccPath() {
        Bundle result = providerCall("getAccPath", null);
        if (result == null) return "";
        String path = result.getString("path", "");
        if (TextUtils.isEmpty(path)) return "";
        return path.endsWith("/") ? path : path + "/";
    }

    private Object providerGetEmojiInfo(String md5) {
        if (TextUtils.isEmpty(md5)) return null;
        Bundle args = new Bundle();
        args.putString("key_md5", md5);
        Bundle result = providerCall("getEmojiByMd5", args);
        if (result == null) return null;
        try {
            ClassLoader loader = dexFinder.emojiSendMethod != null
                    ? dexFinder.emojiSendMethod.getDeclaringClass().getClassLoader()
                    : getClass().getClassLoader();
            result.setClassLoader(loader);
        } catch (Throwable ignored) {
        }
        return result.getParcelable("key_emoji_info");
    }

    private Boolean providerCheckGif(String path) {
        if (TextUtils.isEmpty(path)) return null;
        Bundle args = new Bundle();
        args.putString("key_path", path);
        Bundle result = providerCall("checkGifFile", args);
        return result != null ? result.getBoolean("key_data", false) : null;
    }

    private Object providerCreateEmojiInfo(String md5, int catalog, int type, int size) {
        if (TextUtils.isEmpty(md5)) return null;
        Bundle args = new Bundle();
        args.putString("key_md5", md5);
        args.putInt("key_group", catalog);
        args.putInt("key_type", type);
        args.putInt("key_size", size);
        Bundle result = providerCall("createEmojiInfo", args);
        if (result == null) return null;
        try {
            ClassLoader loader = dexFinder.emojiSendMethod != null
                    ? dexFinder.emojiSendMethod.getDeclaringClass().getClassLoader()
                    : getClass().getClassLoader();
            result.setClassLoader(loader);
        } catch (Throwable ignored) {
        }
        return result.getParcelable("key_emoji_info");
    }

    private Bundle providerCall(String method, Bundle args) {
        if (hostContext == null) return null;
        try {
            return hostContext.getContentResolver().call(emotionUri(), method, null, args);
        } catch (Throwable e) {
            log("EmotionProvider调用失败: " + method + " " + e.getMessage());
            return null;
        }
    }

    private Uri emotionUri() {
        return Uri.parse("content://" + hostContext.getPackageName() + EMOTION_PROVIDER + "/");
    }

    private String normalizeMd5(String value) {
        String text = value != null ? value.trim() : "";
        if (isMd5(text)) return text.toLowerCase();
        return "";
    }

    private boolean isMd5(String value) {
        if (value == null || value.length() != 32) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    private String fileMd5(File file) {
        FileInputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 0xff;
                if (v < 16) sb.append('0');
                sb.append(Integer.toHexString(v));
            }
            return sb.toString();
        } catch (Throwable e) {
            log("计算表情md5失败: " + e.getMessage());
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private int safeFileSize(File file) {
        long size = file != null ? file.length() : 0L;
        if (size <= 0L) return 0;
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    private byte[] readFileBytes(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L || file.length() > Integer.MAX_VALUE) {
            return null;
        }
        FileInputStream in = null;
        try {
            int size = (int) file.length();
            byte[] result = new byte[size];
            in = new FileInputStream(file);
            int offset = 0;
            while (offset < size) {
                int read = in.read(result, offset, size - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset == size) return result;
            byte[] partial = new byte[offset];
            System.arraycopy(result, 0, partial, 0, offset);
            return partial;
        } catch (Throwable e) {
            log("读取表情文件失败: " + e.getMessage());
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private boolean isKnownImage(byte[] data) {
        if (data == null) return false;
        if (data.length >= 6) {
            String gif = new String(data, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
            if ("GIF87a".equals(gif) || "GIF89a".equals(gif)) return true;
        }
        if (data.length >= 8
                && (data[0] & 0xff) == 0x89 && data[1] == 0x50 && data[2] == 0x4e
                && data[3] == 0x47 && data[4] == 0x0d && data[5] == 0x0a
                && data[6] == 0x1a && data[7] == 0x0a) return true;
        if (data.length >= 3
                && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8
                && (data[2] & 0xff) == 0xff) return true;
        return data.length >= 12
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
    }

    private String readString(Object target, String getter, String fieldName) {
        Object value = KavaReflector.invokeMethod(target, getter);
        if (value == null) value = KavaReflector.readField(target, fieldName);
        return value != null ? String.valueOf(value) : "";
    }

    private int readInt(Object target, String getter, String fieldName) {
        Object value = KavaReflector.invokeMethod(target, getter);
        if (!(value instanceof Number)) value = KavaReflector.readField(target, fieldName);
        return value instanceof Number ? ((Number) value).intValue() : 0;
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
            log("复制表情文件异常: " + e.getMessage());
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

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = findField(target.getClass(), name);
        if (field == null) return;
        Class<?> type = field.getType();
        Object next = value;
        if (type == int.class) next = value instanceof Number ? ((Number) value).intValue() : 0;
        else if (type == long.class) next = value instanceof Number ? ((Number) value).longValue() : 0L;
        else if (type == boolean.class) next = value instanceof Boolean && (Boolean) value;
        KavaReflector.writeField(field, target, next);
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) {
        return KavaReflector.findFieldRecursive(clazz, name);
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatEmojiApi] " + message);
    }

    public static final class MassSendPayload {
        private final String md5;
        private final int size;
        private final int type;
        private final String content;

        MassSendPayload(String md5, int size, int type, String content) {
            this.md5 = md5;
            this.size = size;
            this.type = type;
            this.content = content != null ? content : "";
        }

        public String getMd5() {
            return md5;
        }

        public int getSize() {
            return size;
        }

        public int getType() {
            return type;
        }

        public String getContent() {
            return content;
        }
    }
}
