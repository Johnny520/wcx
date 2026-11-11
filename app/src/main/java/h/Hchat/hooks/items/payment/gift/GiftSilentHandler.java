package h.Hchat.hooks.items.payment.gift;

import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.media.WeChatInternalServices;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class GiftSilentHandler {
    public interface Logger {
        void log(String message);
    }

    public interface ResultCallback {
        void onResult(String orderId, boolean success, String message);
    }

    private final DexFinder dexFinder;
    private final ClassLoader classLoader;
    private final Logger logger;
    private final ResultCallback callback;
    private Object serviceInstance;
    private Constructor<?> taskCtor;
    private Method taskRunMethod;

    public GiftSilentHandler(DexFinder dexFinder, ClassLoader classLoader, Logger logger, ResultCallback callback) {
        this.dexFinder = dexFinder;
        this.classLoader = classLoader;
        this.logger = logger;
        this.callback = callback;
    }

    public boolean isReady() {
        return dexFinder != null
                && dexFinder.ecsGiftTaskClass != null
                && dexFinder.ecsGiftServiceClass != null
                && dexFinder.ecsGiftMsgClass != null
                && dexFinder.serviceGetterMethod != null;
    }

    public boolean grab(Object addMsg, String orderId) {
        if (!isReady()) {
            log("礼物静默链路未就绪，跳过");
            return false;
        }

        Object msgInfo = findMsgInfo(addMsg);
        if (msgInfo == null) {
            log("未取到礼物消息对象，跳过");
            return false;
        }

        Object service = getService();
        if (service == null) {
            log("未取到礼物服务实例，跳过");
            return false;
        }

        try {
            if (taskCtor == null) {
                taskCtor = KavaReflector.findConstructor(
                        dexFinder.ecsGiftTaskClass,
                        dexFinder.ecsGiftServiceClass,
                        dexFinder.ecsGiftMsgClass);
            }
            if (taskCtor == null) {
                log("未找到礼物任务构造方法");
                return false;
            }
            if (taskRunMethod == null) {
                taskRunMethod = KavaReflector.findMethod(dexFinder.ecsGiftTaskClass, "run");
            }
            if (taskRunMethod == null) {
                log("未找到礼物任务入口");
                return false;
            }

            Object task = KavaReflector.newInstance(taskCtor, service, msgInfo);
            if (task == null) {
                log("礼物任务创建失败");
                return false;
            }

            KavaReflector.invoke(taskRunMethod, task);
            log("已发起礼物静默领取, orderId=" + orderId);
            if (callback != null) callback.onResult(orderId, true, "");
            return true;
        } catch (Throwable e) {
            log("礼物静默领取异常: " + e);
            if (callback != null) callback.onResult(orderId, false, String.valueOf(e));
            return false;
        }
    }

    private Object getService() {
        if (serviceInstance != null) return serviceInstance;
        Object value = WeChatInternalServices.getService(dexFinder, dexFinder.ecsGiftServiceClass);
        if (value == null) {
            value = KavaReflector.invoke(dexFinder.serviceGetterMethod, null, dexFinder.ecsGiftServiceClass);
        }
        serviceInstance = value;
        return value;
    }

    private Object findMsgInfo(Object addMsg) {
        if (addMsg == null) return null;
        Class<?> target = dexFinder.ecsGiftMsgClass;
        if (target.isInstance(addMsg)) return addMsg;

        for (Class<?> clazz = addMsg.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : KavaReflector.declaredFields(clazz)) {
                if (target.isAssignableFrom(field.getType())) {
                    Object value = KavaReflector.readField(field, addMsg);
                    if (value != null) return value;
                }
            }
        }

        for (Class<?> clazz = addMsg.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                if (method.getParameterTypes().length != 0) continue;
                if (!target.isAssignableFrom(method.getReturnType())) continue;
                Object value = KavaReflector.invoke(method, addMsg);
                if (value != null) return value;
            }
        }

        for (Class<?> clazz = addMsg.getClass(); clazz != null && clazz != Object.class; clazz = clazz.getSuperclass()) {
            for (Field field : KavaReflector.declaredFields(clazz)) {
                Object value = KavaReflector.readField(field, addMsg);
                if (value != null && target.isInstance(value)) return value;
            }
        }

        return null;
    }

    public String describe() {
        if (!isReady()) return "未就绪";
        return dexFinder.ecsGiftTaskClass.getName() + "/" + dexFinder.ecsGiftServiceClass.getName();
    }

    private void log(String message) {
        if (TextUtils.isEmpty(message)) return;
        if (logger != null) logger.log(message);
    }
}
