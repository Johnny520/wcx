package h.Hchat.hooks.api.message;

import android.os.StrictMode;
import android.os.Build;
import android.text.TextUtils;
import android.util.Pair;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.event.EventBus;
import h.Hchat.event.Events;
import h.Hchat.hooks.api.contact.WeChatAccountApi;
import h.Hchat.hooks.api.contact.WeChatContactApi;
import h.Hchat.hooks.api.core.WeChatApis;
import h.Hchat.hooks.api.media.WeChatInternalServices;
import h.Hchat.hooks.api.model.WeChatContact;
import h.Hchat.hooks.api.model.WeChatMessage;
import h.Hchat.hooks.api.model.WeChatQuoteMsg;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.items.payment.detect.RedPacketReflector;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信文本消息发送 API。
 *
 * 基于微信 NetSceneSendMsg 构造请求，再交给模块内统一网络发包器发送。
 */
public final class WeChatMessageApi {
    public interface Logger {
        void log(String message);
    }

    public interface SendCallback {
        void onResult(boolean success, Throwable error);
    }

    private static final int MESSAGE_TYPE_TEXT = 1;
    private static final int MESSAGE_TYPE_SHARE_CARD = 42;
    private static final int MESSAGE_TYPE_LOCATION = 48;
    private static final int MESSAGE_TYPE_APP_XML = 49;
    private static final int MESSAGE_TYPE_OPENIM_SHARE_CARD = 66;
    private static final int SEND_FLAG_NORMAL = 0;
    private static final int SEND_FLAG_MSGSOURCE = 1;
    private static final int PAT_SEND_SCENE = 0;
    private static final String MSGSOURCE_AT_LIST = "atuserlist";
    private static final String AT_SEPARATOR = "\u2005";
    private static final Pattern APPMSG_APPID_ATTR =
            Pattern.compile("<appmsg\\b[^>]*\\bappid\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAPP_APPID_TAG =
            Pattern.compile("<appid>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</appid>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SOURCE_NAME_TAG =
            Pattern.compile("<sourcedisplayname>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</sourcedisplayname>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MSGSOURCE_TAG =
            Pattern.compile("<msgsource\\b[^>]*>.*?</msgsource>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TP_THUMB_URL_TAG =
            Pattern.compile("<tpthumburl>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</tpthumburl>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WEAPP_ICON_URL_TAG =
            Pattern.compile("<weappiconurl>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</weappiconurl>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern THUMB_URL_TAG =
            Pattern.compile("<thumburl>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</thumburl>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CDN_THUMB_URL_TAG =
            Pattern.compile("<cdnthumburl>\\s*(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?\\s*</cdnthumburl>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int THUMB_CONNECT_TIMEOUT_MS = 5000;
    private static final int THUMB_READ_TIMEOUT_MS = 8000;
    private static final int MAX_THUMB_BYTES = 512 * 1024;
    private static final int MAX_CACHED_THUMBS = 64;
    private static final long LOCAL_SEND_FALLBACK_DELAY_MS = 2500L;
    private static final ConcurrentHashMap<String, byte[]> THUMB_DATA_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<String> THUMB_PREFETCHING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService THUMB_PREFETCH_EXECUTOR =
            new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_CACHED_THUMBS), r -> {
                        Thread thread = new Thread(r, "HchatXmlThumbPrefetch");
                        thread.setDaemon(true);
                        return thread;
                    });
    private static final ScheduledExecutorService LOCAL_SEND_FALLBACK_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "HchatLocalSendFallback");
                thread.setDaemon(true);
                return thread;
            });
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<PendingLocalSend>>
            PENDING_LOCAL_SENDS = new ConcurrentHashMap<>();

    private final DexFinder dexFinder;
    private final WeChatNetworkDispatcher networkDispatcher;
    private final WeChatAccountApi accountApi;
    private final WeChatContactApi contactApi;
    private final WeChatMessageStoreApi messageStoreApi;
    private final EventBus eventBus;
    private final Logger logger;
    private volatile boolean networkHookInstalled;

    private static final class PendingLocalSend {
        final EventBus eventBus;
        final Events.MessageReceived event;
        volatile ScheduledFuture<?> future;

        PendingLocalSend(EventBus eventBus, Events.MessageReceived event) {
            this.eventBus = eventBus;
            this.event = event;
        }
    }

    public WeChatMessageApi(DexFinder dexFinder,
                            WeChatNetworkDispatcher networkDispatcher,
                            Logger logger) {
        this(dexFinder, networkDispatcher, null, null, null, logger);
    }

    public WeChatMessageApi(DexFinder dexFinder,
                            WeChatNetworkDispatcher networkDispatcher,
                            WeChatContactApi contactApi,
                            Logger logger) {
        this(dexFinder, networkDispatcher, null, contactApi, null, null, logger);
    }

    public WeChatMessageApi(DexFinder dexFinder,
                            WeChatNetworkDispatcher networkDispatcher,
                            WeChatContactApi contactApi,
                            WeChatMessageStoreApi messageStoreApi,
                            Logger logger) {
        this(dexFinder, networkDispatcher, null, contactApi, messageStoreApi, null, logger);
    }

    public WeChatMessageApi(DexFinder dexFinder,
                            WeChatNetworkDispatcher networkDispatcher,
                            WeChatContactApi contactApi,
                            EventBus eventBus,
                            Logger logger) {
        this(dexFinder, networkDispatcher, null, contactApi, null, eventBus, logger);
    }

    public WeChatMessageApi(DexFinder dexFinder,
                            WeChatNetworkDispatcher networkDispatcher,
                            WeChatAccountApi accountApi,
                            WeChatContactApi contactApi,
                            WeChatMessageStoreApi messageStoreApi,
                            Logger logger) {
        this(dexFinder, networkDispatcher, accountApi, contactApi, messageStoreApi, null, logger);
    }

    public WeChatMessageApi(DexFinder dexFinder,
                            WeChatNetworkDispatcher networkDispatcher,
                            WeChatAccountApi accountApi,
                            WeChatContactApi contactApi,
                            WeChatMessageStoreApi messageStoreApi,
                            EventBus eventBus,
                            Logger logger) {
        this.dexFinder = dexFinder;
        this.networkDispatcher = networkDispatcher;
        this.accountApi = accountApi;
        this.contactApi = contactApi;
        this.messageStoreApi = messageStoreApi;
        this.eventBus = eventBus;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return dexFinder != null
                && dexFinder.sendTextMsgClass != null
                && (dexFinder.sendTextMsgCtorLong != null || dexFinder.sendTextMsgCtorObject != null)
                && networkDispatcher != null;
    }

    public void installNetworkHook() {
        if (networkHookInstalled || networkDispatcher == null || dexFinder == null) return;
        if (dexFinder.netQueueClass == null && dexFinder.netQueueCandidateClasses.isEmpty()) return;
        networkDispatcher.hookNetworkQueue(dexFinder.netQueueClass, dexFinder.netQueueCandidateClasses);
        networkHookInstalled = true;
    }

    public boolean sendText(String talker, String text) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(text)) {
            log("发送文本失败: talker/text 为空");
            return false;
        }
        if (!isAvailable()) {
            log("发送文本失败: API 未就绪");
            return false;
        }

        installNetworkHook();
        try {
            String formattedText = OutgoingTextDecoratorRegistry.decorateText(talker, text);
            Object request = newTextRequest(talker, formattedText);
            boolean result = networkDispatcher.send(request);
            if (result) dispatchLocalSend(talker, formattedText, MESSAGE_TYPE_TEXT, null);
            log("发送文本" + (result ? "成功" : "失败") + ": " + talker);
            return result;
        } catch (Throwable e) {
            log("发送文本异常: " + e.getMessage());
            return false;
        }
    }

    public boolean sendXml(String talker, String xml) {
        String normalized = normalizeXmlContent(xml);
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(normalized)) {
            log("发送XML失败: talker/xml 为空");
            return false;
        }
        if (!looksLikeXml(normalized)) {
            log("发送XML失败: 内容不是XML");
            return false;
        }
        if (isAppMsgXml(normalized)) {
            return sendAppMsgXml(talker, normalized);
        }
        return sendRaw(talker, normalized, MESSAGE_TYPE_APP_XML);
    }

    public boolean sendRaw(String talker, String content, int messageType) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) {
            log("发送原始消息失败: talker/content 为空");
            return false;
        }
        if (messageType <= 0) {
            log("发送原始消息失败: messageType 无效 " + messageType);
            return false;
        }
        if (messageType == MESSAGE_TYPE_TEXT) {
            return sendText(talker, content);
        }
        if (!isAvailable()) {
            log("发送原始消息失败: API 未就绪");
            return false;
        }

        installNetworkHook();
        try {
            Object request = newMessageRequest(talker, content, messageType, SEND_FLAG_NORMAL, null);
            boolean result = networkDispatcher.send(request);
            if (result) dispatchLocalSend(talker, content, messageType, null);
            log("发送原始消息" + (result ? "成功" : "失败")
                    + ": " + talker + " type=" + messageType);
            return result;
        } catch (Throwable e) {
            log("发送原始消息异常: " + e.getMessage());
            return false;
        }
    }

    public boolean sendQuote(String talker, long msgId, String content) {
        if (TextUtils.isEmpty(talker)) {
            log("发送引用失败: talker为空");
            return false;
        }
        if (msgId <= 0L) {
            log("发送引用失败: msgId无效");
            return false;
        }
        if (messageStoreApi == null || !messageStoreApi.isAvailable()) {
            log("发送引用失败: messageStore未就绪");
            return false;
        }
        WeChatMessage source = messageStoreApi.getMessageById(msgId);
        if (source == null) {
            source = messageStoreApi.getMessageBySvrId(msgId);
        }
        if (source == null) {
            log("发送引用失败: 未找到源消息 msgId/msgSvrId=" + msgId);
            return false;
        }
        String xml = buildQuoteXml(talker, source, content);
        if (TextUtils.isEmpty(xml)) {
            log("发送引用失败: 构造引用XML失败 msgId=" + msgId);
            return false;
        }
        return sendXml(talker, xml);
    }

    public boolean revoke(long msgId) {
        if (msgId <= 0L) {
            log("撤回消息失败: msgId无效");
            return false;
        }
        if (messageStoreApi == null || !messageStoreApi.isAvailable()) {
            log("撤回消息失败: messageStore未就绪");
            return false;
        }
        if (dexFinder == null || dexFinder.revokeMsgCtor == null || networkDispatcher == null) {
            log("撤回消息失败: API未就绪");
            return false;
        }
        WeChatMessage message = messageStoreApi.getMessageById(msgId);
        if (message == null) {
            message = messageStoreApi.getMessageBySvrId(msgId);
        }
        if (message == null) {
            log("撤回消息失败: 未找到消息 msgId/msgSvrId=" + msgId);
            return false;
        }
        if (message.isSend != 1) {
            log("撤回消息失败: 只能撤回自己发送的消息 msgId=" + message.msgId);
            return false;
        }
        long localMsgId = message.msgId;
        if (WeChatApis.database() != null) {
            WeChatApis.database().messageTableForTalker(message.talker);
        }
        Object nativeMessage = WeChatApis.database() != null
                ? WeChatApis.database().nativeMessageById(localMsgId)
                : null;
        if (nativeMessage == null) {
            nativeMessage = findNativeMessage(message);
        }
        if (nativeMessage == null) {
            nativeMessage = rebuildNativeMessage(message);
        }
        if (nativeMessage == null) {
            log("撤回消息失败: 原生消息对象为空 msgId=" + localMsgId
                    + " msgSvrId=" + message.msgSvrId);
            return false;
        }

        return revokeNative(nativeMessage);
    }

    public boolean revokeNative(Object nativeMessage) {
        if (nativeMessage == null) {
            log("撤回消息失败: 原生消息对象为空");
            return false;
        }
        if (dexFinder == null || dexFinder.revokeMsgCtor == null || networkDispatcher == null) {
            log("撤回消息失败: API未就绪");
            return false;
        }

        installNetworkHook();
        try {
            Object scene = KavaReflector.newInstance(
                    dexFinder.revokeMsgCtor,
                    nativeMessage,
                    "你撤回了一条消息",
                    "");
            if (scene == null) {
                log("撤回消息失败: NetSceneRevokeMsg构造失败");
                return false;
            }
            boolean result = networkDispatcher.send(scene);
            log("撤回原生消息" + (result ? "已发送" : "发送失败")
                    + ": msgId=" + readNativeMessageId(nativeMessage));
            return result;
        } catch (Throwable e) {
            log("撤回消息异常: " + e.getMessage());
            return false;
        }
    }

    private long readNativeMessageId(Object nativeMessage) {
        if (nativeMessage == null) return 0L;
        for (String name : new String[]{"getMsgId", "getMsgID", "getId"}) {
            Object value = KavaReflector.invokeMethod(nativeMessage, name);
            if (value instanceof Number) return ((Number) value).longValue();
        }
        for (String name : new String[]{"field_msgId", "msgId", "msgID", "id"}) {
            Object value = KavaReflector.readField(nativeMessage, name);
            if (value instanceof Number) return ((Number) value).longValue();
        }
        return 0L;
    }

    private Object findNativeMessage(WeChatMessage message) {
        if (message == null || WeChatApis.database() == null) return null;
        Object nativeMessage = WeChatApis.database().nativeMessageById(message.msgId);
        if (nativeMessage != null) return nativeMessage;

        if (message.msgSvrId > 0L && messageStoreApi != null) {
            WeChatMessage bySvrId = messageStoreApi.getMessageBySvrId(message.talker, message.msgSvrId);
            if (bySvrId != null && bySvrId.msgId > 0L && bySvrId.msgId != message.msgId) {
                nativeMessage = WeChatApis.database().nativeMessageById(bySvrId.msgId);
                if (nativeMessage != null) return nativeMessage;
            }
        }
        return null;
    }

    private Object rebuildNativeMessage(WeChatMessage message) {
        if (message == null || dexFinder == null || dexFinder.localMessageCtor == null) return null;
        try {
            Object nativeMessage;
            if (dexFinder.localMessageCtor.getParameterTypes().length == 0) {
                nativeMessage = KavaReflector.newInstance(dexFinder.localMessageCtor);
            } else {
                nativeMessage = KavaReflector.newInstance(dexFinder.localMessageCtor, message.talker);
            }
            if (nativeMessage == null) return null;
            writeMessageField(nativeMessage, message.msgId, "field_msgId", "msgId");
            writeMessageField(nativeMessage, message.msgSvrId, "field_msgSvrId", "msgSvrId");
            writeMessageField(nativeMessage, message.type, "field_type", "type");
            writeMessageField(nativeMessage, message.status, "field_status", "status");
            writeMessageField(nativeMessage, message.isSend, "field_isSend", "isSend");
            writeMessageField(nativeMessage, message.createTime, "field_createTime", "createTime");
            writeMessageField(nativeMessage, message.talker, "field_talker", "talker");
            writeMessageField(nativeMessage, message.content, "field_content", "content");
            writeMessageField(nativeMessage, message.imagePath, "field_imgPath", "imgPath");
            writeMessageField(nativeMessage, message.reserved, "field_reserved", "reserved");
            writeMessageField(nativeMessage, message.translatedContent, "field_transContent", "transContent");
            writeMessageField(nativeMessage, message.flag, "field_flag", "flag");
            writeMessageField(nativeMessage, message.msgSource, "field_msgSource", "msgSource");
            return nativeMessage;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeMessageField(Object target, Object value, String... fieldNames) {
        if (target == null || fieldNames == null) return;
        for (String fieldName : fieldNames) {
            if (TextUtils.isEmpty(fieldName)) continue;
            try {
                Field field = KavaReflector.findFieldRecursive(target.getClass(), fieldName);
                if (field == null) continue;
                if (KavaReflector.writeField(field, target, value)) return;
            } catch (Throwable ignored) {
            }
        }
    }

    public boolean uploadDeviceStep(long step) {
        if (step <= 0L) {
            log("上传步数失败: step无效");
            return false;
        }
        if (dexFinder == null || dexFinder.uploadDeviceStepCtor == null || networkDispatcher == null) {
            log("上传步数失败: API未就绪");
            return false;
        }
        installNetworkHook();
        try {
            int safeStep = (int) Math.min(step, Integer.MAX_VALUE);
            Object scene = KavaReflector.newInstance(
                    dexFinder.uploadDeviceStepCtor,
                    "",
                    "gh_43f2581f6fd6",
                    todayStartSeconds(),
                    nowSeconds(),
                    safeStep,
                    buildDeviceInfoXml(),
                    1);
            boolean result = networkDispatcher.send(scene);
            log("上传步数" + (result ? "已发送" : "发送失败") + ": step=" + safeStep);
            return result;
        } catch (Throwable e) {
            log("上传步数异常: " + e.getMessage());
            return false;
        }
    }

    public boolean sendPat(String talker, String pattedUser) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(pattedUser)) {
            log("发送拍一拍失败: talker/pattedUser 为空");
            return false;
        }
        if (dexFinder == null
                || dexFinder.serviceGetterMethod == null
                || dexFinder.patCreatePairMethod == null
                || dexFinder.patSuffixMethod == null
                || dexFinder.sendPatSceneCtor == null
                || networkDispatcher == null) {
            log("发送拍一拍失败: API 未就绪");
            return false;
        }
        String selfWxId = accountApi != null ? accountApi.selfWxId() : "";

        installNetworkHook();
        try {
            Object patExtension = getPatExtensionInstance();
            if (patExtension == null) {
                log("发送拍一拍失败: 拍一拍服务为空");
                return false;
            }
            if (dexFinder.patCanSendMethod != null) {
                Object canSend = KavaReflector.invoke(
                        dexFinder.patCanSendMethod, patExtension, PAT_SEND_SCENE, talker, pattedUser);
                if (canSend instanceof Boolean && !((Boolean) canSend)) {
                    log("发送拍一拍失败: 微信原生校验不允许 talker=" + talker
                            + " pattedUser=" + pattedUser);
                    return false;
                }
            }
            Object suffixValue = KavaReflector.invoke(
                    dexFinder.patSuffixMethod, patExtension, pattedUser, talker);
            String suffix = suffixValue instanceof String ? (String) suffixValue : "";
            int createTimeSec = (int) (System.currentTimeMillis() / 1000L);
            String nativeSelfWxId = resolveNativePatSelfWxId(talker, pattedUser);
            if (!TextUtils.isEmpty(nativeSelfWxId)) {
                selfWxId = nativeSelfWxId;
            }
            if (TextUtils.isEmpty(selfWxId)) {
                log("发送拍一拍失败: 自身wxid为空");
                return false;
            }
            Object pairValue = KavaReflector.invoke(
                    dexFinder.patCreatePairMethod,
                    patExtension,
                    talker,
                    selfWxId,
                    pattedUser,
                    suffix,
                    createTimeSec,
                    0L);
            if (!(pairValue instanceof Pair)) {
                log("发送拍一拍失败: 本地消息结果无效");
                return false;
            }
            Pair<?, ?> pair = (Pair<?, ?>) pairValue;
            Object msgId = pair.first;
            if (!(msgId instanceof Number) || ((Number) msgId).longValue() <= 0L) {
                log("发送拍一拍失败: 本地消息插入失败");
                return false;
            }
            Object scene = KavaReflector.newInstance(
                    dexFinder.sendPatSceneCtor, pair, talker, pattedUser, PAT_SEND_SCENE);
            boolean result = networkDispatcher.send(scene);
            if (!result) {
                log("发送拍一拍失败: 网络入队失败 msgId=" + pair.first
                        + " createTime=" + pair.second
                        + " talker=" + talker
                        + " pattedUser=" + pattedUser);
            }
            return result;
        } catch (Throwable e) {
            log("发送拍一拍异常: " + e.getMessage());
            return false;
        }
    }

    private String resolveNativePatSelfWxId(String talker, String pattedUser) {
        try {
            if (dexFinder == null || dexFinder.sendPatSceneCtor == null) return "";
            Pair<Long, Long> probePair = Pair.create(0L, 0L);
            Object scene = KavaReflector.newInstance(
                    dexFinder.sendPatSceneCtor, probePair, talker, pattedUser, PAT_SEND_SCENE);
            return findPatRequestSelf(
                    scene,
                    talker,
                    pattedUser,
                    4,
                    Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String findPatRequestSelf(Object target,
                                      String talker,
                                      String pattedUser,
                                      int depth,
                                      Set<Object> visited) {
        if (target == null || depth < 0) return "";
        try {
            if (visited != null) {
                if (visited.contains(target)) return "";
                visited.add(target);
            }
        } catch (Throwable ignored) {}

        List<String> values = stringFieldValues(target);
        if (values.size() >= 3
                && values.get(1).equals(talker)
                && values.get(2).equals(pattedUser)) {
            return values.get(0);
        }

        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                try {
                    Class<?> type = field.getType();
                    if (shouldSkipPatSelfScan(type)) continue;
                    Object value = KavaReflector.readField(field, target);
                    String found = findPatRequestSelf(value, talker, pattedUser, depth - 1, visited);
                    if (!TextUtils.isEmpty(found)) return found;
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return "";
    }

    private List<String> stringFieldValues(Object target) {
        List<String> result = new ArrayList<>();
        if (target == null) return result;
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                try {
                    if (field.getType() != String.class) continue;
                    Object value = KavaReflector.readField(field, target);
                    if (value instanceof String && !TextUtils.isEmpty((String) value)) {
                        result.add((String) value);
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private boolean shouldSkipPatSelfScan(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum() || type.isArray()) return true;
        return type == String.class
                || Number.class.isAssignableFrom(type)
                || type == Boolean.class
                || type == Character.class
                || type == Class.class
                || type == Method.class
                || type == Field.class
                || type.getName().startsWith("java.");
    }

    public boolean sendShareCard(String talker, String wxId) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(wxId)) {
            log("发送名片失败: talker/wxid 为空");
            return false;
        }
        if (!isAvailable()) {
            log("发送名片失败: 发送API未就绪");
            return false;
        }
        if (dexFinder == null || dexFinder.contactCardXmlMethod == null) {
            log("发送名片失败: 名片API未就绪");
            return false;
        }

        installNetworkHook();
        try {
            String xml = buildContactCardXml(wxId);
            if (TextUtils.isEmpty(xml)) {
                log("发送名片失败: 构造名片XML失败 " + wxId);
                return false;
            }
            int type = xml.contains("openimappid=")
                    ? MESSAGE_TYPE_OPENIM_SHARE_CARD
                    : MESSAGE_TYPE_SHARE_CARD;
            Object request = newMessageRequest(talker, xml, type, SEND_FLAG_NORMAL, null);
            boolean result = networkDispatcher.send(request);
            if (result) dispatchLocalSend(talker, xml, type, null);
            log("发送名片" + (result ? "成功" : "失败") + ": " + talker + " wxid=" + wxId);
            return result;
        } catch (Throwable e) {
            log("发送名片异常: " + e.getMessage());
            return false;
        }
    }

    public boolean sendLocation(String talker,
                                String poiName,
                                String label,
                                String longitude,
                                String latitude,
                                String scale) {
        if (TextUtils.isEmpty(talker)
                || TextUtils.isEmpty(longitude)
                || TextUtils.isEmpty(latitude)) {
            log("发送位置失败: talker/经纬度为空");
            return false;
        }
        String xml = buildLocationXml(poiName, label, longitude, latitude, scale);
        if (TextUtils.isEmpty(xml)) {
            log("发送位置失败: XML构造失败");
            return false;
        }
        return sendRaw(talker, xml, MESSAGE_TYPE_LOCATION);
    }

    public boolean canSendAt() {
        return isAvailable() && dexFinder.sendTextMsgCtorObject != null;
    }

    public boolean canSendXml() {
        return dexFinder != null && dexFinder.sendXmlAppMsgMethod != null;
    }

    public boolean sendAt(String talker, String wxId, String text) {
        if (TextUtils.isEmpty(wxId)) {
            log("发送@文本失败: wxId 为空");
            return false;
        }
        List<String> atList = new ArrayList<>();
        atList.add(wxId);
        return sendTextWithAtList(talker, buildAtContent(talker, wxId, text), atList);
    }

    public boolean sendAtAll(String talker, String text) {
        List<String> atList = new ArrayList<>();
        atList.add("notify@all");
        return sendTextWithAtList(talker, buildAtContent(talker, "notify@all", text), atList);
    }

    public boolean sendTextWithAtList(String talker, String text, List<String> atWxIds) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(text)) {
            log("发送@文本失败: talker/text 为空");
            return false;
        }

        List<String> cleanAtList = cleanAtList(atWxIds);
        if (cleanAtList.isEmpty()) {
            return sendText(talker, text);
        }
        if (!canSendAt()) {
            log("发送@文本失败: Object构造器不可用");
            return false;
        }

        installNetworkHook();
        try {
            String formattedText = OutgoingTextDecoratorRegistry.decorateText(talker, text);
            Object request = newTextRequest(
                    talker,
                    formattedText,
                    SEND_FLAG_MSGSOURCE,
                    buildAtMsgSource(cleanAtList));
            boolean result = networkDispatcher.send(request);
            if (result) {
                dispatchLocalSend(
                        talker,
                        formattedText,
                        MESSAGE_TYPE_TEXT,
                        buildAtMsgSource(cleanAtList));
            }
            log("发送@文本" + (result ? "成功" : "失败") + ": " + talker);
            return result;
        } catch (Throwable e) {
            log("发送@文本异常: " + e.getMessage());
            return false;
        }
    }

    public void sendTextAsync(final String talker, final String text, final SendCallback callback) {
        new Thread(() -> {
            Throwable error = null;
            boolean success = false;
            try {
                success = sendText(talker, text);
            } catch (Throwable e) {
                error = e;
            }
            if (callback != null) {
                try {
                    callback.onResult(success, error);
                } catch (Throwable ignored) {}
            }
        }, "HchatTextSender").start();
    }

    private Object newTextRequest(String talker, String text) throws Throwable {
        return newMessageRequest(talker, text, MESSAGE_TYPE_TEXT, SEND_FLAG_NORMAL, null);
    }

    private Object newTextRequest(String talker, String text, int sendFlag, Object sourceArgs) throws Throwable {
        return newMessageRequest(talker, text, MESSAGE_TYPE_TEXT, sendFlag, sourceArgs);
    }

    private Object newMessageRequest(String talker,
                                     String content,
                                     int messageType,
                                     int sendFlag,
                                     Object sourceArgs) throws Throwable {
        if (sourceArgs != null) {
            Constructor<?> objectCtor = dexFinder.sendTextMsgCtorObject;
            if (objectCtor != null) {
                return KavaReflector.newInstance(objectCtor, talker, content, messageType, sendFlag, sourceArgs);
            }
        }

        Constructor<?> longCtor = dexFinder.sendTextMsgCtorLong;
        if (longCtor != null) {
            return KavaReflector.newInstance(longCtor, talker, content, messageType, sendFlag, 0L);
        }

        Constructor<?> objectCtor = dexFinder.sendTextMsgCtorObject;
        if (objectCtor != null) {
            return KavaReflector.newInstance(objectCtor, talker, content, messageType, sendFlag, sourceArgs);
        }

        return RedPacketReflector.newInstanceByArgs(
                dexFinder.sendTextMsgClass,
                new Object[]{
                        talker,
                        content,
                        messageType,
                        sendFlag,
                        sourceArgs != null ? sourceArgs : 0L
                }
        );
    }

    private Object getPatExtensionInstance() {
        if (dexFinder == null || dexFinder.serviceGetterMethod == null) return null;
        Object instance = getServiceInstance(dexFinder.patCreatePairMethod != null
                ? dexFinder.patCreatePairMethod.getDeclaringClass()
                : dexFinder.patExtensionClass);
        if (instance != null) return instance;
        Class<?>[] interfaces = dexFinder.patExtensionClass != null
                ? dexFinder.patExtensionClass.getInterfaces()
                : new Class<?>[0];
        for (Class<?> serviceClass : interfaces) {
            instance = getServiceInstance(serviceClass);
            if (instance != null) return instance;
        }
        return getServiceInstance(dexFinder.patExtensionClass);
    }

    private int nowSeconds() {
        return (int) (System.currentTimeMillis() / 1000L);
    }

    private int todayStartSeconds() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return (int) (calendar.getTimeInMillis() / 1000L);
    }

    private String buildDeviceInfoXml() {
        return "<deviceinfo><MANUFACTURER name=\"" + escapeXml(Build.MANUFACTURER)
                + "\"><MODEL name=\"" + escapeXml(Build.MODEL)
                + "\"><VERSION_RELEASE name=\"" + escapeXml(Build.VERSION.RELEASE)
                + "\"><VERSION_INCREMENTAL name=\"" + escapeXml(Build.VERSION.INCREMENTAL)
                + "\"><DISPLAY name=\"" + escapeXml(Build.DISPLAY)
                + "\"></DISPLAY></VERSION_INCREMENTAL></VERSION_RELEASE></MODEL></MANUFACTURER></deviceinfo>";
    }

    private String buildContactCardXml(String wxId) {
        try {
            Object result = KavaReflector.invoke(dexFinder.contactCardXmlMethod, null, wxId, null);
            return result instanceof String ? (String) result : "";
        } catch (Throwable e) {
            log("构造名片XML异常: " + e.getMessage());
            return "";
        }
    }

    private Object getServiceInstance(Class<?> serviceClass) {
        if (serviceClass == null || dexFinder == null || dexFinder.serviceGetterMethod == null) return null;
        try {
            return KavaReflector.invoke(dexFinder.serviceGetterMethod, null, serviceClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean sendAppMsgXml(String talker, String xml) {
        if (!canSendXml()) {
            log("发送XML失败: AppMsgLogic API 未就绪");
            return false;
        }

        try {
            Method sendMethod = dexFinder.sendXmlAppMsgMethod;
            Object appMessage = parseAppMessage(sendMethod.getParameterTypes()[0], xml);
            if (appMessage == null) {
                log("发送XML失败: 微信解析AppMsg失败");
                return false;
            }

            String msgSource = extractMsgSource(xml);
            Object[] args = buildSendAppMsgArgs(sendMethod, appMessage, talker, xml, msgSource);
            if (args.length == 12 && args[9] == null) {
                log("发送XML失败: MsgIdTalker参数创建失败");
                return false;
            }
            Object result = KavaReflector.invoke(sendMethod, null, args);
            boolean success = isSuccessfulPair(result);
            if (!success) {
                log("发送XML失败: AppMsgLogic返回 " + pairToString(result));
            } else {
                dispatchLocalSend(talker, xml, MESSAGE_TYPE_APP_XML, msgSource);
            }
            return success;
        } catch (Throwable e) {
            log("发送XML异常: " + e.getMessage());
            return false;
        }
    }

    private Object parseAppMessage(Class<?> appMsgClass, String xml) {
        Method preferred = dexFinder != null ? dexFinder.appMsgParseMethod : null;
        Object parsed = invokeParseMethod(preferred, appMsgClass, xml);
        if (parsed != null) return parsed;

        try {
            for (Method method : KavaReflector.declaredMethods(appMsgClass)) {
                if (!isParseMethod(method, appMsgClass)) continue;
                parsed = invokeParseMethod(method, appMsgClass, xml);
                if (parsed != null) {
                    if (dexFinder != null) dexFinder.appMsgParseMethod = method;
                    return parsed;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object invokeParseMethod(Method method, Class<?> appMsgClass, String xml) {
        if (!isParseMethod(method, appMsgClass)) return null;
        try {
            Object result = KavaReflector.invoke(method, null, xml);
            return appMsgClass.isInstance(result) ? result : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isParseMethod(Method method, Class<?> appMsgClass) {
        if (method == null || appMsgClass == null) return false;
        if (!KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != appMsgClass) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && params[0] == String.class;
    }

    private Object[] buildSendAppMsgArgs(Method method, Object appMessage, String talker, String xml, String msgSource) {
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        args[0] = appMessage;
        args[1] = extractAppId(xml);
        args[2] = extractSource(xml);
        args[3] = talker;
        args[4] = "";
        args[5] = cachedThumbData(xml);
        args[6] = "Hchat_xml_" + System.currentTimeMillis();
        args[7] = "";
        args[8] = msgSource != null ? msgSource : "";

        if (params.length == 10) {
            args[9] = 0L;
        } else if (params.length == 12) {
            args[9] = newMsgIdTalker(params[9], talker);
            args[10] = false;
            args[11] = "";
        }
        return args;
    }

    private Object newMsgIdTalker(Class<?> clazz, String talker) {
        try {
            Object value = KavaReflector.newInstance(
                    KavaReflector.findConstructor(clazz, long.class, String.class),
                    0L,
                    talker
            );
            if (value != null) return value;
        } catch (Throwable ignored) {
        }
        Object value = staticInstance(clazz);
        if (value != null) return value;
        try {
            return KavaReflector.newInstance(KavaReflector.findConstructor(clazz));
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object staticInstance(Class<?> clazz) {
        return KavaReflector.staticInstance(clazz);
    }

    private boolean isSuccessfulPair(Object result) {
        if (!(result instanceof Pair)) return false;
        Pair<?, ?> pair = (Pair<?, ?>) result;
        Object first = pair.first;
        Object second = pair.second;
        int code = first instanceof Number ? ((Number) first).intValue() : -1;
        if (code != 0) return false;
        return !(second instanceof Number) || ((Number) second).longValue() >= 0;
    }

    private void dispatchLocalSend(String talker, String content, int messageType, Object msgSource) {
        if (eventBus == null || TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) return;
        try {
            Events.MessageReceived event = new Events.MessageReceived(
                    isAppMsgXml(content) ? content : "",
                    talker,
                    talker,
                    content,
                    String.valueOf(messageType),
                    System.currentTimeMillis() / 1000L,
                    0L,
                    msgSource != null ? String.valueOf(msgSource) : null,
                    null,
                    "local_send",
                    true);
            scheduleLocalSendFallback(talker, content, eventBus, event);
        } catch (Throwable e) {
            log("登记本地发送兜底事件失败: " + e.getMessage());
        }
    }

    public static boolean cancelPendingLocalSend(String talker, String content) {
        String key = pendingLocalSendKey(talker, content);
        if (TextUtils.isEmpty(key)) return false;
        CopyOnWriteArrayList<PendingLocalSend> list = PENDING_LOCAL_SENDS.get(key);
        if (list == null || list.isEmpty()) return false;
        PendingLocalSend pending = list.remove(0);
        ScheduledFuture<?> future = pending.future;
        if (future != null) future.cancel(false);
        if (list.isEmpty()) PENDING_LOCAL_SENDS.remove(key, list);
        return true;
    }

    private static void scheduleLocalSendFallback(String talker,
                                                  String content,
                                                  EventBus eventBus,
                                                  Events.MessageReceived event) {
        String key = pendingLocalSendKey(talker, content);
        if (TextUtils.isEmpty(key)) return;
        PendingLocalSend pending = new PendingLocalSend(eventBus, event);
        PENDING_LOCAL_SENDS.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(pending);
        pending.future = LOCAL_SEND_FALLBACK_EXECUTOR.schedule(() -> {
            if (!removePendingLocalSend(key, pending)) return;
            try {
                eventBus.post(event);
            } catch (Throwable ignored) {
            }
        }, LOCAL_SEND_FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean removePendingLocalSend(String key, PendingLocalSend pending) {
        CopyOnWriteArrayList<PendingLocalSend> list = PENDING_LOCAL_SENDS.get(key);
        if (list == null) return false;
        boolean removed = list.remove(pending);
        if (list.isEmpty()) PENDING_LOCAL_SENDS.remove(key, list);
        return removed;
    }

    private static String pendingLocalSendKey(String talker, String content) {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(content)) return "";
        return talker.trim() + '\n' + content.trim();
    }

    private String pairToString(Object result) {
        if (!(result instanceof Pair)) return String.valueOf(result);
        Pair<?, ?> pair = (Pair<?, ?>) result;
        return "first=" + pair.first + ", second=" + pair.second;
    }

    private boolean isAppMsgXml(String xml) {
        if (xml == null) return false;
        String lower = xml.toLowerCase();
        return lower.contains("<appmsg") && lower.contains("</appmsg>");
    }

    private boolean looksLikeXml(String xml) {
        if (xml == null) return false;
        String text = xml.trim();
        return text.startsWith("<") && text.endsWith(">") && text.indexOf('>') > 1;
    }

    private String normalizeXmlContent(String xml) {
        if (xml == null) return "";
        String text = xml.trim();
        if (text.length() == 0) return "";
        String lower = text.toLowerCase();
        if (lower.startsWith("<appmsg") && lower.contains("</appmsg>")) {
            return "<msg>" + text + "</msg>";
        }
        return text;
    }

    private String buildQuoteXml(String talker, WeChatMessage source, String titleContent) {
        String title = safeTitle(titleContent, source);
        String quoteTalker = safeQuoteTalker(talker, source);
        String quoteSender = safeQuoteSender(source);
        String currentSender = safeCurrentSender(talker);
        String quoteDisplayName = safeQuoteDisplayName(source);
        String quoteMsgSource = safeQuoteMsgSource(source);
        String quoteContent = safeQuoteContent(source);
        long quoteSvrId = safeQuoteSvrId(source);
        long createTimeSeconds = safeQuoteCreateTimeSeconds(source);
        int quoteType = safeQuoteType(source);
        String escapedMsgSource = escapeXml(quoteMsgSource);
        String escapedQuoteContent = escapeXml(quoteContent);
        return "<?xml version=\"1.0\"?>"
                + "<msg>"
                + "<appmsg appid=\"\" sdkver=\"0\">"
                + "<title>" + escapeXml(title) + "</title>"
                + "<type>57</type>"
                + "<appattach><cdnthumbaeskey /><aeskey /></appattach>"
                + "<refermsg>"
                + "<type>" + quoteType + "</type>"
                + "<svrid>" + quoteSvrId + "</svrid>"
                + "<fromusr>" + escapeXml(quoteTalker) + "</fromusr>"
                + "<chatusr>" + escapeXml(quoteSender) + "</chatusr>"
                + "<displayname>" + escapeXml(quoteDisplayName) + "</displayname>"
                + "<msgsource>" + escapedMsgSource + "</msgsource>"
                + "<content>" + escapedQuoteContent + "</content>"
                + "<createtime>" + createTimeSeconds + "</createtime>"
                + "</refermsg>"
                + "</appmsg>"
                + "<fromusername>" + escapeXml(currentSender) + "</fromusername>"
                + "<scene>0</scene>"
                + "<appinfo><version>1</version><appname></appname></appinfo>"
                + "<commenturl></commenturl>"
                + "</msg>";
    }

    private String safeTitle(String titleContent, WeChatMessage source) {
        if (!TextUtils.isEmpty(titleContent)) {
            return titleContent;
        }
        if (source == null) return "";
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && !TextUtils.isEmpty(quote.getTitle())) {
                return quote.getTitle();
            }
        }
        return source.bodyContent();
    }

    private String safeQuoteTalker(String talker, WeChatMessage source) {
        if (source != null && !TextUtils.isEmpty(source.getTalker())) {
            return source.getTalker();
        }
        return talker != null ? talker : "";
    }

    private String safeQuoteSender(WeChatMessage source) {
        if (source == null) return "";
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && !TextUtils.isEmpty(quote.getSendTalker())) {
                return quote.getSendTalker();
            }
        }
        return source.getSendTalker();
    }

    private String safeCurrentSender(String talker) {
        if (accountApi != null && !TextUtils.isEmpty(accountApi.selfWxId())) {
            return accountApi.selfWxId();
        }
        if (contactApi != null && talker != null && contactApi.isGroup(talker)) {
            return "";
        }
        if (!TextUtils.isEmpty(talker)) {
            return talker;
        }
        if (messageStoreApi != null) {
            WeChatMessage latest = messageStoreApi.getLatestMessage(talker);
            if (latest != null && !TextUtils.isEmpty(latest.selfWxId)) {
                return latest.selfWxId;
            }
        }
        return "";
    }

    private String safeQuoteDisplayName(WeChatMessage source) {
        if (source == null) return "";
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && !TextUtils.isEmpty(quote.getDisplayName())) {
                return quote.getDisplayName();
            }
        }
        String sender = source.getSendTalker();
        if (TextUtils.isEmpty(sender)) return "";
        if (contactApi != null) {
            String value = source.isGroupChat()
                    ? contactApi.getGroupMemberDisplayName(source.getTalker(), sender)
                    : contactApi.getDisplayName(sender);
            if (!TextUtils.isEmpty(value)) return value;
        }
        return sender;
    }

    private String safeQuoteMsgSource(WeChatMessage source) {
        if (source == null) return "";
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && !TextUtils.isEmpty(quote.getMsgSource())) {
                return quote.getMsgSource();
            }
        }
        return source.getMsgSource();
    }

    private String safeQuoteContent(WeChatMessage source) {
        if (source == null) return "";
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && !TextUtils.isEmpty(quote.getContent())) {
                return quote.getContent();
            }
        }
        return source.bodyContent();
    }

    private long safeQuoteSvrId(WeChatMessage source) {
        if (source == null) return 0L;
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && quote.getSvrId() > 0L) {
                return quote.getSvrId();
            }
        }
        return source.msgSvrId;
    }

    private long safeQuoteCreateTimeSeconds(WeChatMessage source) {
        if (source == null) return 0L;
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && quote.getCreateTime() > 0L) {
                return quote.getCreateTime();
            }
        }
        long value = source.getCreateTime();
        return value > 1000000000000L ? (value / 1000L) : value;
    }

    private int safeQuoteType(WeChatMessage source) {
        if (source == null) return 0;
        if (source.isQuote()) {
            WeChatQuoteMsg quote = source.getQuoteMsg();
            if (quote != null && quote.getType() > 0) {
                return quote.getType();
            }
        }
        return source.getType();
    }

    private String buildLocationXml(String poiName,
                                    String label,
                                    String longitude,
                                    String latitude,
                                    String scale) {
        String safeScale = TextUtils.isEmpty(scale) ? "16" : scale.trim();
        return "<msg><location x=\"" + escapeXml(longitude)
                + "\" y=\"" + escapeXml(latitude)
                + "\" scale=\"" + escapeXml(safeScale)
                + "\" label=\"" + escapeXml(label)
                + "\" poiname=\"" + escapeXml(poiName)
                + "\" infourl=\"\" maptype=\"0\" poiid=\"\" isFromPoiList=\"false\""
                + " poiCategoryTips=\"\" poiBusinessHour=\"\" poiPhone=\"\""
                + " poiPriceTips=\"0.0\" buildingId=\"\" floorName=\"\" /></msg>";
    }

    private String escapeXml(String value) {
        if (value == null) return "";
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private String extractAppId(String xml) {
        String appId = firstMatch(APPMSG_APPID_ATTR, xml);
        if (!TextUtils.isEmpty(appId)) return appId;
        return firstMatch(WEAPP_APPID_TAG, xml);
    }

    private String extractSource(String xml) {
        return firstMatch(SOURCE_NAME_TAG, xml);
    }

    private String extractMsgSource(String xml) {
        if (TextUtils.isEmpty(xml)) return "";
        try {
            Matcher matcher = MSGSOURCE_TAG.matcher(xml);
            while (matcher.find()) {
                int start = matcher.start();
                if (isInsideReferMsg(xml, start)) continue;
                return stripCdata(matcher.group(0).trim());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private boolean isInsideReferMsg(String xml, int index) {
        if (TextUtils.isEmpty(xml) || index <= 0) return false;
        String prefix = xml.substring(0, Math.min(index, xml.length())).toLowerCase();
        int open = prefix.lastIndexOf("<refermsg");
        if (open < 0) return false;
        int close = prefix.lastIndexOf("</refermsg>");
        return close < open;
    }

    private byte[] cachedThumbData(String xml) {
        String[] urls = new String[]{
                firstMatch(TP_THUMB_URL_TAG, xml),
                firstMatch(WEAPP_ICON_URL_TAG, xml),
                firstMatch(THUMB_URL_TAG, xml),
                firstMatch(CDN_THUMB_URL_TAG, xml)
        };
        for (String raw : urls) {
            String url = normalizeXmlUrl(raw);
            if (TextUtils.isEmpty(url)) continue;
            byte[] cached = THUMB_DATA_CACHE.get(url);
            if (cached != null && cached.length > 0) return cached;
            prefetchThumbData(url);
        }
        return null;
    }

    private void prefetchThumbData(String url) {
        if (!THUMB_PREFETCHING.add(url)) return;
        try {
            THUMB_PREFETCH_EXECUTOR.execute(() -> {
                try {
                    byte[] data = downloadBytes(url);
                    if (data == null || data.length == 0) return;
                    synchronized (THUMB_DATA_CACHE) {
                        if (!THUMB_DATA_CACHE.containsKey(url)) {
                            while (THUMB_DATA_CACHE.size() >= MAX_CACHED_THUMBS) {
                                java.util.Iterator<String> keys = THUMB_DATA_CACHE.keySet().iterator();
                                if (!keys.hasNext()) break;
                                THUMB_DATA_CACHE.remove(keys.next());
                            }
                        }
                        THUMB_DATA_CACHE.put(url, data);
                    }
                } finally {
                    THUMB_PREFETCHING.remove(url);
                }
            });
        } catch (RejectedExecutionException ignored) {
            THUMB_PREFETCHING.remove(url);
        }
    }

    private byte[] downloadBytes(String urlText) {
        StrictMode.ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder(oldPolicy).permitNetwork().build());
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(THUMB_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(THUMB_READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "MicroMessenger Client");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) return null;
            input = connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_THUMB_BYTES) return null;
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                if (input != null) input.close();
            } catch (Throwable ignored) {
            }
            if (connection != null) connection.disconnect();
            try {
                StrictMode.setThreadPolicy(oldPolicy);
            } catch (Throwable ignored) {
            }
        }
    }

    private String normalizeXmlUrl(String value) {
        if (value == null) return "";
        return stripCdata(value)
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .trim();
    }

    private String firstMatch(Pattern pattern, String text) {
        if (pattern == null || text == null) return "";
        try {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) return "";
            String value = matcher.group(matcher.groupCount());
            return stripCdata(value != null ? value.trim() : "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String stripCdata(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.startsWith("<![CDATA[") && result.endsWith("]]>")) {
            result = result.substring(9, result.length() - 3);
        }
        return result.trim();
    }

    private HashMap<String, String> buildAtMsgSource(List<String> atWxIds) {
        HashMap<String, String> map = new HashMap<>(1);
        map.put(MSGSOURCE_AT_LIST, "<![CDATA[" + join(atWxIds) + "]]>");
        return map;
    }

    private List<String> cleanAtList(List<String> atWxIds) {
        List<String> result = new ArrayList<>();
        if (atWxIds == null) return result;
        for (String wxId : atWxIds) {
            if (TextUtils.isEmpty(wxId) || result.contains(wxId)) continue;
            result.add(wxId);
        }
        return result;
    }

    private String buildAtContent(String talker, String wxId, String text) {
        String displayName = displayNameForAt(talker, wxId);
        String safeText = text != null ? text : "";
        return "@" + displayName + AT_SEPARATOR + safeText;
    }

    private String displayNameForAt(String talker, String wxId) {
        if ("notify@all".equals(wxId)) return "所有人";
        String name = "";
        if (contactApi != null) {
            name = contactApi.getGroupMemberRoomDisplayName(talker, wxId);
            if (TextUtils.isEmpty(name) || wxId.equals(name)) {
                WeChatContact contact = contactApi.getContact(wxId);
                if (contact != null) {
                    if (!TextUtils.isEmpty(contact.nickname)) {
                        name = contact.nickname;
                    } else if (!TextUtils.isEmpty(contact.customWxId)) {
                        name = contact.customWxId;
                    }
                }
            }
        }
        if (TextUtils.isEmpty(name)) name = wxId;
        return name.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append(',');
            sb.append(value);
        }
        return sb.toString();
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatMessageApi] " + message);
    }
}
