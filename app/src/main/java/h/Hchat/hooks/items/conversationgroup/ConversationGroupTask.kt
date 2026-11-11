package h.Hchat.hooks.items.conversationgroup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 后台工作只携带业务数据；页面回调在取消或销毁时立即释放。 */
internal object ConversationGroupTask {
    private val main = Handler(Looper.getMainLooper())
    // 将等待主线程和下一帧的结果也计入上限，避免仅限制执行器队列。
    private val slots = Semaphore(9)
    private val jobs = ConcurrentHashMap.newKeySet<Job<*>>()
    private val executor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { runnable -> Thread(runnable, "Hchat-ConversationGroupMenu").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )

    fun <T> launch(
        activity: Activity,
        title: String,
        message: String,
        nextFrame: Boolean = false,
        task: (AtomicBoolean) -> T,
        onComplete: (Result<T>) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!slots.tryAcquire()) {
            showBusy(activity.application)
            return
        }
        Job(activity, nextFrame, task, onComplete).start(title, message)
    }

    fun cancelAll() {
        jobs.toList().forEach { it.cancel() }
    }

    private fun showBusy(application: Application) {
        Toast.makeText(application, "聊天分组任务较多，请稍后重试", Toast.LENGTH_SHORT).show()
    }

    private class Job<T>(
        activity: Activity,
        private val nextFrame: Boolean,
        task: (AtomicBoolean) -> T,
        onComplete: (Result<T>) -> Unit
    ) : Application.ActivityLifecycleCallbacks {
        private val owner = WeakReference(activity)
        private val application = activity.application
        private val canceled = AtomicBoolean(false)
        private val released = AtomicBoolean(false)
        private val lock = Any()
        private var work: ((AtomicBoolean) -> T)? = task
        private var completion: ((Result<T>) -> Unit)? = onComplete
        private var result: Result<T>? = null
        private var loading: VoiceForwardMiuixDialog.DialogHandle? = null
        private var decor: WeakReference<View>? = null
        private var closingLoading = false
        private val future = FutureTask<Unit> { runWork() }
        private val finish = Runnable { finishWork() }
        private val deliver = Runnable { deliverResult() }

        fun start(title: String, message: String) {
            val activity = owner.get() ?: return cancel()
            jobs.add(this)
            try {
                application.registerActivityLifecycleCallbacks(this)
                loading = VoiceForwardMiuixDialog.showLoading(
                    activity = activity, title = title, message = message,
                    onDismiss = { if (!closingLoading) cancel() }
                )
            } catch (error: Throwable) {
                cancel()
                throw error
            }
            if (canceled.get() || loading?.isShowing() != true) {
                cancel()
                closeLoading()
                return
            }
            try {
                executor.execute(future)
            } catch (_: RejectedExecutionException) {
                cancel()
                showBusy(application)
            }
        }

        private fun runWork() {
            val action = synchronized(lock) {
                if (canceled.get()) null else work.also { work = null }
            } ?: return
            val value = runCatching { action(canceled) }
            synchronized(lock) {
                if (canceled.get()) return
                result = value
                main.post(finish)
            }
        }

        private fun finishWork() {
            if (canceled.get()) return
            closeLoading()
            val activity = owner.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) {
                cancel()
                return
            }
            if (nextFrame) {
                val view = activity.window?.decorView ?: return cancel()
                decor = WeakReference(view)
                view.postOnAnimation(deliver)
            } else {
                deliverResult()
            }
        }

        private fun deliverResult() {
            val activity = owner.get()
            if (canceled.get() || activity == null || activity.isFinishing || activity.isDestroyed) {
                cancel()
                return
            }
            val pair = synchronized(lock) {
                if (canceled.get()) return
                val callback = completion ?: return
                val value = result ?: return
                completion = null
                result = null
                callback to value
            }
            releaseUi()
            if (!canceled.get()) pair.first(pair.second)
        }

        fun cancel() {
            if (!canceled.compareAndSet(false, true)) return
            synchronized(lock) {
                work = null
                completion = null
                result = null
                main.removeCallbacks(finish)
            }
            future.cancel(true)
            executor.remove(future)
            releaseUi()
        }

        private fun closeLoading() {
            val handle = loading
            loading = null
            closingLoading = true
            try {
                handle?.close()
            } catch (error: Throwable) {
                HLog.e("[Hchat:ConversationGroup] 关闭任务加载层失败", error)
            } finally {
                closingLoading = false
            }
        }

        private val cleanup = Runnable {
            decor?.get()?.removeCallbacks(deliver)
            decor = null
            runCatching { application.unregisterActivityLifecycleCallbacks(this) }
                .onFailure { HLog.e("[Hchat:ConversationGroup] 释放任务生命周期监听失败", it) }
            owner.clear()
            closeLoading()
        }

        private fun releaseUi() {
            if (!released.compareAndSet(false, true)) return
            jobs.remove(this)
            slots.release()
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanup.run()
            } else {
                main.post(cleanup)
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (owner.get() === activity) cancel()
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    }
}
