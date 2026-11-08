package h.Hchat.hooks.api.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 模块任务调度 API。
 *
 * 给功能层提供主线程、延迟任务、异步任务和简单节流能力。
 */
class WeChatTaskApi(
    context: Context,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val delayedTasks = ConcurrentHashMap<String, Runnable>()
    private val exactTasks = ConcurrentHashMap<String, ExactTask>()
    private val lastRunTimes = ConcurrentHashMap<String, Long>()
    private val appContext = context.applicationContext ?: context
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    private val exactToken = AtomicLong(System.currentTimeMillis())
    private val exactReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_EXACT_TASK) return
            val key = intent.getStringExtra(EXTRA_EXACT_TASK_KEY).orEmpty()
            if (key.isEmpty()) return
            dispatchExactTask(key, intent.getLongExtra(EXTRA_EXACT_TASK_TOKEN, Long.MIN_VALUE))
        }
    }
    private val exactReceiverRegistered = registerExactReceiver()

    val isAvailable: Boolean
        get() = true

    fun runOnMain(runnable: Runnable?) {
        if (runnable == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            safeRun(runnable)
        } else {
            mainHandler.post { safeRun(runnable) }
        }
    }

    fun runOnMainDelayed(key: String?, delayMs: Long, runnable: Runnable?) {
        if (runnable == null) return
        val safeKey = key?.takeIf { it.isNotEmpty() }
        lateinit var wrapped: Runnable
        wrapped = Runnable {
            if (safeKey != null) delayedTasks.remove(safeKey, wrapped)
            safeRun(runnable)
        }
        if (safeKey != null) {
            cancel(safeKey)
            delayedTasks[safeKey] = wrapped
        }
        mainHandler.postDelayed(wrapped, delayMs.coerceAtLeast(0L))
    }

    /**
     * 按墙上时钟执行可唤醒的精确任务。Handler 仅作为进程前台时的同刻兜底。
     */
    fun runOnMainAtExact(key: String?, triggerAtMillis: Long, runnable: Runnable?) {
        if (runnable == null) return
        val safeKey = key?.takeIf { it.isNotEmpty() }
        if (safeKey == null) {
            runOnMainDelayed(null, triggerAtMillis - System.currentTimeMillis(), runnable)
            return
        }
        cancel(safeKey)
        val token = exactToken.incrementAndGet()
        val pendingIntent = exactPendingIntent(safeKey, token)
        if (pendingIntent == null) {
            runOnMainDelayed(safeKey, triggerAtMillis - System.currentTimeMillis(), runnable)
            return
        }
        val fallback = Runnable { dispatchExactTask(safeKey, token) }
        val task = ExactTask(token, pendingIntent, fallback, runnable)
        exactTasks[safeKey] = task
        mainHandler.postDelayed(
            fallback,
            (triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        )
        scheduleExactAlarm(triggerAtMillis, pendingIntent)
    }

    fun runAsync(runnable: Runnable?) {
        if (runnable == null) return
        executor.execute { safeRun(runnable) }
    }

    fun runOnce(key: String?, runnable: Runnable?): Boolean {
        if (TextUtils.isEmpty(key) || runnable == null) return false
        val old = lastRunTimes.putIfAbsent(key ?: "", System.currentTimeMillis())
        if (old != null) return false
        safeRun(runnable)
        return true
    }

    fun runThrottled(key: String?, intervalMs: Long, runnable: Runnable?): Boolean {
        if (TextUtils.isEmpty(key) || runnable == null) return false
        val now = System.currentTimeMillis()
        val safeKey = key ?: ""
        val old = lastRunTimes[safeKey]
        if (old != null && now - old < intervalMs.coerceAtLeast(0L)) return false
        lastRunTimes[safeKey] = now
        safeRun(runnable)
        return true
    }

    fun cancel(key: String?) {
        if (TextUtils.isEmpty(key)) return
        val old = delayedTasks.remove(key)
        if (old != null) mainHandler.removeCallbacks(old)
        exactTasks.remove(key)?.let(::cancelExactTask)
    }

    fun shutdown() {
        delayedTasks.keys.toList().forEach(::cancel)
        exactTasks.keys.toList().forEach(::cancel)
        if (exactReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(exactReceiver) }
        }
        executor.shutdownNow()
    }

    fun clearRunState(key: String?) {
        if (!TextUtils.isEmpty(key)) lastRunTimes.remove(key)
    }

    fun clearAllRunState() {
        lastRunTimes.clear()
    }

    private fun safeRun(runnable: Runnable) {
        try {
            runnable.run()
        } catch (e: Throwable) {
            log("任务执行失败: ${e.message}")
        }
    }

    private fun log(message: String) {
        logger?.log("[WeChatTaskApi] $message")
    }

    private fun registerExactReceiver(): Boolean {
        val filter = IntentFilter(ACTION_EXACT_TASK).apply {
            addDataScheme(EXACT_TASK_SCHEME)
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(exactReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(exactReceiver, filter)
            }
            true
        }.getOrElse {
            log("精确定时广播注册失败: ${it.message}")
            false
        }
    }

    private fun exactPendingIntent(key: String, token: Long): PendingIntent? {
        return runCatching {
            val intent = Intent(ACTION_EXACT_TASK).apply {
                setPackage(appContext.packageName)
                data = Uri.Builder()
                    .scheme(EXACT_TASK_SCHEME)
                    .authority(EXACT_TASK_AUTHORITY)
                    .appendPath(key)
                    .build()
                putExtra(EXTRA_EXACT_TASK_KEY, key)
                putExtra(EXTRA_EXACT_TASK_TOKEN, token)
            }
            PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrElse {
            log("精确定时 PendingIntent 创建失败: ${it.message}")
            null
        }
    }

    private fun scheduleExactAlarm(triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val manager = alarmManager
        if (manager == null || !exactReceiverRegistered) {
            log("精确定时不可用，已回退进程内调度")
            return
        }
        val triggerAt = triggerAtMillis.coerceAtLeast(System.currentTimeMillis())
        runCatching {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }.onFailure { exactError ->
            runCatching {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }.onFailure { fallbackError ->
                log(
                    "精确定时安装失败: exact=${exactError.message}, " +
                        "fallback=${fallbackError.message}"
                )
            }
        }
    }

    private fun dispatchExactTask(key: String, token: Long) {
        val task = exactTasks[key] ?: return
        if (task.token != token || !exactTasks.remove(key, task)) return
        mainHandler.removeCallbacks(task.fallback)
        runCatching { alarmManager?.cancel(task.pendingIntent) }
        safeRun(task.runnable)
    }

    private fun cancelExactTask(task: ExactTask) {
        mainHandler.removeCallbacks(task.fallback)
        runCatching { alarmManager?.cancel(task.pendingIntent) }
    }

    private data class ExactTask(
        val token: Long,
        val pendingIntent: PendingIntent,
        val fallback: Runnable,
        val runnable: Runnable
    )

    private companion object {
        const val ACTION_EXACT_TASK = "h.Hchat.action.EXACT_TASK"
        const val EXTRA_EXACT_TASK_KEY = "h.Hchat.extra.EXACT_TASK_KEY"
        const val EXTRA_EXACT_TASK_TOKEN = "h.Hchat.extra.EXACT_TASK_TOKEN"
        const val EXACT_TASK_SCHEME = "hchat-scheduled-task"
        const val EXACT_TASK_AUTHORITY = "runtime"
    }
}
