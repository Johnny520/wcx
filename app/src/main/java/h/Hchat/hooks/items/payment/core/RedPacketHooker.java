package h.Hchat.hooks.items.payment.core;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.message.WeChatMessageObserveApi;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.api.runtime.WeChatTaskApi;
import h.Hchat.hooks.items.payment.detect.RedPacketDatabaseHook;
import h.Hchat.hooks.items.payment.detect.RedPacketFilter;
import h.Hchat.hooks.items.payment.detect.RedPacketMessageHook;
import h.Hchat.hooks.items.payment.detect.RedPacketParser;
import h.Hchat.hooks.items.payment.fake.RedPacketFakePacketHook;
import h.Hchat.hooks.items.payment.grab.RedPacketSilentHandler;
import h.Hchat.hooks.items.payment.grab.RedPacketUiAutomator;
import h.Hchat.hooks.items.payment.notify.RedPacketAnnouncer;
import h.Hchat.hooks.items.payment.notify.RedPacketNotificationCenter;
import h.Hchat.hooks.items.payment.notify.RedPacketNotifier;
import h.Hchat.hooks.items.payment.notify.RedPacketTemplateFormatter;
import h.Hchat.hooks.items.payment.reply.RedPacketAutoReply;
import h.Hchat.hooks.items.payment.reply.RedPacketWishSender;
import h.Hchat.utils.KavaReflector;

/**
 * RedPacketHooker - 红包抢包核心逻辑
 * 从原始 BeanShell 脚本移植，保留全部功能逻辑
 */
public class RedPacketHooker {

    // ---- 全局状态 ----
    private final Context hostContext;
    private final ClassLoader classLoader;
    private final DexFinder dexFinder;
    private final RedPacketSettings settings;
    private final RedPacketRuleResolver ruleResolver;
    private final RedPacketState state;
    private final RedPacketStats stats;
    private final RedPacketNotificationCenter notificationCenter;
    private final RedPacketFilter filter;
    private final WeChatNetworkDispatcher networkDispatcher;
    private final RedPacketMessageHook messageHook;
    private final RedPacketDatabaseHook databaseHook;
    private final RedPacketUiAutomator uiAutomator;
    private final RedPacketFakePacketHook fakePacketHook;
    private final RedPacketSilentHandler silentHandler;
    private final RedPacketAutoReply autoReply;
    private final RedPacketWishSender wishSender;
    private final RedPacketAnnouncer announcer;
    private final RedPacketNewGroupBlocker newGroupBlocker;
    private final Set<String> pendingTaskKeys = ConcurrentHashMap.newKeySet();
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    // ---- 快速设置缓存 ----
    private boolean sFastSkipSelf = false;
    private int sFastListMode = 0;
    private int sFastKwMode = 0;
    private String sFastKeywords = "";
    private String sFastMyWxid = "";

    private boolean observeSubscribed = false;

    public RedPacketHooker(Context context, ClassLoader classLoader, DexFinder dexFinder) {
        this.hostContext = context;
        this.classLoader = classLoader;
        this.dexFinder = dexFinder;
        this.settings = new RedPacketSettings(classLoader, context);
        this.ruleResolver = new RedPacketRuleResolver(settings);
        this.state = new RedPacketState();
        this.stats = new RedPacketStats(context, state, this::logx);
        RedPacketTemplateFormatter formatter = new RedPacketTemplateFormatter(state, settings, this::logx);
        this.notificationCenter = new RedPacketNotificationCenter(
                settings,
                state,
                stats,
                new RedPacketNotifier(context),
                formatter
        );
        this.announcer = new RedPacketAnnouncer(context, settings, formatter, this::logx);
        this.newGroupBlocker = new RedPacketNewGroupBlocker(settings, this::logx);
        this.filter = new RedPacketFilter(settings);
        this.networkDispatcher = new WeChatNetworkDispatcher(this::logx);
        this.messageHook = new RedPacketMessageHook(
                dexFinder,
                settings,
                this::getLoginWxid,
                this::processDetectedRedBag,
                this::logx
        );
        this.databaseHook = new RedPacketDatabaseHook(
                classLoader,
                settings,
                this::getLoginWxid,
                this::processDetectedRedBag,
                this::logx
        );
        this.uiAutomator = new RedPacketUiAutomator(
                classLoader,
                settings,
                state,
                this::shouldFilterRedBag,
                this::handleUiReceived,
                this::handleUiFailed,
                this::logx
        );
        this.fakePacketHook = new RedPacketFakePacketHook(
                classLoader,
                dexFinder,
                settings,
                this::logx
        );
        this.autoReply = new RedPacketAutoReply(
                settings,
                state,
                this::getLoginWxid,
                this::logx
        );
        this.wishSender = new RedPacketWishSender(
                dexFinder,
                settings,
                networkDispatcher,
                this::logx
        );
        this.silentHandler = new RedPacketSilentHandler(
                dexFinder,
                settings,
                state,
                networkDispatcher,
                stats::incrementSuccess,
                this::handleSilentReceived,
                this::handleSilentFailed,
                this::logx
        );
        this.preferenceListener = (preferences, key) -> {
            if (RedPacketSettings.KEY_ENABLE.equals(key) && !settings.isEnabled()) {
                releaseActivePackets();
            }
        };
        SharedPreferences preferences = settings.getHostPreferences();
        if (preferences != null) preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
    }

    // ==================== 入口 ====================

    public List<Object> hookAll() {
        List<Object> subscriptions = new ArrayList<>();
        refreshFastSettingsCache();
        Object newGroupSubscription = newGroupBlocker.install();
        if (newGroupSubscription != null) subscriptions.add(newGroupSubscription);
        logx("hookAll 开始, 收红包类=" + (dexFinder.receiveLuckyMoneyClass != null ? dexFinder.receiveLuckyMoneyClass.getName() : "null")
                + " 拆红包类=" + (dexFinder.openLuckyMoneyClass != null ? dexFinder.openLuckyMoneyClass.getName() : "null"));

        if (!installMessageObserver(subscriptions)) {
            hookAddMsgBeforeDb();
            databaseHook.hook();
        }
        uiAutomator.hook();
        fakePacketHook.hook();

        // 网络队列 hook
        findNetworkQueue();

        // Hook 收红包响应回调 (onGYNetEnd) - 收到响应后自动构造拆红包请求
        silentHandler.hookReceiveCallback();

        // Hook 拆红包响庋回调 (onGYNetEnd) - 拆开后获取金额
        silentHandler.hookOpenCallback();

        if (sFastSkipSelf) {
            sFastMyWxid = getLoginWxid();
        }

        logx("hookAll 完成");
        return subscriptions;
    }

    public boolean isDexReadyEnough() {
        boolean receiveReady = dexFinder.receiveLuckyMoneyClass != null || dexFinder.receiveLuckyMoneyUnionClass != null;
        boolean openReady = dexFinder.openLuckyMoneyClass != null || dexFinder.openLuckyMoneyUnionClass != null;
        return receiveReady && openReady;
    }

    // ==================== 工具方法 ====================

    private void logx(Object msg) {
        RedPacketLogger.log(msg);
    }

    private boolean getBoolean(String key, boolean def) {
        return settings.getBoolean(key, def);
    }

    private int getInt(String key, int def) {
        return settings.getInt(key, def);
    }

    private String getString(String key, String def) {
        return settings.getString(key, def);
    }

    private String getLoginWxid() {
        try {
            if (WeChatApis.contact().account() != null) {
                String wxId = WeChatApis.contact().account().selfWxId();
                if (!TextUtils.isEmpty(wxId)) return wxId;
            }
        } catch (Throwable ignored) {}
        try {
            Object sp = KavaReflector.invokeStaticMethod(
                    KavaReflector.loadClass(
                            "com.tencent.mm.sdk.platformtools.MMApplicationContext",
                            classLoader),
                    "getSharedPreferences", "login_info", 0);
            String w = (String) KavaReflector.invokeMethod(sp, "getString",
                    "login_weixin_username", "");
            if (!TextUtils.isEmpty(w)) return w;
        } catch (Throwable ignored) {}
        return "";
    }

    private void refreshFastSettingsCache() {
        try {
            sFastSkipSelf = getBoolean(RedPacketSettings.KEY_SKIP_SELF, false);
            sFastListMode = getInt(RedPacketSettings.KEY_MODE, 0);
            sFastKwMode = getInt(RedPacketSettings.KEY_KW_MODE, 0);
            sFastKeywords = getString(RedPacketSettings.KEY_KEYWORDS, "");
            sFastMyWxid = "";
        } catch (Throwable ignored) {}
    }

    private boolean installMessageObserver(List<Object> subscriptions) {
        if (observeSubscribed) return true;
        try {
            WeChatMessageObserveApi observeApi = WeChatApis.message().observe();
            if (observeApi == null || !WeChatApis.message().hasObserve()
                    || !observeApi.isAvailable()) {
                logx("消息观察API不可用，使用红包检测 fallback");
                return false;
            }
            Object subscription = observeApi.subscribe(this::handleObservedMessage);
            if (subscription == null) {
                logx("消息观察订阅失败，使用红包检测 fallback");
                return false;
            }
            subscriptions.add(subscription);
            observeSubscribed = true;
            logx("红包检测入口: WeChatApis.message().observe()");
            return true;
        } catch (Throwable e) {
            RedPacketLogger.error("安装消息观察失败", e);
            return false;
        }
    }

    private void handleObservedMessage(WeChatMessageObserveApi.ObservedMessage message) {
        if (message == null || !message.isRedPacket()) return;
        String nativeUrl = message.nativeUrl;
        String xml = !TextUtils.isEmpty(message.xml) ? message.xml : message.content;
        if (TextUtils.isEmpty(nativeUrl)) {
            nativeUrl = RedPacketParser.getXmlParamByTag(xml, "nativeurl");
        }
        if (TextUtils.isEmpty(nativeUrl) && !TextUtils.isEmpty(message.content)) {
            nativeUrl = RedPacketParser.getXmlParamByTag(message.content, "nativeurl");
        }
        if (TextUtils.isEmpty(nativeUrl)) {
            logx("ERROR 识别到红包但链接为空，停止处理：来源=" + message.source
                    + " XML长度=" + (xml == null ? 0 : xml.length())
                    + " 内容长度=" + (message.content == null ? 0 : message.content.length()));
            return;
        }

        String exclusiveRecvUser = RedPacketParser.getXmlParamByTag(xml, "exclusive_recv_username");
        if (TextUtils.isEmpty(exclusiveRecvUser) && !TextUtils.isEmpty(message.content)) {
            exclusiveRecvUser = RedPacketParser.getXmlParamByTag(message.content, "exclusive_recv_username");
        }
        String sender = resolveRedPacketSender(message, xml, nativeUrl);
        processDetectedRedBag(
                "Observe:" + message.source,
                xml,
                sender,
                RedPacketParser.normalizeUsername(message.talker),
                nativeUrl,
                exclusiveRecvUser);
    }

    private String resolveRedPacketSender(WeChatMessageObserveApi.ObservedMessage message,
                                          String xml,
                                          String nativeUrl) {
        String sender = RedPacketParser.getXmlParamByTag(xml, "fromusername");
        if (TextUtils.isEmpty(sender) && !TextUtils.isEmpty(message.content)) {
            sender = RedPacketParser.getXmlParamByTag(message.content, "fromusername");
        }
        if (TextUtils.isEmpty(sender)) {
            sender = RedPacketParser.getNativeUrlParam(nativeUrl, "sendusername");
        }
        if (TextUtils.isEmpty(sender) && !TextUtils.isEmpty(message.content)) {
            int prefixEnd = message.content.indexOf(":\n");
            if (prefixEnd > 0) sender = message.content.substring(0, prefixEnd);
        }
        if (TextUtils.isEmpty(sender)) sender = message.sender;
        return RedPacketParser.normalizeUsername(sender);
    }

    private String getNativeUrlParam(String url, String key) {
        return RedPacketParser.getNativeUrlParam(url, key);
    }

    // ==================== 过滤逻辑 ====================

    private RedPacketEffectiveRule resolveRule(String sender, String talker, String nativeUrl) {
        if (!TextUtils.isEmpty(nativeUrl)) {
            RedPacketEffectiveRule cached = state.ruleMap.get(nativeUrl);
            if (cached != null) return cached;
        }
        RedPacketEffectiveRule rule = ruleResolver.resolve(talker, sender);
        if (!TextUtils.isEmpty(nativeUrl)) state.ruleMap.put(nativeUrl, rule);
        return rule;
    }

    private String getRedBagRejectReason(String sender, String talker,
                                         String content, String exclusiveRecvUser,
                                         String nativeUrl) {
        refreshFastSettingsCache();
        String my = sFastMyWxid;
        if (TextUtils.isEmpty(my)) my = getLoginWxid();
        RedPacketEffectiveRule rule = resolveRule(sender, talker, nativeUrl);
        return filter.getRejectReason(sender, talker, content, exclusiveRecvUser, my, rule);
    }

    private boolean shouldFilterRedBag(String nativeUrl) {
        if (!TextUtils.isEmpty(nativeUrl)) {
            String sender = state.senderMap.get(nativeUrl);
            String content = state.contentMap.get(nativeUrl);
            String talker = state.talkerMap.get(nativeUrl);
            String exclusiveRecvUser = !TextUtils.isEmpty(content)
                    ? RedPacketParser.getXmlParamByTag(content, "exclusive_recv_username")
                    : null;
            String rejectReason = getRedBagRejectReason(sender, talker, content, exclusiveRecvUser, nativeUrl);
            if (rejectReason != null) {
                logx("实时检查忽略: " + rejectReason);
                return true;
            }
            return false;
        }

        int keywordMode = sFastKwMode;
        String keywords = sFastKeywords;
        if (TextUtils.isEmpty(keywords) || state.recentContents.isEmpty()) return false;
        boolean anyMatch = false;
        for (String content : state.recentContents) {
            if (RedPacketParser.containsKeyword(content, keywords)) {
                anyMatch = true;
                break;
            }
        }
        if (keywordMode == 1 && !anyMatch) return true;
        return keywordMode == 2 && anyMatch;
    }

    private void handleUiReceived(String nativeUrl, String amount, boolean selfSent) {
        try {
            if (!TextUtils.isEmpty(nativeUrl) && !stats.incrementSuccess(nativeUrl)) return;
            String talker = !TextUtils.isEmpty(nativeUrl) ? state.talkerMap.get(nativeUrl) : null;
            RedPacketEffectiveRule rule = resolveReceivedRule(nativeUrl);
            notificationCenter.notifyReceived(amount, talker, nativeUrl, rule);
            announcer.announceReceived(amount, talker, nativeUrl, rule);
            autoReply.replyAfterGrabbed(nativeUrl, talker, amount, selfSent);
        } finally {
            state.finishDetectedPacket(nativeUrl);
        }
    }

    private void handleUiFailed(String nativeUrl, String reason) {
        try {
            String talker = !TextUtils.isEmpty(nativeUrl) ? state.talkerMap.get(nativeUrl) : null;
            notificationCenter.notifyFailed(talker, nativeUrl, reason, resolveReceivedRule(nativeUrl));
        } finally {
            state.finishDetectedPacket(nativeUrl);
        }
    }

    // ==================== 自动祝福语 ====================

    private void handleSilentReceived(String amount, String talker,
                                      String nativeUrl, String sendId, Object jsonObj) {
        try {
            RedPacketEffectiveRule rule = resolveReceivedRule(nativeUrl);
            notificationCenter.notifyReceived(amount, talker, nativeUrl, rule);
            announcer.announceReceived(amount, talker, nativeUrl, rule);
            autoReply.replyAfterGrabbed(nativeUrl, talker, amount, false);
            wishSender.sendFromOpenResult(sendId, jsonObj);
        } finally {
            state.finishDetectedPacket(nativeUrl);
        }
    }

    private void handleSilentFailed(String talker, String nativeUrl, String sendId, String reason) {
        notificationCenter.notifyFailed(talker, nativeUrl, reason, resolveReceivedRule(nativeUrl));
    }

    private RedPacketEffectiveRule resolveReceivedRule(String nativeUrl) {
        if (!TextUtils.isEmpty(nativeUrl)) {
            RedPacketEffectiveRule cached = state.ruleMap.get(nativeUrl);
            if (cached != null) return cached;
        }
        return RedPacketRuleResolver.legacyRule(settings);
    }

    private long getDelayValue() {
        int v = getInt(RedPacketSettings.KEY_DELAY_VALUE, 0);
        int u = getInt(RedPacketSettings.KEY_DELAY_UNIT, 0);
        return u == 1 ? v * 1000L : v;
    }

    // ==================== 红包处理 ====================

    private void processDetectedRedBag(String source, String xml, String sender,
                                       String talker, String nu, String exUser) {
        if (!settings.isEnabled()) return;
        refreshFastSettingsCache();
        if (!state.markDetected(nu, sender, xml, talker)) {
            logx(source + " 跳过: nu=" + (TextUtils.isEmpty(nu) ? "empty" : "dup"));
            return;
        }

        RedPacketEffectiveRule rule = resolveRule(sender, talker, nu);
        String rej = getRedBagRejectReason(sender, talker, xml, exUser, nu);
        if (rej != null) {
            logx(source + " 忽略: " + rej);
            state.finishDetectedPacket(nu);
            return;
        }
        long delay = rule.nextDelayMillis();
        int grabMode = rule.getGrabMode();
        logx(source + " 红包: " + nu.substring(0, Math.min(30, nu.length()))
                + " mode=" + grabMode + " delay=" + delay
                + " rule=" + rule.getSourceName()
                + " recvClass=" + (dexFinder.receiveLuckyMoneyClass != null)
                + " openClass=" + (dexFinder.openLuckyMoneyClass != null));

        if (grabMode == 1) {
            // 静默模式
            logx("进入静默模式, sendid=" + getNativeUrlParam(nu, "sendid"));
            if (delay <= 0) {
                silentHandler.tryReceive(xml, talker, nu);
            } else {
                final String fxml = xml, ft = talker, fn = nu;
                scheduleOnMain("redpacket_receive:" + fn, delay,
                        () -> {
                            if (!settings.isEnabled()) {
                                state.finishDetectedPacket(fn);
                                return;
                            }
                            silentHandler.tryReceive(fxml, ft, fn);
                        });
            }
        } else {
            // UI 模式
            logx("进入UI模式");
            // 通知用户有红包来了
            notificationCenter.notifyIncoming(talker, nu, rule);
            final String ft = talker, fn = nu, fs = sender;
            scheduleOnMain("redpacket_ui:" + fn, delay, () -> {
                if (!settings.isEnabled() || shouldFilterRedBag(fn)) {
                    state.finishDetectedPacket(fn);
                    return;
                }
                if (!state.markUiPending(fn)) {
                    state.finishDetectedPacket(fn);
                    return;
                }
                Intent intent = RedPacketUiAutomator.createReceiveIntent(xml, ft, fn, fs);
                if (!startLuckyMoneyActivity(intent)) state.finishDetectedPacket(fn);
            });
        }
    }

    private void scheduleOnMain(String key, long delay, Runnable runnable) {
        if (runnable == null) return;
        pendingTaskKeys.add(key);
        Runnable tracked = () -> {
            pendingTaskKeys.remove(key);
            runnable.run();
        };
        try {
            WeChatTaskApi tasks = WeChatApis.runtime().tasks();
            if (tasks != null && tasks.isAvailable()) {
                tasks.runOnMainDelayed(key, Math.max(0L, delay), tracked);
                return;
            }
        } catch (Throwable e) {
            logx("任务API调度失败，直接执行: " + e.getMessage());
        }
        pendingTaskKeys.remove(key);
        tracked.run();
    }

    public void dispose() {
        SharedPreferences preferences = settings.getHostPreferences();
        if (preferences != null) preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        releaseActivePackets();
    }

    private void releaseActivePackets() {
        try {
            WeChatTaskApi tasks = WeChatApis.runtime().tasks();
            if (tasks != null) {
                for (String key : pendingTaskKeys) tasks.cancel(key);
            }
        } catch (Throwable ignored) {
        }
        pendingTaskKeys.clear();
        silentHandler.cancelPendingPackets();
        autoReply.cancelPendingReplies();
        state.clearActivePackets();
    }

    private boolean startLuckyMoneyActivity(Intent intent) {
        String[] cls = {"nk4.l", "oq4.l", "pn4.l", "qm4.l", "rm4.l",
                "sm4.l", "tm4.l", "um4.l", "vm4.l", "wl4.l"};
        String[] mns = {"A", "B", "C", "D"};
        String[] acts = {".ui.LuckyMoneyNewReceiveUI",
                ".ui.LuckyMoneyNotHookReceiveUI",
                ".ui.LuckyMoneyReceiveUI"};
        for (String cn : cls) {
            for (String mn : mns) {
                for (String a : acts) {
                    try {
                        KavaReflector.invokeStaticMethod(
                                KavaReflector.loadClass(cn, classLoader),
                                mn, hostContext, "luckymoney", a, intent);
                        logx("启动: " + a);
                        return true;
                    } catch (Throwable ignored) {}
                }
            }
        }
        for (String a : acts) {
            try {
                intent.setClassName(hostContext.getPackageName(),
                        "com.tencent.mm.plugin.luckymoney" + a);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                hostContext.startActivity(intent);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ==================== 静默模式 ====================

    private void findNetworkQueue() {
        networkDispatcher.hookNetworkQueue(dexFinder.netQueueClass, dexFinder.netQueueCandidateClasses);
    }

    // ==================== AddMsg 预监听 ====================

    private void hookAddMsgBeforeDb() {
        messageHook.hook();
    }
}
