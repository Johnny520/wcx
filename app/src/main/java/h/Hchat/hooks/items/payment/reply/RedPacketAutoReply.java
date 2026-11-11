package h.Hchat.hooks.items.payment.reply;

import android.text.TextUtils;

import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.media.WeChatMediaApi;
import h.Hchat.hooks.api.message.WeChatMessageApi;
import h.Hchat.hooks.api.runtime.WeChatTaskApi;
import h.Hchat.hooks.items.payment.core.RedPacketEffectiveRule;
import h.Hchat.hooks.items.payment.core.RedPacketReplyStep;
import h.Hchat.hooks.items.payment.core.RedPacketRuleConfig;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.core.RedPacketState;
import h.Hchat.hooks.items.payment.notify.RedPacketTemplateFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 抢到红包后的自动回复。
 */
public final class RedPacketAutoReply {
    public interface Logger {
        void log(String message);
    }

    public interface LoginProvider {
        String getLoginWxid();
    }

    private final RedPacketSettings settings;
    private final RedPacketState state;
    private final LoginProvider loginProvider;
    private final Logger logger;
    private final RedPacketTemplateFormatter formatter;
    private final Random random = new Random();
    private final Set<String> pendingReplyTaskKeys = ConcurrentHashMap.newKeySet();
    private static final String AT_SENDER_TOKEN = "{@发红包的人}";
    private static final String[] AT_SENDER_TOKENS = new String[] {
            AT_SENDER_TOKEN,
            "{@sender}",
            "{@成员}"
    };

    public RedPacketAutoReply(RedPacketSettings settings,
                              RedPacketState state,
                              LoginProvider loginProvider,
                              Logger logger) {
        this.settings = settings;
        this.state = state;
        this.loginProvider = loginProvider;
        this.logger = logger;
        this.formatter = new RedPacketTemplateFormatter(state, settings, this::log);
    }

    public void replyAfterGrabbed(String nativeUrl, String fallbackTalker,
                                  String amount, boolean selfSent) {
        try {
            if (!isReplyEnabled()) return;
            RedPacketEffectiveRule rule = !TextUtils.isEmpty(nativeUrl) ? state.ruleMap.get(nativeUrl) : null;
            if (rule != null && !rule.getEnabled()) return;
            if (selfSent || isSelfSender(nativeUrl)) {
                log("自动回复跳过: 自己发的红包");
                return;
            }

            String talker = !TextUtils.isEmpty(nativeUrl) ? state.talkerMap.get(nativeUrl) : null;
            if (TextUtils.isEmpty(talker)) talker = fallbackTalker;
            if (TextUtils.isEmpty(talker)) return;

            List<ReplyPlan> plans = buildReplyPlans(
                    replySteps(rule, isGroupTalker(talker)), amount, talker, nativeUrl);
            if (plans.isEmpty()) return;

            final String finalTalker = talker;
            final String sender = formatter.resolveSenderId(nativeUrl);
            final String replyKey = "redpacket_reply:"
                    + (!TextUtils.isEmpty(nativeUrl) ? nativeUrl : finalTalker + ":" + amount);
            WeChatTaskApi tasks = WeChatApis.runtime().tasks();
            if (tasks == null) {
                sendReplySequence(finalTalker, sender, plans);
                return;
            }
            if (!tasks.runOnce(replyKey, () -> {
                // runOnce 只标记本红包，实际发送交给回复队列。
            })) {
                log("自动回复跳过: 已处理 " + replyKey);
                return;
            }
            scheduleReplyStep(tasks, replyKey, finalTalker, sender, plans, 0);
        } catch (Throwable e) {
            log("自动回复失败: " + e.getMessage());
        }
    }

    private void scheduleReplyStep(WeChatTaskApi tasks, String replyKey, String talker,
                                   String sender, List<ReplyPlan> plans, int index) {
        if (index >= plans.size()) return;
        ReplyPlan plan = plans.get(index);
        String taskKey = replyKey + ":step:" + index;
        pendingReplyTaskKeys.add(taskKey);
        tasks.runOnMainDelayed(taskKey, plan.delayMs, () -> {
            pendingReplyTaskKeys.remove(taskKey);
            if (!isReplyEnabled()) {
                log("自动回复跳过: 全局开关已关闭");
                tasks.clearRunState(replyKey);
                return;
            }
            sendReply(talker, plan.content, sender, plan.atSender, plan.replyMode, plan.delayMs);
            if (isReplyEnabled()) {
                scheduleReplyStep(tasks, replyKey, talker, sender, plans, index + 1);
            }
            clearReplyRunState(tasks, replyKey, index, plans.size());
        });
    }

    public void cancelPendingReplies() {
        WeChatTaskApi tasks = WeChatApis.runtime().tasks();
        if (tasks != null) {
            for (String key : pendingReplyTaskKeys) {
                tasks.cancel(key);
                int separator = key.indexOf(":step:");
                if (separator > 0) tasks.clearRunState(key.substring(0, separator));
            }
        }
        pendingReplyTaskKeys.clear();
    }

    private void clearReplyRunState(WeChatTaskApi tasks, String replyKey, int index, int size) {
        if (index + 1 >= size) tasks.clearRunState(replyKey);
    }

    private void sendReplySequence(String talker, String sender, List<ReplyPlan> plans) {
        for (ReplyPlan plan : plans) {
            sendReply(talker, plan.content, sender, plan.atSender, plan.replyMode, plan.delayMs);
        }
    }

    private void sendReply(String talker, String content, String sender,
                           boolean atSender, int replyMode, long delay) {
        try {
            if (!isReplyEnabled()) {
                log("自动回复跳过: 全局开关已关闭");
                return;
            }
            boolean sent = sendByMode(talker, content, sender, atSender, replyMode);
            log("自动回复" + replyModeName(replyMode) + (sent ? "已发送" : "发送失败")
                    + ": " + content + " -> " + talker
                    + (atSender ? " at=" + sender : "")
                    + " delay=" + delay + "ms");
        } catch (Throwable e) {
            log("自动回复异常: " + e.getMessage());
        }
    }

    private boolean isReplyEnabled() {
        return settings.getBoolean(RedPacketSettings.KEY_REPLY_ENABLE, false);
    }

    private boolean sendByMode(String talker, String content, String sender,
                               boolean atSender, int replyMode) {
        WeChatMessageApi messageApi = WeChatApis.message().sender();
        WeChatMediaApi mediaApi = WeChatApis.media();
        switch (replyMode) {
            case RedPacketRuleConfig.REPLY_TEXT:
                if (messageApi == null) return false;
                if (atSender && !TextUtils.isEmpty(sender)) {
                    List<String> atList = new ArrayList<>();
                    atList.add(sender);
                    return messageApi.sendTextWithAtList(talker, content, atList);
                }
                return messageApi.sendText(talker, content);
            case RedPacketRuleConfig.REPLY_IMAGE:
                return mediaApi != null && mediaApi.sendImage(talker, content);
            case RedPacketRuleConfig.REPLY_VOICE:
                return mediaApi != null && mediaApi.sendVoice(talker, content);
            case RedPacketRuleConfig.REPLY_VIDEO:
                return mediaApi != null && mediaApi.videos().send(talker, content);
            case RedPacketRuleConfig.REPLY_EMOJI:
                return mediaApi != null && mediaApi.sendEmoji(talker, content);
            case RedPacketRuleConfig.REPLY_FILE:
                return mediaApi != null && mediaApi.sendFile(talker, content);
            case RedPacketRuleConfig.REPLY_XML:
                return messageApi != null && messageApi.sendXml(talker, content);
            case RedPacketRuleConfig.REPLY_FAVORITE:
                return mediaApi != null && mediaApi.favorites().send(talker, content);
            default:
                return false;
        }
    }

    private List<RedPacketReplyStep> replySteps(RedPacketEffectiveRule rule, boolean group) {
        if (rule != null) {
            List<RedPacketReplyStep> steps = group
                    ? rule.getGroupReplySteps()
                    : rule.getReplySteps();
            return steps != null ? steps : new ArrayList<>();
        }
        if (!settings.getBoolean(RedPacketSettings.KEY_REPLY_ENABLE, false)) return new ArrayList<>();
        if (group && settings.contains(RedPacketSettings.KEY_REPLY_GROUP_ITEMS)) {
            return RedPacketRuleConfig.parseReplySteps(
                    settings.getString(RedPacketSettings.KEY_REPLY_GROUP_ITEMS, ""));
        }
        String stored = settings.getString(RedPacketSettings.KEY_REPLY_ITEMS, "");
        if (!TextUtils.isEmpty(stored)) return RedPacketRuleConfig.parseReplySteps(stored);
        int replyMode = settings.getInt(RedPacketSettings.KEY_REPLY_TYPE, RedPacketRuleConfig.REPLY_TEXT);
        String content = isMediaReply(replyMode)
                ? settings.getString(RedPacketSettings.KEY_REPLY_MEDIA_PATHS, "")
                : settings.getString(
                        RedPacketSettings.KEY_REPLY_TEMPLATES,
                        settings.getString(RedPacketSettings.KEY_REPLY_TEXT, "谢谢老板"));
        return RedPacketRuleConfig.legacyReplySteps(
                replyMode,
                content,
                getReplyDelayMillis(),
                settings.getBoolean(RedPacketSettings.KEY_REPLY_RANDOM, false));
    }

    private boolean isGroupTalker(String talker) {
        return !TextUtils.isEmpty(talker)
                && (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"));
    }

    private List<ReplyPlan> buildReplyPlans(List<RedPacketReplyStep> steps, String amount,
                                            String talker, String nativeUrl) {
        List<ReplyPlan> plans = new ArrayList<>();
        if (steps == null || steps.isEmpty()) return plans;
        for (RedPacketReplyStep step : steps) {
            ReplyPayload payload = buildReplyPayload(step.getContent(), amount, talker, nativeUrl, step.getMode());
            if (payload == null || TextUtils.isEmpty(payload.content)) continue;
            plans.add(new ReplyPlan(
                    normalizeReplyMode(step.getMode()),
                    payload.content,
                    payload.atSender,
                    step.nextDelayMillis()));
        }
        return plans;
    }

    private ReplyPayload buildReplyPayload(String templates, String amount,
                                           String talker, String nativeUrl,
                                           int replyMode) {
        String chosen = chooseReplyValue(templates);
        if (TextUtils.isEmpty(chosen)) return null;
        boolean legacyAtMode = replyMode == RedPacketRuleConfig.REPLY_AT_SENDER;
        if (legacyAtMode && !containsAtSenderVariable(chosen)) {
            chosen = AT_SENDER_TOKEN + chosen;
        }
        boolean atSender = (replyMode == RedPacketRuleConfig.REPLY_TEXT || legacyAtMode)
                && containsAtSenderVariable(chosen);
        String content = shouldFormatReply(replyMode)
                ? formatter.format(chosen, amount, talker, nativeUrl, "")
                : chosen;
        return new ReplyPayload(content, atSender);
    }

    private String chooseReplyValue(String text) {
        if (TextUtils.isEmpty(text)) return "";
        try {
            String[] parts = text.split("\\|");
            List<String> valid = new ArrayList<>();
            for (String part : parts) {
                if (!TextUtils.isEmpty(part) && !TextUtils.isEmpty(part.trim())) {
                    valid.add(part.trim());
                }
            }
            if (valid.isEmpty()) return text.trim();
            return valid.get(random.nextInt(valid.size()));
        } catch (Throwable ignored) {
            return text;
        }
    }

    private boolean shouldFormatReply(int replyMode) {
        return replyMode == RedPacketRuleConfig.REPLY_TEXT
                || replyMode == RedPacketRuleConfig.REPLY_AT_SENDER
                || replyMode == RedPacketRuleConfig.REPLY_XML;
    }

    private int normalizeReplyMode(int replyMode) {
        return replyMode == RedPacketRuleConfig.REPLY_AT_SENDER
                ? RedPacketRuleConfig.REPLY_TEXT
                : replyMode;
    }

    private boolean containsAtSenderVariable(String value) {
        if (TextUtils.isEmpty(value)) return false;
        for (String token : AT_SENDER_TOKENS) {
            if (value.contains(token)) return true;
        }
        return false;
    }

    private boolean isMediaReply(int replyMode) {
        return replyMode == RedPacketRuleConfig.REPLY_IMAGE
                || replyMode == RedPacketRuleConfig.REPLY_VOICE
                || replyMode == RedPacketRuleConfig.REPLY_VIDEO
                || replyMode == RedPacketRuleConfig.REPLY_EMOJI
                || replyMode == RedPacketRuleConfig.REPLY_FILE
                || replyMode == RedPacketRuleConfig.REPLY_XML
                || replyMode == RedPacketRuleConfig.REPLY_FAVORITE;
    }

    private String replyModeName(int replyMode) {
        switch (replyMode) {
            case RedPacketRuleConfig.REPLY_IMAGE:
                return "图片";
            case RedPacketRuleConfig.REPLY_VOICE:
                return "语音";
            case RedPacketRuleConfig.REPLY_VIDEO:
                return "视频";
            case RedPacketRuleConfig.REPLY_EMOJI:
                return "表情";
            case RedPacketRuleConfig.REPLY_FILE:
                return "文件";
            case RedPacketRuleConfig.REPLY_XML:
                return "XML";
            case RedPacketRuleConfig.REPLY_FAVORITE:
                return "收藏";
            default:
                return "文本";
        }
    }

    private static final class ReplyPayload {
        final String content;
        final boolean atSender;

        ReplyPayload(String content, boolean atSender) {
            this.content = content;
            this.atSender = atSender;
        }
    }

    private static final class ReplyPlan {
        final int replyMode;
        final String content;
        final boolean atSender;
        final long delayMs;

        ReplyPlan(int replyMode, String content, boolean atSender, long delayMs) {
            this.replyMode = replyMode;
            this.content = content;
            this.atSender = atSender;
            this.delayMs = delayMs;
        }
    }

    private long getReplyDelayMillis() {
        long delay = 0L;
        if (settings.getBoolean(RedPacketSettings.KEY_REPLY_CUSTOM_ENABLE, false)) {
            int value = Math.max(0, settings.getInt(RedPacketSettings.KEY_REPLY_DELAY_VALUE, 1));
            int unit = settings.getInt(RedPacketSettings.KEY_REPLY_DELAY_UNIT, 1);
            delay += unit == 1 ? value * 1000L : value;
        }
        if (settings.getBoolean(RedPacketSettings.KEY_REPLY_RANDOM, false)) {
            delay += random.nextInt(2000);
        }
        return delay;
    }

    private boolean isSelfSender(String nativeUrl) {
        if (TextUtils.isEmpty(nativeUrl)) return false;
        String sender = state.senderMap.get(nativeUrl);
        String my = loginProvider != null ? loginProvider.getLoginWxid() : "";
        return !TextUtils.isEmpty(sender) && !TextUtils.isEmpty(my) && sender.equals(my);
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
