package h.Hchat.hooks.items.script

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 每次加载独立持有的后台任务与回调；生命周期锁内绝不执行脚本或网络操作。 */
internal class ScriptAsyncScope(private val pluginName: () -> String) {
    private val lock = Any()
    private val tasks = HashSet<FutureTask<Unit>>()
    private val callbacks = HashSet<Callback<*>>()
    private val cancellations = HashSet<() -> Unit>()
    private var disposed = false
    private val executor = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(128),
        { runnable -> Thread(runnable, "Hchat-Script-Async").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }
    private val timer = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "Hchat-Script-Timeout").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setKeepAliveTime(30L, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }

    fun submit(block: () -> Unit): Boolean = synchronized(lock) {
        if (disposed) return false
        val task = object : FutureTask<Unit>({
            if (isActive()) {
                runCatching(block).onFailure { error ->
                    h.Hchat.utils.HLog.e("[Hchat:Script] 异步任务失败: ${pluginName()} ${error.message}", error)
                }
            }
            Unit
        }) {
            override fun done() { synchronized(lock) { tasks.remove(this) } }
        }
        tasks.add(task)
        try {
            executor.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            task.cancel(false)
            false
        }
    }

    fun isActive(): Boolean = synchronized(lock) { !disposed }

    fun <T : Any> callback(value: T?): Callback<T>? = synchronized(lock) {
        if (disposed || callbacks.size >= 256) return null
        Callback(this, value).also { callbacks.add(it) }
    }

    /** 已经领取的回调允许结束；卸载不等待脚本、解释器锁或宿主网络。 */
    private fun <T : Any> take(callback: Callback<T>): T? = synchronized(lock) {
        val registered = callbacks.remove(callback)
        val value = callback.value.getAndSet(null)
        callback.timeout?.cancel(false)
        callback.timeout = null
        if (!registered || disposed) null else value
    }

    private fun schedule(callback: Callback<*>, millis: Long, action: () -> Unit) {
        synchronized(lock) {
            if (disposed || !callbacks.contains(callback)) return
            callback.timeout?.cancel(false)
            callback.timeout = timer.schedule({
                if (!submit(action)) callback.cancel()
            }, millis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        }
    }

    /** 注册资源后再开始操作，覆盖卸载发生在创建 Call 与 execute 之间的竞态。 */
    fun registerCancellation(cancel: () -> Unit): Boolean = synchronized(lock) {
        if (disposed) return false
        cancellations.add(cancel)
        true
    }

    fun removeCancellation(cancel: () -> Unit) { synchronized(lock) { cancellations.remove(cancel) } }

    fun dispose() {
        val resources = synchronized(lock) {
            if (disposed) return
            disposed = true
            callbacks.toList().forEach { it.cancel() }
            tasks.toList().forEach { it.cancel(true) }
            tasks.clear()
            cancellations.toList().also { cancellations.clear() }
        }
        executor.shutdownNow()
        timer.shutdownNow()
        resources.forEach { runCatching(it) }
    }

    /** 外部宿主可以继续持有此空句柄，但卸载后不再持有脚本闭包。 */
    class Callback<T : Any> internal constructor(
        private val owner: ScriptAsyncScope,
        callback: T?
    ) {
        internal val value = AtomicReference(callback)
        internal var timeout: ScheduledFuture<*>? = null

        fun complete(action: (T) -> Unit) {
            val callback = owner.take(this) ?: return
            runCatching { action(callback) }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 异步回调失败: ${it.message}", it)
            }
        }

        fun cancel() { owner.take(this) }
        fun onTimeout(millis: Long, action: () -> Unit) { owner.schedule(this, millis, action) }
    }
}
