package h.Hchat.hooks.items.script

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** 每次插件加载独立持有的延迟任务，关闭时释放尚未执行的脚本闭包。 */
internal class ScriptDelayScope(
    private val delayOnMainThread: Boolean,
    private val pluginName: () -> String
) {
    private val delayLock = Any()
    private val pendingDelays = HashSet<DelayedAction>()
    private var disposed = false
    private var delayDropLogAt = 0L
    private var droppedDelays = 0L

    private companion object {
        const val MAX_PLUGIN_DELAYS = 128
        const val DELAY_DROP_LOG_COOLDOWN_MS = 10_000L
        val delaySlots = Semaphore(512)
        val delayMainHandler by lazy { Handler(Looper.getMainLooper()) }
        val backgroundDelays by lazy {
            ScheduledThreadPoolExecutor(2) { runnable ->
                Thread(runnable, "Hchat-Script-Delay").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
        }
    }

    fun delay(millis: Long, action: Runnable?) {
        if (action == null) return
        synchronized(delayLock) {
            if (disposed) return
            if (pendingDelays.size >= MAX_PLUGIN_DELAYS || !delaySlots.tryAcquire()) {
                droppedDelays++
                val now = SystemClock.elapsedRealtime()
                if (delayDropLogAt == 0L || now - delayDropLogAt >= DELAY_DROP_LOG_COOLDOWN_MS) {
                    delayDropLogAt = now
                    h.Hchat.utils.HLog.e(
                        "[Hchat:Script] 延迟任务队列已满: ${pluginName()}，累计丢弃 $droppedDelays 项"
                    )
                }
                return
            }
            val task = DelayedAction(action)
            pendingDelays.add(task)
            try {
                if (delayOnMainThread) {
                    check(delayMainHandler.postDelayed(task, millis.coerceAtLeast(0L))) {
                        "主线程拒绝延迟任务"
                    }
                } else {
                    task.future = backgroundDelays.schedule(task, millis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                }
            } catch (error: Throwable) {
                task.cancelLocked()
                h.Hchat.utils.HLog.e("[Hchat:Script] 提交延迟任务失败: ${error.message}", error)
            }
        }
    }

    internal fun dispose() {
        synchronized(delayLock) {
            if (disposed) return
            disposed = true
            pendingDelays.toList().forEach { it.cancelLocked() }
        }
    }

    private inner class DelayedAction(private var action: Runnable?) : Runnable {
        var future: ScheduledFuture<*>? = null

        override fun run() {
            val callback = synchronized(delayLock) {
                if (!pendingDelays.remove(this)) return
                delaySlots.release()
                val current = action
                action = null
                future = null
                if (disposed) null else current
            } ?: return
            runCatching {
                callback.run()
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 延迟任务失败: ${it.message}", it)
            }
        }

        fun cancelLocked() {
            if (!pendingDelays.remove(this)) return
            action = null
            if (delayOnMainThread) delayMainHandler.removeCallbacks(this)
            future?.cancel(false)
            future = null
            delaySlots.release()
        }
    }

}
