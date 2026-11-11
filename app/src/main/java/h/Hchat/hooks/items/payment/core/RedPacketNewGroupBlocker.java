package h.Hchat.hooks.items.payment.core;

import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import h.Hchat.hooks.api.contact.WeChatChatroomApi;
import h.Hchat.hooks.api.contact.WeChatChatroomChangeApi;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.model.DatabaseChange;
import h.Hchat.hooks.api.model.WeChatChatroom;
import h.Hchat.hooks.items.payment.detect.RedPacketParser;

/**
 * 记录开启保护后新出现的群聊，并自动写入关闭状态的适用聊天规则。
 */
public final class RedPacketNewGroupBlocker {
    private final RedPacketSettings settings;
    private final Consumer<String> logger;
    private volatile boolean subscribed;
    private volatile boolean baselineLoaded;

    public RedPacketNewGroupBlocker(RedPacketSettings settings, Consumer<String> logger) {
        this.settings = settings;
        this.logger = logger;
    }

    public synchronized Object install() {
        preloadKnownGroups();
        if (subscribed) return null;
        WeChatChatroomChangeApi api = null;
        try {
            api = WeChatApis.contact().chatroomChanges();
        } catch (Throwable ignored) {
        }
        if (api == null) {
            try {
                api = WeChatApis.chatroomChanges();
            } catch (Throwable ignored) {
            }
        }
        if (api == null) {
            log("群聊变更 API 未就绪，新进群屏蔽只使用已记录名单");
            return null;
        }
        Object subscription = api.subscribe(this::handleChatroomChange);
        subscribed = subscription != null;
        return subscription;
    }

    private void preloadKnownGroups() {
        SharedPreferences prefs = settings.getHostPreferences();
        if (prefs == null) return;
        try {
            WeChatChatroomApi chatrooms = WeChatApis.contact().chatrooms();
            if (chatrooms == null) return;
            Set<String> known = readIdSet(RedPacketSettings.KEY_BLOCK_NEW_GROUP_KNOWN);
            boolean changed = false;
            for (WeChatChatroom room : chatrooms.getChatrooms()) {
                if (room == null) continue;
                String groupId = normalizeGroupId(room.chatroomId);
                if (!TextUtils.isEmpty(groupId) && known.add(groupId)) changed = true;
            }
            if (changed) writeIdSet(RedPacketSettings.KEY_BLOCK_NEW_GROUP_KNOWN, known);
            baselineLoaded = true;
        } catch (Throwable e) {
            log("预加载群聊基线失败: " + e.getMessage());
        }
    }

    private void handleChatroomChange(WeChatChatroomChangeApi.ChatroomChange change) {
        if (change == null) return;
        String groupId = normalizeGroupId(change.chatroomId());
        if (TextUtils.isEmpty(groupId)) return;
        String operation = change.operation();
        if (DatabaseChange.DELETE.equals(operation)) {
            removeKnownGroup(groupId);
            return;
        }
        Set<String> known = readIdSet(RedPacketSettings.KEY_BLOCK_NEW_GROUP_KNOWN);
        boolean firstSeen = known.add(groupId);
        if (!firstSeen) return;
        writeIdSet(RedPacketSettings.KEY_BLOCK_NEW_GROUP_KNOWN, known);
        boolean newRow = DatabaseChange.INSERT.equals(operation) || baselineLoaded;
        if (newRow && settings.getBoolean(RedPacketSettings.KEY_BLOCK_NEW_GROUP_ENABLE, false)
                && addDisabledBinding(groupId, change.chatroom)) {
            log("已自动加入新进群红包关闭规则: " + groupId);
        }
    }

    private void removeKnownGroup(String groupId) {
        Set<String> known = readIdSet(RedPacketSettings.KEY_BLOCK_NEW_GROUP_KNOWN);
        if (known.remove(groupId)) {
            writeIdSet(RedPacketSettings.KEY_BLOCK_NEW_GROUP_KNOWN, known);
        }
    }

    private boolean addDisabledBinding(String groupId, WeChatChatroom chatroom) {
        String targetId = normalizeGroupId(groupId);
        if (TextUtils.isEmpty(targetId)) return false;
        String rawBindings = settings.getString(RedPacketRuleConfig.KEY_BINDINGS, "");
        for (RedPacketRuleBinding binding : RedPacketRuleConfig.parseBindings(rawBindings)) {
            if (TextUtils.equals(RedPacketRuleConfig.bindingKey(binding.getTargetId()),
                    RedPacketRuleConfig.bindingKey(targetId))) {
                return false;
            }
        }
        java.util.List<RedPacketRuleBinding> next = new java.util.ArrayList<>(
                RedPacketRuleConfig.parseBindings(rawBindings)
        );
        next.add(new RedPacketRuleBinding(
                RedPacketRuleConfig.bindingKey(targetId),
                targetId,
                bindingLabel(targetId, chatroom),
                false,
                defaultTemplateId(),
                false,
                null
        ));
        SharedPreferences prefs = settings.getHostPreferences();
        if (prefs == null) return false;
        prefs.edit()
                .putString(RedPacketRuleConfig.KEY_BINDINGS, RedPacketRuleConfig.encodeBindings(next))
                .commit();
        return true;
    }

    private String defaultTemplateId() {
        java.util.List<RedPacketRuleTemplate> templates = settings.ruleTemplates();
        String defaultId = settings.getString(RedPacketRuleConfig.KEY_DEFAULT_TEMPLATE_ID, "").trim();
        for (RedPacketRuleTemplate template : templates) {
            if (TextUtils.equals(template.getId(), defaultId)) return defaultId;
        }
        return templates.size() == 1 ? templates.get(0).getId() : "";
    }

    private String bindingLabel(String groupId, WeChatChatroom chatroom) {
        if (chatroom != null && !TextUtils.isEmpty(chatroom.name)) return chatroom.name;
        try {
            String name = WeChatApis.contact().chatrooms().getChatroomName(groupId);
            if (!TextUtils.isEmpty(name)) return name;
        } catch (Throwable ignored) {
        }
        return groupId;
    }

    private Set<String> readIdSet(String key) {
        Set<String> result = new LinkedHashSet<>();
        String raw = settings.getString(key, "");
        if (TextUtils.isEmpty(raw)) return result;
        String[] parts = raw.split("[|,，\\n\\r]+");
        for (String part : parts) {
            String groupId = normalizeGroupId(part);
            if (!TextUtils.isEmpty(groupId)) result.add(groupId);
        }
        return result;
    }

    private void writeIdSet(String key, Set<String> values) {
        SharedPreferences prefs = settings.getHostPreferences();
        if (prefs == null) return;
        prefs.edit().putString(key, join(values)).commit();
    }

    private String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            String groupId = normalizeGroupId(value);
            if (TextUtils.isEmpty(groupId)) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(groupId);
        }
        return builder.toString();
    }

    private String normalizeGroupId(String value) {
        String groupId = RedPacketParser.normalizeUsername(value);
        return RedPacketParser.isGroupTalker(groupId) ? groupId : "";
    }

    private void log(String message) {
        if (logger != null && !TextUtils.isEmpty(message)) logger.accept(message);
    }
}
