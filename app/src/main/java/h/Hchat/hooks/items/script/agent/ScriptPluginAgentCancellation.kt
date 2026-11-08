package h.Hchat.hooks.items.script.agent

import okhttp3.Call
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ScriptPluginAgentCancellation {
    private val cancelled = AtomicBoolean(false)
    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        activeCalls.toList().forEach(Call::cancel)
        activeCalls.clear()
    }

    internal fun bind(call: Call) {
        if (cancelled.get()) {
            call.cancel()
            return
        }
        activeCalls += call
        if (cancelled.get() && activeCalls.remove(call)) call.cancel()
    }

    internal fun unbind(call: Call) {
        activeCalls.remove(call)
    }

    internal fun throwIfCancelled() {
        if (cancelled.get()) throw CancellationException("Agent 已中断")
    }

    internal fun isCancellation(error: Throwable): Boolean {
        return cancelled.get() || error is CancellationException ||
            (error is IOException && error.message.orEmpty().contains("cancel", ignoreCase = true))
    }
}
