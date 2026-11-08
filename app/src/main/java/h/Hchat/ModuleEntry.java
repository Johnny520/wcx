package h.Hchat;

import android.app.Application;
import android.content.Context;
import android.os.Process;

import h.Hchat.crash.CrashReportRuntime;
import h.Hchat.dexkit.DexFinder;
import h.Hchat.hooks.api.media.WeChatImageApi;
import h.Hchat.hooks.core.FeatureRegistry;
import h.Hchat.hooks.core.FeatureContext;
import h.Hchat.hooks.core.DexInstallScheduler;
import h.Hchat.hooks.core.FeatureManager;
import h.Hchat.hooks.core.HookRegistry;
import h.Hchat.hooks.items.fakelocation.FakeLocationFeature;
import h.Hchat.hooks.items.custombottombar.CustomBottomBarFeature;
import h.Hchat.hooks.items.conversationtabs.ConversationTabsFeature;
import h.Hchat.hooks.items.hotupdate.DisableHotUpdateFeature;
import h.Hchat.hooks.items.miniprogrambaselib.FakeMiniProgramBaseLibFeature;
import h.Hchat.hooks.items.miniprogramsplashad.SkipGlobalMiniProgramSplashAdsFeature;
import h.Hchat.hooks.items.miniprogramvideoad.SkipMiniProgramVideoAdsFeature;
import h.Hchat.hooks.items.floatingshortcut.FloatingShortcutRuntime;
import h.Hchat.hooks.items.script.ScriptPluginRuntime;
import h.Hchat.hooks.items.script.agent.CodexWebGuard;
import h.Hchat.hooks.items.script.agent.ModuleResourceMenuFallback;
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentLocalReverseTools;
import h.Hchat.hooks.items.tablet.WeChatTabletFeature;
import h.Hchat.loader.utils.NativeLibraryLoader;
import h.Hchat.preferences.ConfigStore;
import h.Hchat.preferences.TermsGate;
import h.Hchat.dexkit.DexBridgeHolder;
import h.Hchat.event.EventBus;
import h.Hchat.ui.UIRegistry;
import h.Hchat.utils.HLog;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 模块入口。
 *
 * 启动流程：
 * 1. handleLoadPackage → 过滤目标包 + 目标进程
 * 2. 小程序子进程安装必要的缓存 Hook 和显式声明的小程序脚本插件
 * 3. 主进程在 Tinker attach 后优先安装自定义底栏 Hook
 * 4. Application.onCreate 后异步初始化完整模块
 *
 * 进程过滤：普通功能只在微信主进程加载；:appbrand* 使用独立的轻量运行时。
 */
public class ModuleEntry implements IXposedHookLoadPackage {
    private static final String TAG = "[Hchat:Entry]";
    private static final String WECHAT_PKG = "com.tencent.mm";
    private static final Object DEXKIT_CREATE_LOCK = new Object();
    private static final Map<String, DexKitBridge> DEXKIT_BRIDGES = new ConcurrentHashMap<>();

    // 全局基础设施实例（模块生命周期内单例）
    private final EventBus eventBus = EventBus.get();
    private final UIRegistry uiRegistry = UIRegistry.get();

    private FeatureManager featureManager;
    private FeatureContext featureContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. 包名过滤
        if (!isWeChatPackage(lpparam.packageName)) return;
        if (!Process.is64Bit()) {
            HLog.e(TAG + " 当前版本仅支持 ARM64 微信进程，跳过32位进程: " + lpparam.processName);
            return;
        }

        // 热更新加载发生在 Tinker attach 阶段，必须早于普通功能初始化安装。
        installHotUpdateEarlyHook(lpparam);

        // 平板模式需要在微信所有进程保持一致，避免升级/降级后登录态校验看到不同设备形态。
        installTabletEarlyHook(lpparam);

        // 2. 子进程只安装明确需要的最小 Hook。
        if (!isMainProcess(lpparam)) {
            if (isAppBrandProcess(lpparam)) {
                installAppBrandProcessHook(lpparam);
            }
            return;
        }

        // 3. Mars CDN 管理器可能早于模块 API 初始化创建，先按稳定类名捕获实例。
        WeChatImageApi.installMarsCdnManagerHook(
                lpparam.classLoader,
                message -> HLog.e("[Hchat:WechatApi] " + message));

        // 底栏在 LauncherUI 首帧前创建，必须早于完整 DexKit 初始化安装 Hook。
        installCustomBottomBarEarlyHook(lpparam);

        // 4. Hook Application.onCreate，等 Application 完全初始化后再启动模块
        HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                Application.class,
                "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Application app = (Application) param.thisObject;
                        if (TermsGate.INSTANCE.isAccepted(app)) {
                            CrashReportRuntime.prepare(app);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Application app = (Application) param.thisObject;
                        if (TermsGate.INSTANCE.isAccepted(app)) {
                            CrashReportRuntime.install(app, ModuleEntry.this.getClass().getClassLoader());
                            // 标签栏先捕获首页根视图，不能等待后台 DexKit 初始化后再挂载。
                            try {
                                ConversationTabsFeature.installEarly(resolveHostClassLoader(app, lpparam));
                            } catch (Throwable e) {
                                HLog.e(TAG + " 标签分组早期安装失败", e);
                            }
                            // 悬浮入口不依赖 DexKit，先注册生命周期，避免错过首个 Activity。
                            try {
                                FloatingShortcutRuntime.INSTANCE.install(app);
                            } catch (Throwable e) {
                                HLog.e(TAG + " 悬浮快捷菜单早期安装失败", e);
                            }
                        }
                        installCustomBottomBarEarly(app, resolveHostClassLoader(app, lpparam));
                        new Thread(() -> initModule(app, lpparam), "Hchat-Init").start();
                    }
                }
        ));
    }

    // ============ 包名与进程判断 ============

    /**
     * 判断是否为目标微信包。
     * 支持 com.tencent.mm 以及以 com.tencent.mm 开头的分身包名。
     */
    private boolean isWeChatPackage(String packageName) {
        return packageName != null && packageName.startsWith(WECHAT_PKG);
    }

    /**
     * 判断是否为主进程。
     * 微信主进程的 processName 等于 packageName。
     */
    private boolean isMainProcess(XC_LoadPackage.LoadPackageParam lpparam) {
        String process = lpparam.processName;
        return process == null || process.equals(lpparam.packageName);
    }

    private boolean isAppBrandProcess(XC_LoadPackage.LoadPackageParam lpparam) {
        String process = lpparam.processName;
        String packageName = lpparam.packageName;
        return process != null && packageName != null && process.startsWith(packageName + ":appbrand");
    }

    private void installAppBrandProcessHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            ClassLoader loader = resolveTinkerClassLoader(param.thisObject);
                            if (loader == null) {
                                loader = resolveHostClassLoader(context, lpparam);
                            }
                            installAppBrandProcessHook(context, loader, lpparam, false);
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 小程序进程早期入口安装失败: " + e.getMessage(), e);
        }
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    Application.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Application app = (Application) param.thisObject;
                            ClassLoader loader = resolveHostClassLoader(app, lpparam);
                            installAppBrandProcessHook(app, loader, lpparam, true);
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 小程序进程兜底入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installAppBrandProcessHook(Context context,
                                            ClassLoader classLoader,
                                            XC_LoadPackage.LoadPackageParam lpparam,
                                            boolean applicationReady) {
        if (!TermsGate.INSTANCE.isAccepted(context)) return;
        boolean failed = false;
        if (FakeMiniProgramBaseLibFeature.isEnabled(context)
                && !FakeMiniProgramBaseLibFeature.installAppBrandProcessHook(context, classLoader)) {
            failed = true;
        }
        if (SkipMiniProgramVideoAdsFeature.isEnabled(context)
                && !SkipMiniProgramVideoAdsFeature.install(context, classLoader)) {
            failed = true;
        }
        if (SkipGlobalMiniProgramSplashAdsFeature.isEnabled(context)) {
            SkipGlobalMiniProgramSplashAdsFeature.scheduleAppBrandProcessHook(context, classLoader);
        }
        if (FakeLocationFeature.isEnabled(context)
                && !FakeLocationFeature.installAppBrandProcessHook(context, classLoader)) {
            failed = true;
        }
        if (applicationReady) {
            ScriptPluginRuntime.installAppBrandProcess(
                    context,
                    classLoader,
                    lpparam.processName
            );
        }
        if (failed && applicationReady) {
            HLog.e(TAG + " 小程序进程Hook安装失败: " + lpparam.processName);
        }
    }

    // ============ 模块初始化 ============

    private void installHotUpdateEarlyHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            installHotUpdateForAttach(lpparam, param, "before");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            installHotUpdateForAttach(lpparam, param, "after");
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 热更新早期入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installHotUpdateForAttach(XC_LoadPackage.LoadPackageParam lpparam,
                                           XC_MethodHook.MethodHookParam param,
                                           String stage) {
        try {
            Context context = (Context) param.args[0];
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!DisableHotUpdateFeature.isEnabled(context)) return;
            if ("before".equals(stage)) {
                DisableHotUpdateFeature.installEarly(context, lpparam.classLoader);
                return;
            }
            new Thread(() -> {
                DexInstallScheduler.runDexKitTask(() -> {
                    try {
                        ClassLoader hostClassLoader = resolveTinkerClassLoader(param.thisObject);
                        if (hostClassLoader == null) {
                            hostClassLoader = resolveHostClassLoader(context, lpparam);
                        }
                        NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                        nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                        DexKitBridge dexKit = createDexKitBridge(hostClassLoader, lpparam.appInfo.sourceDir);
                        DisableHotUpdateFeature.install(context, hostClassLoader, dexKit);
                    } catch (Throwable e) {
                        HLog.e(TAG + " 热更新后置安装失败: " + e.getMessage(), e);
                    }
                });
            }, "Hchat-HotUpdateHook").start();
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable e) {
            HLog.e(TAG + " 热更新早期Hook失败(" + stage + "): " + e.getMessage(), e);
        }
    }

    private void installTabletEarlyHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            installTabletHookForAttach(lpparam, param, "before");
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            installTabletHookForAttach(lpparam, param, "after");
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 平板模式早期入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installCustomBottomBarEarlyHook(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                    "com.tencent.tinker.loader.app.TinkerApplication",
                    lpparam.classLoader,
                    "onBaseContextAttached",
                    Context.class,
                    long.class,
                    long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = (Context) param.args[0];
                            ClassLoader classLoader = resolveTinkerClassLoader(param.thisObject);
                            if (classLoader == null) {
                                classLoader = resolveHostClassLoader(context, lpparam);
                            }
                            installCustomBottomBarEarly(context, classLoader);
                        }
                    }
            ));
        } catch (Throwable e) {
            HLog.e(TAG + " 自定义底栏早期入口安装失败: " + e.getMessage(), e);
        }
    }

    private void installCustomBottomBarEarly(Context context, ClassLoader classLoader) {
        try {
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            CustomBottomBarFeature.installEarly(context, classLoader);
        } catch (Throwable e) {
            HLog.e(TAG + " 自定义底栏早期Hook失败: " + e.getMessage(), e);
        }
    }

    private void installTabletHookForAttach(XC_LoadPackage.LoadPackageParam lpparam,
                                            XC_MethodHook.MethodHookParam param,
                                            String stage) {
        try {
            Context context = (Context) param.args[0];
            if (!TermsGate.INSTANCE.isAccepted(context)) return;
            if (!WeChatTabletFeature.isEnabled(context)) return;
            ClassLoader resolvedClassLoader = resolveTinkerClassLoader(param.thisObject);
            if (resolvedClassLoader == null) {
                resolvedClassLoader = resolveHostClassLoader(context, lpparam);
            }
            final ClassLoader hostClassLoader = resolvedClassLoader;
            // 缓存安装不使用 DexKit，避免主线程等待后台预热持有的串行门。
            if (WeChatTabletFeature.installCached(context, hostClassLoader)) return;
            DexInstallScheduler.runDexKitTask(() -> {
                if (WeChatTabletFeature.installCached(context, hostClassLoader)) return;
                if (!"after".equals(stage)) {
                    if (!DisableHotUpdateFeature.isEnabled(context)) return;
                    NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                    nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                    DexKitBridge dexKit = createDexKitBridge(lpparam.classLoader, lpparam.appInfo.sourceDir);
                    WeChatTabletFeature.install(context, lpparam.classLoader, dexKit);
                    return;
                }
                NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
                nativeLibraryLoader.loadDexKit(context, ModuleEntry.this.getClass().getClassLoader());
                DexKitBridge dexKit = createDexKitBridge(hostClassLoader, lpparam.appInfo.sourceDir);
                WeChatTabletFeature.install(context, hostClassLoader, dexKit);
            });
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable e) {
            HLog.e(TAG + " 平板模式早期Hook失败(" + stage + "): " + e.getMessage(), e);
        }
    }

    private ClassLoader resolveTinkerClassLoader(Object tinkerApplication) {
        try {
            Object value = XposedHelpers.callMethod(tinkerApplication, "getClassLoader");
            return value instanceof ClassLoader ? (ClassLoader) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void initModule(Context hostContext, XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // 1. 加载 Native 库（DexKit）
            NativeLibraryLoader nativeLibraryLoader = new NativeLibraryLoader();
            nativeLibraryLoader.loadDexKit(hostContext, getClass().getClassLoader());
            nativeLibraryLoader.loadSilkCodec(hostContext, getClass().getClassLoader());

            // 2. 创建模块自身 Context
            Context moduleContext = createModuleContext(hostContext);
            ConfigStore configStore = new ConfigStore(hostContext);

            ModuleResourceMenuFallback.INSTANCE.install(resolveHostClassLoader(hostContext, lpparam));

            DexInstallScheduler.runDexKitTask(() -> {
                // 3. 初始化 DexKit。优先使用 Tinker 之后的运行时 ClassLoader。
                String apkPath = lpparam.appInfo.sourceDir;
                ClassLoader hostClassLoader = resolveHostClassLoader(hostContext, lpparam);
                DexKitBridge dexKit = createDexKitBridge(hostClassLoader, apkPath);
                DexFinder finder = new DexFinder(dexKit, hostClassLoader, hostContext);
                WeChatImageApi.installMarsCdnManagerHook(
                        finder.marsCdnManagerClass,
                        message -> HLog.e("[Hchat:WechatApi] " + message));

                // 4. 创建共享 DexKit 服务
                DexBridgeHolder dexBridgeHolder = new DexBridgeHolder(
                        dexKit, finder, hostClassLoader, apkPath);
                ScriptPluginAgentLocalReverseTools.install(dexBridgeHolder, hostContext);

                // 5. 创建统一上下文
                featureContext = new FeatureContext(
                        hostContext,
                        moduleContext != null ? moduleContext : hostContext,
                        hostClassLoader,
                        lpparam,
                        dexKit,
                        finder,
                        eventBus,
                        configStore,
                        dexBridgeHolder,
                        uiRegistry
                );

                // 6. 注册并安装所有功能模块
                featureManager = FeatureRegistry.createDefaultManager();
                featureManager.installAll(featureContext);

                // codex 网页版保活：微信进程重启后把上次开着的服务自动拉回来
                CodexWebGuard.INSTANCE.restore(hostContext);

                // 7. 防崩兜底：微信 8.0.76 消息描述文本方法对 null 调 isEmpty() 崩溃（收到特殊小程序电商卡片消息触发）
                installMsgDescTextFallback(finder);
            });

        } catch (Throwable e) {
            HLog.e(TAG + " 初始化失败: " + e, e);
        }
    }

    /**
     * 防崩兜底：微信 8.0.76 的 pt0.r.l(Context, boolean) 存在未初始化局部变量，
     * 收到特殊小程序电商卡片消息（type 44 + ecsInfo）时 String.isEmpty() 对 null 调用导致崩溃。
     * 这里 hook 该方法，手动调用原方法并捕获异常，崩溃时兜底返回空串。
     */
    private void installMsgDescTextFallback(DexFinder finder) {
        try {
            finder.resolveMsgDescTextApi();
            if (finder.msgDescTextMethods.isEmpty()) {
                HLog.e("[Hchat:WechatApi] 消息描述防崩兜底未定位到方法");
                return;
            }
            for (Method m : finder.msgDescTextMethods) {
                if (m == null) continue;
                try {
                    HookRegistry.get().add(XposedHelpers.findAndHookMethod(
                            m.getDeclaringClass(), m.getName(), Context.class, boolean.class,
                            new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    try {
                                        // param.method 是 Member，需转 Method 再手动调用原方法，捕获微信内部 NPE 兜底
                                        Method original = (Method) param.method;
                                        param.setResult(original.invoke(param.thisObject, param.args));
                                    } catch (Throwable t) {
                                        param.setResult("");
                                    }
                                }
                            }
                    ));
                } catch (Throwable e) {
                    HLog.e("[Hchat:WechatApi] 消息描述防崩兜底安装失败: " + m, e);
                }
            }
        } catch (Throwable e) {
            HLog.e(TAG + " 消息描述防崩兜底安装失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建模块自身的 Context。
     * 通过 createPackageContext 获取模块自身的 Context，
     * 以便访问模块资源。
     */
    private Context createModuleContext(Context hostContext) {
        try {
            return hostContext.createPackageContext(
                    "h.Hchat",
                    Context.CONTEXT_IGNORE_SECURITY | Context.CONTEXT_INCLUDE_CODE
            );
        } catch (Throwable e) {
            return null;
        }
    }

    private ClassLoader resolveHostClassLoader(Context hostContext,
                                               XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader lpClassLoader = lpparam.classLoader;
        ClassLoader contextClassLoader = null;
        ClassLoader threadClassLoader = null;
        try {
            contextClassLoader = hostContext.getClassLoader();
        } catch (Throwable ignored) {}
        try {
            threadClassLoader = Thread.currentThread().getContextClassLoader();
        } catch (Throwable ignored) {}

        if (isTinkerClassLoader(contextClassLoader)) return contextClassLoader;
        if (isTinkerClassLoader(threadClassLoader)) return threadClassLoader;
        if (contextClassLoader != null) return contextClassLoader;
        if (threadClassLoader != null) return threadClassLoader;
        return lpClassLoader;
    }

    private DexKitBridge createDexKitBridge(ClassLoader classLoader, String apkPath) {
        synchronized (DEXKIT_CREATE_LOCK) {
            String key = apkPath != null ? apkPath : "";
            DexKitBridge cached = DEXKIT_BRIDGES.get(key);
            if (cached != null) return cached;
            // 仅缓存轻量入口；实际查询才加载，连续空闲 30 秒后释放 native 分析资源。
            DexKitBridge bridge = DexKitBridge.createManaged(apkPath, 30_000L);
            DEXKIT_BRIDGES.put(key, bridge);
            return bridge;
        }
    }

    private boolean isTinkerClassLoader(ClassLoader classLoader) {
        if (classLoader == null) return false;
        String text = String.valueOf(classLoader);
        return text.contains("/tinker/") || text.contains("DelegateLastClassLoader");
    }
}
