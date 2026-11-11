package h.Hchat.hooks.api.message;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.event.EventBus;
import h.Hchat.event.EventHandler;
import h.Hchat.event.Events;
import h.Hchat.hooks.api.contact.WeChatAccountApi;
import h.Hchat.hooks.api.model.WeChatParsedMessage;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 微信消息事件 API。
 */
public final class WeChatMessageEventApi {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final WeChatAccountApi accountApi;
    private final WeChatMessageParseApi parseApi;
    private final EventBus eventBus;
    private final Logger logger;
    private volatile boolean hooked;
    private volatile boolean patHooked;
    private final ConcurrentHashMap<String, Long> recentPatEvents = new ConcurrentHashMap<>();

    public WeChatMessageEventApi(DexFinder dexFinder,
                                 WeChatAccountApi accountApi,
                                 WeChatMessageParseApi parseApi,
                                 EventBus eventBus,
                                 Logger logger) {
        this.dexFinder = dexFinder;
        this.accountApi = accountApi;
        this.parseApi = parseApi;
        this.eventBus = eventBus;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return dexFinder != null && dexFinder.addMsgClasses != null
                && !dexFinder.addMsgClasses.isEmpty()
                && parseApi != null && eventBus != null;
    }

    public void installAddMsgHook() {
        if (!isAvailable()) return;
        if (hooked) {
            installPatMsgHook();
            return;
        }
        int count = 0;
        for (Class<?> clazz : dexFinder.addMsgClasses) {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes == null || parameterTypes.length == 0) continue;
                final List<Integer> addMsgArgIndexes = new ArrayList<>();
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (parseApi.isLikelyAddMsgClass(parameterTypes[i])) addMsgArgIndexes.add(i);
                }
                if (addMsgArgIndexes.isEmpty()) continue;
                HookRegistry.get().hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) return;
                        for (int index : addMsgArgIndexes) {
                            if (index >= 0 && index < param.args.length && param.args[index] != null) {
                                dispatchAddMsg(param.args[index]);
                            }
                        }
                    }
                });
                count++;
            }
        }
        hooked = count > 0;
        log("AddMsg事件Hook: " + count);
        installPatMsgHook();
    }

    public EventBus.Subscription subscribeMessage(EventHandler<Events.MessageReceived> handler) {
        return eventBus != null ? eventBus.subscribe(Events.MessageReceived.class, handler)
                : null;
    }

    public EventBus.Subscription subscribeRedPacket(EventHandler<Events.RedPacketDetected> handler) {
        return eventBus != null ? eventBus.subscribe(Events.RedPacketDetected.class, handler)
                : null;
    }

    public EventBus.Subscription subscribePat(EventHandler<Events.PatDetected> handler) {
        return eventBus != null ? eventBus.subscribe(Events.PatDetected.class, handler)
                : null;
    }

    public void unsubscribe(EventBus.Subscription subscription) {
        if (eventBus != null) eventBus.unsubscribe(subscription);
    }

    private void dispatchAddMsg(Object addMsg) {
        try {
            String selfWxId = accountApi != null ? accountApi.selfWxId() : "";
            WeChatParsedMessage parsed = parseApi.parseAddMsg(addMsg, selfWxId);
            if (parsed == null) return;
            eventBus.post(new Events.MessageReceived(
                    parsed.xml, parsed.sender, parsed.talker, parsed.content,
                    String.valueOf(parsed.type), parsed.createTimeSeconds, parsed.msgSvrId,
                    parsed.msgSource, parsed.selfWxId));
            if (parsed.redPacketMessage) {
                eventBus.post(new Events.RedPacketDetected(
                        "WeChatMessageEventApi",
                        parsed.xml,
                        parsed.sender,
                        parsed.talker,
                        parsed.nativeUrl,
                        parsed.exclusiveRecvUser));
            }
        } catch (Throwable e) {
            log("派发消息事件失败: " + e.getMessage());
        }
    }

    private void installPatMsgHook() {
        if (patHooked || dexFinder == null || dexFinder.patDisplayTemplateMethod == null
                || eventBus == null) {
            return;
        }
        HookRegistry.get().hook(dexFinder.patDisplayTemplateMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 1) return;
                dispatchPatMsg(param.args[0], param.args.length > 1 ? param.args[1] : null);
            }
        });
        patHooked = true;
        log("拍一拍事件Hook: " + dexFinder.patDisplayTemplateMethod.getDeclaringClass().getName()
                + "#" + dexFinder.patDisplayTemplateMethod.getName());
    }

    private void dispatchPatMsg(Object patMsg, Object talkerArg) {
        try {
            if (patMsg == null) return;
            String fromUser = readFieldString(patMsg, "d");
            String pattedUser = readFieldString(patMsg, "e");
            String template = readFieldString(patMsg, "f");
            long createTime = readPatCreateTime(patMsg);
            long svrId = readPatSvrId(patMsg);
            if (TextUtils.isEmpty(fromUser) || TextUtils.isEmpty(pattedUser)) return;
            if (!isRecentPat(createTime)) return;
            String key = fromUser + ":" + pattedUser + ":" + svrId + ":" + createTime + ":" + template;
            long now = System.currentTimeMillis();
            Long previous = recentPatEvents.put(key, now);
            if (previous != null && now - previous < 300000L) return;
            cleanupPatEvents(now);
            String talker = talkerArg instanceof String ? (String) talkerArg : "";
            eventBus.post(new Events.PatDetected(
                    fromUser,
                    pattedUser,
                    template,
                    talker,
                    createTime,
                    svrId));
        } catch (Throwable e) {
            log("派发拍一拍事件失败: " + e.getMessage());
        }
    }

    private long readPatCreateTime(Object patMsg) {
        if (hasField(patMsg.getClass(), "g")) return readFieldLong(patMsg, "g");
        return readFieldLong(patMsg, "i");
    }

    private long readPatSvrId(Object patMsg) {
        if (hasField(patMsg.getClass(), "g")) return readFieldLong(patMsg, "i");
        return readFieldLong(patMsg, "n");
    }

    private boolean isRecentPat(long createTime) {
        if (createTime <= 0L) return true;
        long millis = createTime > 100000000000L ? createTime : createTime * 1000L;
        return Math.abs(System.currentTimeMillis() - millis) <= 300000L;
    }

    private String readFieldString(Object obj, String field) {
        Object value = KavaReflector.readField(obj, field);
        return value != null ? String.valueOf(value) : "";
    }

    private long readFieldLong(Object obj, String field) {
        Object value = KavaReflector.readField(obj, field);
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private boolean hasField(Class<?> clazz, String name) {
        return KavaReflector.findFieldRecursive(clazz, name) != null;
    }

    private void cleanupPatEvents(long now) {
        if (recentPatEvents.size() < 128) return;
        recentPatEvents.entrySet().removeIf(entry -> now - entry.getValue() > 300000L);
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatMessageEventApi] " + message);
    }
}
