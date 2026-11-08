package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.Adapter
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.conversation.WeChatConversationChangeApi
import h.Hchat.hooks.api.contact.WeChatChatroomChangeApi
import h.Hchat.hooks.api.contact.WeChatContactChangeApi
import h.Hchat.hooks.core.ConversationMenuExtensionRegistry
import h.Hchat.hooks.core.ConversationMenuExtensionTarget
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.quickcontactedit.ConversationMenuLocator
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ConversationGroupRuntime {
    private const val TAG = "[Hchat:ConversationGroup]"
    private const val CACHE_PREFS = "Hchat_conversation_group_method_cache"
    private const val CACHE_QUERY = "main_conversation_query"
    private const val CACHE_SHARE_RECENT_ADAPTER_RESET = "share_recent_adapter_reset"
    private const val CACHE_SHARE_RECENT_FORWARD_QUERY = "share_recent_forward_query"
    private const val CACHE_PARENT_UPDATE = "conversation_parent_update"
    private const val CACHE_NATIVE_SET_PINNED = "conversation_set_placed_top"
    private const val CACHE_NATIVE_UNSET_PINNED = "conversation_unset_placed_top"
    private const val CACHE_NATIVE_HIDE_CONVERSATION = "hide_conversation"
    private const val CACHE_CLICK = "main_conversation_click"
    private const val CACHE_NATIVE_GROUP_QUERY = "fold_group_conversation_query"
    private const val CACHE_NATIVE_GROUP_CLICK = "fold_group_conversation_click"
    private const val CACHE_NATIVE_GROUP_REFRESH = "fold_group_adapter_refresh"
    private const val CACHE_NATIVE_GROUP_MARK_READ = "fold_group_mark_read"
    private const val CACHE_NATIVE_GROUP_STATUS_NOTIFY = "fold_group_status_notify"
    private const val CACHE_NATIVE_GROUP_MENU_CREATE = "fold_group_menu_create"
    private const val CACHE_NATIVE_GROUP_MENU_CLICK = "fold_group_menu_click"
    private const val QUERY_ANCHOR =
        "select unReadCount, status, isSend, conversationTime, rconversation.username, content"
    private const val SHARE_RECENT_ADAPTER_TAG = "MicroMsg.NewRecentConversationAdapter"
    private const val SHARE_RECENT_RESET_ANCHOR = "resetData: recent forward control switch on"
    private const val SHARE_RECENT_FORWARD_ORDER_ANCHOR =
        "order by case rconversation.username "
    private const val SHARE_RECENT_ERROR_LOG_COOLDOWN_MS = 10_000L
    private const val NATIVE_GROUP_QUERY_ANCHOR = "select * from rconversation where"
    private const val NATIVE_GROUP_PARENT_ANCHOR = "parentRef = '"
    private const val NATIVE_GROUP_REFRESH_ANCHOR = "conversationboxservice"
    private const val NATIVE_GROUP_MARK_READ_ANCHOR = "updateUnreadByTalker %s"
    private const val NATIVE_GROUP_STATUS_ANCHOR = "enterSession %s %s"
    private const val NATIVE_HIDDEN_PARENT = "hidden_conv_parent"
    private const val CLICK_ANCHOR = "null user at position = "
    private const val CLICK_TAG = "MicroMsg.ConversationClickListener"
    private const val NATIVE_GROUP_ACTIVITY =
        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
    private const val NATIVE_GROUP_FRAGMENT =
        "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI\$ConvBoxServiceConversationFmUI"
    private const val FRAGMENT_ACTIVITY_SUPPORT = "com.tencent.mm.ui.FragmentActivitySupport"
    private const val CONTACT_USER_EXTRA = "Contact_User"
    private const val NATIVE_GROUP_TAG = "MicroMsg.ConvBoxServiceConversationFmUI"
    private const val NATIVE_GROUP_PLACEHOLDER = "@placeholder_foldgroup"
    private const val VIRTUAL_PREFIX = "wxid_hchat_group_"
    private const val PARENT_PREFIX = "hchat_conv_group:"
    private const val KEY_ORIGINAL_PARENT_REFS = "original_parent_refs"
    private const val MENU_ITEM_ID = 0x48434752
    private const val MENU_TITLE = "聊天分组"
    private const val PAGE_MENU_ITEM_ID = 0x4843474D
    private const val PAGE_MENU_TITLE = "菜单"
    private const val CHILD_MENU_REMOVE_ID = 0x48434760
    private const val CHILD_MENU_MOVE_ID = 0x48434761
    private const val CHILD_MENU_PIN_ID = 0x48434762
    private const val CHILD_MENU_BOTTOM_ID = 0x48434764
    private const val CONVERSATION_TOP_FLAG = 1L shl 62
    private const val PARENT_FAILURE_HISTORY_MAX = 4096
    private const val PARENT_FAILURE_HISTORY_TTL_MS = 24 * 60 * 60 * 1000L

    private val initialized = AtomicBoolean(false)
    private val syncScheduled = AtomicBoolean(false)
    private val syncRequested = AtomicBoolean(false)
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val menuBindings = Collections.synchronizedMap(
        WeakHashMap<MenuItem, ConversationMenuTarget>()
    )
    private val nativeGroupMenuBindings = Collections.synchronizedMap(
        WeakHashMap<MenuItem, NativeGroupMenuTarget>()
    )
    private val nativeGroupAdapterParents = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val nativeGroupLongClickListeners = Collections.synchronizedMap(
        WeakHashMap<AdapterView<*>, WeakReference<NativeGroupLongClickBinding>>()
    )
    private val nativeGroupQueryParent = ThreadLocal<String>()
    private val nativeGroupPageParent = ThreadLocal<String>()
    private val nativeGroupLongClickTarget = ThreadLocal<NativeGroupLongClickTarget>()
    private val shareRecentQueryDepth = ThreadLocal<Int>()
    private val expandingShareRecentQuery = ThreadLocal<Boolean>()
    private val shareRecentErrorLogAt = AtomicLong(0L)
    private val automaticAvatarSources = ConcurrentHashMap<String, String>()
    @Volatile private var effectiveConversationSnapshot: EffectiveConversationSnapshot? = null
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-ConversationGroup").apply { isDaemon = true }
    }
    private val lifecycleLock = Any()
    private val installGeneration = AtomicLong()
    private val parentRestoreFailures = LinkedHashMap<String, Long>(128, 0.75f, true)
    private val main = Handler(Looper.getMainLooper())
    private val lifecycleGeneration = AtomicLong()
    @Volatile private var runtimeActive = false
    @Volatile private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    @Volatile private var conversationSubscription: WeChatConversationChangeApi.Subscription? = null
    @Volatile private var contactSubscription: WeChatContactChangeApi.Subscription? = null
    @Volatile private var chatroomSubscription: WeChatChatroomChangeApi.Subscription? = null
    @Volatile private var parentUpdateMethod: Method? = null
    @Volatile private var nativeGroupRefreshMethod: Method? = null
    @Volatile private var conversationStorage: Any? = null

    @JvmStatic
    fun beginInstall(): Long = installGeneration.incrementAndGet()

    @JvmStatic
    fun isInstallGenerationActive(generation: Long): Boolean =
        installGeneration.get() == generation

    @JvmStatic
    fun install(context: FeatureContext, generation: Long): Boolean = synchronized(lifecycleLock) {
        if (!isInstallGenerationActive(generation)) true else installInternal(context)
    }

    private fun installInternal(context: FeatureContext): Boolean {
        val query = locateQueryMethod(context) ?: return false
        val shareRecentAdapterReset = locateShareRecentAdapterResetMethod(context) ?: return false
        val shareRecentForwardQuery = locateShareRecentForwardQueryMethod(context) ?: return false
        val parentUpdate = locateParentUpdateMethod(context) ?: return false
        val nativeSetPinned = locateNativeSetPinnedMethod(context) ?: return false
        val nativeUnsetPinned = locateNativeUnsetPinnedMethod(context) ?: return false
        val nativeHideConversation = locateNativeHideConversationMethod(context) ?: return false
        val click = locateClickMethod(context) ?: return false
        val nativeGroupQuery = locateNativeGroupQueryMethod(context) ?: return false
        val nativeGroupClick = locateNativeGroupClickMethod(context) ?: return false
        val nativeGroupRefresh = locateNativeGroupRefreshMethod(context) ?: return false
        val nativeGroupMarkRead = locateNativeGroupMarkReadMethod(context) ?: return false
        val nativeGroupStatusNotify = locateNativeGroupStatusNotifyMethod(context)
        val nativeGroupMenuMethods = locateNativeGroupMenuMethods(context)
        val conversationMenu = ConversationMenuLocator.menuCreateMethod(context) { message, throwable ->
            HLog.e("$TAG $message", throwable)
        } ?: return false
        parentUpdateMethod = parentUpdate
        nativeGroupRefreshMethod = nativeGroupRefresh
        val homeProjectionInstalled = ConversationGroupHomeProjection.install(context) {
            effectiveConversationSnapshot?.hiddenHomeIds.orEmpty()
        }
        if (!homeProjectionInstalled) {
            HLog.e("$TAG 首页公众号投影 Hook 安装失败，保留原生首页")
        }
        initializeRuntime(context)
        val generation = lifecycleGeneration.get()
        val queryInstalled = hook(query, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (isExpandingShareRecentQuery()) return
                captureConversationStorage(param.thisObject, parentUpdate, context.hostContext(), generation)
                if (!ConversationGroupStore.isEnabled(context.hostContext())) return
                val cursor = param.result as? Cursor ?: return
                if (isShareRecentQuery()) {
                    val groupedIds = shareGroupedConversationIds(context.hostContext())
                    val expanded = expandShareRecentQuery(query, param, 2, groupedIds)
                    param.result = flattenShareRecentCursor(cursor, expanded, groupedIds)
                    return
                }
                val rootGroups = ConversationGroupStore.load(context.hostContext())
                    .filter { it.parentId == null }
                param.result = reorderVirtualGroupRows(filterProjectedOfficialRows(context.hostContext(), cursor), rootGroups)
            }
        })
        val shareRecentAdapterInstalled = hook(
            shareRecentAdapterReset,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    enterShareRecentQuery()
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    leaveShareRecentQuery()
                }
            }
        )
        val shareRecentForwardQueryInstalled = hook(
            shareRecentForwardQuery,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (isExpandingShareRecentQuery() || !isShareRecentQuery() ||
                        !ConversationGroupStore.isEnabled(context.hostContext())
                    ) {
                        return
                    }
                    val cursor = param.result as? Cursor ?: return
                    val groupedIds = shareGroupedConversationIds(context.hostContext())
                    val expanded = expandShareRecentQuery(
                        shareRecentForwardQuery,
                        param,
                        3,
                        groupedIds,
                        candidateListArgumentIndex = 0
                    )
                    param.result = flattenShareRecentCursor(cursor, expanded, groupedIds)
                }
            }
        )
        val clickInstalled = hook(click, groupClickHook())
        val nativeGroupQueryInstalled = hook(nativeGroupQuery, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val parent = nativeGroupQueryParent.get()?.takeIf(::isVirtualTalker) ?: return
                if (param.args?.getOrNull(2) == NATIVE_GROUP_REFRESH_ANCHOR) {
                    param.args[0] = 0
                    param.args[2] = parent
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val parent = nativeGroupQueryParent.get()?.takeIf(::isVirtualTalker) ?: return
                val cursor = param.result as? Cursor ?: return
                val excluded = (param.args?.getOrNull(1) as? List<*>)
                    .orEmpty().filterIsInstance<String>().toSet()
                val limit = param.args?.getOrNull(3) as? Int ?: 0
                val merged = if (param.args?.getOrNull(0) == 0) {
                    mergeProjectedOfficialRows(context.hostContext(), parent, cursor, excluded, limit)
                } else cursor
                param.result = reorderNativeGroupCursor(context.hostContext(), parent, merged)
            }
        })
        val nativeGroupClickInstalled = hook(nativeGroupClick, nativeGroupClickHook())
        val nativeGroupRefreshInstalled = hook(nativeGroupRefresh, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                nativeGroupQueryParent.remove()
                nativeGroupAdapterParents[param.thisObject]
                    ?.takeIf(::isVirtualTalker)
                    ?.let(nativeGroupQueryParent::set)
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                nativeGroupQueryParent.remove()
            }
        })
        val nativeGroupMarkReadInstalled = hook(nativeGroupMarkRead, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (isVirtualTalker(param.args?.getOrNull(0) as? String)) {
                    param.result = false
                }
            }
        })
        val nativeSetPinnedInstalled = hook(
            nativeSetPinned,
            nativeGroupPinnedHook(context.hostContext(), pinned = true)
        )
        val nativeUnsetPinnedInstalled = hook(
            nativeUnsetPinned,
            nativeGroupPinnedHook(context.hostContext(), pinned = false)
        )
        val nativeHideConversationInstalled = hook(
            nativeHideConversation,
            nativeHideConversationHook(nativeHideConversation)
        )
        val nativeGroupStatusInstalled = nativeGroupStatusNotify?.let { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isVirtualTalker(nativeGroupPageParent.get()) &&
                        param.args?.getOrNull(0) == NATIVE_GROUP_PLACEHOLDER
                    ) {
                        param.result = null
                    }
                }
            })
        } ?: true
        val nativePageInstalled = installNativeGroupPageHooks(context)
        nativeGroupMenuMethods?.let { methods ->
            val clickInstalled = hook(
                methods.click,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleNativeGroupChildMenuClick(param)
                    }
                }
            )
            val createInstalled = clickInstalled && hook(
                methods.create,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        appendNativeGroupChildMenu(param)
                    }
                }
            )
            if (!clickInstalled || !createInstalled) {
                HLog.e("$TAG 微信原生分组长按菜单 Hook 安装不完整，已保留原生菜单")
            }
        }
        val conversationMenuInstalled = hook(conversationMenu, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                addConversationGroupMenuItem(param)
            }
        })
        return homeProjectionInstalled && queryInstalled && shareRecentAdapterInstalled &&
            shareRecentForwardQueryInstalled && clickInstalled &&
            nativeGroupQueryInstalled && nativeGroupClickInstalled && nativeGroupRefreshInstalled &&
            nativeGroupMarkReadInstalled && nativeSetPinnedInstalled && nativeUnsetPinnedInstalled &&
            nativeHideConversationInstalled && nativeGroupStatusInstalled && nativePageInstalled &&
            conversationMenuInstalled
    }

    @JvmStatic
    fun syncAsync(context: Context) {
        val appContext = context.applicationContext
        val generation = synchronized(lifecycleLock) {
            if (!runtimeActive) return
            syncRequested.set(true)
            if (!syncScheduled.compareAndSet(false, true)) return
            lifecycleGeneration.get()
        }
        executor.execute {
            try {
                do {
                    synchronized(lifecycleLock) {
                        if (!isRuntimeGenerationActive(generation)) return@execute
                        syncRequested.set(false)
                    }
                    runCatching { syncDatabase(appContext, generation) }
                        .onFailure { HLog.e("$TAG 同步聊天分组失败: ${it.message}", it) }
                } while (runtimeActive && generation == lifecycleGeneration.get() && syncRequested.get())
            } finally {
                syncScheduled.set(false)
                if (runtimeActive && syncRequested.get()) {
                    syncAsync(appContext)
                }
            }
        }
    }

    @JvmStatic
    fun destroy(context: FeatureContext) = synchronized(lifecycleLock) {
        installGeneration.incrementAndGet()
        runtimeActive = false
        lifecycleGeneration.incrementAndGet()
        preferenceListener?.let {
            runCatching {
                HchatStorage.preferences(context.hostContext(), ConversationGroupStore.PREFS_NAME)
                    .unregisterOnSharedPreferenceChangeListener(it)
            }
        }
        preferenceListener = null
        conversationSubscription?.unsubscribe()
        contactSubscription?.unsubscribe()
        chatroomSubscription?.unsubscribe()
        conversationSubscription = null
        contactSubscription = null
        chatroomSubscription = null
        initialized.set(false)
        syncRequested.set(false)
        effectiveConversationSnapshot = null
        automaticAvatarSources.clear()
        synchronized(parentRestoreFailures) { parentRestoreFailures.clear() }
        conversationStorage = null
        parentUpdateMethod = null
        nativeGroupRefreshMethod = null
        hookedMethods.clear()
        synchronized(menuBindings) { menuBindings.clear() }
        synchronized(nativeGroupMenuBindings) { nativeGroupMenuBindings.clear() }
        synchronized(nativeGroupAdapterParents) { nativeGroupAdapterParents.clear() }
        nativeGroupQueryParent.remove()
        nativeGroupPageParent.remove()
        nativeGroupLongClickTarget.remove()
        shareRecentQueryDepth.remove()
        expandingShareRecentQuery.remove()
        main.removeCallbacksAndMessages(null)
        clearNativeGroupLongClickListeners()
        ConversationGroupQuickDialog.closeAll()
        ConversationGroupQuickDialog.cancelPendingDocuments()
        ConversationGroupSendPicker.cancelAll()
        ConversationGroupTask.cancelAll()
    }

    @JvmStatic
    fun virtualTalker(groupId: String): String = VIRTUAL_PREFIX + stableGroupKey(groupId)

    @JvmStatic
    fun isVirtualTalker(value: String?): Boolean = value?.startsWith(VIRTUAL_PREFIX) == true

    /**
     * Returns the real conversation whose avatar should represent an automatic
     * group avatar. The virtual talker remains the conversation identity while
     * this value is used as the native avatar cache key.
     */
    @JvmStatic
    fun automaticAvatarSource(talker: String?): String? {
        return talker?.takeIf(::isVirtualTalker)?.let(automaticAvatarSources::get)
    }

    internal fun effectiveConversationIdsForPicker(
        groups: List<ConversationGroup>
    ): Map<String, List<String>> {
        val snapshot = effectiveConversationSnapshot ?: return emptyMap()
        return if (snapshot.account == ConversationGroupStore.accountKey() && snapshot.groups == groups) {
            snapshot.conversationIds
        } else emptyMap()
    }

    private fun initializeRuntime(context: FeatureContext) {
        if (!initialized.compareAndSet(false, true)) return
        runtimeActive = true
        lifecycleGeneration.incrementAndGet()
        val generation = lifecycleGeneration.get()
        val prefs = HchatStorage.preferences(context.hostContext(), ConversationGroupStore.PREFS_NAME)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (runtimeActive && generation == lifecycleGeneration.get() &&
                (key == ConversationGroupStore.KEY_ENABLE || key == ConversationGroupStore.KEY_DATA)
            ) {
                syncAsync(context.hostContext())
            }
        }
        preferenceListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)
        conversationSubscription = WeChatApis.conversationChanges()?.subscribe { change ->
            if (!runtimeActive || generation != lifecycleGeneration.get()) return@subscribe
            val groups = ConversationGroupStore.load(context.hostContext())
            val affectedTalkers = change?.affectedUsernames()?.toList().orEmpty()
                .filterNot(::isVirtualTalker)
            val hiddenNativeHome = effectiveConversationSnapshot?.hiddenHomeIds.orEmpty()
                .any { it in ConversationGroupHomeVisibility.nativeParents }
            val parentOnlyUpdate = change?.databaseChange?.values?.let { values ->
                values.containsKey("parentRef") && values.keySet().all {
                    it == "parentRef" || it == "username"
                }
            } == true
            if (!parentOnlyUpdate && affectedTalkers.isNotEmpty() &&
                (groups.any { it.automaticGroupingEnabled } || affectedTalkers.any { talker ->
                    talker.isNotBlank() &&
                        ConversationGroupStore.conversationOwner(groups, talker) != null
                } || hiddenNativeHome && affectedTalkers.any { it.startsWith("gh_") })) {
                syncAsync(context.hostContext())
            }
        }
        contactSubscription = WeChatApis.contactChanges()?.subscribe { change ->
            if (!runtimeActive || generation != lifecycleGeneration.get()) return@subscribe
            val groups = ConversationGroupStore.load(context.hostContext())
            val databaseChange = change?.databaseChange
            val talker = change?.wxId().orEmpty().ifBlank {
                databaseChange?.takeIf {
                    it.whereClause?.contains("username", ignoreCase = true) == true
                }?.whereArgs?.firstOrNull(String::isNotBlank).orEmpty()
            }
            val automaticGroupingChanged = groups.any { it.automaticGroupingEnabled }
            val mutedUnreadChanged = databaseChange?.table.equals("rcontact", ignoreCase = true) &&
                groups.any { it.unreadCountMode == ConversationGroupUnreadMode.EXCLUDE_MUTED } &&
                (talker.isBlank() || ConversationGroupStore.conversationOwner(groups, talker) != null)
            if (!isVirtualTalker(talker) && (automaticGroupingChanged || mutedUnreadChanged)) {
                syncAsync(context.hostContext())
            }
        }
        chatroomSubscription = WeChatApis.chatroomChanges()?.subscribe {
            if (!runtimeActive || generation != lifecycleGeneration.get()) return@subscribe
            val groups = ConversationGroupStore.load(context.hostContext())
            if (groups.any {
                    it.automaticGroupingEnabled ||
                        it.unreadCountMode == ConversationGroupUnreadMode.EXCLUDE_MUTED
                }) {
                syncAsync(context.hostContext())
            }
        }
        syncAsync(context.hostContext())
    }

    private fun groupClickHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val adapter = param.args?.getOrNull(0) as? AdapterView<*> ?: return
                val view = param.args?.getOrNull(1) as? View ?: return
                val position = param.args?.getOrNull(2) as? Int ?: return
                val item = runCatching { adapter.getItemAtPosition(position) }.getOrNull() ?: return
                val talker = virtualTalkerFromItem(item) ?: return
                val activity = findActivity(view.context) ?: return
                param.result = null
                main.post {
                    val groupId = ConversationGroupStore.load(activity)
                        .firstOrNull { virtualTalker(it.id) == talker }
                        ?.id ?: return@post
                    showGroup(activity, groupId)
                }
            }
        }
    }

    private fun nativeGroupPinnedHook(context: Context, pinned: Boolean): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result as? Boolean != true) return
                val talker = param.args?.getOrNull(0) as? String
                if (!isVirtualTalker(talker)) return
                val group = ConversationGroupStore.load(context)
                    .firstOrNull { virtualTalker(it.id) == talker }
                    ?: return
                if (group.pinned == pinned) return
                if (!ConversationGroupStore.updateGroup(context, group.copy(pinned = pinned))) {
                    HLog.e("$TAG 同步微信原生分组置顶状态失败: talker=$talker pinned=$pinned")
                }
            }
        }
    }

    private fun nativeHideConversationHook(method: Method): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val talker = (param.args?.getOrNull(0) as? String)?.trim().orEmpty()
                if (talker.isEmpty() || isVirtualTalker(talker)) return
                val parent = WeChatApis.database()?.queryFirstString(
                    "SELECT IFNULL(parentRef,'') AS parentRef " +
                        "FROM rconversation WHERE username=? LIMIT 1",
                    arrayOf(talker),
                    "parentRef"
                ).orEmpty()
                if (!isVirtualTalker(parent)) return

                if (WeChatApis.conversations()?.deleteConversation(talker) == true) {
                    param.result = null
                } else {
                    HLog.e(
                        "$TAG 隐藏分组会话失败，继续执行微信原逻辑: " +
                            "talker=$talker method=${method.toGenericString()}"
                    )
                }
            }
        }
    }

    private fun addConversationGroupMenuItem(param: XC_MethodHook.MethodHookParam) {
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        val view = param.args?.getOrNull(1) as? View ?: return
        val activity = findActivity(view.context) ?: return
        if (!ConversationGroupStore.isEnabled(activity)) return
        if (!installConversationMenuClickHook(param.thisObject)) return
        val talker = conversationMenuTalker(param.thisObject) ?: return

        menu.removeItem(MENU_ITEM_ID)
        val groupId = runCatching { menu.getItem(0).groupId }.getOrDefault(0)
        val item = menu.add(groupId, MENU_ITEM_ID, 0, MENU_TITLE)
        moveMenuItemToFront(menu, item)
        menuBindings[item] = ConversationMenuTarget(activity, talker)
    }

    private fun installConversationMenuClickHook(listener: Any?): Boolean {
        listener ?: return false
        val method = KavaReflector.declaredFields(listener.javaClass)
            .asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> KavaReflector.readField(field, listener) }
            .mapNotNull { callback ->
                KavaReflector.findMethod(
                    callback.javaClass,
                    "onMMMenuItemSelected",
                    MenuItem::class.java,
                    Integer.TYPE
                )
            }
            .firstOrNull { candidate ->
                candidate.returnType == Void.TYPE &&
                    candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
            } ?: return false
        return hook(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val item = param.args?.getOrNull(0) as? MenuItem ?: return
                if (item.itemId != MENU_ITEM_ID) return
                val target = menuBindings.remove(item) ?: return
                param.result = null
                main.post {
                    val activity = target.activity.get() ?: return@post
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        ConversationGroupQuickDialog.show(
                            activity = activity,
                            talker = target.talker,
                            onChanged = { syncAsync(activity) }
                        )
                    }
                }
            }
        })
    }

    private fun conversationMenuTalker(listener: Any?): String? {
        listener ?: return null
        val database = WeChatApis.database() ?: return null
        var current: Class<*>? = listener.javaClass
        while (current != null && current != Any::class.java) {
            val talker = KavaReflector.declaredFields(current)
                .asSequence()
                .filter { field ->
                    field.type == String::class.java && !Modifier.isStatic(field.modifiers)
                }
                .mapNotNull { field -> KavaReflector.readField(field, listener) as? String }
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .firstOrNull { candidate ->
                    database.queryFirstString(
                        "SELECT username FROM rconversation WHERE username=? LIMIT 1",
                        arrayOf(candidate),
                        "username"
                    ) == candidate
                }
            if (talker != null) return talker
            current = current.superclass
        }
        return null
    }

    private fun moveMenuItemToFront(menu: ContextMenu, item: MenuItem) {
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                val index = items.indexOfFirst { candidate ->
                    candidate === item || (candidate as? MenuItem)?.itemId == MENU_ITEM_ID
                }
                if (index > 0) {
                    runCatching {
                        val moved = items.removeAt(index)
                        items.add(0, moved)
                    }
                }
                if (index >= 0) return
            }
            current = current.superclass
        }
    }

    private fun nativeGroupClickHook(): XC_MethodHook {
        return object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val fragment = nativeGroupFragmentFromListener(param.thisObject) ?: return
                if (!isVirtualGroupFragment(fragment)) return
                val adapter = param.args?.getOrNull(0) as? AdapterView<*> ?: return
                val view = param.args?.getOrNull(1) as? View ?: return
                val position = param.args?.getOrNull(2) as? Int ?: return
                val item = runCatching { adapter.getItemAtPosition(position) }.getOrNull() ?: return
                val talker = conversationTalkerFromItem(item)
                val activity = findActivity(view.context) ?: return
                param.result = null
                if (talker.isNullOrBlank()) {
                    HLog.e("$TAG 微信原生分组列表无法解析会话: item=${item.javaClass.name}")
                    return
                }
                main.post {
                    val child = ConversationGroupStore.load(activity)
                        .firstOrNull { virtualTalker(it.id) == talker }
                    if (child != null) {
                        showGroup(activity, child.id)
                    } else if (!openNativeGroupConversation(fragment, talker) &&
                        WeChatApis.conversations()?.openChat(talker) != true
                    ) {
                        HLog.e("$TAG 打开分组内会话失败: talker=$talker")
                    }
                }
            }
        }
    }

    private fun showGroup(activity: Activity, groupId: String) {
        val group = ConversationGroupStore.load(activity).firstOrNull { it.id == groupId } ?: return
        val talker = virtualTalker(group.id)
        runCatching {
            activity.startActivity(
                Intent().setClassName(activity, NATIVE_GROUP_ACTIVITY).apply {
                    putExtra(CONTACT_USER_EXTRA, talker)
                }
            )
        }.onFailure {
            HLog.e("$TAG 打开微信原生分组页面失败: group=${group.id} ${it.message}", it)
        }
    }

    private fun installNativeGroupPageHooks(context: FeatureContext): Boolean {
        val fragmentClass = KavaReflector.loadClass(NATIVE_GROUP_FRAGMENT, context.hostClassLoader())
            ?: return false
        val lifecycle = KavaReflector.findDeclaredMethod(
            fragmentClass,
            "onActivityCreated",
            Bundle::class.java
        ) ?: return false
        val fragmentActivitySupport = KavaReflector.loadClass(
            FRAGMENT_ACTIVITY_SUPPORT,
            context.hostClassLoader()
        ) ?: return false
        val getStringExtra = KavaReflector.findDeclaredMethod(
            fragmentActivitySupport,
            "getStringExtra",
            String::class.java
        ) ?: return false
        val parentExtraInstalled = hook(getStringExtra, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name != NATIVE_GROUP_FRAGMENT ||
                    param.args?.getOrNull(0) != CONTACT_USER_EXTRA
                ) {
                    return
                }
                val activity = (KavaReflector.invokeMethod(param.thisObject, "getActivity")
                    ?: KavaReflector.invokeMethod(param.thisObject, "thisActivity")) as? Activity
                    ?: return
                val talker = activity.intent?.getStringExtra(CONTACT_USER_EXTRA)
                    ?.takeIf(::isVirtualTalker) ?: return
                param.result = talker
            }
        })
        val lifecycleInstalled = hook(lifecycle, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                customizeNativeGroupPage(param.thisObject, context.hostContext())
            }
        })
        val lifecycleContextInstalled = listOf("onPause", "onResume").all { name ->
            val method = KavaReflector.findDeclaredMethod(fragmentClass, name) ?: return@all false
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    nativeGroupPageParent.remove()
                    nativeGroupTalker(param.thisObject)?.let(nativeGroupPageParent::set)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (name == "onResume") {
                            // A read update can happen while the child chat is open and
                            // may finish outside the group page lifecycle callback.
                            syncAsync(context.hostContext())
                            updateNativeGroupUnread(param.thisObject)
                            updateNativeGroupTitle(param.thisObject, context.hostContext())
                        }
                    } finally {
                        nativeGroupPageParent.remove()
                    }
                }
            })
        }
        if (!parentExtraInstalled || !lifecycleInstalled || !lifecycleContextInstalled) {
            HLog.e("$TAG 微信原生分组页面 Hook 安装不完整: fragment=$NATIVE_GROUP_FRAGMENT")
        }
        return parentExtraInstalled && lifecycleInstalled && lifecycleContextInstalled
    }

    private fun customizeNativeGroupPage(fragment: Any?, context: Context) {
        val target = fragment ?: return
        val talker = nativeGroupTalker(target)
        if (talker == null) return
        val groups = ConversationGroupStore.load(context)
        val group = groups.firstOrNull { virtualTalker(it.id) == talker }
        if (group == null) return
        val adapter = KavaReflector.readField(target, "adapter")
        if (adapter != null) nativeGroupAdapterParents[adapter] = talker
        (KavaReflector.readField(target, "emptyTipTv") as? TextView)
            ?.text = "当前分组没有会话"
        (KavaReflector.readField(target, "appbrandMessageLV") as? AdapterView<*>)
            ?.let { bindNativeGroupLongClick(it, target, context, group.id) }
        val refreshed = adapter != null &&
            KavaReflector.invokeSuccessfully(nativeGroupRefreshMethod, adapter)
        if (adapter != null && !refreshed) {
            HLog.e("$TAG 刷新微信原生分组列表失败: adapter=${adapter.javaClass.name}")
        }
        setNativeGroupTitle(target, talker, group.name, adapter)
        installNativeGroupPageMenu(target, context, group.id)
    }

    private fun installNativeGroupPageMenu(fragment: Any, context: Context, groupId: String) {
        val method = KavaReflector.findMethodRecursive(
            fragment.javaClass,
            "addTextOptionMenu",
            Integer.TYPE,
            String::class.java,
            MenuItem.OnMenuItemClickListener::class.java
        )
        val listener = MenuItem.OnMenuItemClickListener {
            val activity = (KavaReflector.invokeMethod(fragment, "getActivity")
                ?: KavaReflector.invokeMethod(fragment, "thisActivity")) as? Activity
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                ConversationGroupMenu.show(activity, groupId) {
                    syncAsync(context)
                    updateNativeGroupTitle(fragment, context)
                    updateNativeGroupUnread(fragment)
                    KavaReflector.readField(fragment, "adapter")?.let { adapter ->
                        KavaReflector.invokeSuccessfully(nativeGroupRefreshMethod, adapter)
                    }
                }
            }
            true
        }
        if (!KavaReflector.invokeSuccessfully(
                method,
                fragment,
                PAGE_MENU_ITEM_ID,
                PAGE_MENU_TITLE,
                listener
            )
        ) {
            HLog.e(
                "$TAG 添加微信原生分组页菜单失败: fragment=${fragment.javaClass.name} " +
                    "method=${method?.toGenericString().orEmpty()}"
            )
        }
    }

    private fun bindNativeGroupLongClick(
        list: AdapterView<*>,
        fragment: Any,
        context: Context,
        groupId: String
    ) {
        val existing = nativeGroupLongClickListeners[list]?.get()
        if (existing != null && list.onItemLongClickListener === existing) return
        val original = list.onItemLongClickListener
        val binding = NativeGroupLongClickBinding(original, AdapterView.OnItemLongClickListener { parent, view, position, id ->
            if (!runtimeActive) {
                return@OnItemLongClickListener original?.onItemLongClick(parent, view, position, id) ?: false
            }
            val item = runCatching { parent.getItemAtPosition(position) }.getOrNull()
            val talker = item?.let(::conversationTalkerFromItem)
            if (!isVirtualTalker(talker)) {
                val activity = findActivity(view.context)
                    ?: return@OnItemLongClickListener false
                nativeGroupLongClickTarget.set(
                    NativeGroupLongClickTarget(activity, talker.orEmpty(), groupId, fragment)
                )
                return@OnItemLongClickListener try {
                    original?.onItemLongClick(parent, view, position, id) ?: false
                } finally {
                    nativeGroupLongClickTarget.remove()
                }
            }
            val activity = findActivity(view.context) ?: return@OnItemLongClickListener false
            main.post {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    ConversationGroupQuickDialog.show(
                        activity = activity,
                        talker = talker.orEmpty(),
                        onChanged = {
                            syncAsync(activity)
                            refreshNativeGroupFragment(fragment, context)
                        }
                    )
                }
            }
            true
        })
        list.onItemLongClickListener = binding
        nativeGroupLongClickListeners[list] = WeakReference(binding)
    }

    private fun clearNativeGroupLongClickListeners() {
        val bindings = synchronized(nativeGroupLongClickListeners) {
            nativeGroupLongClickListeners.map { WeakReference(it.key) to it.value }
                .also { nativeGroupLongClickListeners.clear() }
        }
        val restore = {
            bindings.forEach { (listRef, bindingRef) ->
                val list = listRef.get()
                val binding = bindingRef.get()
                if (list != null && binding != null && list.onItemLongClickListener === binding) {
                    list.onItemLongClickListener = binding.original
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) restore() else main.post(restore)
    }

    private class NativeGroupLongClickBinding(
        val original: AdapterView.OnItemLongClickListener?,
        private val callback: AdapterView.OnItemLongClickListener
    ) : AdapterView.OnItemLongClickListener {
        override fun onItemLongClick(parent: AdapterView<*>, view: View, position: Int, id: Long): Boolean =
            callback.onItemLongClick(parent, view, position, id)
    }

    private fun appendNativeGroupChildMenu(param: XC_MethodHook.MethodHookParam) {
        val target = nativeGroupLongClickTarget.get() ?: return
        if (target.talker.isBlank() || isVirtualTalker(target.talker)) return
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        val activity = target.activity.get() ?: return
        val group = ConversationGroupStore.load(activity)
            .firstOrNull { it.id == target.groupId }
            ?: return
        menu.removeItem(CHILD_MENU_REMOVE_ID)
        menu.removeItem(CHILD_MENU_MOVE_ID)
        menu.removeItem(CHILD_MENU_PIN_ID)
        menu.removeItem(CHILD_MENU_BOTTOM_ID)
        if (target.talker in group.conversationIds) {
            val removeItem = menu.add(0, CHILD_MENU_REMOVE_ID, menu.size(), "移出")
            val moveItem = menu.add(0, CHILD_MENU_MOVE_ID, menu.size(), "移至")
            val pinned = target.talker in group.pinnedConversationIds
            val bottom = target.talker in group.bottomConversationIds
            val pinItem = menu.add(
                0,
                CHILD_MENU_PIN_ID,
                menu.size(),
                if (pinned) "取消置顶" else "置顶聊天"
            )
            val bottomItem = menu.add(
                0,
                CHILD_MENU_BOTTOM_ID,
                menu.size(),
                if (bottom) "取消置底" else "置底聊天"
            )
            nativeGroupMenuBindings[removeItem] = NativeGroupMenuTarget(
                target,
                NativeGroupMenuAction.REMOVE
            )
            nativeGroupMenuBindings[moveItem] = NativeGroupMenuTarget(
                target,
                NativeGroupMenuAction.MOVE
            )
            nativeGroupMenuBindings[pinItem] = NativeGroupMenuTarget(
                target,
                if (pinned) NativeGroupMenuAction.UNPIN else NativeGroupMenuAction.PIN
            )
            nativeGroupMenuBindings[bottomItem] = NativeGroupMenuTarget(
                target,
                if (bottom) NativeGroupMenuAction.UNBOTTOM else NativeGroupMenuAction.BOTTOM
            )
        }
        val extensionTarget = ConversationMenuExtensionTarget(activity, target.talker)
        ConversationMenuExtensionRegistry.visibleItems(extensionTarget).forEach { extension ->
            menu.removeItem(extension.itemId)
            val item = menu.add(0, extension.itemId, menu.size(), extension.title)
            nativeGroupMenuBindings[item] = NativeGroupMenuTarget(
                target = target,
                action = NativeGroupMenuAction.EXTENSION,
                extensionItemId = extension.itemId
            )
        }
    }

    private fun handleNativeGroupChildMenuClick(param: XC_MethodHook.MethodHookParam) {
        val item = param.args?.getOrNull(0) as? MenuItem ?: return
        val binding = nativeGroupMenuBindings.remove(item) ?: return
        param.result = null
        main.post {
            val target = binding.target
            val activity = target.activity.get() ?: return@post
            val fragment = target.fragment.get() ?: return@post
            if (activity.isFinishing || activity.isDestroyed) return@post
            when (binding.action) {
                NativeGroupMenuAction.REMOVE -> {
                    val success = ConversationGroupStore.setConversationGroup(
                        activity,
                        target.talker,
                        null
                    )
                    Toast.makeText(
                        activity.applicationContext,
                        if (success) "已移出当前分组" else "移出会话失败",
                        Toast.LENGTH_SHORT
                    ).show()
                    if (success) {
                        syncAsync(activity)
                        refreshNativeGroupFragment(fragment, activity)
                    }
                }
                NativeGroupMenuAction.MOVE -> ConversationGroupQuickDialog.show(
                    activity = activity,
                    talker = target.talker,
                    onChanged = {
                        syncAsync(activity)
                        refreshNativeGroupFragment(fragment, activity)
                    }
                )
                NativeGroupMenuAction.PIN,
                NativeGroupMenuAction.UNPIN -> {
                    val pin = binding.action == NativeGroupMenuAction.PIN
                    val success = ConversationGroupStore.setConversationPinned(
                        activity,
                        target.groupId,
                        target.talker,
                        pin
                    )
                    Toast.makeText(
                        activity.applicationContext,
                        when {
                            !success -> "更新分组置顶失败"
                            pin -> "已在当前分组置顶"
                            else -> "已取消当前分组置顶"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    if (success) refreshNativeGroupFragment(fragment, activity)
                }
                NativeGroupMenuAction.BOTTOM,
                NativeGroupMenuAction.UNBOTTOM -> {
                    val bottom = binding.action == NativeGroupMenuAction.BOTTOM
                    val success = ConversationGroupStore.setConversationBottom(
                        activity,
                        target.groupId,
                        target.talker,
                        bottom
                    )
                    Toast.makeText(
                        activity.applicationContext,
                        when {
                            !success -> "更新分组置底失败"
                            bottom -> "已在当前分组置底"
                            else -> "已取消当前分组置底"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    if (success) refreshNativeGroupFragment(fragment, activity)
                }
                NativeGroupMenuAction.EXTENSION -> {
                    binding.extensionItemId?.let { itemId ->
                        ConversationMenuExtensionRegistry.perform(
                            itemId,
                            ConversationMenuExtensionTarget(activity, target.talker)
                        )
                    }
                }
            }
        }
    }

    private fun refreshNativeGroupFragment(fragment: Any, context: Context) {
        val adapter = KavaReflector.readField(fragment, "adapter") ?: return
        nativeGroupTalker(fragment)?.let { nativeGroupAdapterParents[adapter] = it }
        if (!KavaReflector.invokeSuccessfully(nativeGroupRefreshMethod, adapter)) {
            HLog.e("$TAG 刷新微信原生分组列表失败: adapter=${adapter.javaClass.name}")
        }
        updateNativeGroupTitle(fragment, context)
        updateNativeGroupUnread(fragment)
    }

    private fun projectedOfficialSnapshot(context: Context): EffectiveConversationSnapshot? {
        if (!ConversationGroupStore.isEnabled(context)) return null
        val snapshot = effectiveConversationSnapshot ?: return null
        return snapshot.takeIf {
            it.account == ConversationGroupStore.accountKey() &&
                it.groups == ConversationGroupStore.load(context)
        }
    }

    private fun filterProjectedOfficialRows(context: Context, cursor: Cursor): Cursor {
        if (ConversationGroupHomeProjection.hasTabFilterInQuery()) return cursor
        val snapshot = projectedOfficialSnapshot(context) ?: return cursor
        return runCatching {
            ConversationGroupOfficialCursor.filter(cursor, snapshot.hiddenHomeIds)
        }.onFailure { HLog.e("$TAG 过滤首页公众号分组投影失败", it) }.getOrDefault(cursor)
    }

    private fun mergeProjectedOfficialRows(
        context: Context,
        parentTalker: String,
        cursor: Cursor,
        excluded: Set<String>,
        limit: Int
    ): Cursor {
        val snapshot = projectedOfficialSnapshot(context) ?: return cursor
        val group = snapshot.groups.firstOrNull { virtualTalker(it.id) == parentTalker } ?: return cursor
        val ids = snapshot.conversationIds[group.id].orEmpty()
            .filter { it in snapshot.projectedOfficialIds && it !in excluded }
        if (ids.isEmpty()) return cursor
        val database = WeChatApis.database() ?: return cursor
        val extras = arrayListOf<Cursor>()
        return try {
            ids.distinct().chunked(400).forEach { chunk ->
                val placeholders = List(chunk.size) { "?" }.joinToString(",")
                extras += database.rawQuery(
                    "SELECT * FROM rconversation WHERE username IN ($placeholders) " +
                        "ORDER BY flag DESC, conversationTime DESC",
                    chunk.toTypedArray()
                ) ?: throw IllegalStateException("读取分组公众号会话失败")
            }
            if (snapshot.account != ConversationGroupStore.accountKey()) {
                throw IllegalStateException("查询公众号期间账号已切换")
            }
            ConversationGroupOfficialCursor.merge(cursor, extras, limit)
        } catch (error: Throwable) {
            extras.forEach { runCatching { it.close() } }
            HLog.e("$TAG 补入分组公众号会话失败", error)
            cursor
        }
    }

    private fun refreshProjectedOfficialGroups(context: Context, account: String, parentTalkers: Set<String>) {
        if (parentTalkers.isEmpty()) return
        main.post {
            if (account != ConversationGroupStore.accountKey() ||
                !ConversationGroupStore.isEnabled(context)
            ) return@post
            val adapters = synchronized(nativeGroupAdapterParents) {
                nativeGroupAdapterParents.filterValues { it in parentTalkers }.keys.toList()
            }
            adapters.forEach { adapter ->
                if (!KavaReflector.invokeSuccessfully(nativeGroupRefreshMethod, adapter)) {
                    HLog.e("$TAG 刷新公众号分组列表失败: ${adapter.javaClass.name}")
                }
            }
        }
    }

    private fun reorderNativeGroupCursor(
        context: Context,
        parentTalker: String,
        cursor: Cursor
    ): Cursor {
        val groups = ConversationGroupStore.load(context)
        val parent = groups.firstOrNull { virtualTalker(it.id) == parentTalker }
        val pinned = parent?.pinnedConversationIds.orEmpty()
        val bottom = parent?.bottomConversationIds.orEmpty()
        val conversationOrder = parent?.conversationOrderIds.orEmpty()
        if (cursor.count <= 0) return cursor
        val usernameIndex = cursor.getColumnIndex("username")
        if (usernameIndex < 0) return cursor
        val flagIndex = cursor.getColumnIndex("flag")
        val pinnedRanks = pinned.withIndex().associate { (index, talker) -> talker to index }
        val bottomRanks = bottom.withIndex().associate { (index, talker) -> talker to index }
        val customRanks = conversationOrder.withIndex().associate { (index, talker) -> talker to index }
        val columns = cursor.columnNames
        val rows = arrayListOf<Triple<Int, Int, Array<Any?>>>()
        var originalIndex = 0
        while (cursor.moveToNext()) {
            val row = Array<Any?>(columns.size) { column -> cursorValue(cursor, column) }
            val talker = row[usernameIndex]?.toString().orEmpty()
            if (flagIndex >= 0) {
                val originalFlag = (row[flagIndex] as? Number)?.toLong() ?: 0L
                row[flagIndex] = if (talker in pinnedRanks) {
                    originalFlag or CONVERSATION_TOP_FLAG
                } else {
                    originalFlag and CONVERSATION_TOP_FLAG.inv()
                }
            }
            val section = when (talker) {
                in pinnedRanks -> 0
                in bottomRanks -> 2
                else -> 1
            }
            val rank = if (customRanks.isNotEmpty()) {
                customRanks[talker] ?: customRanks.size + originalIndex
            } else {
                when (section) {
                    0 -> pinnedRanks[talker] ?: originalIndex
                    2 -> bottomRanks[talker] ?: originalIndex
                    else -> originalIndex
                }
            }
            rows += Triple(section, rank, row)
            originalIndex++
        }
        runCatching { cursor.close() }
        val orderedRows = if (customRanks.isEmpty()) {
            rows.sortedWith(
                compareBy<Triple<Int, Int, Array<Any?>>> { it.first }.thenBy { it.second }
            )
        } else {
            (0..2).flatMap { section ->
                val sectionRows = rows.filter { it.first == section }
                val orderedConversations = sectionRows
                    .filterNot { row ->
                        isVirtualTalker(row.third[usernameIndex]?.toString())
                    }
                    .sortedBy { it.second }
                    .iterator()
                sectionRows.map { row ->
                    if (isVirtualTalker(row.third[usernameIndex]?.toString())) {
                        row
                    } else {
                        orderedConversations.next()
                    }
                }
            }
        }
        val reordered = MatrixCursor(columns, rows.size).apply {
            orderedRows.forEach { addRow(it.third) }
        }
        val childGroups = parent?.let { target -> groups.filter { it.parentId == target.id } }.orEmpty()
        return reorderVirtualGroupRows(reordered, childGroups)
    }

    private fun reorderVirtualGroupRows(
        cursor: Cursor,
        siblings: List<ConversationGroup>
    ): Cursor {
        if (cursor.count <= 1 || siblings.size <= 1) return cursor
        val ranks = siblings
            .sortedWith(compareByDescending<ConversationGroup> { it.pinned }.thenBy { it.order })
            .mapIndexed { index, group -> virtualTalker(group.id) to index }
            .toMap()
        val usernameIndex = cursor.getColumnIndex("username")
        if (usernameIndex < 0) return cursor
        val columns = cursor.columnNames
        val rows = arrayListOf<Array<Any?>>()
        val groupPositions = arrayListOf<Int>()
        while (cursor.moveToNext()) {
            val row = Array<Any?>(columns.size) { column -> cursorValue(cursor, column) }
            val talker = row[usernameIndex]?.toString().orEmpty()
            if (talker in ranks) groupPositions += rows.size
            rows += row
        }
        if (groupPositions.size <= 1) {
            cursor.moveToPosition(-1)
            return cursor
        }
        val orderedGroups = groupPositions
            .map(rows::get)
            .sortedBy { row -> ranks[row[usernameIndex]?.toString().orEmpty()] ?: Int.MAX_VALUE }
        groupPositions.forEachIndexed { index, position -> rows[position] = orderedGroups[index] }
        runCatching { cursor.close() }
        return MatrixCursor(columns, rows.size).apply { rows.forEach { addRow(it) } }
    }

    private fun flattenShareRecentCursor(
        rootCursor: Cursor,
        expandedCursor: Cursor?,
        groupedIds: Set<String>
    ): Cursor {
        val usernameIndex = rootCursor.getColumnIndex("username")
        if (usernameIndex < 0) {
            expandedCursor?.let { runCatching { it.close() } }
            return rootCursor
        }
        val rootTalkers = linkedSetOf<String>()
        while (rootCursor.moveToNext()) {
            rootCursor.getString(usernameIndex)
                ?.takeIf { it.isNotBlank() && !isVirtualTalker(it) }
                ?.let(rootTalkers::add)
        }
        val source = expandedCursor ?: rootCursor
        source.moveToPosition(-1)
        val sourceUsernameIndex = source.getColumnIndex("username")
        if (sourceUsernameIndex < 0) {
            rootCursor.moveToPosition(-1)
            expandedCursor?.let { runCatching { it.close() } }
            return rootCursor
        }
        val allowedTalkers = rootTalkers + groupedIds
        val columns = source.columnNames
        val rows = arrayListOf<Array<Any?>>()
        while (source.moveToNext()) {
            val talker = source.getString(sourceUsernameIndex).orEmpty()
            if (!isVirtualTalker(talker) && talker in allowedTalkers) {
                rows += Array(columns.size) { column -> cursorValue(source, column) }
            }
        }
        if (source !== rootCursor) runCatching { source.close() }
        runCatching { rootCursor.close() }
        return MatrixCursor(columns, rows.size).apply { rows.forEach { addRow(it) } }
    }

    private fun shareGroupedConversationIds(context: Context): Set<String> {
        val groups = ConversationGroupStore.load(context)
        val effective = effectiveConversationIdsForPicker(groups)
        val configured = groups.asSequence()
            .flatMap { group -> (effective[group.id] ?: group.conversationIds).asSequence() }
            .filter { it.isNotBlank() && !isVirtualTalker(it) }
            .toSet()
        val persisted = WeChatApis.database()?.query(
            "SELECT username FROM rconversation WHERE parentRef LIKE ?",
            arrayOf("$VIRTUAL_PREFIX%")
        ).orEmpty().asSequence()
            .map { row -> value(row, "username") }
            .filter { it.isNotBlank() && !isVirtualTalker(it) }
            .toSet()
        return persisted + configured
    }

    private fun expandShareRecentQuery(
        method: Method,
        param: XC_MethodHook.MethodHookParam,
        parentArgumentIndex: Int,
        groupedIds: Set<String>,
        candidateListArgumentIndex: Int? = null
    ): Cursor? {
        if (groupedIds.isEmpty()) return null
        val args = param.args?.copyOf() ?: return null
        if (parentArgumentIndex !in args.indices || args[parentArgumentIndex] != null) return null
        if (candidateListArgumentIndex != null) {
            val candidates = args.getOrNull(candidateListArgumentIndex) as? List<*> ?: return null
            args[candidateListArgumentIndex] = candidates
                .asSequence()
                .filterIsInstance<String>()
                .plus(groupedIds.asSequence())
                .distinct()
                .toList()
        }
        args[parentArgumentIndex] = ""
        expandingShareRecentQuery.set(true)
        return try {
            KavaReflector.invokeOrThrow(method, param.thisObject, *args) as? Cursor
        } catch (throwable: Throwable) {
            logShareRecentExpandFailure(throwable)
            null
        } finally {
            expandingShareRecentQuery.remove()
        }
    }

    private fun enterShareRecentQuery() {
        shareRecentQueryDepth.set((shareRecentQueryDepth.get() ?: 0) + 1)
    }

    private fun leaveShareRecentQuery() {
        val depth = shareRecentQueryDepth.get() ?: return
        if (depth <= 1) shareRecentQueryDepth.remove() else shareRecentQueryDepth.set(depth - 1)
    }

    private fun isShareRecentQuery(): Boolean = (shareRecentQueryDepth.get() ?: 0) > 0

    private fun isExpandingShareRecentQuery(): Boolean = expandingShareRecentQuery.get() == true

    private fun logShareRecentExpandFailure(throwable: Throwable) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = shareRecentErrorLogAt.get()
            if (previous != 0L && now - previous < SHARE_RECENT_ERROR_LOG_COOLDOWN_MS) return
            if (shareRecentErrorLogAt.compareAndSet(previous, now)) break
        }
        HLog.e("$TAG 展开分享最近会话查询失败: ${throwable.message}", throwable)
    }

    private fun cursorValue(cursor: Cursor, column: Int): Any? {
        return when (cursor.getType(column)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
            Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
            else -> cursor.getString(column)
        }
    }

    private fun updateNativeGroupTitle(fragment: Any?, context: Context) {
        val target = fragment ?: return
        val talker = nativeGroupTalker(target) ?: return
        val groupName = ConversationGroupStore.load(context)
            .firstOrNull { virtualTalker(it.id) == talker }
            ?.name ?: return
        setNativeGroupTitle(target, talker, groupName, KavaReflector.readField(target, "adapter"))
    }

    private fun setNativeGroupTitle(target: Any, talker: String, groupName: String, adapter: Any?) {
        val count = (adapter as? Adapter)?.count
        val title = count?.let { "$groupName ($it)" } ?: groupName
        val titleMethod = KavaReflector.findMethodRecursive(
            target.javaClass,
            "setMMTitle",
            String::class.java
        )
        if (!KavaReflector.invokeSuccessfully(titleMethod, target, title)) {
            HLog.e("$TAG 设置微信原生分组页标题失败: talker=$talker title=$title")
        }
    }

    private fun updateNativeGroupUnread(fragment: Any?) {
        val target = fragment ?: return
        val talker = nativeGroupTalker(target) ?: return
        val unread = WeChatApis.database()?.queryFirstString(
            "SELECT IFNULL(unReadCount,0) AS unReadCount FROM rconversation " +
                "WHERE username=? LIMIT 1",
            arrayOf(talker),
            "unReadCount"
        )?.toIntOrNull()?.coerceAtLeast(0) ?: return
        val method = KavaReflector.findCompatibleMethod(
            target.javaClass,
            "setUnread",
            unread,
            true
        )
        if (!KavaReflector.invokeSuccessfully(method, target, unread, true)) {
            HLog.e("$TAG 同步微信原生分组页未读数失败: talker=$talker unread=$unread")
        }
    }

    private fun isVirtualGroupFragment(fragment: Any?): Boolean {
        return nativeGroupTalker(fragment) != null
    }

    private fun nativeGroupTalker(fragment: Any?): String? {
        if (fragment == null) return null
        val getterValue = KavaReflector.invokeMethod(fragment, "getUserName") as? String
        if (isVirtualTalker(getterValue)) return getterValue
        val fieldValue = KavaReflector.readField(fragment, "superUsername") as? String
        return fieldValue?.takeIf(::isVirtualTalker)
    }

    private fun openNativeGroupConversation(fragment: Any, talker: String): Boolean {
        val ui = KavaReflector.readField(fragment, "ui") ?: return false
        val bundle = Bundle().apply { putBoolean("finish_direct", false) }
        val method = KavaReflector.findCompatibleMethod(
            ui.javaClass,
            "startChatting",
            talker,
            bundle,
            true
        )
        return KavaReflector.invokeSuccessfully(method, ui, talker, bundle, true)
    }

    private fun nativeGroupFragmentFromListener(listener: Any?): Any? {
        if (listener == null) return null
        var current: Class<*>? = listener.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).forEach { field ->
                if (field.type.name == NATIVE_GROUP_FRAGMENT) {
                    return KavaReflector.readField(field, listener)
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun snapshot(
        groups: List<ConversationGroup>,
        groupId: String,
        records: Map<String, ConversationRecord>,
        effectiveConversationIds: Map<String, List<String>>
    ): GroupSnapshot {
        val ids = descendantConversationIds(groups, groupId, effectiveConversationIds)
        val groupRecords = ids.mapNotNull(records::get)
        return GroupSnapshot(
            totalConversations = groupRecords.size,
            unreadCount = groupRecords.sumOf { it.unreadCount.coerceAtLeast(0).toLong() }
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt(),
            mutedUnreadCount = groupRecords.sumOf {
                if (it.wechatMuted) it.unreadCount.coerceAtLeast(0).toLong() else 0L
            }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            latest = groupRecords.maxByOrNull { it.conversationTime }
        )
    }

    internal fun descendantConversationIds(
        groups: List<ConversationGroup>,
        rootId: String,
        effectiveConversationIds: Map<String, List<String>> = emptyMap()
    ): Set<String> {
        val byParent = groups.groupBy { it.parentId }
        val byId = groups.associateBy { it.id }
        val result = linkedSetOf<String>()
        val visited = hashSetOf<String>()
        fun collect(id: String) {
            if (!visited.add(id)) return
            val group = byId[id]
            (effectiveConversationIds[id] ?: group?.conversationIds).orEmpty()
                .forEach { if (it.isNotBlank()) result.add(it) }
            byParent[id].orEmpty().forEach { collect(it.id) }
        }
        collect(rootId)
        return result
    }

    private fun loadRecords(ids: Collection<String>): Map<String, ConversationRecord> {
        if (ids.isEmpty()) return emptyMap()
        val database = WeChatApis.database() ?: throw IllegalStateException("消息数据库不可用，保留现有归属")
        val conversationApi = WeChatApis.conversations()
        val result = linkedMapOf<String, ConversationRecord>()
        ids.filter { it.isNotBlank() }.distinct().chunked(400).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            val cursor = database.rawQuery(
                "SELECT username,unReadCount,unReadMuteCount,status,isSend,conversationTime,content,msgType,flag,digest,digestUser " +
                    "FROM rconversation WHERE username IN ($placeholders)",
                chunk.toTypedArray()
            ) ?: throw IllegalStateException("读取分组会话失败，保留现有归属")
            try {
                while (cursor.moveToNext()) {
                    val row = cursor.columnNames.mapIndexedNotNull { index, column ->
                        cursorValue(cursor, index)?.let { column to it }
                    }.toMap()
                    val username = value(row, "username")
                    if (username.isBlank()) continue
                    val unreadCount = intValue(row, "unReadCount").coerceAtLeast(0)
                    val unreadMuteCount = intValue(row, "unReadMuteCount").coerceAtLeast(0)
                    val totalUnreadCount = maxOf(unreadCount, unreadMuteCount)
                    val wechatMuted = if (totalUnreadCount > 0) {
                        runCatching { conversationApi?.getWechatDoNotDisturbState(username) }
                            .getOrNull() ?: (unreadMuteCount > 0)
                    } else {
                        false
                    }
                    val record = ConversationRecord(
                        username = username,
                        unreadCount = totalUnreadCount,
                        wechatMuted = wechatMuted,
                        status = intValue(row, "status"),
                        isSend = intValue(row, "isSend"),
                        conversationTime = longValue(row, "conversationTime"),
                        content = value(row, "content"),
                        messageType = intValue(row, "msgType"),
                        flag = longValue(row, "flag"),
                        digest = value(row, "digest"),
                        digestUser = value(row, "digestUser")
                    )
                    result[record.username] = record
                }
            } finally {
                cursor.close()
            }
        }
        return result
    }

    private data class ConversationParents(
        val parents: Map<String, String>,
        val verifyFlags: Map<String, Int>
    ) {
        fun officialIds(originals: Map<String, String>): Set<String> = parents.keys.filterTo(linkedSetOf()) { talker ->
            ConversationGroupParentPolicy.isOfficial(talker, verifyFlags[talker] ?: 0, parents[talker]) ||
                ConversationGroupParentPolicy.isOfficial(talker, 0, originals[talker])
        }
    }

    private fun loadConversationParents(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        assignedTalkers: Set<String>
    ): ConversationParents {
        val parents = linkedMapOf<String, String>()
        val verifyFlags = linkedMapOf<String, Int>()
        fun read(where: String, args: Array<String>) {
            val cursor = database.rawQuery(
                "SELECT c.username,c.parentRef,r.verifyFlag FROM rconversation c " +
                    "LEFT JOIN rcontact r ON r.username=c.username WHERE $where",
                args
            ) ?: throw IllegalStateException("读取真实会话父级失败，停止分组写入")
            try {
                val username = cursor.getColumnIndexOrThrow("username")
                val parent = cursor.getColumnIndexOrThrow("parentRef")
                val verified = cursor.getColumnIndexOrThrow("verifyFlag")
                while (cursor.moveToNext()) {
                    val talker = cursor.getString(username).orEmpty()
                    if (talker.isBlank() || isVirtualTalker(talker)) continue
                    parents[talker] = cursor.getString(parent).orEmpty()
                    verifyFlags[talker] = if (cursor.isNull(verified)) 0 else cursor.getInt(verified)
                }
            } finally {
                cursor.close()
            }
        }
        read("c.parentRef LIKE ? OR c.parentRef LIKE ?", arrayOf("$PARENT_PREFIX%", "$VIRTUAL_PREFIX%"))
        assignedTalkers.chunked(400).forEach { ids ->
            read("c.username IN (${List(ids.size) { "?" }.joinToString(",")})", ids.toTypedArray())
        }
        return ConversationParents(parents, verifyFlags)
    }

    /** Reads native homepage aggregate membership without changing any parentRef. */
    private fun loadNativeHomeMembers(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi
    ): Map<String, Set<String>>? {
        val nativeParents = ConversationGroupHomeVisibility.nativeParents.toList()
        return runCatching {
            val cursor = database.rawQuery(
                "SELECT username,parentRef FROM rconversation WHERE parentRef IN (?,?,?)",
                nativeParents.toTypedArray()
            ) ?: throw IllegalStateException("读取原生公众号汇总成员失败")
            try {
                val username = cursor.getColumnIndexOrThrow("username")
                val parent = cursor.getColumnIndexOrThrow("parentRef")
                val result = linkedMapOf<String, MutableSet<String>>()
                while (cursor.moveToNext()) {
                    val talker = cursor.getString(username).orEmpty()
                    val parentRef = cursor.getString(parent).orEmpty()
                    if (talker.isNotBlank() && parentRef in ConversationGroupHomeVisibility.nativeParents) {
                        result.getOrPut(parentRef) { linkedSetOf() }.add(talker)
                    }
                }
                result.mapValues { it.value.toSet() }
            } finally {
                cursor.close()
            }
        }.onFailure {
            HLog.e("$TAG 读取原生公众号汇总成员失败", it)
        }.getOrNull()
    }

    private fun applyParentPlan(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        prefs: SharedPreferences,
        account: String,
        plan: ConversationGroupParentPolicy.Plan
    ): Set<String> {
        check(account == ConversationGroupStore.accountKey()) { "账号已切换，停止分组写入" }
        plan.unresolved.forEach { talker ->
            if (rememberParentRestoreFailure("$account|$talker")) {
                HLog.e("$TAG 缺少可信原始父级，保留当前会话归属: talker=$talker")
            }
        }
        // Recovery information must reach disk before any native parent mutation.
        saveOriginalParentRefs(prefs, account, plan.originalParents, forceWrite = plan.updates.isNotEmpty())
        val successful = linkedSetOf<String>()
        plan.updates.entries.groupBy({ it.value }, { it.key }).forEach { (parent, talkers) ->
            talkers.chunked(200).forEach { chunk ->
                check(account == ConversationGroupStore.accountKey()) { "账号已切换，停止分组写入" }
                if (updateParentRefs(database, chunk, parent)) successful.addAll(chunk)
            }
        }
        val remaining = plan.originalParents - successful.intersect(plan.restoring)
        saveOriginalParentRefs(prefs, account, remaining)
        return successful
    }

    private fun rememberParentRestoreFailure(key: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(parentRestoreFailures) {
            val previous = parentRestoreFailures[key]
            if (previous != null && now - previous < PARENT_FAILURE_HISTORY_TTL_MS) return false
            parentRestoreFailures.remove(key)
            parentRestoreFailures[key] = now
            while (parentRestoreFailures.size > PARENT_FAILURE_HISTORY_MAX) {
                parentRestoreFailures.entries.iterator().let { iterator ->
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            return true
        }
    }

    private fun syncDatabase(context: Context, generation: Long) {
        if (!isRuntimeGenerationActive(generation)) return
        val account = ConversationGroupStore.accountKey()
        if (account.isBlank()) return
        val database = WeChatApis.database() ?: return
        val prefs = HchatStorage.preferences(context, ConversationGroupStore.PREFS_NAME)
        val originals = loadOriginalParentRefs(prefs, account)
        var groups = ConversationGroupStore.load(context)
        val enabled = ConversationGroupStore.isEnabled(context)
        if (!enabled) {
            synchronized(lifecycleLock) {
                if (!isRuntimeGenerationActive(generation)) return
                effectiveConversationSnapshot = null
            }
            ConversationGroupHomeProjection.refresh()
            val state = loadConversationParents(database, emptySet())
            val official = state.officialIds(originals)
            val plan = ConversationGroupParentPolicy.plan(
                assigned = state.parents.filterKeys { it !in official },
                current = state.parents,
                protected = official,
                originals = originals
            )
            applyParentPlan(database, prefs, account, plan)
            return
        }
        val automaticResolution = if (enabled) {
            ConversationGroupAutomaticResolver.resolveForSync(context, groups)
        } else null
        if (!isRuntimeGenerationActive(generation)) return
        val effectiveConversationIds = automaticResolution?.conversationIds
            ?: groups.associate { it.id to it.conversationIds }
        if (enabled && groups.any { it.conversationOrderIds.isNotEmpty() }) {
            groups = ConversationGroupStore.appendMissingConversationOrders(
                context,
                effectiveConversationIds
            )
        }
        val records = if (enabled) loadRecords(effectiveConversationIds.values.flatten()) else emptyMap()
        val activeGroups = if (enabled) {
            groups.mapNotNull { group ->
                val state = snapshot(groups, group.id, records, effectiveConversationIds)
                group.takeIf { state.totalConversations > 0 || it.showEmpty }?.let { it to state }
            }
        } else {
            emptyList()
        }
        if (!isRuntimeGenerationActive(generation)) return
        val virtualRows = ensureVirtualRows(database, activeGroups, generation)
        val insertedGroupIds = virtualRows.readyGroupIds
        val groupById = groups.associateBy { it.id }
        val readyGroupIds = insertedGroupIds.filterTo(linkedSetOf()) { id ->
            var current = groupById[id]
            val visited = hashSetOf<String>()
            while (true) {
                val node = current ?: break
                val parentId = node.parentId ?: break
                if (!visited.add(node.id)) return@filterTo false
                if (parentId !in insertedGroupIds) return@filterTo false
                current = groupById[parentId]
            }
            true
        }
        val assigned = if (enabled) {
            buildMap {
                groups.filter { it.id in readyGroupIds }.forEach { group ->
                    effectiveConversationIds[group.id].orEmpty().forEach { talker ->
                        if (talker.isNotBlank() && records.containsKey(talker)) {
                            put(talker, group.id)
                        }
                    }
                }
            }
        } else {
            emptyMap()
        }
        val parentState = loadConversationParents(database, assigned.keys)
        val officialIds = parentState.officialIds(originals)
        val plan = ConversationGroupParentPolicy.plan(
            assigned = assigned.mapValues { (_, groupId) -> virtualTalker(groupId) },
            current = parentState.parents,
            protected = officialIds,
            originals = originals
        )
        if (!isRuntimeGenerationActive(generation)) return
        val parentUpdatesSucceeded = applyParentPlan(database, prefs, account, plan)
        val successfullyProcessedNewGroups = automaticResolution?.newConversationGroupIds.orEmpty()
            .filterTo(linkedSetOf()) { talker ->
                talker in assigned && talker !in plan.unresolved &&
                    (talker !in plan.updates || talker in parentUpdatesSucceeded)
            }
        if (!isRuntimeGenerationActive(generation) || ConversationGroupStore.accountKey() != account) return
        val projectedOfficialIds = officialIds.intersect(assigned.keys)
        val nativeHomeMembers = if (projectedOfficialIds.isNotEmpty()) {
            loadNativeHomeMembers(database)
        } else {
            emptyMap()
        }
        val hiddenHomeIds = if (nativeHomeMembers != null) {
            ConversationGroupHomeVisibility.hidden(projectedOfficialIds, nativeHomeMembers)
        } else {
            // A failed native query must not hide an aggregate row as if it were empty.
            projectedOfficialIds
        }
        val nextSnapshot = EffectiveConversationSnapshot(
            account, groups, effectiveConversationIds, projectedOfficialIds, hiddenHomeIds
        )
        val previousSnapshot = publishConversationSnapshot(generation, nextSnapshot) ?: return
        val oldSnapshot = previousSnapshot.value
        val officialProjectionChanged = oldSnapshot == null || oldSnapshot.account != account ||
            oldSnapshot.projectedOfficialIds != nextSnapshot.projectedOfficialIds ||
            (nextSnapshot.projectedOfficialIds.isNotEmpty() &&
                oldSnapshot.conversationIds != nextSnapshot.conversationIds)
        val homeProjectionChanged = oldSnapshot?.hiddenHomeIds != nextSnapshot.hiddenHomeIds
        notifyVirtualGroupRows(
            database,
            activeGroups.filter {
                it.first.id in readyGroupIds &&
                    (officialProjectionChanged || it.first.id in virtualRows.changedGroupIds)
            }
        )
        if (officialProjectionChanged || homeProjectionChanged) {
            ConversationGroupHomeProjection.refresh()
        }
        // Never remove a parent row while a real conversation still needs recovery.
        if (plan.unresolved.isEmpty() && parentUpdatesSucceeded.containsAll(plan.updates.keys)) {
            cleanupVirtualRows(
                database,
                activeGroups.filter { it.first.id in readyGroupIds }
                    .map { virtualTalker(it.first.id) }
                    .toSet()
            )
        }
        val refreshParents = listOfNotNull(oldSnapshot?.takeIf { it.account == account }, nextSnapshot)
            .flatMap { snapshot ->
                snapshot.groups.filter { group ->
                    snapshot.conversationIds[group.id].orEmpty().any { it in snapshot.projectedOfficialIds }
                }.map { virtualTalker(it.id) }
            }.toSet()
        refreshProjectedOfficialGroups(context, account, refreshParents)
        automaticResolution?.takeIf { it.automaticNewGroupsEnabled }
            ?.let { resolution ->
                val observed = resolution.observedConversationGroupIds ?: return@let
                when {
                    !resolution.baselineInitialized -> {
                        ConversationGroupStore.saveAutomaticSeenGroupIds(context, observed)
                    }
                    successfullyProcessedNewGroups.isNotEmpty() -> {
                        val seen = ConversationGroupStore.loadAutomaticSeenGroupIds(context)
                        ConversationGroupStore.saveAutomaticSeenGroupIds(
                            context,
                            seen + successfullyProcessedNewGroups
                        )
                    }
                }
            }
    }

    private fun isRuntimeGenerationActive(generation: Long): Boolean =
        runtimeActive && lifecycleGeneration.get() == generation

    private data class PreviousSnapshot(val value: EffectiveConversationSnapshot?)

    private fun publishConversationSnapshot(
        generation: Long,
        snapshot: EffectiveConversationSnapshot
    ): PreviousSnapshot? = synchronized(lifecycleLock) {
        if (!isRuntimeGenerationActive(generation)) return@synchronized null
        PreviousSnapshot(effectiveConversationSnapshot).also { effectiveConversationSnapshot = snapshot }
    }

    private fun updateAutomaticAvatar(generation: Long, talker: String, source: String?): Boolean =
        synchronized(lifecycleLock) {
            if (!isRuntimeGenerationActive(generation)) return@synchronized false
            if (source == null) automaticAvatarSources.remove(talker) else automaticAvatarSources[talker] = source
            true
        }

    private fun ensureVirtualRows(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        groups: List<Pair<ConversationGroup, GroupSnapshot>>,
        generation: Long
    ): VirtualRowsResult {
        val ready = linkedSetOf<String>()
        val changed = linkedSetOf<String>()
        val currentTalkers = groups.mapTo(hashSetOf()) { virtualTalker(it.first.id) }
        synchronized(lifecycleLock) {
            if (!isRuntimeGenerationActive(generation)) return VirtualRowsResult(ready, changed)
            (automaticAvatarSources.keys - currentTalkers).forEach(automaticAvatarSources::remove)
        }
        groups.forEach { (group, state) ->
            if (!isRuntimeGenerationActive(generation)) return VirtualRowsResult(ready, changed)
            val talker = virtualTalker(group.id)
            var groupChanged = false
            val contactValues = ContentValues().apply {
                put("username", talker)
                put("nickname", group.name)
                put("encryptUsername", "")
                put("type", 0)
                put("verifyFlag", 0)
            }
            val contactResult = upsertRow(database, "rcontact", talker, contactValues)
            if (!contactResult.success) {
                updateAutomaticAvatar(generation, talker, null)
                return@forEach
            }
            groupChanged = contactResult.changed

            val latest = state.latest
            val automaticAvatarSource = latest
                ?.takeIf { group.previewLatestMessage }
                ?.username
                ?.takeIf(String::isNotBlank)
            val previousAvatarSource = automaticAvatarSources[talker]
            if (!updateAutomaticAvatar(generation, talker, automaticAvatarSource)) {
                return VirtualRowsResult(ready, changed)
            }
            if (previousAvatarSource != automaticAvatarSource) groupChanged = true
            val latestAvatar = latest?.takeIf { group.previewLatestMessage }?.let { record ->
                runCatching { WeChatApis.contact().contacts()?.getContact(record.username) }
                    .getOrNull()
            }
            if (latestAvatar != null &&
                (latestAvatar.avatarUrl.isNotBlank() || latestAvatar.avatarBackupUrl.isNotBlank())
            ) {
                val avatarResult = upsertRow(
                    database,
                    "img_flag",
                    talker,
                    ContentValues().apply {
                        put("username", talker)
                        put("reserved1", latestAvatar.avatarUrl)
                        put("reserved2", latestAvatar.avatarBackupUrl)
                    }
                )
                if (avatarResult.changed) groupChanged = true
            } else {
                val avatarExists = database.queryFirstString(
                    "SELECT username FROM img_flag WHERE username=? LIMIT 1",
                    arrayOf(talker),
                    "username"
                ) == talker
                if (avatarExists && database.delete("img_flag", "username=?", arrayOf(talker)) > 0) {
                    groupChanged = true
                }
            }
            val displayedUnread = when (group.unreadCountMode) {
                ConversationGroupUnreadMode.ALL -> state.unreadCount
                ConversationGroupUnreadMode.EXCLUDE_MUTED ->
                    (state.unreadCount - state.mutedUnreadCount).coerceAtLeast(0)
                ConversationGroupUnreadMode.HIDDEN -> 0
            }
            val fallbackPreview = groupSummary(state.totalConversations, displayedUnread)
            val hasNativePreview = group.previewLatestMessage && latest?.let {
                it.content.isNotBlank() || it.digest.isNotBlank()
            } == true
            val aggregateDigest = latest?.takeIf { hasNativePreview }
                ?.let(::aggregateConversationDigest)
            val conversationValues = ContentValues().apply {
                put("username", talker)
                put("parentRef", group.parentId?.let(::virtualTalker).orEmpty())
                put("unReadCount", displayedUnread)
                put("unReadMuteCount", 0)
                put("status", latest?.status ?: 0)
                put("isSend", latest?.isSend ?: 0)
                put("conversationTime", latest?.conversationTime ?: 0L)
                put(
                    "content",
                    if (group.previewLatestMessage) {
                        latest?.content.orEmpty().ifBlank {
                            if (hasNativePreview) "" else fallbackPreview
                        }
                    } else {
                        fallbackPreview
                    }
                )
                put("msgType", if (group.previewLatestMessage) latest?.messageType ?: 0 else 1)
                put(
                    "flag",
                    if (group.pinned) Long.MAX_VALUE - group.order.coerceAtLeast(0)
                    else latest?.conversationTime ?: 0L
                )
                put(
                    "digest",
                    if (group.previewLatestMessage) {
                        aggregateDigest ?: fallbackPreview
                    } else {
                        fallbackPreview
                    }
                )
                put(
                    "digestUser",
                    if (group.previewLatestMessage && aggregateDigest != null) {
                        latest?.username.orEmpty()
                    } else {
                        ""
                    }
                )
                put("hasTrunc", 0)
            }
            val conversationResult = upsertRow(database, "rconversation", talker, conversationValues)
            if (conversationResult.success) {
                ready.add(group.id)
                if (conversationResult.changed) groupChanged = true
            }
            if (groupChanged) changed.add(group.id)
        }
        return VirtualRowsResult(
            readyGroupIds = ready,
            changedGroupIds = changed
        )
    }

    private fun aggregateConversationDigest(record: ConversationRecord): String {
        val raw = record.digest.ifBlank {
            when (record.messageType) {
                1, 10000 -> record.content
                3 -> "[图片]"
                34 -> "[语音]"
                43, 62 -> "[视频]"
                47 -> "[动画表情]"
                48 -> "[位置]"
                else -> "[消息]"
            }
        }
        val resolved = if (record.digestUser.isBlank() || !raw.contains('%')) {
            raw
        } else {
            val sender = runCatching {
                WeChatApis.contact().contacts()?.getContact(record.digestUser)?.displayName()
            }.getOrNull().orEmpty().ifBlank { record.digestUser }
            runCatching { String.format(raw, sender) }.getOrElse {
                raw.replace("%1\$s", sender).replace("%s", sender)
            }
        }
        val body = resolved.trim().ifBlank { "[消息]" }.replace("%", "%%")
        return "%s: $body"
    }

    /**
     * The aggregate row is maintained by Hchat's database sync, so WeChat does not
     * automatically publish a conversation change for it. Reuse the native parent
     * update entry with the existing parent value to notify the homepage and its
     * unread badge without changing the grouping relation.
     */
    private fun notifyVirtualGroupRows(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        groups: List<Pair<ConversationGroup, GroupSnapshot>>
    ) {
        if (groups.isEmpty()) return
        val method = parentUpdateMethod ?: return
        val receiver = conversationStorage?.takeIf(method.declaringClass::isInstance)
            ?: database.storageObjectForMethod(method)
            ?: return
        groups.groupBy { (group, _) -> group.parentId?.let(::virtualTalker).orEmpty() }
            .forEach { (parentRef, states) ->
                states.map { (group, _) -> virtualTalker(group.id) }
                    .chunked(200)
                    .forEach { talkers ->
                        runCatching {
                            val usernames = talkers.toTypedArray()
                            if (method.parameterTypes.size == 2) {
                                KavaReflector.invokeOrThrow(method, receiver, usernames, parentRef)
                            } else {
                                KavaReflector.invokeOrThrow(
                                    method,
                                    receiver,
                                    usernames,
                                    parentRef,
                                    true,
                                    parentRef.isNotBlank()
                                )
                            }
                        }.onFailure {
                            HLog.e(
                                "$TAG 通知虚拟分组会话刷新失败: count=${talkers.size} " +
                                    "parent=$parentRef ${it.message}",
                                it
                            )
                        }
                    }
            }
    }

    private fun upsertRow(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        table: String,
        talker: String,
        values: ContentValues
    ): UpsertResult {
        val columns = values.keySet().toList()
        val existing = queryRow(database, table, talker, columns)
        if (existing != null && rowMatches(existing, values)) {
            return UpsertResult(success = true, changed = false)
        }
        val success = if (existing != null) {
            database.update(table, values, "username=?", arrayOf(talker)) > 0 ||
                queryRow(database, table, talker, columns)?.let { rowMatches(it, values) } == true
        } else {
            database.insert(table, "username", values) >= 0L ||
                queryRow(database, table, talker, columns)?.let { rowMatches(it, values) } == true
        }
        if (!success) HLog.e("$TAG 写入虚拟分组入口失败: table=$table talker=$talker")
        return UpsertResult(success = success, changed = success)
    }

    private fun queryRow(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        table: String,
        talker: String,
        columns: List<String>
    ): Map<String, Any>? {
        if (columns.isEmpty()) return null
        return database.query(
            "SELECT ${columns.joinToString(",")} FROM $table WHERE username=? LIMIT 1",
            arrayOf(talker)
        ).firstOrNull()
    }

    private fun rowMatches(row: Map<String, Any>, values: ContentValues): Boolean {
        return values.keySet().all { key -> databaseValueEquals(row[key], values.get(key)) }
    }

    private fun databaseValueEquals(actual: Any?, expected: Any?): Boolean {
        if (actual is ByteArray && expected is ByteArray) return actual.contentEquals(expected)
        if (actual is Number && expected is Number) {
            val floatingPoint = actual is Float || actual is Double ||
                expected is Float || expected is Double
            return if (floatingPoint) actual.toDouble() == expected.toDouble()
            else actual.toLong() == expected.toLong()
        }
        return actual == expected
    }

    private fun cleanupVirtualRows(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        desiredTalkers: Set<String>
    ) {
        val existing = linkedSetOf<String>()
        listOf("rconversation", "rcontact", "img_flag").forEach { table ->
            database.query(
                "SELECT username FROM $table WHERE username LIKE ?",
                arrayOf("$VIRTUAL_PREFIX%")
            ).forEach { row ->
                value(row, "username").takeIf(String::isNotBlank)?.let(existing::add)
            }
        }
        (existing - desiredTalkers).forEach { talker ->
            automaticAvatarSources.remove(talker)
            val deleted = runCatching {
                WeChatApis.conversations()?.deleteConversation(talker) == true
            }.getOrDefault(false)
            if (!deleted) {
                database.delete("rconversation", "username=?", arrayOf(talker))
            }
            database.delete("rcontact", "username=?", arrayOf(talker))
            database.delete("img_flag", "username=?", arrayOf(talker))
        }
    }

    private fun updateParentRefs(
        database: h.Hchat.hooks.api.runtime.WeChatDatabaseApi,
        talkers: List<String>,
        parentRef: String
    ): Boolean {
        if (talkers.isEmpty()) return true
        val method = parentUpdateMethod ?: return false
        val receiver = conversationStorage?.takeIf(method.declaringClass::isInstance)
            ?: database.storageObjectForMethod(method)
        if (receiver == null) return false
        return runCatching {
            val usernames = talkers.toTypedArray()
            if (method.parameterTypes.size == 2) {
                KavaReflector.invokeOrThrow(method, receiver, usernames, parentRef)
            } else {
                KavaReflector.invokeOrThrow(method, receiver, usernames, parentRef, true, true)
            }
            val placeholders = List(talkers.size) { "?" }.joinToString(",")
            val args = (talkers + parentRef).toTypedArray()
            val matched = database.queryFirstString(
                "SELECT COUNT(*) AS matched FROM rconversation " +
                    "WHERE username IN ($placeholders) AND IFNULL(parentRef,'')=?",
                args,
                "matched"
            ).toIntOrNull() ?: 0
            if (matched != talkers.size) {
                throw IllegalStateException("数据库仅更新 $matched/${talkers.size} 条会话")
            }
            true
        }.onFailure {
            HLog.e("$TAG 调用微信原生会话归拢失败: count=${talkers.size} ${it.message}", it)
        }.getOrDefault(false)
    }

    private fun captureConversationStorage(candidate: Any?, method: Method, context: Context, generation: Long) {
        if (candidate == null || !method.declaringClass.isInstance(candidate)) return
        synchronized(lifecycleLock) {
            if (!isRuntimeGenerationActive(generation) || conversationStorage === candidate) return
            conversationStorage = candidate
        }
        syncAsync(context)
    }

    private fun loadOriginalParentRefs(
        prefs: SharedPreferences,
        account: String
    ): Map<String, String> {
        val root = JSONObject(prefs.getString(KEY_ORIGINAL_PARENT_REFS, "{}").orEmpty().ifBlank { "{}" })
        if (!root.has(account)) return emptyMap()
        val accountObject = root.getJSONObject(account)
        return buildMap {
            val keys = accountObject.keys()
            while (keys.hasNext()) {
                val talker = keys.next()
                val parent = accountObject.get(talker)
                require(parent is String) { "原始会话父级格式异常: account=$account talker=$talker" }
                if (talker.isNotBlank()) put(talker, parent)
            }
        }
    }

    private fun saveOriginalParentRefs(
        prefs: SharedPreferences,
        account: String,
        values: Map<String, String>,
        forceWrite: Boolean = false
    ) {
        val root = JSONObject(prefs.getString(KEY_ORIGINAL_PARENT_REFS, "{}").orEmpty().ifBlank { "{}" })
        if (values.isEmpty()) {
            root.remove(account)
        } else {
            val accountObject = JSONObject()
            values.forEach { (talker, parentRef) -> accountObject.put(talker, parentRef) }
            root.put(account, accountObject)
        }
        val updated = root.toString()
        if (!forceWrite && updated == prefs.getString(KEY_ORIGINAL_PARENT_REFS, "{}")) return
        check(prefs.edit().putString(KEY_ORIGINAL_PARENT_REFS, updated).commit()) {
            "保存原始 parentRef 失败: account=$account"
        }
    }

    private fun locateQueryMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_QUERY, ::isQueryMethod) ?: locateAndCache(
            context,
            CACHE_QUERY,
            FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass("com.tencent.mm.storage.", StringMatchType.Contains, false)
                    usingStrings(listOf(QUERY_ANCHOR, "parentRef is null", "message_fold"))
                })
            },
            ::isQueryMethod
        )
    }

    private fun locateShareRecentAdapterResetMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_SHARE_RECENT_ADAPTER_RESET, ::isShareRecentAdapterResetMethod)
            ?: locateAndCache(
                context,
                CACHE_SHARE_RECENT_ADAPTER_RESET,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass(
                            "com.tencent.mm.ui.contact.",
                            StringMatchType.StartsWith,
                            false
                        )
                        usingStrings(
                            listOf(
                                SHARE_RECENT_ADAPTER_TAG,
                                "resetData",
                                SHARE_RECENT_RESET_ANCHOR
                            )
                        )
                    })
                },
                ::isShareRecentAdapterResetMethod
            )
    }

    private fun locateShareRecentForwardQueryMethod(context: FeatureContext): Method? {
        return loadCached(
            context,
            CACHE_SHARE_RECENT_FORWARD_QUERY,
            ::isShareRecentForwardQueryMethod
        ) ?: locateAndCache(
            context,
            CACHE_SHARE_RECENT_FORWARD_QUERY,
            FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass("com.tencent.mm.storage.", StringMatchType.StartsWith, false)
                    usingStrings(
                        listOf(
                            QUERY_ANCHOR,
                            SHARE_RECENT_FORWARD_ORDER_ANCHOR,
                            "conversationboxservice"
                        )
                    )
                })
            },
            ::isShareRecentForwardQueryMethod
        )
    }

    private fun locateParentUpdateMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_PARENT_UPDATE, ::isParentUpdateMethod) ?: locateAndCache(
            context,
            CACHE_PARENT_UPDATE,
            FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass("com.tencent.mm.storage.", StringMatchType.Contains, false)
                    usingStrings(
                        listOf(
                            "Update rconversation set parentRef = '",
                            "' where 1 != 1 "
                        )
                    )
                })
            },
            ::isParentUpdateMethod
        )
    }

    private fun locateNativeSetPinnedMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_SET_PINNED, ::isNativePinnedUpdateMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_SET_PINNED,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass("com.tencent.mm.storage.", StringMatchType.Contains, false)
                        usingEqStrings("[setPlacedTop] flag=%s result=%s")
                    })
                },
                ::isNativePinnedUpdateMethod
            )
    }

    private fun locateNativeUnsetPinnedMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_UNSET_PINNED, ::isNativePinnedUpdateMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_UNSET_PINNED,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass("com.tencent.mm.storage.", StringMatchType.Contains, false)
                        usingEqStrings("unSetPlacedTop conversation failed")
                    })
                },
                ::isNativePinnedUpdateMethod
            )
    }

    private fun locateClickMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_CLICK, ::isClickMethod) ?: locateAndCache(
            context,
            CACHE_CLICK,
            FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass("com.tencent.mm.ui.conversation.", StringMatchType.Contains, false)
                    usingStrings(listOf(CLICK_TAG, CLICK_ANCHOR))
                })
            },
            ::isClickMethod
        )
    }

    private fun locateNativeGroupQueryMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_GROUP_QUERY, ::isNativeGroupQueryMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_GROUP_QUERY,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass("com.tencent.mm.storage.", StringMatchType.Contains, false)
                        usingStrings(listOf(NATIVE_GROUP_QUERY_ANCHOR, NATIVE_GROUP_PARENT_ANCHOR))
                    })
                },
                ::isNativeGroupQueryMethod
            )
    }

    private fun locateNativeGroupClickMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_GROUP_CLICK, ::isClickMethod) ?: locateAndCache(
            context,
            CACHE_NATIVE_GROUP_CLICK,
            FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass("com.tencent.mm.ui.conversation.", StringMatchType.Contains, false)
                    usingStrings(
                        listOf(
                            NATIVE_GROUP_TAG,
                            "user should not be null. position:%d, size:%d",
                            "specific_chat_from_scene",
                            "chat_from_scene_for_group_chats"
                        )
                    )
                })
            },
            ::isClickMethod
        )
    }

    private fun locateNativeGroupMenuMethods(context: FeatureContext): NativeGroupMenuMethods? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cachedCreate = DexMethodCache.load(
            prefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_NATIVE_GROUP_MENU_CREATE
        )
        val cachedClick = DexMethodCache.load(
            prefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_NATIVE_GROUP_MENU_CLICK
        )
        if (cachedCreate != null && cachedClick != null &&
            nativeGroupMenuPairOwner(cachedCreate, cachedClick) != null
        ) {
            return NativeGroupMenuMethods(cachedCreate, cachedClick)
        }
        val methods = runCatching {
            val createCandidates = context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass(
                            "com.tencent.mm.ui.conversation.",
                            StringMatchType.StartsWith,
                            false
                        )
                        name("onCreateContextMenu")
                        returnType("void")
                        paramTypes(
                            "android.view.ContextMenu",
                            "android.view.View",
                            "android.view.ContextMenu\$ContextMenuInfo"
                        )
                    })
                }
            ).mapNotNull {
                runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isNativeGroupMenuCreateMethod)
            val clickCandidates = context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass(
                            "com.tencent.mm.ui.conversation.",
                            StringMatchType.StartsWith,
                            false
                        )
                        name("onMMMenuItemSelected")
                        returnType("void")
                        paramTypes("android.view.MenuItem", "int")
                    })
                }
            ).mapNotNull {
                runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isNativeGroupMenuClickMethod)
            createCandidates.flatMap { create ->
                clickCandidates.mapNotNull { click ->
                    nativeGroupMenuPairOwner(create, click)
                        ?.let { NativeGroupMenuMethods(create, click) }
                }
            }.distinctBy { it.create.toGenericString() + it.click.toGenericString() }
                .singleOrNull()
        }.onFailure {
            HLog.e("$TAG 定位微信原生分组长按菜单失败: ${it.message}", it)
        }.getOrNull()
        if (methods == null) {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_NATIVE_GROUP_MENU_CREATE)
            DexMethodCache.clear(prefs, runtimeKey, CACHE_NATIVE_GROUP_MENU_CLICK)
            HLog.e("$TAG 微信原生分组长按菜单缺失或候选不唯一")
        } else {
            DexMethodCache.save(prefs, runtimeKey, CACHE_NATIVE_GROUP_MENU_CREATE, methods.create)
            DexMethodCache.save(prefs, runtimeKey, CACHE_NATIVE_GROUP_MENU_CLICK, methods.click)
        }
        return methods
    }

    private fun locateNativeGroupRefreshMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_GROUP_REFRESH, ::isNativeGroupRefreshMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_GROUP_REFRESH,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass("com.tencent.mm.ui.conversation.", StringMatchType.Contains, false)
                        usingEqStrings(NATIVE_GROUP_REFRESH_ANCHOR)
                    })
                },
                ::isNativeGroupRefreshMethod
            )
    }

    private fun locateNativeGroupMarkReadMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_GROUP_MARK_READ, ::isNativeGroupMarkReadMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_GROUP_MARK_READ,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass("com.tencent.mm.storage.", StringMatchType.Contains, false)
                        usingStrings(
                            listOf("update conversation failed", NATIVE_GROUP_MARK_READ_ANCHOR)
                        )
                    })
                },
                ::isNativeGroupMarkReadMethod
            )
    }

    private fun locateNativeGroupStatusNotifyMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_GROUP_STATUS_NOTIFY, ::isNativeGroupStatusNotifyMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_GROUP_STATUS_NOTIFY,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingEqStrings(NATIVE_GROUP_STATUS_ANCHOR)
                    })
                },
                ::isNativeGroupStatusNotifyMethod
            )
    }

    private fun locateNativeHideConversationMethod(context: FeatureContext): Method? {
        return loadCached(context, CACHE_NATIVE_HIDE_CONVERSATION, ::isNativeHideConversationMethod)
            ?: locateAndCache(
                context,
                CACHE_NATIVE_HIDE_CONVERSATION,
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingEqStrings(NATIVE_HIDDEN_PARENT)
                    })
                },
                ::isNativeHideConversationMethod
            )
    }

    private fun loadCached(context: FeatureContext, key: String, predicate: (Method) -> Boolean): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        return DexMethodCache.load(prefs, runtimeKey, context.hostClassLoader(), key)?.takeIf(predicate)
    }

    private fun locateAndCache(
        context: FeatureContext,
        key: String,
        query: FindMethod,
        predicate: (Method) -> Boolean
    ): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val method = runCatching {
            context.dexKitBridge().findMethod(query).asSequence()
                .mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .firstOrNull(predicate)
        }.onFailure {
            HLog.e("$TAG 定位微信会话入口失败 key=$key: ${it.message}", it)
        }.getOrNull()
        if (method != null) {
            DexMethodCache.save(prefs, runtimeKey, key, method)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, key)
        }
        return method
    }

    private fun isQueryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isAbstract(method.modifiers) &&
            Cursor::class.java.isAssignableFrom(method.returnType) &&
            types.size == 5 &&
            types[0] == Integer.TYPE &&
            List::class.java.isAssignableFrom(types[1]) &&
            types[2] == String::class.java &&
            types[3] == java.lang.Boolean.TYPE &&
            types[4] == String::class.java
    }

    private fun isShareRecentAdapterResetMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE && method.parameterTypes.isEmpty()
    }

    private fun isShareRecentForwardQueryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            Cursor::class.java.isAssignableFrom(method.returnType) && types.size == 6 &&
            List::class.java.isAssignableFrom(types[0]) && types[1] == Integer.TYPE &&
            List::class.java.isAssignableFrom(types[2]) && types[3] == String::class.java &&
            types[4] == java.lang.Boolean.TYPE && types[5] == String::class.java
    }

    private fun isParentUpdateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE && types.size in setOf(2, 4) && types[0].isArray &&
            types[0].componentType == String::class.java && types[1] == String::class.java &&
            (types.size == 2 ||
                types[2] == java.lang.Boolean.TYPE && types[3] == java.lang.Boolean.TYPE)
    }

    private fun isNativePinnedUpdateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == java.lang.Boolean.TYPE && types.size == 1 &&
            types[0] == String::class.java
    }

    private fun isNativeHideConversationMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE && types.size == 1 &&
            types[0] == String::class.java
    }

    private fun isNativeGroupQueryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            Cursor::class.java.isAssignableFrom(method.returnType) && types.size == 4 &&
            types[0] == Integer.TYPE && List::class.java.isAssignableFrom(types[1]) &&
            types[2] == String::class.java && types[3] == Integer.TYPE
    }

    private fun isNativeGroupMarkReadMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == java.lang.Boolean.TYPE && types.size == 1 &&
            types[0] == String::class.java
    }

    private fun isNativeGroupMenuCreateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE && types.size == 3 &&
            ContextMenu::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2].name == "android.view.ContextMenu\$ContextMenuInfo" &&
            nativeGroupMenuOwner(method) != null
    }

    private fun isNativeGroupMenuClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE && types.size == 2 &&
            MenuItem::class.java.isAssignableFrom(types[0]) && types[1] == Integer.TYPE
    }

    private fun nativeGroupMenuPairOwner(create: Method, click: Method): Class<*>? {
        if (!isNativeGroupMenuCreateMethod(create) || !isNativeGroupMenuClickMethod(click)) {
            return null
        }
        val owner = nativeGroupMenuOwner(create) ?: return null
        return owner.takeIf {
            KavaReflector.declaredConstructors(click.declaringClass).any { constructor ->
                owner in constructor.parameterTypes
            }
        }
    }

    private fun nativeGroupMenuOwner(create: Method): Class<*>? {
        return KavaReflector.declaredConstructors(create.declaringClass)
            .asSequence()
            .flatMap { it.parameterTypes.asSequence() }
            .distinct()
            .firstOrNull { candidate ->
                KavaReflector.declaredFields(candidate).any {
                    it.type.name == NATIVE_GROUP_FRAGMENT
                } && KavaReflector.findMethod(
                    candidate,
                    "onItemLongClick",
                    AdapterView::class.java,
                    View::class.java,
                    Integer.TYPE,
                    java.lang.Long.TYPE
                )?.let { method ->
                    method.returnType == java.lang.Boolean.TYPE &&
                        !Modifier.isAbstract(method.modifiers)
                } == true
            }
    }

    private fun isNativeGroupRefreshMethod(method: Method): Boolean {
        val types = method.parameterTypes
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers) ||
            method.returnType != Void.TYPE || types.isNotEmpty()
        ) {
            return false
        }
        return KavaReflector.declaredConstructors(method.declaringClass).any { constructor ->
            val constructorTypes = constructor.parameterTypes
            constructorTypes.size == 3 &&
                Context::class.java.isAssignableFrom(constructorTypes[0]) &&
                constructorTypes[1] == String::class.java
        }
    }

    private fun isNativeGroupStatusNotifyMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE && types.size == 2 &&
            types[0] == String::class.java && types[1] == Integer.TYPE
    }

    private fun isClickMethod(method: Method): Boolean {
        return itemClickSignature(method) && method.returnType == Void.TYPE
    }

    private fun itemClickSignature(method: Method): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            types.size == 4 && AdapterView::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) && types[2] == Integer.TYPE &&
            types[3] == java.lang.Long.TYPE
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            HLog.e("$TAG Hook 安装失败: ${method.toGenericString()} ${it.message}", it)
            false
        }
    }

    private fun virtualTalkerFromItem(item: Any): String? {
        val direct = conversationTalkerFromItem(item)?.takeIf(::isVirtualTalker)
        if (direct != null) return direct
        var current: Class<*>? = item.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).forEach { field ->
                if (field.type == String::class.java) {
                    val value = KavaReflector.readField(field, item) as? String
                    if (isVirtualTalker(value)) return value
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun conversationTalkerFromItem(item: Any): String? {
        return sequenceOf("field_username", "username", "userName")
            .mapNotNull { KavaReflector.readField(item, it)?.toString() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        repeat(8) {
            when (current) {
                is Activity -> return current as Activity
                is ContextWrapper -> current = (current as ContextWrapper).baseContext
                else -> return null
            }
        }
        return null
    }

    private fun groupSummary(count: Int, unread: Int): String {
        return if (unread > 0) "$count 个会话 · $unread 条未读" else "$count 个会话"
    }

    private fun stableGroupKey(groupId: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(groupId.toByteArray(Charsets.UTF_8))
        val hex = CharArray(32)
        val digits = "0123456789abcdef"
        repeat(16) { index ->
            val value = bytes[index].toInt() and 0xff
            hex[index * 2] = digits[value ushr 4]
            hex[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(hex)
    }

    private fun value(row: Map<String, Any>, key: String): String = row[key]?.toString().orEmpty()

    private fun intValue(row: Map<String, Any>, key: String): Int =
        (row[key] as? Number)?.toInt() ?: value(row, key).toIntOrNull() ?: 0

    private fun longValue(row: Map<String, Any>, key: String): Long =
        (row[key] as? Number)?.toLong() ?: value(row, key).toLongOrNull() ?: 0L

    private class ConversationMenuTarget(activity: Activity, val talker: String) {
        val activity = WeakReference(activity)
    }

    private data class NativeGroupMenuMethods(
        val create: Method,
        val click: Method
    )

    private class NativeGroupLongClickTarget(
        activity: Activity,
        val talker: String,
        val groupId: String,
        fragment: Any
    ) {
        val activity = WeakReference(activity)
        val fragment = WeakReference(fragment)
    }

    private data class NativeGroupMenuTarget(
        val target: NativeGroupLongClickTarget,
        val action: NativeGroupMenuAction,
        val extensionItemId: Int? = null
    )

    private enum class NativeGroupMenuAction {
        REMOVE,
        MOVE,
        PIN,
        UNPIN,
        BOTTOM,
        UNBOTTOM,
        EXTENSION
    }

    private data class GroupSnapshot(
        val totalConversations: Int,
        val unreadCount: Int,
        val mutedUnreadCount: Int,
        val latest: ConversationRecord?
    )

    private data class UpsertResult(
        val success: Boolean,
        val changed: Boolean
    )

    private data class VirtualRowsResult(
        val readyGroupIds: Set<String>,
        val changedGroupIds: Set<String>
    )

    private data class EffectiveConversationSnapshot(
        val account: String,
        val groups: List<ConversationGroup>,
        val conversationIds: Map<String, List<String>>,
        val projectedOfficialIds: Set<String>,
        val hiddenHomeIds: Set<String>
    )

    private data class ConversationRecord(
        val username: String,
        val unreadCount: Int,
        val wechatMuted: Boolean,
        val status: Int,
        val isSend: Int,
        val conversationTime: Long,
        val content: String,
        val messageType: Int,
        val flag: Long,
        val digest: String,
        val digestUser: String
    )

}
