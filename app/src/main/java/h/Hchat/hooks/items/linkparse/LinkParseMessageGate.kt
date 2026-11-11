package h.Hchat.hooks.items.linkparse

import h.Hchat.hooks.api.model.WeChatMessage

internal object LinkParseMessageGate {
    const val MAX_AGE = 30_000L
    const val FUTURE_TOLERANCE = 5_000L
    const val MAX_TEXT = 16_384

    fun eligibleTime(message: WeChatMessage, now: Long): Long? {
        if (!message.isText() || message.isSystem() || message.msgId <= 0 || message.talker.isBlank()) return null
        if (message.bodyContent().length > MAX_TEXT) return null
        val time = message.createTime.let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        return time.takeIf { it > 0 && it <= now + FUTURE_TOLERANCE && now - it < MAX_AGE }
    }
}
