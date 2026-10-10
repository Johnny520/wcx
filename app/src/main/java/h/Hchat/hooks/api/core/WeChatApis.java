package h.Hchat.hooks.api.core;

import h.Hchat.hooks.api.contact.*;
import h.Hchat.hooks.api.conversation.*;
import h.Hchat.hooks.api.media.*;
import h.Hchat.hooks.api.message.*;
import h.Hchat.hooks.api.model.WeChatMessageTypes;
import h.Hchat.hooks.api.net.WeChatNetworkApi;
import h.Hchat.hooks.api.payment.WeChatTransferApi;
import h.Hchat.hooks.api.runtime.*;
import h.Hchat.hooks.api.sns.WeChatSnsApi;
import h.Hchat.hooks.api.ui.WeChatActivityStartApi;
import h.Hchat.hooks.api.ui.WeChatChatPageApi;
import h.Hchat.hooks.api.ui.WeChatCurrentActivityApi;
import h.Hchat.hooks.api.ui.WeChatLifecycleApi;
import h.Hchat.hooks.api.ui.WeChatNotifyApi;
import h.Hchat.hooks.api.ui.WeChatUiApi;

/**
 * 模块内微信能力入口。
 *
 * 其他功能需要微信能力时，直接通过这里拿统一 API。
 */
public final class WeChatApis {
    private static volatile WeChatMessageApi messageApi;
    private static volatile WeChatDatabaseApi databaseApi;
    private static volatile WeChatAccountApi accountApi;
    private static volatile WeChatContactApi contactApi;
    private static volatile WeChatMessageStoreApi messageStoreApi;
    private static volatile WeChatConversationApi conversationApi;
    private static volatile WeChatNotifyApi notifyApi;
    private static volatile WeChatConfigApi configApi;
    private static volatile WeChatNetworkApi networkApi;
    private static volatile WeChatUserApi userApi;
    private static volatile WeChatChatroomApi chatroomApi;
    private static volatile WeChatStorageApi storageApi;
    private static volatile WeChatMessageParseApi messageParseApi;
    private static volatile WeChatMessageEventApi messageEventApi;
    private static volatile WeChatLocalMessageApi localMessageApi;
    private static volatile WeChatUiApi uiApi;
    private static volatile WeChatMediaApi mediaApi;
    private static volatile WeChatPermissionApi permissionApi;
    private static volatile WeChatDatabaseListenerApi databaseListenerApi;
    private static volatile WeChatCurrentActivityApi currentActivityApi;
    private static volatile WeChatActivityStartApi activityStartApi;
    private static volatile WeChatMessageChangeApi messageChangeApi;
    private static volatile WeChatConversationChangeApi conversationChangeApi;
    private static volatile WeChatContactChangeApi contactChangeApi;
    private static volatile WeChatChatroomChangeApi chatroomChangeApi;
    private static volatile WeChatLifecycleApi lifecycleApi;
    private static volatile WeChatDiagnosticsApi diagnosticsApi;
    private static volatile WeChatTaskApi taskApi;
    private static volatile WeChatMessageObserveApi messageObserveApi;
    private static volatile WeChatChatPageApi chatPageApi;
    private static volatile WeChatVersionApi versionApi;
    private static volatile WeChatTransferApi transferApi;
    private static volatile WeChatVerifyUserApi verifyUserApi;
    private static volatile WeChatSnsApi snsApi;
    private static final MessageGroup MESSAGE_GROUP = new MessageGroup();
    private static final ContactGroup CONTACT_GROUP = new ContactGroup();
    private static final RuntimeGroup RUNTIME_GROUP = new RuntimeGroup();
    private static final InteractionGroup INTERACTION_GROUP = new InteractionGroup();
    private static final PaymentGroup PAYMENT_GROUP = new PaymentGroup();

    private WeChatApis() {}

    public static void init(WeChatMessageApi api) {
        messageApi = api;
    }

    public static void init(WeChatMessageApi message,
                            WeChatDatabaseApi database,
                            WeChatAccountApi account,
                            WeChatContactApi contact) {
        messageApi = message;
        databaseApi = database;
        accountApi = account;
        contactApi = contact;
    }

    public static void init(WeChatMessageApi message,
                            WeChatDatabaseApi database,
                            WeChatAccountApi account,
                            WeChatContactApi contact,
                            WeChatMessageStoreApi messageStore,
                            WeChatConversationApi conversation,
                            WeChatNotifyApi notify,
                            WeChatConfigApi config,
                            WeChatNetworkApi network,
                            WeChatUserApi user) {
        messageApi = message;
        databaseApi = database;
        accountApi = account;
        contactApi = contact;
        messageStoreApi = messageStore;
        conversationApi = conversation;
        notifyApi = notify;
        configApi = config;
        networkApi = network;
        userApi = user;
    }

    public static void init(WeChatMessageApi message,
                            WeChatDatabaseApi database,
                            WeChatAccountApi account,
                            WeChatContactApi contact,
                            WeChatMessageStoreApi messageStore,
                            WeChatConversationApi conversation,
                            WeChatNotifyApi notify,
                            WeChatConfigApi config,
                            WeChatNetworkApi network,
                            WeChatUserApi user,
                            WeChatChatroomApi chatroom,
                            WeChatStorageApi storage,
                            WeChatMessageParseApi messageParse,
                            WeChatMessageEventApi messageEvent,
                            WeChatLocalMessageApi localMessage,
                            WeChatUiApi ui,
                            WeChatMediaApi media,
                            WeChatPermissionApi permission,
                            WeChatDatabaseListenerApi databaseListener,
                            WeChatCurrentActivityApi currentActivity,
                            WeChatActivityStartApi activityStart,
                            WeChatMessageChangeApi messageChange,
                            WeChatConversationChangeApi conversationChange,
                            WeChatContactChangeApi contactChange,
                            WeChatChatroomChangeApi chatroomChange,
                            WeChatLifecycleApi lifecycle,
                            WeChatDiagnosticsApi diagnostics,
                            WeChatTaskApi task,
                            WeChatMessageObserveApi messageObserve,
                            WeChatChatPageApi chatPage,
                            WeChatVersionApi version,
                            WeChatTransferApi transfer,
                            WeChatVerifyUserApi verifyUser,
                            WeChatSnsApi sns) {
        init(message, database, account, contact, messageStore, conversation,
                notify, config, network, user);
        chatroomApi = chatroom;
        storageApi = storage;
        messageParseApi = messageParse;
        messageEventApi = messageEvent;
        localMessageApi = localMessage;
        uiApi = ui;
        mediaApi = media;
        permissionApi = permission;
        databaseListenerApi = databaseListener;
        currentActivityApi = currentActivity;
        activityStartApi = activityStart;
        messageChangeApi = messageChange;
        conversationChangeApi = conversationChange;
        contactChangeApi = contactChange;
        chatroomChangeApi = chatroomChange;
        lifecycleApi = lifecycle;
        diagnosticsApi = diagnostics;
        taskApi = task;
        messageObserveApi = messageObserve;
        chatPageApi = chatPage;
        versionApi = version;
        transferApi = transfer;
        verifyUserApi = verifyUser;
        snsApi = sns;
    }

    public static WeChatMessageApi messages() {
        return messageApi;
    }

    public static WeChatDatabaseApi database() {
        return databaseApi;
    }

    public static WeChatAccountApi account() {
        return accountApi;
    }

    public static WeChatContactApi contacts() {
        return contactApi;
    }

    public static WeChatMessageStoreApi messageStore() {
        return messageStoreApi;
    }

    public static WeChatConversationApi conversations() {
        return conversationApi;
    }

    public static WeChatNotifyApi notifyApi() {
        return notifyApi;
    }

    public static WeChatConfigApi config() {
        return configApi;
    }

    public static WeChatNetworkApi network() {
        return networkApi;
    }

    public static WeChatUserApi users() {
        return userApi;
    }

    public static WeChatChatroomApi chatrooms() {
        return chatroomApi;
    }

    public static WeChatStorageApi storage() {
        return storageApi;
    }

    public static WeChatMessageParseApi messageParser() {
        return messageParseApi;
    }

    public static WeChatMessageEventApi messageEvents() {
        return messageEventApi;
    }

    public static WeChatLocalMessageApi localMessages() {
        return localMessageApi;
    }

    public static WeChatUiApi ui() {
        return uiApi;
    }

    public static WeChatMediaApi media() {
        return mediaApi;
    }

    public static WeChatPermissionApi permissions() {
        return permissionApi;
    }

    public static WeChatDatabaseListenerApi databaseChanges() {
        return databaseListenerApi;
    }

    public static WeChatCurrentActivityApi currentActivity() {
        return currentActivityApi;
    }

    public static WeChatActivityStartApi activityStart() {
        return activityStartApi;
    }

    public static WeChatMessageChangeApi messageChanges() {
        return messageChangeApi;
    }

    public static WeChatConversationChangeApi conversationChanges() {
        return conversationChangeApi;
    }

    public static WeChatContactChangeApi contactChanges() {
        return contactChangeApi;
    }

    public static WeChatChatroomChangeApi chatroomChanges() {
        return chatroomChangeApi;
    }

    public static WeChatLifecycleApi lifecycle() {
        return lifecycleApi;
    }

    public static WeChatDiagnosticsApi diagnostics() {
        return diagnosticsApi;
    }

    public static WeChatTaskApi tasks() {
        return taskApi;
    }

    public static WeChatMessageObserveApi messageObserve() {
        return messageObserveApi;
    }

    public static WeChatChatPageApi chatPage() {
        return chatPageApi;
    }

    public static WeChatVersionApi version() {
        return versionApi;
    }

    public static WeChatTransferApi transfers() {
        return transferApi;
    }

    public static WeChatVerifyUserApi verifyUsers() {
        return verifyUserApi;
    }

    public static WeChatSnsApi snsApi() {
        return snsApi;
    }

    /**
     * 消息域 API：发送、记录、解析、事件。
     */
    public static MessageGroup message() {
        return MESSAGE_GROUP;
    }

    /**
     * 联系人域 API：账号、联系人、群聊、用户判断。
     */
    public static ContactGroup contact() {
        return CONTACT_GROUP;
    }

    /**
     * 运行时域 API：配置、数据库、存储、网络、能力检测。
     */
    public static RuntimeGroup runtime() {
        return RUNTIME_GROUP;
    }

    /**
     * 交互域 API：UI、通知、媒体。
     */
    public static InteractionGroup interaction() {
        return INTERACTION_GROUP;
    }

    /**
     * 支付域 API：转账等微信支付相关能力。
     */
    public static PaymentGroup payment() {
        return PAYMENT_GROUP;
    }

    public static boolean hasMessages() {
        return messageApi != null && messageApi.isAvailable();
    }

    public static boolean hasDatabase() {
        return databaseApi != null && databaseApi.isAvailable();
    }

    public static boolean hasAccount() {
        return accountApi != null && accountApi.isAvailable();
    }

    public static boolean hasContacts() {
        return contactApi != null && contactApi.isAvailable();
    }

    public static boolean hasMessageStore() {
        return messageStoreApi != null && messageStoreApi.isAvailable();
    }

    public static boolean hasConversations() {
        return conversationApi != null && conversationApi.isAvailable();
    }

    public static boolean hasNotifyApi() {
        return notifyApi != null && notifyApi.isAvailable();
    }

    public static boolean hasConfig() {
        return configApi != null && configApi.isAvailable();
    }

    public static boolean hasNetwork() {
        return networkApi != null && networkApi.isAvailable();
    }

    public static boolean hasUsers() {
        return userApi != null && userApi.isAvailable();
    }

    public static boolean hasChatrooms() {
        return chatroomApi != null && chatroomApi.isAvailable();
    }

    public static boolean hasStorage() {
        return storageApi != null && storageApi.isAvailable();
    }

    public static boolean hasMessageParser() {
        return messageParseApi != null && messageParseApi.isAvailable();
    }

    public static boolean hasMessageEvents() {
        return messageEventApi != null && messageEventApi.isAvailable();
    }

    public static boolean hasLocalMessages() {
        return localMessageApi != null && localMessageApi.isAvailable();
    }

    public static boolean hasUi() {
        return uiApi != null && uiApi.isAvailable();
    }

    public static boolean hasMedia() {
        return mediaApi != null && mediaApi.isAvailable();
    }

    public static boolean hasPermissions() {
        return permissionApi != null && permissionApi.isAvailable();
    }

    public static boolean hasDatabaseChanges() {
        return databaseListenerApi != null && databaseListenerApi.isAvailable();
    }

    public static boolean hasCurrentActivity() {
        return currentActivityApi != null && currentActivityApi.isAvailable();
    }

    public static boolean hasActivityStart() {
        return activityStartApi != null && activityStartApi.isAvailable();
    }

    public static boolean hasMessageChanges() {
        return messageChangeApi != null && messageChangeApi.isAvailable();
    }

    public static boolean hasConversationChanges() {
        return conversationChangeApi != null && conversationChangeApi.isAvailable();
    }

    public static boolean hasContactChanges() {
        return contactChangeApi != null && contactChangeApi.isAvailable();
    }

    public static boolean hasChatroomChanges() {
        return chatroomChangeApi != null && chatroomChangeApi.isAvailable();
    }

    public static boolean hasLifecycle() {
        return lifecycleApi != null && lifecycleApi.isAvailable();
    }

    public static boolean hasDiagnostics() {
        return diagnosticsApi != null && diagnosticsApi.isAvailable();
    }

    public static boolean hasTasks() {
        return taskApi != null && taskApi.isAvailable();
    }

    public static boolean hasMessageObserve() {
        return messageObserveApi != null && messageObserveApi.isAvailable();
    }

    public static boolean hasChatPage() {
        return chatPageApi != null && chatPageApi.isAvailable();
    }

    public static boolean hasVersion() {
        return versionApi != null && versionApi.isAvailable();
    }

    public static boolean hasTransfers() {
        return transferApi != null && transferApi.isAvailable();
    }

    public static boolean hasVerifyUsers() {
        return verifyUserApi != null && verifyUserApi.isAvailable();
    }

    public static boolean hasSnsApi() {
        return snsApi != null && snsApi.isAvailable();
    }

    public static void clear() {
        messageApi = null;
        databaseApi = null;
        accountApi = null;
        contactApi = null;
        messageStoreApi = null;
        conversationApi = null;
        notifyApi = null;
        configApi = null;
        networkApi = null;
        userApi = null;
        chatroomApi = null;
        storageApi = null;
        messageParseApi = null;
        messageEventApi = null;
        localMessageApi = null;
        uiApi = null;
        mediaApi = null;
        permissionApi = null;
        databaseListenerApi = null;
        currentActivityApi = null;
        activityStartApi = null;
        messageChangeApi = null;
        conversationChangeApi = null;
        contactChangeApi = null;
        chatroomChangeApi = null;
        lifecycleApi = null;
        diagnosticsApi = null;
        taskApi = null;
        messageObserveApi = null;
        chatPageApi = null;
        versionApi = null;
        transferApi = null;
        verifyUserApi = null;
        snsApi = null;
    }

    public static final class MessageGroup {
        private MessageGroup() {}

        public WeChatMessageApi sender() {
            return messageApi;
        }

        public WeChatMessageApi text() {
            return messageApi;
        }

        public WeChatMessageStoreApi store() {
            return messageStoreApi;
        }

        public WeChatConversationApi conversations() {
            return conversationApi;
        }

        public WeChatMessageParseApi parser() {
            return messageParseApi;
        }

        public WeChatMessageEventApi events() {
            return messageEventApi;
        }

        public WeChatLocalMessageApi local() {
            return localMessageApi;
        }

        public WeChatMessageTypes types() {
            return WeChatMessageTypes.INSTANCE;
        }

        public WeChatMessageChangeApi changes() {
            return messageChangeApi;
        }

        public WeChatMessageObserveApi observe() {
            return messageObserveApi;
        }

        public boolean hasSender() {
            return hasMessages();
        }

        public boolean hasStore() {
            return hasMessageStore();
        }

        public boolean hasConversations() {
            return WeChatApis.hasConversations();
        }

        public boolean hasParser() {
            return hasMessageParser();
        }

        public boolean hasEvents() {
            return hasMessageEvents();
        }

        public boolean hasLocal() {
            return WeChatApis.hasLocalMessages();
        }

        public boolean hasTypes() {
            return true;
        }

        public boolean hasChanges() {
            return WeChatApis.hasMessageChanges();
        }

        public boolean hasObserve() {
            return WeChatApis.hasMessageObserve();
        }
    }

    public static final class ContactGroup {
        private ContactGroup() {}

        public WeChatAccountApi account() {
            return accountApi;
        }

        public WeChatContactApi contacts() {
            return contactApi;
        }

        public WeChatChatroomApi chatrooms() {
            return chatroomApi;
        }

        public WeChatUserApi users() {
            return userApi;
        }

        public WeChatContactChangeApi changes() {
            return contactChangeApi;
        }

        public WeChatChatroomChangeApi chatroomChanges() {
            return chatroomChangeApi;
        }

        public WeChatVerifyUserApi verifyUser() {
            return verifyUserApi;
        }

        public boolean hasAccount() {
            return WeChatApis.hasAccount();
        }

        public boolean hasContacts() {
            return WeChatApis.hasContacts();
        }

        public boolean hasChatrooms() {
            return WeChatApis.hasChatrooms();
        }

        public boolean hasUsers() {
            return WeChatApis.hasUsers();
        }

        public boolean hasChanges() {
            return WeChatApis.hasContactChanges();
        }

        public boolean hasChatroomChanges() {
            return WeChatApis.hasChatroomChanges();
        }

        public boolean hasVerifyUser() {
            return WeChatApis.hasVerifyUsers();
        }
    }

    public static final class RuntimeGroup {
        private RuntimeGroup() {}

        public WeChatConfigApi config() {
            return configApi;
        }

        public WeChatDatabaseApi database() {
            return databaseApi;
        }

        public WeChatStorageApi storage() {
            return storageApi;
        }

        public WeChatNetworkApi network() {
            return networkApi;
        }

        public WeChatPermissionApi capabilities() {
            return permissionApi;
        }

        public WeChatPermissionApi permissions() {
            return permissionApi;
        }

        public WeChatDatabaseListenerApi databaseChanges() {
            return databaseListenerApi;
        }

        public WeChatCurrentActivityApi currentActivity() {
            return currentActivityApi;
        }

        public WeChatConversationChangeApi conversationChanges() {
            return conversationChangeApi;
        }

        public WeChatDiagnosticsApi diagnostics() {
            return diagnosticsApi;
        }

        public WeChatTaskApi tasks() {
            return taskApi;
        }

        public WeChatVersionApi version() {
            return versionApi;
        }

        public boolean hasConfig() {
            return WeChatApis.hasConfig();
        }

        public boolean hasDatabase() {
            return WeChatApis.hasDatabase();
        }

        public boolean hasStorage() {
            return WeChatApis.hasStorage();
        }

        public boolean hasNetwork() {
            return WeChatApis.hasNetwork();
        }

        public boolean hasCapabilities() {
            return WeChatApis.hasPermissions();
        }

        public boolean hasDatabaseChanges() {
            return WeChatApis.hasDatabaseChanges();
        }

        public boolean hasCurrentActivity() {
            return WeChatApis.hasCurrentActivity();
        }

        public boolean hasConversationChanges() {
            return WeChatApis.hasConversationChanges();
        }

        public boolean hasDiagnostics() {
            return WeChatApis.hasDiagnostics();
        }

        public boolean hasTasks() {
            return WeChatApis.hasTasks();
        }

        public boolean hasVersion() {
            return WeChatApis.hasVersion();
        }
    }

    public static final class InteractionGroup {
        private InteractionGroup() {}

        public WeChatUiApi ui() {
            return uiApi;
        }

        public WeChatNotifyApi notifyApi() {
            return notifyApi;
        }

        public WeChatNotifyApi notifier() {
            return notifyApi;
        }

        public WeChatMediaApi media() {
            return mediaApi;
        }

        public WeChatSnsApi sns() {
            return snsApi;
        }

        public WeChatCurrentActivityApi currentActivity() {
            return currentActivityApi;
        }

        public WeChatActivityStartApi activityStart() {
            return activityStartApi;
        }

        public WeChatLifecycleApi lifecycle() {
            return lifecycleApi;
        }

        public WeChatChatPageApi chatPage() {
            return chatPageApi;
        }

        public boolean hasUi() {
            return WeChatApis.hasUi();
        }

        public boolean hasNotify() {
            return WeChatApis.hasNotifyApi();
        }

        public boolean hasMedia() {
            return WeChatApis.hasMedia();
        }

        public boolean hasCurrentActivity() {
            return WeChatApis.hasCurrentActivity();
        }

        public boolean hasActivityStart() {
            return WeChatApis.hasActivityStart();
        }

        public boolean hasLifecycle() {
            return WeChatApis.hasLifecycle();
        }

        public boolean hasChatPage() {
            return WeChatApis.hasChatPage();
        }

        public boolean hasSns() {
            return WeChatApis.hasSnsApi();
        }
    }

    public static final class PaymentGroup {
        private PaymentGroup() {}

        public WeChatTransferApi transfers() {
            return transferApi;
        }

        public boolean hasTransfers() {
            return WeChatApis.hasTransfers();
        }
    }
}
