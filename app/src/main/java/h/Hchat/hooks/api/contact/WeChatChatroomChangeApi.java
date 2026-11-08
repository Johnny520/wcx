package h.Hchat.hooks.api.contact;

import android.content.ContentValues;
import android.text.TextUtils;

import h.Hchat.hooks.api.model.DatabaseChange;
import h.Hchat.hooks.api.model.WeChatChatroom;
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * chatroom 表变更监听 API。
 */
public final class WeChatChatroomChangeApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onChatroomChanged(ChatroomChange change);
    }

    public static final class ChatroomChange {
        public final DatabaseChange databaseChange;
        public final WeChatChatroom chatroom;

        private ChatroomChange(DatabaseChange databaseChange, WeChatChatroom chatroom) {
            this.databaseChange = databaseChange;
            this.chatroom = chatroom;
        }

        public String chatroomId() {
            if (chatroom != null) return chatroom.chatroomId;
            String fromValues = str(databaseChange != null ? databaseChange.values : null, "chatroomname");
            if (!TextUtils.isEmpty(fromValues)) return fromValues;
            String[] args = databaseChange != null ? databaseChange.whereArgs : null;
            if (args != null && args.length > 0) {
                String where = databaseChange.whereClause != null ? databaseChange.whereClause : "";
                if (where.toLowerCase().contains("chatroomname")) return args[0];
            }
            return "";
        }

        public String operation() {
            return databaseChange != null ? databaseChange.operation : "";
        }

        public boolean mayMemberListChanged() {
            return databaseChange != null
                    && databaseChange.values != null
                    && databaseChange.values.containsKey("memberlist");
        }

        public boolean mayRoomDataChanged() {
            return databaseChange != null
                    && databaseChange.values != null
                    && databaseChange.values.containsKey("roomdata");
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
    private final WeChatChatroomApi chatroomApi;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean installed;

    public WeChatChatroomChangeApi(WeChatDatabaseListenerApi databaseListenerApi,
                                   WeChatChatroomApi chatroomApi,
                                   Logger logger) {
        this.databaseListenerApi = databaseListenerApi;
        this.chatroomApi = chatroomApi;
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
        log("群聊变更监听已安装");
    }

    private void onDatabaseChanged(DatabaseChange change) {
        if (change == null || !"chatroom".equalsIgnoreCase(change.table)) return;
        if (listeners.isEmpty()) return;
        WeChatChatroom chatroom = resolveChatroom(change);
        ChatroomChange event = new ChatroomChange(change, chatroom);
        for (Listener listener : listeners) {
            try {
                listener.onChatroomChanged(event);
            } catch (Throwable e) {
                log("群聊变更回调失败: " + e.getMessage());
            }
        }
    }

    private WeChatChatroom resolveChatroom(DatabaseChange change) {
        String chatroomId = str(change.values, "chatroomname");
        if (TextUtils.isEmpty(chatroomId)
                && change.whereClause != null
                && change.whereClause.toLowerCase().contains("chatroomname")
                && change.whereArgs != null
                && change.whereArgs.length > 0) {
            chatroomId = change.whereArgs[0];
        }
        if (!TextUtils.isEmpty(chatroomId) && chatroomApi != null) {
            return chatroomApi.getChatroom(chatroomId);
        }
        return null;
    }

    private static String str(ContentValues values, String key) {
        if (values == null || TextUtils.isEmpty(key) || !values.containsKey(key)) return "";
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatChatroomChangeApi] " + message);
    }
}
