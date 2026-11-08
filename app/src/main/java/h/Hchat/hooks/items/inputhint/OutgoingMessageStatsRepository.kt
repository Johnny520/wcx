package h.Hchat.hooks.items.inputhint

import android.os.Handler
import android.os.Looper
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.DatabaseChange
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object OutgoingMessageStatsRepository {
    class Subscription internal constructor(private val action: () -> Unit) {
        @Volatile
        private var active = true

        fun unsubscribe() {
            if (!active) return
            active = false
            action()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HchatOutgoingStats").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val refreshLock = Any()

    @Volatile
    private var installed = false

    @Volatile
    private var refreshRunning = false

    @Volatile
    private var refreshPending = false

    @Volatile
    private var snapshotDay = ""

    @Volatile
    private var snapshot = InputHintStats()

    @Volatile
    private var logger: ((String, Throwable?) -> Unit)? = null

    private val outgoingMessages = LinkedHashMap<String, WeChatMessage>()
    private var cachedDay = ""
    private var changeSubscription: WeChatDatabaseListenerApi.Subscription? = null

    @Synchronized
    fun install(logger: (String, Throwable?) -> Unit): Boolean {
        this.logger = logger
        if (installed) return true
        val changes = WeChatApis.databaseChanges() ?: return false
        return runCatching {
            changes.install()
            if (!changes.isInstalled()) return@runCatching false
            changeSubscription = changes.subscribe(::onDatabaseChanged)
            installed = changeSubscription != null
            if (installed) refreshAsync()
            installed
        }.getOrElse {
            logger("真实发送统计监听安装失败", it)
            false
        }
    }

    fun subscribe(listener: () -> Unit): Subscription {
        listeners += listener
        return Subscription { listeners.remove(listener) }
    }

    fun current(): InputHintStats {
        val today = InputHintSettings.dayKey(System.currentTimeMillis())
        if (snapshotDay != today) {
            refreshAsync()
            return InputHintStats()
        }
        return snapshot
    }

    fun refreshAsync() {
        synchronized(refreshLock) {
            if (refreshRunning) {
                refreshPending = true
                return
            }
            refreshRunning = true
        }
        executor.execute {
            while (true) {
                val loaded = runCatching { loadTodayMessages() }
                    .onFailure { error ->
                        logger?.invoke("真实发送统计刷新失败", error)
                    }
                    .getOrNull()
                if (loaded != null) {
                    cachedDay = loaded.first
                    outgoingMessages.clear()
                    loaded.second.forEach { message ->
                        outgoingMessages[messageKey(message)] = message
                    }
                    publish(cachedDay, summarize())
                }
                val rerun = synchronized(refreshLock) {
                    if (refreshPending) {
                        refreshPending = false
                        true
                    } else {
                        refreshRunning = false
                        false
                    }
                }
                if (!rerun) break
            }
        }
    }

    private fun onDatabaseChanged(change: DatabaseChange) {
        if (!isOutgoingMessageTable(change.table)) return
        val msgId = resolveMsgId(change)
        if (change.isDelete()) {
            if (msgId <= 0L) {
                refreshAsync()
            } else {
                executor.execute {
                    if (outgoingMessages.remove("id:$msgId") != null) {
                        publish(cachedDay, summarize())
                    }
                }
            }
            return
        }
        if (change.isInsert() && contentValueInt(change, "isSend") == 0) return
        if (msgId <= 0L) {
            refreshAsync()
            return
        }
        executor.execute { applyMessageChange(msgId) }
    }

    private fun applyMessageChange(msgId: Long) {
        val today = InputHintSettings.dayKey(System.currentTimeMillis())
        if (cachedDay != today) {
            refreshAsync()
            return
        }
        val message = WeChatApis.message().store()?.getMessageById(msgId)
        if (message == null) {
            refreshAsync()
            return
        }
        val key = "id:$msgId"
        if (message.isOutgoing() && isToday(message.createTime)) {
            outgoingMessages[key] = message
        } else {
            outgoingMessages.remove(key)
        }
        publish(cachedDay, summarize())
    }

    private fun loadTodayMessages(): Pair<String, List<WeChatMessage>>? {
        val store = WeChatApis.message().store() ?: return null
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val end = calendar.timeInMillis
        val messages = store.getOutgoingMessagesOrNull(start, end) ?: return null
        return InputHintSettings.dayKey(now) to messages
    }

    private fun publish(day: String, stats: InputHintStats) {
        if (day.isEmpty()) return
        if (snapshotDay == day && snapshot == stats) return
        snapshotDay = day
        snapshot = stats
        mainHandler.post {
            listeners.forEach { listener -> runCatching(listener) }
        }
    }

    private fun summarize(): InputHintStats = outgoingMessages.values
        .fold(InputHintStats()) { current, message -> current.plus(message) }

    private fun messageKey(message: WeChatMessage): String {
        if (message.msgId > 0L) return "id:${message.msgId}"
        return "row:${message.msgSvrId}:${message.createTime}:${message.talker}:" +
            "${message.type}:${message.content.hashCode()}"
    }

    private fun isOutgoingMessageTable(table: String?): Boolean {
        val lower = table.orEmpty().lowercase(java.util.Locale.US)
        return lower == "message" || lower.startsWith("message_") || lower.endsWith("_message")
    }

    private fun resolveMsgId(change: DatabaseChange): Long {
        for (key in listOf("msgId", "msgid", "_id", "rowid")) {
            val value = runCatching { change.values?.getAsLong(key) ?: 0L }.getOrDefault(0L)
            if (value > 0L) return value
        }
        if (change.isInsert() && change.result > 0L) return change.result
        if (!change.whereClause.orEmpty().contains("msgid", ignoreCase = true)) return 0L
        return change.whereArgs.orEmpty()
            .firstNotNullOfOrNull { value -> value.toLongOrNull()?.takeIf { it > 0L } }
            ?: 0L
    }

    private fun contentValueInt(change: DatabaseChange, key: String): Int? = runCatching {
        if (change.values?.containsKey(key) == true) change.values?.getAsInteger(key) else null
    }.getOrNull()

    private fun isToday(timestamp: Long): Boolean {
        if (timestamp <= 0L) return false
        val millis = if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
        return InputHintSettings.dayKey(millis) == InputHintSettings.dayKey(System.currentTimeMillis())
    }

    private fun InputHintStats.plus(message: WeChatMessage): InputHintStats {
        val text = message.isText()
        return copy(
            totalMessages = totalMessages + 1L,
            textMessages = textMessages + if (text) 1L else 0L,
            textCharacters = textCharacters + if (text) {
                val content = message.bodyContent()
                content.codePointCount(0, content.length).toLong()
            } else {
                0L
            },
            emojiMessages = emojiMessages + if (message.isEmoji()) 1L else 0L,
            transferMessages = transferMessages + if (message.isTransfer()) 1L else 0L,
            redPacketMessages = redPacketMessages + if (message.isRedPacket()) 1L else 0L,
            fileMessages = fileMessages + if (message.isFile()) 1L else 0L
        )
    }
}
