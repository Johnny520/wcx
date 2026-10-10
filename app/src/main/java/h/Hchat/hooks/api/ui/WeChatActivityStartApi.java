package h.Hchat.hooks.api.ui;

import android.app.Activity;
import android.content.ContextWrapper;
import android.content.Intent;

import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 微信 Activity 启动监听 API。
 */
public final class WeChatActivityStartApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onStartActivity(Intent intent, Object caller, Method method);
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

    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean installed;
    private volatile int hookedMethodCount;

    public WeChatActivityStartApi(Logger logger) {
        this.logger = logger;
    }

    public boolean isAvailable() {
        return installed;
    }

    public int hookedMethodCount() {
        return hookedMethodCount;
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
        if (installed) return;
        int count = hookStartMethods(Activity.class);
        count += hookStartMethods(ContextWrapper.class);
        hookedMethodCount = count;
        installed = count > 0;
        log("Activity启动监听Hook: methods=" + count);
    }

    private int hookStartMethods(Class<?> clazz) {
        int count = 0;
        for (Method method : KavaReflector.declaredMethods(clazz)) {
            if (!isStartMethod(method)) continue;
            HookRegistry.get().hook(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = findIntent(param.args);
                    if (intent != null) dispatch(intent, param.thisObject, method);
                }
            });
            count++;
        }
        return count;
    }

    private boolean isStartMethod(Method method) {
        if (method == null) return false;
        String name = method.getName();
        if (!"startActivity".equals(name) && !"startActivityForResult".equals(name)) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params == null || params.length == 0) return false;
        for (Class<?> param : params) {
            if (param == Intent.class) return true;
        }
        return false;
    }

    private Intent findIntent(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg instanceof Intent) return (Intent) arg;
        }
        return null;
    }

    private void dispatch(Intent intent, Object caller, Method method) {
        for (Listener listener : listeners) {
            try {
                listener.onStartActivity(intent, caller, method);
            } catch (Throwable e) {
                log("Activity启动监听回调失败: " + e.getMessage());
            }
        }
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatActivityStartApi] " + message);
    }
}
