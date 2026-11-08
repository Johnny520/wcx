package h.Hchat.hooks.items.zombiecheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import h.Hchat.hooks.api.core.WeChatApis
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class ZombieCheckRuntime(
    context: Context,
    private val logger: (String, Throwable?) -> Unit
) {
    private val context = context.applicationContext
    private val settings = ZombieCheckSettings(context)
    private val lock = Any()
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "HchatZombieCheck").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val actionWorker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "HchatZombieAction").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val queue = ArrayDeque<QueueItem>()
    private val results = ArrayList<ZombieCheckResult>()
    private val logs = ArrayDeque<String>()
    private val names = ConcurrentHashMap<String, String>()

    @Volatile private var hooker: ZombieCheckHooker? = null
    @Volatile private var ready = false
    private var running = false
    private var status = "等待检测"
    private var currentName = ""
    private var totalCount = settings.totalCount()
    private var activeProbe: ActiveProbe? = null
    private var actionGeneration = 0L
    private var deleting = false
    private var deleteTotalCount = 0
    private var deleteCompletedCount = 0
    private var deleteSuccessCount = 0
    private var deleteFailureCount = 0
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        settings.pendingIds().forEach { queue.addLast(QueueItem(it, 0)) }
        results += settings.results()
        if (totalCount <= 0) totalCount = queue.size + results.size
        if (queue.isNotEmpty()) status = "检测已暂停，可继续"
    }

    internal fun bind(hooker: ZombieCheckHooker) {
        this.hooker = hooker
    }

    internal fun markReady() {
        synchronized(lock) {
            ready = true
            if (!running && queue.isEmpty() && results.isEmpty()) status = "等待检测"
        }
    }

    fun snapshot(): ZombieCheckSnapshot = synchronized(lock) {
        ZombieCheckSnapshot(
            ready = ready,
            running = running,
            status = status,
            currentName = currentName,
            totalCount = totalCount,
            pendingCount = queue.size + if (activeProbe != null) 1 else 0,
            results = results.toList(),
            logs = logs.toList(),
            deleting = deleting,
            deleteTotalCount = deleteTotalCount,
            deleteCompletedCount = deleteCompletedCount,
            deleteSuccessCount = deleteSuccessCount,
            deleteFailureCount = deleteFailureCount
        )
    }

    fun start(): ZombieCheckActionResult {
        if (!settings.enabled()) return ZombieCheckActionResult(false, "请先启用僵尸粉检测")
        if (!ready || hooker == null) return ZombieCheckActionResult(false, "检测接口尚未就绪")
        synchronized(lock) {
            if (running) return ZombieCheckActionResult(false, "检测正在运行")
            if (deleting) return ZombieCheckActionResult(false, "正在批量删除好友")
        }

        val contacts = runCatching { WeChatApis.contacts()?.getFriends().orEmpty() }
            .getOrElse {
                logger("读取好友列表失败", it)
                return ZombieCheckActionResult(false, "读取好友列表失败")
            }
        val friendIds = contacts.map { it.wxId }.filter(::isUsableFriendId).toSet()
        val excluded = settings.excludedIds()
        contacts.forEach { names[it.wxId] = it.displayName() }

        synchronized(lock) {
            if (queue.isEmpty()) {
                val configured = settings.targetIds()
                val targets = (if (configured.isEmpty()) friendIds else configured.intersect(friendIds)) - excluded
                if (targets.isEmpty()) return ZombieCheckActionResult(false, "没有可检测的好友")
                results.clear()
                targets.forEach { queue.addLast(QueueItem(it, 0)) }
                totalCount = queue.size
            } else {
                val retained = queue.filter { it.wxid in friendIds && it.wxid !in excluded }
                queue.clear()
                retained.forEach(queue::addLast)
                totalCount = queue.size + results.size
                if (queue.isEmpty()) return ZombieCheckActionResult(false, "没有可继续的好友")
            }
            running = true
            status = "检测运行中"
            currentName = ""
            addLogLocked("开始检测，待检测 ${queue.size} 位好友")
            persistLocked()
        }
        acquireWakeLockIfNeeded()
        updateNotification()
        scheduleDispatch(0L)
        return ZombieCheckActionResult(true, "检测已开始")
    }

    fun pause(): ZombieCheckActionResult {
        synchronized(lock) {
            if (!running && activeProbe == null && !deleting) {
                return ZombieCheckActionResult(false, "当前没有运行中的任务")
            }
            val wasDeleting = deleting
            running = false
            deleting = false
            actionGeneration++
            activeProbe?.let { probe ->
                probe.timeout?.cancel(false)
                queue.addFirst(probe.item)
            }
            activeProbe = null
            currentName = ""
            status = if (wasDeleting) "批量删除已停止" else "检测已暂停，可继续"
            addLogLocked(if (wasDeleting) "批量删除已停止" else "检测已暂停")
            persistLocked()
        }
        releaseWakeLock()
        updateNotification()
        return ZombieCheckActionResult(true, "进度已保存")
    }

    fun reset(): ZombieCheckActionResult {
        synchronized(lock) {
            running = false
            deleting = false
            actionGeneration++
            activeProbe?.timeout?.cancel(false)
            activeProbe = null
            queue.clear()
            results.clear()
            logs.clear()
            names.clear()
            totalCount = 0
            deleteTotalCount = 0
            deleteCompletedCount = 0
            deleteSuccessCount = 0
            deleteFailureCount = 0
            currentName = ""
            status = "等待检测"
            settings.clearProgress()
        }
        releaseWakeLock()
        cancelNotification()
        return ZombieCheckActionResult(true, "检测进度已重置")
    }

    fun deleteFriends(
        targetIds: Collection<String>,
        clearRecord: Boolean,
        delaySeconds: Int
    ): ZombieCheckActionResult {
        if (!ready || hooker == null) return ZombieCheckActionResult(false, "删除好友接口尚未就绪")
        val ids = targetIds.map { it.trim() }.distinct()
        if (ids.isEmpty()) return ZombieCheckActionResult(false, "请选择要删除的好友")
        if (ids.any { !isUsableFriendId(it) }) {
            return ZombieCheckActionResult(false, "选择中包含无效联系人，请重新选择")
        }
        synchronized(lock) {
            if (running) return ZombieCheckActionResult(false, "请先暂停好友检测")
            if (deleting) return ZombieCheckActionResult(false, "批量删除正在运行")
        }

        val contacts = runCatching { WeChatApis.contacts()?.getFriends().orEmpty() }
            .getOrElse {
                logger("批量删除前读取好友列表失败", it)
                return ZombieCheckActionResult(false, "读取好友列表失败")
            }
        val contactsById = contacts.associateBy { it.wxId }
        if (ids.any { it !in contactsById }) {
            return ZombieCheckActionResult(false, "部分好友已不在联系人列表，请重新选择")
        }
        val targets = ids.map { wxid ->
            DeleteTarget(wxid, contactsById.getValue(wxid).displayName().ifBlank { wxid })
        }
        val generation = synchronized(lock) {
            if (running) return ZombieCheckActionResult(false, "请先暂停好友检测")
            if (deleting) return ZombieCheckActionResult(false, "批量删除正在运行")
            actionGeneration++
            deleting = true
            deleteTotalCount = targets.size
            deleteCompletedCount = 0
            deleteSuccessCount = 0
            deleteFailureCount = 0
            currentName = ""
            status = "准备批量删除 ${targets.size} 位好友"
            addLogLocked("开始批量删除 ${targets.size} 位好友")
            actionGeneration
        }
        val submitted = runCatching {
            actionWorker.execute {
                runBatchDelete(targets, clearRecord, delaySeconds.coerceIn(0, 300), generation)
            }
        }.isSuccess
        if (!submitted) {
            synchronized(lock) {
                if (batchDeleteStillValidLocked(generation)) {
                    deleting = false
                    currentName = ""
                    status = "批量删除启动失败"
                    addLogLocked("批量删除任务提交失败")
                }
            }
            return ZombieCheckActionResult(false, "批量删除任务启动失败")
        }
        return ZombieCheckActionResult(true, "已开始批量删除 ${targets.size} 位好友")
    }

    internal fun onProbeCallback(scene: Any, errCode: Int, errMessage: String, response: JSONObject?) {
        val probe = synchronized(lock) {
            val current = activeProbe
            if (current == null || current.scene !== scene) return
            current.timeout?.cancel(false)
            activeProbe = null
            currentName = ""
            current
        }
        worker.execute {
            val result = classify(probe.item.wxid, errCode, errMessage, response)
            recordResult(result)
        }
    }

    fun close() {
        synchronized(lock) {
            running = false
            deleting = false
            actionGeneration++
            activeProbe?.timeout?.cancel(false)
            activeProbe = null
            persistLocked()
        }
        releaseWakeLock()
        cancelNotification()
        worker.shutdownNow()
        actionWorker.shutdownNow()
    }

    private fun scheduleDispatch(delayMillis: Long) {
        if (worker.isShutdown) return
        worker.schedule({ dispatchNext() }, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }

    private fun dispatchNext() {
        val item = synchronized(lock) {
            if (!running || activeProbe != null) return
            if (queue.isEmpty()) null else queue.removeFirst()
        }
        if (item == null) {
            completeIfFinished()
            return
        }
        val displayName = names[item.wxid].orEmpty().ifBlank { item.wxid }
        val scene = runCatching { hooker?.createProbe(item.wxid) }.getOrNull()
        if (scene == null) {
            recordResult(
                ZombieCheckResult(item.wxid, displayName, ZombieCheckResultType.UNKNOWN, "构造检测请求失败")
            )
            return
        }
        val probe = ActiveProbe(item, scene)
        synchronized(lock) {
            if (!running) {
                queue.addFirst(item)
                persistLocked()
                return
            }
            activeProbe = probe
            currentName = displayName
            status = "正在检测 $displayName"
            persistLocked()
        }
        updateNotification()
        val sent = runCatching { hooker?.sendProbe(scene) == true }.getOrDefault(false)
        if (!sent) {
            synchronized(lock) {
                if (activeProbe === probe) activeProbe = null
                currentName = ""
            }
            handleRetryOrFailure(probe, "检测请求发送失败")
            return
        }
        val future = worker.schedule(
            { onProbeTimeout(probe) },
            settings.timeoutSeconds().toLong(),
            TimeUnit.SECONDS
        )
        synchronized(lock) {
            if (activeProbe === probe) {
                probe.timeout = future
            } else {
                future.cancel(false)
            }
        }
    }

    private fun onProbeTimeout(probe: ActiveProbe) {
        synchronized(lock) {
            if (activeProbe !== probe) return
            activeProbe = null
            currentName = ""
        }
        handleRetryOrFailure(probe, "检测超时")
    }

    private fun handleRetryOrFailure(probe: ActiveProbe, reason: String) {
        val retry = probe.item.attempt < settings.maxRetries()
        if (retry) {
            synchronized(lock) {
                if (!running) {
                    queue.addFirst(probe.item)
                } else {
                    queue.addFirst(probe.item.copy(attempt = probe.item.attempt + 1))
                    status = "$reason，准备重试"
                    addLogLocked("${probe.item.wxid}: $reason，第 ${probe.item.attempt + 1} 次重试")
                }
                persistLocked()
            }
            updateNotification()
            if (snapshot().running) scheduleDispatch(500L)
        } else {
            val name = names[probe.item.wxid].orEmpty().ifBlank { probe.item.wxid }
            recordResult(ZombieCheckResult(probe.item.wxid, name, ZombieCheckResultType.UNKNOWN, reason))
        }
    }

    private fun classify(wxid: String, errCode: Int, errMessage: String, response: JSONObject?): ZombieCheckResult {
        val name = names[wxid].orEmpty().ifBlank { wxid }
        val message = errMessage.trim()
        return when {
            isRelationshipError(message) -> ZombieCheckResult(
                wxid, name, ZombieCheckResultType.DEAD, message.ifBlank { "好友关系异常" }
            )
            (errCode == 0 || errCode == 2) && !response?.optString("req_key").isNullOrBlank() -> ZombieCheckResult(
                wxid, name, ZombieCheckResultType.NORMAL, "好友关系正常"
            )
            else -> ZombieCheckResult(
                wxid,
                name,
                ZombieCheckResultType.UNKNOWN,
                message.ifBlank { "检测返回异常码 $errCode" }
            )
        }
    }

    private fun isRelationshipError(message: String): Boolean {
        if (message.isBlank()) return false
        return message.contains("不是收款方好友") ||
            message.contains("拒绝接收你的") ||
            message.contains("对方拒绝接收") ||
            message.contains("好友关系是否正常") ||
            (message.contains("确认你和") && message.contains("好友关系"))
    }

    private fun recordResult(result: ZombieCheckResult) {
        val shouldProcess = result.type == ZombieCheckResultType.DEAD
        synchronized(lock) {
            results.removeAll { it.wxid == result.wxid }
            results += result
            status = when (result.type) {
                ZombieCheckResultType.NORMAL -> "${result.name}：正常"
                ZombieCheckResultType.DEAD -> "${result.name}：好友关系异常"
                ZombieCheckResultType.UNKNOWN -> "${result.name}：检测异常"
            }
            addLogLocked("${result.name}: ${result.message}")
            persistLocked()
        }
        updateNotification()
        if (shouldProcess) processDeadFriend(result)
        val delay = randomDelayMillis()
        if (snapshot().running) scheduleDispatch(delay)
    }

    private fun completeIfFinished() {
        synchronized(lock) {
            if (!running || queue.isNotEmpty() || activeProbe != null) return
            running = false
            currentName = ""
            status = "检测完成"
            addLogLocked("检测完成，共发现 ${results.count { it.type == ZombieCheckResultType.DEAD }} 位异常好友")
            persistLocked()
        }
        releaseWakeLock()
        updateNotification()
    }

    private fun processDeadFriend(result: ZombieCheckResult) {
        val generation = synchronized(lock) { actionGeneration }
        actionWorker.execute {
            if (settings.autoTag()) {
                val tagged = appendLabel(result.wxid, settings.labelName(), generation)
                synchronized(lock) {
                    addLogLocked("${result.name}: ${if (tagged) "已追加标签" else "标签处理失败"}")
                }
            }
            if (!settings.autoDelete()) return@execute
            val delay = settings.deleteDelaySeconds().toLong()
            if (delay > 0L) runCatching { Thread.sleep(delay * 1000L) }
            if (!settings.autoDelete() || !actionStillValid(generation)) return@execute
            val deleted = hooker?.deleteFriend(result.wxid, settings.clearRecord()) == true
            synchronized(lock) {
                addLogLocked("${result.name}: ${if (deleted) "已提交删除好友" else "删除好友失败"}")
            }
        }
    }

    private fun runBatchDelete(
        targets: List<DeleteTarget>,
        clearRecord: Boolean,
        delaySeconds: Int,
        generation: Long
    ) {
        targets.forEachIndexed { index, target ->
            if (!batchDeleteStillValid(generation)) return
            synchronized(lock) {
                currentName = target.name
                status = "正在删除 ${target.name} (${index + 1}/${targets.size})"
            }
            val deleted = hooker?.deleteFriend(target.wxid, clearRecord) == true
            synchronized(lock) {
                if (!batchDeleteStillValidLocked(generation)) return
                deleteCompletedCount++
                if (deleted) deleteSuccessCount++ else deleteFailureCount++
                addLogLocked("${target.name}: ${if (deleted) "已提交删除好友" else "删除好友失败"}")
            }
            if (index < targets.lastIndex && !waitForBatchDelay(delaySeconds, generation)) return
        }
        synchronized(lock) {
            if (!batchDeleteStillValidLocked(generation)) return
            deleting = false
            currentName = ""
            status = "批量删除完成"
            addLogLocked("批量删除完成，已提交 $deleteSuccessCount 位，失败 $deleteFailureCount 位")
        }
    }

    private fun waitForBatchDelay(delaySeconds: Int, generation: Long): Boolean {
        var remaining = delaySeconds * 10
        while (remaining > 0) {
            if (!batchDeleteStillValid(generation)) return false
            runCatching { Thread.sleep(100L) }
            remaining--
        }
        return batchDeleteStillValid(generation)
    }

    private fun batchDeleteStillValid(generation: Long): Boolean = synchronized(lock) {
        batchDeleteStillValidLocked(generation)
    }

    private fun batchDeleteStillValidLocked(generation: Long): Boolean {
        return deleting && generation == actionGeneration
    }

    private fun appendLabel(wxid: String, labelName: String, generation: Long): Boolean {
        if (!actionStillValid(generation)) return false
        val contacts = WeChatApis.contacts() ?: return false
        var labels = runCatching { contacts.getContactLabelList() }.getOrDefault(emptyList())
        val existingNames = labels.filter { it.userNameList.contains(wxid) }
            .map { it.labelName }
            .filter { it.isNotBlank() }
            .toMutableSet()
        if (labels.none { it.labelName == labelName }) {
            if (contacts.addContactLabel(labelName).isBlank()) return false
            var attempts = 0
            while (attempts < 15 && labels.none { it.labelName == labelName }) {
                attempts++
                if (!actionStillValid(generation)) return false
                runCatching { Thread.sleep(1000L) }
                labels = runCatching { contacts.getContactLabelList() }.getOrDefault(emptyList())
            }
        }
        if (labels.none { it.labelName == labelName }) return false
        if (!actionStillValid(generation)) return false
        existingNames += labelName
        val modified = runCatching { contacts.modifyContactLabelList(wxid, existingNames.toList()) }
            .getOrDefault(false)
        if (!modified) return false
        return runCatching { contacts.hasContactLabel(wxid, labelName) }
            .getOrDefault(false)
    }

    private fun actionStillValid(generation: Long): Boolean = synchronized(lock) {
        generation == actionGeneration
    }

    private fun randomDelayMillis(): Long {
        val min = settings.minDelaySeconds()
        val max = settings.maxDelaySeconds()
        return Random.nextInt(min, max + 1).toLong() * 1000L
    }

    private fun persistLocked() {
        val pending = buildList {
            activeProbe?.let { add(it.item.wxid) }
            queue.forEach { add(it.wxid) }
        }
        settings.saveProgress(pending, totalCount, results)
    }

    private fun addLogLocked(message: String) {
        logs.addLast(message)
        while (logs.size > MAX_LOGS) logs.removeFirst()
    }

    private fun isUsableFriendId(wxid: String): Boolean {
        if (wxid.isBlank() || wxid.endsWith("@chatroom") || wxid.startsWith("gh_")) return false
        if (wxid == "filehelper" || wxid == "weixin") return false
        return wxid != WeChatApis.account()?.selfWxId()
    }

    private fun acquireWakeLockIfNeeded() {
        if (!settings.keepAwake()) return
        runCatching {
            val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hchat:ZombieCheck")
            lock.setReferenceCounted(false)
            lock.acquire(MAX_WAKE_LOCK_MILLIS)
            wakeLock = lock
        }.onFailure { logger("申请僵尸粉检测 WakeLock 失败", it) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun updateNotification() {
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "僵尸粉检测", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "显示好友关系检测进度"
                        setShowBadge(false)
                    }
                )
            }
            val state = snapshot()
            if (state.totalCount <= 0) {
                manager.cancel(NOTIFICATION_ID)
                return
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                Notification.Builder(context)
            }
            builder
                .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_sync)
                .setContentTitle("僵尸粉检测")
                .setContentText("已检测 ${state.checkedCount}/${state.totalCount}，异常 ${state.deadCount}，失败 ${state.unknownCount}")
                .setProgress(state.totalCount, state.checkedCount, false)
                .setOngoing(state.running)
                .setOnlyAlertOnce(true)
            manager.notify(NOTIFICATION_ID, builder.build())
        }.onFailure { logger("更新僵尸粉检测通知失败", it) }
    }

    private fun cancelNotification() {
        runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
    }

    private data class QueueItem(val wxid: String, val attempt: Int)

    private data class DeleteTarget(val wxid: String, val name: String)

    private data class ActiveProbe(
        val item: QueueItem,
        val scene: Any,
        var timeout: ScheduledFuture<*>? = null
    )

    companion object {
        private const val CHANNEL_ID = "hchat_zombie_check"
        private const val NOTIFICATION_ID = 0x48435A43
        private const val MAX_LOGS = 80
        private const val MAX_WAKE_LOCK_MILLIS = 60L * 60L * 1000L
    }
}

object ZombieCheckController {
    @Volatile private var runtime: ZombieCheckRuntime? = null

    internal fun attach(runtime: ZombieCheckRuntime) {
        this.runtime = runtime
    }

    internal fun detach(target: ZombieCheckRuntime?) {
        if (runtime === target) runtime = null
    }

    fun snapshot(): ZombieCheckSnapshot = runtime?.snapshot() ?: ZombieCheckSnapshot()

    fun start(): ZombieCheckActionResult =
        runtime?.start() ?: ZombieCheckActionResult(false, "检测运行时尚未就绪")

    fun pause(): ZombieCheckActionResult =
        runtime?.pause() ?: ZombieCheckActionResult(false, "检测运行时尚未就绪")

    fun reset(): ZombieCheckActionResult =
        runtime?.reset() ?: ZombieCheckActionResult(false, "检测运行时尚未就绪")

    fun deleteFriends(
        targetIds: Collection<String>,
        clearRecord: Boolean,
        delaySeconds: Int
    ): ZombieCheckActionResult = runtime?.deleteFriends(targetIds, clearRecord, delaySeconds)
        ?: ZombieCheckActionResult(false, "检测运行时尚未就绪")
}
