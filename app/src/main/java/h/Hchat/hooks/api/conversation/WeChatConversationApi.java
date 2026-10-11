package h.Hchat.hooks.api.conversation;

import android.database.Cursor;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.contact.WeChatContactApi;
import h.Hchat.hooks.api.media.WeChatInternalServices;
import h.Hchat.hooks.api.model.WeChatConversation;
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi;
import h.Hchat.hooks.api.ui.WeChatNotifyApi;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 微信会话 API。
 *
 * 会话查询只读取 rconversation 稳定字段，删除使用微信原生会话存储入口。
 */
public final class WeChatConversationApi {
    private static final String HCHAT_INTERNAL_PREFIX = "wxid_hchat_group_";

    public interface Logger {
        void log(String message);
    }

    private static final String CONVERSATION_COLUMNS =
            "username, unReadCount, status, isSend, conversationTime, content, "
                    + "msgType, flag, digest, digestUser, atCount, unReadMuteCount, hasTodo";

    private final WeChatDatabaseApi databaseApi;
    private final WeChatContactApi contactApi;
    private final WeChatNotifyApi notifyApi;
    private final DexFinder dexFinder;
    private final Logger logger;

    public WeChatConversationApi(WeChatDatabaseApi databaseApi,
                                 WeChatContactApi contactApi,
                                 WeChatNotifyApi notifyApi,
                                 DexFinder dexFinder,
                                 Logger logger) {
        this.databaseApi = databaseApi;
        this.contactApi = contactApi;
        this.notifyApi = notifyApi;
        this.dexFinder = dexFinder;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return databaseApi != null && databaseApi.isAvailable();
    }

    public WeChatConversation getConversation(String username) {
        if (TextUtils.isEmpty(username)) return null;
        List<WeChatConversation> rows = queryConversations(
                "SELECT " + CONVERSATION_COLUMNS + " FROM rconversation WHERE username=? LIMIT 1",
                new String[]{username});
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WeChatConversation> getRecentConversations(int limit) {
        int safeLimit = clamp(limit, 1, 10000);
        return queryConversations(
                "SELECT " + CONVERSATION_COLUMNS
                        + " FROM rconversation WHERE username!='' "
                        + "ORDER BY conversationTime DESC LIMIT ?",
                new String[]{String.valueOf(safeLimit)});
    }

    /** Returns only recent talker IDs for picker ordering. */
    public List<String> getRecentConversationUsernames(int limit) {
        List<String> result = new ArrayList<>();
        if (databaseApi == null) return result;
        int safeLimit = clamp(limit, 1, 10000);
        try {
            for (Map<String, Object> row : databaseApi.query(
                    "SELECT username FROM rconversation WHERE username!='' "
                            + "ORDER BY conversationTime DESC LIMIT ?",
                    new String[]{String.valueOf(safeLimit)})) {
                String username = str(row, "username");
                if (TextUtils.isEmpty(username) || username.startsWith(HCHAT_INTERNAL_PREFIX)) continue;
                result.add(username);
            }
        } catch (Throwable e) {
            log("会话用户名查询失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 微信会话是否处于原生消息免打扰状态。
     */
    public boolean isWechatDoNotDisturb(String username) {
        return Boolean.TRUE.equals(getWechatDoNotDisturbState(username));
    }

    /**
     * 返回微信会话的原生消息免打扰状态，无法可靠读取时返回 null。
     */
    public Boolean getWechatDoNotDisturbState(String username) {
        String talker = username != null ? username.trim() : "";
        if (TextUtils.isEmpty(talker) || contactApi == null || dexFinder == null) return null;
        boolean chatroom = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom");
        if (chatroom) return contactApi.getChatroomDoNotDisturbState(talker);
        Method method = dexFinder.contactMuteStateMethod;
        if (method != null) {
            Object contact = contactApi.getNativeContactObject(talker);
            if (contact != null && method.getParameterTypes()[0].isInstance(contact)) {
                try {
                    return Boolean.TRUE.equals(KavaReflector.invokeOrThrow(method, null, contact));
                } catch (Throwable e) {
                    log("读取微信免打扰状态失败: " + e.getMessage() + " talker=" + talker);
                }
            }
        }
        return null;
    }

    /**
     * 使用微信原生联系人或 RoomSDK 入口开启、解除消息免打扰。
     */
    public boolean setWechatDoNotDisturb(String username, boolean enabled) {
        String talker = username != null ? username.trim() : "";
        if (TextUtils.isEmpty(talker) || contactApi == null || dexFinder == null) return false;
        if (contactApi.isGroup(talker)) {
            return setChatroomDoNotDisturb(talker, enabled);
        }
        Method method = enabled
                ? dexFinder.contactMuteEnableMethod
                : dexFinder.contactMuteDisableMethod;
        Object contact = contactApi.getNativeContactObject(talker);
        if (method == null) {
            log("私聊免打扰API尚未就绪: muteMethod=null talker="
                    + talker + " enabled=" + enabled);
            return false;
        }
        if (contact == null) {
            log("私聊免打扰API尚未就绪: contact=null talker="
                    + talker + " enabled=" + enabled);
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 2 || !params[0].isInstance(contact)) {
            log("私聊免打扰API尚未就绪: typeMismatch method="
                    + method.toGenericString() + " contact=" + contact.getClass().getName()
                    + " talker=" + talker + " enabled=" + enabled);
            return false;
        }
        try {
            KavaReflector.invokeOrThrow(method, null, contact, Boolean.TRUE);
            return true;
        } catch (Throwable e) {
            log("私聊免打扰设置失败: " + e.getMessage()
                    + " talker=" + talker + " enabled=" + enabled);
            return false;
        }
    }

    private boolean setChatroomDoNotDisturb(String talker, boolean enabled) {
        Method getter = dexFinder.chatroomMuteServiceGetterMethod;
        Method build = dexFinder.chatroomMuteBuildMethod;
        Method submit = dexFinder.chatroomMuteSubmitMethod;
        if (getter == null || build == null || submit == null) {
            log("群聊免打扰API尚未就绪: talker=" + talker);
            return false;
        }
        try {
            Object service = WeChatInternalServices.getService(dexFinder, getter.getDeclaringClass());
            Object manager = KavaReflector.invokeOrThrow(getter, service, talker);
            if (manager == null || !build.getDeclaringClass().isInstance(manager)) {
                log("群聊免打扰RoomSDK实例为空: talker=" + talker);
                return false;
            }
            int notifyMsg = enabled ? 0 : 1;
            Object operation;
            if (build.getParameterTypes().length == 3) {
                operation = KavaReflector.invokeOrThrow(build, manager, talker, notifyMsg, 0);
            } else {
                operation = KavaReflector.invokeOrThrow(build, manager, talker, notifyMsg);
            }
            if (operation == null || !submit.getDeclaringClass().isInstance(operation)) {
                log("群聊免打扰操作对象为空: talker=" + talker);
                return false;
            }
            KavaReflector.invokeOrThrow(submit, operation);
            return true;
        } catch (Throwable e) {
            log("群聊免打扰设置失败: " + e.getMessage()
                    + " talker=" + talker + " enabled=" + enabled);
            return false;
        }
    }

    public List<WeChatConversation> getUnreadConversations(int limit) {
        int safeLimit = clamp(limit, 1, 200);
        return queryConversations(
                "SELECT " + CONVERSATION_COLUMNS
                        + " FROM rconversation WHERE username!='' AND unReadCount>0 "
                        + "ORDER BY conversationTime DESC LIMIT ?",
                new String[]{String.valueOf(safeLimit)});
    }

    public List<WeChatConversation> searchConversations(String keyword, int limit) {
        if (TextUtils.isEmpty(keyword)) return new ArrayList<>();
        int safeLimit = clamp(limit, 1, 200);
        String like = "%" + keyword.trim() + "%";
        return queryConversations(
                "SELECT c." + CONVERSATION_COLUMNS.replace(", ", ", c.")
                        + " FROM rconversation c "
                        + "LEFT JOIN rcontact r ON c.username = r.username "
                        + "WHERE c.username LIKE ? OR c.content LIKE ? OR c.digest LIKE ? "
                        + "OR r.nickname LIKE ? OR r.conRemark LIKE ? OR r.alias LIKE ? "
                        + "ORDER BY c.conversationTime DESC LIMIT ?",
                new String[]{like, like, like, like, like, like, String.valueOf(safeLimit)});
    }

    public int getUnreadCount(String username) {
        if (TextUtils.isEmpty(username) || databaseApi == null) return 0;
        String value = databaseApi.queryFirstString(
                "SELECT unReadCount FROM rconversation WHERE username=? LIMIT 1",
                new String[]{username},
                "unReadCount");
        return parseInt(value);
    }

    public int getTotalUnreadCount() {
        if (databaseApi == null) return 0;
        String value = databaseApi.queryFirstString(
                "SELECT IFNULL(SUM(unReadCount),0) AS total FROM rconversation "
                        + "WHERE username NOT LIKE ?",
                new String[]{HCHAT_INTERNAL_PREFIX + "%"},
                "total");
        return parseInt(value);
    }

    public String getConversationTitle(String username) {
        if (TextUtils.isEmpty(username)) return "";
        if (contactApi == null) return username;
        String name = contactApi.getDisplayName(username);
        return !TextUtils.isEmpty(name) ? name : username;
    }

    public boolean isGroupConversation(String username) {
        return contactApi != null && contactApi.isGroup(username);
    }

    public boolean isOfficialAccount(String username) {
        return !TextUtils.isEmpty(username) && username.startsWith("gh_");
    }

    public boolean openChat(String username) {
        return notifyApi != null && notifyApi.openChat(username);
    }

    /**
     * 删除本地首页会话项，不删除消息、联系人或群资料，也不会退出群聊。
     */
    public boolean deleteConversation(String username) {
        String target = username != null ? username.trim() : "";
        if (TextUtils.isEmpty(target) || databaseApi == null || dexFinder == null) return false;

        Method method = dexFinder.conversationDeleteMethod;
        if (method == null) {
            log("原生会话删除方法尚未就绪");
            return false;
        }
        Object storage = databaseApi.storageObjectForMethod(method);
        if (storage == null) {
            log("未找到原生会话 storage: " + method.getDeclaringClass().getName());
            return false;
        }

        try {
            KavaReflector.invokeOrThrow(method, storage, target);
        } catch (Throwable e) {
            log("原生会话删除失败: " + e.getMessage() + " talker=" + target);
            return false;
        }

        Boolean remains = conversationEntryExists(target);
        if (remains == null) {
            log("无法验证原生会话删除结果: talker=" + target);
            return false;
        }
        return !remains;
    }

    private Boolean conversationEntryExists(String username) {
        Cursor cursor = databaseApi.rawQuery(
                "SELECT 1 FROM rconversation WHERE username=? LIMIT 1",
                new String[]{username});
        if (cursor == null) return null;
        try {
            return cursor.moveToFirst();
        } catch (Throwable e) {
            return null;
        } finally {
            try {
                cursor.close();
            } catch (Throwable ignored) {}
        }
    }

    private List<WeChatConversation> queryConversations(String sql, String[] args) {
        List<WeChatConversation> result = new ArrayList<>();
        if (databaseApi == null) return result;
        try {
            for (Map<String, Object> row : databaseApi.query(sql, args)) {
                if (str(row, "username").startsWith(HCHAT_INTERNAL_PREFIX)) continue;
                result.add(toConversation(row));
            }
        } catch (Throwable e) {
            log("会话查询失败: " + e.getMessage());
        }
        return result;
    }

    private WeChatConversation toConversation(Map<String, Object> row) {
        return new WeChatConversation(
                str(row, "username"),
                intValue(row, "unReadCount"),
                intValue(row, "status"),
                intValue(row, "isSend"),
                longValue(row, "conversationTime"),
                str(row, "content"),
                str(row, "msgType"),
                longValue(row, "flag"),
                str(row, "digest"),
                str(row, "digestUser"),
                intValue(row, "atCount"),
                intValue(row, "unReadMuteCount"),
                intValue(row, "hasTodo"));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String str(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        return value != null ? String.valueOf(value) : "";
    }

    private static int intValue(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        if (value instanceof Number) return ((Number) value).intValue();
        return parseInt(value != null ? String.valueOf(value) : "");
    }

    private static long longValue(Map<String, Object> row, String key) {
        Object value = row != null ? row.get(key) : null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value != null ? String.valueOf(value) : "");
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatConversationApi] " + message);
    }
}
