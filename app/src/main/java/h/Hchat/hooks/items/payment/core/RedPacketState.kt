package h.Hchat.hooks.items.payment.core

import android.text.TextUtils
import java.util.Collections
import java.util.LinkedHashMap
import java.util.function.LongSupplier
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 红包功能运行时状态。
 */
class RedPacketState @JvmOverloads constructor(
    private val clock: LongSupplier = LongSupplier { System.nanoTime() / 1_000_000L }
) {
    @JvmField
    val processedRedBags: MutableSet<String> = RecentPacketKeys(clock)

    @JvmField
    val processedRedBagIds: MutableSet<String> = RecentPacketKeys(clock)

    @JvmField
    val recordedAmountKeys: MutableSet<String> = RecentPacketKeys(clock)

    @JvmField
    val notifiedRedBags: MutableSet<String> = RecentPacketKeys(clock)

    @JvmField
    val failedNotifiedRedBags: MutableSet<String> = RecentPacketKeys(clock)

    @JvmField
    val senderMap: MutableMap<String, String> = ConcurrentHashMap()

    @JvmField
    val contentMap: MutableMap<String, String> = ConcurrentHashMap()

    @JvmField
    val talkerMap: MutableMap<String, String> = ConcurrentHashMap()

    @JvmField
    val ruleMap: MutableMap<String, RedPacketEffectiveRule> = ConcurrentHashMap()

    @JvmField
    val pendingUiPackets: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    fun markUiPending(nativeUrl: String?): Boolean {
        if (nativeUrl.isNullOrEmpty()) return false
        val rule = ruleMap[nativeUrl] ?: return false
        return rule.enabled && rule.grabMode != 1 && pendingUiPackets.add(nativeUrl)
    }

    fun isUiPending(nativeUrl: String?): Boolean {
        return !nativeUrl.isNullOrEmpty() && pendingUiPackets.contains(nativeUrl)
    }

    fun finishUiPacket(nativeUrl: String?): Boolean {
        return !nativeUrl.isNullOrEmpty() && pendingUiPackets.remove(nativeUrl)
    }

    @JvmField
    val recentContents: java.util.Deque<String> = ConcurrentLinkedDeque()

    @JvmField
    val silentRedPacketMap: MutableMap<String, MutableMap<String, Any>> = ConcurrentHashMap()

    @JvmField
    val silentReceiveRequestInfoMap: MutableMap<Any, MutableMap<String, Any>> =
        Collections.synchronizedMap(WeakHashMap())

    @JvmField
    val silentOpenRequestSendIdMap: MutableMap<Any, String> =
        Collections.synchronizedMap(WeakHashMap())

    @JvmField
    val silentReceiveRetryMap: MutableMap<String, Int> = ConcurrentHashMap()

    @JvmField
    val silentOpenRetryMap: MutableMap<String, Int> = ConcurrentHashMap()

    @JvmField
    val silentReceivingSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val silentOpeningSet: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @JvmField
    val silentFinishedSet: MutableSet<String> = RecentPacketKeys(clock)

    private val detectedFinishedKeys: MutableSet<String> = RecentPacketKeys(clock)
    private val replyScheduledKeys: MutableSet<String> = RecentPacketKeys(clock)

    @Synchronized
    fun markDetected(nativeUrl: String?, sender: String?, content: String?, talker: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        val key = nativeUrl ?: return false
        // 已结束的红包仅保留轻量键，不让消息重放重新保存正文或重新领取。
        if (detectedFinishedKeys.contains(amountKey(key)) || hasProcessed(key)) return false
        val safeContent = content ?: ""
        val oldContent = contentMap.putIfAbsent(key, safeContent)
        if (oldContent != null) {
            if (TextUtils.isEmpty(oldContent) && !TextUtils.isEmpty(safeContent)) {
                contentMap[key] = safeContent
            }
            fillIfEmpty(senderMap, key, sender)
            fillIfEmpty(talkerMap, key, talker)
            return false
        }
        senderMap[key] = sender ?: ""
        talkerMap[key] = talker ?: ""
        recentContents.addFirst(safeContent)
        while (recentContents.size > MAX_RECENT_CONTENT) recentContents.removeLast()
        return true
    }

    /** 过滤、失败、成功和取消都走同一终态；进行中的红包不按历史容量淘汰。 */
    @Synchronized
    fun finishDetectedPacket(nativeUrl: String?) {
        if (nativeUrl.isNullOrEmpty()) return
        detectedFinishedKeys.add(amountKey(nativeUrl))
        pendingUiPackets.remove(nativeUrl)
        senderMap.remove(nativeUrl)
        contentMap.remove(nativeUrl)
        talkerMap.remove(nativeUrl)
        ruleMap.remove(nativeUrl)
    }

    fun markReplyScheduled(key: String?): Boolean {
        return !key.isNullOrEmpty() && replyScheduledKeys.add(key)
    }

    fun markProcessed(nativeUrl: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        val key = nativeUrl ?: return false
        val id = redPacketId(key)
        if (!TextUtils.isEmpty(id) && !processedRedBagIds.add(id)) return false
        return processedRedBags.add(key)
    }

    fun hasProcessed(nativeUrl: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        val key = nativeUrl ?: return false
        if (processedRedBags.contains(key)) return true
        val id = redPacketId(key)
        return !TextUtils.isEmpty(id) && processedRedBagIds.contains(id)
    }

    fun markAmountRecorded(nativeUrl: String?): Boolean {
        val key = amountKey(nativeUrl)
        return !TextUtils.isEmpty(key) && recordedAmountKeys.add(key)
    }

    fun hasAmountRecorded(nativeUrl: String?): Boolean {
        val key = amountKey(nativeUrl)
        return !TextUtils.isEmpty(key) && recordedAmountKeys.contains(key)
    }

    fun markNotified(key: String?): Boolean {
        return !TextUtils.isEmpty(key) && notifiedRedBags.add(key ?: "")
    }

    fun markFailedNotified(key: String?): Boolean {
        return !TextUtils.isEmpty(key) && failedNotifiedRedBags.add(key ?: "")
    }

    fun cleanupSilentPacket(sendId: String?) {
        if (TextUtils.isEmpty(sendId)) return
        val key = sendId ?: return
        val info = silentRedPacketMap[key]
        val nativeUrl = info?.get("nativeurl") as? String
        if (!nativeUrl.isNullOrEmpty()) {
            finishDetectedPacket(nativeUrl)
        } else {
            // 请求构造前就失败时尚无 info，仍需释放检测阶段保存的全文和规则。
            contentMap.keys.filter { redPacketId(it) == key }.forEach(::finishDetectedPacket)
        }
        silentReceivingSet.remove(key)
        silentOpeningSet.remove(key)
        silentReceiveRetryMap.remove(key)
        silentOpenRetryMap.remove(key)
        silentRedPacketMap.remove(key)
        synchronized(silentReceiveRequestInfoMap) {
            silentReceiveRequestInfoMap.entries.removeAll { it.value["sendid"] == key }
        }
        synchronized(silentOpenRequestSendIdMap) {
            silentOpenRequestSendIdMap.entries.removeAll { it.value == key }
        }
    }

    fun clearActivePackets() {
        senderMap.clear()
        contentMap.clear()
        talkerMap.clear()
        ruleMap.clear()
        pendingUiPackets.clear()
        silentRedPacketMap.clear()
        silentReceiveRetryMap.clear()
        silentOpenRetryMap.clear()
        silentReceivingSet.clear()
        silentOpeningSet.clear()
        synchronized(silentReceiveRequestInfoMap) { silentReceiveRequestInfoMap.clear() }
        synchronized(silentOpenRequestSendIdMap) { silentOpenRequestSendIdMap.clear() }
    }

    private fun fillIfEmpty(map: MutableMap<String, String>, key: String, value: String?) {
        if (TextUtils.isEmpty(value)) return
        val old = map[key]
        if (TextUtils.isEmpty(old)) map[key] = value ?: return
    }

    private fun amountKey(nativeUrl: String?): String {
        val id = redPacketId(nativeUrl)
        return if (!TextUtils.isEmpty(id)) "sendid:$id" else nativeUrl ?: ""
    }

    /** 同步维护插入顺序，仅保存ID/时间；到期和容量淘汰都不接触活跃请求。 */
    private class RecentPacketKeys(private val clock: LongSupplier) : AbstractMutableSet<String>() {
        private val keys = LinkedHashMap<String, Long>()

        override val size: Int
            get() = synchronized(this) { prune(); keys.size }

        @Synchronized
        override fun add(element: String): Boolean {
            prune()
            if (keys.containsKey(element)) return false
            keys[element] = clock.asLong
            prune()
            return true
        }

        @Synchronized
        override fun contains(element: String): Boolean {
            prune()
            return keys.containsKey(element)
        }

        @Synchronized
        override fun remove(element: String): Boolean = keys.remove(element) != null

        @Synchronized
        override fun clear() = keys.clear()

        @Synchronized
        override fun iterator(): MutableIterator<String> {
            prune()
            val snapshot = keys.keys.toList().iterator()
            return object : MutableIterator<String> {
                private var last: String? = null
                override fun hasNext(): Boolean = snapshot.hasNext()
                override fun next(): String = snapshot.next().also { last = it }
                override fun remove() {
                    val key = last ?: throw IllegalStateException("必须先读取迭代项")
                    this@RecentPacketKeys.remove(key)
                    last = null
                }
            }
        }

        private fun prune() {
            val now = clock.asLong
            val iterator = keys.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (keys.size <= MAX_RECENT_KEYS && now - entry.value < RECENT_KEY_TTL_MS) break
                iterator.remove()
            }
        }
    }

    companion object {
        const val MAX_RECENT_KEYS = 4096
        const val RECENT_KEY_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_RECENT_CONTENT = 30

        @JvmStatic
        fun redPacketId(nativeUrl: String?): String {
            if (TextUtils.isEmpty(nativeUrl)) return ""
            var sendId = nativeUrlParam(nativeUrl, "sendid")
            if (TextUtils.isEmpty(sendId)) sendId = nativeUrlParam(nativeUrl, "sendId")
            return sendId
        }

        private fun nativeUrlParam(url: String?, key: String?): String {
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(key)) return ""
            try {
                val actualUrl = url ?: return ""
                val prefix = "$key="
                var start = actualUrl.indexOf('?')
                start = if (start >= 0) start + 1 else 0
                while (start < actualUrl.length) {
                    var end = actualUrl.indexOf('&', start)
                    if (end < 0) end = actualUrl.length
                    if (actualUrl.startsWith(prefix, start)) {
                        return actualUrl.substring(start + prefix.length, end)
                    }
                    start = end + 1
                }
            } catch (_: Throwable) {
            }
            return ""
        }
    }
}
