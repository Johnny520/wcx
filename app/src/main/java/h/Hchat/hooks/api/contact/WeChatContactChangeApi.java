package h.Hchat.hooks.api.contact;

import android.content.ContentValues;
import android.text.TextUtils;

import h.Hchat.hooks.api.model.DatabaseChange;
import h.Hchat.hooks.api.model.WeChatContact;
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 联系人相关表变更监听 API。
 */
public final class WeChatContactChangeApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onContactChanged(ContactChange change);
    }

    public static final class ContactChange {
        public final DatabaseChange databaseChange;
        public final WeChatContact contact;

        private ContactChange(DatabaseChange databaseChange, WeChatContact contact) {
            this.databaseChange = databaseChange;
            this.contact = contact;
        }

        public String wxId() {
            if (contact != null) return contact.wxId;
            return str(databaseChange != null ? databaseChange.values : null, "username");
        }

        public String operation() {
            return databaseChange != null ? databaseChange.operation : "";
        }

        public boolean isAvatarChange() {
            return databaseChange != null && "img_flag".equalsIgnoreCase(databaseChange.table);
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
    private final WeChatContactApi contactApi;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean installed;

    public WeChatContactChangeApi(WeChatDatabaseListenerApi databaseListenerApi,
                                  WeChatContactApi contactApi,
                                  Logger logger) {
        this.databaseListenerApi = databaseListenerApi;
        this.contactApi = contactApi;
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
        log("联系人变更监听已安装");
    }

    private void onDatabaseChanged(DatabaseChange change) {
        if (change == null || (!"rcontact".equalsIgnoreCase(change.table)
                && !"img_flag".equalsIgnoreCase(change.table))) {
            return;
        }
        if (listeners.isEmpty()) return;
        WeChatContact contact = resolveContact(change);
        ContactChange event = new ContactChange(change, contact);
        for (Listener listener : listeners) {
            try {
                listener.onContactChanged(event);
            } catch (Throwable e) {
                log("联系人变更回调失败: " + e.getMessage());
            }
        }
    }

    private WeChatContact resolveContact(DatabaseChange change) {
        String username = str(change.values, "username");
        if (!TextUtils.isEmpty(username) && contactApi != null) {
            WeChatContact stored = contactApi.getContact(username);
            if (stored != null) return stored;
        }
        if (!"rcontact".equalsIgnoreCase(change.table) || change.values == null) return null;
        return new WeChatContact(
                username,
                str(change.values, "nickname"),
                str(change.values, "alias"),
                str(change.values, "conRemark"),
                "",
                "",
                str(change.values, "encryptUsername"),
                str(change.values, "province"),
                str(change.values, "city"),
                intValue(change.values, "sex"),
                intValue(change.values, "type"));
    }

    private static String str(ContentValues values, String key) {
        if (values == null || TextUtils.isEmpty(key) || !values.containsKey(key)) return "";
        Object value = values.get(key);
        return value != null ? String.valueOf(value) : "";
    }

    private static int intValue(ContentValues values, String key) {
        if (values == null || TextUtils.isEmpty(key) || !values.containsKey(key)) return 0;
        try {
            Long value = values.getAsLong(key);
            return value != null ? value.intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatContactChangeApi] " + message);
    }
}
