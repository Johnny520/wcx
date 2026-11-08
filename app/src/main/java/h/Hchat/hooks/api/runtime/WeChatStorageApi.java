package h.Hchat.hooks.api.runtime;

import android.text.TextUtils;

import java.util.List;
import java.util.Map;

/**
 * 微信数据库安全查询辅助 API。
 */
public final class WeChatStorageApi {
    public interface Logger {
        void log(String message);
    }

    private final WeChatDatabaseApi databaseApi;
    private final Logger logger;

    public WeChatStorageApi(WeChatDatabaseApi databaseApi, Logger logger) {
        this.databaseApi = databaseApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return databaseApi != null && databaseApi.isAvailable();
    }

    public boolean tableExists(String tableName) {
        if (TextUtils.isEmpty(tableName) || databaseApi == null) return false;
        String value = databaseApi.queryFirstString(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                new String[]{tableName},
                "name");
        return tableName.equals(value);
    }

    public boolean columnExists(String tableName, String columnName) {
        if (TextUtils.isEmpty(tableName) || TextUtils.isEmpty(columnName) || databaseApi == null) {
            return false;
        }
        try {
            for (Map<String, Object> row : databaseApi.query(
                    "PRAGMA table_info(" + safeIdentifier(tableName) + ")",
                    null)) {
                if (columnName.equals(str(row, "name"))) return true;
            }
        } catch (Throwable e) {
            log("检查字段失败: " + tableName + "." + columnName + " " + e.getMessage());
        }
        return false;
    }

    public int count(String tableName) {
        if (TextUtils.isEmpty(tableName) || databaseApi == null) return 0;
        if (!tableExists(tableName)) return 0;
        String value = databaseApi.queryFirstString(
                "SELECT COUNT(*) AS c FROM " + safeIdentifier(tableName),
                null,
                "c");
        return parseInt(value);
    }

    public List<Map<String, Object>> queryList(String sql, String[] args) {
        return databaseApi != null ? databaseApi.query(sql, args)
                : new java.util.ArrayList<>();
    }

    public Map<String, Object> queryOne(String sql, String[] args) {
        List<Map<String, Object>> rows = queryList(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String queryString(String sql, String[] args, String columnName) {
        return databaseApi != null ? databaseApi.queryFirstString(sql, args, columnName) : "";
    }

    public String getString(Map<String, Object> row, String key) {
        return str(row, key);
    }

    public int getInt(Map<String, Object> row, String key) {
        return parseInt(str(row, key));
    }

    public long getLong(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value != null ? String.valueOf(value) : "");
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String safeIdentifier(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatStorageApi] " + message);
    }
}
