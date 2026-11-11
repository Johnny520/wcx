package h.Hchat.hooks.items.moments

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Adapter
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.runtime.WeChatVersionApi
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class MomentsKeywordBlockFeature : BaseFeature() {
    private var runtime: MomentsKeywordBlockRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈关键词屏蔽"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsKeywordBlockSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MomentsKeywordBlockRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(
            "$ID:sns_query",
            "${name()}朋友圈数据库查询",
            stage = DexInstallScheduler.Stage.BRIDGE,
            priority = -100
        ) {
            runtime?.installSnsTimelineQueryHook() == true
        }
        DexInstallScheduler.schedule(
            "$ID:timeline_refresh",
            "${name()}朋友圈本地刷新",
            stage = DexInstallScheduler.Stage.BRIDGE,
            priority = -90
        ) {
            runtime?.warmupTimelineLocalRefresh() == true
        }
        DexInstallScheduler.schedule(
            "$ID:timeline_legacy",
            "${name()}旧版时间线",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) {
            runtime?.installLegacyTimelineHook() == true
        }
        DexInstallScheduler.schedule(
            "$ID:timeline_improve",
            "${name()}新版时间线",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) {
            runtime?.installImproveTimelineHook() == true
        }
        DexInstallScheduler.schedule(
            "$ID:profile_list",
            "${name()}个人主页列表",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) {
            runtime?.installProfileListHook() == true
        }
        DexInstallScheduler.schedule(
            "$ID:profile_switch",
            "${name()}个人主页入口",
            stage = DexInstallScheduler.Stage.BRIDGE
        ) {
            runtime?.installProfileSwitchHook() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "moments_keyword_block"

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return HchatStorage.preferences(context, MomentsKeywordBlockSettings.PREFS_NAME)
                .getBoolean(MomentsKeywordBlockSettings.KEY_ENABLE, MomentsKeywordBlockSettings.DEFAULT_ENABLE)
        }
    }
}

private class MomentsKeywordBlockRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class HiddenViewState(
        val visibility: Int,
        val height: Int?
    )

    private data class ImproveInfoAccessor(
        val methods: List<Method>
    )

    private data class UserNameAccessor(
        val method: Method?,
        val field: Field?
    )

    private data class BoundMoment(
        val info: Any,
        val filterByAuthor: Boolean,
        val generation: Long,
        val hidden: Boolean
    )

    private data class FilterSnapshot(
        val generation: Long,
        val keywords: Set<String>,
        val contactEnabled: Boolean,
        val contactMode: Int,
        val contactTargets: Set<String>
    )

    private data class AuthorFilterDecision(
        val generation: Long,
        val hidden: Boolean
    )

    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        MomentsKeywordBlockSettings.PREFS_NAME
    )
    private val contactFilterPrefs = HchatStorage.preferences(
        context.hostContext(),
        MomentsContactFilterSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    private val main = Handler(Looper.getMainLooper())
    private val hiddenViews = Collections.synchronizedMap(WeakHashMap<View, HiddenViewState>())
    private val boundInfos = Collections.synchronizedMap(WeakHashMap<View, BoundMoment>())
    private val authorDecisionCache = Collections.synchronizedMap(
        WeakHashMap<Any, AuthorFilterDecision>()
    )
    private val snapshotLock = Any()
    private val improveInfoAccessors = ConcurrentHashMap<Class<*>, ImproveInfoAccessor>()
    private val missingImproveInfoAccessors = ConcurrentHashMap.newKeySet<Class<*>>()
    private val userNameAccessors = ConcurrentHashMap<Class<*>, UserNameAccessor>()
    private val nativeSnsInfoClass by lazy {
        KavaReflector.loadClass(SNS_INFO_CLASS, context.hostClassLoader())
    }
    private val reapplyRunnable = Runnable(::reapplyBoundViews)
    private val refreshTimelineRunnable = Runnable {
        val activity = runCatching {
            WeChatApis.currentActivity()?.currentActivity() as? Activity
        }.getOrNull() ?: return@Runnable
        if (!isTimelineActivity(activity) || refreshCurrentTimeline(activity)) return@Runnable
        runCatching { WeChatApis.snsApi()?.refreshTimeline() }
            .onFailure { logger("朋友圈过滤触发原生刷新失败", it) }
    }
    private val selfResolveRunnable = Runnable {
        nextSelfResolveAt = 0L
        val snapshot = filterSnapshot
        if (snapshot.contactEnabled && snapshot.contactTargets.isNotEmpty()) {
            selfWxId()
        }
    }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MomentsKeywordBlockSettings.KEY_ENABLE ||
            key == MomentsKeywordBlockSettings.KEY_KEYWORDS
        ) {
            refreshFilterSnapshot()
            main.removeCallbacks(reapplyRunnable)
            main.post(reapplyRunnable)
        }
    }
    private val contactFilterPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MomentsContactFilterSettings.KEY_ENABLE ||
            key == MomentsContactFilterSettings.KEY_MODE ||
            key == MomentsContactFilterSettings.KEY_TARGETS
        ) {
            refreshFilterSnapshot()
            main.removeCallbacks(reapplyRunnable)
            main.post(reapplyRunnable)
            main.removeCallbacks(refreshTimelineRunnable)
            main.postDelayed(refreshTimelineRunnable, CONTACT_FILTER_REFRESH_DEBOUNCE_MS)
        }
    }

    @Volatile
    private var legacyTimelineHookInstalled = false

    @Volatile
    private var improveTimelineHookInstalled = false

    @Volatile
    private var snsTimelineQueryHookInstalled = false

    @Volatile
    private var profileListHookInstalled = false

    @Volatile
    private var profileSwitchHookInstalled = false

    @Volatile
    private var timelineLocalReloadMethod: Method? = null

    @Volatile
    private var improveTimelineRefreshMethod: Method? = null

    @Volatile
    private var filterSnapshot = readFilterSnapshot(0L)

    private val querySubscription = WeChatApis.databaseChanges()?.subscribeQuery(::rewriteTimelineQuery)

    @Volatile
    private var cachedSelfWxId: String = ""

    @Volatile
    private var nextSelfResolveAt: Long = 0L

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        contactFilterPrefs.registerOnSharedPreferenceChangeListener(contactFilterPreferenceListener)
    }

    /**
     * 朋友圈使用的 SnsSqliteDB 查询方法名会随微信版本混淆，且不一定继续经过公共
     * rawQuery 名称入口。直接在这个稳定签名的存储层方法前改写 SQL，避免先加载再逐条
     * 隐藏导致无效绑定和分页反复触发。
     */
    @Synchronized
    fun installSnsTimelineQueryHook(): Boolean {
        if (snsTimelineQueryHookInstalled) return true
        val method = locateSnsTimelineQueryMethod() ?: return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val sql = param.args.getOrNull(0) as? String ?: return
                    val rewritten = rewriteTimelineQuery(sql)
                    if (rewritten != sql) param.args[0] = rewritten
                }
            })
            snsTimelineQueryHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈关键词屏蔽朋友圈数据库查询 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    @Synchronized
    fun installLegacyTimelineHook(): Boolean {
        if (legacyTimelineHookInstalled) return true
        val method = locateLegacyTimelineCreateViewMethod()
        if (method == null) {
            legacyTimelineHookInstalled = true
            return true
        }
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.getOrNull(3) as? View
                    if (!filterSnapshot.isActive(filterByAuthor = true)) {
                        view?.let(::clearViewBinding)
                        return
                    }
                    val info = param.args.getOrNull(0) ?: run {
                        view?.let(::clearViewBinding)
                        return
                    }
                    param.setObjectExtra(BIND_INFO_EXTRA, info)
                    view?.let { view ->
                        prepareViewBinding(view, info, filterByAuthor = true)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? View ?: return
                    if (!filterSnapshot.isActive(filterByAuthor = true)) {
                        clearViewBinding(view)
                        return
                    }
                    val info = param.getObjectExtra(BIND_INFO_EXTRA) ?: param.args.getOrNull(0) ?: run {
                        clearViewBinding(view)
                        return
                    }
                    bindView(view, info, filterByAuthor = true)
                }
            })
            legacyTimelineHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈关键词屏蔽旧版时间线 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    @Synchronized
    fun installImproveTimelineHook(): Boolean {
        if (improveTimelineHookInstalled) return true
        val method = locateImproveTimelineBindMethod() ?: return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View
                    if (!filterSnapshot.isActive(filterByAuthor = true)) {
                        view?.let(::clearViewBinding)
                        return
                    }
                    val info = resolveImproveSnsInfo(param.args.getOrNull(1)) ?: run {
                        view?.let(::clearViewBinding)
                        return
                    }
                    param.setObjectExtra(BIND_INFO_EXTRA, info)
                    view?.let { view ->
                        prepareViewBinding(view, info, filterByAuthor = true)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    if (!filterSnapshot.isActive(filterByAuthor = true)) {
                        clearViewBinding(view)
                        return
                    }
                    val info = param.getObjectExtra(BIND_INFO_EXTRA)
                        ?: resolveImproveSnsInfo(param.args.getOrNull(1))
                        ?: run {
                            clearViewBinding(view)
                            return
                        }
                    bindView(view, info, filterByAuthor = true)
                }
            })
            improveTimelineHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈关键词屏蔽新版时间线 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    @Synchronized
    fun installProfileListHook(): Boolean {
        if (profileListHookInstalled) return true
        val method = locateProfileListBindMethod() ?: return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.getOrNull(1) as? View
                    if (!filterSnapshot.isActive(filterByAuthor = false)) {
                        view?.let(::clearViewBinding)
                        return
                    }
                    val info = profileListInfo(param) ?: run {
                        view?.let(::clearViewBinding)
                        return
                    }
                    param.setObjectExtra(BIND_INFO_EXTRA, info)
                    view?.let { view ->
                        prepareViewBinding(view, info, filterByAuthor = false)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? View ?: return
                    if (!filterSnapshot.isActive(filterByAuthor = false)) {
                        clearViewBinding(view)
                        return
                    }
                    val info = param.getObjectExtra(BIND_INFO_EXTRA) ?: profileListInfo(param) ?: run {
                        clearViewBinding(view)
                        return
                    }
                    bindView(view, info, filterByAuthor = false)
                }
            })
            profileListHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈关键词屏蔽个人主页列表 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    @Synchronized
    fun installProfileSwitchHook(): Boolean {
        if (profileSwitchHookInstalled) return true
        if (!requiresFlutterProfileSwitch()) {
            profileSwitchHookInstalled = true
            return true
        }
        val method = locateFlutterProfileSwitchMethod() ?: return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (activeKeywords().isNotEmpty()) param.result = false
                }
            })
            profileSwitchHookInstalled = true
            true
        }.getOrElse {
            logger("朋友圈关键词屏蔽个人主页 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    @Synchronized
    fun warmupTimelineLocalRefresh(): Boolean {
        val improveReady = KavaReflector.loadClass(
            IMPROVE_MVVM_LIST_CLASS,
            context.hostClassLoader()
        )?.let { locateImproveTimelineRefreshMethod(it) != null } == true
        val legacyReady = locateTimelineLocalReloadMethod() != null
        return improveReady || legacyReady
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        contactFilterPrefs.unregisterOnSharedPreferenceChangeListener(contactFilterPreferenceListener)
        querySubscription?.unsubscribe()
        main.removeCallbacks(reapplyRunnable)
        main.removeCallbacks(selfResolveRunnable)
        main.removeCallbacks(refreshTimelineRunnable)
        restoreAllViews()
        boundInfos.clear()
        authorDecisionCache.clear()
        improveInfoAccessors.clear()
        missingImproveInfoAccessors.clear()
        userNameAccessors.clear()
    }

    private fun prepareViewBinding(view: View, info: Any, filterByAuthor: Boolean) {
        val bound = boundInfos[view]
        val snapshot = filterSnapshot
        if (bound != null &&
            bound.info === info &&
            bound.filterByAuthor == filterByAuthor &&
            bound.generation == snapshot.generation &&
            snapshot.keywords.isEmpty()
        ) {
            applyHiddenState(view, bound.hidden)
            return
        }
        restoreView(view)
        boundInfos.remove(view)
    }

    private fun clearViewBinding(view: View) {
        boundInfos.remove(view)
        restoreView(view)
    }

    private fun bindView(view: View, info: Any, filterByAuthor: Boolean) {
        val snapshot = filterSnapshot
        if (!snapshot.isActive(filterByAuthor) || !isSnsInfo(info)) {
            clearViewBinding(view)
            return
        }
        val bound = boundInfos[view]
        if (bound != null &&
            bound.info === info &&
            bound.filterByAuthor == filterByAuthor &&
            bound.generation == snapshot.generation &&
            snapshot.keywords.isEmpty()
        ) {
            applyHiddenState(view, bound.hidden)
            return
        }
        clearViewBinding(view)
        val hidden = shouldHide(info, filterByAuthor, snapshot)
        boundInfos[view] = BoundMoment(info, filterByAuthor, snapshot.generation, hidden)
        applyHiddenState(view, hidden)
    }

    private fun shouldHide(
        info: Any,
        filterByAuthor: Boolean,
        snapshot: FilterSnapshot = filterSnapshot
    ): Boolean {
        if (filterByAuthor && shouldHideAuthor(info, snapshot)) return true
        if (snapshot.keywords.isNotEmpty()) {
            val text = momentsTimelineText(info)
            if (text.isNotBlank() && snapshot.keywords.any { text.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        return false
    }

    private fun shouldHideAuthor(info: Any, snapshot: FilterSnapshot): Boolean {
        if (!snapshot.contactEnabled || snapshot.contactTargets.isEmpty()) return false
        authorDecisionCache[info]
            ?.takeIf { it.generation == snapshot.generation }
            ?.let { return it.hidden }
        val author = momentsUserName(info)
        if (author.isBlank()) return false
        val hiddenByList = when (snapshot.contactMode) {
            MomentsContactFilterSettings.MODE_INCLUDE_ONLY -> author !in snapshot.contactTargets
            else -> author in snapshot.contactTargets
        }
        val self = selfWxId()
        if (self.isBlank()) return hiddenByList
        val hidden = hiddenByList && author != self
        authorDecisionCache[info] = AuthorFilterDecision(snapshot.generation, hidden)
        return hidden
    }

    private fun momentsUserName(info: Any): String {
        val accessor = userNameAccessors.computeIfAbsent(info.javaClass) { clazz ->
            UserNameAccessor(
                method = KavaReflector.findMethodRecursive(clazz, "getUserName"),
                field = KavaReflector.findFieldRecursive(clazz, "field_userName")
            )
        }
        return (KavaReflector.invoke(accessor.method, info)
            ?: KavaReflector.readField(accessor.field, info))
            ?.toString()
            ?.trim()
            .orEmpty()
    }

    private fun rewriteTimelineQuery(sql: String): String {
        val snapshot = filterSnapshot
        if (!snapshot.contactEnabled ||
            snapshot.contactTargets.isEmpty()
        ) return sql

        if (sql != INITIAL_TIMELINE_QUERY) return sql

        val authors = LinkedHashSet(snapshot.contactTargets)
        val self = selfWxId()
        if (snapshot.contactMode == MomentsContactFilterSettings.MODE_INCLUDE_ONLY) {
            if (self.isNotBlank()) authors.add(self)
        } else if (self.isNotBlank()) {
            // 页面层始终保留自己的朋友圈，排除模式的 SQL 也必须保持一致。
            authors.remove(self)
        }
        if (authors.isEmpty()) return sql
        val values = authors.joinToString(",") { "'${it.replace("'", "''")}'" }
        val condition = when (snapshot.contactMode) {
            MomentsContactFilterSettings.MODE_INCLUDE_ONLY -> "SnsInfo.userName IN ($values)"
            else -> "SnsInfo.userName NOT IN ($values)"
        }
        return "SELECT rowid, * FROM SnsInfo WHERE ((SnsInfo.sourceType & 2) <> 0) " +
            "AND ($condition) ORDER BY SnsInfo.createTime DESC LIMIT 1000"
    }

    private fun FilterSnapshot.isActive(filterByAuthor: Boolean = true): Boolean {
        return keywords.isNotEmpty() ||
            (filterByAuthor && contactEnabled && contactTargets.isNotEmpty())
    }

    private fun selfWxId(): String {
        cachedSelfWxId.takeIf { it.isNotBlank() }?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (now < nextSelfResolveAt) return ""
        val resolved = runCatching {
            WeChatApis.account()?.selfWxId().orEmpty().trim()
        }.getOrDefault("")
        if (resolved.isNotBlank()) {
            cachedSelfWxId = resolved
            nextSelfResolveAt = 0L
            main.removeCallbacks(selfResolveRunnable)
            main.post(reapplyRunnable)
        } else {
            nextSelfResolveAt = now + SELF_RESOLVE_RETRY_MS
            main.removeCallbacks(selfResolveRunnable)
            main.postDelayed(selfResolveRunnable, SELF_RESOLVE_RETRY_MS)
        }
        return resolved
    }

    private fun activeKeywords(): Set<String> {
        return filterSnapshot.keywords
    }

    private fun refreshFilterSnapshot() {
        synchronized(snapshotLock) {
            filterSnapshot = readFilterSnapshot(filterSnapshot.generation + 1L)
            authorDecisionCache.clear()
            if (!filterSnapshot.contactEnabled || filterSnapshot.contactTargets.isEmpty()) {
                main.removeCallbacks(selfResolveRunnable)
                nextSelfResolveAt = 0L
            }
        }
    }

    private fun readFilterSnapshot(generation: Long): FilterSnapshot {
        val keywords = if (prefs.getBoolean(
                MomentsKeywordBlockSettings.KEY_ENABLE,
                MomentsKeywordBlockSettings.DEFAULT_ENABLE
            )
        ) {
            parseMomentsKeywords(
                prefs.getString(
                    MomentsKeywordBlockSettings.KEY_KEYWORDS,
                    MomentsKeywordBlockSettings.DEFAULT_KEYWORDS
                )
            )
        } else {
            emptySet()
        }
        val contactEnabled = contactFilterPrefs.getBoolean(
            MomentsContactFilterSettings.KEY_ENABLE,
            MomentsContactFilterSettings.DEFAULT_ENABLE
        )
        val contactTargets = if (contactEnabled) {
            parseMomentsIds(
                contactFilterPrefs.getString(
                    MomentsContactFilterSettings.KEY_TARGETS,
                    MomentsContactFilterSettings.DEFAULT_TARGETS
                )
            )
        } else {
            emptySet()
        }
        return FilterSnapshot(
            generation = generation,
            keywords = keywords,
            contactEnabled = contactEnabled,
            contactMode = contactFilterPrefs.getInt(
                MomentsContactFilterSettings.KEY_MODE,
                MomentsContactFilterSettings.DEFAULT_MODE
            ),
            contactTargets = contactTargets
        )
    }

    private fun profileListInfo(param: XC_MethodHook.MethodHookParam): Any? {
        val adapter = param.thisObject as? Adapter ?: return null
        val position = (param.args.getOrNull(0) as? Number)?.toInt() ?: return null
        return runCatching { adapter.getItem(position) }.getOrNull()
    }

    private fun resolveImproveSnsInfo(item: Any?): Any? {
        if (item == null) return null
        if (isSnsInfo(item)) return item
        val accessor = improveInfoAccessors[item.javaClass]
            ?: resolveImproveInfoAccessor(item.javaClass)
            ?: return null
        var value: Any? = item
        for (method in accessor.methods) {
            value = KavaReflector.invoke(method, value) ?: return null
        }
        return value?.takeIf(::isSnsInfo)
    }

    private fun resolveImproveInfoAccessor(itemClass: Class<*>): ImproveInfoAccessor? {
        improveInfoAccessors[itemClass]?.let { return it }
        if (missingImproveInfoAccessors.contains(itemClass)) return null

        val firstLevel = instanceNoArgMethods(itemClass)
        val candidates = buildList {
            firstLevel.filter { isSnsInfoType(it.returnType) }.forEach { method ->
                add(ImproveInfoAccessor(listOf(method)))
            }
            firstLevel.asSequence()
                .filter { method ->
                    val type = method.returnType
                    !type.isPrimitive && type != Void.TYPE && type != itemClass
                }
                .forEach { first ->
                    instanceNoArgMethods(first.returnType)
                        .filter { isSnsInfoType(it.returnType) }
                        .forEach { second ->
                            add(ImproveInfoAccessor(listOf(first, second)))
                        }
                }
        }.distinctBy { accessor -> accessor.methods.joinToString("|") { it.toGenericString() } }

        val resolved = candidates.singleOrNull()
        if (resolved != null) {
            improveInfoAccessors[itemClass] = resolved
        } else {
            missingImproveInfoAccessors.add(itemClass)
        }
        return resolved
    }

    private fun instanceNoArgMethods(clazz: Class<*>): List<Method> {
        val methods = ArrayList<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current)
                .filterTo(methods) { method ->
                    !KavaReflector.isStatic(method) &&
                        !KavaReflector.isAbstract(method) &&
                        method.parameterTypes.isEmpty()
                }
            current = current.superclass
        }
        return methods.distinctBy { it.toGenericString() }
    }

    private fun isSnsInfo(value: Any): Boolean {
        return nativeSnsInfoClass?.isInstance(value) == true || value.javaClass.name == SNS_INFO_CLASS
    }

    private fun isSnsInfoType(type: Class<*>): Boolean {
        return nativeSnsInfoClass?.isAssignableFrom(type) == true || type.name == SNS_INFO_CLASS
    }

    private fun hideView(view: View) {
        val layoutParams = view.layoutParams
        if (!hiddenViews.containsKey(view)) {
            hiddenViews[view] = HiddenViewState(view.visibility, layoutParams?.height)
        }
        if (view.visibility != View.GONE) view.visibility = View.GONE
        if (layoutParams != null && layoutParams.height != 0) {
            layoutParams.height = 0
            view.layoutParams = layoutParams
        }
    }

    private fun restoreView(view: View) {
        val state = hiddenViews.remove(view) ?: return
        if (view.visibility != state.visibility) view.visibility = state.visibility
        val layoutParams = view.layoutParams
        val height = state.height
        if (height != null && layoutParams != null && layoutParams.height != height) {
            layoutParams.height = height
            view.layoutParams = layoutParams
        }
    }

    private fun applyHiddenState(view: View, hidden: Boolean) {
        if (hidden) hideView(view) else restoreView(view)
    }

    private fun restoreAllViews() {
        val views = synchronized(hiddenViews) { hiddenViews.keys.toList() }
        views.forEach(::restoreView)
    }

    private fun reapplyBoundViews() {
        val snapshot = filterSnapshot
        val bindings = synchronized(boundInfos) { boundInfos.entries.map { it.key to it.value } }
        bindings.forEach { (view, bound) ->
            val hidden = snapshot.isActive(bound.filterByAuthor) &&
                shouldHide(bound.info, bound.filterByAuthor, snapshot)
            applyHiddenState(view, hidden)
            boundInfos[view] = bound.copy(generation = snapshot.generation, hidden = hidden)
        }
    }

    private fun refreshCurrentTimeline(activity: Activity): Boolean {
        val mvvmListClass = KavaReflector.loadClass(
            IMPROVE_MVVM_LIST_CLASS,
            context.hostClassLoader()
        )
        if (mvvmListClass != null) {
            val recyclerView = findTimelineRecyclerView(activity)
            // Improve 列表在刷新前需要先把当前布局位置归零，否则提交新数据后旧的
            // 分页状态仍会触发继续加载，尤其是只看模式命中条目较少时会卡在加载中。
            recyclerView?.let { resetTimelineRecyclerView(it) }
            val adapter = recyclerView?.let { KavaReflector.invokeMethod(it, "getAdapter") }
            val mvvmList = adapter?.let { findMvvmListField(it, mvvmListClass) }
            val method = improveTimelineRefreshMethod
                ?.takeIf { isImproveTimelineRefreshMethod(it, mvvmListClass) }
            if (mvvmList != null && method != null) {
                val refreshed = if (Modifier.isStatic(method.modifiers)) {
                    KavaReflector.invokeSuccessfully(method, null, mvvmList, null, 1, null)
                } else {
                    KavaReflector.invokeSuccessfully(method, mvvmList)
                }
                if (refreshed) return true
            }
        }

        val method = timelineLocalReloadMethod?.takeIf(::isTimelineLocalReloadMethod) ?: return false
        val adapter = findCurrentTimelineObject(activity, method.declaringClass) ?: return false
        return KavaReflector.invokeSuccessfully(method, adapter, "")
    }

    private fun resetTimelineRecyclerView(recyclerView: View) {
        var type: Class<*>? = recyclerView.javaClass
        while (type != null && type != Any::class.java) {
            val method = KavaReflector.declaredMethods(type).firstOrNull { candidate ->
                !Modifier.isStatic(candidate.modifiers) &&
                    candidate.parameterTypes.size == 3 &&
                    candidate.parameterTypes.all { it == Int::class.javaPrimitiveType }
            }
            if (method != null) {
                KavaReflector.invokeSuccessfully(method, recyclerView, 0, 0, 0)
                return
            }
            type = type.superclass
        }
    }

    private fun findTimelineRecyclerView(activity: Activity): View? {
        val recyclerViewClass = KavaReflector.loadClass(
            RECYCLER_VIEW_CLASS,
            context.hostClassLoader()
        ) ?: return null
        val mvvmListClass = KavaReflector.loadClass(
            IMPROVE_MVVM_LIST_CLASS,
            context.hostClassLoader()
        ) ?: return null
        return findTimelineRecyclerView(
            activity.window?.decorView ?: return null,
            recyclerViewClass,
            mvvmListClass
        )
    }

    private fun findTimelineRecyclerView(
        view: View,
        recyclerViewClass: Class<*>,
        mvvmListClass: Class<*>
    ): View? {
        if (recyclerViewClass.isInstance(view)) {
            val adapter = KavaReflector.invokeMethod(view, "getAdapter")
            if (adapter != null && findMvvmListField(adapter, mvvmListClass) != null) return view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTimelineRecyclerView(
                    view.getChildAt(index),
                    recyclerViewClass,
                    mvvmListClass
                )?.let { return it }
            }
        }
        return null
    }

    private fun findMvvmListField(adapter: Any, mvvmListClass: Class<*>): Any? {
        var type: Class<*>? = adapter.javaClass
        while (type != null && type != Any::class.java) {
            for (field in KavaReflector.declaredFields(type)) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value = KavaReflector.readField(field, adapter) ?: continue
                if (mvvmListClass.isInstance(value)) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun findCurrentTimelineObject(activity: Activity, expectedClass: Class<*>): Any? {
        return findNestedTimelineObject(
            activity,
            expectedClass,
            0,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        )
    }

    private fun findNestedTimelineObject(
        value: Any?,
        expectedClass: Class<*>,
        depth: Int,
        visited: MutableSet<Any>
    ): Any? {
        value ?: return null
        if (expectedClass.isInstance(value)) return value
        if (depth >= MAX_TIMELINE_OBJECT_SEARCH_DEPTH || !visited.add(value)) return null
        val className = value.javaClass.name
        if (!className.startsWith("com.tencent.mm.") &&
            !className.startsWith("androidx.recyclerview.") &&
            !className.startsWith("android.widget.")
        ) return null
        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            for (field in KavaReflector.declaredFields(type)) {
                if (Modifier.isStatic(field.modifiers)) continue
                val nested = KavaReflector.readField(field, value) ?: continue
                if (expectedClass.isInstance(nested)) return nested
                findNestedTimelineObject(nested, expectedClass, depth + 1, visited)?.let { return it }
            }
            type = type.superclass
        }
        return null
    }

    private fun locateLegacyTimelineCreateViewMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_LEGACY_TIMELINE_CREATE_VIEW
        )?.takeIf(::isLegacyTimelineCreateViewMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(TIMELINE_CREATE_ANCHOR, TIMELINE_OWNER_ANCHOR)
            },
            ::isLegacyTimelineCreateViewMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_LEGACY_TIMELINE_CREATE_VIEW, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_LEGACY_TIMELINE_CREATE_VIEW)
        }
        return method
    }

    private fun locateSnsTimelineQueryMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_SNS_TIMELINE_QUERY
        )?.takeIf(::isSnsTimelineQueryMethod)?.let { return it }

        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(SNS_SQLITE_RAW_QUERY_ANCHOR, SNS_SQLITE_OWNER_ANCHOR)
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isSnsTimelineQueryMethod)
                .distinctBy(Method::toGenericString)
        }.onFailure {
            logger("定位朋友圈数据库查询方法失败", it)
        }.getOrDefault(emptyList())

        val method = methods.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_SNS_TIMELINE_QUERY, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_SNS_TIMELINE_QUERY)
            logger("朋友圈数据库查询方法定位结果不唯一或为空: count=${methods.size}", null)
        }
        return method
    }

    private fun locateTimelineLocalReloadMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_TIMELINE_LOCAL_RELOAD
        )?.takeIf(::isTimelineLocalReloadMethod)?.let {
            timelineLocalReloadMethod = it
            return it
        }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings("onNotifyChange", "SnsTimeLineVendingAdapter")
            },
            ::isTimelineLocalReloadMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_TIMELINE_LOCAL_RELOAD, method)
            timelineLocalReloadMethod = method
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_TIMELINE_LOCAL_RELOAD)
        }
        return method
    }

    private fun locateImproveTimelineRefreshMethod(mvvmListClass: Class<*>): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_IMPROVE_TIMELINE_REFRESH
        )?.takeIf { isImproveTimelineRefreshMethod(it, mvvmListClass) }?.let {
            improveTimelineRefreshMethod = it
            return it
        }
        val method = findMethod(
            MethodMatcher().apply {
                declaredClass(IMPROVE_MVVM_LIST_CLASS)
                usingEqStrings("submitRefreshAll")
            },
            { isImproveTimelineRefreshMethod(it, mvvmListClass) }
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_IMPROVE_TIMELINE_REFRESH, method)
            improveTimelineRefreshMethod = method
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_IMPROVE_TIMELINE_REFRESH)
        }
        return method
    }

    private fun locateImproveTimelineBindMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_IMPROVE_TIMELINE_BIND
        )?.takeIf(::isImproveTimelineBindMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(IMPROVE_BIND_ANCHOR, IMPROVE_OWNER_ANCHOR)
            },
            ::isImproveTimelineBindMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_IMPROVE_TIMELINE_BIND, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_IMPROVE_TIMELINE_BIND)
            logger("朋友圈关键词屏蔽未找到新版时间线绑定方法", null)
        }
        return method
    }

    private fun locateProfileListBindMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_PROFILE_LIST_BIND
        )?.takeIf(::isProfileListBindMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(PROFILE_LIST_BIND_ANCHOR, PROFILE_LIST_OWNER_ANCHOR)
            },
            ::isProfileListBindMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_PROFILE_LIST_BIND, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_PROFILE_LIST_BIND)
            logger("朋友圈关键词屏蔽未找到个人主页列表绑定方法", null)
        }
        return method
    }

    private fun locateFlutterProfileSwitchMethod(): Method? {
        val runtimeKey = methodCacheKey()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_FLUTTER_PROFILE_SWITCH
        )?.takeIf(::isFlutterProfileSwitchMethod)?.let { return it }
        val method = findMethod(
            MethodMatcher().apply {
                usingEqStrings(FLUTTER_PROFILE_ANCHOR, FLUTTER_PROFILE_OWNER_ANCHOR)
            },
            ::isFlutterProfileSwitchMethod
        )
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_FLUTTER_PROFILE_SWITCH, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_FLUTTER_PROFILE_SWITCH)
        }
        return method
    }

    private fun findMethod(methodMatcher: MethodMatcher, predicate: (Method) -> Boolean): Method? {
        return runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply { matcher(methodMatcher) })
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .firstOrNull(predicate)
        }.onFailure {
            logger("朋友圈关键词屏蔽 DexKit 定位失败", it)
        }.getOrNull()
    }

    private fun isTimelineActivity(activity: Activity): Boolean {
        var type: Class<*>? = activity.javaClass
        while (type != null && type != Any::class.java) {
            if (type.name in TIMELINE_ACTIVITY_CLASSES) return true
            type = type.superclass
        }
        return false
    }

    private fun isTimelineLocalReloadMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
            android.widget.BaseAdapter::class.java.isAssignableFrom(method.declaringClass)
    }

    private fun isImproveTimelineRefreshMethod(method: Method, mvvmListClass: Class<*>): Boolean {
        if (method.declaringClass != mvvmListClass || method.returnType != Void.TYPE) return false
        if (!Modifier.isStatic(method.modifiers)) return method.parameterTypes.isEmpty()
        val types = method.parameterTypes
        return types.size == 4 &&
            types[0] == mvvmListClass &&
            types[2] == Int::class.javaPrimitiveType &&
            types[3] == Any::class.java
    }

    private fun isLegacyTimelineCreateViewMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            View::class.java.isAssignableFrom(method.returnType) &&
            types.size == 4 &&
            types[0].name == SNS_INFO_CLASS &&
            types[1] == Int::class.javaPrimitiveType &&
            types[2] == Int::class.javaPrimitiveType &&
            View::class.java.isAssignableFrom(types[3])
    }

    private fun isImproveTimelineBindMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            View::class.java.isAssignableFrom(method.declaringClass) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            types.size == 3 &&
            types[2] == Int::class.javaPrimitiveType &&
            resolveImproveInfoAccessor(types[1]) != null
    }

    private fun isProfileListBindMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "getView" &&
            !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            View::class.java.isAssignableFrom(method.returnType) &&
            types.size == 3 &&
            types[0] == Int::class.javaPrimitiveType &&
            View::class.java.isAssignableFrom(types[1]) &&
            ViewGroup::class.java.isAssignableFrom(types[2])
    }

    private fun isFlutterProfileSwitchMethod(method: Method): Boolean {
        return !KavaReflector.isAbstract(method) &&
            !method.declaringClass.isInterface &&
            method.parameterTypes.isEmpty() &&
            method.returnType == Boolean::class.javaPrimitiveType
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun requiresFlutterProfileSwitch(): Boolean {
        val versionCode = WeChatVersionApi.build(
            context.hostContext(),
            context.hostClassLoader()
        ).versionCode
        return versionCode == 0L || versionCode >= IMPROVE_MIN_VERSION_CODE
    }

    companion object {
        private const val BIND_INFO_EXTRA = "hchat_moments_filter_bind_info"
        private const val SELF_RESOLVE_RETRY_MS = 2_000L
        private const val CACHE_PREFS = "Hchat_moments_keyword_block_method_cache"
        private const val CACHE_LEGACY_TIMELINE_CREATE_VIEW = "legacy_timeline_create_view"
        private const val CACHE_IMPROVE_TIMELINE_BIND = "improve_timeline_bind"
        private const val CACHE_SNS_TIMELINE_QUERY = "sns_timeline_query_v1"
        private const val CACHE_PROFILE_LIST_BIND = "profile_list_bind"
        private const val CACHE_FLUTTER_PROFILE_SWITCH = "flutter_profile_switch"
        private const val CACHE_TIMELINE_LOCAL_RELOAD = "timeline_local_reload_v1"
        private const val CACHE_IMPROVE_TIMELINE_REFRESH = "improve_timeline_refresh_v1"
        private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"
        private const val TIMELINE_CREATE_ANCHOR = "createView"
        private const val TIMELINE_OWNER_ANCHOR = "com.tencent.mm.plugin.sns.ui.SnsTimeLineBaseAdapter"
        private const val IMPROVE_BIND_ANCHOR = "measure"
        private const val IMPROVE_OWNER_ANCHOR =
            "com.tencent.mm.plugin.sns.ui.improve.item.ImproveTimelineItemMeasure"
        private const val PROFILE_LIST_BIND_ANCHOR = "getView"
        private const val PROFILE_LIST_OWNER_ANCHOR = "com.tencent.mm.plugin.sns.ui.SnsSelfAdapter"
        private const val FLUTTER_PROFILE_ANCHOR = "enableFlutterSNSPage"
        private const val FLUTTER_PROFILE_OWNER_ANCHOR = "com.tencent.mm.plugin.sns.router.SnsRouter"
        private const val IMPROVE_MVVM_LIST_CLASS = "com.tencent.mm.plugin.mvvmlist.MvvmList"
        private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
        private const val SNS_SQLITE_RAW_QUERY_ANCHOR = "rawQuery"
        private const val SNS_SQLITE_OWNER_ANCHOR = "com.tencent.mm.plugin.sns.storage.SnsSqliteDB"
        private const val SNS_STORAGE_PACKAGE = "com.tencent.mm.plugin.sns.storage."
        private const val IMPROVE_MIN_VERSION_CODE = 3020L
        private const val MAX_TIMELINE_OBJECT_SEARCH_DEPTH = 4
        private const val CONTACT_FILTER_REFRESH_DEBOUNCE_MS = 120L
        private val TIMELINE_ACTIVITY_CLASSES = setOf(
            "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI",
            "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
        )
        private const val INITIAL_TIMELINE_QUERY =
            "SELECT rowid, * FROM SnsInfo WHERE (SnsInfo.sourceType & 2) <> 0 " +
                "ORDER BY SnsInfo.createTime DESC LIMIT 10 OFFSET 0"
    }

    private fun isSnsTimelineQueryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !KavaReflector.isStatic(method) &&
            !KavaReflector.isAbstract(method) &&
            Cursor::class.java.isAssignableFrom(method.returnType) &&
            types.size == 2 &&
            types[0] == String::class.java &&
            types[1].isArray &&
            types[1].componentType == String::class.java &&
            method.declaringClass.name.startsWith(SNS_STORAGE_PACKAGE)
    }
}
