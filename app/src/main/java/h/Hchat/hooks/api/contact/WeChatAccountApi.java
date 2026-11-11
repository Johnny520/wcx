package h.Hchat.hooks.api.contact;

import android.content.Context;
import android.text.TextUtils;

import h.Hchat.hooks.api.runtime.WeChatDatabaseApi;
import h.Hchat.utils.KavaReflector;

/**
 * 当前登录账号资料 API。
 */
public final class WeChatAccountApi {
    public static final int FIELD_WXID = 2;
    public static final int FIELD_NAME = 4;
    public static final int FIELD_EMAIL = 5;
    public static final int FIELD_PHONE_NUMBER = 6;
    public static final int FIELD_CUSTOM_WXID = 42;
    public static final int FIELD_GENDER = 12290;
    public static final int FIELD_SIGNATURE = 12291;
    public static final int FIELD_CITY = 12292;
    public static final int FIELD_PROVINCE = 12293;

    public interface Logger {
        void log(String message);
    }

    private final Context hostContext;
    private final ClassLoader classLoader;
    private final WeChatDatabaseApi databaseApi;
    private final Logger logger;
    private volatile String cachedWxId;

    public WeChatAccountApi(Context hostContext,
                            ClassLoader classLoader,
                            WeChatDatabaseApi databaseApi,
                            Logger logger) {
        this.hostContext = hostContext;
        this.classLoader = classLoader;
        this.databaseApi = databaseApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return databaseApi != null && databaseApi.isAvailable();
    }

    public String selfWxId() {
        if (!TextUtils.isEmpty(cachedWxId)) return cachedWxId;
        String wxId = getSelfProfileField(FIELD_WXID);
        if (TextUtils.isEmpty(wxId)) wxId = getLoginWxIdFromPrefs();
        if (TextUtils.isEmpty(wxId)) wxId = getSelfUsernameByAlias(customWxId());
        if (!TextUtils.isEmpty(wxId)) cachedWxId = wxId;
        return wxId;
    }

    public String selfName() {
        return getSelfProfileField(FIELD_NAME);
    }

    public String customWxId() {
        return getSelfProfileField(FIELD_CUSTOM_WXID);
    }

    public String phoneNumber() {
        return getSelfProfileField(FIELD_PHONE_NUMBER);
    }

    public String email() {
        return getSelfProfileField(FIELD_EMAIL);
    }

    public String signature() {
        return getSelfProfileField(FIELD_SIGNATURE);
    }

    public String city() {
        return getSelfProfileField(FIELD_CITY);
    }

    public String province() {
        return getSelfProfileField(FIELD_PROVINCE);
    }

    public String getSelfProfileField(int fieldCode) {
        try {
            if (databaseApi == null) return "";
            return databaseApi.queryFirstString(
                    "SELECT value FROM userinfo WHERE id=?",
                    new String[]{String.valueOf(fieldCode)},
                    "value");
        } catch (Throwable e) {
            log("读取账号字段失败: " + fieldCode + " " + e.getMessage());
            return "";
        }
    }

    private String getPreferenceString(String preferencesName, String key) {
        try {
            Object sp = KavaReflector.invokeStaticMethod(
                    KavaReflector.loadClass(
                            "com.tencent.mm.sdk.platformtools.MMApplicationContext",
                            classLoader),
                    "getSharedPreferences", preferencesName, Context.MODE_MULTI_PROCESS);
            if (sp == null && hostContext != null) {
                sp = hostContext.getSharedPreferences(
                        preferencesName,
                        Context.MODE_MULTI_PROCESS);
            }
            if (sp == null) return "";
            Object value = KavaReflector.invokeMethod(sp, "getString", key, "");
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String getLoginWxIdFromPrefs() {
        String value = getPreferenceString(
                "notify_key_pref_no_account",
                "login_weixin_username");
        if (!TextUtils.isEmpty(value)) return value.trim();
        for (String key : new String[]{
                "login_weixin_username",
                "login_user_name",
                "login_username",
                "last_login_username"
        }) {
            value = getPreferenceString("login_info", key);
            if (!TextUtils.isEmpty(value)) return value.trim();
        }
        return "";
    }

    private String getSelfUsernameByAlias(String alias) {
        if (TextUtils.isEmpty(alias) || databaseApi == null) return "";
        try {
            return databaseApi.queryFirstString(
                    "SELECT username FROM rcontact "
                            + "WHERE alias=? AND username!='' "
                            + "AND username NOT LIKE '%@chatroom' "
                            + "AND username NOT LIKE '%@im.chatroom' "
                            + "LIMIT 1",
                    new String[]{alias},
                    "username");
        } catch (Throwable e) {
            log("通过微信号反查自身wxid失败: " + e.getMessage());
            return "";
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatAccountApi] " + message);
    }
}
