package h.Hchat.hooks.items.payment.reply;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.net.WeChatNetworkDispatcher;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.detect.RedPacketReflector;
import h.Hchat.utils.KavaReflector;

/**
 * 抢到红包后的祝福语网络请求。
 */
public final class RedPacketWishSender {
    public interface Logger {
        void log(String message);
    }

    private final DexFinder dexFinder;
    private final RedPacketSettings settings;
    private final WeChatNetworkDispatcher networkDispatcher;
    private final Logger logger;

    public RedPacketWishSender(DexFinder dexFinder,
                               RedPacketSettings settings,
                               WeChatNetworkDispatcher networkDispatcher,
                               Logger logger) {
        this.dexFinder = dexFinder;
        this.settings = settings;
        this.networkDispatcher = networkDispatcher;
        this.logger = logger;
    }

    public void sendFromOpenResult(String fallbackSendId, Object jsonObj) {
        String sendId = readJson(jsonObj, "sendId");
        if (TextUtils.isEmpty(sendId)) sendId = readJson(jsonObj, "sendid");
        if (TextUtils.isEmpty(sendId)) sendId = fallbackSendId;

        String receiveId = readJson(jsonObj, "receiveId");
        if (TextUtils.isEmpty(receiveId)) receiveId = readJson(jsonObj, "receiveid");
        if (TextUtils.isEmpty(receiveId)) receiveId = readJson(jsonObj, "receive_id");

        String ver = readJson(jsonObj, "ver");
        if (TextUtils.isEmpty(ver)) ver = readJson(jsonObj, "hbVer");
        send(sendId, receiveId, ver);
    }

    private void send(String sendId, String receiveId, String ver) {
        if (!settings.getBoolean(RedPacketSettings.KEY_WISH_ENABLE, false)) return;
        if (TextUtils.isEmpty(sendId)) return;

        String wishText = chooseWishText();
        if (TextUtils.isEmpty(wishText)) return;

        try {
            if (dexFinder.wishWxHbClass != null && dexFinder.wishWxHbCtor != null) {
                Object request = KavaReflector.newInstance(dexFinder.wishWxHbCtor,
                        sendId,
                        wishText,
                        TextUtils.isEmpty(receiveId) ? "" : receiveId,
                        TextUtils.isEmpty(ver) ? "v1.0" : ver);
                if (networkDispatcher.send(request)) {
                    log("自动祝福已发送: " + wishText);
                } else {
                    log("自动祝福发送失败");
                }
            } else {
                log("祝福功能不可用: class=" + (dexFinder.wishWxHbClass != null)
                        + " ctor=" + (dexFinder.wishWxHbCtor != null)
                        + " dispatcher=" + networkDispatcher.isReady());
            }
        } catch (Throwable e) {
            log("ERROR sendWish: " + e.getMessage());
        }
    }

    private String chooseWishText() {
        String wishText = settings.getString(RedPacketSettings.KEY_WISH_TEXT, "谢谢老板");
        if (!settings.getBoolean(RedPacketSettings.KEY_WISH_RANDOM, false)) return wishText;
        String templates = settings.getString(RedPacketSettings.KEY_WISH_TEMPLATES, "");
        if (TextUtils.isEmpty(templates)) return wishText;
        String[] parts = templates.split("\\|");
        if (parts.length == 0) return wishText;
        String picked = parts[(int) (Math.random() * parts.length)].trim();
        return TextUtils.isEmpty(picked) ? wishText : picked;
    }

    private String readJson(Object jsonObj, String key) {
        return jsonObj != null ? RedPacketReflector.readJsonString(jsonObj, key) : null;
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
