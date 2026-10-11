package h.Hchat.hooks.api.message;

import android.text.TextUtils;

import h.Hchat.event.EventBus;
import h.Hchat.event.Events;
import h.Hchat.hooks.api.contact.WeChatAccountApi;
import h.Hchat.hooks.api.model.WeChatMessage;
import h.Hchat.hooks.api.model.WeChatMessageTypes;
import h.Hchat.hooks.api.model.WeChatFileMsg;
import h.Hchat.hooks.api.model.WeChatImageMsg;
import h.Hchat.hooks.api.model.WeChatPatMsg;
import h.Hchat.hooks.api.model.WeChatQuoteMsg;
import h.Hchat.hooks.api.model.WeChatTransferMsg;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务级消息观察 API。
 */
public final class WeChatMessageObserveApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onObservedMessage(ObservedMessage message);
    }

    public static final class Kind {
        public static final String TEXT = "text";
        public static final String IMAGE = "image";
        public static final String VOICE = "voice";
        public static final String VIDEO = "video";
        public static final String EMOJI = "emoji";
        public static final String APP = "app";
        public static final String LOCATION = "location";
        public static final String RED_PACKET = "red_packet";
        public static final String TRANSFER = "transfer";
        public static final String QUOTE = "quote";
        public static final String FILE = "file";
        public static final String PAT = "pat";
        public static final String LINK = "link";
        public static final String MUSIC = "music";
        public static final String NOTE = "note";
        public static final String SHARE_CARD = "share_card";
        public static final String VOIP = "voip";
        public static final String VIDEO_NUMBER_VIDEO = "video_number_video";
        public static final String SYSTEM = "system";
        public static final String RECALLED = "recalled";
        public static final String UNKNOWN = "unknown";

        private Kind() {}
    }

    public static final class ObservedMessage {
        public final String source;
        public final String kind;
        public final String talker;
        public final String sender;
        public final String content;
        public final String xml;
        public final String nativeUrl;
        public final boolean group;
        public final boolean outgoing;
        public final WeChatMessage storedMessage;
        public final WeChatMessage message;
        public final String patFromUser;
        public final String patPattedUser;
        public final String patTemplate;
        public final long patCreateTime;
        public final long patSvrId;
        public final WeChatTransferMsg transferMsg;

        private ObservedMessage(String source,
                                String kind,
                                String talker,
                                String sender,
                                String content,
                                String xml,
                                String nativeUrl,
                                boolean group,
                                boolean outgoing,
                                WeChatMessage storedMessage) {
            this(source, kind, talker, sender, content, xml, nativeUrl, group, outgoing, storedMessage, null);
        }

        private ObservedMessage(String source,
                                String kind,
                                String talker,
                                String sender,
                                String content,
                                String xml,
                                String nativeUrl,
                                boolean group,
                                boolean outgoing,
                                WeChatMessage storedMessage,
                                WeChatMessage message) {
            this(source, kind, talker, sender, content, xml, nativeUrl, group, outgoing,
                    storedMessage, message, "", "", "", 0L, 0L);
        }

        private ObservedMessage(String source,
                                String kind,
                                String talker,
                                String sender,
                                String content,
                                String xml,
                                String nativeUrl,
                                boolean group,
                                boolean outgoing,
                                WeChatMessage storedMessage,
                                WeChatMessage message,
                                String patFromUser,
                                String patPattedUser,
                                String patTemplate,
                                long patCreateTime,
                                long patSvrId) {
            this.source = safe(source);
            this.kind = safe(kind);
            this.talker = safe(talker);
            this.sender = safe(sender);
            this.content = safe(content);
            this.xml = safe(xml);
            this.nativeUrl = safe(nativeUrl);
            this.group = group;
            this.outgoing = outgoing;
            this.storedMessage = storedMessage;
            WeChatMessage resolvedMessage = message;
            if (resolvedMessage == null) resolvedMessage = storedMessage;
            if (resolvedMessage == null) {
                resolvedMessage = WeChatMessage.fromTransient(
                        talker,
                        sender,
                        !TextUtils.isEmpty(content) ? content : xml,
                        System.currentTimeMillis(),
                        outgoing);
            }
            this.message = resolvedMessage;
            this.patFromUser = safe(patFromUser);
            this.patPattedUser = safe(patPattedUser);
            this.patTemplate = safe(patTemplate);
            this.patCreateTime = patCreateTime;
            this.patSvrId = patSvrId;
            this.transferMsg = resolvedMessage != null ? resolvedMessage.getTransferMsg() : null;
        }

        public static ObservedMessage transientMessage(String source,
                                                       String kind,
                                                       String talker,
                                                       String sender,
                                                       String content,
                                                       String xml,
                                                       String nativeUrl,
                                                       boolean group,
                                                       boolean outgoing,
                                                       WeChatMessage message) {
            return new ObservedMessage(source, kind, talker, sender, content, xml, nativeUrl,
                    group, outgoing, null, message);
        }

        public boolean isText() {
            return Kind.TEXT.equals(kind);
        }

        public long getMsgId() {
            return message != null ? message.getMsgId() : 0L;
        }

        public int getType() {
            return message != null ? message.getType() : 0;
        }

        public long getCreateTime() {
            return message != null ? message.getCreateTime() : 0L;
        }

        public String getTalker() {
            return message != null ? message.getTalker() : talker;
        }

        public String getSendTalker() {
            return message != null ? message.getSendTalker() : sender;
        }

        public String getContent() {
            return message != null ? message.getContent() : content;
        }

        public boolean isPrivateChat() {
            return message != null && message.isPrivateChat();
        }

        public boolean isOpenIM() {
            return message != null && message.isOpenIM();
        }

        public boolean isGroupChat() {
            return message != null && message.isGroupChat();
        }

        public boolean isChatroom() {
            return message != null && message.isChatroom();
        }

        public boolean isImChatroom() {
            return message != null && message.isImChatroom();
        }

        public boolean isOfficialAccount() {
            return message != null && message.isOfficialAccount();
        }

        public boolean isSend() {
            return outgoing || (message != null && message.isSend());
        }

        public String getMsgSource() {
            return message != null ? message.getMsgSource() : "";
        }

        public java.util.List<String> getAtUserList() {
            return message != null ? message.getAtUserList() : java.util.Collections.emptyList();
        }

        public boolean isAnnounceAll() {
            return message != null && message.isAnnounceAll();
        }

        public boolean isNotifyAll() {
            return message != null && message.isNotifyAll();
        }

        public boolean isAtMe() {
            return message != null && message.isAtMe();
        }

        public boolean isImage() {
            return Kind.IMAGE.equals(kind) || (message != null && message.isImage());
        }

        public boolean isVoice() {
            return Kind.VOICE.equals(kind) || (message != null && message.isVoice());
        }

        public boolean isVideo() {
            return Kind.VIDEO.equals(kind) || (message != null && message.isVideo());
        }

        public boolean isEmoji() {
            return Kind.EMOJI.equals(kind) || (message != null && message.isEmoji());
        }

        public boolean isLocation() {
            return Kind.LOCATION.equals(kind) || (message != null && message.isLocation());
        }

        public boolean isApp() {
            return Kind.APP.equals(kind) || (message != null && message.isApp());
        }

        public boolean isSystem() {
            return Kind.SYSTEM.equals(kind) || (message != null && message.isSystem());
        }

        public boolean isRedPacket() {
            return Kind.RED_PACKET.equals(kind);
        }

        public boolean isRedBag() {
            return isRedPacket();
        }

        public boolean isTransfer() {
            return Kind.TRANSFER.equals(kind) || (message != null && message.isTransfer());
        }

        public boolean isQuote() {
            return Kind.QUOTE.equals(kind) || (message != null && message.isQuote());
        }

        public boolean isFile() {
            return Kind.FILE.equals(kind) || (message != null && message.isFile());
        }

        public boolean isLink() {
            return Kind.LINK.equals(kind) || (message != null && message.isLink());
        }

        public boolean isMusic() {
            return Kind.MUSIC.equals(kind) || (message != null && message.isMusic());
        }

        public boolean isNote() {
            return Kind.NOTE.equals(kind) || (message != null && message.isNote());
        }

        public boolean isShareCard() {
            return Kind.SHARE_CARD.equals(kind) || (message != null && message.isShareCard());
        }

        public boolean isVoip() {
            return Kind.VOIP.equals(kind) || (message != null && message.isVoip());
        }

        public boolean isVoipVoice() {
            return message != null && message.isVoipVoice();
        }

        public boolean isVoipVideo() {
            return message != null && message.isVoipVideo();
        }

        public boolean isVideoNumberVideo() {
            return Kind.VIDEO_NUMBER_VIDEO.equals(kind)
                    || (message != null && message.isVideoNumberVideo());
        }

        public boolean isPat() {
            return Kind.PAT.equals(kind) || (message != null && message.isPat());
        }

        public WeChatImageMsg getImageMsg() {
            return message != null ? message.getImageMsg() : null;
        }

        public WeChatQuoteMsg getQuoteMsg() {
            return message != null ? message.getQuoteMsg() : null;
        }

        public WeChatFileMsg getFileMsg() {
            return message != null ? message.getFileMsg() : null;
        }

        public WeChatTransferMsg getTransferMsg() {
            return transferMsg != null ? transferMsg : (message != null ? message.getTransferMsg() : null);
        }

        public WeChatPatMsg getPatMsg() {
            if (!isPat()) return null;
            if (!TextUtils.isEmpty(patFromUser) || !TextUtils.isEmpty(patPattedUser)) {
                return new WeChatPatMsg(talker, patFromUser, patPattedUser, patTemplate, patCreateTime);
            }
            return message != null ? message.getPatMsg() : null;
        }

        public boolean isRecalled() {
            return Kind.RECALLED.equals(kind) || (message != null && message.isRecalled());
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

    private final WeChatMessageEventApi eventApi;
    private final WeChatMessageChangeApi changeApi;
    private final WeChatMessageParseApi parseApi;
    private final WeChatAccountApi accountApi;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Long> recentOutgoing = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> recentIncomingDatabaseMessages = new ConcurrentHashMap<>();
    private volatile boolean installed;
    private volatile boolean pbLayerActive;
    private volatile boolean dbLayerActive;
    private volatile long databaseIncomingStartedAt;

    public WeChatMessageObserveApi(WeChatMessageEventApi eventApi,
                                   WeChatMessageChangeApi changeApi,
                                   WeChatMessageParseApi parseApi,
                                   WeChatAccountApi accountApi,
                                   Logger logger) {
        this.eventApi = eventApi;
        this.changeApi = changeApi;
        this.parseApi = parseApi;
        this.accountApi = accountApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return (eventApi != null && eventApi.isAvailable())
                || (changeApi != null && changeApi.isAvailable());
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
        boolean usePbLayer = eventApi != null && eventApi.isAvailable();
        if (usePbLayer && !pbLayerActive) {
            eventApi.subscribeMessage(this::onMessageReceived);
            eventApi.subscribePat(this::onPatDetected);
            pbLayerActive = true;
        }
        if (changeApi != null && changeApi.isAvailable() && !dbLayerActive) {
            databaseIncomingStartedAt = System.currentTimeMillis();
            changeApi.subscribe(this::onMessageChanged);
            dbLayerActive = true;
        }
        installed = true;
        log("消息观察已安装: pb=" + usePbLayer
                + " db=" + (changeApi != null && changeApi.isAvailable()));
    }

    private void onMessageReceived(Events.MessageReceived event) {
        if (event == null) return;
        String nativeUrl = parseApi != null
                ? parseApi.getXmlParamByTag(event.xml, "nativeurl") : "";
        int type = parseMessageType(event.msgType, event.content);
        boolean outgoing = event.outgoing || isSelf(event.sender);
        String source = !TextUtils.isEmpty(event.source) ? event.source : "add_msg";
        WeChatMessage transientMessage = WeChatMessage.fromTransient(
                event.talker,
                event.sender,
                event.content,
                event.createTimeSeconds > 0 ? event.createTimeSeconds * 1000L : System.currentTimeMillis(),
                outgoing,
                type,
                event.msgSvrId,
                event.msgSource,
                selfWxId());
        String kind = kindOf(transientMessage, nativeUrl);
        if (outgoing && !"local_send".equals(source)) {
            markOutgoing(event.talker, event.content);
            WeChatMessageApi.cancelPendingLocalSend(event.talker, event.content);
        }
        dispatch(new ObservedMessage(
                source,
                kind,
                event.talker,
                event.sender,
                event.content,
                event.xml,
                nativeUrl,
                isGroup(event.talker),
                outgoing,
                null,
                transientMessage));
    }

    private void onRedPacketDetected(Events.RedPacketDetected event) {
        if (event == null) return;
        dispatch(new ObservedMessage(
                "red_packet",
                Kind.RED_PACKET,
                event.talker,
                event.sender,
                "",
                event.xml,
                event.nativeUrl,
                isGroup(event.talker),
                isSelf(event.sender),
                null));
    }

    private void onPatDetected(Events.PatDetected event) {
        if (event == null) return;
        boolean outgoing = isSelf(event.fromUser);
        long millis = event.createTime > 100000000000L
                ? event.createTime
                : (event.createTime > 0 ? event.createTime * 1000L : System.currentTimeMillis());
        WeChatMessage transientMessage = WeChatMessage.fromTransient(
                event.talker,
                event.fromUser,
                event.template,
                millis,
                outgoing,
                WeChatMessageTypes.SYSTEM,
                event.svrId);
        dispatch(new ObservedMessage(
                "pat_pb",
                Kind.PAT,
                event.talker,
                event.fromUser,
                event.template,
                "",
                "",
                isGroup(event.talker),
                outgoing,
                null,
                transientMessage,
                event.fromUser,
                event.pattedUser,
                event.template,
                event.createTime,
                event.svrId));
    }

    private void onMessageChanged(WeChatMessageChangeApi.MessageChange change) {
        if (change == null || change.message == null) return;
        WeChatMessage msg = change.message;
        if (pbLayerActive && !msg.isOutgoing()) return;
        if (!msg.isOutgoing() && (!change.isInsert() || !claimIncomingDatabaseMessage(msg))) return;
        if (msg.isOutgoing() && isRecentOutgoing(msg.talker, msg.content)) return;
        if (msg.isOutgoing()) {
            WeChatMessageApi.cancelPendingLocalSend(msg.talker, msg.content);
        }
        dispatch(new ObservedMessage(
                "message_db",
                kindOf(msg),
                msg.talker,
                msg.isOutgoing() ? selfWxId() : "",
                msg.content,
                parseApi != null ? parseApi.extractXml(msg.content) : msg.content,
                parseNativeUrl(msg.content),
                isGroup(msg.talker),
                msg.isOutgoing(),
                msg));
    }

    private boolean claimIncomingDatabaseMessage(WeChatMessage message) {
        long now = System.currentTimeMillis();
        long createTime = message.createTime;
        if (createTime > 0L && createTime < 100000000000L) createTime *= 1000L;
        if (createTime <= 0L
                || databaseIncomingStartedAt <= 0L
                || createTime < databaseIncomingStartedAt - INCOMING_DATABASE_START_GRACE_MS
                || createTime < now - INCOMING_DATABASE_FRESHNESS_MS
                || createTime > now + INCOMING_DATABASE_FRESHNESS_MS) {
            return false;
        }
        String key;
        if (message.msgSvrId > 0L) {
            key = "svr:" + message.msgSvrId;
        } else if (message.msgId > 0L) {
            key = "local:" + message.msgId;
        } else {
            key = "fallback:" + message.talker + ':' + message.type + ':' + createTime + ':'
                    + Integer.toHexString(message.content.hashCode());
        }
        if (recentIncomingDatabaseMessages.putIfAbsent(key, now) != null) return false;
        cleanupIncomingDatabaseMessages(now);
        return true;
    }

    private void cleanupIncomingDatabaseMessages(long now) {
        if (recentIncomingDatabaseMessages.size() < 1024) return;
        recentIncomingDatabaseMessages.entrySet().removeIf(
                entry -> now - entry.getValue() > INCOMING_DATABASE_DEDUP_TTL_MS);
    }

    private void markOutgoing(String talker, String content) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) return;
        recentOutgoing.put(outgoingKey(talker, content), System.currentTimeMillis());
        cleanupRecentOutgoing();
    }

    private boolean isRecentOutgoing(String talker, String content) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) return false;
        Long time = recentOutgoing.get(outgoingKey(talker, content));
        return time != null && System.currentTimeMillis() - time < 10000L;
    }

    private String outgoingKey(String talker, String content) {
        return talker + '\n' + content;
    }

    private void cleanupRecentOutgoing() {
        if (recentOutgoing.size() < 64) return;
        long now = System.currentTimeMillis();
        recentOutgoing.entrySet().removeIf(entry -> now - entry.getValue() > 10000L);
    }

    private static final long INCOMING_DATABASE_FRESHNESS_MS = 5L * 60L * 1000L;
    private static final long INCOMING_DATABASE_START_GRACE_MS = 10L * 1000L;
    private static final long INCOMING_DATABASE_DEDUP_TTL_MS = 60L * 60L * 1000L;

    private String kindOf(WeChatMessage msg) {
        return kindOf(msg, "");
    }

    private String kindOf(WeChatMessage msg, String nativeUrl) {
        if (msg == null) return Kind.UNKNOWN;
        if (msg.isRedPacket() || containsRedPacket(msg.content)) {
            return Kind.RED_PACKET;
        }
        if (msg.isTransfer()) return Kind.TRANSFER;
        if (msg.isQuote()) return Kind.QUOTE;
        if (msg.isPat()) return Kind.PAT;
        if (msg.isNote()) return Kind.NOTE;
        if (msg.isFile()) return Kind.FILE;
        if (msg.isLink()) return Kind.LINK;
        if (msg.isMusic()) return Kind.MUSIC;
        if (msg.isVideoNumberVideo()) return Kind.VIDEO_NUMBER_VIDEO;
        if (msg.isShareCard()) return Kind.SHARE_CARD;
        if (msg.isVoip()) return Kind.VOIP;
        if (msg.isRecalled()) return Kind.RECALLED;
        switch (msg.type) {
            case WeChatMessageTypes.TEXT:
                return Kind.TEXT;
            case WeChatMessageTypes.IMAGE:
                return Kind.IMAGE;
            case WeChatMessageTypes.VOICE:
                return Kind.VOICE;
            case WeChatMessageTypes.VIDEO:
                return Kind.VIDEO;
            case WeChatMessageTypes.EMOJI:
                return Kind.EMOJI;
            case WeChatMessageTypes.APP:
                return Kind.APP;
            case WeChatMessageTypes.LOCATION:
                return Kind.LOCATION;
            case WeChatMessageTypes.SYSTEM:
                return Kind.SYSTEM;
            case WeChatMessageTypes.RECALLED:
                return Kind.RECALLED;
            default:
                return msg.type > 0 ? "type_" + msg.type : Kind.UNKNOWN;
        }
    }

    private String parseNativeUrl(String content) {
        if (parseApi == null) return "";
        String xml = parseApi.extractXml(content);
        return parseApi.getXmlParamByTag(xml, "nativeurl");
    }

    private int parseMessageType(String value, String content) {
        if (!TextUtils.isEmpty(value)) {
            try {
                int type = Integer.parseInt(value);
                if (type > 0) return type;
            } catch (Throwable ignored) {}
        }
        return WeChatMessage.inferType(content);
    }

    private boolean containsRedPacket(String content) {
        return parseApi != null && parseApi.containsRedPacketMarker(content);
    }

    private boolean isGroup(String talker) {
        return parseApi != null && parseApi.isGroupTalker(talker);
    }

    private boolean isSelf(String wxId) {
        String self = selfWxId();
        return !TextUtils.isEmpty(self) && self.equals(wxId);
    }

    private String selfWxId() {
        return accountApi != null ? accountApi.selfWxId() : "";
    }

    private void dispatch(ObservedMessage message) {
        for (Listener listener : listeners) {
            try {
                listener.onObservedMessage(message);
            } catch (Throwable e) {
                log("消息观察回调失败: " + e.getMessage());
            }
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatMessageObserveApi] " + message);
    }
}
