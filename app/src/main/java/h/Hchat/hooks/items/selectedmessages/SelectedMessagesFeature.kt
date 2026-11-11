package h.Hchat.hooks.items.selectedmessages

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.MultiSelectMessageMenuLocator
import h.Hchat.hooks.api.message.MultiSelectMessageResolver
import h.Hchat.hooks.api.message.MultiSelectMessageUi
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskRuntimeCoordinator
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class SelectedMessagesFeature : BaseFeature() {
    private var hooker: SelectedMessagesHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "群发助手"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SelectedMessagesSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = SelectedMessagesHooker(context, ::logFeatureError).also { it.attach() }
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.detach()
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "selected_messages"
    }
}

private class SelectedMessagesHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val settings = HchatStorage.preferences(context.hostContext(), SelectedMessagesSettings.PREFS_NAME)
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
    private val moduleSender = SelectedMessageModuleSender(context, logger)
    private val customModuleSender = CustomMassSendModuleSender(context.hostContext(), logger)
    private val officialSender = OfficialMassSendSender(context, logger)

    fun attach() {
        SelectedMessagesRuntimeCoordinator.attach(customModuleSender, moduleSender, officialSender)
        SelectedMessageContactRepository.warm()
    }

    fun detach() {
        SelectedMessagesRuntimeCoordinator.detach(customModuleSender)
        moduleSender.shutdown()
        customModuleSender.shutdown()
    }

    fun install(): Boolean {
        val menuCreate = MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)
        val menuClick = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
        val exitMethod = menuClick?.let { MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger) }
        val createHooked = menuCreate != null && exitMethod != null && hook(menuCreate, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                addMenu(param)
            }
        })
        val clickHooked = menuClick != null && exitMethod != null && hook(menuClick, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                handleMenuClick(param, exitMethod)
            }
        })
        val doneMethods = locateRetransmitDoneMethods()
        val doneHooked = doneMethods.isNotEmpty() && doneMethods.all { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as? Activity ?: return
                    if (moduleSender.handleRetransmitDone(activity)) param.result = null
                }
            })
        }
        val officialHooked = officialSender.install()
        if (!createHooked) logger("群发助手菜单创建Hook未安装", null)
        if (!clickHooked) logger("群发助手菜单点击Hook未安装", null)
        if (!doneHooked) logger("群发助手重发完成Hook未安装", null)
        if (!officialHooked) logger("微信原生群发助手通道未安装", null)
        return createHooked && clickHooked && doneHooked && officialHooked
    }

    private fun addMenu(param: XC_MethodHook.MethodHookParam) {
        if (!isEnabled()) return
        if (MultiSelectMessageResolver.resolve(param.thisObject).isEmpty()) return
        val menu = param.args?.getOrNull(0) ?: return
        addMenuItem(menu, MENU_MASS_SEND_ID, MENU_MASS_SEND_TITLE)
        addMenuItem(menu, MENU_SCHEDULE_ID, MENU_SCHEDULE_TITLE)
    }

    private fun addMenuItem(menu: Any, itemId: Int, title: String) {
        if (KavaReflector.invokeMethod(menu, "findItem", itemId) != null) return
        KavaReflector.invokeMethod(menu, "add", 0, itemId, 0, title)
            ?: KavaReflector.invokeMethod(menu, "add", 0, itemId, 0, title as CharSequence)
            ?: KavaReflector.invokeMethod(menu, "f", itemId, title)
            ?: KavaReflector.invokeMethod(menu, "f", itemId, title as CharSequence)
    }

    private fun handleMenuClick(param: XC_MethodHook.MethodHookParam, exitMethod: Method) {
        if (!isEnabled()) return
        val item = param.args?.getOrNull(0) as? MenuItem ?: return
        val action = when (item.itemId) {
            MENU_MASS_SEND_ID -> MenuAction.MASS_SEND
            MENU_SCHEDULE_ID -> MenuAction.SCHEDULE
            else -> return
        }
        param.result = null
        val activity = currentActivity()
        if (activity == null) return
        val selected = MultiSelectMessageResolver.resolve(param.thisObject)
        val snapshots = selected.mapNotNull { SelectedMessageSnapshot.fromNative(it) }
            .sortedWith(compareBy<SelectedMessageSnapshot> { it.createTime }.thenBy { it.msgId })
        if (selected.isEmpty() || snapshots.size != selected.size) {
            toast(activity, "部分选中消息暂不支持发送")
            return
        }
        val exitTarget = MultiSelectMessageUi.resolveExitTarget(param.thisObject, exitMethod, logger)
        if (exitTarget == null) {
            toast(activity, "无法退出多选状态，请稍后重试")
            return
        }
        main.post {
            if (activity.isFinishing) return@post
            when (action) {
                MenuAction.MASS_SEND -> chooseMassSendChannel(activity, snapshots, exitTarget)
                MenuAction.SCHEDULE -> chooseScheduleChannel(activity, snapshots, exitTarget)
            }
        }
    }

    private fun chooseScheduleChannel(
        activity: Activity,
        snapshots: List<SelectedMessageSnapshot>,
        exitTarget: MultiSelectMessageUi.ExitTarget
    ) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "选择定时转发通道",
            summary = "已选 ${snapshots.size} 条消息",
            choices = listOf(
                "模块通道" to "支持好友、群聊、公众号和标签",
                "微信原生群发助手" to "仅选择好友并按原生队列发送"
            ),
            onSelected = { index ->
                if (activity.isFinishing) return@showChoices
                val channel = if (index == 1) {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
                } else {
                    SelectedMessagesRuntimeCoordinator.CHANNEL_MODULE
                }
                SelectedMessagesRuntimeCoordinator.snapshotValidationError(channel, snapshots)?.let {
                    toast(activity, it)
                    return@showChoices
                }
                chooseScheduleTargets(activity, snapshots, exitTarget, channel)
            },
            onDismiss = {}
        )
    }

    private fun chooseScheduleTargets(
        activity: Activity,
        snapshots: List<SelectedMessageSnapshot>,
        exitTarget: MultiSelectMessageUi.ExitTarget,
        channel: Int
    ) {
        val official = channel == SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL
        showContacts(
            activity,
            friendsOnly = official,
            title = if (official) "选择定时群发好友" else "选择定时发送对象",
            confirmText = "下一步"
        ) { targets ->
            val initialPlanTime = Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }.timeInMillis
            showScheduleRepeatPicker(activity, initialPlanTime) { repeatType, repeatDays ->
                showDateTimePicker(activity) { pickedTime ->
                    val planTime = normalizeSchedulePlanTime(pickedTime, repeatType, repeatDays)
                    if (planTime <= 0L) {
                        toast(activity, "计划时间需要晚于当前时间")
                        return@showDateTimePicker
                    }
                    val settings = ScheduledTaskSettings(context.hostContext())
                    val task = ScheduledTaskSettings.newDraft().copy(
                        remark = MENU_SCHEDULE_TITLE,
                        type = ScheduledTaskSettings.TYPE_SELECTED_MESSAGE,
                        items = snapshots.map {
                            ScheduledTaskContentItem(ScheduledTaskSettings.TYPE_SELECTED_MESSAGE, it.encode())
                        },
                        targetIds = targets.map { it.id },
                        planTime = planTime,
                        planTimes = listOf(planTime),
                        repeatType = repeatType,
                        repeatDays = repeatDays,
                        sendChannel = channel
                    )
                    settings.setEnabled(true)
                    settings.saveTask(ScheduledTaskSettings.normalizeForSave(task))
                    ScheduledTaskRuntimeCoordinator.reload()
                    exitTarget.exit(logger)
                    toast(activity, "定时任务已保存")
                }
            }
        }
    }

    private fun normalizeSchedulePlanTime(
        pickedTime: Long,
        repeatType: Int,
        repeatDays: Set<Int>
    ): Long {
        val now = System.currentTimeMillis()
        if (repeatType == ScheduledTaskSettings.REPEAT_NONE) {
            return pickedTime.takeIf { it > now } ?: 0L
        }
        var candidate = pickedTime
        if (repeatType == ScheduledTaskSettings.REPEAT_WEEKLY) {
            if (repeatDays.isEmpty()) return 0L
            val pickedDay = Calendar.getInstance().apply { timeInMillis = candidate }
                .get(Calendar.DAY_OF_WEEK)
            if (pickedDay !in repeatDays) {
                candidate = ScheduledTaskSettings.calculateNextPlanTime(
                    candidate,
                    repeatType,
                    repeatDays
                )
            }
        }
        return ScheduledTaskSettings.resolveNextPlanTime(
            candidate,
            repeatType,
            repeatDays,
            now
        ).takeIf { it > now } ?: 0L
    }

    private fun showScheduleRepeatPicker(
        activity: Activity,
        planTime: Long,
        onPicked: (repeatType: Int, repeatDays: Set<Int>) -> Unit
    ) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "选择重复方式",
            summary = "",
            choices = listOf(
                "单次" to "",
                "每天" to "",
                "每周" to ""
            ),
            onSelected = { index ->
                when (index) {
                    1 -> onPicked(ScheduledTaskSettings.REPEAT_DAILY, emptySet())
                    2 -> showScheduleWeekdayPicker(activity, planTime, onPicked)
                    else -> onPicked(ScheduledTaskSettings.REPEAT_NONE, emptySet())
                }
            },
            onDismiss = {}
        )
    }

    private fun showScheduleWeekdayPicker(
        activity: Activity,
        planTime: Long,
        onPicked: (repeatType: Int, repeatDays: Set<Int>) -> Unit
    ) {
        val weekdays = listOf(
            Calendar.MONDAY to "周一",
            Calendar.TUESDAY to "周二",
            Calendar.WEDNESDAY to "周三",
            Calendar.THURSDAY to "周四",
            Calendar.FRIDAY to "周五",
            Calendar.SATURDAY to "周六",
            Calendar.SUNDAY to "周日"
        )
        val plannedDay = Calendar.getInstance().apply { timeInMillis = planTime }
            .get(Calendar.DAY_OF_WEEK)
        val initialSelected = weekdays.indices.filter { weekdays[it].first == plannedDay }.toSet()
        VoiceForwardMiuixDialog.showMultiChoices(
            activity = activity,
            title = "选择重复日期",
            summary = "",
            choices = weekdays.map { it.second to "" },
            initialSelected = initialSelected,
            onConfirm = { selected ->
                val days = selected.mapNotNull { weekdays.getOrNull(it)?.first }.toSet()
                if (days.isNotEmpty()) {
                    onPicked(ScheduledTaskSettings.REPEAT_WEEKLY, days)
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseMassSendChannel(
        activity: Activity,
        snapshots: List<SelectedMessageSnapshot>,
        exitTarget: MultiSelectMessageUi.ExitTarget
    ) {
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = "选择群发通道",
            summary = "已选 ${snapshots.size} 条消息",
            choices = listOf(
                "模块通道" to "支持好友、群聊、公众号和好友标签",
                "微信原生群发助手" to "按微信当前人数上限自动分批"
            ),
            onSelected = { index ->
                if (activity.isFinishing) return@showChoices
                if (index == 0) {
                    chooseModuleTargets(activity, snapshots, exitTarget)
                } else {
                    chooseOfficialTargets(activity, snapshots, exitTarget)
                }
            },
            onDismiss = {}
        )
    }

    private fun chooseModuleTargets(
        activity: Activity,
        snapshots: List<SelectedMessageSnapshot>,
        exitTarget: MultiSelectMessageUi.ExitTarget
    ) {
        showContacts(activity, friendsOnly = false, title = "选择群发对象", confirmText = "发送") { targets ->
            var sendHandle: SelectedMessageSendHandle? = null
            val finishing = AtomicBoolean(false)
            val progress = if (SelectedMessagesSettings.isBackgroundSilentSendEnabled(activity)) {
                null
            } else {
                VoiceForwardMiuixDialog.showLoading(
                    activity = activity,
                    onDismiss = { if (!finishing.get()) sendHandle?.cancel() },
                    title = "模块群发",
                    message = "正在发送..."
                )
            }
            sendHandle = moduleSender.enqueueMassSend(snapshots, targets.map { it.id }) { success, total, canceled ->
                main.post {
                    finishing.set(true)
                    progress?.close()
                    val message = when {
                        canceled -> "模块群发已取消: $success/$total"
                        success == total -> "模块群发完成: $success/$total"
                        else -> "模块群发部分失败: $success/$total"
                    }
                    toast(activity, message)
                }
            }
            if (sendHandle == null) {
                finishing.set(true)
                progress?.close()
                toast(activity, "模块群发启动失败")
                return@showContacts
            }
            exitTarget.exit(logger)
            toast(activity, "已开始模块群发")
        }
    }

    private fun chooseOfficialTargets(
        activity: Activity,
        snapshots: List<SelectedMessageSnapshot>,
        exitTarget: MultiSelectMessageUi.ExitTarget
    ) {
        val unsupported = officialSender.unsupportedLabels(snapshots)
        if (unsupported.isNotEmpty()) {
            toast(activity, "原生群发不支持: ${unsupported.joinToString("、")}")
            return
        }
        officialSender.preparationError(snapshots)?.let {
            toast(activity, it)
            return
        }
        showContacts(activity, friendsOnly = true, title = "选择官方群发好友", confirmText = "发送") { targets ->
            var sendHandle: SelectedMessageSendHandle? = null
            val finishing = AtomicBoolean(false)
            val progress = if (SelectedMessagesSettings.isBackgroundSilentSendEnabled(activity)) {
                null
            } else {
                VoiceForwardMiuixDialog.showLoading(
                    activity = activity,
                    onDismiss = { if (!finishing.get()) sendHandle?.cancel() },
                    title = "微信原生群发助手",
                    message = "正在发送..."
                )
            }
            sendHandle = officialSender.enqueue(snapshots, targets.map { it.id }) { success, total, canceled ->
                main.post {
                    finishing.set(true)
                    progress?.close()
                    val message = when {
                        canceled -> "原生群发已取消: $success/$total"
                        success == total -> "原生群发完成: $success/$total"
                        else -> "原生群发部分失败: $success/$total"
                    }
                    toast(activity, message)
                }
            }
            if (sendHandle == null) {
                finishing.set(true)
                progress?.close()
                toast(activity, "微信原生群发助手启动失败")
                return@showContacts
            }
            exitTarget.exit(logger)
            toast(activity, "已开始微信原生群发")
        }
    }

    private fun showContacts(
        activity: Activity,
        friendsOnly: Boolean,
        title: String,
        confirmText: String,
        onSelected: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit
    ) {
        val showLoaded: (List<VoiceForwardMiuixDialog.ContactItem>) -> Unit = showLoaded@ { contacts ->
            if (activity.isFinishing || activity.isDestroyed) return@showLoaded
            if (contacts.isEmpty()) {
                toast(activity, "没有可用联系人")
            } else {
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
        SelectedMessageContactRepository.cached(friendsOnly)?.let {
            showLoaded(it)
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
        Thread({
            val result = runCatching { SelectedMessageContactRepository.load(friendsOnly) }
            main.post {
                if (canceled.get()) return@post
                closingForTransition.set(true)
                loading.close()
                val decor = activity.window?.decorView
                if (activity.isFinishing || activity.isDestroyed || decor == null) return@post
                decor.postOnAnimation {
                    if (canceled.get() || activity.isFinishing || activity.isDestroyed) return@postOnAnimation
                    result.onSuccess { contacts ->
                        showLoaded(contacts)
                    }.onFailure {
                        logger("群发助手读取联系人失败", it)
                        toast(activity, "联系人列表不可用")
                    }
                }
            }
        }, "Hchat-SelectedMessageContacts").start()
    }

    private fun showDateTimePicker(activity: Activity, onPicked: (Long) -> Unit) {
        val initial = Calendar.getInstance().apply { add(Calendar.MINUTE, 5) }
        DatePickerDialog(
            activity,
            { _, year, month, day ->
                TimePickerDialog(
                    activity,
                    { _, hour, minute ->
                        showSecondPicker(activity, initial.get(Calendar.SECOND)) { second ->
                            val picked = Calendar.getInstance().apply {
                                set(year, month, day, hour, minute, second)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            onPicked(picked)
                        }
                    },
                    initial.get(Calendar.HOUR_OF_DAY),
                    initial.get(Calendar.MINUTE),
                    true
                ).show()
            },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showSecondPicker(activity: Activity, initialSecond: Int, onPicked: (Int) -> Unit) {
        val showDialog: () -> Unit = {
            VoiceForwardMiuixDialog.showNumberInput(
                activity = activity,
                title = "设置秒数",
                initialValue = initialSecond,
                minValue = 0,
                maxValue = 59,
                onConfirm = onPicked,
                onDismiss = {}
            )
        }
        activity.window?.decorView?.postOnAnimation {
            if (!activity.isFinishing && !activity.isDestroyed) showDialog()
        } ?: showDialog()
    }

    private fun locateRetransmitDoneMethods(): List<Method> {
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.loadList(
            methodPrefs,
            cacheKey,
            context.hostClassLoader(),
            CACHE_RETRANSMIT_DONE
        ).filter(::isRetransmitDoneMethod)
        if (cached.isNotEmpty()) return cached
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        declaredClass(SelectedMessageModuleSender.MSG_RETRANSMIT_UI)
                        usingStrings(listOf("sendResult", "SendMsgUsernames"))
                    })
                }
            ).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isRetransmitDoneMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure { logger("定位群发助手重发完成方法失败", it) }.getOrDefault(emptyList())
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, cacheKey, CACHE_RETRANSMIT_DONE, methods)
        }
        return methods
    }

    private fun isRetransmitDoneMethod(method: Method): Boolean {
        return method.declaringClass.name == SelectedMessageModuleSender.MSG_RETRANSMIT_UI &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java))
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.onFailure {
            hookedMethods.remove(method)
            logger("群发助手Hook安装失败: ${method.toGenericString()}", it)
        }.getOrDefault(false)
    }

    private fun currentActivity(): Activity? {
        return (WeChatApis.currentActivity()?.currentActivity() as? Activity)?.takeUnless { it.isFinishing }
    }

    private fun toast(activity: Activity?, message: String) {
        val target = activity?.takeUnless { it.isFinishing } ?: currentActivity() ?: return
        main.post { Toast.makeText(target, message, Toast.LENGTH_SHORT).show() }
    }

    private fun isEnabled(): Boolean {
        return settings.getBoolean(SelectedMessagesSettings.KEY_ENABLE, SelectedMessagesSettings.DEFAULT_ENABLE)
    }

    companion object {
        private const val PREFS_NAME = "Hchat_selected_message_method_cache"
        private const val CACHE_RETRANSMIT_DONE = "retransmit_done_v1"
        private const val MENU_MASS_SEND_ID = 0x4843534d
        private const val MENU_SCHEDULE_ID = 0x48435354
        private const val MENU_MASS_SEND_TITLE = "群发助手[H]"
        private const val MENU_SCHEDULE_TITLE = "定时转发[H]"
    }

    private enum class MenuAction {
        MASS_SEND,
        SCHEDULE
    }
}
