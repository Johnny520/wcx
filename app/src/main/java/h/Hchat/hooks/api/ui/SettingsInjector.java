package h.Hchat.hooks.api.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.dx.stock.ProxyBuilder;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import h.Hchat.dexkit.SettingsDexFinder;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.quickread.QuickMarkReadRuntime;
import h.Hchat.hooks.items.quickterminate.QuickTerminateRuntime;
import h.Hchat.hooks.items.settings.SettingsEntrySettings;
import h.Hchat.ui.SettingsUI;
import h.Hchat.utils.KavaReflector;

/**
 * SettingsInjector - 完整翻译 WeKit 的 WeSettingsInjector
 *
 * 三条注入路径（与 WeKit 一一对应）：
 * 1. injectLegacy(): 旧版 SettingsUI，用 Preference 框架
 * 2. injectModernMethod2(): 新版 MainSettingsUI，用 ProxyBuilder 动态代理 SettingItem
 * 3. hookLauncherUi(): LauncherUI Intent extra 打开设置
 * 4. hookLauncherPlusMenu(): LauncherUI 右上角加号菜单入口
 * 5. hookLauncherPlusLongPress(): LauncherUI 右上角加号长按入口
 */
public class SettingsInjector {

    private static final String TAG = "[Hchat:SettingsInjector]";
    private static final String PREFS_KEY = "Hchat_settings";
    private static final String PREFS_TITLE = "Hchat";
    private static final String MODULE_TAG = "Hchat";
    private static final boolean VERBOSE = false;

    // 新版 SettingItem 的 nameResId，用 Context.getString 拦截时判断。
    // 必须使用 Hchat 独有值，避免和 WeKitFork 等同源模块的伪资源 ID 串台。
    private static final int HCHAT_SETTING_ITEM_NAME_RES_ID = -1212373076;
    private static final String SETTING_ITEM_ID = "SettingGroup_Main_Other_Hchat";
    private static final int HCHAT_PLUS_MENU_ID = -1212373075;
    private static final int HCHAT_PLUS_LONG_PRESS_TAG = -1212373074;
    private static final int QUICK_READ_PLUS_MENU_ID = -1212373073;
    private static final String QUICK_READ_PLUS_MENU_TITLE = "全部已读";
    private static final int QUICK_TERMINATE_PLUS_MENU_ID = -1212373072;
    private static final String QUICK_TERMINATE_PLUS_MENU_TITLE = "快捷终止";
    private static final int PLUGIN_AGENT_PLUS_MENU_ID = -1212373071;
    private static final String PLUGIN_AGENT_PLUS_MENU_TITLE = "插件 Agent";

    private final Context hostContext;
    private final ClassLoader classLoader;
    private final SettingsDexFinder dex;
    private final SettingsEntrySettings entrySettings;
    private final File dexCacheDir;
    private final HashSet<Class<?>> plusMenuAdapterViewHookedClasses = new HashSet<>();
    private XC_MethodHook.Unhook contextGetStringUnhook;
    private volatile Class<?> modernLocationParentClass;
    private volatile Class<?> modernLocationFrontClass;

    public SettingsInjector(Context context, ClassLoader classLoader, SettingsDexFinder dex,
                            SettingsEntrySettings entrySettings) {
        this.hostContext = context != null && context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        this.classLoader = classLoader;
        this.dex = dex;
        this.entrySettings = entrySettings;
        this.dexCacheDir = new File(context.getCacheDir(), "Hchat_proxy_classes");
        this.dexCacheDir.mkdirs();
    }

    /**
     * 安装全部注入 Hook（对应 WeKit onEnable）。
     */
    public void hookSettings() {
        logDetail("hookSettings start");
        injectLegacy();         // 旧版 SettingsUI
        injectModernMethod2();  // 新版 MainSettingsUI (>= 8.0.49)
        hookLauncherUi();       // LauncherUI Intent extra
        if (isPlusMenuHookRequired()) {
            hookLauncherPlusMenu();  // LauncherUI 右上角加号菜单
        }
        if (isPlusLongPressEnabled()) {
            hookLauncherPlusLongPress();  // LauncherUI 右上角加号长按
        }
        logDetail("hookSettings end");
    }

    private boolean isPlusMenuEnabled() {
        return entrySettings != null && entrySettings.plusMenuEnabled();
    }

    private boolean isQuickReadPlusMenuEnabled() {
        return QuickMarkReadRuntime.isPlusMenuEnabled(hostContext);
    }

    private boolean isQuickTerminatePlusMenuEnabled() {
        return QuickTerminateRuntime.isEnabled(hostContext);
    }

    private boolean isPluginAgentPlusMenuEnabled() {
        return entrySettings != null && entrySettings.pluginAgentPlusMenuEnabled();
    }

    private boolean isPlusMenuHookRequired() {
        return isPlusMenuEnabled()
                || isPluginAgentPlusMenuEnabled()
                || isQuickReadPlusMenuEnabled()
                || isQuickTerminatePlusMenuEnabled();
    }

    private boolean isPlusLongPressEnabled() {
        return entrySettings != null && entrySettings.plusLongPressEnabled();
    }

    // ========================================================================
    // injectLegacy — 对应 WeKit injectLegacy()
    // ========================================================================

    /**
     * 旧版微信 (< 8.0.49) 注入。
     * Hook SettingsUI.initView → 创建 IconPreference → addPreference(pref, 0)
     * Hook onPreferenceTreeClick → 拦截 PREFS_KEY 的点击
     */
    private void injectLegacy() {
        if (dex.settingsUIClass == null) {
            logDetail("[Legacy] SettingsUI 不存在，跳过");
            return;
        }
        if (dex.iconPreferenceClass == null) {
            logDetail("[Legacy] IconPreference 不存在，跳过");
            return;
        }
        if (dex.methodSetKey == null || dex.methodSetTitle == null
                || dex.methodGetKey == null || dex.methodAddPref == null) {
            logDetail("[Legacy] Preference 方法未全部解析，跳过");
            return;
        }

        try {
            // Hook SettingsUI.initView()
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(dex.settingsUIClass, "initView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.thisObject;
                            try {
                                Constructor<?> ctor = KavaReflector.findConstructor(
                                        dex.iconPreferenceClass, Context.class);
                                Object prefInstance = KavaReflector.newInstance(ctor, activity);

                                KavaReflector.invoke(dex.methodSetKey, prefInstance, PREFS_KEY);
                                KavaReflector.invoke(dex.methodSetTitle, prefInstance, PREFS_TITLE);

                                Object prefScreen = KavaReflector.invokeMethod(activity, "getPreferenceScreen");
                                KavaReflector.invoke(dex.methodAddPref, prefScreen, prefInstance, 0);

                                logDetail("[Legacy] 设置条目已插入");
                            } catch (Throwable e) {
                                h.Hchat.utils.HLog.e(TAG + " [Legacy] 插入失败: " + e.getMessage(), e);
                            }
                        }
                    }));

            // Hook onPreferenceTreeClick
            // WeKit 用 resolve().firstMethod { name = "onPreferenceTreeClick" }
            // 不依赖参数签名，直接按方法名搜索所有重载
            Method targetTreeClick = null;
            for (Method m : KavaReflector.declaredMethods(dex.settingsUIClass)) {
                if ("onPreferenceTreeClick".equals(m.getName())) {
                    targetTreeClick = m;
                    break;
                }
            }
            if (targetTreeClick != null) {
                final Method finalTarget = targetTreeClick;
                HookRegistry.get().hook(finalTarget, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            // WeKit: if (args.size < 2) return; val preference = args[1]
                            if (param.args.length < 2) return;
                            Object preference = param.args[1];
                            if (preference == null) return;

                            String key = (String) KavaReflector.invoke(dex.methodGetKey, preference);
                            if (PREFS_KEY.equals(key)) {
                                Activity activity = (Activity) param.thisObject;
                                showModuleSettingsPage(activity);
                                param.setResult(true);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                logDetail("[Legacy] onPreferenceTreeClick Hook 已安装: " + finalTarget);
            } else {
                logDetail("[Legacy] onPreferenceTreeClick 方法未找到");
            }

            logDetail("[Legacy] 已安装");
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [Legacy] Hook 失败: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // injectModernMethod2 — 对应 WeKit injectModernMethod2()
    // ========================================================================

    /**
     * 新版微信 (>= 8.0.49) 注入。
     * 用 ProxyBuilder 动态代理创建自定义 SettingItem，注入到 MainSettingsUI。
     *
     * WeKit 原文：
     * 1. 创建 customSettingItemClass（ProxyBuilder 代理 BaseSettingItem）
     * 2. hook SettingGroupPersonalInfo 返回 SettingLocation
     * 3. hook SettingItemClassesProvider 注入自定义类
     * 4. hook BaseSettingPrefUI.superImportUIComponents 注入到 HashSet
     * 5. hook Context.getString 拦截自定义 resId 返回标题文本
     * 6. hook BaseSettingUI.onDestroy 清理 unhook
     */
    private void injectModernMethod2() {
        logDetail("[Modern] start");

        // 前置检查：新版框架类是否全部存在
        if (dex.settingGroupMainClass == null) {
            logDetail("[Modern] SettingGroupMain 不存在，跳过");
            return;
        }
        if (dex.baseSettingItemClass == null) {
            logDetail("[Modern] BaseSettingItem 不存在，跳过");
            return;
        }
        if (dex.settingLocationClass == null) {
            logDetail("[Modern] SettingLocation 不存在，跳过");
            return;
        }
        if (dex.settingItemClassesProviderClass == null) {
            logDetail("[Modern] SettingItemClassesProvider 不存在，跳过");
            return;
        }

        try {
            // 需要的组类
            Class<?> groupSettingItemClass = dex.settingGroupMainClass;
            Class<?> parentSettingItemClass = dex.settingAdditionHeaderSearchClass;
            Class<?> childSettingItemClass = dex.settingGroupPersonalInfoClass;
            if (parentSettingItemClass == null || childSettingItemClass == null) {
                logDetail("[Modern] SettingAdditionHeaderSearch 或 SettingGroupPersonalInfo 不存在，跳过");
                return;
            }

            // === Step 1: 通过反射获取 SettingGroupAccountInfo 的方法名 ===
            String mGetGroupItemClass = null;
            String mReturns1 = null;
            String mOnClick = null;
            String mGetStringId = null;
            String mGetSettingLocation = null;
            String mGetNameResId = null;

            if (dex.settingGroupAccountInfoClass != null && dex.methodAccountInfoReturns1 != null) {
                // mGetGroupItemClass: return Class
                for (Method m : KavaReflector.declaredMethods(dex.settingGroupAccountInfoClass)) {
                    if (m.getReturnType() == Class.class) {
                        mGetGroupItemClass = m.getName();
                        break;
                    }
                }
                // mReturns1
                mReturns1 = dex.methodAccountInfoReturns1.getName();
                // mOnClick: paramCount == 3
                for (Method m : KavaReflector.declaredMethods(dex.settingGroupAccountInfoClass)) {
                    if (m.getParameterCount() == 3) {
                        mOnClick = m.getName();
                        break;
                    }
                }
                // mGetStringId: return String
                if (dex.methodAccountInfoSettingKey != null) {
                    mGetStringId = dex.methodAccountInfoSettingKey.getName();
                } else {
                    for (Method m : KavaReflector.declaredMethods(dex.settingGroupAccountInfoClass)) {
                        if (m.getReturnType() == String.class
                                && m.getParameterCount() == 0
                                && KavaReflector.isAbstract(findInheritedMethodModifiers(dex.baseSettingItemClass, m))) {
                            mGetStringId = m.getName();
                            break;
                        }
                    }
                }
                // mGetSettingLocation: return SettingLocation
                for (Method m : KavaReflector.declaredMethods(dex.settingGroupAccountInfoClass)) {
                    if (m.getReturnType() == dex.settingLocationClass) {
                        mGetSettingLocation = m.getName();
                    }
                }
                // mGetNameResId: return int, != mReturns1
                for (Method m : KavaReflector.declaredMethods(dex.settingGroupAccountInfoClass)) {
                    if (m.getReturnType() == int.class && !m.getName().equals(mReturns1)) {
                        mGetNameResId = m.getName();
                    }
                }
            }

            final String fGroupClass = mGetGroupItemClass;
            final String fReturns1 = mReturns1;
            final String fOnClick = mOnClick;
            final String fStringId = mGetStringId;
            final String fLocation = mGetSettingLocation;
            final String fNameResId = mGetNameResId;

            logDetail("[Modern] 方法名解析: " + fGroupClass + ", " + fReturns1
                    + ", " + fOnClick + ", " + fStringId + ", " + fLocation + ", " + fNameResId);

            if (fGroupClass == null || fOnClick == null
                    || fStringId == null || fLocation == null || fNameResId == null) {
                logDetail("[Modern] 方法名解析不完整，跳过 ProxyBuilder 注入");
                return;
            }

            hookTitleStringResource();

            // === Step 2: 用 ProxyBuilder 创建代理类 ===
            Class<?> activityClass = KavaReflector.loadClass(
                    "androidx.appcompat.app.AppCompatActivity", classLoader);

            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                Object[] safeArgs = args == null ? new Object[0] : args;

                if (fGroupClass.equals(name)) {
                    return groupSettingItemClass;
                }
                if (fReturns1 != null && fReturns1.equals(name)) {
                    return 1;
                }
                if (fOnClick.equals(name) && args != null && args.length > 0) {
                    Context ctx = (Context) args[0];
                    showModuleSettingsPage(ctx);
                    return null;
                }
                if (fStringId.equals(name)) {
                    return SETTING_ITEM_ID;
                }
                if (fLocation.equals(name)) {
                    Class<?> parentClass = modernLocationParentClass != null
                            ? modernLocationParentClass
                            : groupSettingItemClass;
                    Class<?> frontClass = modernLocationFrontClass != null
                            ? modernLocationFrontClass
                            : parentSettingItemClass;
                    return newSettingLocation(parentClass, frontClass);
                }
                if (fNameResId.equals(name)) {
                    return HCHAT_SETTING_ITEM_NAME_RES_ID;
                }

                // 抽象方法返回默认值
                if (KavaReflector.isAbstract(method)) {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class || rt == Boolean.class) return false;
                    if (rt == byte.class || rt == Byte.class) return (byte) 0;
                    if (rt == short.class || rt == Short.class) return (short) 0;
                    if (rt == int.class || rt == Integer.class) return 0;
                    if (rt == long.class || rt == Long.class) return 0L;
                    if (rt == float.class || rt == Float.class) return 0.0f;
                    if (rt == double.class || rt == Double.class) return 0.0;
                    if (rt == char.class || rt == Character.class) return '\u0000';
                    return ProxyBuilder.callSuper(proxy, method, safeArgs);
                }

                return ProxyBuilder.callSuper(proxy, method, safeArgs);
            };

            Class<?> proxyClass = ProxyBuilder.forClass(dex.baseSettingItemClass)
                    .dexCache(dexCacheDir)
                    .parentClassLoader(classLoader)
                    .constructorArgTypes(activityClass)
                    .handler(handler)
                    .buildProxyClass();

            // hook proxy class 构造函数，确保 handler 被设置
            Constructor<?> proxyConstructor = KavaReflector.findConstructor(proxyClass, activityClass);
            final Constructor<?> finalProxyConstructor = proxyConstructor;
            HookRegistry.get().hook(proxyConstructor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ProxyBuilder.setInvocationHandler(param.thisObject, handler);
                }
            });

            logDetail("[Modern] ProxyBuilder 代理类已创建: " + proxyClass.getName());
            modernLocationParentClass = groupSettingItemClass;
            modernLocationFrontClass = parentSettingItemClass;

            // === Step 3: hook SettingGroupPersonalInfo 返回 SettingLocation ===
            // 找到返回 SettingLocation 的方法
            for (Method m : KavaReflector.declaredMethods(childSettingItemClass)) {
                if (m.getReturnType() == dex.settingLocationClass) {
                    HookRegistry.get().hook(m, new XC_MethodHook(
                            de.robv.android.xposed.callbacks.XCallback.PRIORITY_HIGHEST) {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object oldLocation = param.getResult();
                                Class<?> parentClass = readSettingLocationClass(oldLocation, 0, groupSettingItemClass);
                                Class<?> frontClass = readSettingLocationClass(oldLocation, 1, parentSettingItemClass);
                                if (frontClass == proxyClass) {
                                    return;
                                }
                                modernLocationParentClass = parentClass;
                                modernLocationFrontClass = frontClass;
                                logDetail("[Modern] 接入设置入口链: front="
                                        + frontClass.getName());
                                Object location = newSettingLocation(parentClass, proxyClass);
                                param.setResult(location);
                            } catch (Throwable e) {
                                h.Hchat.utils.HLog.e(TAG + " [Modern] SettingLocation 构造失败: " + e.getMessage(), e);
                            }
                        }
                    });
                    logDetail("[Modern] SettingGroupPersonalInfo.hook: " + m.getName());
                    break;
                }
            }

            // === Step 4: hook SettingItemClassesProvider 注入自定义类 ===
            final Class<?> finalProxyClass = proxyClass;
            Method providerMethod = findSettingItemClassesProviderMethod();
            if (providerMethod == null) {
                logDetail("[Modern] ClassesProvider 方法未找到，跳过 Provider 注入");
            } else {
                HookRegistry.get().hook(providerMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Map<?, ?> map = (Map<?, ?>) param.getResult();
                            if (map == null) return;

                            Map<Object, Object> patched = new LinkedHashMap<>();
                            patched.putAll((Map<?, ?>) map);

                            if (hasBaseSettingItemValue(map)) {
                                Object activity = param.args != null && param.args.length > 0 ? param.args[0] : null;
                                if (activity == null) return;
                                Object item = KavaReflector.newInstance(finalProxyConstructor, activity);
                                ProxyBuilder.setInvocationHandler(item, handler);
                                patched.put(SETTING_ITEM_ID, item);
                                param.setResult(patched);
                                return;
                            }

                            Object pageKey = findSettingsPageKey(
                                    map,
                                    groupSettingItemClass,
                                    parentSettingItemClass,
                                    childSettingItemClass);
                            if (pageKey == null) {
                                logDetail("[Modern] 主设置页集合未找到，跳过 Provider 注入");
                                return;
                            }
                            Object value = pageKey != null ? map.get(pageKey) : null;
                            if (!(value instanceof Iterable<?>)) {
                                logDetail("[Modern] 主设置页集合不是 Iterable，跳过 Provider 注入");
                                return;
                            }
                            LinkedHashSet<Object> newSet = new LinkedHashSet<>();
                            for (Object item : (Iterable<?>) value) {
                                newSet.add(item);
                            }
                            newSet.add(finalProxyClass);
                            patched.put(pageKey, newSet);
                            param.setResult(patched);
                        } catch (Throwable e) {
                            h.Hchat.utils.HLog.e(TAG + " [Modern] ClassesProvider 注入失败: " + e.getMessage(), e);
                        }
                    }
                });

                logDetail("[Modern] SettingItemClassesProvider 已 Hook");
            }

            // === Step 5: hook BaseSettingPrefUI.superImportUIComponents ===
            hookSuperImportUIComponents(dex.baseSettingPrefUIClass, finalProxyClass, true);
            hookSuperImportUIComponents(dex.mainSettingsUIClass, finalProxyClass, false);

            logDetail("[Modern] 已安装");
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [Modern] 安装失败: " + e.getMessage(), e);
        }
    }

    private int findInheritedMethodModifiers(Class<?> startClass, Method overrideMethod) {
        if (startClass == null || overrideMethod == null) return 0;
        Class<?> current = startClass;
        while (current != null) {
            try {
                Method inherited = KavaReflector.findDeclaredMethod(
                        current, overrideMethod.getName(), overrideMethod.getParameterTypes());
                if (inherited == null) throw new NoSuchMethodException(overrideMethod.getName());
                return KavaReflector.modifiers(inherited);
            } catch (Throwable ignored) {
                current = current.getSuperclass();
            }
        }
        return 0;
    }

    private Object newSettingLocation(Class<?> parentClass, Class<?> frontClass) {
        return KavaReflector.newInstance(
                KavaReflector.findConstructor(dex.settingLocationClass, Class.class, Class.class),
                parentClass, frontClass);
    }

    private Class<?> readSettingLocationClass(Object location, int index, Class<?> fallback) {
        if (location == null) return fallback;
        int seen = 0;
        for (Field field : KavaReflector.declaredFields(location.getClass())) {
            try {
                if (field.getType() != Class.class) continue;
                Object value = KavaReflector.readField(field, location);
                if (seen == index && value instanceof Class<?>) {
                    return (Class<?>) value;
                }
                seen++;
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private void hookSuperImportUIComponents(Class<?> ownerClass, Class<?> proxyClass, boolean mainOnly) {
        if (ownerClass == null) return;

        Method superImportMethod = null;
        for (Method m : KavaReflector.declaredMethods(ownerClass)) {
            if ("superImportUIComponents".equals(m.getName())
                    && m.getParameterCount() == 1
                    && HashSet.class.isAssignableFrom(m.getParameterTypes()[0])) {
                superImportMethod = m;
                break;
            }
        }
        if (superImportMethod == null) {
            logDetail("[Modern] " + ownerClass.getName()
                    + ".superImportUIComponents 未找到");
            return;
        }

        HookRegistry.get().hook(superImportMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (mainOnly
                            && dex.mainSettingsUIClass != null
                            && !dex.mainSettingsUIClass.isInstance(param.thisObject)) {
                        return;
                    }

                    @SuppressWarnings("unchecked")
                    HashSet<Class<?>> itemClasses = (HashSet<Class<?>>) param.args[0];
                    itemClasses.add(proxyClass);

                    logDetail("[Modern] superImportUIComponents 注入完成: "
                            + ownerClass.getName());
                } catch (Throwable e) {
                    h.Hchat.utils.HLog.e(TAG + " [Modern] superImportUIComponents 失败: "
                            + ownerClass.getName() + ", " + e.getMessage(), e);
                }
            }
        });
        logDetail("[Modern] superImportUIComponents Hook 已安装: "
                + ownerClass.getName());
    }

    private void hookTitleStringResource() {
        if (contextGetStringUnhook != null) return;
        contextGetStringUnhook = XposedHelpers.findAndHookMethod(
                Context.class, "getString", int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((int) param.args[0] == HCHAT_SETTING_ITEM_NAME_RES_ID) {
                            param.setResult(PREFS_TITLE);
                        }
                    }
                });
        HookRegistry.get().add(contextGetStringUnhook);
        logDetail("[Modern] Context.getString 标题资源 Hook 已安装");
    }

    private Method findSettingItemClassesProviderMethod() {
        if (dex.settingItemClassesProviderClass == null) return null;

        for (Method m : KavaReflector.declaredMethods(dex.settingItemClassesProviderClass)) {
            if (m.getParameterCount() == 0
                    && Map.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        return null;
    }

    private Object findSettingsPageKey(Map<?, ?> map, Class<?> pageClass, Class<?>... knownItemClasses) {
        if (map == null || pageClass == null) return null;
        if (map.containsKey(pageClass)) return pageClass;
        String pageName = pageClass.getName();
        for (Object key : map.keySet()) {
            if (key == pageClass) return key;
            if (key instanceof Class<?> && pageName.equals(((Class<?>) key).getName())) {
                return key;
            }
            if (key instanceof String && pageName.equals(key)) {
                return key;
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (containsAnyClass(entry.getValue(), knownItemClasses)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean containsAnyClass(Object value, Class<?>... targetClasses) {
        if (!(value instanceof Iterable<?>) || targetClasses == null) return false;
        for (Object item : (Iterable<?>) value) {
            if (!(item instanceof Class<?>)) continue;
            String itemName = ((Class<?>) item).getName();
            for (Class<?> targetClass : targetClasses) {
                if (targetClass == null) continue;
                if (item == targetClass || itemName.equals(targetClass.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasBaseSettingItemValue(Map<?, ?> map) {
        if (map == null || dex.baseSettingItemClass == null) return false;
        for (Object value : map.values()) {
            if (value != null && dex.baseSettingItemClass.isInstance(value)) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // hookLauncherUi — 对应 WeKit hookLauncherUi()
    // ========================================================================

    /**
     * Hook LauncherUI 的 onCreate 和 onNewIntent。
     * 当 Intent 包含模块 extra 时，打开模块设置页。
     */
    private void hookLauncherUi() {
        try {
            Class<?> launcherClass = KavaReflector.loadClass(
                    "com.tencent.mm.ui.LauncherUI", classLoader);
            if (launcherClass == null) return;

            // Hook onCreate
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(launcherClass, "onCreate",
                    android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                Intent intent = activity.getIntent();
                                if (intent != null && intent.hasExtra(MODULE_TAG)) {
                                    new Handler(Looper.getMainLooper()).postDelayed(
                                            () -> showModuleSettingsPage(activity), 500);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }));

            // Hook onNewIntent
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(launcherClass, "onNewIntent",
                    Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                Intent intent = (Intent) param.args[0];
                                if (intent != null && intent.hasExtra(MODULE_TAG)) {
                                    showModuleSettingsPage(activity);
                                }
                            } catch (Throwable ignored) {}
                        }
                    }));

            logDetail("[LauncherUI] 已安装");
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [LauncherUI] Hook 失败: " + e.getMessage(), e);
        }
    }

    // ========================================================================
    // hookLauncherPlusLongPress — LauncherUI 右上角加号长按入口
    // ========================================================================

    private void hookLauncherPlusLongPress() {
        try {
            if (hookLauncherPlusActionViewConstructors()) {
                logDetail("[PlusLongPress] 已安装");
            } else {
                logDetail("[PlusLongPress] PlusActionView 未安装");
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [PlusLongPress] Hook 失败: " + e.getMessage(), e);
        }
    }

    private boolean hookLauncherPlusActionViewConstructors() {
        try {
            Class<?> plusActionViewClass = KavaReflector.loadClass(
                    "com.tencent.mm.ui.HomeUI$PlusActionView", classLoader);
            if (plusActionViewClass == null) return false;
            for (XC_MethodHook.Unhook unhook : XposedBridge.hookAllConstructors(
                    plusActionViewClass,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                markPlusView(resolvePlusActionRealView(param.thisObject));
                            } catch (Throwable e) {
                                h.Hchat.utils.HLog.e(TAG + " [PlusLongPress] 绑定加号入口失败: " + e.getMessage(), e);
                            }
                        }
                    })) {
                HookRegistry.get().add(unhook);
            }
            return true;
        } catch (Throwable e) {
            logDetail("[PlusLongPress] PlusActionView 直接 Hook 不可用: " + e.getMessage());
            return false;
        }
    }

    private void markPlusView(View plusView) {
        if (plusView == null) return;
        if (isMarkedPlusView(plusView)) return;
        plusView.setTag(HCHAT_PLUS_LONG_PRESS_TAG, Boolean.TRUE);
        plusView.setOnLongClickListener(view -> {
            return openPlusLongPressSettings(view);
        });
    }

    private boolean isMarkedPlusView(View view) {
        return view != null && Boolean.TRUE.equals(view.getTag(HCHAT_PLUS_LONG_PRESS_TAG));
    }

    private boolean openPlusLongPressSettings(View view) {
        try {
            if (view == null) return false;
            Context context = view.getContext();
            if (context == null) return false;
            showModuleSettingsPage(context);
            return true;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [PlusLongPress] 长按打开失败: " + e.getMessage(), e);
            return false;
        }
    }

    private View resolvePlusActionRealView(Object plusAction) {
        if (plusAction instanceof View) {
            return (View) plusAction;
        }
        if (plusAction == null || !classNameContains(plusAction.getClass(), "HomeUI$PlusActionView")) {
            return null;
        }
        try {
            Object value = KavaReflector.invokeMethod(plusAction, "h");
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean classNameContains(Class<?> clazz, String needle) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            String name = current.getName();
            if (name.contains(needle)) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    // ========================================================================
    // hookLauncherPlusMenu — LauncherUI 右上角加号菜单入口
    // ========================================================================

    private void hookLauncherPlusMenu() {
        if (dex.plusSubMenuHelperClass == null
                || dex.plusSubMenuAdapterMethod == null
                || dex.plusSubMenuOnItemClickMethod == null) {
            logDetail("[PlusMenu] PlusSubMenuHelper 未解析，跳过");
            return;
        }

        try {
            HookRegistry.get().hook(dex.plusSubMenuAdapterMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        BaseAdapter adapter = param.getResult() instanceof BaseAdapter
                                ? (BaseAdapter) param.getResult()
                                : null;
                        appendEnabledPlusMenuItems(param.thisObject, adapter);
                    } catch (Throwable e) {
                        h.Hchat.utils.HLog.e(TAG + " [PlusMenu] 添加入口失败: " + e.getMessage(), e);
                    }
                }
            });

            HookRegistry.get().hook(dex.plusSubMenuOnItemClickMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3) return;
                        int position = (int) param.args[2];
                        int itemId = readPlusMenuItemId(param.thisObject, position);
                        if (itemId == Integer.MIN_VALUE) {
                            return;
                        }

                        param.setResult(null);
                        dismissPlusMenu(param.thisObject);
                        if (itemId == QUICK_TERMINATE_PLUS_MENU_ID) {
                            QuickTerminateRuntime.terminateCurrentProcess();
                            return;
                        }

                        Context context = findContextFieldValue(param.thisObject);
                        if (context == null) return;
                        if (itemId == HCHAT_PLUS_MENU_ID) {
                            showModuleSettingsPage(context);
                        } else if (itemId == PLUGIN_AGENT_PLUS_MENU_ID) {
                            showScriptPluginAgentPage(context);
                        } else if (itemId == QUICK_READ_PLUS_MENU_ID) {
                            QuickMarkReadRuntime.markAllRead(context, true);
                        }
                    } catch (Throwable e) {
                        h.Hchat.utils.HLog.e(TAG + " [PlusMenu] 处理菜单点击失败: " + e.getMessage(), e);
                    }
                }
            });

            hookPlusMenuPopulateAndShowMethods(dex.plusSubMenuHelperClass);

            logDetail("[PlusMenu] 已安装: " + dex.plusSubMenuHelperClass.getName());
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [PlusMenu] Hook 失败: " + e.getMessage(), e);
        }
    }

    private void appendEnabledPlusMenuItems(Object helper, BaseAdapter adapter) {
        SparseArray<Object> items = findSparseArrayFieldValue(helper);
        if (items == null) return;
        boolean changed = false;
        if (!isPlusMenuEnabled()) {
            changed |= removePlusMenuItem(items, HCHAT_PLUS_MENU_ID, PREFS_TITLE);
        }
        if (!isQuickReadPlusMenuEnabled()) {
            changed |= removePlusMenuItem(items, QUICK_READ_PLUS_MENU_ID, QUICK_READ_PLUS_MENU_TITLE);
        }
        if (!isQuickTerminatePlusMenuEnabled()) {
            changed |= removePlusMenuItem(
                    items,
                    QUICK_TERMINATE_PLUS_MENU_ID,
                    QUICK_TERMINATE_PLUS_MENU_TITLE);
        }
        if (!isPluginAgentPlusMenuEnabled()) {
            changed |= removePlusMenuItem(
                    items,
                    PLUGIN_AGENT_PLUS_MENU_ID,
                    PLUGIN_AGENT_PLUS_MENU_TITLE);
        }
        if (!isPlusMenuHookRequired()) {
            if (changed && adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        if (containsPlusMenuItem(items, HCHAT_PLUS_MENU_ID, PREFS_TITLE)
                && containsPlusMenuItem(items, QUICK_READ_PLUS_MENU_ID, QUICK_READ_PLUS_MENU_TITLE)
                && containsPlusMenuItem(items, PLUGIN_AGENT_PLUS_MENU_ID, PLUGIN_AGENT_PLUS_MENU_TITLE)
                && containsPlusMenuItem(
                        items,
                        QUICK_TERMINATE_PLUS_MENU_ID,
                        QUICK_TERMINATE_PLUS_MENU_TITLE)) {
            ensurePlusMenuAdapterViewHook(adapter);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            return;
        }

        Object sampleWrapper = firstSparseArrayValue(items);
        if (sampleWrapper == null) return;

        Object sampleItem = findNestedItemObject(sampleWrapper);
        if (sampleItem == null) return;

        ensurePlusMenuAdapterViewHook(adapter);
        if (isPlusMenuEnabled()
                && !containsPlusMenuItem(items, HCHAT_PLUS_MENU_ID, PREFS_TITLE)) {
            changed |= appendPlusMenuItem(items, sampleWrapper.getClass(), sampleItem, HCHAT_PLUS_MENU_ID, PREFS_TITLE);
        }
        if (isPluginAgentPlusMenuEnabled()
                && !containsPlusMenuItem(
                        items,
                        PLUGIN_AGENT_PLUS_MENU_ID,
                        PLUGIN_AGENT_PLUS_MENU_TITLE)) {
            changed |= appendPlusMenuItem(
                    items,
                    sampleWrapper.getClass(),
                    sampleItem,
                    PLUGIN_AGENT_PLUS_MENU_ID,
                    PLUGIN_AGENT_PLUS_MENU_TITLE);
        }
        if (isQuickReadPlusMenuEnabled()
                && !containsPlusMenuItem(items, QUICK_READ_PLUS_MENU_ID, QUICK_READ_PLUS_MENU_TITLE)) {
            changed |= appendPlusMenuItem(
                    items,
                    sampleWrapper.getClass(),
                    sampleItem,
                    QUICK_READ_PLUS_MENU_ID,
                    QUICK_READ_PLUS_MENU_TITLE);
        }
        if (isQuickTerminatePlusMenuEnabled()
                && !containsPlusMenuItem(
                        items,
                        QUICK_TERMINATE_PLUS_MENU_ID,
                        QUICK_TERMINATE_PLUS_MENU_TITLE)) {
            changed |= appendPlusMenuItem(
                    items,
                    sampleWrapper.getClass(),
                    sampleItem,
                    QUICK_TERMINATE_PLUS_MENU_ID,
                    QUICK_TERMINATE_PLUS_MENU_TITLE);
        }
        if (changed && adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private boolean appendPlusMenuItem(SparseArray<Object> items, Class<?> wrapperClass,
                                       Object sampleItem, int itemId, String title) {
        Object item = createPlusMenuItem(sampleItem, itemId, title);
        if (item == null) return false;
        Object wrapper = createPlusMenuWrapper(wrapperClass, item);
        if (wrapper == null) return false;
        items.put(nextPlusMenuPositionKey(items), wrapper);
        return true;
    }

    @SuppressWarnings("unchecked")
    private SparseArray<Object> findSparseArrayFieldValue(Object helper) {
        if (helper == null) return null;
        Class<?> current = helper.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                if (!KavaReflector.isStatic(field)
                        && SparseArray.class.isAssignableFrom(field.getType())) {
                    Object value = KavaReflector.readField(field, helper);
                    if (value instanceof SparseArray<?>) {
                        return (SparseArray<Object>) value;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Context findContextFieldValue(Object helper) {
        if (helper == null) return null;
        Class<?> current = helper.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                if (!KavaReflector.isStatic(field)
                        && Context.class.isAssignableFrom(field.getType())) {
                    Object value = KavaReflector.readField(field, helper);
                    if (value instanceof Context) {
                        return (Context) value;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean containsPlusMenuItem(SparseArray<Object> items, int itemId, String title) {
        for (int i = 0; i < items.size(); i++) {
            if (isPlusMenuWrapper(items.valueAt(i), itemId, title)) {
                return true;
            }
        }
        return false;
    }

    private boolean removePlusMenuItem(SparseArray<Object> items, int itemId, String title) {
        boolean removed = false;
        for (int i = items.size() - 1; i >= 0; i--) {
            if (isPlusMenuWrapper(items.valueAt(i), itemId, title)) {
                items.removeAt(i);
                removed = true;
            }
        }
        return removed;
    }

    private int nextPlusMenuPositionKey(SparseArray<Object> items) {
        int key = Math.max(0, items.size());
        while (items.get(key) != null) {
            key++;
        }
        return key;
    }

    private Object firstSparseArrayValue(SparseArray<Object> items) {
        return items.size() > 0 ? items.valueAt(0) : null;
    }

    private int readPlusMenuItemId(Object helper, int position) {
        SparseArray<Object> items = findSparseArrayFieldValue(helper);
        if (items == null) return Integer.MIN_VALUE;
        if (position >= 0 && position < items.size()) {
            Object wrapperByIndex = items.valueAt(position);
            int idByIndex = plusMenuWrapperId(wrapperByIndex);
            if (idByIndex != Integer.MIN_VALUE) return idByIndex;
        }
        Object wrapperByKey = items.get(position);
        return plusMenuWrapperId(wrapperByKey);
    }

    private int plusMenuWrapperId(Object wrapper) {
        if (isPlusMenuWrapper(wrapper, HCHAT_PLUS_MENU_ID, PREFS_TITLE)) {
            return HCHAT_PLUS_MENU_ID;
        }
        if (isPlusMenuWrapper(wrapper, QUICK_READ_PLUS_MENU_ID, QUICK_READ_PLUS_MENU_TITLE)) {
            return QUICK_READ_PLUS_MENU_ID;
        }
        if (isPlusMenuWrapper(
                wrapper,
                QUICK_TERMINATE_PLUS_MENU_ID,
                QUICK_TERMINATE_PLUS_MENU_TITLE)) {
            return QUICK_TERMINATE_PLUS_MENU_ID;
        }
        if (isPlusMenuWrapper(
                wrapper,
                PLUGIN_AGENT_PLUS_MENU_ID,
                PLUGIN_AGENT_PLUS_MENU_TITLE)) {
            return PLUGIN_AGENT_PLUS_MENU_ID;
        }
        return Integer.MIN_VALUE;
    }

    private boolean isPlusMenuWrapper(Object wrapper, int itemId, String title) {
        Object item = findNestedItemObject(wrapper);
        if (item == null) return false;
        boolean allowTitleFallback = itemId == HCHAT_PLUS_MENU_ID;
        boolean hasTargetTitle = false;
        boolean hasTargetId = false;
        for (Field field : KavaReflector.declaredFields(item.getClass())) {
            if (KavaReflector.isStatic(field)) continue;
            Object value = KavaReflector.readField(field, item);
            if (value instanceof Integer && (Integer) value == itemId) {
                hasTargetId = true;
            } else if (value instanceof CharSequence && title.contentEquals((CharSequence) value)) {
                hasTargetTitle = true;
            } else if (value instanceof String && title.equals(value)) {
                hasTargetTitle = true;
            }
        }
        if (hasTargetId || (allowTitleFallback && hasTargetTitle)) return true;

        for (Field field : KavaReflector.declaredFields(wrapper.getClass())) {
            if (KavaReflector.isStatic(field)) continue;
            Object value = KavaReflector.readField(field, wrapper);
            if (value instanceof Integer && (Integer) value == itemId) {
                return true;
            }
            if (allowTitleFallback
                    && value instanceof CharSequence
                    && title.contentEquals((CharSequence) value)) {
                return true;
            }
            if (allowTitleFallback
                    && value instanceof String
                    && title.equals(value)) {
                return true;
            }
            if (value != null && value != item) {
                Class<?> valueClass = value.getClass();
                if (!valueClass.isPrimitive()
                        && !valueClass.getName().startsWith("java.")
                        && allowTitleFallback
                        && hasPlusMenuTextField(value, title)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasPlusMenuTextField(Object target, String title) {
        for (Field field : KavaReflector.declaredFields(target.getClass())) {
            if (KavaReflector.isStatic(field)) continue;
            Object value = KavaReflector.readField(field, target);
            if (value instanceof CharSequence && title.contentEquals((CharSequence) value)) {
                return true;
            }
            if (value instanceof String && title.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private Object findNestedItemObject(Object wrapper) {
        if (wrapper == null) return null;
        for (Field field : KavaReflector.declaredFields(wrapper.getClass())) {
            if (KavaReflector.isStatic(field)) continue;
            Object value = KavaReflector.readField(field, wrapper);
            if (value == null) continue;
            if (hasIntInstanceFields(value.getClass())) {
                return value;
            }
        }
        return null;
    }

    private boolean hasIntInstanceFields(Class<?> cl) {
        for (Field field : KavaReflector.declaredFields(cl)) {
            if (!KavaReflector.isStatic(field) && field.getType() == int.class) {
                return true;
            }
        }
        return false;
    }

    private Object createPlusMenuItem(Object sampleItem, int itemId, String title) {
        Class<?> itemClass = sampleItem.getClass();
        Object[] args = new Object[] {
                itemId,
                title,
                "",
                0,
                0
        };
        Object item = KavaReflector.newInstanceByArgs(itemClass, args);
        if (item != null) return item;

        Object[] shortArgs = new Object[] {
                itemId,
                title,
                "",
                0
        };
        return KavaReflector.newInstanceByArgs(itemClass, shortArgs);
    }

    private Object createPlusMenuWrapper(Class<?> wrapperClass, Object item) {
        return KavaReflector.newInstanceByArgs(wrapperClass, new Object[] { item });
    }

    private void ensurePlusMenuAdapterViewHook(BaseAdapter adapter) {
        if (adapter == null) return;
        Class<?> adapterClass = adapter.getClass();
        synchronized (plusMenuAdapterViewHookedClasses) {
            if (plusMenuAdapterViewHookedClasses.contains(adapterClass)) return;
            Method getViewMethod = findAdapterGetViewMethod(adapterClass);
            if (getViewMethod == null) return;
            HookRegistry.get().hook(getViewMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        Object result = param.getResult();
                        if (!(result instanceof View)) return;
                        int position = (int) param.args[0];
                        Object helper = findPlusMenuHelperFieldValue(param.thisObject);
                        int itemId = readPlusMenuItemId(helper, position);
                        if (itemId == Integer.MIN_VALUE) return;
                        applyPlusMenuIcon((View) result, itemId);
                    } catch (Throwable e) {
                        h.Hchat.utils.HLog.e(TAG + " [PlusMenu] 设置菜单图标失败: " + e.getMessage(), e);
                    }
                }
            });
            plusMenuAdapterViewHookedClasses.add(adapterClass);
        }
    }

    private Method findAdapterGetViewMethod(Class<?> adapterClass) {
        Class<?> current = adapterClass;
        while (current != null && current != Object.class) {
            for (Method method : KavaReflector.declaredMethods(current)) {
                Class<?>[] p = method.getParameterTypes();
                if ("getView".equals(method.getName())
                        && !KavaReflector.isStatic(method)
                        && p.length == 3
                        && p[0] == int.class
                        && View.class.isAssignableFrom(p[1])
                        && ViewGroup.class.isAssignableFrom(p[2])
                        && View.class.isAssignableFrom(method.getReturnType())) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Object findPlusMenuHelperFieldValue(Object adapter) {
        if (adapter == null || dex.plusSubMenuHelperClass == null) return null;
        Class<?> current = adapter.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                if (!KavaReflector.isStatic(field)
                        && dex.plusSubMenuHelperClass.isAssignableFrom(field.getType())) {
                    Object value = KavaReflector.readField(field, adapter);
                    if (value != null) return value;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private BaseAdapter findBaseAdapterFieldValue(Object helper) {
        if (helper == null) return null;
        Class<?> current = helper.getClass();
        while (current != null && current != Object.class) {
            for (Field field : KavaReflector.declaredFields(current)) {
                if (!KavaReflector.isStatic(field)
                        && BaseAdapter.class.isAssignableFrom(field.getType())) {
                    Object value = KavaReflector.readField(field, helper);
                    if (value instanceof BaseAdapter) {
                        return (BaseAdapter) value;
                    }
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void hookPlusMenuPopulateAndShowMethods(Class<?> helperClass) {
        HashSet<Method> methods = new HashSet<>();
        for (Method populateMethod : findPlusMenuPopulateMethods(helperClass)) {
            if (!methods.add(populateMethod)) continue;
            HookRegistry.get().hook(populateMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    appendPlusMenuItemSafely(param.thisObject);
                }
            });
        }

        for (Method showMethod : findPlusMenuBaseShowMethods(helperClass)) {
            if (!methods.add(showMethod)) continue;
            HookRegistry.get().hook(showMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    appendPlusMenuItemSafely(param.thisObject);
                }
            });
        }
    }

    private void appendPlusMenuItemSafely(Object helper) {
        try {
            BaseAdapter adapter = findBaseAdapterFieldValue(helper);
            appendEnabledPlusMenuItems(helper, adapter);
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " [PlusMenu] 显示时添加入口失败: " + e.getMessage(), e);
        }
    }

    private HashSet<Method> findPlusMenuPopulateMethods(Class<?> helperClass) {
        HashSet<Method> result = new HashSet<>();
        if (helperClass == null) return result;
        for (Method method : KavaReflector.declaredMethods(helperClass)) {
            if (!KavaReflector.isStatic(method)
                    && method.getReturnType() == boolean.class
                    && method.getParameterCount() == 0) {
                result.add(method);
            }
        }
        return result;
    }

    private HashSet<Method> findPlusMenuBaseShowMethods(Class<?> helperClass) {
        HashSet<Method> result = new HashSet<>();
        Class<?> current = helperClass == null ? null : helperClass.getSuperclass();
        while (current != null && current != Object.class) {
            for (Method method : KavaReflector.declaredMethods(current)) {
                Class<?>[] p = method.getParameterTypes();
                if (!KavaReflector.isStatic(method)
                        && method.getReturnType() == boolean.class
                        && (p.length == 0 || (p.length == 1 && p[0] == int.class))) {
                    result.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return result;
    }

    private void applyHchatPlusMenuIcon(View rowView) {
        ImageView imageView = findFirstImageView(rowView);
        if (imageView == null) return;
        imageView.setVisibility(View.VISIBLE);
        imageView.setImageTintList(null);
        imageView.setColorFilter(null);
        imageView.setAlpha(1.0f);
        imageView.setImageDrawable(new HchatAgentIconDrawable(
                Color.WHITE,
                HchatAgentIconDrawable.Frame.ROUNDED_RECTANGLE));
    }

    private void applyPlusMenuIcon(View rowView, int itemId) {
        if (itemId == QUICK_TERMINATE_PLUS_MENU_ID) {
            applyQuickTerminatePowerIcon(rowView);
        } else {
            applyHchatPlusMenuIcon(rowView);
        }
    }

    private void applyQuickTerminatePowerIcon(View rowView) {
        ImageView imageView = findFirstImageView(rowView);
        if (imageView == null) return;
        Drawable icon = rowView.getContext().getDrawable(android.R.drawable.ic_lock_power_off);
        if (icon == null) return;
        icon = icon.mutate();
        icon.setTint(Color.WHITE);
        imageView.setVisibility(View.VISIBLE);
        imageView.setAlpha(1.0f);
        imageView.setImageDrawable(icon);
        imageView.setImageTintMode(PorterDuff.Mode.SRC_IN);
        imageView.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        imageView.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
    }

    private ImageView findFirstImageView(View view) {
        if (view instanceof ImageView) {
            return (ImageView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = findFirstImageView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private TextView findFirstTextView(View view) {
        if (view instanceof TextView) {
            return (TextView) view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView found = findFirstTextView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void dismissPlusMenu(Object helper) {
        try {
            Object result = KavaReflector.invokeMethod(helper, "a");
            logDetail("[PlusMenu] dismiss result=" + result);
        } catch (Throwable ignored) {
        }
    }

    // ========================================================================
    // 设置页
    // ========================================================================

    private void showModuleSettingsPage(Context context) {
        SettingsUI.show(context);
    }

    private void showScriptPluginAgentPage(Context context) {
        SettingsUI.showScriptPluginAgent(context);
    }

    private void logDetail(String message) {
        if (VERBOSE) {
            XposedBridge.log(TAG + " " + message);
        }
    }
}
