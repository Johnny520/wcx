package com.Johnny.wcx.ai.daily

import android.os.Handler
import android.os.HandlerThread
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * 每日总结的定时层：每天到了设定钟点跑一次。
 *
 * 计时器放在进程内（HandlerThread），不注册 AlarmManager：
 * 微信进程平时常驻，到点就能跑；被系统杀掉也只是「当天没跑」，
 * 下次启动时 [onEnable] 会按「今天是否已生成」补一次，当天只补一次。
 */
object DailyReportScheduler {

    private const val TAG = "DailyReportScheduler"

    private const val KEY_ENABLED = "ai_daily_report_enabled"
    private const val KEY_HOUR = "ai_daily_report_hour"
    private const val KEY_LAST_DAY = "ai_daily_report_last_day"

    private const val DEFAULT_HOUR = 22

    /** 可选钟点：晚上这几个点最像「今天收工」。 */
    val HOUR_OPTIONS = intArrayOf(20, 21, 22, 23)

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var running = false

    val isEnabled: Boolean
        get() = runCatching { WePrefs.getBoolOrDef(KEY_ENABLED, true) }.getOrDefault(true)

    fun setEnabled(enabled: Boolean) {
        runCatching { WePrefs.putBool(KEY_ENABLED, enabled) }
        WeLogger.i(TAG, "每日总结自动生成 -> ${if (enabled) "开" else "关"}")
        if (enabled) scheduleNext() else handler?.removeCallbacksAndMessages(null)
    }

    val hour: Int
        get() {
            val value = runCatching { WePrefs.getIntOrDef(KEY_HOUR, DEFAULT_HOUR) }.getOrDefault(DEFAULT_HOUR)
            return if (value in HOUR_OPTIONS) value else DEFAULT_HOUR
        }

    fun setHour(value: Int) {
        val safe = if (value in HOUR_OPTIONS) value else DEFAULT_HOUR
        runCatching { WePrefs.putInt(KEY_HOUR, safe) }
        WeLogger.i(TAG, "每日总结时间 -> ${safe} 点")
        scheduleNext()
    }

    fun onEnable() {
        if (!isEnabled) return
        ensureThread()
        // 启动补跑：今天已经过点、但还没生成 → 立刻补一份。
        if (shouldCatchUp()) {
            WeLogger.i(TAG, "启动补跑今天的总结")
            runGeneration()
        }
        scheduleNext()
    }

    fun onDisable() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** 手动触发（设置页按钮）。 */
    fun runNow(onDone: (String) -> Unit) {
        ensureThread()
        val h = handler ?: return
        h.post {
            val message = generateBlocking()
            Handler(android.os.Looper.getMainLooper()).post { onDone(message) }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun ensureThread() {
        if (thread != null) return
        val t = HandlerThread("WCX-DailyReport").apply { start() }
        thread = t
        handler = Handler(t.looper)
    }

    private fun shouldCatchUp(): Boolean {
        val now = Calendar.getInstance()
        if (now.get(Calendar.HOUR_OF_DAY) < hour) return false
        val today = DailyReportStore.todayKey()
        val last = runCatching { WePrefs.getStringOrDef(KEY_LAST_DAY, "") }.getOrDefault("")
        if (last == today) return false
        return DailyReportStore.read(today) == null
    }

    private fun scheduleNext() {
        if (!isEnabled) return
        ensureThread()
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        val delay = millisUntilNextRun()
        h.postDelayed({
            if (isEnabled) {
                runGeneration()
                scheduleNext()
            }
        }, delay)
        WeLogger.i(TAG, "下次每日总结：${delay / 60000} 分钟后")
    }

    private fun millisUntilNextRun(): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        return (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
    }

    private fun runGeneration() {
        val h = handler ?: return
        h.post { generateBlocking() }
    }

    /** 真正生成；返回给用户看的一句话结果。 */
    private fun generateBlocking(): String {
        if (running) return "上一次总结还在生成，稍后再试"
        running = true
        return try {
            val result = runBlocking { DailyReportRunner.generate() }
            result.fold(
                onSuccess = {
                    runCatching { WePrefs.putString(KEY_LAST_DAY, it.dateKey) }
                    WeLogger.i(TAG, "每日总结完成：${it.dateKey}（好友 ${it.friendCount} / 群 ${it.groupCount}）")
                    "今日总结已生成"
                },
                onFailure = { error ->
                    WeLogger.w(TAG, "每日总结失败：${error.message}")
                    error.message ?: "生成失败"
                },
            )
        } catch (error: Throwable) {
            WeLogger.e(TAG, "每日总结异常", error)
            error.message ?: "生成异常"
        } finally {
            running = false
        }
    }
}
