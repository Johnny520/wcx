package com.Johnny.wcx.ai

import android.os.Handler
import android.os.HandlerThread
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import java.util.Calendar

/**
 * 每天自动增量整理（同步自知友的 AiAutoIncrementalScheduler，做成全局开关）。
 *
 * 已就绪的档案每天补一次新增聊天记录：只读「最新一条事件之后」的部分，
 * 不会重跑全量，花费可控。
 *
 * 为什么默认关闭：这会花用户的模型额度，必须他自己打开。
 * 计时用进程内 HandlerThread（每 15 分钟看一眼），微信被杀就下次启动按
 * 「今天是否已跑」补一次，当天只补一次。
 */
object AutoIncrementalScheduler {

    private const val TAG = "AutoIncremental"

    private const val KEY_ENABLED = "ai_auto_incremental_enabled"
    private const val KEY_HOUR = "ai_auto_incremental_hour"
    private const val KEY_LAST_DAY = "ai_auto_incremental_last_day"

    private const val DEFAULT_HOUR = 22

    /** 检查间隔：够密，又不至于频繁唤醒。 */
    private const val TICK_MS = 15 * 60 * 1000L

    /** 多个好友之间的启动间隔：错开一点，避免同一秒挤进队列被服务商限流。 */
    private const val STAGGER_MS = 3_000L

    val HOUR_OPTIONS = intArrayOf(20, 21, 22, 23)

    @Volatile
    private var thread: HandlerThread? = null

    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var running = false

    val isEnabled: Boolean
        get() = runCatching { WePrefs.getBoolOrDef(KEY_ENABLED, false) }.getOrDefault(false)

    fun setEnabled(enabled: Boolean) {
        runCatching { WePrefs.putBool(KEY_ENABLED, enabled) }
        WeLogger.i(TAG, "自动增量整理 -> ${if (enabled) "开" else "关"}")
        if (enabled) {
            ensureThread()
            startTicking()
        } else {
            handler?.removeCallbacksAndMessages(null)
        }
    }

    val hour: Int
        get() {
            val value = runCatching { WePrefs.getIntOrDef(KEY_HOUR, DEFAULT_HOUR) }.getOrDefault(DEFAULT_HOUR)
            return if (value in HOUR_OPTIONS) value else DEFAULT_HOUR
        }

    fun setHour(value: Int) {
        val safe = if (value in HOUR_OPTIONS) value else DEFAULT_HOUR
        runCatching { WePrefs.putInt(KEY_HOUR, safe) }
        WeLogger.i(TAG, "自动增量整理时间 -> ${safe} 点")
    }

    fun onEnable() {
        if (!isEnabled) return
        ensureThread()
        startTicking()
    }

    fun onDisable() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** 手动跑一次（设置页按钮）。 */
    fun runNow(onDone: (String) -> Unit) {
        ensureThread()
        val h = handler ?: return
        h.post {
            val message = runBatch()
            Handler(android.os.Looper.getMainLooper()).post { onDone(message) }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun ensureThread() {
        if (thread != null) return
        val t = HandlerThread("WCX-AutoIncremental").apply { start() }
        thread = t
        handler = Handler(t.looper)
    }

    private fun startTicking() {
        val h = handler ?: return
        h.removeCallbacksAndMessages(null)
        h.post(checkRunnable)
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isEnabled) return
            val today = todayKey()
            val last = runCatching { WePrefs.getStringOrDef(KEY_LAST_DAY, "") }.getOrDefault("")
            val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (last != today && nowHour >= hour) {
                val message = runBatch()
                WeLogger.i(TAG, "每日增量整理：$message")
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    /** 逐个跑已就绪档案的增量整理；返回一句话结果。 */
    private fun runBatch(): String {
        if (running) return "上一轮还在跑，稍后再试"
        running = true
        return try {
            val ready = runCatching {
                AiProfileStore.loadIndex().profiles.filter { it.state == AiProfileState.READY }
            }.getOrDefault(emptyList())
            if (ready.isEmpty()) {
                "没有已就绪的好友档案"
            } else {
                var started = 0
                ready.forEach { summary ->
                    if (AiProfileInitializer.startIncremental(summary.wxId)) {
                        started++
                        Thread.sleep(STAGGER_MS)
                    }
                }
                runCatching { WePrefs.putString(KEY_LAST_DAY, todayKey()) }
                "已开始 $started/${ready.size} 位好友的增量整理"
            }
        } catch (error: Throwable) {
            WeLogger.e(TAG, "增量整理异常", error)
            error.message ?: "增量整理异常"
        } finally {
            running = false
        }
    }

    private fun todayKey(): String = java.time.LocalDate.now().toString()
}
