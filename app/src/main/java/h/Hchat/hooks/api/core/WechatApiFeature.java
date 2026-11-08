package h.Hchat.hooks.api.core;

import h.Hchat.hooks.api.contact.*;
import h.Hchat.hooks.api.conversation.*;
import h.Hchat.hooks.api.media.WeChatMediaApi;
import h.Hchat.hooks.api.message.*;
import h.Hchat.hooks.api.net.WeChatNetworkApi;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.api.payment.WeChatTransferApi;
import h.Hchat.hooks.api.runtime.*;
import h.Hchat.hooks.api.sns.WeChatSnsApi;
import h.Hchat.hooks.api.ui.WeChatActivityStartApi;
import h.Hchat.hooks.api.ui.WeChatChatPageApi;
import h.Hchat.hooks.api.ui.WeChatCurrentActivityApi;
import h.Hchat.hooks.api.ui.WeChatLifecycleApi;
import h.Hchat.hooks.api.ui.WeChatNotifyApi;
import h.Hchat.hooks.api.ui.WeChatUiApi;
import h.Hchat.event.Events;
import h.Hchat.hooks.core.DexInstallScheduler;
import h.Hchat.hooks.core.Feature;
import h.Hchat.hooks.core.FeatureContext;

import h.Hchat.utils.HLog;

/**
 * 公共微信 API 初始化入口。
 */
public class WechatApiFeature implements Feature {
    public static final String ID = "wechat_api";
    private static final String TAG = "[Hchat:WechatApi]";

    @Override
    public String featureId() {
        return ID;
    }

    @Override
    public String name() {
        return "微信公共API";
    }

    @Override
    public boolean isEnabled(FeatureContext context) {
        return true;
    }

    @Override
    public void install(FeatureContext context) throws Throwable {
        WeChatNetworkDispatcher dispatcher = new WeChatNetworkDispatcher(this::log);
        WeChatConfigApi configApi = new WeChatConfigApi(context.hostContext(), this::log);
        WeChatNetworkApi networkApi = new WeChatNetworkApi(dispatcher, this::log);
        WeChatDatabaseApi databaseApi = new WeChatDatabaseApi(context.dexFinder(), this::log);
        WeChatAccountApi accountApi = new WeChatAccountApi(
                context.hostContext(),
                context.hostClassLoader(),
                databaseApi,
                this::log);
        WeChatContactApi contactApi = new WeChatContactApi(databaseApi, context.dexFinder(), dispatcher, this::log);
        WeChatNotifyApi notifyApi = new WeChatNotifyApi(context.hostContext(), this::log);
        WeChatUiApi uiApi = new WeChatUiApi(context.hostContext(), notifyApi, this::log);
        WeChatMessageStoreApi messageStoreApi = new WeChatMessageStoreApi(
                databaseApi, accountApi, context.dexFinder(), this::log);
        WeChatMessageApi messageApi = new WeChatMessageApi(
                context.dexFinder(), dispatcher, accountApi, contactApi, messageStoreApi, context.eventBus(), this::log);
        WeChatLocalMessageApi localMessageApi = new WeChatLocalMessageApi(context.dexFinder(), this::log);
        localMessageApi.installCreateTimeHook();
        WeChatConversationApi conversationApi = new WeChatConversationApi(
                databaseApi, contactApi, notifyApi, context.dexFinder(), this::log);
        WeChatUserApi userApi = new WeChatUserApi(accountApi, contactApi, this::log);
        WeChatChatroomApi chatroomApi = new WeChatChatroomApi(
                databaseApi, contactApi, context.dexFinder(), dispatcher, this::log);
        WeChatStorageApi storageApi = new WeChatStorageApi(databaseApi, this::log);
        WeChatMessageParseApi messageParseApi = new WeChatMessageParseApi();
        WeChatMessageEventApi messageEventApi = new WeChatMessageEventApi(
                context.dexFinder(), accountApi, messageParseApi, context.eventBus(), this::log);
        WeChatPermissionApi permissionApi = new WeChatPermissionApi();
        WeChatDatabaseListenerApi databaseListenerApi = new WeChatDatabaseListenerApi(
                context.dexFinder(), context.hostClassLoader(), this::log);
        WeChatCurrentActivityApi currentActivityApi = new WeChatCurrentActivityApi(this::log);
        WeChatMediaApi mediaApi = new WeChatMediaApi(
                context.hostContext(), context.dexFinder(), context.hostClassLoader(),
                context.dexKitBridge(), currentActivityApi, this::log);
        WeChatActivityStartApi activityStartApi = new WeChatActivityStartApi(this::log);
        WeChatMessageChangeApi messageChangeApi = new WeChatMessageChangeApi(
                databaseListenerApi, messageStoreApi, accountApi, this::log);
        WeChatConversationChangeApi conversationChangeApi = new WeChatConversationChangeApi(
                databaseListenerApi, conversationApi, this::log);
        WeChatContactChangeApi contactChangeApi = new WeChatContactChangeApi(
                databaseListenerApi, contactApi, this::log);
        WeChatChatroomChangeApi chatroomChangeApi = new WeChatChatroomChangeApi(
                databaseListenerApi, chatroomApi, this::log);
        WeChatLifecycleApi lifecycleApi = new WeChatLifecycleApi(this::log);
        WeChatDiagnosticsApi diagnosticsApi = new WeChatDiagnosticsApi(
                context.dexFinder(), this::log);
        WeChatTaskApi taskApi = new WeChatTaskApi(context.hostContext(), this::log);
        WeChatVersionApi versionApi = new WeChatVersionApi(
                context.hostContext(), context.hostClassLoader(), this::log);
        WeChatMessageObserveApi messageObserveApi = new WeChatMessageObserveApi(
                messageEventApi, messageChangeApi, messageParseApi, accountApi, this::log);
        WeChatChatPageApi chatPageApi = new WeChatChatPageApi(
                context.dexFinder(), currentActivityApi, lifecycleApi, activityStartApi, contactApi, this::log);
        WeChatTransferApi transferApi = new WeChatTransferApi(
                context.dexFinder(), dispatcher, this::log);
        WeChatVerifyUserApi verifyUserApi = new WeChatVerifyUserApi(
                context.dexFinder(), dispatcher, this::log);
        WeChatSnsApi snsApi = new WeChatSnsApi(
                context.hostContext(), context.dexFinder(), context.hostClassLoader(),
                context.dexKitBridge(), networkApi, accountApi, contactApi, context, this::log);
        networkApi.installNetworkHook(context.dexFinder());
        messageEventApi.installAddMsgHook();
        installApiHook("databaseChanges", databaseListenerApi::install);
        installApiHook("messageChanges", messageChangeApi::install);
        installApiHook("conversationChanges", conversationChangeApi::install);
        installApiHook("contactChanges", contactChangeApi::install);
        installApiHook("chatroomChanges", chatroomChangeApi::install);
        installApiHook("currentActivity", currentActivityApi::install);
        installApiHook("activityStart", activityStartApi::install);
        installApiHook("lifecycle", lifecycleApi::install);
        installApiHook("messageObserve", messageObserveApi::install);
        installApiHook("chatPage", chatPageApi::install);
        WeChatApis.init(messageApi, databaseApi, accountApi, contactApi,
                messageStoreApi, conversationApi, notifyApi, configApi, networkApi, userApi,
                chatroomApi, storageApi, messageParseApi, messageEventApi,
                localMessageApi, uiApi, mediaApi, permissionApi, databaseListenerApi,
                currentActivityApi, activityStartApi, messageChangeApi,
                conversationChangeApi, contactChangeApi, chatroomChangeApi,
                lifecycleApi, diagnosticsApi, taskApi, messageObserveApi, chatPageApi, versionApi,
                transferApi, verifyUserApi, snsApi);
        DexInstallScheduler.scheduleTask("wechat_api:warmup", "微信公共API预热",
                DexInstallScheduler.Stage.BRIDGE, 1000, () -> {
            try {
                context.dexFinder().resolveAll();
                networkApi.installNetworkHook(context.dexFinder());
                installApiHook("snsInteractionWarmup", snsApi::warmupInteraction);
                installApiHook("snsCachedPostsWarmup", snsApi::warmupCachedPosts);
                installApiHook("snsPostObserverWarmup", snsApi::installPostObserver);
                messageEventApi.installAddMsgHook();
                installApiHook("databaseChangesWarmup", databaseListenerApi::install);
                installApiHook("messageChangesWarmup", messageChangeApi::install);
                installApiHook("conversationChangesWarmup", conversationChangeApi::install);
                installApiHook("contactChangesWarmup", contactChangeApi::install);
                installApiHook("chatroomChangesWarmup", chatroomChangeApi::install);
                installApiHook("messageObserveWarmup", messageObserveApi::install);
                installApiHook("chatPageWarmup", chatPageApi::install);
            } catch (Throwable e) {
                log("DexKit 预热失败: " + e.getMessage());
            } finally {
                try {
                    DexInstallScheduler.markDexWarmupReady();
                    context.eventBus().post(new Events.DexReady());
                } catch (Throwable e) {
                    log("DexReady 发布失败: " + e.getMessage());
                }
            }
            return true;
        });
        DexInstallScheduler.scheduleTask(
                "wechat_api:database_changes",
                "数据库变更监听补装",
                DexInstallScheduler.Stage.WARMUP,
                -1000,
                () -> {
                    context.dexFinder().resolveDatabaseApi();
                    databaseListenerApi.install();
                    messageChangeApi.install();
                    conversationChangeApi.install();
                    contactChangeApi.install();
                    chatroomChangeApi.install();
                    return databaseListenerApi.isOperational() && messageChangeApi.isInstalled();
                });
        DexInstallScheduler.scheduleTask(
                "wechat_api:private_conversation_mute",
                "私聊免打扰API补定位",
                DexInstallScheduler.Stage.WARMUP,
                0,
                () -> {
                    context.dexFinder().resolveConversationMuteApi();
                    return context.dexFinder().isPrivateConversationMuteApiReady();
                });
    }

    @Override
    public void onDestroy(FeatureContext context) {
        WeChatTaskApi taskApi = WeChatApis.tasks();
        if (taskApi != null) taskApi.shutdown();
        WeChatApis.clear();
    }

    private void log(String message) {
        if (isImportantLog(message)) {
            HLog.e(TAG + " " + message);
        }
    }

    private void installApiHook(String name, Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable e) {
            HLog.e(TAG + " " + name + " API安装失败: " + e.getMessage(), e);
        }
    }

    private boolean isImportantLog(String message) {
        if (message == null) return false;
        return message.contains("失败")
                || message.contains("异常")
                || message.contains("未找到")
                || message.contains("为空")
                || message.contains("不可用")
                || message.contains("未就绪")
                || message.contains("未解析")
                || message.contains("缺失")
                || message.contains("无合适")
                || message.contains("ERROR")
                || message.contains("error");
    }
}
