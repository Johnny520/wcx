package h.Hchat.hooks.api.runtime;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.text.TextUtils;

import h.Hchat.hooks.api.model.WeChatVersionInfo;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信版本、clientVersion 与 Tinker 热更新指纹 API。
 */
public final class WeChatVersionApi {
    public interface Logger {
        void log(String message);
    }

    private static final Pattern CLIENT_VER_PATTERN =
            Pattern.compile("(?:patch\\.client\\.ver|clientVersion|CLIENT_VERSION)\\s*[=:]\\s*(0x[0-9a-fA-F]+|\\d+)");
    private static final Pattern TINKER_ID_PATTERN =
            Pattern.compile("(?:NEW_TINKER_ID|TINKER_ID)\\s*[=:]\\s*([^,}\\s]+)");
    private static final Pattern PATCH_ID_PATTERN =
            Pattern.compile("intent_patch_(?:new|old)_version\\s*[=:]\\s*([^,}\\s]+)");
    private static final String STABLE_CACHE_SCOPE = "stable";

    private final Context hostContext;
    private final ClassLoader classLoader;
    private final Logger logger;
    private volatile WeChatVersionInfo cached;

    public WeChatVersionApi(Context hostContext, ClassLoader classLoader, Logger logger) {
        this.hostContext = hostContext;
        this.classLoader = classLoader;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return hostContext != null;
    }

    public WeChatVersionInfo current() {
        WeChatVersionInfo value = cached;
        WeChatVersionInfo fresh = build(hostContext, classLoader);
        if (value == null) {
            cached = fresh;
            return fresh;
        }
        if (shouldRefresh(value)
                || !TextUtils.equals(value.cacheKey, fresh.cacheKey)
                || !TextUtils.equals(value.clientVersion, fresh.clientVersion)
                || !TextUtils.equals(value.tinkerId, fresh.tinkerId)
                || !TextUtils.equals(value.patchId, fresh.patchId)) {
            cached = fresh;
            return fresh;
        }
        return value;
    }

    public String packageName() {
        return current().packageName;
    }

    public String versionName() {
        return current().versionName;
    }

    public long versionCode() {
        return current().versionCode;
    }

    public String clientVersion() {
        return current().clientVersion;
    }

    public String tinkerId() {
        return current().tinkerId;
    }

    public String patchId() {
        return current().patchId;
    }

    public String cacheKey() {
        return current().cacheKey;
    }

    public boolean hasTinkerPatch() {
        return current().hasTinkerPatch();
    }

    public static WeChatVersionInfo build(Context context, ClassLoader loader) {
        if (context == null) {
            return new WeChatVersionInfo("", "", 0L, "", "", "", 0L,
                    loaderHash(loader), "");
        }

        String packageName = context.getPackageName();
        String versionName = "";
        long versionCode = 0L;
        long sourceLastModified = 0L;

        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            versionName = pi.versionName != null ? pi.versionName : "";
            versionCode = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable ignored) {}

        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info != null && !TextUtils.isEmpty(info.sourceDir)) {
                sourceLastModified = new File(info.sourceDir).lastModified();
            }
        } catch (Throwable ignored) {}

        String loaderText = String.valueOf(loader);
        // Evaluate fallbacks only when needed; eager varargs scanned Tinker
        // files even when the running loader already supplied the metadata.
        String clientVersion = readBuildConfigClientVersion(loader);
        if (TextUtils.isEmpty(clientVersion)) clientVersion = match(CLIENT_VER_PATTERN, loaderText);
        if (TextUtils.isEmpty(clientVersion)) clientVersion = readTinkerValue(context, "patch.client.ver");
        if (TextUtils.isEmpty(clientVersion)) clientVersion = readTinkerValue(context, "client.ver");
        String tinkerId = match(TINKER_ID_PATTERN, loaderText);
        if (TextUtils.isEmpty(tinkerId)) tinkerId = readTinkerValue(context, "NEW_TINKER_ID");
        if (TextUtils.isEmpty(tinkerId)) tinkerId = readTinkerValue(context, "TINKER_ID");
        String patchId = match(PATCH_ID_PATTERN, loaderText);
        if (TextUtils.isEmpty(patchId)) patchId = readPatchDirectoryName(context);
        String classLoaderHash = loaderHash(loader);

        String cacheKey = buildCacheKey(packageName, versionName, versionCode,
                clientVersion, tinkerId, patchId, sourceLastModified, STABLE_CACHE_SCOPE);
        return new WeChatVersionInfo(packageName, versionName, versionCode,
                clientVersion, tinkerId, patchId, sourceLastModified,
                classLoaderHash, cacheKey);
    }

    public static String buildCacheKey(Context context, ClassLoader loader) {
        return build(context, loader).cacheKey;
    }

    private static String buildCacheKey(String packageName,
                                        String versionName,
                                        long versionCode,
                                        String clientVersion,
                                        String tinkerId,
                                        String patchId,
                                        long sourceLastModified,
                                        String classLoaderHash) {
        StringBuilder sb = new StringBuilder();
        sb.append(safe(packageName))
                .append('|').append(safe(versionName))
                .append('|').append(versionCode)
                .append('|').append(safe(clientVersion))
                .append('|').append(safe(tinkerId))
                .append('|').append(safe(patchId))
                .append('|').append(sourceLastModified)
                .append('|').append(safe(classLoaderHash));
        return sb.toString();
    }

    private static String readTinkerValue(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) return "";
        try {
            File tinkerDir = new File(context.getFilesDir().getParentFile(), "tinker");
            String value = scanTextFiles(tinkerDir, key);
            return value != null ? value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String scanTextFiles(File dir, String key) {
        if (dir == null || !dir.exists()) return "";
        File[] files = dir.listFiles();
        if (files == null) return "";
        for (File file : files) {
            if (file == null) continue;
            if (file.isDirectory()) {
                String value = scanTextFiles(file, key);
                if (!TextUtils.isEmpty(value)) return value;
                continue;
            }
            String name = file.getName();
            if (TextUtils.isEmpty(name)
                    || (!name.endsWith(".meta") && !name.endsWith(".txt")
                    && !name.endsWith(".properties"))) {
                continue;
            }
            String value = readKeyFromFile(file, key);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static String readKeyFromFile(File file, String key) {
        java.io.BufferedReader reader = null;
        try {
            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                int index = line.indexOf(key);
                if (index < 0) continue;
                int eq = line.indexOf('=', index + key.length());
                if (eq < 0) eq = line.indexOf(':', index + key.length());
                if (eq < 0 || eq + 1 >= line.length()) continue;
                return line.substring(eq + 1).trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {}
            }
        }
        return "";
    }

    private static String readPatchDirectoryName(Context context) {
        try {
            File tinkerDir = new File(context.getFilesDir().getParentFile(), "tinker");
            File[] files = tinkerDir.listFiles();
            if (files == null) return "";
            long newestTime = 0L;
            String newest = "";
            for (File file : files) {
                if (file == null || !file.isDirectory()) continue;
                String name = file.getName();
                if (TextUtils.isEmpty(name) || !name.startsWith("patch-")) continue;
                long time = file.lastModified();
                if (time >= newestTime) {
                    newestTime = time;
                    newest = name;
                }
            }
            return newest;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String match(Pattern pattern, String text) {
        if (pattern == null || TextUtils.isEmpty(text)) return "";
        try {
            Matcher matcher = pattern.matcher(text);
            return matcher.find() ? matcher.group(1) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readBuildConfigClientVersion(ClassLoader loader) {
        if (loader == null) return "";
        try {
            Class<?> clazz = Class.forName("com.tencent.mm.boot.BuildConfig", false, loader);
            String value = readStaticField(clazz, "CLIENT_VERSION_ARM64");
            if (TextUtils.isEmpty(value)) value = readStaticField(clazz, "CLIENT_VERSION");
            if (TextUtils.isEmpty(value)) value = readStaticField(clazz, "CLIENT_VERSION_INT");
            if (TextUtils.isEmpty(value)) value = readStaticField(clazz, "CLIENTVERSION");
            return value != null ? value.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readStaticField(Class<?> clazz, String fieldName) {
        if (clazz == null || TextUtils.isEmpty(fieldName)) return "";
        try {
            java.lang.reflect.Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(null);
            return value != null ? String.valueOf(value) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean shouldRefresh(WeChatVersionInfo info) {
        if (info == null) return true;
        if (TextUtils.isEmpty(info.clientVersion)) return true;
        if (TextUtils.isEmpty(info.versionName)) return true;
        return info.versionCode <= 0L;
    }

    private static String loaderHash(ClassLoader loader) {
        return Integer.toHexString(String.valueOf(loader).hashCode());
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatVersionApi] " + message);
    }
}
