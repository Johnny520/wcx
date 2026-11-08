package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件选择、后台读取及主线程交付共用一份可取消状态。
 * 后台工作和 onDiscard 不得捕获页面；onDiscard 只回收未交付结果拥有的资源。
 */
internal class ConversationGroupFileRequest<T> private constructor(
    activity: Activity,
    callback: (T) -> Unit,
    private val onDiscard: (T) -> Unit,
    private val onReleased: () -> Unit
) : Application.ActivityLifecycleCallbacks {
    val activity = WeakReference(activity)
    private val application = activity.application
    val canceled = AtomicBoolean(false)
    private val lock = Any()
    private var completion: ((T) -> Unit)? = callback
    private var result: T? = null
    private var resultReady = false
    private var work: (() -> T)? = null
    private var future: FutureTask<Unit>? = null
    private var started = false
    private val delivery = Runnable { deliverOnMain() }

    fun execute(task: (AtomicBoolean) -> T, failure: (Throwable) -> T) {
        var rejection: RejectedExecutionException? = null
        synchronized(lock) {
            if (canceled.get() || started) return
            started = true
            work = { try { task(canceled) } catch (error: Throwable) {
                if (canceled.get() || error is CancellationException) throw CancellationException()
                failure(error)
            } }
            val job = FutureTask<Unit> {
                val action = synchronized(lock) { work.also { work = null } } ?: return@FutureTask
                try {
                    deliver(action())
                } catch (_: CancellationException) {
                    cancel()
                } catch (error: Throwable) {
                    cancel()
                    HLog.e("[Hchat:ConversationGroup] 文件任务处理异常", error)
                }
            }
            future = job
            try { executor.execute(job) } catch (error: RejectedExecutionException) {
                work = null
                future = null
                rejection = error
            }
        }
        rejection?.let { error ->
            if (!canceled.get()) {
                try { deliver(failure(error)) } catch (failureError: Throwable) {
                    cancel()
                    HLog.e("[Hchat:ConversationGroup] 文件任务处理异常", failureError)
                }
            }
        }
    }

    fun deliver(value: T) {
        var shouldDiscard = false
        var postFailed = false
        synchronized(lock) {
            if (canceled.get() || resultReady) {
                shouldDiscard = true
            } else {
                result = value
                resultReady = true
                postFailed = !main.post(delivery)
            }
        }
        // 清理回调可能获取 Hook 锁，不得持有请求锁执行。
        if (shouldDiscard) discard(value)
        if (postFailed) cancel()
    }

    private fun deliverOnMain() {
        val owner = activity.get()
        if (owner == null || owner.isFinishing || owner.isDestroyed) return cancel()
        val callback: ((T) -> Unit)?
        val value: T
        synchronized(lock) {
            if (!resultReady || !canceled.compareAndSet(false, true)) return
            callback = completion
            @Suppress("UNCHECKED_CAST")
            value = result as T
            completion = null
            result = null
            resultReady = false
        }
        release()
        callback?.invoke(value)
    }

    fun cancel() {
        if (!canceled.compareAndSet(false, true)) return
        release()
    }

    private fun release() {
        var discarded: T? = null
        var hadResult = false
        val oldFuture = synchronized(lock) {
            completion = null
            if (resultReady) {
                discarded = result
                hadResult = true
            }
            result = null
            resultReady = false
            work = null
            main.removeCallbacks(delivery)
            future.also { future = null }
        }
        if (hadResult) {
            @Suppress("UNCHECKED_CAST")
            discard(discarded as T)
        }
        oldFuture?.cancel(true)
        oldFuture?.let { executor.remove(it) }
        activity.clear()
        runCatching { application.unregisterActivityLifecycleCallbacks(this) }
            .onFailure { HLog.e("[Hchat:ConversationGroup] 释放文件任务生命周期监听失败", it) }
        active.remove(this)
        slots.release()
        runCatching { onReleased() }
            .onFailure { HLog.e("[Hchat:ConversationGroup] 清理文件请求索引失败", it) }
    }

    private fun discard(value: T) {
        runCatching { onDiscard(value) }
            .onFailure { HLog.e("[Hchat:ConversationGroup] 清理未交付文件结果失败", it) }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (this.activity.get() === activity) cancel()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val slots = Semaphore(9)
        private val active = ConcurrentHashMap.newKeySet<ConversationGroupFileRequest<*>>()
        private val executor = ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
            { runnable -> Thread(runnable, "Hchat-ConversationGroupFile").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )

        fun <T> create(
            activity: Activity,
            callback: (T) -> Unit,
            onDiscard: (T) -> Unit = {},
            onReleased: () -> Unit
        ): ConversationGroupFileRequest<T>? {
            if (activity.isDestroyed || activity.isFinishing || !slots.tryAcquire()) return null
            val request = ConversationGroupFileRequest(activity, callback, onDiscard, onReleased)
            active.add(request)
            try { activity.application.registerActivityLifecycleCallbacks(request) }
            catch (error: Throwable) {
                request.cancel()
                HLog.e("[Hchat:ConversationGroup] 注册文件任务生命周期失败", error)
                return null
            }
            if (activity.isDestroyed || activity.isFinishing) {
                request.cancel()
                return null
            }
            return request
        }
    }
}

/** 只持有自己注册的结果 Hook；失败可重试，功能销毁后允许重新安装。 */
internal class ConversationGroupFileResultHooks(
    private val onResult: (Activity, Int, Int, Intent?) -> Unit,
    private val onHostFailure: ((Activity, Int) -> Unit)? = null
) {
    private val hooks = linkedMapOf<Class<*>, Set<XC_MethodHook.Unhook>>()
    private val retiredHooks = linkedSetOf<XC_MethodHook.Unhook>()
    private var generation = 0L

    @Synchronized
    fun ensure(activityClass: Class<*>): Boolean {
        retryRetiredHooks()
        val installingGeneration = generation
        try {
            var clazz: Class<*>? = activityClass
            while (clazz != null && Activity::class.java.isAssignableFrom(clazz)) {
                if (!hooks.containsKey(clazz)) {
                    val handles = XposedBridge.hookAllMethods(clazz, "onActivityResult", object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val owner = param.thisObject as? Activity ?: return
                            val code = param.args.getOrNull(0) as? Int ?: return
                            val result = param.args.getOrNull(1) as? Int ?: Activity.RESULT_CANCELED
                            synchronized(this@ConversationGroupFileResultHooks) {
                                if (generation == installingGeneration) {
                                    if (param.hasThrowable() && onHostFailure != null) {
                                        runCatching { onHostFailure(owner, code) }
                                            .onFailure { HLog.e("[Hchat:ConversationGroup] 取消失败的文件结果请求异常", it) }
                                    } else {
                                        onResult(owner, code, result, param.args.getOrNull(2) as? Intent)
                                    }
                                }
                            }
                        }
                    })
                    hooks[clazz] = handles
                }
                clazz = clazz.superclass
            }
            return hooks.values.any { it.isNotEmpty() }
        } catch (error: Throwable) {
            // 本轮失败时整代作废，已经注册但未能卸载的回调不能处理后续请求。
            generation++
            hooks.values.forEach { retiredHooks.addAll(it) }
            hooks.clear()
            retryRetiredHooks()
            HLog.e("[Hchat:ConversationGroup] 安装文件选择结果 Hook 失败", error)
            return false
        }
    }

    @Synchronized
    fun clear() {
        generation++
        hooks.values.forEach { retiredHooks.addAll(it) }
        hooks.clear()
        retryRetiredHooks()
    }

    private fun retryRetiredHooks() {
        val iterator = retiredHooks.iterator()
        while (iterator.hasNext()) {
            val handle = iterator.next()
            runCatching { handle.unhook() }
                .onSuccess { iterator.remove() }
                .onFailure { HLog.e("[Hchat:ConversationGroup] 卸载文件选择结果 Hook 失败", it) }
        }
    }
}
