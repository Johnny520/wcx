package h.Hchat.hooks.items.scheduledtask

import android.os.PowerManager
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatMediaApi
import h.Hchat.hooks.api.message.WeChatMessageApi
import h.Hchat.hooks.api.runtime.WeChatTaskApi
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSendHandle
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSnapshot
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesRuntimeCoordinator
import h.Hchat.hooks.items.selectedmessages.SelectedMessagesSettings
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

class ScheduledTaskFeature : BaseFeature() {
    private var runtime: ScheduledTaskRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "定时任务"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ScheduledTaskSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = ScheduledTaskRuntime(context, ::logFeatureError).also {
            ScheduledTaskRuntimeCoordinator.attach(it)
            it.start()
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        ScheduledTaskRuntimeCoordinator.detach(runtime)
        runtime?.shutdown()
        runtime = null
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "scheduled_task"
    }
}

object ScheduledTaskRuntimeCoordinator {
    @Volatile
    private var runtime: ScheduledTaskRuntime? = null

    fun attach(value: ScheduledTaskRuntime) {
        runtime = value
    }

    fun detach(value: ScheduledTaskRuntime?) {
        if (runtime === value) runtime = null
    }

    fun reload() {
        runtime?.reload()
    }

    fun executeNow(taskId: String): Boolean {
        return runtime?.executeNow(taskId) == true
    }
}

class ScheduledTaskRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val settings = ScheduledTaskSettings(context.hostContext())
    private val scheduled = ConcurrentHashMap<String, Boolean>()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        reload()
    }

    fun shutdown() {
        started = false
        cancelAll()
    }

    @Synchronized
    fun reload() {
        if (!started) return
        cancelAll()
        if (!settings.isEnabled()) return
        val now = System.currentTimeMillis()
        val nextTasks = ArrayList<ScheduledTaskItem>()
        var changed = false
        settings.tasks().forEach { stored ->
            var task = withPlanTimes(stored, ScheduledTaskSettings.normalizedPlanTimes(stored))
            if (task != stored) changed = true
            if (task.status == ScheduledTaskSettings.STATUS_RUNNING) {
                task = task.copy(status = ScheduledTaskSettings.STATUS_PENDING)
                changed = true
            }
            if (task.planTime <= 0L) {
                changed = true
                return@forEach
            }
            if (task.repeatType == ScheduledTaskSettings.REPEAT_NONE) {
                val eligibleTimes = task.planTimes.filter { planTime ->
                    planTime > now ||
                        (task.sendOnTimeout && now - planTime < SINGLE_TIMEOUT_WINDOW_MS)
                }
                if (eligibleTimes != task.planTimes) {
                    changed = true
                    if (eligibleTimes.isEmpty()) return@forEach
                    task = withPlanTimes(task, eligibleTimes)
                }
                if (now >= task.planTime) {
                    if (task.sendOnTimeout) {
                        nextTasks += task
                        schedule(task, 1000L)
                    }
                } else {
                    nextTasks += task
                    schedule(task, task.planTime - now)
                }
                return@forEach
            }
            if (now >= task.planTime) {
                if (task.sendOnTimeout) {
                    nextTasks += task
                    schedule(task, 1000L)
                } else {
                    val nextPlanTimes = task.planTimes.map { planTime ->
                        ScheduledTaskSettings.resolveNextPlanTime(
                            planTime,
                            task.repeatType,
                            task.repeatDays,
                            now
                        )
                    }.filter { it > now }
                    if (nextPlanTimes.isNotEmpty()) {
                        task = withPlanTimes(task, nextPlanTimes)
                            .copy(status = ScheduledTaskSettings.STATUS_PENDING)
                        nextTasks += task
                        schedule(task, task.planTime - now)
                        changed = true
                    } else {
                        changed = true
                    }
                }
            } else {
                nextTasks += task
                schedule(task, task.planTime - now)
            }
        }
        if (changed) settings.saveTasks(nextTasks)
    }

    @Synchronized
    fun executeNow(taskId: String): Boolean {
        if (!settings.isEnabled()) return false
        val task = settings.findTask(taskId) ?: return false
        if (task.status == ScheduledTaskSettings.STATUS_RUNNING) return false
        cancel(taskId)
        val running = ScheduledTaskSettings.normalizeForSave(task)
            .copy(status = ScheduledTaskSettings.STATUS_RUNNING)
        settings.saveTask(running)
        val job = Runnable {
            val result = runCatching { sendTask(running) }.getOrElse {
                logger("定时任务立即执行异常", it)
                0 to expectedTargetCount(running)
            }
            finishImmediateTask(running, result.first, result.second)
        }
        taskApi()?.runAsync(job) ?: Thread(job, "Hchat-ScheduledTask-Now-$taskId").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun schedule(task: ScheduledTaskItem, delayMs: Long) {
        val taskApi = taskApi() ?: run {
            logger("定时任务公共调度不可用", null)
            return
        }
        val key = scheduleKey(task.id)
        cancel(task.id)
        scheduled[key] = true
        val now = System.currentTimeMillis()
        val triggerAtMillis = if (task.planTime > now) {
            task.planTime
        } else {
            now + delayMs.coerceAtLeast(0L)
        }
        taskApi.runOnMainAtExact(key, triggerAtMillis) {
            execute(task.id, task.planTime)
        }
    }

    private fun execute(taskId: String, expectedPlanTime: Long) {
        if (!settings.isEnabled()) return
        val task = settings.findTask(taskId) ?: return
        if (task.planTime != expectedPlanTime || task.status == ScheduledTaskSettings.STATUS_RUNNING) return
        val now = System.currentTimeMillis()
        if (now < task.planTime) {
            schedule(task, task.planTime - now)
            return
        }
        cancel(taskId)
        if (!shouldExecuteOccurrence(task, now)) {
            finishTask(task, 0, 0)
            return
        }
        val running = task.copy(status = ScheduledTaskSettings.STATUS_RUNNING)
        settings.saveTask(running)
        val wakeLock = acquireExecutionWakeLock()
        val job = Runnable {
            try {
                val result = runCatching { sendTask(running) }.getOrElse {
                    logger("定时任务执行异常", it)
                    0 to expectedTargetCount(running)
                }
                finishTask(running, result.first, result.second)
            } finally {
                releaseExecutionWakeLock(wakeLock)
            }
        }
        taskApi()?.runAsync(job) ?: Thread(job, "Hchat-ScheduledTask-$taskId").apply {
            isDaemon = true
            start()
        }
    }

    private fun sendTask(task: ScheduledTaskItem): Pair<Int, Int> {
        if (task.targetType == ScheduledTaskSettings.TARGET_MOMENTS) {
            return if (sendMomentsTask(task)) 1 to 0 else 0 to 1
        }
        val items = ScheduledTaskSettings.normalizedItems(task)
        if (items.any { it.type == ScheduledTaskSettings.TYPE_SELECTED_MESSAGE }) {
            if (items.any { it.type != ScheduledTaskSettings.TYPE_SELECTED_MESSAGE }) {
                logger("自选聊天记录定时任务不能与普通内容混合", null)
                return 0 to expectedTargetCount(task)
            }
            val snapshots = items.mapNotNull { SelectedMessageSnapshot.decode(it.value) }
            if (snapshots.size != items.size || snapshots.isEmpty()) {
                logger("自选聊天记录定时任务快照无效", null)
                return 0 to expectedTargetCount(task)
            }
            return sendSnapshotsTask(task, snapshots)
        }
        if (task.sendChannel == ScheduledTaskSettings.SEND_CHANNEL_OFFICIAL) {
            return sendOfficialTask(task, items)
        }
        val messageApi = WeChatApis.message().sender() ?: WeChatApis.messages()
        val mediaApi = WeChatApis.media()
        val globalIntervalSeconds =
            SelectedMessagesSettings.sendIntervalSeconds(context.hostContext())
        val targetIntervalSeconds = maxOf(task.intervalSeconds, globalIntervalSeconds)
        val itemIntervalSeconds = maxOf(task.mediaIntervalSeconds, globalIntervalSeconds)
        var success = 0
        var fail = 0
        task.targetIds.forEachIndexed { index, talker ->
            val ok = sendToTalker(
                task,
                talker,
                messageApi,
                mediaApi,
                itemIntervalSeconds
            )
            if (ok) success++ else fail++
            if (index < task.targetIds.lastIndex) {
                sleep(targetIntervalSeconds * 1000L)
            }
        }
        return success to fail
    }

    private fun sendSnapshotsTask(
        task: ScheduledTaskItem,
        snapshots: List<SelectedMessageSnapshot>
    ): Pair<Int, Int> {
        SelectedMessagesRuntimeCoordinator.snapshotValidationError(
            task.sendChannel,
            snapshots
        )?.let {
            logger("定时转发配置无效: $it", null)
            return 0 to expectedTargetCount(task)
        }
        return awaitChannelSend(
            snapshots.size,
            task.targetIds.size,
            channelSendTimeoutMinutes(task, snapshots.size, task.targetIds.size)
        ) { callback ->
            SelectedMessagesRuntimeCoordinator.enqueueScheduledSnapshots(
                task.sendChannel,
                snapshots,
                task.targetIds,
                task.intervalSeconds,
                task.mediaIntervalSeconds,
                callback
            )
        }
    }

    private fun sendOfficialTask(
        task: ScheduledTaskItem,
        items: List<ScheduledTaskContentItem>
    ): Pair<Int, Int> {
        SelectedMessagesRuntimeCoordinator.validationError(
            SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL,
            items
        )?.let {
            logger("微信原生定时任务配置无效: $it", null)
            return 0 to expectedTargetCount(task)
        }
        return awaitChannelSend(
            items.size,
            task.targetIds.size,
            channelSendTimeoutMinutes(task, items.size, task.targetIds.size)
        ) { callback ->
            SelectedMessagesRuntimeCoordinator.enqueueScheduledItems(
                SelectedMessagesRuntimeCoordinator.CHANNEL_OFFICIAL,
                items,
                task.targetIds,
                task.intervalSeconds,
                task.mediaIntervalSeconds,
                callback
            )
        }
    }

    private fun awaitChannelSend(
        itemCount: Int,
        targetCount: Int,
        timeoutMinutes: Long,
        start: (((Int, Int, Boolean) -> Unit) -> SelectedMessageSendHandle?)
    ): Pair<Int, Int> {
        if (itemCount <= 0 || targetCount <= 0) return 0 to targetCount.coerceAtLeast(1)
        val latch = CountDownLatch(1)
        val successOperations = AtomicInteger(0)
        val handle = start { success, _, _ ->
            successOperations.set(success.coerceAtLeast(0))
            latch.countDown()
        } ?: return 0 to targetCount
        val completed = try {
            latch.await(timeoutMinutes, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed) {
            handle.cancel()
            logger("定时任务等待发送通道完成超时", null)
            return 0 to targetCount
        }
        val successTargets = (successOperations.get() / itemCount).coerceIn(0, targetCount)
        return successTargets to (targetCount - successTargets)
    }

    private fun channelSendTimeoutMinutes(
        task: ScheduledTaskItem,
        itemCount: Int,
        targetCount: Int
    ): Long {
        val hostContext = context.hostContext()
        val globalSendInterval = SelectedMessagesSettings.sendIntervalSeconds(hostContext).toLong()
        val itemInterval = maxOf(task.mediaIntervalSeconds.toLong(), globalSendInterval)
        val targetInterval = if (task.sendChannel == ScheduledTaskSettings.SEND_CHANNEL_OFFICIAL) {
            maxOf(
                task.intervalSeconds.toLong(),
                SelectedMessagesSettings.officialIntervalMinutes(hostContext) * 60L
            )
        } else {
            maxOf(task.intervalSeconds.toLong(), globalSendInterval)
        }
        val itemWaits = targetCount.coerceAtLeast(1).toLong() *
            (itemCount - 1).coerceAtLeast(0).toLong()
        val targetWaits = (targetCount - 1).coerceAtLeast(0).toLong()
        val delayMinutes = (itemWaits * itemInterval + targetWaits * targetInterval + 59L) / 60L
        return (CHANNEL_SEND_TIMEOUT_MINUTES + delayMinutes)
            .coerceAtMost(MAX_CHANNEL_SEND_TIMEOUT_MINUTES)
    }

    private fun sendMomentsTask(task: ScheduledTaskItem): Boolean {
        ScheduledTaskSettings.momentsValidationError(task)?.let {
            logger("朋友圈定时任务配置无效: $it", null)
            return false
        }
        val snsApi = WeChatApis.snsApi()
        if (snsApi == null || !snsApi.isAvailable) {
            logger("朋友圈定时任务公共 API 不可用", null)
            return false
        }
        val items = ScheduledTaskSettings.normalizedMomentsItems(task)
        val content = items.firstOrNull { it.type == ScheduledTaskSettings.TYPE_TEXT }
            ?.value.orEmpty()
        val images = items.filter { it.type == ScheduledTaskSettings.TYPE_IMAGE }.map { it.value }
        val video = items.firstOrNull { it.type == ScheduledTaskSettings.TYPE_VIDEO }?.value.orEmpty()
        return when (task.momentsType) {
            ScheduledTaskSettings.MOMENTS_TEXT -> snsApi.uploadText(content)
            ScheduledTaskSettings.MOMENTS_TEXT_IMAGE -> snsApi.uploadTextAndPicList(content, images)
            ScheduledTaskSettings.MOMENTS_TEXT_VIDEO -> snsApi.uploadTextAndVideo(content, video)
            ScheduledTaskSettings.MOMENTS_IMAGE -> snsApi.uploadTextAndPicList("", images)
            ScheduledTaskSettings.MOMENTS_VIDEO -> snsApi.uploadVideo(video)
            else -> false
        }
    }

    private fun sendToTalker(
        task: ScheduledTaskItem,
        talker: String,
        messageApi: WeChatMessageApi?,
        mediaApi: WeChatMediaApi?,
        itemIntervalSeconds: Int
    ): Boolean {
        val items = ScheduledTaskSettings.normalizedItems(task)
        if (items.isEmpty()) return false
        val displayName = displayName(talker)
        items.forEachIndexed { index, item ->
            if (!sendItem(item, talker, displayName, messageApi, mediaApi)) return false
            if (index < items.lastIndex) {
                sleep(itemIntervalSeconds * 1000L)
            }
        }
        return true
    }

    private fun sendItem(
        item: ScheduledTaskContentItem,
        talker: String,
        displayName: String,
        messageApi: WeChatMessageApi?,
        mediaApi: WeChatMediaApi?
    ): Boolean {
        return when (item.type) {
            ScheduledTaskSettings.TYPE_TEXT -> {
                if (messageApi == null) return false
                val content = item.value.replace("%friendName%", displayName)
                if (content.isBlank()) return false
                messageApi.sendText(talker, content)
            }
            ScheduledTaskSettings.TYPE_XML -> {
                if (messageApi == null) return false
                val content = item.value.replace("%friendName%", displayName)
                if (content.isBlank()) return false
                messageApi.sendXml(talker, content)
            }
            ScheduledTaskSettings.TYPE_FAVORITE -> {
                mediaApi?.favorites()?.send(talker, item.value) == true
            }
            ScheduledTaskSettings.TYPE_IMAGE,
            ScheduledTaskSettings.TYPE_VIDEO,
            ScheduledTaskSettings.TYPE_FILE,
            ScheduledTaskSettings.TYPE_EMOJI,
            ScheduledTaskSettings.TYPE_VOICE -> {
                if (mediaApi == null) return false
                val file = File(item.value)
                if (!file.isFile) return false
                when (item.type) {
                    ScheduledTaskSettings.TYPE_IMAGE -> mediaApi.sendImage(talker, item.value)
                    ScheduledTaskSettings.TYPE_VIDEO -> mediaApi.videos().send(talker, item.value)
                    ScheduledTaskSettings.TYPE_FILE -> mediaApi.sendFile(talker, item.value, file.name)
                    ScheduledTaskSettings.TYPE_EMOJI -> mediaApi.sendEmoji(talker, item.value)
                    ScheduledTaskSettings.TYPE_VOICE -> mediaApi.sendVoice(talker, item.value)
                    else -> false
                }
            }
            else -> false
        }
    }

    @Synchronized
    private fun finishTask(task: ScheduledTaskItem, success: Int, fail: Int) {
        val latest = settings.findTask(task.id) ?: return
        val latestTimes = ScheduledTaskSettings.normalizedPlanTimes(latest)
        if (task.planTime !in latestTimes) {
            if (latest.status == ScheduledTaskSettings.STATUS_RUNNING) {
                settings.saveTask(latest.copy(status = ScheduledTaskSettings.STATUS_PENDING))
            }
            reload()
            return
        }
        if (latest.repeatType == ScheduledTaskSettings.REPEAT_NONE) {
            val remaining = latestTimes.filterNot { it == task.planTime }
            if (remaining.isEmpty()) {
                settings.deleteTask(task.id)
            } else {
                settings.saveTask(
                    withPlanTimes(latest, remaining).copy(
                        status = ScheduledTaskSettings.STATUS_PENDING,
                        lastExecutedTime = System.currentTimeMillis(),
                        lastSuccessCount = success,
                        lastFailCount = fail
                    )
                )
                reload()
            }
            return
        }
        val nextPlan = ScheduledTaskSettings.resolveNextPlanTime(
            task.planTime,
            latest.repeatType,
            latest.repeatDays,
            System.currentTimeMillis()
        )
        if (nextPlan <= 0L) {
            settings.deleteTask(task.id)
            return
        }
        val nextPlanTimes = latestTimes.map { planTime ->
            if (planTime == task.planTime) nextPlan else planTime
        }
        val updated = ScheduledTaskSettings.normalizeForSave(
            withPlanTimes(latest, nextPlanTimes).copy(
                status = ScheduledTaskSettings.STATUS_PENDING,
                lastExecutedTime = System.currentTimeMillis(),
                lastSuccessCount = success,
                lastFailCount = fail
            )
        )
        settings.saveTask(updated)
        reload()
    }

    @Synchronized
    private fun finishImmediateTask(task: ScheduledTaskItem, success: Int, fail: Int) {
        val latest = settings.findTask(task.id) ?: return
        settings.saveTask(
            latest.copy(
                status = ScheduledTaskSettings.STATUS_PENDING,
                lastExecutedTime = System.currentTimeMillis(),
                lastSuccessCount = success,
                lastFailCount = fail
            )
        )
        reload()
    }

    private fun withPlanTimes(task: ScheduledTaskItem, values: List<Long>): ScheduledTaskItem {
        val planTimes = values.filter { it > 0L }.distinct().sorted()
        return task.copy(
            planTime = planTimes.firstOrNull() ?: 0L,
            planTimes = planTimes
        )
    }

    private fun displayName(talker: String): String {
        return runCatching {
            WeChatApis.contact().contacts()?.getDisplayName(talker)
        }.getOrNull().orEmpty().ifBlank { talker }
    }

    private fun expectedTargetCount(task: ScheduledTaskItem): Int {
        return if (task.targetType == ScheduledTaskSettings.TARGET_MOMENTS) {
            1
        } else {
            task.targetIds.size.coerceAtLeast(1)
        }
    }

    private fun cancelAll() {
        scheduled.keys.toList().forEach { cancelScheduledKey(it) }
    }

    private fun cancel(taskId: String) {
        cancelScheduledKey(scheduleKey(taskId))
    }

    private fun cancelScheduledKey(key: String) {
        scheduled.remove(key)
        taskApi()?.cancel(key)
    }

    private fun taskApi(): WeChatTaskApi? {
        return WeChatApis.runtime().tasks() ?: WeChatApis.tasks()
    }

    private fun shouldExecuteOccurrence(task: ScheduledTaskItem, now: Long): Boolean {
        val lateness = (now - task.planTime).coerceAtLeast(0L)
        if (lateness <= ON_TIME_GRACE_MS) return true
        if (!task.sendOnTimeout) return false
        return task.repeatType != ScheduledTaskSettings.REPEAT_NONE ||
            lateness < SINGLE_TIMEOUT_WINDOW_MS
    }

    private fun acquireExecutionWakeLock(): PowerManager.WakeLock? {
        return runCatching {
            val powerManager = context.hostContext().getSystemService(PowerManager::class.java)
                ?: return null
            powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                EXECUTION_WAKE_LOCK_TAG
            ).apply {
                setReferenceCounted(false)
                acquire(EXECUTION_WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrElse {
            logger("定时任务 WakeLock 获取失败", it)
            null
        }
    }

    private fun releaseExecutionWakeLock(wakeLock: PowerManager.WakeLock?) {
        if (wakeLock == null) return
        runCatching {
            if (wakeLock.isHeld) wakeLock.release()
        }.onFailure {
            logger("定时任务 WakeLock 释放失败", it)
        }
    }

    private fun scheduleKey(taskId: String): String {
        return "scheduled_task:$taskId"
    }

    private fun sleep(delayMs: Long) {
        if (delayMs <= 0L) return
        try {
            Thread.sleep(min(delayMs, MAX_SLEEP_MS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val SINGLE_TIMEOUT_WINDOW_MS = 10 * 60 * 1000L
        private const val MAX_SLEEP_MS = 60L * 60L * 1000L
        private const val CHANNEL_SEND_TIMEOUT_MINUTES = 30L
        private const val MAX_CHANNEL_SEND_TIMEOUT_MINUTES = 43_200L
        private const val EXECUTION_WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1000L
        private const val EXECUTION_WAKE_LOCK_TAG = "Hchat:ScheduledTask"
        private const val ON_TIME_GRACE_MS = 60L * 1000L
    }
}
