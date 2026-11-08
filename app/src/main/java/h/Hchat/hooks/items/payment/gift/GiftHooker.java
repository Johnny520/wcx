package h.Hchat.hooks.items.payment.gift;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.utils.HLog;

public class GiftHooker {
    private static final String TAG = "HchatGift";

    private final Context hostContext;
    private final ClassLoader classLoader;
    private final DexFinder dexFinder;
    private final GiftSettings settings;
    private final GiftSilentHandler.Logger logger;
    private final GiftSilentHandler handler;
    private final GiftMessageHook messageHook;

    public GiftHooker(Context hostContext, ClassLoader classLoader, DexFinder dexFinder, GiftSettings settings) {
        this.hostContext = hostContext;
        this.classLoader = classLoader;
        this.dexFinder = dexFinder;
        this.settings = settings;
        this.logger = new GiftSilentHandler.Logger() {
            @Override
            public void log(String message) {
                logx(message);
            }
        };
        this.handler = new GiftSilentHandler(dexFinder, classLoader, logger, new GiftSilentHandler.ResultCallback() {
            @Override
            public void onResult(String orderId, boolean success, String message) {
                settings.recordResult(orderId, success);
                settings.putString(GiftSettings.KEY_LAST_RESULT, success ? "成功" : "失败");
            }
        });
        this.messageHook = new GiftMessageHook(dexFinder, settings, handler, logger);
    }

    public List<Object> hookAll() {
        List<Object> subscriptions = new ArrayList<>();
        logx("hookAll 开始, 礼物任务类=" + name(dexFinder.ecsGiftTaskClass)
                + " 礼物服务类=" + name(dexFinder.ecsGiftServiceClass)
                + " 礼物消息类=" + name(dexFinder.ecsGiftMsgClass));
        subscriptions.addAll(messageHook.hook());
        logx("hookAll 完成, 订阅数=" + subscriptions.size());
        return subscriptions;
    }

    public boolean isReady() {
        return handler.isReady();
    }

    public String describe() {
        return handler.describe();
    }

    private static String name(Class<?> clazz) {
        return clazz == null ? "null" : clazz.getName();
    }

    private void logx(String message) {
        if (message == null) return;
        boolean important = message.contains("失败")
                || message.contains("未找到")
                || message.contains("未就绪")
                || message.contains("异常");
        if (important) {
            HLog.e(TAG + " " + message);
        } else if (settings != null && settings.getBoolean(GiftSettings.KEY_LOG_ENABLE, false)) {
            XposedBridge.log(TAG + " " + message);
        }
    }
}
