package h.Hchat.hooks.api.message;

import android.content.ContentValues;
import android.text.TextUtils;

import h.Hchat.hooks.api.contact.WeChatAccountApi;
import h.Hchat.hooks.api.model.DatabaseChange;
import h.Hchat.hooks.api.model.WeChatMessage;
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * message 表变更监听 API。
 */
public final class WeChatMessageChangeApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onMessageChanged(MessageChange change);
    }

    public static final class MessageChange {
        public final DatabaseChange databaseChange;
        public final WeChatMessage message;

        private MessageChange(DatabaseChange databaseChange, WeChatMessage message) {
            this.databaseChange = databaseChange;
            this.message = message;
        }

        public String operation() {
            return databaseChange != null ? databaseChange.operation : "";
        }

        public boolean isInsert() {
            return databaseChange != null && databaseChange.isInsert();
        }

        public boolean isUpdate() {
            return databaseChange != null && databaseChange.isUpdate();
        }

        public boolean isDelete() {
            return databaseChange != null && databaseChange.isDelete();
        }
    }

    public final class Subscription {
        private final Listener listener;
        private volatile boolean active = true;

        private Subscription(Listener listener) {
            this.listener = listener;
        }

        public void unsubscribe() {
            if (!active) return;
            active = false;
            listeners.remove(listener);
        }

        public boolean isActive() {
            return active;
        }
    }

    private final WeChatDatabaseListenerApi databaseListenerApi;
    private final WeChatMessageStoreApi messageStoreApi;
    private final WeChatAccountApi accountApi;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean installed;

    public WeChatMessageChangeApi(WeChatDatabaseListenerApi databaseListenerApi,
                                  WeChatMessageStoreApi messageStoreApi,
                                  Logger logger) {
        this(databaseListenerApi, messageStoreApi, null, logger);
    }

    public WeChatMessageChangeApi(WeChatDatabaseListenerApi databaseListenerApi,
                                  WeChatMessageStoreApi messageStoreApi,
                                  WeChatAccountApi accountApi,
                                  Logger logger) {
        this.databaseListenerApi = databaseListenerApi;
        this.messageStoreApi = messageStoreApi;
        this.accountApi = accountApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return databaseListenerApi != null && databaseListenerApi.isAvailable();
    }

    public boolean isInstalled() {
        return installed;
    }

    public Subscription subscribe(Listener listener) {
        if (listener == null) return null;
        listeners.addIfAbsent(listener);
        return new Subscription(listener);
    }

    public void unsubscribe(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public synchronized void install() {
        if (installed || databaseListenerApi == null) return;
        databaseListenerApi.install();
        if (!databaseListenerApi.isOperational()) {
            log("消息变更监听未就绪: databaseHooks="
                    + databaseListenerApi.hookedMethodCount()
                    + " wrapperHooks="
                    + databaseListenerApi.hookedWrapperMutationMethodCount()
                    + " wrapperInserts="
                    + databaseListenerApi.hookedWrapperInsertMethodCount());
            return;
        }
        databaseListenerApi.subscribe(this::onDatabaseChanged);
        installed = true;
        log("消息变更监听已安装");
    }

    private void onDatabaseChanged(DatabaseChange change) {
        if (change == null || !isMessageTable(change.table)) return;
        WeChatMessage message = resolveMessage(change);
        MessageChange event = new MessageChange(change, message);
        for (Listener listener : listeners) {
            try {
                listener.onMessageChanged(event);
            } catch (Throwable e) {
                log("消息变更回调失败: " + e.getMessage());
            }
        }
    }

    private boolean isMessageTable(String table) {
        if (TextUtils.isEmpty(table)) return false;
        String lower = table.toLowerCase(java.util.Locale.US);
        return "message".equals(lower)
                || lower.startsWith("message_")
                || lower.endsWith("_message");
    }

    private WeChatMessage resolveMessage(DatabaseChange change) {
        long msgId = resolveMsgId(change);
        if (msgId > 0 && messageStoreApi != null) {
            WeChatMessage stored = messageStoreApi.getMessageById(msgId);
            if (stored != null) return stored;
        }
        if (change.values == null) return null;
        return new WeChatMessage(
                msgId,
                longValue(change.values, "msgSvrId"),
                intValue(change.values, "type"),
                intValue(change.values, "status"),
                intValue(change.values, "isSend"),
                longValue(change.values, "createTime"),
                str(change.values, "talker"),
                str(change.values, "content"),
                str(change.values, "imgPath"),
                str(change.values, "reserved"),
                str(change.values, "transContent"),
                intValue(change.values, "flag"),
                str(change.values, "msgSource"),
                selfWxId());
    }

    private String selfWxId() {
        return accountApi != null ? accountApi.selfWxId() : "";
    }

    private long resolveMsgId(DatabaseChange change) {
        if (change == null) return 0L;
        long msgId = longValue(change.values, "msgId");
        if (msgId > 0) return msgId;
        msgId = longValue(change.values, "msgid");
        if (msgId > 0) return msgId;
        msgId = longValue(change.values, "_id");
        if (msgId > 0) return msgId;
        msgId = longValue(change.values, "rowid");
        if (msgId > 0) return msgId;
        if (change.isInsert() && change.result > 0) return change.result;
        return msgIdFromWhere(change.whereClause, change.whereArgs);
    }

    private long msgIdFromWhere(String whereClause, String[] whereArgs) {
        if (TextUtils.isEmpty(whereClause) || whereArgs == null || whereArgs.length == 0) return 0L;
        String where = whereClause.toLowerCase(java.util.Locale.US);
        if (!where.contains("msgid")) return 0L;
        for (String arg : whereArgs) {
            if (TextUtils.isEmpty(arg)) continue;
            try {
                long value = Long.parseLong(arg);
                if (value > 0) return value;
            } catch (Throwable ignored) {}
        }
        return 0L;
    }

    private static String str(ContentValues values, String key) {
        if (values == null || TextUtils.isEmpty(key) || !values.containsKey(key)) return "";
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private static int intValue(ContentValues values, String key) {
        Long value = longBox(values, key);
        return value != null ? value.intValue() : 0;
    }

    private static long longValue(ContentValues values, String key) {
        Long value = longBox(values, key);
        return value != null ? value : 0L;
    }

    private static Long longBox(ContentValues values, String key) {
        if (values == null || TextUtils.isEmpty(key) || !values.containsKey(key)) return null;
        try {
            return values.getAsLong(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatMessageChangeApi] " + message);
    }
}
