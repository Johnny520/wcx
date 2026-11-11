package h.Hchat.hooks.items.payment.detect;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * AddMsg 消息预监听。
 * 负责从微信 AddMsg 对象中识别红包消息，并把识别结果回调给业务层。
 */
public class RedPacketMessageHook {
    public interface Logger {
        void log(String message);
    }

    public interface LoginProvider {
        String getLoginWxid();
    }

    public interface RedPacketDetectedCallback {
        void onDetected(String source, String xml, String sender, String talker,
                        String nativeUrl, String exclusiveRecvUser);
    }

    private final DexFinder dexFinder;
    private final RedPacketSettings settings;
    private final LoginProvider loginProvider;
    private final RedPacketDetectedCallback callback;
    private final Logger logger;
    private boolean hooked = false;

    public RedPacketMessageHook(
            DexFinder dexFinder,
            RedPacketSettings settings,
            LoginProvider loginProvider,
            RedPacketDetectedCallback callback,
            Logger logger
    ) {
        this.dexFinder = dexFinder;
        this.settings = settings;
        this.loginProvider = loginProvider;
        this.callback = callback;
        this.logger = logger;
    }

    public void hook() {
        if (hooked) return;
        if (dexFinder.addMsgClasses.isEmpty()) {
            log("AddMsg类未找到，跳过");
            return;
        }

        int count = 0;
        for (Class<?> clazz : dexFinder.addMsgClasses) {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (parameterTypes == null || parameterTypes.length == 0) continue;

                final List<Integer> addMsgArgIndexes = new ArrayList<>();
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (RedPacketReflector.isLikelyAddMsgClass(parameterTypes[i])) {
                        addMsgArgIndexes.add(i);
                    }
                }
                if (addMsgArgIndexes.isEmpty()) continue;

                HookRegistry.get().hook(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) return;
                        for (int index : addMsgArgIndexes) {
                            if (index >= 0 && index < param.args.length && param.args[index] != null) {
                                handleAddMsgBeforeDb(param.args[index]);
                            }
                        }
                    }
                });
                count++;
            }
        }

        hooked = count > 0;
        log("AddMsg Hook: " + count);
    }

    private void handleAddMsgBeforeDb(Object addMsg) {
        if (!settings.getBoolean(RedPacketSettings.KEY_ENABLE, false)) return;
        if (addMsg == null) return;

        try {
            String content = RedPacketReflector.findAddMsgContent(addMsg);
            if (TextUtils.isEmpty(content) || !content.contains("<wcpayinfo>")) return;

            String xml = content;
            int index = content.indexOf(":\n");
            if (index > 0 && content.indexOf("<") > index) {
                xml = content.substring(index + 2);
            }

            String nativeUrl = RedPacketParser.getXmlParamByTag(xml, "nativeurl");
            log("AddMsg wcpayinfo nativeurl="
                    + (TextUtils.isEmpty(nativeUrl) ? "EMPTY" : nativeUrl.substring(0, Math.min(50, nativeUrl.length()))));

            if (TextUtils.isEmpty(nativeUrl)) {
                String altNativeUrl = RedPacketParser.getXmlParamByTag(content, "nativeurl");
                log("  原始content中nativeurl="
                        + (TextUtils.isEmpty(altNativeUrl) ? "EMPTY" : altNativeUrl.substring(0, Math.min(50, altNativeUrl.length()))));
                if (!TextUtils.isEmpty(altNativeUrl)) {
                    nativeUrl = altNativeUrl;
                    xml = content;
                }
            }

            if (TextUtils.isEmpty(nativeUrl)) {
                log("  放弃: 无法提取nativeurl");
                return;
            }

            String from = RedPacketReflector.readObjFieldString(addMsg, "e");
            String to = RedPacketReflector.readObjFieldString(addMsg, "f");
            String my = loginProvider != null ? loginProvider.getLoginWxid() : "";

            String talker = from;
            if (RedPacketParser.isGroupTalker(to)) {
                talker = to;
            } else if (RedPacketParser.isGroupTalker(from)) {
                talker = from;
            } else if (!TextUtils.isEmpty(my) && my.equals(from) && !TextUtils.isEmpty(to)) {
                talker = to;
            }
            if (TextUtils.isEmpty(talker)) talker = to;

            String sender = RedPacketParser.getXmlParamByTag(xml, "fromusername");
            if (TextUtils.isEmpty(sender) && !TextUtils.isEmpty(content)) {
                int prefixEnd = content.indexOf(":\n");
                if (prefixEnd > 0) sender = content.substring(0, prefixEnd);
            }
            if (TextUtils.isEmpty(sender)) sender = from;
            sender = RedPacketParser.normalizeUsername(sender);
            talker = RedPacketParser.normalizeUsername(talker);
            String exclusiveRecvUser = RedPacketParser.getXmlParamByTag(xml, "exclusive_recv_username");

            log("  from=" + from + " to=" + to + " talker=" + talker + " sender=" + sender);
            if (callback != null) {
                callback.onDetected("AddMsg", xml, sender, talker, nativeUrl, exclusiveRecvUser);
            }
        } catch (Throwable e) {
            log("ERROR handleAddMsg: " + e.getMessage());
        }
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
