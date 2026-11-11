package h.Hchat.hooks.items.script

import android.os.SystemClock
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadSupport
import h.Hchat.utils.HLog
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object ScriptMessageHook {
    private const val DEDUP_WINDOW_MS = 1000L
    private const val STABLE_ID_DEDUP_WINDOW_MS = 60_000L
    private const val MEDIA_MESSAGE_WAIT_MS = 15_000L
    private const val MEDIA_MESSAGE_RETRY_MS = 250L
    private const val MESSAGE_DISPATCH_QUEUE_CAPACITY = 128
    private const val OVERFLOW_LOG_INTERVAL_MS = 10_000L
    private const val MEDIA_DISPATCH_QUEUE_CAPACITY = 32
    private const val MAX_RECENT_MESSAGES = 4096
    private val USERNAME_REGEX = Regex("[a-z0-9_\\-.]{3,}")
    @Volatile
    private var installed = false
    private val recentMessages = ConcurrentHashMap<String, Long>()
    private val droppedMessages = AtomicLong()
    private val nextOverflowLogAtMs = AtomicLong()
    private val dispatchExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MESSAGE_DISPATCH_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "HchatScriptMessage").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val mediaDispatchExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MEDIA_DISPATCH_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "HchatScriptMediaMessage").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )

    @Synchronized
    fun install(context: FeatureContext): Boolean {
        if (installed) return true
        val changeApi = runCatching { WeChatApis.message().changes() }.getOrNull()
        if (changeApi == null || !changeApi.isAvailable()) return false
        try {
            changeApi.install()
        } catch (t: Throwable) {
            HLog.e("[Hchat:Script] 消息监听安装失败: ${t.message}", t)
            return false
        }
        if (!changeApi.isInstalled) return false
        changeApi.subscribe { change ->
            val handleMessage = ScriptPluginRuntime.hasHandleMsgCallbacks()
            val imageCallback = ScriptPluginRuntime.hasImageDownloadCallback()
            val videoCallback = ScriptPluginRuntime.hasVideoDownloadCallback()
            val finderCallback = ScriptPluginRuntime.hasFinderMediaDownloadCallback()
            if (!handleMessage && !imageCallback && !videoCallback && !finderCallback) return@subscribe
            val message = change.message ?: return@subscribe
            val scriptMessage = ScriptMessageBean(message)
            val image = imageCallback && scriptMessage.isImage()
            val video = videoCallback && scriptMessage.isVideo()
            val finder = finderCallback && scriptMessage.isVideoNumberVideo()
            dispatchMedia(scriptMessage, image, video, finder)
            if (handleMessage) dispatchMessageDedup(scriptMessage)
        }
        installed = true
        return true
    }

    fun clearPendingMessages() {
        dispatchExecutor.queue.clear()
        recentMessages.clear()
    }

    private fun dispatchMessageDedup(message: ScriptMessageBean) {
        if (!ScriptPluginRuntime.hasHandleMsgCallbacks()) return
        val now = System.currentTimeMillis()
        cleanup(now)
        val keys = dedupKeys(message)
        if (keys.any { key ->
                val previous = recentMessages[key]
                previous != null && now - previous < dedupWindowMs(key)
            }) {
            return
        }
        val dispatch = ScriptPluginRuntime.captureHandleMsgDispatch(message) ?: return
        keys.forEach { key -> recentMessages[key] = now }
        trimRecentMessages()
        try {
            dispatchExecutor.execute {
                if (!ScriptPluginRuntime.hasHandleMsgCallbacks()) return@execute
                try {
                    dispatch.run()
                } catch (t: Throwable) {
                    HLog.e("[Hchat:Script] 消息监听异步分发失败: ${t.message}", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            val dropped = droppedMessages.incrementAndGet()
            val nowMs = SystemClock.elapsedRealtime()
            val nextLogAtMs = nextOverflowLogAtMs.get()
            if (nowMs >= nextLogAtMs &&
                nextOverflowLogAtMs.compareAndSet(nextLogAtMs, nowMs + OVERFLOW_LOG_INTERVAL_MS)
            ) {
                HLog.e("[Hchat:Script] 消息分发队列已满，累计丢弃 $dropped 个新事件")
            }
        }
    }

    private fun dispatchMedia(
        message: ScriptMessageBean,
        image: Boolean,
        video: Boolean,
        finder: Boolean
    ) {
        if (!image && !video && !finder) return
        try {
            mediaDispatchExecutor.execute {
                try {
                    val stableMessage = resolveStableMediaMessage(message, finder)
                    if (image && stableMessage.isImage()) {
                        ScriptPluginRuntime.dispatchOnImageDownload(stableMessage)
                    }
                    if (video && stableMessage.isVideo()) {
                        ScriptPluginRuntime.dispatchOnVideoDownload(stableMessage)
                    }
                    if (finder && stableMessage.isVideoNumberVideo()) {
                        ScriptPluginRuntime.dispatchOnFinderMediaDownload(stableMessage)
                    }
                } catch (t: Throwable) {
                    HLog.e("[Hchat:Script] 媒体消息异步分发失败: ${t.message}", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            HLog.e("[Hchat:Script] 媒体消息补查队列已满，已丢弃新事件")
        }
    }

    private fun resolveStableMediaMessage(
        message: ScriptMessageBean,
        finder: Boolean
    ): ScriptMessageBean {
        val msgId = message.getMsgId()
        val msgSvrId = message.getMsgSvrId()
        if (msgId <= 0L && msgSvrId <= 0L) return message
        val store = runCatching { WeChatApis.message().store() }.getOrNull() ?: return message
        val talker = message.getTalker()
        val deadline = System.currentTimeMillis() + MEDIA_MESSAGE_WAIT_MS
        var latest = message
        do {
            val stored = msgId.takeIf { it > 0L }?.let { store.getMessageById(it) }
                ?: msgSvrId.takeIf { it > 0L && talker.isNotBlank() }
                    ?.let { store.getMessageBySvrId(talker, it) }
                ?: msgSvrId.takeIf { it > 0L }?.let { store.getMessageBySvrId(it) }
            if (stored != null) {
                latest = ScriptMessageBean(stored)
            }
            if (isStableMediaMessage(latest, finder)) {
                return latest
            }
            if (System.currentTimeMillis() >= deadline) break
            try {
                Thread.sleep(MEDIA_MESSAGE_RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        } while (true)
        return latest
    }

    private fun isStableMediaMessage(message: ScriptMessageBean, finder: Boolean): Boolean {
        if (finder) {
            return FinderMediaDownloadSupport.extractMedia(message.getContent()) != null
        }
        if (message.getMsgSvrId() > 0L) return true
        return message.isSend() &&
            (message.getMsgId() > 0L || message.getContent().isNotBlank())
    }

    private fun dedupKeys(message: ScriptMessageBean): List<String> {
        val keys = ArrayList<String>(4)
        val talker = normalizeTalker(message.getTalker())
        val sender = normalizeSender(message.getSender(), talker)
        val type = normalizeType(message.getMsgType())
        val content = normalizeContent(message.getContent(), talker, sender)
        val msgId = message.getMsgId()
        if (msgId > 0L) {
            keys += "msg:$talker:$msgId"
        }
        val msgSvrId = message.getMsgSvrId()
        if (msgSvrId > 0L) {
            keys += "svr:$talker:$msgSvrId"
        }
        keys += buildString {
            append("raw:")
            append(talker).append('|')
            append(sender).append('|')
            append(type).append('|')
            append(message.isSend()).append('|')
            append(content)
        }
        keys += "body:$talker|$sender|$type|$content"
        if (message.isSend()) {
            keys += "chat:$talker|$type|$content"
        }
        return keys
    }

    private fun dedupWindowMs(key: String): Long {
        return if (key.startsWith("msg:")) {
            STABLE_ID_DEDUP_WINDOW_MS
        } else {
            DEDUP_WINDOW_MS
        }
    }

    private fun normalizeTalker(value: String?): String = value.orEmpty().trim()

    private fun normalizeSender(value: String?, talker: String): String {
        val sender = value.orEmpty().trim()
        return if (sender.isNotEmpty() && sender != talker) sender else ""
    }

    private fun normalizeType(value: String?): String = value.orEmpty().trim()

    private fun normalizeContent(value: String?, talker: String, sender: String): String {
        var content = value.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (talker.endsWith("@chatroom", ignoreCase = true)) {
            content = stripGroupContentPrefix(content, sender)
        }
        return content
    }

    private fun stripGroupContentPrefix(content: String, sender: String): String {
        if (content.isBlank()) return content
        if (sender.isNotBlank()) {
            val prefix = "$sender:\n"
            if (content.startsWith(prefix)) return content.substring(prefix.length).trim()
        }
        val lineBreak = content.indexOf('\n')
        if (lineBreak <= 0) return content
        val prefix = content.substring(0, lineBreak).trim().removeSuffix(":")
        if (!isLikelyUserName(prefix)) return content
        return content.substring(lineBreak + 1).trim()
    }

    private fun isLikelyUserName(value: String): Boolean {
        if (value.isBlank() || value.length > 80) return false
        val lower = value.lowercase(Locale.ROOT)
        return lower.endsWith("@openim")
                || lower.endsWith("@chatroom")
                || lower.matches(USERNAME_REGEX)
    }

    private fun cleanup(now: Long) {
        if (recentMessages.size >= 128) {
            recentMessages.entries.removeIf { now - it.value > dedupWindowMs(it.key) }
        }
        trimRecentMessages()
    }

    private fun trimRecentMessages() {
        while (recentMessages.size > MAX_RECENT_MESSAGES) {
            val oldest = recentMessages.entries.minByOrNull { it.value } ?: return
            if (!recentMessages.remove(oldest.key, oldest.value)) continue
        }
    }
}
