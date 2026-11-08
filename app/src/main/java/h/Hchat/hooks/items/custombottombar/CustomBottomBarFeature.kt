package h.Hchat.hooks.items.custombottombar

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.EmbeddedComposeOwnerInstaller
import h.Hchat.ui.miuix.EmbeddedComposeRuntime
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

class CustomBottomBarFeature : BaseFeature() {
    private var runtime: CustomBottomBarRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "自定义底栏"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(CustomBottomBarSettingsProvider())
        registerSettingsProvider(FloatingBottomBarSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = obtainRuntime(context)
        if (runtime?.install(allowDexSearch = false) != true) scheduleInstall()
        runtime?.refreshCurrentActivity()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            val installed = runtime?.install(allowDexSearch = true) == true
            if (installed) runtime?.refreshCurrentActivity()
            installed
        }
    }

    companion object {
        const val ID = "custom_bottom_bar"
        const val FLOATING_BOTTOM_BAR_TAG = "Hchat:FloatingBottomBar"

        private val earlyRuntimes = Collections.synchronizedMap(
            WeakHashMap<ClassLoader, CustomBottomBarRuntime>()
        )

        @JvmStatic
        fun installEarly(context: Context, classLoader: ClassLoader?): Boolean {
            if (classLoader == null) return false
            val runtime = synchronized(earlyRuntimes) {
                earlyRuntimes[classLoader] ?: CustomBottomBarRuntime(
                    context,
                    classLoader,
                    null,
                    ::logFeatureError
                ).also { earlyRuntimes[classLoader] = it }
            }
            return runtime.install(allowDexSearch = false)
        }

        private fun obtainRuntime(context: FeatureContext): CustomBottomBarRuntime {
            val classLoader = context.hostClassLoader()
            return synchronized(earlyRuntimes) {
                earlyRuntimes.remove(classLoader)?.also { it.attachDexKit(context) }
                    ?: CustomBottomBarRuntime(context, ::logFeatureError)
            }
        }

        private fun logFeatureError(message: String, throwable: Throwable?) {
            HLog.e("[Hchat:自定义底栏] $message", throwable)
        }
    }
}

private class CustomBottomBarRuntime(
    private val hostContext: Context,
    private val hostClassLoader: ClassLoader,
    private var dexKitProvider: (() -> DexKitBridge)?,
    private val logger: (String, Throwable?) -> Unit
) {
    constructor(context: FeatureContext, logger: (String, Throwable?) -> Unit) : this(
        context.hostContext(),
        context.hostClassLoader(),
        { context.dexKitBridge() },
        logger
    )

    private val customPrefs = HchatStorage.preferences(hostContext, CustomBottomBarSettings.PREFS_NAME)
    private val floatingPrefs = HchatStorage.preferences(hostContext, FloatingBottomBarSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(hostContext, METHOD_CACHE_PREFS)
    private val knownViews = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, WeakReference<Activity>>()
    )
    private val observedBottomTabs = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, Unit>()
    )
    private val floatingHosts = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, CustomBottomBarHost>()
    )
    private val nativeHosts = Collections.synchronizedMap(
        WeakHashMap<ViewGroup, NativeCustomBottomBarHost>()
    )
    private val originalBottomBlurHeights = Collections.synchronizedMap(
        WeakHashMap<View, Int>()
    )
    private val suppressedBottomBlurRoots = Collections.synchronizedMap(
        WeakHashMap<View, Unit>()
    )
    private val failedViews = Collections.synchronizedMap(WeakHashMap<ViewGroup, Unit>())

    private val bottomTabClass by lazy {
        KavaReflector.loadClass(BOTTOM_TAB_CLASS, hostClassLoader)
    }
    private val getCurIdxMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_CUR_IDX)
    }
    private val setToMethod by lazy {
        KavaReflector.findMethod(
            bottomTabClass,
            SET_TO,
            Int::class.javaPrimitiveType!!
        )
    }
    private val getMainTabUnreadMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_MAIN_TAB_UNREAD)
    }
    private val getContactTabUnreadMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_CONTACT_TAB_UNREAD)
    }
    private val getFriendTabUnreadMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_FRIEND_TAB_UNREAD)
    }
    private val getShowFriendPointMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_SHOW_FRIEND_POINT)
    }
    private val getSettingsTabUnreadMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_SETTINGS_TAB_UNREAD)
    }
    private val getSettingsPointMethod by lazy {
        KavaReflector.findMethod(bottomTabClass, GET_SETTINGS_POINT)
    }
    @Volatile
    private var frostedMembers: FrostedMembers? = null

    @Volatile
    private var frostedUnavailable = false
    private val customPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in CUSTOM_CONFIG_KEYS) refreshKnownViews()
    }
    private val floatingPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in FLOATING_CONFIG_KEYS) refreshKnownViews()
    }

    @Volatile
    private var createHookInstalled = false

    @Volatile
    private var resumeHookInstalled = false

    @Volatile
    private var bottomTabStateHooksInstalled = false

    @Volatile
    private var bottomTabConstructorHooksInstalled = false

    @Volatile
    private var frostedDrawHookInstalled = false

    @Volatile
    private var lastLauncherActivity = WeakReference<Activity>(null)

    private val bottomTabAttachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            applyObservedBottomTab(view as? ViewGroup)
        }

        override fun onViewDetachedFromWindow(view: View) = Unit
    }

    init {
        customPrefs.registerOnSharedPreferenceChangeListener(customPreferenceListener)
        floatingPrefs.registerOnSharedPreferenceChangeListener(floatingPreferenceListener)
    }

    fun attachDexKit(context: FeatureContext) {
        dexKitProvider = { context.dexKitBridge() }
    }

    fun destroy() {
        customPrefs.unregisterOnSharedPreferenceChangeListener(customPreferenceListener)
        floatingPrefs.unregisterOnSharedPreferenceChangeListener(floatingPreferenceListener)
        synchronized(knownViews) { knownViews.clear() }
        val observed = synchronized(observedBottomTabs) {
            observedBottomTabs.keys.toList().also { observedBottomTabs.clear() }
        }
        observed.forEach { it.removeOnAttachStateChangeListener(bottomTabAttachListener) }
        lastLauncherActivity = WeakReference<Activity>(null)
        val activeFloatingHosts = synchronized(floatingHosts) {
            floatingHosts.values.toList().also { floatingHosts.clear() }
        }
        val activeNativeHosts = synchronized(nativeHosts) {
            nativeHosts.values.toList().also { nativeHosts.clear() }
        }
        activeFloatingHosts.forEach(CustomBottomBarHost::restore)
        activeNativeHosts.forEach(NativeCustomBottomBarHost::restore)
        restoreAllBottomBlurAreas()
    }

    @Synchronized
    fun install(allowDexSearch: Boolean): Boolean {
        val resumeInstalled = installLauncherResumeHook()
        val createInstalled = installMainTabCreateHook(allowDexSearch)
        val constructorHooksInstalled = installBottomTabConstructorHooks()
        val stateHooksInstalled = installBottomTabStateHooks()
        installFrostedDrawHook(finalAttempt = allowDexSearch)
        return resumeInstalled && createInstalled && constructorHooksInstalled && stateHooksInstalled
    }

    fun refreshCurrentActivity() {
        val current = WeChatApis.currentActivity()?.currentActivity()
        val target = current?.takeIf(::isLauncherActivity) ?: lastLauncherActivity.get()
        if (target == null || target.isFinishing || target.isDestroyed) return
        val decor = target.window?.decorView ?: return
        decor.post { applyFromActivity(target) }
    }

    private fun installBottomTabConstructorHooks(): Boolean {
        if (bottomTabConstructorHooksInstalled) return true
        val clazz = bottomTabClass ?: return false
        val constructors = KavaReflector.declaredConstructors(clazz)
        if (constructors.isEmpty()) return false
        return runCatching {
            constructors.forEach { constructor ->
                HookRegistry.get().hook(constructor, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        observeBottomTab(param.thisObject as? ViewGroup)
                    }
                })
            }
            bottomTabConstructorHooksInstalled = true
            true
        }.getOrElse {
            logger("微信底栏构造 Hook 安装失败", it)
            false
        }
    }

    private fun installFrostedDrawHook(finalAttempt: Boolean) {
        if (frostedDrawHookInstalled) return
        val method = resolveFrostedMembers(finalAttempt)?.dispatchDraw
        if (method == null) {
            if (frostedUnavailable) frostedDrawHookInstalled = true
            return
        }
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!isBottomBlurSuppressed(view.rootView)) return
                    clearBottomBlurView(view, invalidate = false)
                }
            })
            frostedDrawHookInstalled = true
        }.onFailure {
            logger("微信底部磨砂绘制 Hook 安装失败: ${method.toGenericString()}", it)
        }
    }

    private fun installBottomTabStateHooks(): Boolean {
        if (bottomTabStateHooksInstalled) return true
        val clazz = bottomTabClass ?: return false
        val exactSetTo = setToMethod ?: return false
        val methods = (KavaReflector.declaredMethods(clazz).filter(::isBottomTabStateUpdateMethod) +
            exactSetTo).distinctBy(Method::toGenericString)
        return runCatching {
            methods.forEach { method ->
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val selectedIndex = if (method == exactSetTo) {
                            param.args.getOrNull(0) as? Int
                        } else {
                            null
                        }
                        syncFloatingHost(param.thisObject as? ViewGroup, selectedIndex)
                    }
                })
            }
            bottomTabStateHooksInstalled = true
            true
        }.getOrElse {
            logger("微信底栏角标更新 Hook 安装失败", it)
            false
        }
    }

    private fun isBottomTabStateUpdateMethod(method: Method): Boolean {
        if (method.returnType != Void.TYPE ||
            !Modifier.isPublic(method.modifiers) ||
            Modifier.isStatic(method.modifiers) ||
            Modifier.isAbstract(method.modifiers)
        ) {
            return false
        }
        val parameters = method.parameterTypes
        return (parameters.size == 1 &&
            (parameters[0] == Int::class.javaPrimitiveType ||
                parameters[0] == Boolean::class.javaPrimitiveType)) ||
            (parameters.size == 2 &&
                parameters[0] == Boolean::class.javaPrimitiveType &&
                parameters[1] == String::class.java)
    }

    private fun syncFloatingHost(bottomTab: ViewGroup?, selectedIndex: Int? = null) {
        val target = bottomTab ?: return
        val host = synchronized(floatingHosts) { floatingHosts[target] }
        if (host == null) {
            applyObservedBottomTab(target)
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            host.syncState(selectedIndex)
        } else {
            target.post { host.syncState(selectedIndex) }
        }
    }

    private fun installLauncherResumeHook(): Boolean {
        if (resumeHookInstalled) return true
        val launcherClass = KavaReflector.loadClass(LAUNCHER_UI_CLASS, hostClassLoader)
            ?: return false
        val onResume = KavaReflector.declaredMethods(launcherClass).singleOrNull {
            it.name == "onResume" &&
                it.returnType == Void.TYPE &&
                it.parameterTypes.isEmpty() &&
                !Modifier.isStatic(it.modifiers) &&
                !Modifier.isAbstract(it.modifiers)
        } ?: return false
        return runCatching {
            HookRegistry.get().hook(onResume, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyFromActivity(param.thisObject as? Activity)
                }
            })
            resumeHookInstalled = true
            true
        }.getOrElse {
            logger("微信底栏恢复入口 Hook 安装失败", it)
            false
        }
    }

    private fun installMainTabCreateHook(allowDexSearch: Boolean): Boolean {
        if (createHookInstalled) return true
        val method = locateMainTabCreateMethod(allowDexSearch) ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyFromActivity(findActivity(param.thisObject))
                }
            })
            createHookInstalled = true
            true
        }.getOrElse {
            logger("微信底栏创建入口 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun locateMainTabCreateMethod(allowDexSearch: Boolean): Method? {
        val cacheKey = methodCacheKey()
        val cached = DexMethodCache.load(
            methodPrefs,
            cacheKey,
            hostClassLoader,
            CACHE_MAIN_TAB_CREATE
        )
        if (cached != null) {
            if (isMainTabCreateMethod(cached)) return cached
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_MAIN_TAB_CREATE)
        }
        val directMethod = locateKnownMainTabCreateMethod()
        if (directMethod != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_MAIN_TAB_CREATE, directMethod)
            return directMethod
        }
        if (!allowDexSearch) return null

        val dexKit = dexKitProvider?.invoke() ?: return null
        val candidates = runCatching {
            dexKit.findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(MAIN_TAB_LOG_TAG, MAIN_TAB_CREATE_LOG)
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(hostClassLoader) }.getOrNull()
            }.filter(::isMainTabCreateMethod)
                .distinctBy(Method::toGenericString)
        }.getOrElse {
            logger("定位微信底栏创建入口失败", it)
            emptyList()
        }

        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_MAIN_TAB_CREATE, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_MAIN_TAB_CREATE)
            if (candidates.size > 1) {
                logger(
                    "微信底栏创建入口定位结果不唯一: ${candidates.joinToString { it.toGenericString() }}",
                    null
                )
            }
        }
        return method
    }

    private fun locateKnownMainTabCreateMethod(): Method? {
        val mainTabClass = KavaReflector.loadClass(MAIN_TAB_UI_CLASS, hostClassLoader)
            ?: return null
        return KavaReflector.declaredMethods(mainTabClass).singleOrNull {
            it.name == MAIN_TAB_CREATE_METHOD && isMainTabCreateMethod(it)
        }
    }

    private fun isMainTabCreateMethod(method: Method): Boolean {
        return method.declaringClass.name == MAIN_TAB_UI_CLASS &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.isEmpty() &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun findActivity(owner: Any?): Activity? {
        if (owner is Activity) return owner
        if (owner is Context) {
            var context: Context? = owner
            while (context is ContextWrapper) {
                if (context is Activity) return context
                val base = context.baseContext
                if (base === context) break
                context = base
            }
        }
        val target = owner ?: return null
        var current: Class<*>? = target.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) && Activity::class.java.isAssignableFrom(field.type)
            }?.let { field ->
                return KavaReflector.readField(field, target) as? Activity
            }
            current = current.superclass
        }
        return null
    }

    private fun applyFromActivity(activity: Activity?) {
        val target = activity ?: return
        if (!isLauncherActivity(target) || target.isFinishing || target.isDestroyed) return
        lastLauncherActivity = WeakReference(target)
        val root = target.window?.decorView ?: return
        val bottomTab = findBottomTab(root) ?: return
        applyBottomTab(target, bottomTab)
    }

    private fun applyBottomTab(activity: Activity, bottomTab: ViewGroup) {
        lastLauncherActivity = WeakReference(activity)
        cleanupDetachedHosts(bottomTab)
        synchronized(knownViews) { knownViews[bottomTab] = WeakReference(activity) }
        configureBottomTab(activity, bottomTab)
    }

    private fun observeBottomTab(bottomTab: ViewGroup?) {
        val target = bottomTab ?: return
        val added = synchronized(observedBottomTabs) {
            if (observedBottomTabs.containsKey(target)) false else {
                observedBottomTabs[target] = Unit
                true
            }
        }
        if (added) target.addOnAttachStateChangeListener(bottomTabAttachListener)
        applyObservedBottomTab(target)
    }

    private fun applyObservedBottomTab(bottomTab: ViewGroup?) {
        val target = bottomTab ?: return
        val apply = apply@{
            if (!target.isAttachedToWindow) return@apply
            val activity = findActivity(target.context)
                ?: lastLauncherActivity.get()?.takeIf { target.rootView === it.window?.decorView }
                ?: return@apply
            applyBottomTab(activity, target)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply() else target.post { apply() }
    }

    private fun isLauncherActivity(activity: Activity): Boolean {
        return activity.javaClass.name == LAUNCHER_UI_CLASS
    }

    private fun cleanupDetachedHosts(current: ViewGroup) {
        val staleFloating = synchronized(floatingHosts) {
            floatingHosts.entries
                .filter { (view, _) -> view !== current && !view.isAttachedToWindow }
                .map { (view, host) -> view to host }
                .onEach { (view, _) -> floatingHosts.remove(view) }
                .map { it.second }
        }
        val staleNative = synchronized(nativeHosts) {
            nativeHosts.entries
                .filter { (view, _) -> view !== current && !view.isAttachedToWindow }
                .map { (view, host) -> view to host }
                .onEach { (view, _) -> nativeHosts.remove(view) }
                .map { it.second }
        }
        staleFloating.forEach(CustomBottomBarHost::restore)
        staleNative.forEach(NativeCustomBottomBarHost::restore)
    }

    private fun findBottomTab(view: View): ViewGroup? {
        if (view.javaClass.name == BOTTOM_TAB_CLASS) return view as? ViewGroup
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findBottomTab(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun configureBottomTab(activity: Activity, bottomTab: ViewGroup) {
        val customEnabled = isCustomEnabled()
        val floatingEnabled = !customEnabled && isFloatingEnabled()
        if (!customEnabled && !floatingEnabled) {
            synchronized(floatingHosts) { floatingHosts.remove(bottomTab) }?.restore()
            synchronized(nativeHosts) { nativeHosts.remove(bottomTab) }?.restore()
            synchronized(failedViews) { failedViews.remove(bottomTab) }
            restoreBottomBlurAreas(bottomTab.rootView)
            return
        }

        if (customEnabled) {
            synchronized(floatingHosts) { floatingHosts.remove(bottomTab) }?.restore()
            configureNativeBottomTab(activity, bottomTab, readNativeConfig())
            return
        }

        synchronized(nativeHosts) { nativeHosts.remove(bottomTab) }?.restore()
        val config = readFloatingConfig()
        val active = synchronized(floatingHosts) { floatingHosts[bottomTab] }
        if (active?.updateConfig(config) == true) return
        if (active != null) {
            synchronized(floatingHosts) {
                if (floatingHosts[bottomTab] === active) floatingHosts.remove(bottomTab)
            }
            active.restore()
        }

        val parent = bottomTab.parent as? FrameLayout
        val sourceView = parent?.let { findContentSource(it, bottomTab) }
        val clickListener = findBottomTabClickListener(bottomTab)
        val missing = buildList {
            if (parent == null) add("悬浮父容器")
            if (sourceView == null) add("内容视图")
            if (clickListener == null) add("原生点击监听")
            if (getCurIdxMethod == null) add(GET_CUR_IDX)
            if (getMainTabUnreadMethod == null) add(GET_MAIN_TAB_UNREAD)
            if (getContactTabUnreadMethod == null) add(GET_CONTACT_TAB_UNREAD)
            if (getFriendTabUnreadMethod == null) add(GET_FRIEND_TAB_UNREAD)
            if (getShowFriendPointMethod == null) add(GET_SHOW_FRIEND_POINT)
            if (getSettingsTabUnreadMethod == null) add(GET_SETTINGS_TAB_UNREAD)
            if (getSettingsPointMethod == null) add(GET_SETTINGS_POINT)
        }
        if (missing.isNotEmpty()) {
            val firstFailure = synchronized(failedViews) {
                if (failedViews.containsKey(bottomTab)) false else {
                    failedViews[bottomTab] = Unit
                    true
                }
            }
            if (firstFailure) logger("无法接管微信底栏，缺少: ${missing.joinToString()}", null)
            return
        }

        var createdHost: CustomBottomBarHost? = null
        val host = runCatching {
            CustomBottomBarHost(
                activity = activity,
                bottomTab = bottomTab,
                parent = parent!!,
                sourceView = sourceView!!,
                clickListener = clickListener!!,
                getCurIdxMethod = getCurIdxMethod!!,
                getMainTabUnreadMethod = getMainTabUnreadMethod!!,
                getContactTabUnreadMethod = getContactTabUnreadMethod!!,
                getFriendTabUnreadMethod = getFriendTabUnreadMethod!!,
                getShowFriendPointMethod = getShowFriendPointMethod!!,
                getSettingsTabUnreadMethod = getSettingsTabUnreadMethod!!,
                getSettingsPointMethod = getSettingsPointMethod!!,
                initialConfig = config,
                clearBottomBlur = ::clearBottomBlurAreas,
                restoreBottomBlur = ::restoreBottomBlurAreas,
                logger = logger,
                onRestored = {
                    synchronized(floatingHosts) {
                        if (floatingHosts[bottomTab] === createdHost) floatingHosts.remove(bottomTab)
                    }
                    if (bottomTab.isAttachedToWindow && (isCustomEnabled() || isFloatingEnabled())) {
                        runAfterCurrentFrame(bottomTab) { configureBottomTab(activity, bottomTab) }
                    }
                }
            )
        }.getOrElse {
            logger("自定义底栏宿主创建失败", it)
            return
        }
        createdHost = host
        if (host.install()) {
            synchronized(floatingHosts) { floatingHosts[bottomTab] = host }
            synchronized(failedViews) { failedViews.remove(bottomTab) }
        }
    }

    private fun configureNativeBottomTab(
        activity: Activity,
        bottomTab: ViewGroup,
        config: NativeCustomBottomBarConfig
    ) {
        val active = synchronized(nativeHosts) { nativeHosts[bottomTab] }
        if (active?.config == config && active.isApplied()) {
            if (active.enforce()) return
        }
        if (active != null) {
            synchronized(nativeHosts) {
                if (nativeHosts[bottomTab] === active) nativeHosts.remove(bottomTab)
            }
            active.restore()
        }
        var createdHost: NativeCustomBottomBarHost? = null
        val host = NativeCustomBottomBarHost(
            activity = activity,
            bottomTab = bottomTab,
            config = config,
            clearBottomBlur = ::clearBottomBlurAreas,
            restoreBottomBlur = ::restoreBottomBlurAreas,
            logger = logger,
            onRestored = {
                synchronized(nativeHosts) {
                    if (nativeHosts[bottomTab] === createdHost) nativeHosts.remove(bottomTab)
                }
            }
        )
        createdHost = host
        if (host.install()) {
            synchronized(nativeHosts) { nativeHosts[bottomTab] = host }
            synchronized(failedViews) { failedViews.remove(bottomTab) }
        }
    }

    private fun findContentSource(parent: ViewGroup, bottomTab: ViewGroup): View? {
        return (0 until parent.childCount)
            .map(parent::getChildAt)
            .filter { it !== bottomTab && it.visibility != View.GONE }
            .maxByOrNull { view ->
                val width = view.width.coerceAtLeast(view.measuredWidth).coerceAtLeast(1)
                val height = view.height.coerceAtLeast(view.measuredHeight).coerceAtLeast(1)
                width.toLong() * height.toLong()
            }
    }

    private fun findBottomTabClickListener(bottomTab: ViewGroup): View.OnClickListener? {
        val listeners = ArrayList<View.OnClickListener>()
        var current: Class<*>? = bottomTab.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).forEach { field ->
                if (!Modifier.isStatic(field.modifiers) &&
                    View.OnClickListener::class.java.isAssignableFrom(field.type)
                ) {
                    val listener = KavaReflector.readField(field, bottomTab) as? View.OnClickListener
                    if (listener != null && listeners.none { it === listener }) listeners += listener
                }
            }
            current = current.superclass
        }
        return listeners.singleOrNull()
    }

    private fun clearBottomBlurAreas(root: View) {
        val alreadySuppressed = synchronized(suppressedBottomBlurRoots) {
            val present = suppressedBottomBlurRoots.containsKey(root)
            suppressedBottomBlurRoots[root] = Unit
            present
        }
        if (alreadySuppressed && frostedDrawHookInstalled) return
        val targetClass = resolveFrostedMembers(finalAttempt = false)?.clazz ?: return
        val frostedViews = ArrayList<View>()
        collectFrostedContentViews(root, targetClass, frostedViews)
        frostedViews.forEach { clearBottomBlurView(it, invalidate = true) }
    }

    private fun clearBottomBlurView(view: View, invalidate: Boolean) {
        val members = resolveFrostedMembers(finalAttempt = false) ?: return
        val current = KavaReflector.invoke(members.getBottomBlurAreaHeight, view) as? Int ?: return
        if (current > 0) {
            synchronized(originalBottomBlurHeights) {
                val saved = originalBottomBlurHeights[view]
                if (saved == null || saved <= 0) originalBottomBlurHeights[view] = current
            }
        }
        if (current != 0 &&
            KavaReflector.invokeSuccessfully(members.setBottomBlurAreaHeight, view, 0) &&
            invalidate
        ) {
            view.invalidate()
        }
    }

    private fun restoreBottomBlurAreas(root: View) {
        synchronized(suppressedBottomBlurRoots) { suppressedBottomBlurRoots.remove(root) }
        val entries = synchronized(originalBottomBlurHeights) {
            originalBottomBlurHeights.entries
                .filter { (view, _) -> view.rootView === root }
                .map { it.key to it.value }
                .also { restored -> restored.forEach { originalBottomBlurHeights.remove(it.first) } }
        }
        entries.forEach { (view, height) ->
            if (writeBottomBlurAreaHeight(view, height)) view.invalidate()
        }
    }

    private fun restoreAllBottomBlurAreas() {
        synchronized(suppressedBottomBlurRoots) { suppressedBottomBlurRoots.clear() }
        val entries = synchronized(originalBottomBlurHeights) {
            originalBottomBlurHeights.entries.map { it.key to it.value }
                .also { originalBottomBlurHeights.clear() }
        }
        entries.forEach { (view, height) ->
            val restore = {
                if (writeBottomBlurAreaHeight(view, height)) view.invalidate()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) restore() else view.post { restore() }
        }
    }

    private fun collectFrostedContentViews(
        view: View,
        targetClass: Class<*>,
        output: MutableList<View>
    ) {
        if (targetClass.isInstance(view)) {
            output += view
            return
        }
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) {
            collectFrostedContentViews(view.getChildAt(index), targetClass, output)
        }
    }

    private fun writeBottomBlurAreaHeight(view: View, height: Int): Boolean {
        val method = resolveFrostedMembers(finalAttempt = false)?.setBottomBlurAreaHeight ?: return false
        return KavaReflector.invokeSuccessfully(method, view, height)
    }

    @Synchronized
    private fun resolveFrostedMembers(finalAttempt: Boolean): FrostedMembers? {
        frostedMembers?.let { return it }
        if (frostedUnavailable) return null
        val clazz = KavaReflector.loadClass(FROSTED_CONTENT_VIEW_CLASS, hostClassLoader)
        if (clazz == null) {
            if (finalAttempt) frostedUnavailable = true
            return null
        }
        val getHeight = KavaReflector.findMethod(clazz, GET_BOTTOM_BLUR_AREA_HEIGHT)
        val setHeight = KavaReflector.findMethod(
            clazz,
            SET_BOTTOM_BLUR_AREA_HEIGHT,
            Int::class.javaPrimitiveType!!
        )
        val dispatchDraw = KavaReflector.findMethod(clazz, DISPATCH_DRAW, Canvas::class.java)
        if (getHeight == null || setHeight == null || dispatchDraw == null) {
            if (finalAttempt) frostedUnavailable = true
            return null
        }
        return FrostedMembers(clazz, getHeight, setHeight, dispatchDraw).also {
            frostedMembers = it
        }
    }

    private fun refreshKnownViews() {
        val entries = synchronized(knownViews) {
            knownViews.entries.mapNotNull { (view, activity) ->
                activity.get()?.let { view to it }
            }
        }
        entries.forEach { (view, activity) ->
            runAfterCurrentFrame(view) { configureBottomTab(activity, view) }
        }
        refreshCurrentActivity()
    }

    private fun runAfterCurrentFrame(view: View, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            view.postOnAnimation { action() }
        } else {
            view.post { view.postOnAnimation { action() } }
        }
    }

    private fun isBottomBlurSuppressed(root: View): Boolean {
        return synchronized(suppressedBottomBlurRoots) {
            suppressedBottomBlurRoots.containsKey(root)
        }
    }

    private fun isCustomEnabled(): Boolean = customPrefs.getBoolean(
        CustomBottomBarSettings.KEY_ENABLE,
        CustomBottomBarSettings.DEFAULT_ENABLE
    )

    private fun isFloatingEnabled(): Boolean = floatingPrefs.getBoolean(
        FloatingBottomBarSettings.KEY_ENABLE,
        FloatingBottomBarSettings.DEFAULT_ENABLE
    ) && floatingPrefs.getString(FloatingBottomBarSettings.KEY_STYLE, null) ==
        FloatingBottomBarSettings.LEGACY_STYLE_FLOATING

    private fun readFloatingConfig(): CustomBottomBarConfig {
        return CustomBottomBarConfig(
            glass = floatingPrefs.getBoolean(
                FloatingBottomBarSettings.KEY_GLASS,
                FloatingBottomBarSettings.DEFAULT_GLASS
            ),
            blurRadius = FloatingBottomBarSettings.normalizeBlurRadius(
                floatingPrefs.getInt(
                    FloatingBottomBarSettings.KEY_BLUR_RADIUS,
                    FloatingBottomBarSettings.DEFAULT_BLUR_RADIUS
                )
            ),
            hideLabels = floatingPrefs.getBoolean(
                FloatingBottomBarSettings.KEY_HIDE_LABELS,
                FloatingBottomBarSettings.DEFAULT_HIDE_LABELS
            ),
            showBadges = floatingPrefs.getBoolean(
                FloatingBottomBarSettings.KEY_SHOW_BADGES,
                FloatingBottomBarSettings.DEFAULT_SHOW_BADGES
            ),
            vibrationEnabled = floatingPrefs.getBoolean(
                FloatingBottomBarSettings.KEY_VIBRATION_ENABLED,
                FloatingBottomBarSettings.DEFAULT_VIBRATION_ENABLED
            ),
            vibrationStrength = FloatingBottomBarSettings.normalizeVibrationStrength(
                floatingPrefs.getInt(
                    FloatingBottomBarSettings.KEY_VIBRATION_STRENGTH,
                    FloatingBottomBarSettings.DEFAULT_VIBRATION_STRENGTH
                )
            )
        )
    }

    private fun readNativeConfig(): NativeCustomBottomBarConfig {
        return NativeCustomBottomBarConfig(
            modifyIcons = customPrefs.getBoolean(
                CustomBottomBarSettings.KEY_MODIFY_ICONS,
                CustomBottomBarSettings.DEFAULT_MODIFY_ICONS
            ),
            modifyTitles = customPrefs.getBoolean(
                CustomBottomBarSettings.KEY_MODIFY_TITLES,
                CustomBottomBarSettings.DEFAULT_MODIFY_TITLES
            ),
            hideTitles = customPrefs.getBoolean(
                CustomBottomBarSettings.KEY_HIDE_TITLES,
                CustomBottomBarSettings.DEFAULT_HIDE_TITLES
            ),
            hideBar = customPrefs.getBoolean(
                CustomBottomBarSettings.KEY_HIDE_BAR,
                CustomBottomBarSettings.DEFAULT_HIDE_BAR
            ),
            titles = CustomBottomBarSettings.TITLE_KEYS.mapIndexed { index, key ->
                CustomBottomBarSettings.normalizeTitle(index, customPrefs.getString(key, null))
            },
            iconPaths = CustomBottomBarSettings.ICON_KEYS.map { key ->
                customPrefs.getString(key, null)?.trim().orEmpty()
            }
        )
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(hostContext, hostClassLoader)
            .takeIf(String::isNotBlank)
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private data class FrostedMembers(
        val clazz: Class<*>,
        val getBottomBlurAreaHeight: Method,
        val setBottomBlurAreaHeight: Method,
        val dispatchDraw: Method
    )

    private companion object {
        const val METHOD_CACHE_PREFS = "Hchat_custom_bottom_bar_method_cache"
        const val CACHE_SCHEMA = "custom_bottom_bar_v1_main_tab"
        const val CACHE_MAIN_TAB_CREATE = "main_tab_create_method"
        const val MAIN_TAB_CREATE_METHOD = "d"
        const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
        const val MAIN_TAB_UI_CLASS = "com.tencent.mm.ui.MainTabUI"
        const val BOTTOM_TAB_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"
        const val FROSTED_CONTENT_VIEW_CLASS = "com.tencent.mm.ui.FrostedContentView"
        const val GET_BOTTOM_BLUR_AREA_HEIGHT = "getBottomBlurAreaHeight"
        const val SET_BOTTOM_BLUR_AREA_HEIGHT = "setBottomBlurAreaHeight"
        const val DISPATCH_DRAW = "dispatchDraw"
        const val GET_CUR_IDX = "getCurIdx"
        const val SET_TO = "setTo"
        const val GET_MAIN_TAB_UNREAD = "getMainTabUnread"
        const val GET_CONTACT_TAB_UNREAD = "getContactTabUnread"
        const val GET_FRIEND_TAB_UNREAD = "getFriendTabUnread"
        const val GET_SHOW_FRIEND_POINT = "getShowFriendPoint"
        const val GET_SETTINGS_TAB_UNREAD = "getSettingsTabUnread"
        const val GET_SETTINGS_POINT = "getSettingsPoint"
        const val MAIN_TAB_LOG_TAG = "MicroMsg.LauncherUI.MainTabUI"
        const val MAIN_TAB_CREATE_LOG = "doOnCreate"

        val CUSTOM_CONFIG_KEYS = setOf(
            CustomBottomBarSettings.KEY_ENABLE,
            CustomBottomBarSettings.KEY_MODIFY_ICONS,
            CustomBottomBarSettings.KEY_MODIFY_TITLES,
            CustomBottomBarSettings.KEY_HIDE_TITLES,
            CustomBottomBarSettings.KEY_HIDE_BAR,
            *CustomBottomBarSettings.TITLE_KEYS.toTypedArray(),
            *CustomBottomBarSettings.ICON_KEYS.toTypedArray()
        )
        val FLOATING_CONFIG_KEYS = setOf(
            FloatingBottomBarSettings.KEY_ENABLE,
            FloatingBottomBarSettings.KEY_STYLE,
            FloatingBottomBarSettings.KEY_GLASS,
            FloatingBottomBarSettings.KEY_BLUR_RADIUS,
            FloatingBottomBarSettings.KEY_HIDE_LABELS,
            FloatingBottomBarSettings.KEY_SHOW_BADGES,
            FloatingBottomBarSettings.KEY_VIBRATION_ENABLED,
            FloatingBottomBarSettings.KEY_VIBRATION_STRENGTH
        )
    }
}

private data class NativeCustomBottomBarConfig(
    val modifyIcons: Boolean,
    val modifyTitles: Boolean,
    val hideTitles: Boolean,
    val hideBar: Boolean,
    val titles: List<String>,
    val iconPaths: List<String>
)

private class NativeCustomBottomBarHost(
    activity: Activity,
    private val bottomTab: ViewGroup,
    val config: NativeCustomBottomBarConfig,
    private val clearBottomBlur: (View) -> Unit,
    private val restoreBottomBlur: (View) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
    private val onRestored: () -> Unit
) : View.OnAttachStateChangeListener {
    private val applied = AtomicBoolean(false)
    private val originalVisibility = bottomTab.visibility
    private val originalBackground = bottomTab.background
    private val navigationAppearance = BottomBarNavigationAppearance(activity.window)
    private var observedRoot = bottomTab.rootView
    private val tabs = ArrayList<NativeTabState>(4)
    private val hiddenChildren = ArrayList<NativeHiddenChild>()
    private var preDrawObserver: ViewTreeObserver? = null
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (applied.get() && !enforce()) bottomTab.post(::restoreOnMain)
        true
    }

    fun install(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (applied.get()) return true
        return runCatching {
            if (config.hideBar) {
                for (index in 0 until bottomTab.childCount) {
                    val child = bottomTab.getChildAt(index)
                    hiddenChildren += NativeHiddenChild(child, child.visibility)
                }
            } else if (config.modifyIcons || config.modifyTitles || config.hideTitles) {
                for (index in 0..3) {
                    val tabRoot = findTaggedTab(bottomTab, index)
                        ?: error("未找到索引 $index 的底栏标签")
                    val label = findTabLabel(tabRoot)
                    if ((config.modifyTitles || config.hideTitles) && label == null) {
                        error("未找到索引 $index 的底栏标题")
                    }
                    val icon = findTabIcon(tabRoot)
                    val bitmap = if (config.modifyIcons) {
                        CustomBottomBarIconStore.loadBitmap(config.iconPaths.getOrNull(index))
                    } else {
                        null
                    }
                    tabs += NativeTabState(
                        root = tabRoot,
                        label = label,
                        icon = icon,
                        originalText = label?.text?.toString(),
                        originalLabelVisibility = label?.visibility,
                        originalIconVisibility = icon?.visibility,
                        bitmap = bitmap
                    )
                }
            }
            bottomTab.addOnAttachStateChangeListener(this)
            observedRoot = bottomTab.rootView
            observePreDraw(observedRoot)
            applied.set(true)
            enforce()
            true
        }.getOrElse {
            logger("应用原生自定义底栏失败", it)
            restoreAfterFailedInstall()
            false
        }
    }

    fun isApplied(): Boolean = applied.get()

    fun enforce(): Boolean {
        if (!applied.get()) return false
        if (config.hideBar) {
            bottomTab.background = null
            for (index in 0 until bottomTab.childCount) {
                val child = bottomTab.getChildAt(index)
                if (hiddenChildren.none { it.view === child }) {
                    hiddenChildren += NativeHiddenChild(child, child.visibility)
                }
                if (child.visibility != View.GONE) child.visibility = View.GONE
            }
            clearBottomBlur(observedRoot)
            navigationAppearance.enforce()
            return true
        }
        if (tabs.any { !isDescendant(bottomTab, it.root) }) return false
        tabs.forEachIndexed { index, tab -> applyTab(index, tab) }
        return true
    }

    fun restore() {
        if (Looper.myLooper() == Looper.getMainLooper()) restoreOnMain() else bottomTab.post(::restoreOnMain)
    }

    override fun onViewAttachedToWindow(view: View) {
        val currentRoot = bottomTab.rootView
        if (currentRoot !== observedRoot) {
            if (config.hideBar) restoreBottomBlur(observedRoot)
            observedRoot = currentRoot
        }
        observePreDraw(observedRoot)
        enforce()
    }

    override fun onViewDetachedFromWindow(view: View) {
        removePreDrawObserver()
    }

    private fun observePreDraw(root: View) {
        val observer = root.viewTreeObserver
        if (preDrawObserver === observer) return
        removePreDrawObserver()
        if (observer.isAlive) {
            observer.addOnPreDrawListener(preDrawListener)
            preDrawObserver = observer
        }
    }

    private fun removePreDrawObserver() {
        preDrawObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
        }
        preDrawObserver = null
    }

    private fun applyTab(index: Int, tab: NativeTabState) {
        tab.label?.let { label ->
            if (config.modifyTitles) {
                val title = config.titles.getOrElse(index) { CustomBottomBarSettings.DEFAULT_TITLES[index] }
                if (label.text?.toString() != title) label.text = title
            }
            if (config.hideTitles && label.visibility != View.GONE) {
                label.visibility = View.GONE
            }
        }

        val icon = tab.icon ?: return
        val bitmap = tab.bitmap ?: return
        val parent = icon.parent as? ViewGroup ?: return
        var customIcon = tab.customIcon
        if (customIcon == null || customIcon.parent !== parent) {
            (customIcon?.parent as? ViewGroup)?.removeView(customIcon)
            customIcon = ImageView(bottomTab.context).apply {
                tag = CUSTOM_ICON_TAG_PREFIX + index
                contentDescription = config.titles.getOrNull(index)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageDrawable(BitmapDrawable(resources, bitmap))
            }
            val iconIndex = parent.indexOfChild(icon).coerceAtLeast(0)
            val layoutParams = icon.layoutParams?.let(::copyLayoutParams) ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            parent.addView(customIcon, (iconIndex + 1).coerceAtMost(parent.childCount), layoutParams)
            tab.customIcon = customIcon
        }
        syncCustomIconSlot(icon, customIcon)
        val hiddenVisibility = if (parent is RelativeLayout) View.INVISIBLE else View.GONE
        if (icon.visibility != hiddenVisibility) icon.visibility = hiddenVisibility
        if (customIcon.visibility != View.VISIBLE) customIcon.visibility = View.VISIBLE
    }

    private fun syncCustomIconSlot(icon: View, customIcon: ImageView) {
        val width = icon.width.coerceAtLeast(icon.measuredWidth)
        val height = icon.height.coerceAtLeast(icon.measuredHeight)
        if (width <= 0 || height <= 0) return
        val layoutParams = customIcon.layoutParams ?: return
        if (layoutParams.width == width && layoutParams.height == height) return
        layoutParams.width = width
        layoutParams.height = height
        customIcon.layoutParams = layoutParams
    }

    private fun restoreOnMain() {
        if (!applied.compareAndSet(true, false)) return
        bottomTab.removeOnAttachStateChangeListener(this)
        removePreDrawObserver()
        restoreState()
        onRestored()
    }

    private fun restoreAfterFailedInstall() {
        applied.set(false)
        bottomTab.removeOnAttachStateChangeListener(this)
        removePreDrawObserver()
        runCatching(::restoreState).onFailure { logger("原生自定义底栏失败后恢复失败", it) }
    }

    private fun restoreState() {
        tabs.forEach { tab ->
            tab.customIcon?.let { custom -> (custom.parent as? ViewGroup)?.removeView(custom) }
            tab.customIcon = null
            tab.label?.let { label ->
                tab.originalText?.let { label.text = it }
                tab.originalLabelVisibility?.let { label.visibility = it }
            }
            tab.icon?.let { icon -> tab.originalIconVisibility?.let { icon.visibility = it } }
            tab.bitmap?.takeUnless { it.isRecycled }?.recycle()
        }
        tabs.clear()
        if (config.hideBar) {
            hiddenChildren.forEach { child ->
                if (child.view.parent === bottomTab) child.view.visibility = child.visibility
            }
            hiddenChildren.clear()
            bottomTab.background = originalBackground
            bottomTab.visibility = originalVisibility
            restoreBottomBlur(observedRoot)
        }
        navigationAppearance.restore()
    }

    private fun findTaggedTab(view: View, index: Int): ViewGroup? {
        if (view !== bottomTab && view.tag == index && view is ViewGroup) return view
        if (view !is ViewGroup) return null
        for (childIndex in 0 until view.childCount) {
            findTaggedTab(view.getChildAt(childIndex), index)?.let { return it }
        }
        return null
    }

    private fun findTabIcon(view: View): View? {
        if (view.javaClass.name == TAB_ICON_VIEW_CLASS) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTabIcon(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findTabLabel(view: View): TextView? {
        val labels = ArrayList<TextView>()
        collectTextViews(view, labels)
        labels.firstOrNull { resourceEntryName(it) == TAB_LABEL_RESOURCE_NAME }?.let { return it }
        return labels.firstOrNull { label ->
            val value = label.text?.toString()?.trim().orEmpty()
            value.isNotEmpty() && value.length <= 12 && value.any(Char::isLetter)
        }
    }

    private fun collectTextViews(view: View, output: MutableList<TextView>) {
        if (view is TextView) output += view
        if (view !is ViewGroup) return
        for (index in 0 until view.childCount) collectTextViews(view.getChildAt(index), output)
    }

    private fun resourceEntryName(view: View): String? {
        if (view.id == View.NO_ID || view.id == 0) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    private fun isDescendant(parent: ViewGroup, child: View): Boolean {
        var current: Any? = child
        while (current is View) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private fun copyLayoutParams(source: ViewGroup.LayoutParams): ViewGroup.LayoutParams {
        return when (source) {
            is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(source)
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(source)
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(source)
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(source)
            else -> KavaReflector.newInstanceByArgs(source.javaClass, arrayOf<Any?>(source))
                as? ViewGroup.LayoutParams ?: ViewGroup.LayoutParams(source)
        }
    }

    private data class NativeTabState(
        val root: ViewGroup,
        val label: TextView?,
        val icon: View?,
        val originalText: CharSequence?,
        val originalLabelVisibility: Int?,
        val originalIconVisibility: Int?,
        val bitmap: Bitmap?,
        var customIcon: ImageView? = null
    )

    private data class NativeHiddenChild(
        val view: View,
        val visibility: Int
    )

    private companion object {
        const val TAB_ICON_VIEW_CLASS = "com.tencent.mm.ui.TabIconView"
        const val TAB_LABEL_RESOURCE_NAME = "icon_tv"
        const val CUSTOM_ICON_TAG_PREFIX = "Hchat.CustomBottomBar.Icon."
    }
}

private class CustomBottomBarHost(
    private val activity: Activity,
    private val bottomTab: ViewGroup,
    private val parent: FrameLayout,
    private val sourceView: View,
    private val clickListener: View.OnClickListener,
    private val getCurIdxMethod: Method,
    private val getMainTabUnreadMethod: Method,
    private val getContactTabUnreadMethod: Method,
    private val getFriendTabUnreadMethod: Method,
    private val getShowFriendPointMethod: Method,
    private val getSettingsTabUnreadMethod: Method,
    private val getSettingsPointMethod: Method,
    initialConfig: CustomBottomBarConfig,
    private val clearBottomBlur: (View) -> Unit,
    private val restoreBottomBlur: (View) -> Unit,
    private val logger: (String, Throwable?) -> Unit,
    private val onRestored: () -> Unit
) : View.OnAttachStateChangeListener {
    private val applied = AtomicBoolean(false)
    private val originalChildren = ArrayList<OriginalChild>()
    private val originalVisibility = bottomTab.visibility
    private val originalBackground: Drawable? = bottomTab.background
    private val originalLayoutParams = bottomTab.layoutParams
    private val originalClipChildren = parent.clipChildren
    private val originalClipToPadding = parent.clipToPadding
    private val root = bottomTab.rootView
    private val navigationAppearance = BottomBarNavigationAppearance(activity.window)
    private val owner = BottomBarComposeOwner()
    private val clickTargets = arrayOfNulls<ViewGroup>(4)
    private val configState = mutableStateOf(initialConfig)
    private val state = readInitialState()
    private val touchState = FloatingBottomBarTouchState()
    private val composeView = ComposeView(activity).apply {
        tag = touchState
        owner.install(this)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }
    private val composeHost = object : FrameLayout(activity) {
        init {
            tag = CustomBottomBarFeature.FLOATING_BOTTOM_BAR_TAG
            owner.install(this)
            clipChildren = false
            clipToPadding = false
            addView(
                composeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM
                )
            )
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            owner.install(this)
            owner.install(composeView)
            owner.installComposition(composeView)
        }
    }
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (applied.get()) {
            if (enforce()) {
                val now = SystemClock.uptimeMillis()
                if (now - lastStateSyncAt >= STATE_SYNC_FALLBACK_INTERVAL_MS) syncState()
            } else {
                bottomTab.post(::restoreOnMain)
            }
        }
        true
    }
    private var pendingSelectedIndex: Int? = null
    private var pendingSelectedAt = 0L
    private var lastStateSyncAt = 0L
    private val pendingSelectionTimeout = Runnable {
        if (pendingSelectedIndex != null &&
            SystemClock.uptimeMillis() - pendingSelectedAt >= PENDING_SELECTION_TIMEOUT_MS
        ) {
            pendingSelectedIndex = null
            syncState()
        }
    }

    fun install(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (applied.get()) return true
        return runCatching {
            for (index in 0 until bottomTab.childCount) {
                val child = bottomTab.getChildAt(index)
                originalChildren += OriginalChild(child, child.visibility)
            }
            for (index in clickTargets.indices) {
                val target = findTaggedTab(bottomTab, index)
                    ?: error("未找到索引 $index 的微信原生底栏标签")
                if (findNativeTabIcon(target) == null) {
                    error("索引 $index 的微信原生底栏标签缺少 TabIconView")
                }
                clickTargets[index] = target
            }
            if (clickTargets.distinct().size != clickTargets.size) {
                error("微信原生底栏标签索引重复")
            }
            owner.attach()
            composeView.setContent {
                CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                    CustomBottomBarRoot(
                        activity = activity,
                        sourceView = sourceView,
                        state = state,
                        config = configState.value,
                        touchState = touchState,
                        onTabClicked = ::onTabClicked
                    )
                }
            }
            bottomTab.background = null
            hideNativeTabChildren()
            parent.clipChildren = false
            parent.clipToPadding = false
            attachComposeHost()
            bottomTab.addOnAttachStateChangeListener(this)
            val observer = root.viewTreeObserver
            if (observer.isAlive) observer.addOnPreDrawListener(preDrawListener)
            applied.set(true)
            enforce()
            syncState()
            composeHost.postOnAnimation {
                if (applied.get()) syncState()
            }
            true
        }.getOrElse {
            logger("自定义底栏挂载失败", it)
            restoreAfterFailedInstall()
            false
        }
    }

    fun isApplied(): Boolean = applied.get()

    fun updateConfig(config: CustomBottomBarConfig): Boolean {
        if (!applied.get() || !isHierarchyCurrent()) return false
        if (configState.value != config) configState.value = config
        enforce()
        syncState()
        return true
    }

    fun enforce(): Boolean {
        if (!applied.get() || !isHierarchyCurrent()) return false
        bottomTab.background = null
        hideNativeTabChildren()
        parent.clipChildren = false
        parent.clipToPadding = false
        val showOverlay = shouldShowOverlay()
        if (showOverlay) clearBottomBlur(root) else restoreBottomBlur(root)
        navigationAppearance.enforce()
        composeHost.visibility = if (showOverlay) View.VISIBLE else View.GONE
        if (composeHost.parent !== parent && parent.isAttachedToWindow) {
            attachComposeHost()
        }
        return true
    }

    fun syncState(selectedIndexHint: Int? = null) {
        if (!applied.get()) return
        lastStateSyncAt = SystemClock.uptimeMillis()
        val selectedIndex = selectedIndexHint
            ?: (KavaReflector.invoke(getCurIdxMethod, bottomTab) as? Int)
        val mainUnread = KavaReflector.invoke(getMainTabUnreadMethod, bottomTab) as? Int
        val contactUnread = KavaReflector.invoke(getContactTabUnreadMethod, bottomTab) as? Int
        val discoveryUnread = KavaReflector.invoke(getFriendTabUnreadMethod, bottomTab) as? Int
        val showDiscoveryDot = KavaReflector.invoke(getShowFriendPointMethod, bottomTab) as? Boolean
        val settingsUnread = KavaReflector.invoke(getSettingsTabUnreadMethod, bottomTab) as? Int
        val showSettingsDot = KavaReflector.invoke(getSettingsPointMethod, bottomTab) as? Boolean
        if (selectedIndex != null && selectedIndex in clickTargets.indices) {
            val pending = pendingSelectedIndex
            val pendingAge = SystemClock.uptimeMillis() - pendingSelectedAt
            val effectiveIndex = when {
                pending == null -> selectedIndex
                selectedIndex == pending -> {
                    pendingSelectedIndex = null
                    selectedIndex
                }
                pendingAge < PENDING_SELECTION_TIMEOUT_MS -> pending
                else -> {
                    pendingSelectedIndex = null
                    selectedIndex
                }
            }
            if (state.selectedIndex != effectiveIndex) state.selectedIndex = effectiveIndex
        }
        if (mainUnread != null && state.mainUnread != mainUnread) state.mainUnread = mainUnread
        if (contactUnread != null && state.contactUnread != contactUnread) {
            state.contactUnread = contactUnread
        }
        if (discoveryUnread != null && state.discoveryUnread != discoveryUnread) {
            state.discoveryUnread = discoveryUnread
        }
        if (showDiscoveryDot != null && state.showDiscoveryDot != showDiscoveryDot) {
            state.showDiscoveryDot = showDiscoveryDot
        }
        if (settingsUnread != null && state.settingsUnread != settingsUnread) {
            state.settingsUnread = settingsUnread
        }
        if (showSettingsDot != null && state.showSettingsDot != showSettingsDot) {
            state.showSettingsDot = showSettingsDot
        }
    }

    fun restore() {
        if (Looper.myLooper() == Looper.getMainLooper()) restoreOnMain() else bottomTab.post(::restoreOnMain)
    }

    override fun onViewAttachedToWindow(view: View) {
        if (!isHierarchyCurrent()) {
            bottomTab.post(::restoreOnMain)
            return
        }
        val observer = bottomTab.rootView.viewTreeObserver
        if (observer.isAlive) observer.addOnPreDrawListener(preDrawListener)
        enforce()
        syncState()
        bottomTab.postOnAnimation {
            if (applied.get()) syncState()
        }
    }

    override fun onViewDetachedFromWindow(view: View) {
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
        composeHost.visibility = View.GONE
        restoreBottomBlur(root)
    }

    private fun attachComposeHost() {
        if (composeHost.parent === parent) return
        (composeHost.parent as? ViewGroup)?.removeView(composeHost)
        parent.addView(
            composeHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM
            )
        )
    }

    private fun hideNativeTabChildren() {
        for (index in 0 until bottomTab.childCount) {
            val child = bottomTab.getChildAt(index)
            if (originalChildren.none { it.view === child }) {
                originalChildren += OriginalChild(child, child.visibility)
            }
            if (child.visibility != View.GONE) child.visibility = View.GONE
        }
    }

    private fun isHierarchyCurrent(): Boolean {
        return bottomTab.parent === parent &&
            bottomTab.rootView === root &&
            parent.rootView === root &&
            sourceView.rootView === root &&
            clickTargets.filterNotNull().all { isDescendant(bottomTab, it) }
    }

    private fun shouldShowOverlay(): Boolean {
        return bottomTab.isAttachedToWindow &&
            bottomTab.windowVisibility == View.VISIBLE &&
            bottomTab.isShown
    }

    private fun onTabClicked(index: Int) {
        if (!applied.get() || index !in clickTargets.indices) return
        val target = clickTargets[index] ?: return
        val previousIndex = state.selectedIndex
        val config = configState.value
        performBottomBarHaptic(
            composeHost,
            config.vibrationEnabled,
            config.vibrationStrength
        )
        pendingSelectedIndex = index
        pendingSelectedAt = SystemClock.uptimeMillis()
        composeHost.removeCallbacks(pendingSelectionTimeout)
        composeHost.postDelayed(pendingSelectionTimeout, PENDING_SELECTION_TIMEOUT_MS)
        state.selectedIndex = index
        runCatching {
            clickListener.onClick(target)
        }
            .onFailure {
                pendingSelectedIndex = null
                composeHost.removeCallbacks(pendingSelectionTimeout)
                state.selectedIndex = previousIndex
                logger("调用微信原生底栏点击失败: index=$index", it)
            }
        composeView.post {
            syncState()
        }
    }

    private fun readInitialState(): CustomBottomBarUiState {
        return CustomBottomBarUiState(
            selectedIndex = (KavaReflector.invoke(getCurIdxMethod, bottomTab) as? Int)
                ?.coerceIn(0, 3) ?: 0,
            mainUnread = (KavaReflector.invoke(getMainTabUnreadMethod, bottomTab) as? Int) ?: 0,
            contactUnread = (KavaReflector.invoke(getContactTabUnreadMethod, bottomTab) as? Int) ?: 0,
            discoveryUnread = (KavaReflector.invoke(getFriendTabUnreadMethod, bottomTab) as? Int) ?: 0,
            showDiscoveryDot = (KavaReflector.invoke(getShowFriendPointMethod, bottomTab) as? Boolean)
                ?: false,
            settingsUnread = (KavaReflector.invoke(getSettingsTabUnreadMethod, bottomTab) as? Int) ?: 0,
            showSettingsDot = (KavaReflector.invoke(getSettingsPointMethod, bottomTab) as? Boolean)
                ?: false
        )
    }

    private fun restoreOnMain() {
        if (!applied.compareAndSet(true, false)) return
        bottomTab.removeOnAttachStateChangeListener(this)
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
        restoreHierarchy()
        onRestored()
    }

    private fun restoreAfterFailedInstall() {
        applied.set(false)
        bottomTab.removeOnAttachStateChangeListener(this)
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(preDrawListener)
        runCatching { restoreHierarchy() }
            .onFailure { logger("自定义底栏挂载失败后恢复原底栏失败", it) }
    }

    private fun restoreHierarchy() {
        composeHost.removeCallbacks(pendingSelectionTimeout)
        pendingSelectedIndex = null
        runCatching { composeView.disposeComposition() }
        runCatching { (composeHost.parent as? ViewGroup)?.removeView(composeHost) }
            .onFailure { logger("移除悬浮底栏宿主失败", it) }
        runCatching { (composeView.parent as? ViewGroup)?.removeView(composeView) }
            .onFailure { logger("移除悬浮底栏 ComposeView 失败", it) }
        runCatching { owner.clear(composeView) }
            .onFailure { logger("清理悬浮底栏 ComposeView Owner 失败", it) }
        runCatching { owner.clear(composeHost) }
            .onFailure { logger("清理悬浮底栏宿主 Owner 失败", it) }
        runCatching { owner.destroy() }
            .onFailure { logger("销毁悬浮底栏 Compose Owner 失败", it) }

        originalChildren.forEach { child ->
            if (child.view.parent === bottomTab) child.view.visibility = child.visibility
        }
        bottomTab.background = originalBackground
        bottomTab.layoutParams = originalLayoutParams
        bottomTab.visibility = originalVisibility
        parent.clipChildren = originalClipChildren
        parent.clipToPadding = originalClipToPadding
        clickTargets.fill(null)
        restoreBottomBlur(root)
        navigationAppearance.restore()
    }

    private fun findTaggedTab(view: View, index: Int): ViewGroup? {
        if (view !== bottomTab && view.tag == index && view is ViewGroup) return view
        if (view !is ViewGroup) return null
        for (childIndex in 0 until view.childCount) {
            findTaggedTab(view.getChildAt(childIndex), index)?.let { return it }
        }
        return null
    }

    private fun findNativeTabIcon(view: View): View? {
        if (view.javaClass.name == NATIVE_TAB_ICON_VIEW_CLASS) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findNativeTabIcon(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun isDescendant(parent: ViewGroup, child: View): Boolean {
        var current: Any? = child
        while (current is View) {
            if (current === parent) return true
            current = current.parent
        }
        return false
    }

    private data class OriginalChild(
        val view: View,
        val visibility: Int
    )

    private companion object {
        const val NATIVE_TAB_ICON_VIEW_CLASS = "com.tencent.mm.ui.TabIconView"
        const val STATE_SYNC_FALLBACK_INTERVAL_MS = 500L
        const val PENDING_SELECTION_TIMEOUT_MS = 1_200L
    }
}

private class BottomBarNavigationAppearance(private val window: Window) {
    private val originalSystemUiVisibility = window.decorView.systemUiVisibility
    private val originalNavigationBarColor = window.navigationBarColor
    private val originalDrawsSystemBarBackgrounds =
        (window.attributes.flags and WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS) != 0
    private val originalContrastEnforced = if (Build.VERSION.SDK_INT >= 29) {
        window.isNavigationBarContrastEnforced
    } else {
        false
    }
    private var applied = false

    fun enforce() {
        val decor = window.decorView
        val layoutFlags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if ((decor.systemUiVisibility and layoutFlags) != layoutFlags) {
            decor.systemUiVisibility = decor.systemUiVisibility or layoutFlags
        }
        if (!originalDrawsSystemBarBackgrounds) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        if (window.navigationBarColor != Color.TRANSPARENT) {
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced) {
            window.isNavigationBarContrastEnforced = false
        }
        applied = true
    }

    fun restore() {
        if (!applied) return
        window.decorView.systemUiVisibility = originalSystemUiVisibility
        window.navigationBarColor = originalNavigationBarColor
        if (!originalDrawsSystemBarBackgrounds) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = originalContrastEnforced
        }
        applied = false
    }
}

private class BottomBarComposeOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner,
    NavigationEventDispatcherOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private val navigationDispatcher = NavigationEventDispatcher()
    private val composeRuntime = lazy(LazyThreadSafetyMode.NONE) {
        EmbeddedComposeRuntime(lifecycleRegistry)
    }
    private var restored = false

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val navigationEventDispatcher: NavigationEventDispatcher
        get() = navigationDispatcher

    fun install(view: View) {
        EmbeddedComposeOwnerInstaller.install(view, this, this, this, this)
    }

    fun clear(view: View) {
        EmbeddedComposeOwnerInstaller.clear(view)
    }

    fun installComposition(view: ComposeView) {
        composeRuntime.value.install(view)
    }

    fun attach() {
        if (!restored) {
            savedStateRegistryController.performRestore(Bundle.EMPTY)
            restored = true
        }
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        if (composeRuntime.isInitialized()) composeRuntime.value.destroy()
        navigationDispatcher.dispose()
        store.clear()
    }
}
