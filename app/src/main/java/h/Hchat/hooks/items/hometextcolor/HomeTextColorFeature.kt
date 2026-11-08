package h.Hchat.hooks.items.hometextcolor

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XCallback
import h.Hchat.R
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class HomeTextColorFeature : BaseFeature() {
    private var runtime: HomeTextColorRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "首页文字颜色"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(HomeTextColorSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = HomeTextColorRuntime(context)
        if (runtime?.install(allowDexSearch = false) != true) scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.install(allowDexSearch = true) == true
        }
    }

    companion object {
        const val ID = "home_text_color"
    }
}

private class HomeTextColorRuntime(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        HomeTextColorSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), METHOD_CACHE_PREFS)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val trackedRows = Collections.synchronizedMap(WeakHashMap<View, RowKind>())
    private val deferredConversationRows = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val modernConversationRows = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<View>>()
    )
    private val installedConversationMethods = ConcurrentHashMap.newKeySet<Method>()
    private val trackedFixedViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val neatClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val noMeasuredClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val noMeasuredAccessCache = ConcurrentHashMap<Class<*>, NoMeasuredTextAccess>()
    private val noMeasuredAccessMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val colorApplyFailures = ConcurrentHashMap.newKeySet<Class<*>>()
    private val wrappedTextMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val wrappedTextMethodMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val holderRootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val viewResourceNameCache = ConcurrentHashMap<Int, String>()
    private val viewResourceNameMisses = ConcurrentHashMap.newKeySet<Int>()
    private val neatTextMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val neatTextMethodMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val neatColorMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val neatColorMethodMisses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val gradientCache = LruCache<GradientKey, LinearGradient>(GRADIENT_CACHE_SIZE)
    private val colorStateCache = LruCache<Int, ColorStateList>(COLOR_STATE_CACHE_SIZE)

    @Volatile private var config = readConfig()
    @Volatile private var conversationInstalled = false
    @Volatile private var modernConversationHolderInstalled = false
    @Volatile private var modernConversationBindInstalled = false
    @Volatile private var modernConversationUnavailable = false
    @Volatile private var contactsInstalled = false
    @Volatile private var preferenceInstalled = false
    @Volatile private var layoutInflaterInstalled = false
    @Volatile private var recyclerInstalled = false
    @Volatile private var recyclerPayloadInstalled = false
    @Volatile private var destroyed = false

    private val settingsRefresh = Runnable {
        if (destroyed) return@Runnable
        config = readConfig()
        refreshTrackedRows()
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != HomeTextColorSettings.KEY_ENABLE &&
            key != HomeTextColorSettings.KEY_TITLE_COLOR &&
            key != HomeTextColorSettings.KEY_SUBTITLE_COLOR
        ) {
            return@OnSharedPreferenceChangeListener
        }
        mainHandler.removeCallbacks(settingsRefresh)
        mainHandler.post(settingsRefresh)
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    @Synchronized
    fun install(allowDexSearch: Boolean): Boolean {
        if (destroyed) return false
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        conversationInstalled = installConversationAdapterHooks(
            locateConversationGetViews(runtimeKey, allowDexSearch)
        ) || conversationInstalled
        installModernConversationHooks(runtimeKey, allowDexSearch)
        if (!contactsInstalled) {
            contactsInstalled = installAdapterHook(
                locateAdapterGetView(
                    runtimeKey,
                    CACHE_CONTACTS,
                    ANCHOR_CONTACTS,
                    OWNER_CONTACTS,
                    anchorOnMethod = false,
                    allowDexSearch = allowDexSearch
                ),
                RowKind.CONTACTS
            )
        }
        if (!preferenceInstalled) {
            preferenceInstalled = installAdapterHook(
                locateAdapterGetView(
                    runtimeKey,
                    CACHE_PREFERENCE,
                    ANCHOR_PREFERENCE,
                    OWNER_PREFERENCE,
                    anchorOnMethod = true,
                    allowDexSearch = allowDexSearch
                ),
                RowKind.PREFERENCE
            )
        }
        if (!layoutInflaterInstalled) {
            layoutInflaterInstalled = installLayoutInflaterHook(
                locateLayoutInflaterFactory(runtimeKey, allowDexSearch)
            )
        }
        if (!recyclerInstalled) {
            recyclerInstalled = installRecyclerAdapterHook(
                locateRecyclerAdapterBind(
                    runtimeKey,
                    CACHE_RECYCLER_BIND,
                    payload = false
                ),
                "完整"
            )
        }
        if (!recyclerPayloadInstalled) {
            recyclerPayloadInstalled = installRecyclerAdapterHook(
                locateRecyclerAdapterBind(
                    runtimeKey,
                    CACHE_RECYCLER_PAYLOAD_BIND,
                    payload = true
                ),
                "局部"
            )
        }
        return conversationInstalled && contactsInstalled && preferenceInstalled &&
            layoutInflaterInstalled &&
            recyclerInstalled && recyclerPayloadInstalled
    }

    private fun locateConversationGetViews(
        runtimeKey: String,
        allowDexSearch: Boolean
    ): List<Method> {
        val methods = CONVERSATION_ADAPTER_SPECS.flatMap { spec ->
            locateConversationGetViews(runtimeKey, spec, allowDexSearch)
        }.distinctBy(::methodDescriptor)
        if (allowDexSearch && methods.isEmpty()) {
            HLog.e(
                "$TAG 未找到旧式首页或分组会话绑定入口: " +
                    "anchors=${CONVERSATION_ADAPTER_SPECS.joinToString { it.anchor }}"
            )
        }
        return methods
    }

    private fun locateConversationGetViews(
        runtimeKey: String,
        spec: ConversationAdapterSpec,
        allowDexSearch: Boolean
    ): List<Method> {
        val cached = DexMethodCache.loadList(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            spec.cacheName
        ).filter { method -> isConversationAdapterGetView(method, spec) }
        if (cached.isNotEmpty()) return cached
        DexMethodCache.clear(methodPrefs, runtimeKey, spec.cacheName)
        if (!allowDexSearch) return emptyList()

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingStrings(listOf(spec.anchor)) })
                }
            ).asSequence()
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .filter { method -> isConversationAdapterGetView(method, spec) }
                .distinctBy(::methodDescriptor)
                .toList()
        }.getOrElse {
            HLog.e("$TAG 定位 ${spec.anchor} 失败: ${it.message}", it)
            return emptyList()
        }
        if (candidates.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, runtimeKey, spec.cacheName, candidates)
        }
        return candidates
    }

    private fun isConversationAdapterGetView(
        method: Method,
        spec: ConversationAdapterSpec
    ): Boolean {
        return isAdapterGetView(method) &&
            (!spec.requireConversationOwner ||
                method.declaringClass.name.startsWith(OWNER_CONVERSATION))
    }

    fun destroy() {
        destroyed = true
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        mainHandler.removeCallbacks(settingsRefresh)
        runOnMain {
            val rows = synchronized(trackedRows) {
                trackedRows.keys.toList().also { trackedRows.clear() }
            }
            val fixedViews = synchronized(trackedFixedViews) {
                trackedFixedViews.keys.toList().also { trackedFixedViews.clear() }
            }
            synchronized(deferredConversationRows) { deferredConversationRows.clear() }
            synchronized(modernConversationRows) { modernConversationRows.clear() }
            rows.forEach(::restoreRow)
            fixedViews.forEach(::restoreOwner)
            gradientCache.evictAll()
            colorStateCache.evictAll()
        }
    }

    private fun locateAdapterGetView(
        runtimeKey: String,
        cacheName: String,
        anchor: String,
        ownerPrefix: String,
        anchorOnMethod: Boolean,
        allowDexSearch: Boolean
    ): Method? {
        DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), cacheName)
            ?.takeIf {
                isAdapterGetView(it) && it.declaringClass.name.startsWith(ownerPrefix)
            }
            ?.let { return it }
        DexMethodCache.clear(methodPrefs, runtimeKey, cacheName)
        if (!allowDexSearch) return null

        val candidates = runCatching {
            val methods = if (anchorOnMethod) {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply { usingStrings(listOf(anchor)) })
                    }
                ).mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
            } else {
                context.dexKitBridge().findClass(
                    FindClass().apply {
                        matcher(ClassMatcher().apply { usingStrings(listOf(anchor)) })
                    }
                ).asSequence()
                    .mapNotNull { KavaReflector.loadClass(it.name, context.hostClassLoader()) }
                    .flatMap { KavaReflector.declaredMethods(it).asSequence() }
                    .toList()
            }
            methods.asSequence()
                .filter { it.declaringClass.name.startsWith(ownerPrefix) }
                .filter(::isAdapterGetView)
                .distinctBy(::methodDescriptor)
                .toList()
        }.getOrElse {
            HLog.e("$TAG 定位 $anchor 失败: ${it.message}", it)
            emptyList()
        }
        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, cacheName, method)
        } else {
            HLog.e("$TAG 未找到唯一列表绑定入口: anchor=$anchor count=${candidates.size}")
        }
        return method
    }

    private fun installAdapterHook(method: Method?, kind: RowKind): Boolean {
        if (method == null) return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    (param.args?.getOrNull(1) as? View)?.let {
                        deferredConversationRows.remove(it)
                        restoreRow(it)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (destroyed || param.hasThrowable()) return
                    val row = param.result as? View ?: return
                    if (row !== param.args?.getOrNull(1)) restoreRow(row)
                    applyBoundRow(row, kind)
                }
            })
            true
        }.getOrElse {
            HLog.e("$TAG 安装${kind.label}列表绑定 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun installConversationAdapterHooks(methods: List<Method>): Boolean {
        methods.forEach { method ->
            if (method in installedConversationMethods) return@forEach
            if (installAdapterHook(method, RowKind.CONVERSATION)) {
                installedConversationMethods += method
            }
        }
        return installedConversationMethods.isNotEmpty()
    }

    private fun installModernConversationHooks(runtimeKey: String, allowDexSearch: Boolean) {
        if (modernConversationUnavailable ||
            (modernConversationHolderInstalled && modernConversationBindInstalled)
        ) return
        val methods = locateModernConversationMethods(runtimeKey, allowDexSearch) ?: return
        if (!modernConversationHolderInstalled) {
            modernConversationHolderInstalled = runCatching {
                HookRegistry.get().hook(
                    methods.holder,
                    object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (destroyed || param.hasThrowable()) return
                            val holder = param.args?.getOrNull(0) ?: return
                            val row = param.args?.getOrNull(1) as? View ?: return
                            modernConversationRows[holder] = WeakReference(row)
                        }
                    }
                )
                true
            }.getOrElse {
                HLog.e(
                    "$TAG 安装高版本首页会话 Holder 初始化 Hook 失败: " +
                        "${methodDescriptor(methods.holder)} ${it.message}",
                    it
                )
                false
            }
        }
        if (!modernConversationBindInstalled) {
            modernConversationBindInstalled = runCatching {
                HookRegistry.get().hook(
                    methods.bind,
                    object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val holder = param.args?.getOrNull(1) ?: return
                            val row = modernConversationRows[holder]?.get() ?: return
                            deferredConversationRows.remove(row)
                            restoreRow(row)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (destroyed || param.hasThrowable()) return
                            val holder = param.args?.getOrNull(1) ?: return
                            val row = modernConversationRows[holder]?.get() ?: return
                            applyBoundRow(row, RowKind.CONVERSATION)
                        }
                    }
                )
                true
            }.getOrElse {
                HLog.e(
                    "$TAG 安装高版本首页会话内容绑定 Hook 失败: " +
                        "${methodDescriptor(methods.bind)} ${it.message}",
                    it
                )
                false
            }
        }
    }

    private fun locateModernConversationMethods(
        runtimeKey: String,
        allowDexSearch: Boolean
    ): ModernConversationMethods? {
        val cachedBind = DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_MODERN_CONVERSATION_BIND
        )?.takeIf(::isModernConversationBind)
        val cachedHolder = DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_MODERN_CONVERSATION_HOLDER
        )?.takeIf { method ->
            cachedBind != null && isModernConversationHolder(method, cachedBind.parameterTypes[1])
        }
        if (cachedBind != null && cachedHolder != null) {
            return ModernConversationMethods(cachedHolder, cachedBind)
        }
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_MODERN_CONVERSATION_BIND)
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_MODERN_CONVERSATION_HOLDER)
        if (!allowDexSearch) return null

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(MODERN_CONVERSATION_BIND_ANCHORS)
                        }
                    )
                }
            ).asSequence()
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .filter(::isModernConversationBind)
                .distinctBy(::methodDescriptor)
                .toList()
        }.getOrElse {
            HLog.e("$TAG 定位高版本首页会话绑定入口失败: ${it.message}", it)
            return null
        }
        val bind = candidates.singleOrNull()
        if (bind == null) {
            if (candidates.size > 1) {
                HLog.e(
                    "$TAG 高版本首页会话绑定入口不唯一: count=${candidates.size} " +
                        "methods=${candidates.joinToString(transform = ::methodDescriptor)}"
                )
            }
            modernConversationUnavailable = true
            return null
        }
        val declaredMethods = KavaReflector.declaredMethods(bind.declaringClass)
        val holder = declaredMethods.singleOrNull {
            isModernConversationHolder(it, bind.parameterTypes[1])
        }
        if (holder == null) {
            HLog.e(
                "$TAG 未找到高版本首页会话 Holder 初始化入口: class=${bind.declaringClass.name} " +
                    "holder=${bind.parameterTypes[1].name}"
            )
            modernConversationUnavailable = true
            return null
        }
        DexMethodCache.save(methodPrefs, runtimeKey, CACHE_MODERN_CONVERSATION_BIND, bind)
        DexMethodCache.save(methodPrefs, runtimeKey, CACHE_MODERN_CONVERSATION_HOLDER, holder)
        return ModernConversationMethods(holder, bind)
    }

    private fun isModernConversationBind(method: Method): Boolean {
        val types = method.parameterTypes
        val ownerPackage = method.declaringClass.name.substringBeforeLast('.', "")
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE &&
            types.size == 4 &&
            types[0] == Int::class.javaPrimitiveType &&
            types[1].name.substringBeforeLast('.', "") == ownerPackage &&
            types[2].name.substringBeforeLast('.', "") == ownerPackage &&
            types[3].name.startsWith(MODERN_CONVERSATION_STORAGE_PREFIX)
    }

    private fun isModernConversationHolder(method: Method, holderClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE &&
            types.contentEquals(arrayOf(holderClass, View::class.java))
    }

    private fun locateLayoutInflaterFactory(
        runtimeKey: String,
        allowDexSearch: Boolean
    ): Method? {
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_LAYOUT_INFLATER_FACTORY
        )?.takeIf(::isLayoutInflaterFactoryMethod)?.let { return it }
        DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_LAYOUT_INFLATER_FACTORY)
        if (!allowDexSearch) return null

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            name("onCreateView")
                            returnType("android.view.View")
                            paramTypes(
                                "java.lang.String",
                                "android.content.Context",
                                "android.util.AttributeSet"
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isLayoutInflaterFactoryMethod)
                .distinctBy(::methodDescriptor)
        }.getOrElse {
            HLog.e("$TAG 定位微信布局创建入口失败: ${it.message}", it)
            emptyList()
        }
        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_LAYOUT_INFLATER_FACTORY, method)
        } else {
            HLog.e(
                "$TAG 未找到唯一微信布局创建入口: count=${candidates.size} " +
                    "methods=${candidates.joinToString(transform = ::methodDescriptor)}"
            )
        }
        return method
    }

    private fun isLayoutInflaterFactoryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "onCreateView" &&
            method.declaringClass.name.startsWith("com.tencent.mm.ui.") &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == View::class.java &&
            types.contentEquals(
                arrayOf(
                    String::class.java,
                    Context::class.java,
                    AttributeSet::class.java
                )
            )
    }

    private fun installLayoutInflaterHook(method: Method?): Boolean {
        if (method == null) return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (destroyed || param.hasThrowable()) return
                    val view = param.result as? View ?: return
                    if (viewAnchor(view) !in FIXED_CONTACT_ENTRY_ANCHORS) return
                    val creationContext = param.args?.getOrNull(1) as? Context
                    if (!isContactsPage(view.context) && !isContactsPage(creationContext) &&
                        !isLauncherPage(view.context) && !isLauncherPage(creationContext)
                    ) return
                    applyFixedContactEntry(view)
                }
            })
            true
        }.getOrElse {
            HLog.e("$TAG 安装微信布局创建 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun applyFixedContactEntry(view: View) {
        val anchor = viewAnchor(view)
        if (anchor !in FIXED_CONTACT_ENTRY_ANCHORS) return
        if (!isNeatTextView(view) && !isNoMeasuredTextView(view) && view !is TextView) return
        if (anchor in AMBIGUOUS_FIXED_CONTACT_ENTRY_ANCHORS) {
            view.post {
                if (destroyed || !hasContactOwner(view)) return@post
                trackAndApplyFixedContactEntry(view)
            }
            return
        }
        trackAndApplyFixedContactEntry(view)
    }

    private fun trackAndApplyFixedContactEntry(view: View) {
        trackedFixedViews[view] = true
        applyFixedContactEntryColor(view)
        view.post {
            if (destroyed || !trackedFixedViews.containsKey(view)) return@post
            applyFixedContactHeader(findFixedContactHeaderRoot(view))
        }
    }

    private fun hasContactOwner(view: View): Boolean {
        var current: View? = view
        repeat(MAX_FIXED_HEADER_ANCESTORS) {
            if (current?.javaClass?.name?.startsWith(OWNER_CONTACTS) == true) return true
            current = current?.parent as? View ?: return false
        }
        return false
    }

    private fun findFixedContactHeaderRoot(anchor: View): View {
        var root = anchor
        repeat(MAX_FIXED_HEADER_ANCESTORS) {
            val parent = root.parent as? View ?: return root
            if (parent.javaClass.name.contains("RecyclerView")) return root
            root = parent
        }
        return root
    }

    private fun applyFixedContactHeader(root: View) {
        if (isFixedContactTitle(root, root)) {
            trackedFixedViews[root] = true
            applyFixedContactEntryColor(root)
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                applyFixedContactHeader(root.getChildAt(index), root)
            }
        }
    }

    private fun applyFixedContactHeader(view: View, headerRoot: View) {
        if (isFixedContactTitle(view, headerRoot)) {
            trackedFixedViews[view] = true
            applyFixedContactEntryColor(view)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyFixedContactHeader(view.getChildAt(index), headerRoot)
            }
        }
    }

    private fun isFixedContactTitle(view: View, headerRoot: View): Boolean {
        if (!isNeatTextView(view) && !isNoMeasuredTextView(view) && view !is TextView) return false
        val anchor = viewAnchor(view)
        if (anchor in AMBIGUOUS_FIXED_CONTACT_ENTRY_ANCHORS) return hasContactOwner(view)
        if (anchor in FIXED_CONTACT_ENTRY_ANCHORS) return true
        var current = view.parent as? View
        while (current != null) {
            if (viewAnchor(current) in OFFICIAL_CONTACT_ENTRY_CONTAINERS) return true
            if (current === headerRoot) break
            current = current.parent as? View
        }
        return false
    }

    private fun applyFixedContactEntryColor(view: View) {
        val spec = config.takeIf { it.enabled }?.title
        if (spec == null) {
            restoreOwner(view)
            return
        }
        val candidate = createCandidate(view, view, allowBlank = true) ?: return
        applyColor(candidate, spec)
    }

    private fun locateRecyclerAdapterBind(
        runtimeKey: String,
        cacheName: String,
        payload: Boolean
    ): Method? {
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            cacheName
        )?.takeIf { isRecyclerAdapterBind(it, payload) }?.let { return it }
        DexMethodCache.clear(methodPrefs, runtimeKey, cacheName)

        val adapterClass = KavaReflector.loadClass(
            WX_RECYCLER_ADAPTER,
            context.hostClassLoader()
        ) ?: return null
        val candidates = KavaReflector.declaredMethods(adapterClass)
            .asSequence()
            .filter { isRecyclerAdapterBind(it, payload) }
            .toList()
        val method = candidates.singleOrNull()
        if (method == null) {
            HLog.e(
                "$TAG 未找到唯一 WxRecyclerAdapter ${if (payload) "局部" else "完整"}绑定入口: " +
                    "count=${candidates.size} " +
                    "methods=${candidates.joinToString(transform = ::methodDescriptor)}"
            )
        } else {
            DexMethodCache.save(methodPrefs, runtimeKey, cacheName, method)
        }
        return method
    }

    private fun isRecyclerAdapterBind(method: Method, payload: Boolean): Boolean {
        val types = method.parameterTypes
        return method.declaringClass.name == WX_RECYCLER_ADAPTER &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE &&
            types.size == (if (payload) 3 else 2) &&
            types[1] == Int::class.javaPrimitiveType &&
            types[0].name.startsWith("androidx.recyclerview.widget.") &&
            (!payload || List::class.java.isAssignableFrom(types[2]))
    }

    private fun installRecyclerAdapterHook(method: Method?, label: String): Boolean {
        if (method == null) return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    findRootView(param.args?.getOrNull(0))?.let {
                        deferredConversationRows.remove(it)
                        restoreRow(it)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (destroyed || param.hasThrowable()) return
                    val root = findRootView(param.args?.getOrNull(0)) ?: return
                    val kind = resolveRecyclerRowKind(root) ?: run {
                        trackedRows.remove(root)
                        return
                    }
                    applyBoundRow(root, kind)
                }
            })
            true
        }.getOrElse {
            HLog.e("$TAG 安装 WxRecyclerAdapter $label 绑定 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun findRootView(holder: Any?): View? {
        if (holder == null) return null
        (KavaReflector.readField(holder, "itemView") as? View)?.let { return it }
        val field = holderRootFieldCache[holder.javaClass] ?: run {
            var current: Class<*>? = holder.javaClass
            var found: Field? = null
            while (current != null && current != Any::class.java) {
                found = KavaReflector.declaredFields(current).firstOrNull {
                    it.name == "itemView" || it.type == View::class.java
                }
                if (found != null) break
                current = current.superclass
            }
            found?.also { holderRootFieldCache.putIfAbsent(holder.javaClass, it) }
        }
        return field?.let { KavaReflector.readField(it, holder) as? View }
    }

    private fun resolveRecyclerRowKind(row: View): RowKind? {
        val anchors = recyclerAnchorMask(row)
        if (anchors and ANCHOR_MASK_CONVERSATION == ANCHOR_MASK_CONVERSATION) {
            return RowKind.CONVERSATION
        }
        if (isContactsPage(row.context)) return RowKind.CONTACTS
        if (isLauncherPage(row.context) &&
            anchors and ANCHOR_MASK_CONTACTS == ANCHOR_MASK_CONTACTS
        ) {
            return RowKind.CONTACTS
        }
        return null
    }

    private fun recyclerAnchorMask(view: View): Int {
        val anchor = viewAnchor(view)
        var result = 0
        if (anchor in TITLE_ANCHORS) result = result or ANCHOR_MASK_CONVERSATION_TITLE
        if (anchor in SUBTITLE_ANCHORS) result = result or ANCHOR_MASK_CONVERSATION_SUBTITLE
        if (anchor in TIME_ANCHORS) result = result or ANCHOR_MASK_CONVERSATION_TIME
        if (anchor in CONTACT_TITLE_ANCHORS) result = result or ANCHOR_MASK_CONTACT_TITLE
        if (anchor in CONTACT_SUBTITLE_ANCHORS) result = result or ANCHOR_MASK_CONTACT_SUBTITLE
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                result = result or recyclerAnchorMask(view.getChildAt(index))
                if (result and ANCHOR_MASK_CONVERSATION == ANCHOR_MASK_CONVERSATION ||
                    result and ANCHOR_MASK_CONTACTS == ANCHOR_MASK_CONTACTS
                ) return result
            }
        }
        return result
    }

    private fun isLauncherPage(context: Context?): Boolean {
        var current: Context? = context
        while (current != null) {
            val name = current.javaClass.name
            if (name == "com.tencent.mm.ui.LauncherUI" || name == "com.tencent.mm.ui.HomeUI") {
                return true
            }
            val base = (current as? ContextWrapper)?.baseContext ?: break
            if (base === current) break
            current = base
        }
        return false
    }

    private fun isContactsPage(context: Context?): Boolean {
        var current: Context? = context
        while (current != null) {
            val name = current.javaClass.name
            if (name.startsWith("com.tencent.mm.ui.contact.") ||
                name.contains("AddressUI") ||
                name.contains("ContactListUI")
            ) return true
            val base = (current as? ContextWrapper)?.baseContext ?: break
            if (base === current) break
            current = base
        }
        return context is Activity && context.javaClass.name.contains("contact", ignoreCase = true)
    }

    private fun applyBoundRow(
        row: View,
        kind: RowKind,
        scheduleDeferredConversationRefresh: Boolean = true
    ) {
        if (kind == RowKind.PREFERENCE &&
            (!isLauncherPage(row.context) || isExcludedPreferencePage(row))
        ) {
            restoreRow(row)
            trackedRows.remove(row)
            return
        }
        trackedRows[row] = kind
        val snapshot = config
        if (!snapshot.enabled || (snapshot.title == null && snapshot.subtitle == null)) return

        val candidates = collectCandidates(row)
        if (candidates.isEmpty()) {
            if (kind == RowKind.CONVERSATION && scheduleDeferredConversationRefresh) {
                scheduleConversationRefresh(row, kind)
            }
            return
        }
        val targets = resolveTargets(row, kind, candidates)
        if (targets.isEmpty()) {
            if (kind == RowKind.CONVERSATION && scheduleDeferredConversationRefresh) {
                scheduleConversationRefresh(row, kind)
            }
            return
        }

        val appliedOwners = ArrayList<View>(targets.size)
        targets.forEach { target ->
            val spec = when (target.role) {
                TextRole.TITLE -> snapshot.title
                TextRole.SUBTITLE -> snapshot.subtitle
            } ?: return@forEach
            if (applyColor(target.candidate, spec)) {
                appliedOwners += target.candidate.owner
            }
        }
        if (appliedOwners.isNotEmpty()) {
            row.setTag(
                R.id.hchat_home_text_color_row_state,
                RowState(appliedOwners)
            )
        }
        if (kind == RowKind.CONVERSATION && scheduleDeferredConversationRefresh) {
            scheduleConversationRefresh(row, kind)
        }
    }

    private fun scheduleConversationRefresh(row: View, kind: RowKind) {
        synchronized(deferredConversationRows) {
            if (deferredConversationRows.containsKey(row)) return
            deferredConversationRows[row] = true
        }
        row.post {
            deferredConversationRows.remove(row)
            if (destroyed || trackedRows[row] != kind) return@post
            restoreRow(row)
            applyBoundRow(row, kind, scheduleDeferredConversationRefresh = false)
        }
    }

    private fun refreshTrackedRows() {
        if (destroyed) return
        val rows = synchronized(trackedRows) { trackedRows.entries.map { it.key to it.value } }
        rows.forEach { (row, kind) ->
            if (row.isAttachedToWindow) {
                restoreRow(row)
                applyBoundRow(row, kind)
            } else {
                restoreRow(row)
            }
        }
        val fixedViews = synchronized(trackedFixedViews) { trackedFixedViews.keys.toList() }
        fixedViews.forEach { view ->
            if (view.isAttachedToWindow) {
                restoreOwner(view)
                applyFixedContactEntryColor(view)
            } else {
                restoreOwner(view)
            }
        }
    }

    private fun restoreRow(row: View) {
        val state = row.getTag(R.id.hchat_home_text_color_row_state) as? RowState ?: return
        state.owners.forEach(::restoreOwner)
        row.setTag(R.id.hchat_home_text_color_row_state, null)
    }

    private fun applyColor(
        candidate: TextCandidate,
        spec: MemberTitleStore.ColorSpec
    ): Boolean {
        val owner = candidate.owner
        restoreOwner(owner)
        val textView = candidate.textView
        val paint = textPaint(owner, textView) ?: return false
        val originalColors = textColors(owner, textView) ?: return false
        val originalShader = paint.shader
        val appliedColors = colorStateCache.get(spec.startColor) ?: ColorStateList(
            arrayOf(IntArray(0)),
            intArrayOf(spec.startColor)
        ).also { colorStateCache.put(spec.startColor, it) }
        if (!setTextColors(owner, textView, appliedColors)) {
            if (colorApplyFailures.add(owner.javaClass)) {
                HLog.e("$TAG 文字颜色写入失败: class=${owner.javaClass.name}")
            }
            return false
        }
        val confirmedAppliedColors = textColors(owner, textView) ?: appliedColors
        val state = AppliedState(
            textView = textView,
            originalColors = originalColors,
            originalShader = originalShader,
            appliedColors = confirmedAppliedColors
        )
        owner.setTag(R.id.hchat_home_text_color_state, state)
        if (spec.isGradient || originalShader != null) {
            val width = textWidth(owner, textView, candidate.text, paint)
            if (width > 0f) {
                val key = GradientKey(spec.startColor, spec.endColor, width.toBits())
                val shader = gradientCache.get(key) ?: LinearGradient(
                    0f, 0f, width, 0f,
                    spec.startColor, spec.endColor,
                    Shader.TileMode.CLAMP
                ).also { gradientCache.put(key, it) }
                state.appliedShader = shader
                paint.shader = shader
            }
        }
        owner.invalidate()
        return true
    }

    private fun restoreOwner(owner: View) {
        val state = owner.getTag(R.id.hchat_home_text_color_state) as? AppliedState ?: return
        val paint = textPaint(owner, state.textView)
        when {
            paint == null -> Unit
            state.appliedShader != null && paint.shader === state.appliedShader -> {
                paint.shader = state.originalShader
            }
        }
        if (textColors(owner, state.textView) === state.appliedColors) {
            setTextColors(owner, state.textView, state.originalColors)
        }
        owner.setTag(R.id.hchat_home_text_color_state, null)
        owner.invalidate()
    }

    private fun collectCandidates(row: View): List<TextCandidate> {
        val result = ArrayList<TextCandidate>(6)
        collectCandidates(row, row, result)
        return result
    }

    private fun collectCandidates(view: View, row: View, result: MutableList<TextCandidate>) {
        if (view.visibility != View.VISIBLE) return
        if (isNeatTextView(view) || isNoMeasuredTextView(view)) {
            createCandidate(view, row)?.let(result::add)
            return
        }
        if (view is TextView) {
            createCandidate(view, row)?.let(result::add)
            return
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectCandidates(view.getChildAt(index), row, result)
            }
        }
    }

    private fun createCandidate(
        owner: View,
        row: View,
        allowBlank: Boolean = false
    ): TextCandidate? {
        val textView = targetTextView(owner)
        if (textView == null && !isNoMeasuredTextView(owner)) return null
        if (textView is EditText || textView is Button || textView is CompoundButton) return null
        val rendered = renderedText(owner, textView)
        if (rendered.isBlank() && !allowBlank) return null
        val textSize = textSize(owner, textView) ?: return null
        val text = rendered.toString()
        val position = relativePosition(owner, row)
        return TextCandidate(
            owner = owner,
            textView = textView,
            text = text,
            textSize = textSize,
            left = (position shr 32).toInt(),
            top = position.toInt(),
            hasBackground = owner.background != null || textView?.background != null,
            isNoMeasured = isNoMeasuredTextView(owner)
        )
    }

    private fun resolveTargets(
        row: View,
        kind: RowKind,
        candidates: List<TextCandidate>
    ): List<Target> {
        val usable = candidates.filterNot { isBadge(it) }
        if (usable.isEmpty()) return emptyList()
        val title = when (kind) {
            RowKind.CONVERSATION -> resolveConversationTitle(row, usable)
            RowKind.CONTACTS -> resolveContactTitle(usable)
            RowKind.PREFERENCE -> resolvePreferenceTitle(usable)
        } ?: return emptyList()

        val targets = ArrayList<Target>(3)
        targets += Target(title, TextRole.TITLE)
        when (kind) {
            RowKind.CONVERSATION -> resolveConversationSubtitles(row, title, usable)
                .forEach { targets += Target(it, TextRole.SUBTITLE) }
            RowKind.CONTACTS -> {
                resolveContactSubtitle(title, usable)
                    ?.let { targets += Target(it, TextRole.SUBTITLE) }
                findAnchoredCandidate(usable, CONTACT_SECTION_ANCHORS)
                    ?.takeIf { section -> targets.none { it.candidate.owner === section.owner } }
                    ?.let { targets += Target(it, TextRole.TITLE) }
            }
            RowKind.PREFERENCE -> resolvePreferenceSubtitles(row, title, usable)
                .forEach { targets += Target(it, TextRole.SUBTITLE) }
        }
        return targets
    }

    private fun resolveConversationTitle(row: View, candidates: List<TextCandidate>): TextCandidate? {
        findAnchoredCandidate(candidates, TITLE_ANCHORS)?.let { return it }
        val maxSize = candidates.maxOfOrNull { it.textSize } ?: return null
        return candidates.asSequence()
            .filterNot { isLikelyTime(row, it, maxSize) }
            .maxWithOrNull(
                compareBy<TextCandidate> { if (it.isNoMeasured) 1 else 0 }
                    .thenBy { it.textSize }
                    .thenBy { -it.left }
                    .thenBy { -it.top }
            )
    }

    private fun resolveConversationSubtitles(
        row: View,
        title: TextCandidate,
        candidates: List<TextCandidate>
    ): List<TextCandidate> {
        val anchored = listOfNotNull(
            findAnchoredCandidate(candidates, SUBTITLE_ANCHORS),
            findAnchoredCandidate(candidates, TIME_ANCHORS)
        ).filter { it.owner !== title.owner }.distinctBy { it.owner }
        if (anchored.isNotEmpty()) return anchored

        val possible = candidates.asSequence()
            .filter { it.owner !== title.owner }
            .filter {
                it.textSize < title.textSize - SIZE_EPSILON ||
                    it.top > title.top ||
                    isLikelyTime(row, it, title.textSize)
            }
            .toList()
        val subtitle = possible.asSequence()
            .filterNot { isLikelyTime(row, it, title.textSize) }
            .maxWithOrNull(
                compareBy<TextCandidate> { if (it.top > title.top) 1 else 0 }
                    .thenBy { if (kotlin.math.abs(it.left - title.left) <= title.textSize * 2f) 1 else 0 }
                .thenBy { if (it.isNoMeasured) 1 else 0 }
                .thenBy { it.textSize }
            )
        val time = possible.asSequence()
            .filter { isLikelyTime(row, it, title.textSize) }
            .maxWithOrNull(
                compareBy<TextCandidate> { if (it.left > title.left) 1 else 0 }
                    .thenBy { it.textSize }
            )
        return listOfNotNull(subtitle, time).distinctBy { it.owner }
    }

    private fun resolveContactTitle(candidates: List<TextCandidate>): TextCandidate? {
        findAnchoredCandidate(candidates, CONTACT_TITLE_ANCHORS)?.let { return it }
        val withoutHeaders = candidates.filterNot(::isContactSectionHeader)
            .ifEmpty { candidates }
        return withoutHeaders.maxWithOrNull(
            compareBy<TextCandidate> { if (it.isNoMeasured) 1 else 0 }
                .thenBy { it.textSize }
                .thenBy { -it.left }
                .thenBy { -it.top }
        )
    }

    private fun resolveContactSubtitle(
        title: TextCandidate,
        candidates: List<TextCandidate>
    ): TextCandidate? {
        findAnchoredCandidate(candidates, CONTACT_SUBTITLE_ANCHORS)
            ?.takeIf { it.owner !== title.owner }
            ?.let { return it }
        return candidates.asSequence()
            .filter { it.owner !== title.owner }
            .filterNot(::isContactSectionHeader)
            .filter { it.textSize < title.textSize - SIZE_EPSILON && it.top >= title.top }
            .maxWithOrNull(
                compareBy<TextCandidate> { if (it.top > title.top) 1 else 0 }
                    .thenBy { it.textSize }
                    .thenBy { -kotlin.math.abs(it.left - title.left) }
            )
    }

    private fun resolvePreferenceTitle(candidates: List<TextCandidate>): TextCandidate? {
        return candidates.maxWithOrNull(
            compareBy<TextCandidate> { it.textSize }
                .thenBy { -it.left }
                .thenBy { -it.top }
        )
    }

    private fun resolvePreferenceSubtitles(
        row: View,
        title: TextCandidate,
        candidates: List<TextCandidate>
    ): List<TextCandidate> {
        val rowWidth = row.width.coerceAtLeast(row.measuredWidth)
        return candidates.asSequence()
            .filter { it.owner !== title.owner }
            .filter {
                it.textSize < title.textSize - SIZE_EPSILON ||
                    it.top > title.top ||
                    (rowWidth > 0 && it.left > rowWidth / 2)
            }
            .sortedWith(
                compareByDescending<TextCandidate> { it.top > title.top }
                    .thenByDescending { it.textSize }
                    .thenBy { it.left }
            )
            .take(MAX_PREFERENCE_SUBTITLES)
            .toList()
    }

    private fun isBadge(candidate: TextCandidate): Boolean {
        if (!candidate.hasBackground || candidate.text.length > 4) return false
        return candidate.text.all { it.isDigit() || it == '+' || it == '·' || it == '•' }
    }

    private fun isContactSectionHeader(candidate: TextCandidate): Boolean {
        val text = candidate.text
        return text.length == 1 && (text[0] in 'A'..'Z' || text[0] == '#') && !candidate.isNoMeasured
    }

    private fun findAnchoredCandidate(
        candidates: List<TextCandidate>,
        anchors: Set<String>
    ): TextCandidate? {
        return candidates.firstOrNull { candidateAnchor(it.owner) in anchors }
    }

    private fun candidateAnchor(view: View): String? {
        return viewAnchor(view)
    }

    private fun viewAnchor(view: View): String? {
        (view.tag as? String)?.let { return it }
        if (view.id == View.NO_ID) return null
        viewResourceNameCache[view.id]?.let { return it }
        if (viewResourceNameMisses.contains(view.id)) return null
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
        if (name == null) {
            viewResourceNameMisses.add(view.id)
            return null
        }
        viewResourceNameCache.putIfAbsent(view.id, name)
        return viewResourceNameCache[view.id] ?: name
    }

    private fun isLikelyTime(row: View, candidate: TextCandidate, primarySize: Float): Boolean {
        if (candidate.textSize >= primarySize - SIZE_EPSILON) return false
        val rowWidth = row.width.coerceAtLeast(row.measuredWidth)
        if (rowWidth > 0 && candidate.left >= rowWidth / 2) return true
        val text = candidate.text
        if (text == "刚刚" || text == "昨天" || text == "前天") return true
        if (text.startsWith("星期") || text.startsWith("周") ||
            text.startsWith("上午") || text.startsWith("下午")
        ) {
            return true
        }
        val colon = text.indexOf(':')
        return colon in 1 until text.lastIndex &&
            text.substring(0, colon).all(Char::isDigit) &&
            text.substring(colon + 1).all(Char::isDigit)
    }

    private fun relativePosition(view: View, row: View): Long {
        var left = 0
        var top = 0
        var current: View? = view
        while (current != null && current !== row) {
            left += current.left
            top += current.top
            current = current.parent as? View
        }
        return (left.toLong() shl 32) or (top.toLong() and 0xffffffffL)
    }

    private fun textWidth(owner: View, textView: TextView?, text: String, paint: Paint): Float {
        val layout = textLayout(owner, textView)
        if (layout != null && layout.lineCount > 0 && layout.text.toString() == text) {
            var width = 0f
            for (line in 0 until layout.lineCount) {
                width = width.coerceAtLeast(layout.getLineWidth(line))
            }
            if (width > 0f) return width
        }
        return paint.measureText(text)
    }

    private fun targetTextView(view: View): TextView? {
        if (!isNeatTextView(view)) return view as? TextView
        val clazz = view.javaClass
        val method = wrappedTextMethodCache[clazz] ?: run {
            if (wrappedTextMethodMisses.contains(clazz)) return view as? TextView
            KavaReflector.findCompatibleMethod(clazz, "getWrappedTextView")?.also {
                wrappedTextMethodCache.putIfAbsent(clazz, it)
            } ?: run {
                wrappedTextMethodMisses.add(clazz)
                return view as? TextView
            }
        }
        return KavaReflector.invoke(method, view) as? TextView ?: view as? TextView
    }

    private fun renderedText(owner: View, textView: TextView?): CharSequence {
        if (isNoMeasuredTextView(owner)) {
            val access = noMeasuredTextAccess(owner) ?: return ""
            return KavaReflector.invoke(access.getText, owner) as? CharSequence ?: ""
        }
        if (textView == null) return ""
        if (!isNeatTextView(owner)) return textView.text ?: ""
        val clazz = owner.javaClass
        val method = neatTextMethodCache[clazz] ?: run {
            if (neatTextMethodMisses.contains(clazz)) return textView.text ?: ""
            KavaReflector.findCompatibleMethod(clazz, "a")?.also {
                neatTextMethodCache.putIfAbsent(clazz, it)
            } ?: run {
                neatTextMethodMisses.add(clazz)
                return textView.text ?: ""
            }
        }
        return (KavaReflector.invoke(method, owner) as? CharSequence)
            ?.takeIf { it.isNotEmpty() }
            ?: textView.text
            ?: ""
    }

    private fun textColors(owner: View, textView: TextView?): ColorStateList? {
        if (isNoMeasuredTextView(owner)) {
            val access = noMeasuredTextAccess(owner) ?: return null
            return KavaReflector.invoke(access.getTextColors, owner) as? ColorStateList
        }
        return textView?.textColors
    }

    private fun textSize(owner: View, textView: TextView?): Float? {
        if (isNoMeasuredTextView(owner)) {
            val access = noMeasuredTextAccess(owner) ?: return null
            return (KavaReflector.invoke(access.getTextSize, owner) as? Number)?.toFloat()
        }
        return textView?.textSize
    }

    private fun textPaint(owner: View, textView: TextView?): Paint? {
        if (isNoMeasuredTextView(owner)) {
            val access = noMeasuredTextAccess(owner) ?: return null
            return KavaReflector.invoke(access.getPaint, owner) as? Paint
        }
        return textView?.paint
    }

    private fun textLayout(owner: View, textView: TextView?): Layout? {
        if (isNoMeasuredTextView(owner)) {
            val access = noMeasuredTextAccess(owner) ?: return null
            return KavaReflector.invoke(access.getLayout ?: return null, owner) as? Layout
        }
        return textView?.layout
    }

    private fun setTextColors(owner: View, textView: TextView?, colors: ColorStateList): Boolean {
        if (isNoMeasuredTextView(owner)) {
            val access = noMeasuredTextAccess(owner) ?: return false
            val expected = colors.getColorForState(owner.drawableState, colors.defaultColor)
            val listApplied = access.setTextColorList?.let {
                KavaReflector.invokeSuccessfully(it, owner, colors)
            } == true
            if (listApplied && noMeasuredColorMatches(owner, access, colors, expected)) {
                return true
            }
            val intApplied = access.setTextColorInt?.let {
                KavaReflector.invokeSuccessfully(it, owner, expected)
            } == true
            return intApplied && noMeasuredColorMatches(owner, access, null, expected)
        }
        if (isNeatTextView(owner)) invokeNeatTextColor(owner, colors.defaultColor)
        textView?.setTextColor(colors) ?: return false
        return true
    }

    private fun noMeasuredColorMatches(
        owner: View,
        access: NoMeasuredTextAccess,
        expectedList: ColorStateList?,
        expectedColor: Int
    ): Boolean {
        val actualList = KavaReflector.invoke(access.getTextColors, owner) as? ColorStateList
        if (expectedList != null && actualList === expectedList) return true
        if (actualList?.getColorForState(owner.drawableState, actualList.defaultColor) == expectedColor) {
            return true
        }
        val current = access.getCurrentTextColor?.let {
            KavaReflector.invoke(it, owner) as? Number
        }?.toInt()
        return current == expectedColor
    }

    private fun invokeNeatTextColor(view: View, color: Int) {
        val clazz = view.javaClass
        val method = neatColorMethodCache[clazz] ?: run {
            if (neatColorMethodMisses.contains(clazz)) return
            KavaReflector.findCompatibleMethod(clazz, "setTextColor", color)?.also {
                neatColorMethodCache.putIfAbsent(clazz, it)
            } ?: run {
                neatColorMethodMisses.add(clazz)
                return
            }
        }
        KavaReflector.invoke(method, view, color)
    }

    private fun isNeatTextView(view: View): Boolean {
        val clazz = view.javaClass
        neatClassCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        var result = false
        while (current != null && current != Any::class.java) {
            val name = current.name
            if (name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
                name == "com.tencent.neattextview.textview.view.NeatTextView" ||
                name.contains("NeatTextView")
            ) {
                result = true
                break
            }
            current = current.superclass
        }
        neatClassCache.putIfAbsent(clazz, result)
        return result
    }

    private fun isNoMeasuredTextView(view: View): Boolean {
        val clazz = view.javaClass
        noMeasuredClassCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        var result = false
        while (current != null && current != Any::class.java) {
            if (current.name == NO_MEASURED_TEXT_VIEW) {
                result = true
                break
            }
            current = current.superclass
        }
        noMeasuredClassCache.putIfAbsent(clazz, result)
        return result
    }

    private fun noMeasuredTextAccess(view: View): NoMeasuredTextAccess? {
        val clazz = view.javaClass
        noMeasuredAccessCache[clazz]?.let { return it }
        if (noMeasuredAccessMisses.contains(clazz)) return null
        val access = runCatching {
            val setTextColorList = KavaReflector.findMethodRecursive(
                clazz,
                "setTextColor",
                ColorStateList::class.java
            )
            val setTextColorInt = KavaReflector.findMethodRecursive(
                clazz,
                "setTextColor",
                Int::class.javaPrimitiveType!!
            )
            if (setTextColorList == null && setTextColorInt == null) return@runCatching null
            NoMeasuredTextAccess(
                getText = KavaReflector.findMethodRecursive(clazz, "getText") ?: return@runCatching null,
                getTextColors = KavaReflector.findMethodRecursive(clazz, "getTextColors") ?: return@runCatching null,
                getTextSize = KavaReflector.findMethodRecursive(clazz, "getTextSize") ?: return@runCatching null,
                getPaint = KavaReflector.findMethodRecursive(clazz, "getPaint") ?: return@runCatching null,
                getLayout = KavaReflector.findMethodRecursive(clazz, "getLayout"),
                getCurrentTextColor = KavaReflector.findMethodRecursive(clazz, "getCurrentTextColor"),
                setTextColorList = setTextColorList,
                setTextColorInt = setTextColorInt
            )
        }.getOrNull()
        if (access == null) {
            if (noMeasuredAccessMisses.add(clazz)) {
                HLog.e("$TAG 无法解析 NoMeasuredTextView 颜色接口: class=${clazz.name}")
            }
            return null
        }
        noMeasuredAccessCache.putIfAbsent(clazz, access)
        return noMeasuredAccessCache[clazz] ?: access
    }

    private fun isExcludedPreferencePage(row: View): Boolean {
        var current: Context? = row.context
        while (current != null) {
            val name = current.javaClass.name
            if (name in WECHAT_SETTINGS_ACTIVITIES ||
                (name.startsWith(WECHAT_SETTINGS_ACTIVITY_PREFIX) && name.endsWith(WECHAT_SETTINGS_ACTIVITY_SUFFIX))
            ) return true
            val base = (current as? ContextWrapper)?.baseContext ?: break
            if (base === current) break
            current = base
        }
        return false
    }

    private fun isAdapterGetView(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "getView" &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            View::class.java.isAssignableFrom(method.returnType) &&
            types.size == 3 &&
            types[0] == Int::class.javaPrimitiveType &&
            types[1] == View::class.java &&
            types[2] == ViewGroup::class.java
    }

    private fun methodDescriptor(method: Method): String {
        return buildString {
            append(method.declaringClass.name)
            append('#')
            append(method.name)
            append('(')
            method.parameterTypes.joinTo(this, ",") { it.name }
            append(')')
            append(method.returnType.name)
        }
    }

    private fun readConfig(): ColorConfig {
        return ColorConfig(
            enabled = prefs.getBoolean(
                HomeTextColorSettings.KEY_ENABLE,
                HomeTextColorSettings.DEFAULT_ENABLE
            ),
            title = MemberTitleStore.parseColorSpec(
                prefs.getString(
                    HomeTextColorSettings.KEY_TITLE_COLOR,
                    HomeTextColorSettings.DEFAULT_TITLE_COLOR
                )
            ),
            subtitle = MemberTitleStore.parseColorSpec(
                prefs.getString(
                    HomeTextColorSettings.KEY_SUBTITLE_COLOR,
                    HomeTextColorSettings.DEFAULT_SUBTITLE_COLOR
                )
            )
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private data class ColorConfig(
        val enabled: Boolean,
        val title: MemberTitleStore.ColorSpec?,
        val subtitle: MemberTitleStore.ColorSpec?
    )

    private data class GradientKey(
        val startColor: Int,
        val endColor: Int,
        val widthBits: Int
    )

    private data class RowState(
        val owners: List<View>
    )

    private data class AppliedState(
        val textView: TextView?,
        val originalColors: ColorStateList,
        val originalShader: Shader?,
        val appliedColors: ColorStateList,
        var appliedShader: Shader? = null
    )

    private data class TextCandidate(
        val owner: View,
        val textView: TextView?,
        val text: String,
        val textSize: Float,
        val left: Int,
        val top: Int,
        val hasBackground: Boolean,
        val isNoMeasured: Boolean
    )

    private data class Target(
        val candidate: TextCandidate,
        val role: TextRole
    )

    private data class NoMeasuredTextAccess(
        val getText: Method,
        val getTextColors: Method,
        val getTextSize: Method,
        val getPaint: Method,
        val getLayout: Method?,
        val getCurrentTextColor: Method?,
        val setTextColorList: Method?,
        val setTextColorInt: Method?
    )

    private data class ModernConversationMethods(
        val holder: Method,
        val bind: Method
    )

    private data class ConversationAdapterSpec(
        val cacheName: String,
        val anchor: String,
        val requireConversationOwner: Boolean
    )

    private enum class RowKind(val label: String) {
        CONVERSATION("会话"),
        CONTACTS("通讯录"),
        PREFERENCE("发现/我")
    }

    private enum class TextRole {
        TITLE,
        SUBTITLE
    }

    private companion object {
        const val TAG = "[Hchat:HomeTextColor]"
        const val METHOD_CACHE_PREFS = "Hchat_home_text_color_method_cache"
        const val CACHE_CONVERSATION_MAIN_LEGACY = "conversation_main_get_views_v4"
        const val CACHE_CONVERSATION_GROUP = "conversation_group_get_views_v2"
        const val CACHE_CONTACTS = "contacts_get_view_v1"
        const val CACHE_PREFERENCE = "preference_get_view_v1"
        const val CACHE_LAYOUT_INFLATER_FACTORY = "layout_inflater_factory_v1"
        const val CACHE_RECYCLER_BIND = "recycler_bind_v2"
        const val CACHE_RECYCLER_PAYLOAD_BIND = "recycler_payload_bind_v1"
        const val CACHE_MODERN_CONVERSATION_HOLDER = "modern_conversation_holder_v2"
        const val CACHE_MODERN_CONVERSATION_BIND = "modern_conversation_bind_v2"
        val CONVERSATION_ADAPTER_SPECS = listOf(
            ConversationAdapterSpec(
                CACHE_CONVERSATION_MAIN_LEGACY,
                "MicroMsg.ConversationWithCacheAdapter",
                requireConversationOwner = false
            ),
            ConversationAdapterSpec(
                CACHE_CONVERSATION_GROUP,
                "MicroMsg.ConversationAdapter",
                requireConversationOwner = true
            )
        )
        const val ANCHOR_CONTACTS = "MicroMsg.AddressAdapter"
        const val ANCHOR_PREFERENCE = "com/tencent/mm/ui/base/preference/MMPreferenceAdapter"
        const val OWNER_CONVERSATION = "com.tencent.mm.ui.conversation."
        const val OWNER_CONTACTS = "com.tencent.mm.ui.contact."
        const val OWNER_PREFERENCE = "com.tencent.mm.ui.base.preference."
        const val NO_MEASURED_TEXT_VIEW = "com.tencent.mm.ui.base.NoMeasuredTextView"
        const val WX_RECYCLER_ADAPTER = "com.tencent.mm.view.recyclerview.WxRecyclerAdapter"
        val MODERN_CONVERSATION_BIND_ANCHORS = listOf(
            "[getView] position=",
            "handleShowTipCnt"
        )
        const val MODERN_CONVERSATION_STORAGE_PREFIX = "com.tencent.mm.storage."
        val TITLE_ANCHORS = setOf("nickname_tv", "kbq")
        val SUBTITLE_ANCHORS = setOf("last_msg_tv", "ht5")
        val TIME_ANCHORS = setOf("update_time_tv", "otg")
        val CONTACT_TITLE_ANCHORS = setOf("kbq")
        val CONTACT_SUBTITLE_ANCHORS = setOf("kjp")
        val CONTACT_SECTION_ANCHORS = setOf("cfx")
        val FIXED_CONTACT_ENTRY_ANCHORS = setOf("obc", "n9", "dgz")
        val AMBIGUOUS_FIXED_CONTACT_ENTRY_ANCHORS = setOf("obc")
        val OFFICIAL_CONTACT_ENTRY_CONTAINERS = setOf("as2")
        const val ANCHOR_MASK_CONVERSATION_TITLE = 1
        const val ANCHOR_MASK_CONVERSATION_SUBTITLE = 1 shl 1
        const val ANCHOR_MASK_CONVERSATION_TIME = 1 shl 2
        const val ANCHOR_MASK_CONVERSATION = ANCHOR_MASK_CONVERSATION_TITLE or
            ANCHOR_MASK_CONVERSATION_SUBTITLE or ANCHOR_MASK_CONVERSATION_TIME
        const val ANCHOR_MASK_CONTACT_TITLE = 1 shl 3
        const val ANCHOR_MASK_CONTACT_SUBTITLE = 1 shl 4
        const val ANCHOR_MASK_CONTACTS = ANCHOR_MASK_CONTACT_TITLE or ANCHOR_MASK_CONTACT_SUBTITLE
        val WECHAT_SETTINGS_ACTIVITIES = setOf(
            "com.tencent.mm.plugin.setting.ui.setting.SettingsUI",
            "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"
        )
        const val WECHAT_SETTINGS_ACTIVITY_PREFIX = "com.tencent.mm.plugin.setting.ui.setting_new."
        const val WECHAT_SETTINGS_ACTIVITY_SUFFIX = "SettingsUI"
        const val SIZE_EPSILON = 0.5f
        const val MAX_PREFERENCE_SUBTITLES = 3
        const val GRADIENT_CACHE_SIZE = 64
        const val COLOR_STATE_CACHE_SIZE = 16
        const val MAX_FIXED_HEADER_ANCESTORS = 10
    }
}
