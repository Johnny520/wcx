package h.Hchat.hooks.items.messageforward

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.sns.PreparedSnsForward
import h.Hchat.hooks.api.sns.PreparedSnsImage
import h.Hchat.hooks.api.sns.SnsForwardContentResolver
import h.Hchat.hooks.api.sns.SnsForwardSnapshot
import h.Hchat.hooks.api.media.FavoriteMenuItemResolver
import h.Hchat.hooks.api.media.FavoriteMenuLocator
import h.Hchat.hooks.api.message.MultiSelectMessageMenuLocator
import h.Hchat.hooks.api.message.MultiSelectMessageResolver
import h.Hchat.hooks.api.message.MultiSelectMessageUi
import h.Hchat.hooks.api.message.SingleMessageMenuLocator
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.conversationgroup.ConversationGroupPickerSupport
import h.Hchat.hooks.items.grouplabel.GroupChatLabelStore
import h.Hchat.hooks.items.selectedmessages.SelectedMessageContactRepository
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSendHandle
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSnapshot
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesRuntimeCoordinator
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesSettings
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MessageForwardFeature : BaseFeature() {
    private var hooker: MessageForwardHooker? = null
    private var snsHooker: SnsForwardMenuHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "转发"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MessageForwardSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val snsResolver = SnsForwardContentResolver(context, ::logFeatureError)
        hooker = MessageForwardHooker(context, snsResolver, ::logFeatureError)
        snsHooker = SnsForwardMenuHooker(
            context = context,
            resolver = snsResolver,
            onForward = { activity, snapshot -> hooker?.showSnsActions(activity, snapshot) },
            logger = ::logFeatureError
        )
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.destroy()
        hooker = null
        snsHooker?.destroy()
        snsHooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
        DexInstallScheduler.schedule("${ID}_sns", "朋友圈转发") {
            snsHooker?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "message_forward"
    }
}

private class MessageForwardHooker(
    private val context: FeatureContext,
    private val snsResolver: SnsForwardContentResolver,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = HchatStorage.preferences(context.hostContext(), MessageForwardSettings.PREFS_NAME)
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val bindingsByItem = Collections.synchronizedMap(WeakHashMap<MenuItem, MessageBinding>())
    private val bindingsByGroup = ConcurrentHashMap<Int, MessageBinding>()
    private val favoriteBindingsByItem = Collections.synchronizedMap(WeakHashMap<MenuItem, Long>())
    @Volatile private var pendingFavoriteLocalId = 0L
    @Volatile private var lastFavoriteHandledItem: WeakReference<MenuItem>? = null
    @Volatile private var lastFavoriteHandledAt = 0L
    private val messageIdMethods = ConcurrentHashMap<Class<*>, Method>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-MessageForward").apply { isDaemon = true }
    }
    private val systemShare = MessageForwardSystemShare(context)
    private val chatLivePhotoResolver = ChatLivePhotoResolver(context, logger)

    @Synchronized
    fun install(): Boolean {
        chatLivePhotoResolver.warmup()
        val createMethods = SingleMessageMenuLocator.menuCreateMethods(context, logger)
        val clickMethods = SingleMessageMenuLocator.menuClickMethods(context, logger)
        val createHooked = createMethods.count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addForwardMenu(param)
                }
            })
        }
        val clickHooked = clickMethods.count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleForwardClick(param)
                }
            })
        }
        if (createHooked <= 0) logger("转发菜单创建Hook未安装", null)
        if (clickHooked <= 0) logger("转发菜单点击Hook未安装", null)

        val multiCreateMethod = MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)
        val multiClickMethod = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
        val multiExitMethod = multiClickMethod?.let {
            MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger)
        }
        val multiCreateHooked = multiCreateMethod != null && multiExitMethod != null &&
            hook(multiCreateMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addMultiMomentsMenu(param)
                }
            })
        val multiClickHooked = multiClickMethod != null && multiExitMethod != null &&
            hook(multiClickMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleMultiMomentsClick(param, multiExitMethod)
                }
            })
        if (!multiCreateHooked) logger("多选转发朋友圈菜单创建Hook未安装", null)
        if (!multiClickHooked) logger("多选转发朋友圈菜单点击Hook未安装", null)

        val favoriteCreateHooked = FavoriteMenuLocator.menuCreateMethods(
            context,
            includeDetails = false,
            logger = logger
        ).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addFavoriteForwardMenu(param)
                }
            })
        }
        val favoriteClickHooked = FavoriteMenuLocator.menuClickMethods(
            context,
            includeDetails = false,
            logger = logger
        ).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleFavoriteForwardClick(param)
                }
            })
        }
        if (favoriteCreateHooked <= 0) logger("收藏转发菜单创建Hook未安装", null)
        if (favoriteClickHooked <= 0) logger("收藏转发菜单点击Hook未安装", null)
        return createHooked > 0 && clickHooked > 0 &&
            multiCreateHooked && multiClickHooked &&
            favoriteCreateHooked > 0 && favoriteClickHooked > 0
    }

    fun destroy() {
        bindingsByItem.clear()
        bindingsByGroup.clear()
        favoriteBindingsByItem.clear()
        pendingFavoriteLocalId = 0L
        lastFavoriteHandledItem = null
        lastFavoriteHandledAt = 0L
        executor.shutdownNow()
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("转发菜单Hook安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addForwardMenu(param: XC_MethodHook.MethodHookParam) {
        clearBindings()
        if (!singleForwardEnabled()) return
        val args = param.args ?: return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View ?: return
        val nativeMessage = resolveNativeMessage(view.tag) ?: return
        if (nativeMessageType(nativeMessage) == null) return
        val item = addMenuItem(menu, view, readMenuGroupId(menu)) ?: return
        val binding = MessageBinding(nativeMessage)
        bindingsByItem[item] = binding
        bindingsByGroup[item.groupId] = binding
        moveAfterRepeat(menu, item)
    }

    private fun handleForwardClick(param: XC_MethodHook.MethodHookParam) {
        if (!singleForwardEnabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != SingleMessageMenuLocator.HCHAT_FORWARD_MENU_ITEM_ID) return
        val binding = consumeBinding(item)
        val activity = currentActivity()
        if (binding == null || activity == null) {
            toast(activity, "消息不可转发")
            return
        }
        val snapshot = SelectedMessageSnapshot.fromNative(binding.nativeMessage)
            ?: SelectedMessageSnapshot.fromNativeForMoments(binding.nativeMessage)
        if (snapshot == null) {
            toast(activity, "无法读取该消息")
            return
        }
        main.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                showActions(activity, snapshot)
            }
        }
    }

    private fun addMultiMomentsMenu(param: XC_MethodHook.MethodHookParam) {
        if (!multiMomentsEnabled()) return
        val selected = MultiSelectMessageResolver.resolve(param.thisObject)
        val types = selected.mapNotNull(::nativeMessageType)
        if (selected.isEmpty() || types.size != selected.size || momentsCombinationError(types) != null) return
        val menu = param.args?.getOrNull(0) ?: return
        addPlainMenuItem(menu, MULTI_MOMENTS_MENU_ITEM_ID, MULTI_MOMENTS_MENU_TITLE)
    }

    private fun handleMultiMomentsClick(
        param: XC_MethodHook.MethodHookParam,
        exitMethod: Method
    ) {
        if (!multiMomentsEnabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != MULTI_MOMENTS_MENU_ITEM_ID) return
        param.result = null

        val activity = currentActivity() ?: return
        val selected = MultiSelectMessageResolver.resolve(param.thisObject)
        val snapshots = selected.mapNotNull { SelectedMessageSnapshot.fromNativeForMoments(it) }
            .sortedWith(compareBy<SelectedMessageSnapshot> { it.createTime }.thenBy { it.msgId })
        if (selected.isEmpty() || snapshots.size != selected.size) {
            toast(activity, "部分选中消息无法读取")
            return
        }
        val preparation = prepareMomentsIntent(activity, snapshots)
        val intent = preparation.intent
        if (intent == null) {
            toast(activity, preparation.error)
            return
        }
        val exitTarget = MultiSelectMessageUi.resolveExitTarget(param.thisObject, exitMethod, logger)
        if (exitTarget == null) {
            toast(activity, "无法退出多选状态，请稍后重试")
            return
        }
        exitTarget.exit(logger)
        main.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                startMomentsEditor(activity, intent)
            }
        }
    }

    private fun addFavoriteForwardMenu(param: XC_MethodHook.MethodHookParam) {
        if (!favoriteForwardEnabled()) return
        val args = param.args ?: return
        val favorite = FavoriteMenuItemResolver.resolveMenuItem(args.getOrNull(2))
            ?: FavoriteMenuItemResolver.resolveMenuItem(args.getOrNull(1) as? View)
            ?: FavoriteMenuItemResolver.resolveMenuItem(args)
            ?: FavoriteMenuItemResolver.resolveMenuItem(param.thisObject)
            ?: FavoriteMenuItemResolver.resolve(args)
            ?: return
        if (KavaReflector.invokeMethod(
                args.getOrNull(0) ?: return,
                "findItem",
                FavoriteMenuLocator.HCHAT_VOICE_FAVORITE_FORWARD_MENU_ITEM_ID
            ) != null
        ) return
        val localId = FavoriteMenuItemResolver.localId(favorite)
        if (localId <= 0L) return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View
        val item = addIconMenuItem(
            menu = menu,
            view = view,
            groupId = readMenuGroupId(menu),
            itemId = FAVORITE_FORWARD_MENU_ITEM_ID,
            title = FAVORITE_FORWARD_MENU_TITLE
        ) ?: return
        synchronized(favoriteBindingsByItem) {
            if (!favoriteBindingsByItem.containsKey(item)) {
                favoriteBindingsByItem[item] = localId
                pendingFavoriteLocalId = localId
            }
        }
    }

    @Synchronized
    private fun handleFavoriteForwardClick(param: XC_MethodHook.MethodHookParam) {
        if (!favoriteForwardEnabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != FAVORITE_FORWARD_MENU_ITEM_ID) return
        val now = SystemClock.elapsedRealtime()
        if (lastFavoriteHandledItem?.get() === item &&
            now - lastFavoriteHandledAt < FAVORITE_CLICK_DEDUP_MS
        ) return
        param.result = null

        val activity = currentActivity() ?: return
        val localId = favoriteBindingsByItem.remove(item)
            ?: pendingFavoriteLocalId.takeIf { it > 0L }
            ?: FavoriteMenuItemResolver.localId(FavoriteMenuItemResolver.resolve(param.args))
        favoriteBindingsByItem.clear()
        pendingFavoriteLocalId = 0L
        if (localId <= 0L) {
            toast(activity, "当前收藏不可用")
            return
        }
        lastFavoriteHandledItem = WeakReference(item)
        lastFavoriteHandledAt = now
        main.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            showFavoriteActions(activity, localId)
        }
    }

    private fun sendFavorite(
        activity: Activity,
        localId: Long,
        targetIds: List<String>,
        channel: Int = SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
        title: String = "转发收藏"
    ) {
        sendContentItems(
            activity = activity,
            items = listOf(favoriteContent(localId)),
            targetIds = targetIds,
            channel = channel,
            title = title
        )
    }

    private fun sendContentItems(
        activity: Activity,
        items: List<ScheduledTaskContentItem>,
        targetIds: List<String>,
        channel: Int,
        title: String
    ) {
        SelectedMessagesRuntimeCoordinator.validationError(channel, items)?.let {
            toast(activity, it)
            return
        }
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            toast(activity, "请选择转发对象")
            return
        }
        var handle: SelectedMessageSendHandle? = null
        val finishing = AtomicBoolean(false)
        val progress = if (SelectedMessagesSettings.isBackgroundSilentSendEnabled(activity)) {
            null
        } else {
            VoiceForwardMiuixDialog.showLoading(
                activity = activity,
                onDismiss = { if (!finishing.get()) handle?.cancel() },
                title = title,
                message = "正在发送..."
            )
        }
        handle = SelectedMessagesRuntimeCoordinator.enqueueScheduledItems(
            channel = channel,
            items = items,
            targetIds = targets,
            targetIntervalSeconds = 0,
            itemIntervalSeconds = 0
        ) { success, total, canceled ->
            main.post {
                finishing.set(true)
                progress?.close()
                val message = when {
                    canceled -> "$title 已取消: $success/$total"
                    success == total -> "$title 完成: $success/$total"
                    else -> "$title 部分失败: $success/$total"
                }
                toast(activity, message)
            }
        }
        if (handle == null) {
            finishing.set(true)
            progress?.close()
            toast(activity, "$title 启动失败")
        }
    }

    private fun favoriteContent(localId: Long): ScheduledTaskContentItem {
        return ScheduledTaskContentItem(
            ScheduledTaskSettings.TYPE_FAVORITE,
            localId.toString()
        )
    }

    private fun showFavoriteActions(activity: Activity, localId: Long) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = FAVORITE_FORWARD_MENU_TITLE,
            summary = "",
            choices = listOf(
                "转发到朋友圈" to "",
                "转发给好友" to "",
                "转发至分组" to "",
                "分享" to "",
                "群发助手" to "",
                "转发至标签" to ""
            ),
            onSelected = { index ->
                when (index) {
                    0 -> forwardFavoriteToMoments(activity, localId)
                    1 -> chooseFavoriteDirectTargets(activity, localId)
                    2 -> chooseConversationGroupTargets(activity) { targetIds ->
                        sendFavorite(
                            activity = activity,
                            localId = localId,
                            targetIds = targetIds,
                            title = "转发至分组"
                        )
                    }
                    3 -> shareFavorite(activity, localId)
                    4 -> chooseFavoriteMassSendChannel(activity, localId)
                    5 -> chooseFavoriteLabels(activity, localId)
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseFavoriteDirectTargets(activity: Activity, localId: Long) {
        showContacts(
            activity = activity,
            friendsOnly = false,
            title = "选择转发对象",
            confirmText = "转发"
        ) { targets ->
            sendFavorite(
                activity = activity,
                localId = localId,
                targetIds = targets.map { it.id },
                title = "转发给好友"
            )
        }
    }

    private fun chooseFavoriteMassSendChannel(activity: Activity, localId: Long) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "选择群发通道",
            summary = "",
            choices = listOf(
                "模块通道" to "支持好友、群聊、公众号和标签",
                "微信原生群发助手" to "仅选择好友并按原生队列发送"
            ),
            onSelected = { index ->
                val channel = if (index == 1) {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                } else {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE
                }
                SelectedMessagesRuntimeCoordinator.validationError(
                    channel,
                    listOf(favoriteContent(localId))
                )?.let {
                    toast(activity, it)
                    return@showChoices
                }
                val official = channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                showContacts(
                    activity = activity,
                    friendsOnly = official,
                    title = if (official) "选择官方群发好友" else "选择群发对象",
                    confirmText = "发送"
                ) { targets ->
                    sendFavorite(
                        activity = activity,
                        localId = localId,
                        targetIds = targets.map { it.id },
                        channel = channel,
                        title = "群发助手"
                    )
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseFavoriteLabels(activity: Activity, localId: Long) {
        chooseLabelTargets(activity) { targetIds ->
            sendFavorite(
                activity = activity,
                localId = localId,
                targetIds = targetIds,
                title = "转发至标签"
            )
        }
    }

    private fun forwardFavoriteToMoments(activity: Activity, localId: Long) {
        loadFavoriteShareData(activity, localId, "转发到朋友圈") { data ->
            val intent = when (data.type) {
                1 -> Intent().setClassName(activity.packageName, SNS_UPLOAD_ACTIVITY).apply {
                    putExtra("Ksnsupload_type", 9)
                    putExtra("Kdescription", data.text)
                }
                2 -> data.path?.let { path ->
                    Intent().setClassName(activity.packageName, SNS_UPLOAD_ACTIVITY).apply {
                        putStringArrayListExtra("sns_kemdia_path_list", arrayListOf(path))
                    }
                }
                4 -> data.path?.let { path ->
                    Intent().setClassName(activity.packageName, SNS_UPLOAD_ACTIVITY).apply {
                        putExtra("Ksnsupload_type", 14)
                        putExtra("KSightPath", path)
                        putExtra("KSightThumbPath", path)
                    }
                }
                else -> null
            }
            if (intent == null) {
                val error = if (data.type == 2 || data.type == 4) {
                    "收藏媒体文件不存在"
                } else {
                    "该收藏类型暂不支持转发到朋友圈"
                }
                toast(activity, error)
            } else {
                startMomentsEditor(activity, intent)
            }
        }
    }

    private fun shareFavorite(activity: Activity, localId: Long) {
        loadFavoriteShareData(activity, localId, "分享收藏") { data ->
            systemShare.shareFavorite(
                activity = activity,
                type = data.type,
                text = data.text,
                path = data.path
            )?.let { toast(activity, it) }
        }
    }

    private fun loadFavoriteShareData(
        activity: Activity,
        localId: Long,
        title: String,
        onLoaded: (FavoriteShareData) -> Unit
    ) {
        val canceled = AtomicBoolean(false)
        val closingForTransition = AtomicBoolean(false)
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            onDismiss = {
                if (!closingForTransition.get()) canceled.set(true)
            },
            title = title,
            message = "正在读取收藏..."
        )
        executor.execute {
            val result = runCatching {
                val favoriteApi = WeChatApis.media()?.favorites()
                    ?: throw IllegalStateException("收藏接口不可用")
                val item = favoriteApi.get(localId)
                    ?: throw IllegalStateException("收藏内容不可用")
                val text = if (item.type == 1) {
                    favoriteApi.textContent(localId).orEmpty().ifBlank { item.title }
                } else {
                    item.title
                }
                val path = if (item.type == 2 || item.type == 4) {
                    favoriteApi.previewPath(localId)?.takeIf { File(it).isFile }
                } else {
                    null
                }
                FavoriteShareData(item.type, text, path)
            }
            main.post {
                if (canceled.get()) return@post
                closingForTransition.set(true)
                loading.close()
                val decor = activity.window?.decorView ?: return@post
                decor.postOnAnimation {
                    if (canceled.get() || activity.isFinishing || activity.isDestroyed) {
                        return@postOnAnimation
                    }
                    result.onSuccess(onLoaded).onFailure {
                        logger("读取收藏转发内容失败: localId=$localId", it)
                        toast(activity, it.message ?: "收藏内容不可用")
                    }
                }
            }
        }
    }

    fun showSnsActions(activity: Activity, snapshot: SnsForwardSnapshot) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = SNS_FORWARD_MENU_TITLE,
            summary = "",
            choices = listOf(
                "转发到朋友圈" to "",
                "转发给好友" to "",
                "转发至分组" to "",
                "分享" to "",
                "群发助手" to "",
                "转发至标签" to ""
            ),
            onSelected = { index ->
                when (index) {
                    0 -> prepareSnsForward(activity, snapshot, "转发到朋友圈") { prepared ->
                        forwardPreparedSnsToMoments(activity, prepared)
                    }
                    1 -> chooseSnsDirectTargets(activity, snapshot)
                    2 -> chooseConversationGroupTargets(activity) { targetIds ->
                        prepareSnsForward(activity, snapshot, "转发至分组") { prepared ->
                            sendContentItems(
                                activity = activity,
                                items = prepared.contentItems(),
                                targetIds = targetIds,
                                channel = SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
                                title = "转发至分组"
                            )
                        }
                    }
                    3 -> prepareSnsForward(activity, snapshot, "分享朋友圈") { prepared ->
                        systemShare.shareSns(activity, prepared)?.let { toast(activity, it) }
                    }
                    4 -> chooseSnsMassSendChannel(activity, snapshot)
                    5 -> chooseSnsLabels(activity, snapshot)
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseSnsDirectTargets(activity: Activity, snapshot: SnsForwardSnapshot) {
        showContacts(
            activity = activity,
            friendsOnly = false,
            title = "选择转发对象",
            confirmText = "转发"
        ) { targets ->
            prepareSnsForward(activity, snapshot, "转发给好友") { prepared ->
                sendContentItems(
                    activity = activity,
                    items = prepared.contentItems(),
                    targetIds = targets.map { it.id },
                    channel = SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
                    title = "转发给好友"
                )
            }
        }
    }

    private fun chooseSnsMassSendChannel(activity: Activity, snapshot: SnsForwardSnapshot) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "选择群发通道",
            summary = "",
            choices = listOf(
                "模块通道" to "支持好友、群聊、公众号和标签",
                "微信原生群发助手" to "仅选择好友并按原生队列发送"
            ),
            onSelected = { index ->
                val channel = if (index == 1) {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                } else {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE
                }
                val official = channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                showContacts(
                    activity = activity,
                    friendsOnly = official,
                    title = if (official) "选择官方群发好友" else "选择群发对象",
                    confirmText = "发送"
                ) { targets ->
                    prepareSnsForward(activity, snapshot, "群发助手") { prepared ->
                        sendContentItems(
                            activity = activity,
                            items = prepared.contentItems(),
                            targetIds = targets.map { it.id },
                            channel = channel,
                            title = "群发助手"
                        )
                    }
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseSnsLabels(activity: Activity, snapshot: SnsForwardSnapshot) {
        chooseLabelTargets(activity) { targetIds ->
            prepareSnsForward(activity, snapshot, "转发至标签") { prepared ->
                sendContentItems(
                    activity = activity,
                    items = prepared.contentItems(),
                    targetIds = targetIds,
                    channel = SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
                    title = "转发至标签"
                )
            }
        }
    }

    private fun prepareSnsForward(
        activity: Activity,
        snapshot: SnsForwardSnapshot,
        title: String,
        onPrepared: (PreparedSnsForward) -> Unit
    ) {
        val canceled = AtomicBoolean(false)
        val closingForTransition = AtomicBoolean(false)
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            onDismiss = {
                if (!closingForTransition.get()) canceled.set(true)
            },
            title = title,
            message = if (snapshot.isImage || snapshot.isVideo) {
                "正在准备朋友圈媒体..."
            } else {
                "正在读取朋友圈..."
            }
        )
        executor.execute {
            val result = runCatching { snsResolver.prepare(snapshot, canceled) }
            main.post {
                if (canceled.get()) return@post
                closingForTransition.set(true)
                loading.close()
                val decor = activity.window?.decorView ?: return@post
                decor.postOnAnimation {
                    if (canceled.get() || activity.isFinishing || activity.isDestroyed) {
                        return@postOnAnimation
                    }
                    result.onSuccess(onPrepared).onFailure {
                        if (it !is InterruptedException) {
                            logger("准备朋友圈转发内容失败: id=${snapshot.id}", it)
                            toast(activity, it.message ?: "朋友圈内容准备失败")
                        }
                    }
                }
            }
        }
    }

    private fun forwardPreparedSnsToMoments(
        activity: Activity,
        prepared: PreparedSnsForward
    ) {
        val intent = Intent().setClassName(activity.packageName, SNS_UPLOAD_ACTIVITY)
        when {
            prepared.video.isNotBlank() -> {
                intent.putExtra("Ksnsupload_type", 14)
                intent.putExtra("KSightPath", prepared.video)
                intent.putExtra("KSightThumbPath", prepared.videoThumb)
                intent.putExtra("Kdescription", prepared.text)
            }
            prepared.images.isNotEmpty() -> {
                if (!putPreparedImageItems(intent, prepared.imageItems)) {
                    intent.putStringArrayListExtra(
                        "sns_kemdia_path_list",
                        prepared.images.toCollection(ArrayList())
                    )
                }
                intent.putExtra("Kdescription", prepared.text)
            }
            prepared.text.isNotBlank() -> {
                intent.putExtra("Ksnsupload_type", 9)
                intent.putExtra("Kdescription", prepared.text)
            }
            else -> {
                toast(activity, "朋友圈内容为空")
                return
            }
        }
        startMomentsEditor(activity, intent)
    }

    private fun showActions(activity: Activity, snapshot: SelectedMessageSnapshot) {
        val supportsGeneralForwarding = snapshot.retransmit != null || File(snapshot.voicePath).isFile
        val choices = if (supportsGeneralForwarding) {
            listOf(
                "转发到朋友圈" to "",
                "转发给好友" to "",
                "转发至分组" to "",
                "分享" to "",
                "群发助手" to "",
                "转发至标签" to ""
            )
        } else {
            listOf("转发到朋友圈" to "")
        }
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = MENU_TITLE,
            summary = "",
            choices = choices,
            onSelected = { index ->
                when (index) {
                    0 -> forwardToMoments(activity, snapshot)
                    1 -> chooseDirectTargets(activity, snapshot)
                    2 -> chooseConversationGroupTargets(activity) { targetIds ->
                        sendSnapshots(
                            activity = activity,
                            snapshot = snapshot,
                            targetIds = targetIds,
                            channel = SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
                            title = "转发至分组"
                        )
                    }
                    3 -> systemShare.share(activity, snapshot)?.let { toast(activity, it) }
                    4 -> chooseMassSendChannel(activity, snapshot)
                    5 -> chooseLabels(activity, snapshot)
                }
            },
            onDismiss = {}
        )
    }

    private fun forwardToMoments(activity: Activity, snapshot: SelectedMessageSnapshot) {
        val preparation = prepareMomentsIntent(activity, listOf(snapshot))
        val intent = preparation.intent
        if (intent == null) {
            toast(activity, preparation.error)
            return
        }
        startMomentsEditor(activity, intent)
    }

    private fun prepareMomentsIntent(
        activity: Activity,
        snapshots: List<SelectedMessageSnapshot>
    ): MomentsPreparation {
        if (snapshots.isEmpty()) return MomentsPreparation(error = "未找到选中的消息")
        val types = snapshots.map { WeChatMessageTypes.normalize(it.type) }
        momentsCombinationError(types)?.let { return MomentsPreparation(error = it) }

        val text = snapshots.asSequence()
            .map(::momentsDescription)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val imageItems = snapshots.asSequence()
            .filter { WeChatMessageTypes.normalize(it.type) == WeChatMessageTypes.IMAGE }
            .map { snapshot ->
                val imagePath = snapshot.retransmit?.fileName.orEmpty()
                    .ifBlank { snapshot.imagePath }
                val livePhoto = chatLivePhotoResolver.resolve(snapshot)
                PreparedSnsImage(
                    imagePath = imagePath,
                    liveVideoPath = livePhoto?.videoPath.orEmpty(),
                    liveVideoDurationMillis = livePhoto?.durationMillis ?: 0,
                    liveVideoWidth = livePhoto?.width ?: 0,
                    liveVideoHeight = livePhoto?.height ?: 0,
                    liveVideoSizeBytes = livePhoto?.sizeBytes ?: 0L
                )
            }
            .toList()
        val images = imageItems.map { it.imagePath }
        val videoSnapshot = snapshots.firstOrNull {
            WeChatMessageTypes.normalize(it.type) in MOMENTS_VIDEO_TYPES
        }
        val video = videoSnapshot?.let(::resolveSelectedVideoPath).orEmpty()
        if (images.any { !File(it).isFile }) {
            return MomentsPreparation(error = "部分选中图片文件不存在")
        }
        if (videoSnapshot != null && video.isBlank()) {
            return MomentsPreparation(error = "选中视频文件不存在")
        }
        if (video.isNotBlank() && !File(video).isFile) {
            return MomentsPreparation(error = "选中视频文件不存在")
        }

        val intent = Intent().setClassName(activity.packageName, SNS_UPLOAD_ACTIVITY)
        when {
            video.isNotBlank() -> {
                intent.putExtra("Ksnsupload_type", 14)
                intent.putExtra("KSightPath", video)
                intent.putExtra("KSightThumbPath", video)
                intent.putExtra("Kdescription", text)
            }
            images.isNotEmpty() -> {
                if (!putPreparedImageItems(intent, imageItems)) {
                    if (imageItems.any { it.isLivePhoto }) {
                        return MomentsPreparation(error = "实况图片视频未能交给微信朋友圈编辑器")
                    }
                    intent.putStringArrayListExtra(
                        "sns_kemdia_path_list",
                        images.toCollection(ArrayList())
                    )
                }
                intent.putExtra("Kdescription", text)
            }
            else -> {
                intent.putExtra("Ksnsupload_type", 9)
                intent.putExtra("Kdescription", text)
            }
        }
        return MomentsPreparation(intent = intent)
    }

    private fun putPreparedImageItems(
        intent: Intent,
        items: List<PreparedSnsImage>
    ): Boolean {
        return SnsLivePhotoIntentBuilder.putImageItems(
            intent = intent,
            items = items,
            classLoader = context.hostClassLoader(),
            logger = logger
        )
    }

    private fun resolveSelectedVideoPath(snapshot: SelectedMessageSnapshot): String {
        val candidates = listOf(snapshot.retransmit?.fileName.orEmpty(), snapshot.imagePath)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        candidates.firstOrNull { File(it).isFile }?.let {
            return File(it).absolutePath
        }
        val videos = WeChatApis.media()?.videos() ?: return ""
        return candidates.firstNotNullOfOrNull { token ->
            videos.resolvePathToken(token).takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun momentsCombinationError(types: List<Int>): String? {
        if (types.isEmpty()) return "未找到选中的消息"
        val imageCount = types.count { it == WeChatMessageTypes.IMAGE }
        val videoCount = types.count { it in MOMENTS_VIDEO_TYPES }
        if (imageCount > MAX_MOMENTS_IMAGES) return "朋友圈最多选择 $MAX_MOMENTS_IMAGES 张图片"
        if (videoCount > 1) return "朋友圈一次只能选择一个视频"
        if (imageCount > 0 && videoCount > 0) return "图片和视频不能同时转发到朋友圈"
        return null
    }

    private fun momentsDescription(snapshot: SelectedMessageSnapshot): String {
        val raw = snapshot.retransmit?.content.orEmpty().ifBlank { snapshot.content }
        val message = WeChatMessage(
            snapshot.msgId,
            0L,
            snapshot.type,
            0,
            0,
            snapshot.createTime,
            snapshot.sourceTalker,
            raw,
            snapshot.imagePath,
            "",
            "",
            0
        )
        val body = message.bodyContent().trim()
        return when {
            message.isText() -> readablePlainText(body)
            message.isImage() || message.isVideo() ||
                WeChatMessageTypes.normalize(message.type) == 62 -> ""
            message.isVoice() -> {
                val seconds = (snapshot.voiceDurationMillis / 1000f)
                    .takeIf { it > 0f }
                    ?.let { value -> if (value % 1f == 0f) value.toInt().toString() else "%.1f".format(value) }
                labeledMomentsText("语音", seconds?.let { "${it}秒" }.orEmpty())
            }
            message.isEmoji() -> "[表情]"
            message.isShareCard() -> labeledMomentsText(
                "名片",
                firstReadableValue(
                    WeChatMessage.xmlAttr(body, "nickname"),
                    WeChatMessage.xmlTag(body, "nickname"),
                    WeChatMessage.xmlAttr(body, "alias"),
                    WeChatMessage.xmlTag(body, "username")
                )
            )
            message.isLocation() -> labeledMomentsText(
                "位置",
                firstReadableValue(
                    WeChatMessage.xmlAttr(body, "label"),
                    WeChatMessage.xmlAttr(body, "poiname"),
                    WeChatMessage.xmlTag(body, "label"),
                    WeChatMessage.xmlTag(body, "poiname")
                )
            )
            message.isRedPacket() -> "[红包]"
            message.isTransfer() -> labeledMomentsText(
                "转账",
                message.getTransferMsg()?.description.orEmpty()
            )
            message.isQuote() -> {
                val quote = message.getQuoteMsg()
                labeledMomentsText(
                    "引用",
                    listOf(quote?.title.orEmpty(), quote?.content.orEmpty())
                        .map(::readableMessageDetail)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" | ")
                )
            }
            message.isFile() -> labeledMomentsText(
                "文件",
                message.getFileMsg()?.let { it.title.ifBlank { it.fileName } }.orEmpty()
            )
            message.isVideoNumberVideo() -> appMomentsText("视频号", body)
            message.isMiniProgram() -> appMomentsText("小程序", body)
            message.isLink() -> appMomentsText("链接", body)
            message.isMusic() -> appMomentsText("音乐", body)
            message.isNote() -> appMomentsText("接龙", body)
            message.isVoipVideo() -> "[视频通话]"
            message.isVoipVoice() -> "[语音通话]"
            message.isVoip() -> "[通话记录]"
            message.isRecalled() -> "[撤回消息]"
            message.isPat() -> labeledMomentsText("拍一拍", readablePlainText(body))
            message.isSystem() -> labeledMomentsText("系统消息", readableMessageDetail(body))
            message.isApp() -> appMomentsText("卡片", body)
            else -> labeledMomentsText(snapshot.label(), readableMessageDetail(body))
        }
    }

    private fun appMomentsText(label: String, raw: String): String {
        val detail = firstReadableValue(
            WeChatMessage.xmlTag(raw, "title"),
            WeChatMessage.xmlTag(raw, "des"),
            WeChatMessage.xmlTag(raw, "description"),
            WeChatMessage.xmlTag(raw, "content")
        )
        val url = firstReadableValue(
            WeChatMessage.xmlTag(raw, "url"),
            WeChatMessage.xmlTag(raw, "weburl")
        ).takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
        return labeledMomentsText(
            label,
            listOf(detail, url).filter { it.isNotBlank() }.distinct().joinToString("\n")
        )
    }

    private fun readableMessageDetail(raw: String): String {
        val detail = firstReadableValue(
            WeChatMessage.xmlTag(raw, "title"),
            WeChatMessage.xmlTag(raw, "des"),
            WeChatMessage.xmlTag(raw, "description"),
            WeChatMessage.xmlTag(raw, "content")
        )
        if (detail.isNotBlank()) return detail
        return readablePlainText(raw).takeUnless { raw.trimStart().startsWith("<") }.orEmpty()
    }

    private fun firstReadableValue(vararg values: String): String {
        return values.asSequence()
            .map(::readablePlainText)
            .firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("<") }
            .orEmpty()
    }

    private fun labeledMomentsText(label: String, detail: String): String {
        val readable = readablePlainText(detail)
        return if (readable.isBlank()) "[$label]" else "[$label] $readable"
    }

    private fun readablePlainText(value: String): String {
        return value.trim()
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun startMomentsEditor(activity: Activity, intent: Intent) {
        runCatching { activity.startActivity(intent) }
            .onFailure {
                logger("打开朋友圈编辑界面失败", it)
                toast(activity, "朋友圈编辑界面不可用")
            }
    }

    private fun chooseDirectTargets(activity: Activity, snapshot: SelectedMessageSnapshot) {
        showContacts(
            activity = activity,
            friendsOnly = false,
            title = "选择转发对象",
            confirmText = "转发"
        ) { targets ->
            sendSnapshots(
                activity,
                snapshot,
                targets.map { it.id },
                SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
                "转发给好友"
            )
        }
    }

    private fun chooseConversationGroupTargets(
        activity: Activity,
        onSelected: (List<String>) -> Unit
    ) {
        loadContacts(activity, friendsOnly = false, title = "转发至分组") { contacts ->
            val groups = ConversationGroupPickerSupport.filters(
                activity,
                contacts.map { it.id }
            )
            if (groups.isEmpty()) {
                toast(activity, "没有可用的聊天分组")
                return@loadContacts
            }
            VoiceForwardMiuixDialog.showMultiChoices(
                activity = activity,
                title = "选择转发分组",
                summary = "将发送到所选分组内的全部会话",
                choices = groups.map { group ->
                    group.name to "${group.conversationIds.size} 个会话"
                },
                onConfirm = { selected ->
                    val targetIds = selected.asSequence()
                        .mapNotNull(groups::getOrNull)
                        .flatMap { it.conversationIds.asSequence() }
                        .distinct()
                        .toList()
                    if (targetIds.isEmpty()) {
                        toast(activity, "所选分组没有可用会话")
                    } else {
                        onSelected(targetIds)
                    }
                },
                onDismiss = {}
            )
        }
    }

    private fun chooseMassSendChannel(activity: Activity, snapshot: SelectedMessageSnapshot) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "选择群发通道",
            summary = "",
            choices = listOf(
                "模块通道" to "支持好友、群聊、公众号和标签",
                "微信原生群发助手" to "仅选择好友并按原生队列发送"
            ),
            onSelected = { index ->
                val channel = if (index == 1) {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                } else {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE
                }
                val snapshots = listOf(snapshot)
                SelectedMessagesRuntimeCoordinator.snapshotValidationError(channel, snapshots)?.let {
                    toast(activity, it)
                    return@showChoices
                }
                val official = channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                showContacts(
                    activity = activity,
                    friendsOnly = official,
                    title = if (official) "选择官方群发好友" else "选择群发对象",
                    confirmText = "发送"
                ) { targets ->
                    sendSnapshots(activity, snapshot, targets.map { it.id }, channel, "群发助手")
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseLabels(activity: Activity, snapshot: SelectedMessageSnapshot) {
        chooseLabelTargets(activity) { targetIds ->
            sendSnapshots(
                activity,
                snapshot,
                targetIds,
                SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE,
                "转发至标签"
            )
        }
    }

    private fun chooseLabelTargets(activity: Activity, onSelected: (List<String>) -> Unit) {
        loadContacts(activity, friendsOnly = false, title = "转发至标签") { contacts ->
            val friends = contacts.filter { !it.group && !it.official && it.labels.isNotEmpty() }
            val groupsById = contacts.asSequence()
                .filter { it.group }
                .associateBy { it.id }
            val options = buildList {
                friends.flatMap { it.labels }.distinct().sorted().forEach { label ->
                    val targetIds = friends.asSequence()
                        .filter { label in it.labels }
                        .map { it.id }
                        .distinct()
                        .toList()
                    if (targetIds.isNotEmpty()) {
                        add(LabelTargets("好友标签 · $label", "${targetIds.size} 人", targetIds))
                    }
                }
                GroupChatLabelStore.load(context.hostContext()).forEach { label ->
                    val targetIds = label.groupIds.asSequence()
                        .filter(groupsById::containsKey)
                        .distinct()
                        .toList()
                    if (targetIds.isNotEmpty()) {
                        add(LabelTargets("群聊标签 · ${label.name}", "${targetIds.size} 个群聊", targetIds))
                    }
                }
            }
            if (options.isEmpty()) {
                toast(activity, "没有可用的标签")
                return@loadContacts
            }
            VoiceForwardMiuixDialog.showMultiChoices(
                activity = activity,
                title = "选择标签",
                summary = "",
                choices = options.map { it.title to it.summary },
                onConfirm = { selected ->
                    val targetIds = selected.asSequence()
                        .mapNotNull(options::getOrNull)
                        .flatMap { it.targetIds.asSequence() }
                        .distinct()
                        .toList()
                    onSelected(targetIds)
                },
                onDismiss = {}
            )
        }
    }

    private fun sendSnapshots(
        activity: Activity,
        snapshot: SelectedMessageSnapshot,
        targetIds: List<String>,
        channel: Int,
        title: String
    ) {
        val snapshots = listOf(snapshot)
        SelectedMessagesRuntimeCoordinator.snapshotValidationError(channel, snapshots)?.let {
            toast(activity, it)
            return
        }
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) {
            toast(activity, "请选择转发对象")
            return
        }
        var handle: SelectedMessageSendHandle? = null
        val finishing = AtomicBoolean(false)
        val progress = if (SelectedMessagesSettings.isBackgroundSilentSendEnabled(activity)) {
            null
        } else {
            VoiceForwardMiuixDialog.showLoading(
                activity = activity,
                onDismiss = { if (!finishing.get()) handle?.cancel() },
                title = title,
                message = "正在发送..."
            )
        }
        handle = SelectedMessagesRuntimeCoordinator.enqueueScheduledSnapshots(
            channel,
            snapshots,
            targets
        ) { success, total, canceled ->
            main.post {
                finishing.set(true)
                progress?.close()
                val message = when {
                    canceled -> "$title 已取消: $success/$total"
                    success == total -> "$title 完成: $success/$total"
                    else -> "$title 部分失败: $success/$total"
                }
                toast(activity, message)
            }
        }
        if (handle == null) {
            finishing.set(true)
            progress?.close()
            toast(activity, "$title 启动失败")
        }
    }

    private fun showContacts(
        activity: Activity,
        friendsOnly: Boolean,
        title: String,
        confirmText: String,
        onSelected: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit
    ) {
        loadContacts(activity, friendsOnly, title) { contacts ->
            VoiceForwardMiuixDialog.showContacts(
                activity = activity,
                contacts = contacts,
                onConfirm = onSelected,
                onDismiss = {},
                title = title,
                confirmText = confirmText,
                showGroupFilter = !friendsOnly
            )
        }
    }

    private fun loadContacts(
        activity: Activity,
        friendsOnly: Boolean,
        title: String,
        onLoaded: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit
    ) {
        val deliver: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit = deliver@ { contacts ->
            if (activity.isFinishing || activity.isDestroyed) return@deliver
            if (contacts.isEmpty()) {
                toast(activity, "没有可用联系人")
            } else {
                onLoaded(contacts)
            }
        }
        SelectedMessageContactRepository.cached(friendsOnly)?.let {
            deliver(it)
            return
        }
        val canceled = AtomicBoolean(false)
        val closingForTransition = AtomicBoolean(false)
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            onDismiss = {
                if (!closingForTransition.get()) canceled.set(true)
            },
            title = title,
            message = "正在载入联系人..."
        )
        executor.execute {
            val result = runCatching { SelectedMessageContactRepository.load(friendsOnly) }
            main.post {
                if (canceled.get()) return@post
                closingForTransition.set(true)
                loading.close()
                val decor = activity.window?.decorView ?: return@post
                decor.postOnAnimation {
                    if (canceled.get() || activity.isFinishing || activity.isDestroyed) {
                        return@postOnAnimation
                    }
                    result.onSuccess(deliver).onFailure {
                        logger("转发读取联系人失败", it)
                        toast(activity, "联系人列表不可用")
                    }
                }
            }
        }
    }

    private fun addMenuItem(menu: Any, view: View, groupId: Int): MenuItem? {
        return addIconMenuItem(
            menu = menu,
            view = view,
            groupId = groupId,
            itemId = SingleMessageMenuLocator.HCHAT_FORWARD_MENU_ITEM_ID,
            title = MENU_TITLE
        )
    }

    private fun addIconMenuItem(
        menu: Any,
        view: View?,
        groupId: Int,
        itemId: Int,
        title: String
    ): MenuItem? {
        findMenuItem(menu, itemId)?.let { return it }
        val iconRes = menuIconResId(view, "icons_filled_share")
        if (iconRes != 0) {
            val iconMethod = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "c" &&
                    types.size == 5 &&
                    types[0] == Integer.TYPE &&
                    types[1] == Integer.TYPE &&
                    types[2] == Integer.TYPE &&
                    types[3].isAssignableFrom(String::class.java) &&
                    types[4] == Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(iconMethod, menu, groupId, itemId, 0, title, iconRes)) {
                return findMenuItem(menu, itemId)
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", groupId, itemId, 0, title)
            ?: KavaReflector.invokeMethod(menu, "add", groupId, itemId, 0, title as CharSequence)
        if (added is MenuItem) {
            if (iconRes != 0) runCatching { added.setIcon(iconRes) }
            return added
        }
        if (added != null) return findMenuItem(menu, itemId)
        val fallback = KavaReflector.invokeMethod(menu, "f", itemId, title)
            ?: KavaReflector.invokeMethod(menu, "f", itemId, title as CharSequence)
        return (fallback as? MenuItem) ?: findMenuItem(menu, itemId)
    }

    private fun addPlainMenuItem(menu: Any, itemId: Int, title: String) {
        if (KavaReflector.invokeMethod(menu, "findItem", itemId) != null) return
        val added = KavaReflector.invokeMethod(
            menu,
            "add",
            0,
            itemId,
            0,
            title
        ) ?: KavaReflector.invokeMethod(
            menu,
            "add",
            0,
            itemId,
            0,
            title as CharSequence
        )
        if (added != null) return
        KavaReflector.invokeMethod(menu, "f", itemId, title)
            ?: KavaReflector.invokeMethod(
                menu,
                "f",
                itemId,
                title as CharSequence
            )
    }

    private fun moveAfterRepeat(menu: Any, item: MenuItem) {
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                val itemIndex = items.indexOfFirst { candidate ->
                    candidate === item ||
                        (candidate as? MenuItem)?.itemId == SingleMessageMenuLocator.HCHAT_FORWARD_MENU_ITEM_ID
                }
                if (itemIndex < 0) continue
                val moved = items.removeAt(itemIndex)
                val repeatIndex = items.indexOfFirst { candidate ->
                    (candidate as? MenuItem)?.itemId == SingleMessageMenuLocator.HCHAT_REPEAT_MENU_ITEM_ID
                }
                val targetIndex = if (repeatIndex >= 0) repeatIndex + 1 else 0
                items.add(targetIndex.coerceAtMost(items.size), moved)
                return
            }
            current = current.superclass
        }
    }

    private fun findMenuItem(
        menu: Any,
        itemId: Int = SingleMessageMenuLocator.HCHAT_FORWARD_MENU_ITEM_ID
    ): MenuItem? {
        return KavaReflector.invokeMethod(
            menu,
            "findItem",
            itemId
        ) as? MenuItem
    }

    private fun menuIconResId(view: View?, iconName: String): Int {
        val iconContext = view?.context ?: WeChatApis.currentActivity()?.currentActivity() ?: return 0
        val resources = iconContext.resources
        val packageName = iconContext.packageName
        for (type in arrayOf("raw", "drawable")) {
            val id = resources.getIdentifier(iconName, type, packageName)
            if (id != 0) return id
        }
        return 0
    }

    private fun readMenuGroupId(menu: Any): Int {
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: 0
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            return item.groupId
        }
        return 0
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        val tag = if (source is View) source.tag else source
        if (tag == null) return null
        if (isNativeMessageClass(tag.javaClass) && messageId(tag) > 0L) return tag
        var current: Class<*>? = tag.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (KavaReflector.isStatic(field) || !isNativeMessageClass(field.type)) continue
                val value = KavaReflector.readField(field, tag) ?: continue
                if (messageId(value) > 0L) return value
            }
            current = current.superclass
        }
        current = tag.javaClass
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (KavaReflector.isStatic(method) || method.parameterTypes.isNotEmpty()) continue
                if (!isNativeMessageClass(method.returnType)) continue
                val value = KavaReflector.invoke(method, tag) ?: continue
                if (messageId(value) > 0L) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun isNativeMessageClass(clazz: Class<*>): Boolean {
        return clazz.name.startsWith("com.tencent.mm.storage.")
    }

    private fun nativeMessageType(message: Any): Int? {
        val type = (KavaReflector.invokeMethod(message, "getType") as? Number)?.toInt()
            ?: (KavaReflector.readField(message, "field_type") as? Number)?.toInt()
            ?: (KavaReflector.readField(message, "type") as? Number)?.toInt()
            ?: return null
        return WeChatMessageTypes.normalize(type)
    }

    private fun messageId(message: Any): Long {
        messageIdMethods[message.javaClass]?.let { method ->
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        val method = KavaReflector.declaredMethods(message.javaClass).firstOrNull { candidate ->
            candidate.parameterTypes.isEmpty() &&
                candidate.name in setOf("getMsgId", "getMsgID", "getId") &&
                (candidate.returnType == java.lang.Long.TYPE || candidate.returnType == java.lang.Long::class.java)
        }
        if (method != null) {
            messageIdMethods.putIfAbsent(message.javaClass, method)
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID")) {
            (KavaReflector.readField(message, name) as? Number)?.toLong()?.let { return it }
        }
        return 0L
    }

    private fun consumeBinding(item: MenuItem): MessageBinding? {
        val binding = bindingsByItem.remove(item) ?: bindingsByGroup.remove(item.groupId)
        clearBindings()
        return binding
    }

    private fun clearBindings() {
        bindingsByItem.clear()
        bindingsByGroup.clear()
    }

    private fun currentActivity(): Activity? {
        return (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    private fun toast(activity: Activity?, message: String) {
        val target = activity?.takeUnless { it.isFinishing } ?: currentActivity() ?: return
        main.post { Toast.makeText(target, message, Toast.LENGTH_SHORT).show() }
    }

    private fun singleForwardEnabled(): Boolean {
        return prefs.getBoolean(MessageForwardSettings.KEY_ENABLE, MessageForwardSettings.DEFAULT_ENABLE)
    }

    private fun multiMomentsEnabled(): Boolean {
        return prefs.getBoolean(
            MessageForwardSettings.KEY_MULTI_MOMENTS_ENABLE,
            MessageForwardSettings.DEFAULT_ENABLE
        )
    }

    private fun favoriteForwardEnabled(): Boolean {
        return prefs.getBoolean(
            MessageForwardSettings.KEY_FAVORITE_FORWARD_ENABLE,
            MessageForwardSettings.DEFAULT_ENABLE
        )
    }

    private data class MessageBinding(val nativeMessage: Any)

    private data class MomentsPreparation(
        val intent: Intent? = null,
        val error: String = ""
    )

    private data class FavoriteShareData(
        val type: Int,
        val text: String,
        val path: String?
    )

    private data class LabelTargets(
        val title: String,
        val summary: String,
        val targetIds: List<String>
    )

    companion object {
        private const val MENU_TITLE = "转发[H]"
        private const val MULTI_MOMENTS_MENU_ITEM_ID = 0x48434d50
        private const val MULTI_MOMENTS_MENU_TITLE = "转发到朋友圈[H]"
        private const val FAVORITE_FORWARD_MENU_ITEM_ID = 0x48434641
        private const val FAVORITE_FORWARD_MENU_TITLE = "转发[H]"
        private const val FAVORITE_CLICK_DEDUP_MS = 1_500L
        private const val SNS_FORWARD_MENU_TITLE = "转发[H]"
        private const val SNS_UPLOAD_ACTIVITY = "com.tencent.mm.plugin.sns.ui.SnsUploadUI"
        private const val MAX_MOMENTS_IMAGES = 9
        private val MOMENTS_VIDEO_TYPES = setOf(WeChatMessageTypes.VIDEO, 62)
    }
}
