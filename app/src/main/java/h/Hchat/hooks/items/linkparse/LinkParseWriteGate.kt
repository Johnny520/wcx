package h.Hchat.hooks.items.linkparse

import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.utils.KavaReflector

/** Captures an outgoing message row before the host write, then observes only its successful result. */
internal object LinkParseWriteGate {
    data class Capture(val id: Long, val native: Any)

    fun capture(enabled: Boolean, args: Array<Any?>): Capture? {
        if (!enabled) return null
        val id = (args.getOrNull(0) as? Number)?.toLong()?.takeIf { it > 0L } ?: return null
        val native = args.getOrNull(1) ?: return null
        return Capture(id, native)
    }

    fun observe(
        result: Int?,
        capture: Capture?,
        lookup: (Long) -> WeChatMessage?,
        accept: (WeChatMessage) -> Unit,
        onError: () -> Unit
    ) {
        try {
            if (result == null || result <= 0 || capture == null) return
            val native = capture.native
            val status = KavaReflector.findFieldRecursive(native.javaClass, "field_status")?.get(native) as? Number
            val sent = KavaReflector.findFieldRecursive(native.javaClass, "field_isSend")?.get(native) as? Number
            if (status?.toInt() != 2 || sent?.toInt() != 1) return
            val talker = KavaReflector.findFieldRecursive(native.javaClass, "field_talker")?.get(native) as? String
            if (talker.isNullOrBlank()) return
            val message = lookup(capture.id) ?: return
            if (!message.isOutgoing() || message.status != 2 || message.msgId != capture.id || message.talker != talker) return
            accept(message)
        } catch (_: Throwable) {
            runCatching(onError)
        }
    }
}
