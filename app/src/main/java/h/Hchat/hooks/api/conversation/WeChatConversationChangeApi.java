package h.Hchat.hooks.api.conversation;

import android.content.ContentValues;
import android.text.TextUtils;

import h.Hchat.hooks.api.model.DatabaseChange;
import h.Hchat.hooks.api.model.WeChatConversation;
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * rconversation 表变更监听 API。
 */
public final class WeChatConversationChangeApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onConversationChanged(ConversationChange change);
    }

    public static final class ConversationChange {
        public final DatabaseChange databaseChange;
        public final WeChatConversation conversation;

        private ConversationChange(DatabaseChange databaseChange, WeChatConversation conversation) {
            this.databaseChange = databaseChange;
            this.conversation = conversation;
        }

        public String username() {
            if (conversation != null) return conversation.username;
            String[] usernames = affectedUsernames();
            return usernames.length > 0 ? usernames[0] : "";
        }

        /**
         * Returns usernames affected by this database write. For UPDATE/DELETE,
         * SQLite commonly keeps username in whereArgs instead of ContentValues.
         */
        public String[] affectedUsernames() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            if (conversation != null && !TextUtils.isEmpty(conversation.username)) {
                result.add(conversation.username);
            }
            DatabaseChange change = databaseChange;
            if (change == null) return result.toArray(new String[0]);

            String valueUsername = str(change.values, "username");
            if (!TextUtils.isEmpty(valueUsername)) result.add(valueUsername);

            String where = change.whereClause;
            if (!TextUtils.isEmpty(where)
                    && where.toLowerCase(Locale.US).contains("username")
                    && change.whereArgs != null) {
                for (String arg : change.whereArgs) {
                    if (!TextUtils.isEmpty(arg)) result.add(arg);
                }
            }
            return result.toArray(new String[0]);
        }

        public String operation() {
            return databaseChange != null ? databaseChange.operation : "";
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
    private final WeChatConversationApi conversationApi;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean installed;

    public WeChatConversationChangeApi(WeChatDatabaseListenerApi databaseListenerApi,
                                       WeChatConversationApi conversationApi,
                                       Logger logger) {
        this.databaseListenerApi = databaseListenerApi;
        this.conversationApi = conversationApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return databaseListenerApi != null && databaseListenerApi.isAvailable();
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
        databaseListenerApi.subscribe(this::onDatabaseChanged);
        installed = true;
        log("会话变更监听已安装");
    }

    private void onDatabaseChanged(DatabaseChange change) {
        if (change == null || !"rconversation".equalsIgnoreCase(change.table)) return;
        if (listeners.isEmpty()) return;
        WeChatConversation conversation = resolveConversation(change);
        ConversationChange event = new ConversationChange(change, conversation);
        for (Listener listener : listeners) {
            try {
                listener.onConversationChanged(event);
            } catch (Throwable e) {
                log("会话变更回调失败: " + e.getMessage());
            }
        }
    }

    private WeChatConversation resolveConversation(DatabaseChange change) {
        String username = firstUsername(change);
        if (!TextUtils.isEmpty(username) && conversationApi != null) {
            WeChatConversation stored = conversationApi.getConversation(username);
            if (stored != null) return stored;
        }
        if (change.values == null) return null;
        return new WeChatConversation(
                username,
                intValue(change.values, "unReadCount"),
                intValue(change.values, "status"),
                intValue(change.values, "isSend"),
                longValue(change.values, "conversationTime"),
                str(change.values, "content"),
                str(change.values, "msgType"),
                longValue(change.values, "flag"),
                str(change.values, "digest"),
                str(change.values, "digestUser"),
                intValue(change.values, "atCount"),
                intValue(change.values, "unReadMuteCount"),
                intValue(change.values, "hasTodo"));
    }

    private static String firstUsername(DatabaseChange change) {
        if (change == null) return "";
        String valueUsername = str(change.values, "username");
        if (!TextUtils.isEmpty(valueUsername)) return valueUsername;
        String where = change.whereClause;
        if (TextUtils.isEmpty(where)
                || !where.toLowerCase(Locale.US).contains("username")
                || change.whereArgs == null) {
            return "";
        }
        for (String arg : change.whereArgs) {
            if (!TextUtils.isEmpty(arg)) return arg;
        }
        return "";
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
        if (logger != null) logger.log("[WeChatConversationChangeApi] " + message);
    }
}
