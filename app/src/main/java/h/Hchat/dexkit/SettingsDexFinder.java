package h.Hchat.dexkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseArray;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;

import h.Hchat.hooks.api.runtime.WeChatVersionApi;
import h.Hchat.preferences.HchatStorage;
import h.Hchat.utils.KavaReflector;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * SettingsDexFinder - 使用 DexKit 解析微信设置页相关类和方法
 *
 * 逐项对应 WeKit 的 WeSettingsInjector.resolveDex()：
 * - Preference.setKey / setTitle / getKey / addPreference
 * - SettingItemClassesProvider / BaseSettingItem / SettingLocation
 * - SettingsUI (旧版) / MainSettingsUI (新版)
 * - SettingGroupAccountInfo 方法（返回 1 的那个）
 */
public class SettingsDexFinder {

    private static final String TAG = "[Hchat:SettingsDex]";
    private static final String PREF_CLASS = "com.tencent.mm.ui.base.preference.Preference";
    private static final String CACHE_PREFS = "Hchat_settings_dex_cache";
    private static final String CACHE_COMPLETE = "cache.complete";
    private static final String CACHE_KEY = "cache.key";
    private static final String CACHE_CORE_COMPLETE = "cache.core.complete";
    private static final String CACHE_PLUS_COMPLETE = "cache.plus.complete";
    private static final boolean VERBOSE = false;

    private final DexKitBridge dexKit;
    private final ClassLoader classLoader;
    private final SharedPreferences cachePrefs;
    private final String runtimeCacheKey;
    private boolean resolutionFailed;

    private static final String[] CORE_CACHE_FIELDS = {
            "preferenceClass", "iconPreferenceClass", "methodSetKey", "methodSetTitle",
            "methodGetKey", "methodAddPref", "settingItemClassesProviderClass",
            "baseSettingItemClass", "settingLocationClass", "settingGroupMainClass",
            "settingGroupAccountInfoClass", "settingGroupPersonalInfoClass",
            "settingAdditionHeaderSearchClass", "baseSettingPrefUIClass",
            "baseSettingUIClass", "mainSettingsUIClass", "methodAccountInfoReturns1",
            "methodAccountInfoSettingKey", "settingsUIClass"
    };
    private static final String[] PLUS_CACHE_FIELDS = {
            "plusSubMenuHelperClass", "plusSubMenuAdapterMethod",
            "plusSubMenuOnItemClickMethod"
    };

    // ===== 旧版 Preference 框架 =====
    public Class<?> preferenceClass;
    public Class<?> iconPreferenceClass;
    public Method methodSetKey;
    public Method methodSetTitle;
    public Method methodGetKey;
    public Method methodAddPref;

    // ===== 新版 SettingItem 框架 (>= 8.0.49) =====
    public Class<?> settingItemClassesProviderClass;  // 用 "Repairer_Setting" 定位
    public Class<?> baseSettingItemClass;              // 用 "", "activity", "context", "intent" 定位
    public Class<?> settingLocationClass;              // 用 "SettingLocation(parentGroup=" 定位
    public Class<?> settingGroupMainClass;             // com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain
    public Class<?> settingGroupAccountInfoClass;
    public Class<?> settingGroupPersonalInfoClass;
    public Class<?> settingAdditionHeaderSearchClass;
    public Class<?> baseSettingPrefUIClass;
    public Class<?> baseSettingUIClass;
    public Class<?> mainSettingsUIClass;
    public Method methodAccountInfoReturns1;            // SettingGroupAccountInfo 中返回 1 的方法
    public Method methodAccountInfoSettingKey;          // SettingGroupAccountInfo.v7(): 设置项 key

    // ===== LauncherUI 右上角加号菜单 =====
    public Class<?> plusSubMenuHelperClass;              // MicroMsg.PlusSubMenuHelper
    public Method plusSubMenuAdapterMethod;              // 返回 BaseAdapter 的菜单适配器方法
    public Method plusSubMenuOnItemClickMethod;          // onItemClick(AdapterView, View, int, long)

    // ===== 旧版 SettingsUI =====
    public Class<?> settingsUIClass;

    public SettingsDexFinder(DexKitBridge dexKit, ClassLoader classLoader) {
        this(dexKit, classLoader, null);
    }

    public SettingsDexFinder(DexKitBridge dexKit, ClassLoader classLoader, Context context) {
        this.dexKit = dexKit;
        this.classLoader = classLoader;
        this.cachePrefs = context != null ? HchatStorage.preferences(context, CACHE_PREFS) : null;
        this.runtimeCacheKey = WeChatVersionApi.buildCacheKey(context, classLoader);
    }

    public void resolveAll() {
        resolveAll(true);
    }

    public void resolveAll(boolean includePlusMenu) {
        boolean coreReady = loadCache(false);
        if (!coreReady) {
            resolutionFailed = false;
            resolveMissingTargets(false);
            if (!resolutionFailed) saveCache(false);
        }
        if (includePlusMenu && !loadCache(true)) {
            resolutionFailed = false;
            resolvePlusSubMenuHelper();
            if (!resolutionFailed) saveCache(true);
        }
        logDetail("设置Dex 缓存处理完成: " + shortKey(runtimeCacheKey));
    }

    private void resolveMissingTargets(boolean includePlusMenu) {
        resolvePreferenceClass();
        resolveIconPreference();
        resolveSetKey();
        resolveSetTitle();
        resolveGetKey();
        resolveAddPreference();
        resolveSettingsActivity();
        resolveModernFramework();
        if (includePlusMenu) {
            resolvePlusSubMenuHelper();
        }
    }

    // ===== Preference 基类 =====
    private void resolvePreferenceClass() {
        if (preferenceClass != null) return;
        try {
            preferenceClass = KavaReflector.loadClass(PREF_CLASS, classLoader);
            logDetail("Preference: " + PREF_CLASS);
        } catch (Throwable e) {
            logDetail("Preference 未找到: " + e.getMessage());
        }
    }

    // ===== IconPreference =====
    private void resolveIconPreference() {
        if (iconPreferenceClass != null) return;
        try {
            iconPreferenceClass = KavaReflector.loadClass(
                    "com.tencent.mm.ui.base.preference.IconPreference", classLoader);
            logDetail("IconPreference: OK");
        } catch (Throwable e) {
            logDetail("IconPreference 未找到: " + e.getMessage());
        }
    }

    // ===== Preference.setKey(String) void =====
    private void resolveSetKey() {
        if (methodSetKey != null) return;
        if (preferenceClass == null) return;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.declaredClass(PREF_CLASS);
            mm.returnType("void");
            mm.paramTypes("java.lang.String");
            mm.usingStrings(Arrays.asList("Preference"));
            fm.matcher(mm);
            List<MethodData> results = dexKit.findMethod(fm);
            for (MethodData md : results) {
                Class<?> cl = KavaReflector.loadClass(md.getClassName(), classLoader);
                methodSetKey = KavaReflector.findMethod(cl, md.getName(), String.class);
                logDetail("setKey: " + md.getName());
                break;
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " setKey 失败: " + e.getMessage(), e);
        }
    }

    // ===== Preference.setTitle(CharSequence) void =====
    private void resolveSetTitle() {
        if (methodSetTitle != null) return;
        if (preferenceClass == null) return;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.className(PREF_CLASS);
            fc.matcher(cm);
            List<ClassData> classes = dexKit.findClass(fc);
            for (ClassData cd : classes) {
                FindMethod fm2 = new FindMethod();
                MethodMatcher mm2 = new MethodMatcher();
                mm2.returnType("void");
                mm2.paramTypes("java.lang.CharSequence");
                fm2.matcher(mm2);
                List<MethodData> candidates = dexKit.findMethod(fm2);
                // 过滤只保留声明在 Preference 中的
                List<MethodData> filtered = new java.util.ArrayList<>();
                for (MethodData md : candidates) {
                    if (PREF_CLASS.equals(md.getClassName())) {
                        filtered.add(md);
                    }
                }
                if (!filtered.isEmpty()) {
                    MethodData last = filtered.get(filtered.size() - 1);
                    Class<?> cl = KavaReflector.loadClass(last.getClassName(), classLoader);
                    methodSetTitle = KavaReflector.findMethod(cl, last.getName(), CharSequence.class);
                    logDetail("setTitle: " + last.getName());
                }
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " setTitle 失败: " + e.getMessage(), e);
        }
    }

    // ===== Preference.getKey() String =====
    private void resolveGetKey() {
        if (methodGetKey != null) return;
        if (preferenceClass == null) return;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.declaredClass(PREF_CLASS);
            mm.paramCount(0);
            mm.returnType("java.lang.String");
            fm.matcher(mm);
            List<MethodData> candidates = dexKit.findMethod(fm);
            for (MethodData md : candidates) {
                if (!"toString".equals(md.getName())) {
                    Class<?> cl = KavaReflector.loadClass(md.getClassName(), classLoader);
                    methodGetKey = KavaReflector.findMethod(cl, md.getName());
                    logDetail("getKey: " + md.getName());
                    break;
                }
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " getKey 失败: " + e.getMessage(), e);
        }
    }

    // ===== Adapter.addPreference(Preference, int) =====
    private void resolveAddPreference() {
        if (methodAddPref != null) return;
        if (preferenceClass == null) return;
        try {
            // 找 PreferenceAdapter: 继承 BaseAdapter, 在 com.tencent.mm.ui.base.preference 包下
            FindClass fc2 = new FindClass();
            ClassMatcher cm2 = new ClassMatcher();
            cm2.superClass("android.widget.BaseAdapter");
            fc2.searchPackages("com.tencent.mm.ui.base.preference");
            fc2.matcher(cm2);
            List<ClassData> allClasses = dexKit.findClass(fc2);
            String adapterClassName = null;
            for (ClassData cd : allClasses) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    // 验证: 有 public getView(3 params) 和 <init>(3 params)
                    boolean hasGetView = false;
                    boolean hasInit = false;
                    for (Method m : KavaReflector.declaredMethods(cl)) {
                        if ("getView".equals(m.getName())
                                && KavaReflector.isPublic(m)
                                && m.getParameterCount() == 3) {
                            hasGetView = true;
                        }
                    }
                    for (java.lang.reflect.Constructor<?> c : KavaReflector.declaredConstructors(cl)) {
                        if (c.getParameterCount() == 3) {
                            hasInit = true;
                        }
                    }
                    if (hasGetView && hasInit) {
                        adapterClassName = cd.getName();
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            if (adapterClassName == null) {
                logDetail("addPreference: Adapter 未找到");
                return;
            }

            // 在该 Adapter 中搜索 addPreference(Preference, int)
            FindMethod fm3 = new FindMethod();
            MethodMatcher mm3 = new MethodMatcher();
            mm3.declaredClass(adapterClassName);
            mm3.paramCount(2);
            mm3.returnType("void");
            fm3.searchPackages("com.tencent.mm.ui.base.preference");
            fm3.matcher(mm3);
            List<MethodData> results = dexKit.findMethod(fm3);
            for (MethodData md : results) {
                try {
                    Class<?> cl = KavaReflector.loadClass(md.getClassName(), classLoader);
                    methodAddPref = KavaReflector.findMethod(cl, md.getName(), preferenceClass, int.class);
                    if (methodAddPref == null) {
                        for (Method m : KavaReflector.declaredMethods(cl)) {
                            if (md.getName().equals(m.getName())
                                    && m.getParameterCount() == 2
                                    && m.getReturnType() == void.class) {
                                methodAddPref = m;
                                break;
                            }
                        }
                    }
                    if (methodAddPref != null) {
                        logDetail("addPreference: " + md.getClassName() + "." + md.getName());
                    }
                    if (methodAddPref != null) break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " addPreference 失败: " + e.getMessage(), e);
        }
    }

    // ===== SettingsUI / MainSettingsUI =====
    private void resolveSettingsActivity() {
        if (settingsUIClass != null && mainSettingsUIClass != null) return;
        try {
            settingsUIClass = KavaReflector.loadClass(
                    "com.tencent.mm.plugin.setting.ui.setting.SettingsUI", classLoader);
            logDetail("SettingsUI: OK");
        } catch (Throwable e) {
            logDetail("SettingsUI: 未找到");
        }
        try {
            mainSettingsUIClass = KavaReflector.loadClass(
                    "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI", classLoader);
            logDetail("MainSettingsUI: OK");
        } catch (Throwable e) {
            logDetail("MainSettingsUI: 未找到");
        }
    }

    // ===== 新版框架类 (WeKit injectModernMethod2 需要) =====
    private void resolveModernFramework() {
        // SettingGroupMain
        settingGroupMainClass = forNameOrNull(
                "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain");
        if (settingGroupMainClass != null) logDetail("SettingGroupMain: OK");

        // SettingGroupAccountInfo
        settingGroupAccountInfoClass = forNameOrNull(
                "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupAccountInfo");

        // SettingGroupPersonalInfo
        settingGroupPersonalInfoClass = forNameOrNull(
                "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupPersonalInfo");

        // SettingAdditionHeaderSearch
        settingAdditionHeaderSearchClass = forNameOrNull(
                "com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingAdditionHeaderSearch");

        // BaseSettingPrefUI
        baseSettingPrefUIClass = forNameOrNull(
                "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI");

        // BaseSettingUI
        baseSettingUIClass = forNameOrNull(
                "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingUI");

        // 用 DexKit 解析混淆类
        resolveSettingItemClassesProvider();
        resolveBaseSettingItem();
        resolveSettingLocation();
        resolveAccountInfoReturns1();
        resolveAccountInfoSettingKey();
    }

    // ===== SettingItemClassesProvider (用 "Repairer_Setting" 定位) =====
    private void resolveSettingItemClassesProvider() {
        if (settingItemClassesProviderClass != null) return;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.usingEqStrings("Repairer_Setting");
            fc.matcher(cm);
            List<ClassData> results = dexKit.findClass(fc);
            Class<?> fallback = null;
            for (ClassData cd : results) {
                Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                if (fallback == null) fallback = cl;
                if (hasNoArgMapReturningMethod(cl)) {
                    settingItemClassesProviderClass = cl;
                    break;
                }
            }
            if (settingItemClassesProviderClass == null) {
                settingItemClassesProviderClass = fallback;
            }
            if (settingItemClassesProviderClass != null) {
                logDetail("SettingItemClassesProvider: "
                        + settingItemClassesProviderClass.getName());
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " SettingItemClassesProvider 失败: " + e.getMessage(), e);
        }
    }

    // ===== BaseSettingItem (用 "activity", "context", "intent" 定位) =====
    private void resolveBaseSettingItem() {
        if (baseSettingItemClass != null) return;
        try {
            baseSettingItemClass = resolveBaseSettingItemFromKnownItem();
            if (baseSettingItemClass != null) {
                logDetail("BaseSettingItem: " + baseSettingItemClass.getName());
                return;
            }

            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.usingEqStrings("", "activity", "context", "intent");
            fc.matcher(cm);
            List<ClassData> results = dexKit.findClass(fc);
            Class<?> fallback = null;
            for (ClassData cd : results) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    // 验证：构造函数接受 AppCompatActivity 参数
                    boolean hasAppCompactCtor = false;
                    for (java.lang.reflect.Constructor<?> c : KavaReflector.declaredConstructors(cl)) {
                        Class<?>[] pTypes = c.getParameterTypes();
                        if (pTypes.length == 1
                                && pTypes[0].getName().contains("AppCompatActivity")) {
                            hasAppCompactCtor = true;
                            break;
                        }
                    }
                    if (hasAppCompactCtor) {
                        if (fallback == null) fallback = cl;
                        if (implementsNewTipsInterface(cl)) {
                            baseSettingItemClass = cl;
                            break;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            if (baseSettingItemClass == null) {
                baseSettingItemClass = fallback;
            }
            if (baseSettingItemClass != null) {
                logDetail("BaseSettingItem: " + baseSettingItemClass.getName());
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " BaseSettingItem 失败: " + e.getMessage(), e);
        }
    }

    private Class<?> resolveBaseSettingItemFromKnownItem() {
        if (settingGroupAccountInfoClass == null) return null;
        Class<?> current = settingGroupAccountInfoClass.getSuperclass();
        Class<?> fallback = null;
        while (current != null && current != Object.class) {
            if (hasAppCompatActivityConstructor(current)) {
                if (fallback == null) fallback = current;
                if (hasSettingItemContract(current)) {
                    return current;
                }
            }
            current = current.getSuperclass();
        }
        return fallback;
    }

    private boolean hasAppCompatActivityConstructor(Class<?> cl) {
        try {
            for (java.lang.reflect.Constructor<?> c : KavaReflector.declaredConstructors(cl)) {
                Class<?>[] pTypes = c.getParameterTypes();
                if (pTypes.length == 1
                        && pTypes[0].getName().contains("AppCompatActivity")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private boolean hasSettingItemContract(Class<?> cl) {
        return countNoArgMethodsInHierarchy(cl, String.class) > 0
                && countNoArgMethodsInHierarchy(cl, Class.class) > 0
                && countNoArgMethodsInHierarchy(cl, int.class) > 0;
    }

    private int countNoArgMethodsInHierarchy(Class<?> cl, Class<?> returnType) {
        int count = 0;
        Class<?> current = cl;
        while (current != null && current != Object.class) {
            for (Method m : KavaReflector.declaredMethods(current)) {
                if (m.getParameterCount() == 0 && m.getReturnType() == returnType) {
                    count++;
                }
            }
            current = current.getSuperclass();
        }
        return count;
    }

    // ===== SettingLocation (用 "SettingLocation(parentGroup=" 定位) =====
    private void resolveSettingLocation() {
        if (settingLocationClass != null) return;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.usingEqStrings("SettingLocation(parentGroup=", ", frontItem=");
            fc.matcher(cm);
            List<ClassData> results = dexKit.findClass(fc);
            for (ClassData cd : results) {
                settingLocationClass = KavaReflector.loadClass(cd.getName(), classLoader);
                logDetail("SettingLocation: " + cd.getName());
                break;
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " SettingLocation 失败: " + e.getMessage(), e);
        }
    }

    // ===== SettingGroupAccountInfo 中返回 1 的方法 =====
    private void resolveAccountInfoReturns1() {
        if (methodAccountInfoReturns1 != null) return;
        if (settingGroupAccountInfoClass == null) return;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.declaredClass(settingGroupAccountInfoClass.getName());
            mm.usingNumbers(1);
            mm.returnType("int");
            fm.matcher(mm);
            List<MethodData> results = dexKit.findMethod(fm);
            for (MethodData md : results) {
                methodAccountInfoReturns1 = KavaReflector.findMethod(
                        KavaReflector.loadClass(md.getClassName(), classLoader), md.getName());
                logDetail("AccountInfo.returns1: " + md.getName());
                break;
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " AccountInfo.returns1 失败: " + e.getMessage(), e);
        }
    }

    // ===== SettingGroupAccountInfo 中返回 SettingGroup_Main_AccountInfo 的 key 方法 =====
    private void resolveAccountInfoSettingKey() {
        if (methodAccountInfoSettingKey != null) return;
        if (settingGroupAccountInfoClass == null) return;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.declaredClass(settingGroupAccountInfoClass.getName());
            mm.paramCount(0);
            mm.returnType("java.lang.String");
            mm.usingStrings(Arrays.asList("SettingGroup_Main_AccountInfo"));
            fm.matcher(mm);
            List<MethodData> results = dexKit.findMethod(fm);
            for (MethodData md : results) {
                methodAccountInfoSettingKey = KavaReflector.findMethod(
                        KavaReflector.loadClass(md.getClassName(), classLoader), md.getName());
                logDetail("AccountInfo.settingKey: " + md.getName());
                break;
            }
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " AccountInfo.settingKey 失败: " + e.getMessage(), e);
        }
    }

    private Class<?> forNameOrNull(String name) {
        try {
            return KavaReflector.loadClass(name, classLoader);
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean hasNoArgMapReturningMethod(Class<?> cl) {
        for (Method m : KavaReflector.declaredMethods(cl)) {
            if (m.getParameterCount() == 0
                    && java.util.Map.class.isAssignableFrom(m.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    private boolean implementsNewTipsInterface(Class<?> cl) {
        for (Class<?> itf : cl.getInterfaces()) {
            if (itf.getName().startsWith("com.tencent.mm.plugin.newtips.model")) {
                return true;
            }
        }
        return false;
    }

    // ===== LauncherUI 右上角加号菜单 (MicroMsg.PlusSubMenuHelper) =====
    private void resolvePlusSubMenuHelper() {
        if (plusSubMenuHelperClass != null
                && plusSubMenuAdapterMethod != null
                && plusSubMenuOnItemClickMethod != null) {
            return;
        }
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.usingStrings(Arrays.asList("MicroMsg.PlusSubMenuHelper"));
            fc.matcher(cm);
            List<ClassData> results = dexKit.findClass(fc);
            for (ClassData cd : results) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    Method clickMethod = findPlusSubMenuClickMethod(cl);
                    Method adapterMethod = findPlusSubMenuAdapterMethod(cl);
                    if (clickMethod == null || adapterMethod == null) continue;
                    if (!hasInstanceFieldAssignableTo(cl, SparseArray.class)) continue;
                    if (!hasInstanceFieldAssignableTo(cl, Context.class)) continue;

                    plusSubMenuHelperClass = cl;
                    plusSubMenuOnItemClickMethod = clickMethod;
                    plusSubMenuAdapterMethod = adapterMethod;
                    logDetail("PlusSubMenuHelper: " + cl.getName()
                            + ", adapter=" + adapterMethod.getName());
                    return;
                } catch (Throwable ignored) {
                }
            }
            logDetail("PlusSubMenuHelper 未找到");
        } catch (Throwable e) {
            resolutionFailed = true;
            h.Hchat.utils.HLog.e(TAG + " PlusSubMenuHelper 失败: " + e.getMessage(), e);
        }
    }

    private Method findPlusSubMenuClickMethod(Class<?> cl) {
        for (Method m : KavaReflector.declaredMethods(cl)) {
            Class<?>[] p = m.getParameterTypes();
            if ("onItemClick".equals(m.getName())
                    && m.getReturnType() == void.class
                    && p.length == 4
                    && AdapterView.class.isAssignableFrom(p[0])
                    && View.class.isAssignableFrom(p[1])
                    && p[2] == int.class
                    && p[3] == long.class) {
                return m;
            }
        }
        return null;
    }

    private Method findPlusSubMenuAdapterMethod(Class<?> cl) {
        for (Method m : KavaReflector.declaredMethods(cl)) {
            if (m.getParameterCount() == 0
                    && BaseAdapter.class.isAssignableFrom(m.getReturnType())) {
                return m;
            }
        }
        return null;
    }

    private boolean hasInstanceFieldAssignableTo(Class<?> cl, Class<?> targetType) {
        for (Field field : KavaReflector.declaredFields(cl)) {
            if (!KavaReflector.isStatic(field)
                    && targetType.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private void logDetail(String message) {
        if (VERBOSE) {
            XposedBridge.log(TAG + " " + message);
        }
    }

    private boolean loadCache(boolean includePlusMenu) {
        if (cachePrefs == null || runtimeCacheKey == null || runtimeCacheKey.length() == 0) return false;
        try {
            String savedKey = cachePrefs.getString(CACHE_KEY, "");
            if (!DexCacheIdentity.sameRuntime(savedKey, runtimeCacheKey)) {
                resetCacheForRuntimeKey();
                return false;
            }
            boolean coreComplete = cachePrefs.getBoolean(CACHE_CORE_COMPLETE,
                    cachePrefs.getBoolean(CACHE_COMPLETE, false));
            boolean plusComplete = cachePrefs.getBoolean(CACHE_PLUS_COMPLETE,
                    cachePrefs.getBoolean(CACHE_COMPLETE, false));
            loadAllCachedTargets();
            if (!coreComplete || !cacheFieldsValid(CORE_CACHE_FIELDS)) return false;
            return !includePlusMenu || (plusComplete && cacheFieldsValid(PLUS_CACHE_FIELDS));
        } catch (Throwable e) {
            logDetail("读取设置Dex缓存失败: " + e.getMessage());
            return false;
        }
    }

    private void loadAllCachedTargets() {
        preferenceClass = loadClass("preferenceClass");
        iconPreferenceClass = loadClass("iconPreferenceClass");
        methodSetKey = loadMethod("methodSetKey");
        methodSetTitle = loadMethod("methodSetTitle");
        methodGetKey = loadMethod("methodGetKey");
        methodAddPref = loadMethod("methodAddPref");
        settingItemClassesProviderClass = loadClass("settingItemClassesProviderClass");
        baseSettingItemClass = loadClass("baseSettingItemClass");
        settingLocationClass = loadClass("settingLocationClass");
        settingGroupMainClass = loadClass("settingGroupMainClass");
        settingGroupAccountInfoClass = loadClass("settingGroupAccountInfoClass");
        settingGroupPersonalInfoClass = loadClass("settingGroupPersonalInfoClass");
        settingAdditionHeaderSearchClass = loadClass("settingAdditionHeaderSearchClass");
        baseSettingPrefUIClass = loadClass("baseSettingPrefUIClass");
        baseSettingUIClass = loadClass("baseSettingUIClass");
        mainSettingsUIClass = loadClass("mainSettingsUIClass");
        methodAccountInfoReturns1 = loadMethod("methodAccountInfoReturns1");
        methodAccountInfoSettingKey = loadMethod("methodAccountInfoSettingKey");
        plusSubMenuHelperClass = loadClass("plusSubMenuHelperClass");
        plusSubMenuAdapterMethod = loadMethod("plusSubMenuAdapterMethod");
        plusSubMenuOnItemClickMethod = loadMethod("plusSubMenuOnItemClickMethod");
        settingsUIClass = loadClass("settingsUIClass");
    }

    private boolean cacheFieldsValid(String[] fields) {
        for (String key : fields) {
            String value = cachePrefs.getString(key, null);
            if (value == null) return false;
            if (!value.isEmpty() && cachedTarget(key) == null) return false;
        }
        return true;
    }

    private Object cachedTarget(String key) {
        if ("preferenceClass".equals(key)) return preferenceClass;
        if ("iconPreferenceClass".equals(key)) return iconPreferenceClass;
        if ("methodSetKey".equals(key)) return methodSetKey;
        if ("methodSetTitle".equals(key)) return methodSetTitle;
        if ("methodGetKey".equals(key)) return methodGetKey;
        if ("methodAddPref".equals(key)) return methodAddPref;
        if ("settingItemClassesProviderClass".equals(key)) return settingItemClassesProviderClass;
        if ("baseSettingItemClass".equals(key)) return baseSettingItemClass;
        if ("settingLocationClass".equals(key)) return settingLocationClass;
        if ("settingGroupMainClass".equals(key)) return settingGroupMainClass;
        if ("settingGroupAccountInfoClass".equals(key)) return settingGroupAccountInfoClass;
        if ("settingGroupPersonalInfoClass".equals(key)) return settingGroupPersonalInfoClass;
        if ("settingAdditionHeaderSearchClass".equals(key)) return settingAdditionHeaderSearchClass;
        if ("baseSettingPrefUIClass".equals(key)) return baseSettingPrefUIClass;
        if ("baseSettingUIClass".equals(key)) return baseSettingUIClass;
        if ("mainSettingsUIClass".equals(key)) return mainSettingsUIClass;
        if ("methodAccountInfoReturns1".equals(key)) return methodAccountInfoReturns1;
        if ("methodAccountInfoSettingKey".equals(key)) return methodAccountInfoSettingKey;
        if ("plusSubMenuHelperClass".equals(key)) return plusSubMenuHelperClass;
        if ("plusSubMenuAdapterMethod".equals(key)) return plusSubMenuAdapterMethod;
        if ("plusSubMenuOnItemClickMethod".equals(key)) return plusSubMenuOnItemClickMethod;
        if ("settingsUIClass".equals(key)) return settingsUIClass;
        return null;
    }

    private void resetCacheForRuntimeKey() {
        try {
            cachePrefs.edit().clear().putString(CACHE_KEY, runtimeCacheKey).commit();
        } catch (Throwable ignored) {
        }
    }

    private void saveCache(boolean plusOnly) {
        if (cachePrefs == null || runtimeCacheKey == null || runtimeCacheKey.length() == 0) return;
        try {
            String savedKey = cachePrefs.getString(CACHE_KEY, "");
            SharedPreferences.Editor editor = cachePrefs.edit();
            if (!DexCacheIdentity.sameRuntime(savedKey, runtimeCacheKey)) {
                editor.clear();
            }
            if (savedKey == null || savedKey.isEmpty() || !DexCacheIdentity.sameRuntime(savedKey, runtimeCacheKey)) {
                editor.putString(CACHE_KEY, runtimeCacheKey);
            }
            if (!plusOnly) {
                putClass(editor, "preferenceClass", preferenceClass);
                putClass(editor, "iconPreferenceClass", iconPreferenceClass);
                putMethod(editor, "methodSetKey", methodSetKey);
                putMethod(editor, "methodSetTitle", methodSetTitle);
                putMethod(editor, "methodGetKey", methodGetKey);
                putMethod(editor, "methodAddPref", methodAddPref);
                putClass(editor, "settingItemClassesProviderClass", settingItemClassesProviderClass);
                putClass(editor, "baseSettingItemClass", baseSettingItemClass);
                putClass(editor, "settingLocationClass", settingLocationClass);
                putClass(editor, "settingGroupMainClass", settingGroupMainClass);
                putClass(editor, "settingGroupAccountInfoClass", settingGroupAccountInfoClass);
                putClass(editor, "settingGroupPersonalInfoClass", settingGroupPersonalInfoClass);
                putClass(editor, "settingAdditionHeaderSearchClass", settingAdditionHeaderSearchClass);
                putClass(editor, "baseSettingPrefUIClass", baseSettingPrefUIClass);
                putClass(editor, "baseSettingUIClass", baseSettingUIClass);
                putClass(editor, "mainSettingsUIClass", mainSettingsUIClass);
                putMethod(editor, "methodAccountInfoReturns1", methodAccountInfoReturns1);
                putMethod(editor, "methodAccountInfoSettingKey", methodAccountInfoSettingKey);
                putClass(editor, "settingsUIClass", settingsUIClass);
            }
            if (plusOnly) {
                putClass(editor, "plusSubMenuHelperClass", plusSubMenuHelperClass);
                putMethod(editor, "plusSubMenuAdapterMethod", plusSubMenuAdapterMethod);
                putMethod(editor, "plusSubMenuOnItemClickMethod", plusSubMenuOnItemClickMethod);
            }
            boolean coreComplete = plusOnly
                    ? cachePrefs.getBoolean(CACHE_CORE_COMPLETE, false)
                    : true;
            boolean plusComplete = plusOnly
                    ? true
                    : cachePrefs.getBoolean(CACHE_PLUS_COMPLETE, false);
            editor.putBoolean(plusOnly ? CACHE_PLUS_COMPLETE : CACHE_CORE_COMPLETE, true);
            editor.putBoolean(CACHE_COMPLETE, coreComplete && plusComplete);
            editor.apply();
        } catch (Throwable e) {
            logDetail("保存设置Dex缓存失败: " + e.getMessage());
        }
    }

    private void putClass(SharedPreferences.Editor editor, String key, Class<?> value) {
        editor.putString(key, value != null ? value.getName() : "");
    }

    private void putMethod(SharedPreferences.Editor editor, String key, Method value) {
        editor.putString(key, value != null ? value.getDeclaringClass().getName() + "#" + value.getName() + descriptor(value) : "");
    }

    private String descriptor(Method method) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    private Class<?> loadClass(String key) {
        try {
            String name = cachePrefs != null ? cachePrefs.getString(key, "") : "";
            if (name == null || name.isEmpty()) return null;
            return KavaReflector.loadClass(name, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method loadMethod(String key) {
        try {
            String spec = cachePrefs != null ? cachePrefs.getString(key, "") : "";
            if (spec == null || spec.length() == 0) return null;
            return loadMethodSpec(spec);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Method loadMethodSpec(String spec) {
        try {
            int hash = spec.indexOf('#');
            int left = spec.indexOf('(', hash + 1);
            int right = spec.indexOf(')', left + 1);
            if (hash <= 0 || left <= hash || right < left) return null;
            Class<?> owner = KavaReflector.loadClass(spec.substring(0, hash), classLoader);
            String name = spec.substring(hash + 1, left);
            String paramsText = spec.substring(left + 1, right);
            Class<?>[] params = parseParamTypes(paramsText);
            return KavaReflector.findDeclaredMethod(owner, name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?>[] parseParamTypes(String paramsText) throws ClassNotFoundException {
        if (paramsText == null || paramsText.length() == 0) return new Class<?>[0];
        String[] names = paramsText.split(",");
        Class<?>[] result = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            result[i] = typeOf(names[i].trim());
        }
        return result;
    }

    private Class<?> typeOf(String name) throws ClassNotFoundException {
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        if ("short".equals(name)) return short.class;
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("void".equals(name)) return void.class;
        return KavaReflector.loadClass(name, classLoader);
    }

    private String methodSpec(Method method) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] params = method.getParameterTypes();
        sb.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    private String shortKey(String key) {
        if (key == null) return "";
        return key.length() <= 80 ? key : key.substring(0, 80) + "...";
    }
}
