package h.Hchat.hooks.api.message

import h.Hchat.utils.HLog
import java.util.concurrent.CopyOnWriteArrayList

object OutgoingTextDecoratorRegistry {
    data class DecorationContext(
        val talker: String,
        val text: String,
        val inputDurationMs: Long
    )

    data class TextDecoration(
        val prefix: String = "",
        val suffix: String = ""
    )

    data class AppliedDecoration(
        val prefix: String,
        val suffix: String,
        val text: String
    )

    fun interface Decorator {
        fun decorate(context: DecorationContext): TextDecoration?
    }

    class Subscription internal constructor(private val action: () -> Unit) {
        @Volatile
        private var active = true

        fun unsubscribe() {
            if (!active) return
            active = false
            action()
        }
    }

    private data class RegisteredDecorator(val id: String, val decorator: Decorator)

    private val decorators = CopyOnWriteArrayList<RegisteredDecorator>()
    private val contextualInputDuration = ThreadLocal<Long?>()

    @JvmStatic
    fun register(id: String, decorator: Decorator): Subscription {
        decorators.removeAll { it.id == id }
        val entry = RegisteredDecorator(id, decorator)
        decorators += entry
        return Subscription { decorators.remove(entry) }
    }

    @JvmStatic
    fun decorate(talker: String?, text: String, inputDurationMs: Long): AppliedDecoration? {
        var currentText = text
        var combinedPrefix = ""
        var combinedSuffix = ""
        for (entry in decorators) {
            val decoration = try {
                entry.decorator.decorate(
                    DecorationContext(
                        talker = talker.orEmpty(),
                        text = currentText,
                        inputDurationMs = inputDurationMs.coerceAtLeast(0L)
                    )
                )
            } catch (throwable: Throwable) {
                HLog.e(
                    "[Hchat:TextDecorator] 格式化回调失败: ${entry.id} ${throwable.message}",
                    throwable
                )
                null
            } ?: continue
            if (decoration.prefix.isEmpty() && decoration.suffix.isEmpty()) continue
            combinedPrefix = decoration.prefix + combinedPrefix
            combinedSuffix += decoration.suffix
            currentText = decoration.prefix + currentText + decoration.suffix
        }
        if (combinedPrefix.isEmpty() && combinedSuffix.isEmpty()) return null
        return AppliedDecoration(combinedPrefix, combinedSuffix, currentText)
    }

    @JvmStatic
    fun decorateText(talker: String?, text: String): String {
        val duration = contextualInputDuration.get() ?: 0L
        return decorate(talker, text, duration)?.text ?: text
    }

    fun <T> withInputDuration(inputDurationMs: Long, block: () -> T): T {
        val previous = contextualInputDuration.get()
        contextualInputDuration.set(inputDurationMs.coerceAtLeast(0L))
        return try {
            block()
        } finally {
            if (previous == null) contextualInputDuration.remove()
            else contextualInputDuration.set(previous)
        }
    }
}
