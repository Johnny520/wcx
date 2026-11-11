package h.Hchat.hooks.items.conversationgroup

import android.database.Cursor
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.conversationtabs.ConversationTabFilter
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Homepage-only projection. Never changes the official account's stored parent or message data. */
internal object ConversationGroupHomeProjection {
    private const val TAG = "[Hchat:ConversationGroupHome]"
    private const val CACHE = "homepage_projection_v1"
    private val lifecycleLock = Any()
    @Volatile private var lifecycleGeneration = 0L
    private data class Installation(val lifecycle: Long, val hooks: Long)
    private val installed = ConcurrentHashMap.newKeySet<Member>()
    private val legacyDepth = ThreadLocal<Int>()
    private val refreshers = Collections.synchronizedMap(WeakHashMap<Any, Refresh>())
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var hiddenIds: () -> Set<String> = { emptySet() }
    @Volatile private var legacyStorage: WeakReference<Any>? = null
    @Volatile private var tabFilter: () -> ConversationTabFilter? = { null }
    private data class QueryScope(val installation: Installation, val filter: ConversationTabFilter?, var rootApplied: Boolean = false)
    private val queryScopes = ThreadLocal<ArrayDeque<QueryScope>>()
    private val tabRefreshPending = AtomicBoolean(false)
    private var delayedTabRefresh: Runnable? = null

    fun setTabFilter(provider: () -> ConversationTabFilter?) = synchronized(lifecycleLock) { tabFilter = provider }
    fun hasTabFilterInQuery(): Boolean = queryScopes.get()?.lastOrNull()
        ?.takeIf { isActive(it.installation) }?.filter?.active == true

    private fun installation(): Installation = synchronized(lifecycleLock) {
        Installation(lifecycleGeneration, HookRegistry.get().installationGeneration()).also {
            if (!isActive(it)) throw CancellationException("首页安装代次已失效")
        }
    }

    private fun isActive(installation: Installation): Boolean =
        lifecycleGeneration == installation.lifecycle && HookRegistry.get().isInstallationGenerationActive(installation.hooks)

    private fun markQueryFilterApplied() {
        queryScopes.get()?.lastOrNull()?.rootApplied = true
    }

    private fun enterQuery(installation: Installation) {
        val scopes = queryScopes.get() ?: ArrayDeque<QueryScope>().also(queryScopes::set)
        if (scopes.lastOrNull()?.installation?.let { it != installation } == true) {
            scopes.clear()
            legacyDepth.remove()
        }
        scopes.addLast(QueryScope(installation, if (scopes.isEmpty()) tabFilter() else scopes.last().filter))
    }

    private fun leaveQuery(installation: Installation) {
        val scopes = queryScopes.get() ?: return
        if (scopes.lastOrNull()?.installation != installation) return
        if (scopes.isNotEmpty()) scopes.removeLast()
        if (scopes.isEmpty()) queryScopes.remove()
    }

    /** Only constructors reached inside the native homepage query receive tab predicates. */
    fun installTabs(context: FeatureContext): Boolean = runCatching {
        val installation = installation()
        check(install(context)) { "首页共享入口尚未就绪" }
        val prefs = DexMethodCache.prefs(context.hostContext(), "Hchat_conversation_group_home_cache")
        val runtime = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cacheKey = "homepage_tabs_select_constructor_v2"
        val expected = arrayOf(
            String::class.java,
            Array<String>::class.java,
            Long::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!,
            Boolean::class.javaPrimitiveType!!
        )
        val cached = DexMethodCache.loadConstructor(prefs, runtime, context.hostClassLoader(), cacheKey)
            ?.takeIf { it.parameterTypes.contentEquals(expected) }
        val constructors: List<Constructor<*>> = if (cached != null) {
            listOf(cached)
        } else {
            DexMethodCache.clear(prefs, runtime, cacheKey)
            val candidates = context.dexKitBridge().findClass(FindClass().apply {
                matcher(ClassMatcher().apply {
                    usingStrings(listOf("MicroMsg.Sql.SelectSql", "explainQueryPlanSql: "))
                })
            }).asSequence()
                .mapNotNull { KavaReflector.loadClass(it.name, context.hostClassLoader()) }
                .flatMap { KavaReflector.declaredConstructors(it).asSequence() }
                .filter { it.parameterTypes.contentEquals(expected) }
                .distinctBy { it.declaringClass.name }
                .toList()
            check(candidates.size == 1) { "原生分页 SQL 构造器候选数异常: ${candidates.size}" }
            DexMethodCache.saveConstructor(prefs, runtime, cacheKey, candidates.single())
            candidates
        }
        constructors.forEach { constructor -> hook(constructor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isActive(installation)) return
                val scope = queryScopes.get()?.lastOrNull() ?: return
                if (scope.installation != installation) return
                val filter = scope.filter ?: return
                if (!filter.active || scope.rootApplied) return
                val original = param.args.firstOrNull() as? String ?: return
                param.args[0] = filter.query(original)
            }
        }, installation) }
        true
    }.getOrElse { HLog.e("$TAG 安装标签查询失败", it); false }

    private data class Refresh(val method: Method, val argument: WeakReference<Any>? = null)

    fun install(context: FeatureContext, hidden: (() -> Set<String>)? = null): Boolean = runCatching {
        val installation = installation()
        synchronized(lifecycleLock) {
            if (!isActive(installation)) throw CancellationException("首页安装代次已失效")
            if (hidden != null) hiddenIds = hidden
        }
        val prefs = DexMethodCache.prefs(context.hostContext(), "Hchat_conversation_group_home_cache")
        val runtime = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        var methods = DexMethodCache.loadList(prefs, runtime, context.hostClassLoader(), CACHE)
        fun cacheComplete(cached: List<Method>): Boolean {
            val legacy = cached.filter { it.parameterTypes.size == 2 && it.returnType.isArray }
            val modern = cached.filter { it.parameterTypes.size == 1 && !it.returnType.isPrimitive }
            val roots = cached.filter {
                it.parameterTypes.isEmpty() && it.returnType == String::class.java
            }
            val notices = cached.filter {
                it.parameterTypes.size == 3 && it.returnType == Void.TYPE
            }
            val updates = cached.filter(::isModernUpdate)
            return (legacy.isNotEmpty() || modern.isNotEmpty()) &&
                (legacy.isEmpty() || roots.size == 1 && notices.size >= legacy.size) &&
                (modern.isEmpty() || modern.all { page ->
                    updates.any { it.declaringClass.isAssignableFrom(page.declaringClass) }
                })
        }
        if (!cacheComplete(methods)) {
            fun find(vararg anchors: String): List<Method> = context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().apply { usingStrings(anchors.toList()) })
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter { !Modifier.isAbstract(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            val legacy = find("MicroMsg.ConversationWithCacheAdapter", "refreshChangedConversation searchArray")
                .filter { it.parameterTypes.size == 2 && it.returnType.isArray }
            val modern = find("all flag is same, count:", "message_fold_config2")
                .filter { it.parameterTypes.size == 1 && !it.returnType.isPrimitive }
            val roots = if (legacy.isEmpty()) emptyList() else find("((parentRef is null)")
                .filter { it.parameterTypes.isEmpty() && it.returnType == String::class.java && it.declaringClass.name.startsWith("com.tencent.mm.storage.") }
            val notices = if (legacy.isEmpty()) emptyList() else find("unreadcheck onConversationStorageNotifyChange")
                .filter { method -> legacy.any { it.declaringClass == method.declaringClass } && method.parameterTypes.size == 3 }
            val updates = if (modern.isEmpty()) emptyList() else find("remove conv, parentRef:")
                .filter { method -> modern.any { it.declaringClass.isAssignableFrom(method.declaringClass) } && isModernUpdate(method) }
            check(legacy.isNotEmpty() || modern.isNotEmpty()) { "没有定位到原生首页数据入口" }
            check(legacy.isEmpty() || (roots.size == 1 && notices.size == legacy.size)) { "旧版首页刷新入口不完整" }
            check(modern.isEmpty() || updates.isNotEmpty()) { "新版首页刷新入口不完整" }
            methods = (legacy + modern + roots + notices + updates).distinct()
            DexMethodCache.saveList(prefs, runtime, CACHE, methods)
        }
        val legacy = methods.filter { it.returnType.isArray }
        val modern = methods.filter { it.parameterTypes.size == 1 && !it.returnType.isPrimitive }
        val updates = methods.filter(::isModernUpdate)
        legacy.forEach { changes ->
            val notice = methods.single { it.declaringClass == changes.declaringClass && it.returnType == Void.TYPE }
            // Preserve the specialized native cursor type; only extend its WHERE expression in this scope.
            val queries = KavaReflector.declaredMethods(changes.declaringClass).filter {
                it.parameterTypes.isEmpty() && Cursor::class.java.isAssignableFrom(it.returnType)
            }
            check(queries.isNotEmpty()) { "旧版首页 Cursor 查询缺失" }
            (queries + changes).forEach { method -> hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!captureRefresh(installation, param.thisObject, Refresh(notice))) return
                    enterQuery(installation)
                    legacyDepth.set((legacyDepth.get() ?: 0) + 1)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (queryScopes.get()?.lastOrNull()?.installation != installation) return
                    val depth = (legacyDepth.get() ?: 1) - 1
                    if (depth <= 0) legacyDepth.remove() else legacyDepth.set(depth)
                    leaveQuery(installation)
                }
            }, installation) }
        }
        methods.filter { it.returnType == String::class.java && it.parameterTypes.isEmpty() }.forEach { root ->
            hook(root, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive(installation)) return
                    if ((legacyDepth.get() ?: 0) <= 0 || param.hasThrowable()) return
                    val scope = queryScopes.get()?.lastOrNull()?.takeIf { it.installation == installation } ?: return
                    synchronized(lifecycleLock) {
                        if (!isActive(installation)) return
                        legacyStorage = WeakReference(param.thisObject)
                    }
                    val original = param.result as? String ?: return
                    val filter = scope.filter
                    if (filter?.active == true) {
                        param.result = filter.rootWhere(original)
                        markQueryFilterApplied()
                    } else {
                        param.result = ConversationGroupHomeVisibility.rootWhere(original, hiddenIds())
                    }
                }
            }, installation)
        }
        modern.forEach { page ->
            val conversion = KavaReflector.declaredMethods(page.declaringClass).single { method ->
                method.parameterTypes.size == 2 && method.parameterTypes.all { it.name.startsWith("com.tencent.mm.storage.") } &&
                    method.returnType != Void.TYPE && !method.returnType.isPrimitive
            }
            val rowType = conversion.returnType
            val conversation = KavaReflector.declaredFields(rowType).single {
                !Modifier.isStatic(it.modifiers) && it.type == conversion.parameterTypes[0]
            }
            val listField = KavaReflector.declaredFields(page.returnType).single {
                !Modifier.isStatic(it.modifiers) && List::class.java.isAssignableFrom(it.type)
            }
            val contact = KavaReflector.declaredFields(rowType).firstOrNull {
                !Modifier.isStatic(it.modifiers) && it.type == conversion.parameterTypes[1]
            }
            val visible = KavaReflector.declaredMethods(page.declaringClass).single {
                it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(rowType))
            }
            fun username(row: Any?): String? {
                val native = KavaReflector.readField(conversation, row) ?: return null
                // convertTo is the native storage object's stable public serialization API.
                val values = KavaReflector.invokeMethod(native, "convertTo") as? android.content.ContentValues
                return values?.getAsString("username")
            }
            hook(page, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isActive(installation)) enterQuery(installation)
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                    if (!isActive(installation)) return
                    if (param.hasThrowable()) return
                    updates.firstOrNull { it.declaringClass.isInstance(param.thisObject) }
                        ?.let { captureRefresh(installation, param.thisObject, Refresh(it)) }
                    val hidden = if (hasTabFilterInQuery()) emptySet() else hiddenIds()
                    if (hidden.isEmpty()) return
                    runCatching {
                        val result = param.result ?: return
                        val rows = KavaReflector.readField(listField, result) as? List<*> ?: return
                        val filtered = rows.filterNot { username(it) in hidden }
                        if (filtered.size != rows.size) {
                            if (rows is MutableList<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val mutable = rows as MutableList<Any?>
                                mutable.clear()
                                mutable.addAll(filtered)
                            } else {
                                check(KavaReflector.writeField(listField, result, ArrayList(filtered)))
                            }
                        }
                        // Keep native hasMore and next flag unchanged, including completely hidden pages.
                    }.onFailure { HLog.e("$TAG 过滤首页分页失败", it) }
                    } finally { leaveQuery(installation) }
                }
            }, installation)
            hook(visible, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isActive(installation)) return
                    if (param.result != true) return
                    val filter = tabFilter()
                    if (filter?.active == true) {
                        val row = param.args.firstOrNull()
                        val native = KavaReflector.readField(conversation, row) ?: return
                        val values = KavaReflector.invokeMethod(native, "convertTo") as? android.content.ContentValues ?: return
                        val nativeContact = contact?.let { KavaReflector.readField(it, row) }
                        val contactValues = nativeContact?.let { KavaReflector.invokeMethod(it, "convertTo") } as? android.content.ContentValues
                        param.result = filter.accepts(values.getAsString("username").orEmpty(),
                            values.getAsInteger("unReadCount") ?: 0, values.getAsInteger("unReadMuteCount") ?: 0,
                            contactValues?.getAsInteger("verifyFlag") ?: 0)
                        return
                    }
                    val hidden = hiddenIds()
                    if (hidden.isNotEmpty() && username(param.args.firstOrNull()) in hidden) param.result = false
                }
            }, installation)
        }
        updates.forEach { update -> hook(update, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val argument = param.args?.getOrNull(0)
                captureRefresh(installation, param.thisObject, Refresh(
                    update,
                    if (update.parameterTypes.size == 3 && argument != null) {
                        WeakReference(argument)
                    } else null
                ))
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!isActive(installation)) return
                if (tabFilter()?.active != true || param.hasThrowable()) return
                // Native incremental updates may reject folded children before the visibility test.
                // Reload at most once per burst; never recursively refresh our own type-5 reset.
                val type = param.args.getOrNull(update.parameterTypes.size - 2) as? Int
                if (type == 5) return
                requestTabRefresh(installation)
            }
        }, installation) }
        true
    }.getOrElse { HLog.e("$TAG 安装首页投影失败", it); false }

    private fun captureRefresh(installation: Installation, owner: Any, refresh: Refresh): Boolean = synchronized(lifecycleLock) {
        if (!isActive(installation)) return false
        refreshers[owner] = refresh
        true
    }

    private fun requestTabRefresh(installation: Installation): Unit = synchronized(lifecycleLock) {
        if (!isActive(installation) || !tabRefreshPending.compareAndSet(false, true)) return
        val task = object : Runnable {
            override fun run() {
                val needed = synchronized(lifecycleLock) {
                    if (delayedTabRefresh !== this || !isActive(installation)) return
                    delayedTabRefresh = null
                    tabRefreshPending.set(false)
                    tabFilter()?.active == true
                }
                if (needed) refresh(installation)
            }
        }
        delayedTabRefresh = task
        if (!main.postDelayed(task, 400L)) {
            delayedTabRefresh = null
            tabRefreshPending.set(false)
        }
    }

    private val refreshPending = AtomicBoolean(false)
    private var refreshTask: Runnable? = null

    fun refresh() {
        val installation = runCatching { installation() }.getOrNull() ?: return
        refresh(installation)
    }

    private fun refresh(installation: Installation): Unit = synchronized(lifecycleLock) {
        if (!isActive(installation) || !refreshPending.compareAndSet(false, true)) return
        val task = object : Runnable {
            override fun run() {
                val current = synchronized(lifecycleLock) {
                    if (refreshTask !== this || !isActive(installation)) return
                    refreshTask = null
                    refreshPending.set(false)
                    synchronized(refreshers) { refreshers.entries.map { it.key to it.value } }
                }
                current.forEach { (owner, refresh) ->
                    if (!isActive(installation)) return
                    runCatching {
                        val types = refresh.method.parameterTypes
                        when {
                            types.size == 2 -> KavaReflector.invokeOrThrow(refresh.method, owner, 5, "")
                            types[0] == Integer.TYPE -> legacyStorage?.get()?.takeIf(types[1]::isInstance)?.let {
                                KavaReflector.invokeOrThrow(refresh.method, owner, 5, it, "")
                            }
                            else -> refresh.argument?.get()?.let {
                                KavaReflector.invokeOrThrow(refresh.method, owner, it, 5, "")
                            }
                        }
                    }.onFailure { HLog.e("$TAG 刷新首页投影失败", it) }
                }
            }
        }
        refreshTask = task
        if (!main.post(task)) {
            refreshTask = null
            refreshPending.set(false)
        }
    }

    fun resetAfterUnhookAll() = synchronized(lifecycleLock) {
        lifecycleGeneration++
        installed.clear()
        refreshers.clear()
        legacyStorage = null
        hiddenIds = { emptySet() }
        tabFilter = { null }
        refreshTask?.let(main::removeCallbacks)
        delayedTabRefresh?.let(main::removeCallbacks)
        refreshTask = null
        delayedTabRefresh = null
        refreshPending.set(false)
        tabRefreshPending.set(false)
        legacyDepth.remove()
        queryScopes.remove()
    }

    private fun isModernUpdate(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE && types.size in 2..3 &&
            types[types.size - 2] == Integer.TYPE && types.last() == String::class.java
    }

    private fun hook(method: Member, callback: XC_MethodHook, installation: Installation): Unit = synchronized(lifecycleLock) {
        if (!isActive(installation)) throw CancellationException("首页安装代次已失效")
        if (!installed.add(method)) return
        try { HookRegistry.get().withInstallationGeneration(installation.hooks) { HookRegistry.get().hook(method, callback) } }
        catch (error: Throwable) { installed.remove(method); throw error }
    }
}
