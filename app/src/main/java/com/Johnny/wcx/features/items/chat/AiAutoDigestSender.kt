package com.Johnny.wcx.features.items.chat

import android.os.Handler
import android.os.HandlerThread
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import java.util.Calendar

/**
 * 每天定时把某个会话的 AI 总结做成图片，自动发回该会话。
 *
 * 配置来源只有一处：聊天页双击 → AI 工作台 →「每天自动发」，
 * 把当前会话、当前标题记下来；AI 页「设置」里只能关掉它（关掉后要重新在工作台里设）。
 *
 * 调度用一条自己的 HandlerThread 每 15 分钟看一次，到点且今天还没跑过就执行：
 * 生成总结文本 → 渲染分享图 → 发送。默认关闭，用户不主动开启就不会发任何消息。
 */
object AiAutoDigestSender {

    private const val TAG = "AiAutoDigest"

    private const val KEY_ENABLED = "ai_auto_digest_enabled"
    private const val KEY_TALKER = "ai_auto_digest_talker"
    private const val KEY_TITLE = "ai_auto_digest_title"
    private const val KEY_HOUR = "ai_auto_digest_hour"
    private const val KEY_STYLE = "ai_auto_digest_style"
    private const val KEY_LAST_DAY = "ai_auto_digest_last_day"

    private const val TICK_MS = 15 * 60 * 1000L

    private const val DEFAULT_HOUR = 21

    val HOUR_OPTIONS = intArrayOf(20, 21, 22, 23)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var running = false

    // ── 配置 ────────────────────────────────────────────────────────────────

    val isEnabled: Boolean
        get() = runCatching { WePrefs.getBoolOrDef(KEY_ENABLED, false) }.getOrDefault(false)

    val hour: Int
        get() = runCatching {
            val value = WePrefs.getIntOrDef(KEY_HOUR, DEFAULT_HOUR)
            if (value in HOUR_OPTIONS) value else DEFAULT_HOUR
        }.getOrDefault(DEFAULT_HOUR)

    /** 目标会话的展示名（没设置过就是空）。 */
    val targetTitle: String
        get() = runCatching { WePrefs.getStringOrDef(KEY_TITLE, "") }.getOrDefault("")

    private val talker: String
        get() = runCatching { WePrefs.getStringOrDef(KEY_TALKER, "") }.getOrDefault("")

    private val style: AiShareImage.Style
        get() = runCatching {
            val name = WePrefs.getStringOrDef(KEY_STYLE, AiShareImage.Style.CLASSIC.name)
            AiShareImage.Style.valueOf(name)
        }.getOrDefault(AiShareImage.Style.CLASSIC)

    fun enable(talker: String, title: String, style: AiShareImage.Style = AiShareImage.Style.CLASSIC) {
        if (talker.isBlank()) return
        runCatching {
            WePrefs.putString(KEY_TALKER, talker)
            WePrefs.putString(KEY_TITLE, title)
            WePrefs.putString(KEY_STYLE, style.name)
            if (WePrefs.getIntOrDef(KEY_HOUR, DEFAULT_HOUR) !in HOUR_OPTIONS) {
                WePrefs.putInt(KEY_HOUR, DEFAULT_HOUR)
            }
            WePrefs.putBool(KEY_ENABLED, true)
        }.onFailure { WeLogger.e(TAG, "保存自动发送配置失败", it) }
        WeLogger.i(TAG, "已开启每日自动总结：$title（$talker）")
    }

    fun disable() {
        runCatching { WePrefs.putBool(KEY_ENABLED, false) }
            .onFailure { WeLogger.e(TAG, "关闭自动发送失败", it) }
        WeLogger.i(TAG, "已关闭每日自动总结")
    }

    fun setHour(value: Int) {
        val safe = if (value in HOUR_OPTIONS) value else DEFAULT_HOUR
        runCatching { WePrefs.putInt(KEY_HOUR, safe) }
    }

    // ── 调度 ────────────────────────────────────────────────────────────────

    fun start() {
        if (thread != null) return
        val worker = HandlerThread("WCX-AiAutoDigest").apply { start() }
        thread = worker
        handler = Handler(worker.looper)
        handler?.post(checkRunnable)
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** 立刻跑一次（便于验证）。返回一句结果。 */
    fun runNow(onDone: (String) -> Unit) {
        start()
        val h = handler ?: return
        h.post {
            val message = runBatch()
            Handler(android.os.Looper.getMainLooper()).post { onDone(message) }
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isEnabled) {
                val today = todayKey()
                val last = runCatching { WePrefs.getStringOrDef(KEY_LAST_DAY, "") }.getOrDefault("")
                val nowHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                if (last != today && nowHour >= hour) {
                    val message = runBatch()
                    WeLogger.i(TAG, "每日自动总结：$message")
                }
            }
            handler?.postDelayed(this, TICK_MS)
        }
    }

    private fun runBatch(): String {
        if (running) return "上一轮还在跑"
        if (talker.isBlank()) return "没有设置目标会话"
        running = true
        return try {
            val title = targetTitle.ifBlank { talker }
            val text = AiWorkbench
                .run(AiWorkbench.Task.GROUP_SUMMARY, talker, title, talker.contains("@chatroom"), "")
                .getOrElse { return "生成失败：${it.message}" }
            val bitmap = AiShareImage.render(
                style = style,
                title = "$title · AI 总结",
                subtitle = AiWorkbench.timeLabel(),
                body = text,
            )
            val file = AiShareImage.saveTemp(HostInfo.application, bitmap)
                ?: return "保存图片失败"
            val sent = AiShareImage.sendToChat(talker, file)
            runCatching { file.delete() }
            if (sent) {
                runCatching { WePrefs.putString(KEY_LAST_DAY, todayKey()) }
                "已发送到「$title」"
            } else {
                "生成成功但发送失败"
            }
        } catch (error: Throwable) {
            WeLogger.e(TAG, "自动总结执行异常", error)
            "异常：${error.message}"
        } finally {
            running = false
        }
    }

    private fun todayKey(): String = java.time.LocalDate.now().toString()
}
