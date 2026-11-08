package h.Hchat.hooks.items.payment.detect;

import android.content.ContentValues;
import android.text.TextUtils;

import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.fake.RedPacketFakePacketCompat;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 数据库插入兜底监听。
 * AddMsg Hook 失效或错过时，从 message 表写入的 ContentValues 中再次识别红包。
 */
public class RedPacketDatabaseHook {
    public interface LoginProvider {
        String getLoginWxid();
    }

    public interface DetectedCallback {
        void onDetected(String source, String xml, String sender, String talker,
                        String nativeUrl, String exclusiveRecvUser);
    }

    public interface Logger {
        void log(String message);
    }

    private final ClassLoader classLoader;
    private final RedPacketSettings settings;
    private final LoginProvider loginProvider;
    private final DetectedCallback callback;
    private final Logger logger;
    private boolean hooked;

    public RedPacketDatabaseHook(
            ClassLoader classLoader,
            RedPacketSettings settings,
            LoginProvider loginProvider,
            DetectedCallback callback,
            Logger logger
    ) {
        this.classLoader = classLoader;
        this.settings = settings;
        this.loginProvider = loginProvider;
        this.callback = callback;
        this.logger = logger;
    }

    public void hook() {
        if (hooked) return;
        int count = 0;
        count += hookOneDatabaseClass("com.tencent.wcdb.database.SQLiteDatabase");
        count += hookOneDatabaseClass("android.database.sqlite.SQLiteDatabase");
        hooked = count > 0;
        if (hooked) {
            log("数据库兜底Hook完成: " + count);
        } else {
            log("数据库兜底: 未找到可Hook的SQLiteDatabase");
        }
    }

    private int hookOneDatabaseClass(String className) {
        try {
            Class<?> dbClass = KavaReflector.loadClass(className, classLoader);
            if (dbClass == null) return 0;
            int hookedCount = 0;
            for (Method method : KavaReflector.declaredMethods(dbClass)) {
                String name = method.getName();
                if (!("insert".equals(name)
                        || "insertWithOnConflict".equals(name)
                        || "replace".equals(name)
                        || "replaceOrThrow".equals(name)
                        || "update".equals(name)
                        || "updateWithOnConflict".equals(name))) {
                    continue;
                }
                if (!hasContentValuesArg(method)) continue;

                HookRegistry.get().hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        ContentValues values = findContentValuesArg(param.args);
                        if (values == null) return;
                        handleDatabaseInsert(findTableNameArg(param.args), values);
                    }
                });
                hookedCount++;
            }
            if (hookedCount > 0) log("数据库兜底Hook: " + className + " count=" + hookedCount);
            return hookedCount;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private boolean hasContentValuesArg(Method method) {
        try {
            Class<?>[] types = method.getParameterTypes();
            if (types == null || types.length < 2) return false;
            for (Class<?> type : types) {
                if (ContentValues.class.isAssignableFrom(type)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private ContentValues findContentValuesArg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof ContentValues) return (ContentValues) arg;
        }
        return null;
    }

    private String findTableNameArg(Object[] args) {
        if (args == null) return null;
        try {
            if (args.length > 0 && args[0] instanceof String) return String.valueOf(args[0]);
            for (Object arg : args) {
                if (!(arg instanceof String)) continue;
                String value = String.valueOf(arg);
                String lower = value.toLowerCase();
                if ("message".equals(lower) || lower.contains("message")) return value;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isMessageTable(String table) {
        if (TextUtils.isEmpty(table)) return true;
        String lower = table.toLowerCase();
        return "message".equals(lower) || lower.endsWith("message") || lower.contains("message");
    }

    private void handleDatabaseInsert(String table, ContentValues values) {
        if (!settings.isEnabled() || values == null || !isMessageTable(table)) return;
        try {
            RedPacketFakePacketCompat.fixContentValues(values);
            String content = getContentValueString(values, "content");
            if (TextUtils.isEmpty(content)) content = getContentValueString(values, "msgContent");
            if (TextUtils.isEmpty(content) || !content.contains("<wcpayinfo>")) return;
            if (!RedPacketParser.containsRedBagMarker(content)) return;

            String xml = stripMsgContentPrefix(content);
            String nativeUrl = RedPacketParser.getXmlParamByTag(xml, "nativeurl");
            if (TextUtils.isEmpty(nativeUrl)) nativeUrl = RedPacketParser.getXmlParamByTag(content, "nativeurl");
            if (TextUtils.isEmpty(nativeUrl)) return;

            String talker = getContentValueString(values, "talker");
            if (TextUtils.isEmpty(talker)) talker = getContentValueString(values, "username");

            String sender = getContentValueString(values, "sendTalker");
            if (TextUtils.isEmpty(sender)) {
                int prefixEnd = content.indexOf(":\n");
                if (prefixEnd > 0) sender = content.substring(0, prefixEnd);
            }
            if (TextUtils.isEmpty(sender)) {
                int isSend = getContentValueInt(values, "isSend", 0);
                String my = loginProvider != null ? loginProvider.getLoginWxid() : "";
                sender = isSend == 1 ? my : talker;
            }

            if (RedPacketParser.isGroupTalker(talker)) {
                // keep talker
            } else if (RedPacketParser.isGroupTalker(sender)) {
                talker = sender;
            } else if (TextUtils.isEmpty(talker)) {
                talker = sender;
            }

            String exclusiveRecvUser = RedPacketParser.getXmlParamByTag(xml, "exclusive_recv_username");
            log("数据库兜底红包: talker=" + talker + " sender=" + sender);
            if (callback != null) {
                callback.onDetected("数据库兜底", xml, sender, talker, nativeUrl, exclusiveRecvUser);
            }
        } catch (Throwable e) {
            log("ERROR 数据库兜底处理失败: " + e.getMessage());
        }
    }

    private String getContentValueString(ContentValues values, String key) {
        try {
            Object value = values.get(key);
            if (value == null) return null;
            String text = String.valueOf(value);
            return TextUtils.isEmpty(text) || "null".equalsIgnoreCase(text) ? null : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int getContentValueInt(ContentValues values, String key, int defaultValue) {
        try {
            Object value = values.get(key);
            if (value instanceof Number) return ((Number) value).intValue();
            if (value != null) return RedPacketParser.safeParseInt(String.valueOf(value), defaultValue);
        } catch (Throwable ignored) {}
        return defaultValue;
    }

    private String stripMsgContentPrefix(String content) {
        if (TextUtils.isEmpty(content)) return content;
        int index = content.indexOf(":\n");
        if (index > 0 && content.indexOf("<") > index) return content.substring(index + 2);
        return content;
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
