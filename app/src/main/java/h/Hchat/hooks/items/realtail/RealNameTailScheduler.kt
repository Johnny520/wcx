package h.Hchat.hooks.items.realtail

import h.Hchat.hooks.api.core.WeChatApis
import java.lang.ref.WeakReference
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RealNameTailScheduler(
    private val store: RealNameTailStore,
    private val query: BeforeTransferNameQuery,
    logger: (String, Throwable?) -> Unit,
    onTailSaved: (String) -> Unit
) {
    @Volatile private var logger: ((String, Throwable?) -> Unit)? = logger
    private var onTailSaved: ((String) -> Unit)? = onTailSaved
    private val lock = Any()
    private val taskApi = WeChatApis.tasks()
    private val taskPrefix = "real_tail_${instances.incrementAndGet()}:"
    private val pendingTasks = HashMap<String, Pair<Long, () -> Unit>>()
    private var taskSequence = 0L
    private val queue = ArrayDeque<Pair<String, String>>()
    private val scheduled = HashSet<String>()
    private val querying = ConcurrentHashMap.newKeySet<String>()
    private val retryCount = ConcurrentHashMap<String, Int>()
    private val retryUntil = ConcurrentHashMap<String, Long>()
    private val renderQueryTs = ConcurrentHashMap<String, Long>()
    private val renderQueryPending = ConcurrentHashMap<String, Boolean>()
    private val messageQueryTs = ConcurrentHashMap<String, Long>()
    private val activeTokens = ConcurrentHashMap<String, Long>()

    @Volatile private var closed = false
    private var workerThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var busy = false
    @Volatile private var busySinceMs = 0L
    @Volatile private var tokenSeq = 0L

    fun destroy() {
        synchronized(lock) {
            closed = true
            logger = null
            onTailSaved = null
            workerThread?.interrupt()
            pendingTasks.keys.forEach { taskApi?.cancel(taskPrefix + it) }
            pendingTasks.clear()
            queue.clear()
            scheduled.clear()
            querying.clear()
            activeTokens.clear()
            retryCount.clear()
            retryUntil.clear()
            renderQueryTs.clear()
            renderQueryPending.clear()
            messageQueryTs.clear()
            running = false
            busy = false
        }
    }

    fun onMessage(roomId: String?, wxid: String?) {
        if (closed || taskApi == null) return
        val room = roomId?.trim().orEmpty()
        val id = wxid?.trim().orEmpty()
        if (!isRoom(room) || !RealNameTailStore.isSupportedQueryId(id)) return
        if (isSelf(id) || store.hasTail(id) || querying.contains(id)) return
        synchronized(lock) {
            if (queue.size >= AUTO_QUERY_QUEUE_LIMIT) return
        }
        val now = System.currentTimeMillis()
        val key = taskKey(room, id)
        val last = messageQueryTs[key]
        if (last != null && now - last < AUTO_QUERY_MIN_GAP_MS) return
        messageQueryTs[key] = now
        enqueueQueryTask(room, id)
    }

    fun onVisible(roomId: String?, wxid: String?) {
        if (closed || taskApi == null) return
        recoverStaleBusy()
        val room = roomId?.trim().orEmpty()
        val id = wxid?.trim().orEmpty()
        if (!isRoom(room) || !RealNameTailStore.isSupportedQueryId(id)) return
        if (isSelf(id) || store.hasTail(id) || querying.contains(id)) return
        if (!isRenderQueryQueueFull()) enqueueQueryTask(room, id)
        if (inRetryCooldown(room, id)) return
        val key = taskKey(room, id)
        val now = System.currentTimeMillis()
        val last = renderQueryTs[key]
        if (last == null || now - last >= VISIBLE_DIRECT_QUERY_COOLDOWN_MS) {
            renderQueryTs[key] = now
            tryImmediateQuery(room, id)
        }
    }

    fun onVisibleSoon(roomId: String?, wxid: String?) {
        if (closed || taskApi == null) return
        val room = roomId?.trim().orEmpty()
        val id = wxid?.trim().orEmpty()
        if (!isRoom(room) || !RealNameTailStore.isSupportedQueryId(id) || isSelf(id)) return
        val key = taskKey(room, id)
        if (renderQueryPending.putIfAbsent(key, true) != null) return
        scheduleTask("visible_$key", VISIBLE_IMMEDIATE_QUERY_DELAY_MS) {
            renderQueryPending.remove(key)
            if (!isRoom(room) || !RealNameTailStore.isSupportedQueryId(id) || isSelf(id)) return@scheduleTask
            if (store.hasTail(id) || querying.contains(id) || inRetryCooldown(room, id)) return@scheduleTask
            if (!isRenderQueryQueueFull()) enqueueQueryTask(room, id)
            tryImmediateQuery(room, id)
        }
    }

    private fun enqueueQueryTask(roomId: String, wxid: String) {
        if (!isRoom(roomId) || !RealNameTailStore.isSupportedQueryId(wxid)) return
        if (store.hasTail(wxid) || querying.contains(wxid) || inRetryCooldown(roomId, wxid)) return
        val key = taskKey(roomId, wxid)
        synchronized(lock) {
            if (closed || scheduled.contains(key)) return
            queue.addLast(roomId to wxid)
            scheduled.add(key)
        }
        ensureScheduler()
    }

    private fun ensureScheduler() {
        recoverStaleBusy()
        synchronized(lock) {
            if (closed || taskApi == null || running) return
            running = true
        }
        taskApi?.runAsync { runSchedulerLoop() }
    }

    private fun resumeSchedulerSoon() {
        scheduleTask("resume", nextQueryGapMs()) {
            ensureScheduler()
        }
    }

    private fun runSchedulerLoop() {
        synchronized(lock) {
            if (closed) return
            workerThread = Thread.currentThread()
        }
        try {
            while (!closed && !Thread.currentThread().isInterrupted && store.isEnabled()) {
                recoverStaleBusy()
                synchronized(lock) {
                    if (busy) {
                        running = false
                        return
                    }
                }
                val item = synchronized(lock) {
                    if (queue.isEmpty()) {
                        null
                    } else {
                        queue.removeFirst().also { scheduled.remove(taskKey(it.first, it.second)) }
                    }
                } ?: run {
                    synchronized(lock) { running = false }
                    return
                }
                val room = item.first
                val wxid = item.second
                if (store.hasTail(wxid) || querying.contains(wxid) || inRetryCooldown(room, wxid)) continue
                if (!query.ensureReady()) {
                    enqueueQueryTask(room, wxid)
                    sleepQuietly(800L)
                    continue
                }
                if (!acquireBusy()) {
                    enqueueQueryTask(room, wxid)
                    continue
                }
                startQuery(room, wxid)
                synchronized(lock) { running = false }
                return
            }
        } catch (t: Throwable) {
            logger?.invoke("实名尾字队列异常", t)
        } finally {
            synchronized(lock) {
                if (workerThread === Thread.currentThread()) workerThread = null
                if (closed || (!busy && queue.isEmpty())) running = false
            }
        }
    }

    private fun tryImmediateQuery(roomId: String, wxid: String) {
        if (closed || taskApi == null) return
        recoverStaleBusy()
        if (!isRoom(roomId) || !RealNameTailStore.isSupportedQueryId(wxid)) return
        if (store.hasTail(wxid) || querying.contains(wxid) || inRetryCooldown(roomId, wxid)) return
        if (!query.ensureReady()) {
            enqueueQueryTask(roomId, wxid)
            return
        }
        if (!acquireBusy()) {
            enqueueQueryTask(roomId, wxid)
            return
        }
        startQuery(roomId, wxid)
    }

    private fun startQuery(roomId: String, wxid: String) {
        val key = taskKey(roomId, wxid)
        val token = synchronized(lock) {
            if (closed) return
            querying.add(wxid)
            beginToken(key)
        }
        startWatchdog(roomId, wxid, token)
        val owner = WeakReference(this)
        scheduleTask("query_$key", null) {
            if (!isActiveToken(key, token)) return@scheduleTask
            val sent = query.query(wxid, roomId) { maskedName ->
                owner.get()?.finishQuery(roomId, wxid, token, maskedName)
            }
            if (!sent) finishFailed(roomId, wxid, token, retry = true)
        }
    }

    private fun finishQuery(roomId: String, wxid: String, token: Long, maskedName: String) {
        synchronized(lock) {
            val key = taskKey(roomId, wxid)
            if (!isActiveToken(key, token)) return
            clearToken(key, token)
            if (maskedName.isBlank()) {
                finishNoResult(roomId, wxid)
                return
            }
            store.saveTail(wxid, maskedName)
            querying.remove(wxid)
            retryCount.remove(key)
            retryUntil.remove(key)
            setBusy(false)
            onTailSaved?.invoke(wxid)
            resumeSchedulerSoon()
        }
    }

    private fun finishFailed(roomId: String, wxid: String, token: Long, retry: Boolean) {
        synchronized(lock) {
            val key = taskKey(roomId, wxid)
            if (!isActiveToken(key, token)) return
            clearToken(key, token)
            querying.remove(wxid)
            setBusy(false)
            if (retry) onTaskFailed(roomId, wxid) else onTaskNoResult(roomId, wxid)
            resumeSchedulerSoon()
        }
    }

    private fun finishNoResult(roomId: String, wxid: String) {
        querying.remove(wxid)
        setBusy(false)
        onTaskNoResult(roomId, wxid)
        resumeSchedulerSoon()
    }

    private fun startWatchdog(roomId: String, wxid: String, token: Long) {
        val key = taskKey(roomId, wxid)
        scheduleTask("watchdog_$key", QUERY_TIMEOUT_MS) {
            synchronized(lock) {
                if (!isActiveToken(key, token) || !querying.contains(wxid)) return@scheduleTask
                clearToken(key, token)
                querying.remove(wxid)
                setBusy(false)
                onTaskNoResult(roomId, wxid)
                resumeSchedulerSoon()
            }
        }
    }

    private fun onTaskFailed(roomId: String, wxid: String) {
        val key = taskKey(roomId, wxid)
        val count = (retryCount[key] ?: 0) + 1
        retryCount[key] = count
        if (count == 1) {
            val delay = RETRY_ONCE_MIN_MS + (Math.random() * RETRY_ONCE_JITTER_MS).toLong()
            retryUntil[key] = System.currentTimeMillis() + delay
            scheduleTask("retry_$key", delay) {
                retryUntil.remove(key)
                if (!store.hasTail(wxid)) enqueueQueryTask(roomId, wxid)
            }
            return
        }
        retryUntil[key] = System.currentTimeMillis() + RETRY_FAIL_COOLDOWN_MS
    }

    private fun onTaskNoResult(roomId: String, wxid: String) {
        val key = taskKey(roomId, wxid)
        retryCount.remove(key)
        retryUntil[key] = System.currentTimeMillis() + QUERY_NO_RESULT_COOLDOWN_MS
    }

    private fun isRenderQueryQueueFull(): Boolean {
        return synchronized(lock) {
            queue.size + renderQueryPending.size >= RENDER_QUERY_QUEUE_LIMIT
        }
    }

    private fun inRetryCooldown(roomId: String, wxid: String): Boolean {
        return (retryUntil[taskKey(roomId, wxid)] ?: 0L) > System.currentTimeMillis()
    }

    private fun recoverStaleBusy() {
        synchronized(lock) {
            if (!busy) return
            val since = busySinceMs
            val max = QUERY_TIMEOUT_MS + QUERY_BUSY_STALE_EXTRA_MS
            if (since > 0L && System.currentTimeMillis() - since >= max) {
                busy = false
                busySinceMs = 0L
                querying.clear()
                activeTokens.clear()
            }
        }
    }

    private fun acquireBusy(): Boolean {
        synchronized(lock) {
            if (closed || busy) return false
            busy = true
            busySinceMs = System.currentTimeMillis()
            return true
        }
    }

    private fun setBusy(value: Boolean) {
        synchronized(lock) {
            busy = value
            busySinceMs = if (value) System.currentTimeMillis() else 0L
        }
    }

    private fun beginToken(key: String): Long {
        synchronized(lock) {
            tokenSeq += 1L
            activeTokens[key] = tokenSeq
            return tokenSeq
        }
    }

    private fun isActiveToken(key: String, token: Long): Boolean = !closed && activeTokens[key] == token

    private fun clearToken(key: String, token: Long) {
        if (activeTokens[key] == token) activeTokens.remove(key)
        pendingTasks.remove("watchdog_$key")
        taskApi?.cancel(taskPrefix + "watchdog_$key")
    }

    private fun scheduleTask(key: String, delayMs: Long?, action: () -> Unit) {
        val api = taskApi ?: return
        val token: Long
        val task = synchronized(lock) {
            if (closed) return
            token = ++taskSequence
            pendingTasks[key] = token to action
            val owner = WeakReference(this)
            Runnable { owner.get()?.runPendingTask(key, token) }
        }
        if (delayMs == null) {
            api.runOnMain(task)
        } else {
            synchronized(lock) {
                if (closed || pendingTasks[key]?.first != token) return
                api.runOnMainDelayed(taskPrefix + key, delayMs, task)
            }
        }
    }

    private fun runPendingTask(key: String, token: Long) {
        val action = synchronized(lock) {
            if (closed || pendingTasks[key]?.first != token) return
            pendingTasks.remove(key)?.second
        }
        action?.invoke()
    }

    private fun nextQueryGapMs(): Long = SERIAL_QUERY_GAP_MS + (Math.random() * SERIAL_QUERY_JITTER_MS).toLong()

    private fun taskKey(roomId: String, wxid: String): String = "$roomId|$wxid"

    private fun isRoom(roomId: String): Boolean = roomId.endsWith("@chatroom") || roomId.endsWith("@im.chatroom")

    private fun isSelf(wxid: String): Boolean {
        val self = WeChatApis.account()?.selfWxId().orEmpty()
        return self.isNotEmpty() && self == wxid
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val instances = AtomicLong()
        private const val RENDER_QUERY_QUEUE_LIMIT = 80
        private const val AUTO_QUERY_QUEUE_LIMIT = 12
        private const val SERIAL_QUERY_GAP_MS = 800L
        private const val SERIAL_QUERY_JITTER_MS = 700L
        private const val QUERY_TIMEOUT_MS = 12_000L
        private const val RETRY_ONCE_MIN_MS = 8_000L
        private const val RETRY_ONCE_JITTER_MS = 7_000L
        private const val RETRY_FAIL_COOLDOWN_MS = 60_000L
        private const val QUERY_NO_RESULT_COOLDOWN_MS = 6_000L
        private const val QUERY_BUSY_STALE_EXTRA_MS = 3_000L
        private const val AUTO_QUERY_MIN_GAP_MS = 3_000L
        private const val VISIBLE_IMMEDIATE_QUERY_DELAY_MS = 120L
        private const val VISIBLE_DIRECT_QUERY_COOLDOWN_MS = 5_000L
    }
}
