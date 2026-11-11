package h.Hchat.hooks.items.atallnotify

import android.content.Context
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.model.WeChatMessage
import java.util.concurrent.ConcurrentHashMap

object AtAllNotificationBlockRuntime {
    private const val PENDING_TTL_MS = 15_000L
    private const val CLEANUP_THRESHOLD = 128
    private val pending = ConcurrentHashMap<PendingKey, Long>()

    fun clear() {
        pending.clear()
    }

    fun record(context: Context, message: WeChatMessageObserveApi.ObservedMessage) {
        if (!AtAllNotificationBlockSettings.isEnabled(context)) {
            pending.clear()
            return
        }
        val talker = message.talker.ifBlank { message.getTalker() }
        val selected = AtAllNotificationBlockSettings.blocksGroup(context, talker)
        val atAll = isAtAllMessage(message, talker)
        val msgSvrId = message.message?.msgSvrId ?: 0L
        if (!selected || !atAll) return
        recordNative(context, talker, msgSvrId)
    }

    fun recordNative(context: Context, talker: String, msgSvrId: Long) {
        val target = talker.trim()
        val selected = AtAllNotificationBlockSettings.blocksGroup(context, target)
        if (!isGroupTalker(target) || msgSvrId <= 0L || !selected) return
        val now = System.currentTimeMillis()
        pending[PendingKey(target, msgSvrId)] = now
        if (pending.size >= CLEANUP_THRESHOLD) cleanup(now)
    }

    fun shouldSuppress(
        context: Context,
        message: WeChatMessageObserveApi.ObservedMessage
    ): Boolean {
        if (!AtAllNotificationBlockSettings.isEnabled(context)) return false
        val talker = message.talker.ifBlank { message.getTalker() }
        val selected = AtAllNotificationBlockSettings.blocksGroup(context, talker)
        return selected && isAtAllMessage(message, talker)
    }

    fun shouldSuppress(context: Context, talker: String?, msgSvrId: Long): Boolean {
        if (!AtAllNotificationBlockSettings.isEnabled(context)) {
            pending.clear()
            return false
        }
        val target = talker?.trim().orEmpty()
        if (!isGroupTalker(target) || msgSvrId <= 0L) return false
        if (!AtAllNotificationBlockSettings.blocksGroup(context, target)) {
            pending.remove(PendingKey(target, msgSvrId))
            return false
        }
        val key = PendingKey(target, msgSvrId)
        val createdAt = pending[key] ?: return false
        val age = System.currentTimeMillis() - createdAt
        if (age <= PENDING_TTL_MS) return true
        pending.remove(key, createdAt)
        return false
    }

    internal fun isAtAllMessage(
        message: WeChatMessageObserveApi.ObservedMessage,
        talker: String
    ): Boolean {
        if (!isGroupTalker(talker) || message.outgoing || message.isSend()) return false
        if (message.isAnnounceAll) return false
        return message.isNotifyAll
    }

    internal fun isExpandedNotifyAll(
        content: String,
        msgSource: String,
        selfWxId: String
    ): Boolean {
        return WeChatMessage.isNotifyAllMessage(msgSource, content, selfWxId)
    }

    private fun cleanup(now: Long) {
        pending.entries.removeIf { now - it.value > PENDING_TTL_MS }
    }

    private fun isGroupTalker(talker: String): Boolean {
        return talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
    }

    private data class PendingKey(
        val talker: String,
        val msgSvrId: Long
    )

}
