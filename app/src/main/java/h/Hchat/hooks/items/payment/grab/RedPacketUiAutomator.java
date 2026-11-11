package h.Hchat.hooks.items.payment.grab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.TextView;

import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.payment.core.RedPacketSettings;
import h.Hchat.hooks.items.payment.core.RedPacketState;
import h.Hchat.hooks.items.payment.detect.RedPacketParser;
import h.Hchat.utils.KavaReflector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;

/**
 * UI 抢包模式兜底。
 * 负责在红包领取页自动点击、在详情页识别成功金额，并按配置自动关闭页面。
 */
public class RedPacketUiAutomator {
    public interface FilterCallback {
        boolean shouldSkip(String nativeUrl);
    }

    public interface SuccessCallback {
        void onSuccess(String nativeUrl, String amount, boolean selfSent);
    }

    public interface FailureCallback {
        void onFailure(String nativeUrl, String reason);
    }

    public interface Logger {
        void log(String message);
    }

    private final ClassLoader classLoader;
    private final RedPacketSettings settings;
    private final RedPacketState state;
    private final FilterCallback filterCallback;
    private final SuccessCallback successCallback;
    private final FailureCallback failureCallback;
    private final Logger logger;
    private final Map<Activity, Boolean> clickedActivities = new WeakHashMap<>();
    private final Map<View, Boolean> clickedViews = new WeakHashMap<>();
    private boolean hooked;

    public RedPacketUiAutomator(
            ClassLoader classLoader,
            RedPacketSettings settings,
            RedPacketState state,
            FilterCallback filterCallback,
            SuccessCallback successCallback,
            FailureCallback failureCallback,
            Logger logger
    ) {
        this.classLoader = classLoader;
        this.settings = settings;
        this.state = state;
        this.filterCallback = filterCallback;
        this.successCallback = successCallback;
        this.failureCallback = failureCallback;
        this.logger = logger;
    }

    public void hook() {
        if (hooked) return;
        hookReceiveActivities();
        hookDetailActivities();
        hooked = true;
    }

    public static Intent createReceiveIntent(String content, String talker, String nativeUrl, String sender) {
        Intent intent = new Intent();
        intent.putExtra("key_native_url", nativeUrl);
        intent.putExtra("key_username", talker);
        intent.putExtra("scene_id", RedPacketParser.getLuckyMoneySceneId(content, talker, nativeUrl));
        intent.putExtra("key_way", 0);
        if (!TextUtils.isEmpty(sender)) intent.putExtra("key_from_username", sender);
        return intent;
    }

    private void hookReceiveActivities() {
        String[] activityClasses = {
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI",
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI",
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyReceiveUI"
        };
        for (String className : activityClasses) {
            try {
                Class<?> clazz = KavaReflector.loadClass(className, classLoader);
                hookReceiveLifecycle(clazz, className);
            } catch (Throwable e) {
                log("查找领取页类失败: " + className + " | " + e.getMessage());
            }
        }
    }

    private void hookReceiveLifecycle(Class<?> clazz, String className) {
        if (clazz == null) return;
        if (hookDeclaredAfter(clazz, "initView", null, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handleReceivePage(param.thisObject, "initView", true);
            }
        })) {
            log("Hook领取页: " + className + ".initView");
        }

        hookDeclaredAfter(clazz, "onCreate", new Class<?>[]{Bundle.class}, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handleReceivePage(param.thisObject, "onCreate", false);
            }
        });

        hookDeclaredAfter(clazz, "onResume", null, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                handleReceivePage(param.thisObject, "onResume", true);
            }
        });

        try {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                if (!"onSceneEnd".equals(method.getName())) continue;
                if (method.getParameterTypes().length != 4) continue;
                HookRegistry.get().hook(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        handleReceivePage(param.thisObject, "onSceneEnd", true);
                    }
                });
                log("Hook领取页: " + className + ".onSceneEnd");
                break;
            }
        } catch (Throwable e) {
            log("Hook领取页onSceneEnd失败: " + className + " | " + e.getMessage());
        }

        try {
            hookDeclaredAfter(clazz, "onDestroy", null, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) {
                        clickedActivities.remove((Activity) param.thisObject);
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private void handleReceivePage(Object thisObject, String source, boolean click) {
        if (!(thisObject instanceof Activity)) return;
        Activity activity = (Activity) thisObject;
        String nativeUrl = getNativeUrl(activity);
        if (!canAutomate(nativeUrl)) return;
        log("领取页" + source + ": nativeurl=" + nativeUrl);
        if (click && !isActivityClicked(activity)) {
            tryClickButton(activity, thisObject);
        }
        startContinuousCheck(activity, nativeUrl);
    }

    private void hookDetailActivities() {
        String[] detailClasses = {
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewDetailUI",
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyDetailUI",
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyBeforeDetailUI"
        };
        for (String className : detailClasses) {
            try {
                Class<?> clazz = KavaReflector.loadClass(className, classLoader);
                hookDetailLifecycle(clazz, className);
            } catch (Throwable e) {
                log("查找详情页类失败: " + className + " | " + e.getMessage());
            }
        }
    }

    private void hookDetailLifecycle(Class<?> clazz, String className) {
        if (clazz == null) return;
        if (hookDeclaredAfter(clazz, "onCreate", new Class<?>[]{Bundle.class}, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) {
                    scheduleDetailSuccessCheck((Activity) param.thisObject);
                }
            }
        })) {
            log("Hook详情页: " + className + ".onCreate");
        }
        if (hookDeclaredAfter(clazz, "onResume", null, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Activity) {
                    scheduleDetailSuccessCheck((Activity) param.thisObject);
                }
            }
        })) {
            log("Hook详情页: " + className + ".onResume");
        }
    }

    private boolean hookDeclaredAfter(Class<?> clazz, String methodName, Class<?>[] parameterTypes, XC_MethodHook callback) {
        try {
            Method method = parameterTypes == null
                    ? KavaReflector.findDeclaredMethod(clazz, methodName)
                    : KavaReflector.findDeclaredMethod(clazz, methodName, parameterTypes);
            if (method == null) return false;
            HookRegistry.get().hook(method, callback);
            return true;
        } catch (Throwable e) {
            log("Hook红包页面方法失败: " + clazz.getName() + "." + methodName + " | " + e.getMessage());
            return false;
        }
    }

    private void startContinuousCheck(Activity activity, String nativeUrl) {
        Handler handler = new Handler(activity.getMainLooper());
        int maxChecks = settings.getInt(RedPacketSettings.KEY_CHECK_TIMES, 10);
        final int[] count = {0};
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed() || !canAutomate(nativeUrl)) return;
                boolean closed = checkAndCloseIfFailed(activity, nativeUrl);
                if (!closed && count[0]++ < maxChecks) handler.postDelayed(this, 300);
            }
        };
        handler.postDelayed(runnable, 300);
    }

    private void scheduleDetailSuccessCheck(Activity activity) {
        String nativeUrl = getNativeUrl(activity);
        if (!canAutomate(nativeUrl)) return;
        boolean selfSent = false;
        try {
            Intent intent = activity.getIntent();
            selfSent = intent != null && intent.getBooleanExtra("key_is_self_sent", false);
        } catch (Throwable ignored) {}
        boolean finalSelfSent = selfSent;
        Handler handler = new Handler(activity.getMainLooper());
        int maxChecks = Math.max(3, settings.getInt(RedPacketSettings.KEY_CHECK_TIMES, 10));
        final int[] count = {0};
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed() || !canAutomate(nativeUrl)) return;
                boolean success = checkAndCloseIfSuccess(activity, nativeUrl, finalSelfSent);
                if (!success && count[0]++ < maxChecks) handler.postDelayed(this, 300);
            }
        };
        handler.postDelayed(runnable, 100);
    }

    private boolean checkAndCloseIfFailed(Activity activity, String nativeUrl) {
        try {
            View root = activity.getWindow().getDecorView();
            if (checkSuccessStatus(root)) return false;
            if (checkFailedStatus(root)) {
                if (!state.finishUiPacket(nativeUrl)) return true;
                log("检测到红包失败状态，关闭页面");
                if (failureCallback != null) failureCallback.onFailure(nativeUrl, "手慢了或红包已领完");
                clickedActivities.remove(activity);
                if (settings.getBoolean(RedPacketSettings.KEY_AUTO_CLOSE, false)) activity.finish();
                return true;
            }
        } catch (Throwable e) {
            log("检测红包失败状态异常: " + e.getMessage());
        }
        return false;
    }

    private boolean checkAndCloseIfSuccess(Activity activity, String nativeUrl, boolean selfSent) {
        try {
            View root = activity.getWindow().getDecorView();
            if (!checkSuccessStatus(root)) return false;
            String amount = extractAmountFromView(root);
            if (!state.finishUiPacket(nativeUrl)) return true;
            log("检测到红包领取成功: amount=" + amount);
            if (successCallback != null) successCallback.onSuccess(nativeUrl, amount, selfSent);
            if (settings.getBoolean(RedPacketSettings.KEY_AUTO_CLOSE, false)) activity.finish();
            return true;
        } catch (Throwable e) {
            log("检测红包成功状态异常: " + e.getMessage());
        }
        return false;
    }

    private void tryClickButton(Activity activity, Object thisObject) {
        if (activity == null || thisObject == null || isActivityClicked(activity)) return;
        String[] fields = {"p","q","r","s","t","u","v","w","x","y","z",
                "a","b","c","d","e","f","g","h","i","j","k","l","m","n","o"};
        for (String fieldName : fields) {
            try {
                Object value = KavaReflector.readField(thisObject, fieldName);
                if (value instanceof Button && !isViewClicked((View) value)) {
                    clickButton(activity, (Button) value);
                    markViewClicked((View) value);
                    markActivityClicked(activity);
                    log("通过字段点击红包按钮: " + fieldName);
                    return;
                }
            } catch (Throwable ignored) {}
        }

        try {
            for (Field field : KavaReflector.declaredFields(thisObject.getClass())) {
                Object value = KavaReflector.readField(field, thisObject);
                if (!(value instanceof Button)) continue;
                Button button = (Button) value;
                if (isViewClicked(button)) continue;
                if (isOpenButtonText(getViewText(button), true)) {
                    clickButton(activity, button);
                    markViewClicked(button);
                    markActivityClicked(activity);
                    log("遍历字段点击红包按钮: " + field.getName());
                    return;
                }
            }
        } catch (Throwable ignored) {}

        if (findAndClickButton(activity, activity.getWindow().getDecorView())) {
            markActivityClicked(activity);
        }
    }

    private boolean findAndClickButton(Activity activity, View view) {
        if (view == null || isViewClicked(view)) return false;
        if (view instanceof Button) {
            Button button = (Button) view;
            CharSequence desc = view.getContentDescription();
            if (isOpenButtonText(getViewText(button), false)
                    || (desc != null && desc.toString().contains("開"))) {
                clickButton(activity, button);
                markViewClicked(button);
                return true;
            }
        }
        if (view.isClickable() && view instanceof TextView) {
            TextView tv = (TextView) view;
            if (isOpenButtonText(getViewText(tv), false)) {
                clickView(activity, view);
                markViewClicked(view);
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (findAndClickButton(activity, group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private String extractAmountFromView(View view) {
        TextView deposit = findTextViewContaining(view, "已存入");
        if (deposit == null) return null;
        ViewParent parent = deposit.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == deposit) continue;
                String amount = findAmountInSubtree(child);
                if (amount != null) return amount;
            }
            parent = group.getParent();
        }
        return null;
    }

    private String findAmountInSubtree(View view) {
        if (view == null) return null;
        if (view instanceof TextView) {
            String text = getViewText((TextView) view);
            Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元").matcher(text);
            if (matcher.find()) return matcher.group(0);
            Matcher number = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text);
            if (number.find()) {
                String value = number.group(1);
                if (value.length() <= 6 && value.contains(".")) return value + "元";
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String result = findAmountInSubtree(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private boolean checkFailedStatus(View view) {
        if (view == null) return false;
        if (view instanceof TextView) {
            String text = getViewText((TextView) view);
            if (text.contains("手慢了") || text.contains("红包派完了")
                    || text.contains("已被领完") || text.contains("来晚了")
                    || text.contains("已抢完") || text.contains("已领完")
                    || text.contains("红包已被抢完") || text.contains("红包已领完")
                    || text.contains("该红包已超过") || text.contains("已过期")) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (checkFailedStatus(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private boolean checkSuccessStatus(View view) {
        if (view == null) return false;
        if (view instanceof TextView) {
            String text = getViewText((TextView) view);
            if (text.contains("已存入") || text.matches(".*\\d+\\.\\d+元.*")) return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (checkSuccessStatus(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private TextView findTextViewContaining(View view, String keyword) {
        if (view == null) return null;
        if (view instanceof TextView) {
            TextView tv = (TextView) view;
            if (getViewText(tv).contains(keyword)) return tv;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView result = findTextViewContaining(group.getChildAt(i), keyword);
                if (result != null) return result;
            }
        }
        return null;
    }

    private void clickButton(Activity activity, Button button) {
        clickView(activity, button);
    }

    private void clickView(Activity activity, View view) {
        view.setEnabled(true);
        view.post(() -> view.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        if (activity.isFinishing() || activity.isDestroyed() || !canAutomate(getNativeUrl(activity))) {
                            view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            return;
                        }
                        if (view.isEnabled() && view.getVisibility() == View.VISIBLE) {
                            try {
                                view.performClick();
                                log("红包按钮已点击");
                            } catch (Throwable e) {
                                log("红包按钮点击失败: " + e.getMessage());
                            }
                            view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                }));
    }

    private boolean canAutomate(String nativeUrl) {
        return settings.isEnabled() && state.isUiPending(nativeUrl) && !shouldSkip(nativeUrl);
    }

    private boolean shouldSkip(String nativeUrl) {
        return filterCallback != null && filterCallback.shouldSkip(nativeUrl);
    }

    private String getNativeUrl(Activity activity) {
        try {
            Intent intent = activity.getIntent();
            return intent != null ? intent.getStringExtra("key_native_url") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isOpenButtonText(String text, boolean allowEmpty) {
        return text.contains("開") || text.contains("拆") || text.contains("领取")
                || (allowEmpty && text.length() == 0);
    }

    private String getViewText(TextView tv) {
        CharSequence text = tv.getText();
        return text != null ? text.toString() : "";
    }

    private boolean isActivityClicked(Activity activity) {
        return activity != null && Boolean.TRUE.equals(clickedActivities.get(activity));
    }

    private void markActivityClicked(Activity activity) {
        if (activity != null) clickedActivities.put(activity, true);
    }

    private boolean isViewClicked(View view) {
        return view != null && Boolean.TRUE.equals(clickedViews.get(view));
    }

    private void markViewClicked(View view) {
        if (view != null) clickedViews.put(view, true);
    }

    private void log(String message) {
        if (logger != null) logger.log(message);
    }
}
