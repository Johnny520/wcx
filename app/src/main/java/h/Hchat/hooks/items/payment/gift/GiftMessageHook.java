package h.Hchat.hooks.items.payment.gift;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.detect.RedPacketParser;
import h.Hchat.hooks.items.payment.detect.RedPacketReflector;
import h.Hchat.utils.KavaReflector;

public class GiftMessageHook {
    private static final int MAX_DEDUP = 300;

    private final DexFinder dexFinder;
    private final GiftSettings settings;
    private final GiftSilentHandler handler;
    private final GiftSilentHandler.Logger logger;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Set<String> processedOrders = new LinkedHashSet<>();
    private final List<Object> subscriptions = new ArrayList<>();

    public GiftMessageHook(DexFinder dexFinder, GiftSettings settings, GiftSilentHandler handler,
                           GiftSilentHandler.Logger logger) {
        this.dexFinder = dexFinder;
        this.settings = settings;
        this.handler = handler;
        this.logger = logger;
    }

    public List<Object> hook() {
        if (dexFinder == null || dexFinder.addMsgClasses == null || dexFinder.addMsgClasses.isEmpty()) {
            log("未找到消息类，跳过礼物监听");
            return subscriptions;
        }

        for (Class<?> clazz : dexFinder.addMsgClasses) {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes.length == 0) continue;

                final List<Integer> indexes = new ArrayList<>();
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (RedPacketReflector.isLikelyAddMsgClass(parameterTypes[i])) indexes.add(i);
                }
                if (indexes.isEmpty()) continue;

                XC_MethodHook.Unhook unhook = HookRegistry.get().hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object[] args = param.args;
                        if (args == null) return;
                        for (Integer index : indexes) {
                            if (index == null || index >= args.length) continue;
                            try {
                                onMessage(args[index]);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                if (unhook != null) subscriptions.add(unhook);
            }
        }

        log("礼物消息监听已安装, 订阅数=" + subscriptions.size());
        return subscriptions;
    }

    private void onMessage(Object addMsg) {
        if (addMsg == null) return;
        if (!settings.isEnabled()) return;

        String content = RedPacketReflector.findAddMsgContent(addMsg);
        if (TextUtils.isEmpty(content)) return;

        String xml = content;
        int split = content.indexOf(":\n");
        if (split > 0 && content.indexOf('<') > split) {
            xml = content.substring(split + 2);
        }
        if (xml.indexOf("<orderid>") < 0) return;

        final String orderId = RedPacketParser.getXmlParamByTag(xml, "orderid");
        if (TextUtils.isEmpty(orderId)) return;
        if (!markProcessed(orderId)) return;

        final Object message = addMsg;
        long delay = settings.getDelayMillis();
        log("检测到礼物消息, orderId=" + orderId + (delay > 0 ? " 延迟" + delay + "ms" : ""));

        if (delay > 0) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    handler.grab(message, orderId);
                }
            }, delay);
        } else {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    handler.grab(message, orderId);
                }
            });
        }
    }

    private boolean markProcessed(String orderId) {
        synchronized (processedOrders) {
            if (!processedOrders.add(orderId)) return false;
            if (processedOrders.size() > MAX_DEDUP) {
                String first = processedOrders.iterator().next();
                processedOrders.remove(first);
            }
            return true;
        }
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
