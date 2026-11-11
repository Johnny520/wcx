package h.Hchat.hooks.items.payment.notify;

import android.text.TextUtils;

import h.Hchat.hooks.items.payment.core.RedPacketEffectiveRule;
import h.Hchat.hooks.items.payment.core.RedPacketRuleResolver;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.core.RedPacketState;
import h.Hchat.hooks.items.payment.core.RedPacketStats;

/**
 * 红包通知协调层。
 */
public final class RedPacketNotificationCenter {
    private final RedPacketSettings settings;
    private final RedPacketState state;
    private final RedPacketStats stats;
    private final RedPacketNotifier notifier;
    private final RedPacketTemplateFormatter formatter;

    public RedPacketNotificationCenter(RedPacketSettings settings,
                                       RedPacketState state,
                                       RedPacketStats stats,
                                       RedPacketNotifier notifier,
                                       RedPacketTemplateFormatter formatter) {
        this.settings = settings;
        this.state = state;
        this.stats = stats;
        this.notifier = notifier;
        this.formatter = formatter;
    }

    public void notifyIncoming(String talker, String nativeUrl) {
        notifyIncoming(talker, nativeUrl, null);
    }

    public void notifyIncoming(String talker, String nativeUrl, RedPacketEffectiveRule rule) {
        talker = resolveTalker(talker, nativeUrl);
        rule = resolveRule(nativeUrl, rule);
        boolean system = rule.getNotifySystemEnabled();
        boolean toast = rule.getNotifyToastEnabled();
        if (!system && !toast) return;
        boolean sound = rule.getNotifySoundEnabled();
        boolean vibrate = rule.getNotifyVibrateEnabled();
        String soundUri = resolveSoundUri(rule);
        notifier.sendNotice(
                format(settings.getString(RedPacketSettings.KEY_NOTIFY_TITLE, "自动抢红包"),
                        "", talker, nativeUrl, ""),
                format("收到红包，点击打开",
                        "", talker, nativeUrl, ""),
                format("收到红包，点击打开",
                        "", talker, nativeUrl, ""),
                talker,
                nativeUrl,
                200000,
                system,
                toast,
                sound,
                vibrate,
                soundUri);
    }

    public void notifyReceived(String amount, String talker, String nativeUrl) {
        notifyReceived(amount, talker, nativeUrl, null);
    }

    public void notifyReceived(String amount, String talker, String nativeUrl, RedPacketEffectiveRule rule) {
        talker = resolveTalker(talker, nativeUrl);
        rule = resolveRule(nativeUrl, rule);
        stats.recordAmount(amount, nativeUrl);
        boolean system = rule.getNotifySystemEnabled();
        boolean toast = rule.getNotifyToastEnabled();
        if (!system && !toast) return;
        boolean sound = rule.getNotifySoundEnabled();
        boolean vibrate = rule.getNotifyVibrateEnabled();
        String soundUri = resolveSoundUri(rule);
        String key = TextUtils.isEmpty(nativeUrl) ? ("t_" + talker + "_" + amount) : nativeUrl;
        if (!state.markNotified(key)) return;
        String safeAmount = TextUtils.isEmpty(amount) ? "未知" : amount.replace("元", "");
        notifier.sendNotice(
                format(settings.getString(RedPacketSettings.KEY_NOTIFY_TITLE, "自动抢红包"),
                        safeAmount, talker, nativeUrl, ""),
                format(nonEmpty(rule.getNotifyText(), "抢到红包 {amount} 元"),
                        safeAmount, talker, nativeUrl, ""),
                format(nonEmpty(rule.getNotifyToastText(), "抢到红包 {amount} 元"),
                        safeAmount, talker, nativeUrl, ""),
                talker,
                nativeUrl,
                0,
                system,
                toast,
                sound,
                vibrate,
                soundUri);
    }

    public void notifyFailed(String talker, String nativeUrl, String reason) {
        notifyFailed(talker, nativeUrl, reason, null);
    }

    public void notifyFailed(String talker, String nativeUrl, String reason, RedPacketEffectiveRule rule) {
        talker = resolveTalker(talker, nativeUrl);
        rule = resolveRule(nativeUrl, rule);
        if (stats.hasSuccessRecorded(nativeUrl)) return;
        String key = TextUtils.isEmpty(nativeUrl) ? ("f_" + talker + "_" + reason) : nativeUrl;
        stats.incrementFailure(nativeUrl, key);
        boolean system = rule.getNotifyFailedSystemEnabled();
        boolean toast = rule.getNotifyFailedToastEnabled();
        if (!system && !toast) return;
        boolean sound = rule.getNotifySoundEnabled();
        boolean vibrate = rule.getNotifyVibrateEnabled();
        String soundUri = resolveSoundUri(rule);
        if (!state.markFailedNotified("notify:" + key)) return;
        notifier.sendNotice(
                format(settings.getString(RedPacketSettings.KEY_NOTIFY_FAILED_TITLE, "未抢到红包"),
                        "", talker, nativeUrl, reason),
                format(nonEmpty(rule.getNotifyFailedText(), "未抢到红包"),
                        "", talker, nativeUrl, reason),
                format(nonEmpty(rule.getNotifyFailedToastText(), "未抢到红包"),
                        "", talker, nativeUrl, reason),
                talker,
                nativeUrl,
                100000,
                system,
                toast,
                sound,
                vibrate,
                soundUri);
    }

    private String format(String template, String amount,
                          String talker, String nativeUrl, String reason) {
        return formatter.format(template, amount, talker, nativeUrl, reason);
    }

    private String resolveTalker(String talker, String nativeUrl) {
        if (!TextUtils.isEmpty(talker)) return talker;
        return !TextUtils.isEmpty(nativeUrl) ? state.talkerMap.get(nativeUrl) : talker;
    }

    private RedPacketEffectiveRule resolveRule(String nativeUrl, RedPacketEffectiveRule rule) {
        if (rule != null) return rule;
        if (!TextUtils.isEmpty(nativeUrl)) {
            RedPacketEffectiveRule cached = state.ruleMap.get(nativeUrl);
            if (cached != null) return cached;
        }
        return RedPacketRuleResolver.legacyRule(settings);
    }

    private String resolveSoundUri(RedPacketEffectiveRule rule) {
        return rule != null ? rule.getNotifySoundUri() : "";
    }

    private String nonEmpty(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }
}
