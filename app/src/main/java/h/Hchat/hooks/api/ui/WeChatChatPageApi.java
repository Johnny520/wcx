package h.Hchat.hooks.api.ui;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;

import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.contact.WeChatContactApi;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 当前聊天页 API。
 */
public final class WeChatChatPageApi {
    public interface Logger {
        void log(String message);
    }

    public interface Listener {
        void onChatPageChanged(ChatPageEvent event);
    }

    public static final class ChatPageEvent {
        public static final String ENTER = "enter";
        public static final String EXIT = "exit";

        public final String event;
        public final String talker;
        public final String title;
        public final boolean group;
        public final String activityName;

        private ChatPageEvent(String event,
                              String talker,
                              String title,
                              boolean group,
                              String activityName) {
            this.event = event;
            this.talker = safe(talker);
            this.title = safe(title);
            this.group = group;
            this.activityName = safe(activityName);
        }

        public boolean isEnter() {
            return ENTER.equals(event);
        }

        public boolean isExit() {
            return EXIT.equals(event);
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

    private static final String CHATTING_UI = "com.tencent.mm.ui.chatting.ChattingUI";
    private static final String CHAT_USER = "Chat_User";

    private final DexFinder dexFinder;
    private final WeChatCurrentActivityApi currentActivityApi;
    private final WeChatLifecycleApi lifecycleApi;
    private final WeChatActivityStartApi activityStartApi;
    private final WeChatContactApi contactApi;
    private final Logger logger;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean installed;
    private volatile int hookedDexMethodCount;
    private volatile String currentTalker = "";
    private volatile String pendingTalker = "";
    private volatile long lastEnterAt;

    public WeChatChatPageApi(DexFinder dexFinder,
                             WeChatCurrentActivityApi currentActivityApi,
                             WeChatLifecycleApi lifecycleApi,
                             WeChatActivityStartApi activityStartApi,
                             WeChatContactApi contactApi,
                             Logger logger) {
        this.dexFinder = dexFinder;
        this.currentActivityApi = currentActivityApi;
        this.lifecycleApi = lifecycleApi;
        this.activityStartApi = activityStartApi;
        this.contactApi = contactApi;
        this.logger = logger;
    }

    public boolean isAvailable() {
        return installed;
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
        hookedDexMethodCount = installDexHooks();
        if (activityStartApi != null) {
            activityStartApi.subscribe(this::onStartActivity);
        }
        if (lifecycleApi != null) {
            lifecycleApi.subscribe(this::onActivityEvent);
        }
        installed = true;
        log("聊天页监听已安装: dexMethods=" + hookedDexMethodCount);
    }

    public boolean isInChatPage() {
        Activity activity = currentActivityApi != null ? currentActivityApi.currentActivity() : null;
        return isChattingActivity(activity) || !TextUtils.isEmpty(currentTalker);
    }

    public String currentTalker() {
        Activity activity = currentActivityApi != null ? currentActivityApi.currentActivity() : null;
        if (isChattingActivity(activity)) {
            String talker = talkerFromActivity(activity);
            if (!TextUtils.isEmpty(talker)) {
                currentTalker = talker;
                return talker;
            }
        }
        return currentTalker;
    }

    public boolean isCurrentGroup() {
        return contactApi != null && contactApi.isGroup(currentTalker());
    }

    public String currentTitle() {
        String talker = currentTalker();
        if (TextUtils.isEmpty(talker)) return "";
        String title = contactApi != null ? contactApi.getDisplayName(talker) : "";
        return !TextUtils.isEmpty(title) ? title : talker;
    }

    private void onStartActivity(Intent intent, Object caller, Method method) {
        if (intent == null) return;
        String className = intent.getComponent() != null ? intent.getComponent().getClassName() : "";
        String talker = talkerFromIntent(intent);
        if (TextUtils.isEmpty(talker)) return;
        pendingTalker = talker;
        if (isChatIntent(className, intent)) {
            dispatchEnter(talker, !TextUtils.isEmpty(className) ? className : "intent:" + method.getName());
        }
    }

    private void onActivityEvent(WeChatLifecycleApi.ActivityEvent event) {
        if (event == null) return;
        if (event.isResume() && CHATTING_UI.equals(event.activityName)) {
            String talker = talkerFromActivity(event.activity);
            if (TextUtils.isEmpty(talker)) talker = pendingTalker;
            if (!TextUtils.isEmpty(talker)) {
                dispatchEnter(talker, event.activityName);
            }
            return;
        }
        if ((event.isPause() || event.isDestroy()) && CHATTING_UI.equals(event.activityName)) {
            String old = currentTalker;
            currentTalker = "";
            dispatch(ChatPageEvent.EXIT, old, event.activityName);
        }
    }

    private boolean isChattingActivity(Activity activity) {
        if (activity == null) return false;
        String name = activity.getClass().getName();
        return CHATTING_UI.equals(name) || name.contains(".ui.chatting.");
    }

    private boolean isChatIntent(String className, Intent intent) {
        if (!TextUtils.isEmpty(className)
                && (CHATTING_UI.equals(className) || className.contains(".ui.chatting."))) {
            return true;
        }
        return intent != null && !TextUtils.isEmpty(talkerFromIntent(intent));
    }

    private void dispatchEnter(String talker, String activityName) {
        if (TextUtils.isEmpty(talker)) return;
        long now = System.currentTimeMillis();
        if (talker.equals(currentTalker) && now - lastEnterAt < 500L) return;
        currentTalker = talker;
        lastEnterAt = now;
        dispatch(ChatPageEvent.ENTER, talker, activityName);
    }

    private int installDexHooks() {
        int count = 0;
        if (dexFinder == null) return 0;
        try {
            dexFinder.resolveChatPageApi();
        } catch (Throwable e) {
            log("聊天页Dex解析失败: " + e.getMessage());
        }
        count += hookStartChattingMethod(dexFinder.chatPageStartMethod);
        count += hookFragmentEnterMethod(dexFinder.chatPageFragmentEnterMethod);
        count += hookFragmentExitMethod(dexFinder.chatPageFragmentExitMethod);
        return count;
    }

    private int hookStartChattingMethod(Method method) {
        if (method == null) return 0;
        try {
            KavaReflector.accessible(method);
            HookRegistry.get().hook(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String talker = firstStringArg(param.args);
                    if (TextUtils.isEmpty(talker)) return;
                    pendingTalker = talker;
                    dispatchEnter(talker, sourceOf(method));
                }
            });
            log("聊天页启动方法Hook: " + sourceOf(method));
            return 1;
        } catch (Throwable e) {
            log("聊天页启动方法Hook失败: " + sourceOf(method) + " " + e.getMessage());
            return 0;
        }
    }

    private int hookFragmentEnterMethod(Method method) {
        if (method == null) return 0;
        try {
            KavaReflector.accessible(method);
            HookRegistry.get().hook(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String talker = callStringMethod(param.thisObject, "getStringExtra", CHAT_USER);
                    if (TextUtils.isEmpty(talker)) talker = pendingTalker;
                    if (!TextUtils.isEmpty(talker)) {
                        dispatchEnter(talker, sourceOf(method));
                    }
                }
            });
            log("聊天页Fragment进入方法Hook: " + sourceOf(method));
            return 1;
        } catch (Throwable e) {
            log("聊天页Fragment进入方法Hook失败: " + sourceOf(method) + " " + e.getMessage());
            return 0;
        }
    }

    private int hookFragmentExitMethod(Method method) {
        if (method == null) return 0;
        try {
            KavaReflector.accessible(method);
            HookRegistry.get().hook(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String old = currentTalker;
                    currentTalker = "";
                    pendingTalker = "";
                    if (!TextUtils.isEmpty(old)) {
                        dispatch(ChatPageEvent.EXIT, old, sourceOf(method));
                    }
                }
            });
            log("聊天页Fragment退出方法Hook: " + sourceOf(method));
            return 1;
        } catch (Throwable e) {
            log("聊天页Fragment退出方法Hook失败: " + sourceOf(method) + " " + e.getMessage());
            return 0;
        }
    }

    private String talkerFromActivity(Activity activity) {
        if (activity == null) return "";
        try {
            return talkerFromIntent(activity.getIntent());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String talkerFromIntent(Intent intent) {
        if (intent == null) return "";
        try {
            String talker = intent.getStringExtra(CHAT_USER);
            return talker != null ? talker.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String firstStringArg(Object[] args) {
        if (args == null) return "";
        for (Object arg : args) {
            if (arg instanceof String) {
                return ((String) arg).trim();
            }
        }
        return "";
    }

    private String callStringMethod(Object receiver, String name, String arg) {
        if (receiver == null) return "";
        Method method = findStringMethod(receiver.getClass(), name);
        if (method == null) return "";
        try {
            Object value = KavaReflector.invoke(method, receiver, arg);
            return value instanceof String ? ((String) value).trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private Method findStringMethod(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Method method = KavaReflector.findDeclaredMethod(cur, name, String.class);
                if (method.getReturnType() == String.class) {
                    return method;
                }
            } catch (Throwable ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private String sourceOf(Method method) {
        if (method == null) return "";
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private void dispatch(String event, String talker, String activityName) {
        ChatPageEvent pageEvent = new ChatPageEvent(
                event,
                talker,
                titleOf(talker),
                contactApi != null && contactApi.isGroup(talker),
                activityName);
        for (Listener listener : listeners) {
            try {
                listener.onChatPageChanged(pageEvent);
            } catch (Throwable e) {
                log("聊天页回调失败: " + e.getMessage());
            }
        }
    }

    private String titleOf(String talker) {
        if (TextUtils.isEmpty(talker)) return "";
        String title = contactApi != null ? contactApi.getDisplayName(talker) : "";
        return !TextUtils.isEmpty(title) ? title : talker;
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private void log(String message) {
        if (logger != null) logger.log("[WeChatChatPageApi] " + message);
    }
}
