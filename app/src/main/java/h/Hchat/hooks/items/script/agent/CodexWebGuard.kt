package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// 网页版保活：服务掉线自动拉起；开关持久化，微信进程重启后自动恢复
object CodexWebGuard {
    private const val TAG = "[Hchat:CodexWebGuard]"
    private const val PREFS_NAME = "hchat_codex_web"
    private const val KEY_AUTO_KEEP = "auto_keep"
    private const val TICK_SECONDS = 15L
    private const val MAX_FAILURES = 6

    private val starting = AtomicBoolean(false)
    private val failures = AtomicInteger(0)
    private val restarts = AtomicInteger(0)

    @Volatile
    private var timer: ScheduledExecutorService? = null

    fun isEnabled(context: Context): Boolean = runCatching {
        prefs(context).getBoolean(KEY_AUTO_KEEP, false)
    }.getOrDefault(false)

    fun restartCount(): Int = restarts.get()

    fun enable(context: Context) {
        val ctx = app(context)
        runCatching { prefs(ctx).edit().putBoolean(KEY_AUTO_KEEP, true).apply() }
        schedule(ctx)
        ensureRunning(ctx, "开启保活")
    }

    fun disable(context: Context) {
        val ctx = app(context)
        runCatching { prefs(ctx).edit().putBoolean(KEY_AUTO_KEEP, false).apply() }
        stopTimer()
        failures.set(0)
    }

    // 模块随微信进程启动时调用：上次开着就自动把服务拉回来
    fun restore(context: Context) {
        val ctx = app(context)
        if (!isEnabled(ctx)) return
        schedule(ctx)
        ensureRunning(ctx, "进程重启恢复")
    }

    private fun schedule(context: Context) {
        if (timer?.isShutdown == false) return
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Hchat-CodexWebGuard").apply { isDaemon = true }
        }
        timer = executor
        executor.scheduleWithFixedDelay(
            {
                runCatching { tick(context) }
                    .onFailure { HLog.e("$TAG 保活轮询异常: ${it.message}", it) }
            },
            TICK_SECONDS,
            TICK_SECONDS,
            TimeUnit.SECONDS
        )
    }

    private fun stopTimer() {
        runCatching { timer?.shutdownNow() }
        timer = null
    }

    private fun tick(context: Context) {
        if (!isEnabled(context)) {
            stopTimer()
            return
        }
        if (CodexWebServer.isRunning()) {
            failures.set(0)
            return
        }
        if (failures.get() >= MAX_FAILURES) return
        ensureRunning(context, "掉线重启")
    }

    private fun ensureRunning(context: Context, reason: String) {
        if (CodexWebServer.isRunning()) {
            failures.set(0)
            return
        }
        // 正在下载/安装运行环境时别插手，免得拉起半装好的目录
        if (CodexWebUpdater.isBusy()) return
        if (!CodexWebUpdater.isInstalled(context)) {
            HLog.e("$TAG 网页版运行环境不存在，停止自动重启")
            disable(context)
            return
        }
        if (!starting.compareAndSet(false, true)) return
        Thread({
            try {
                CodexWebServer.start(context)
                    .onSuccess {
                        failures.set(0)
                        restarts.incrementAndGet()
                        HLog.e("$TAG 网页版服务已就绪（$reason）")
                    }
                    .onFailure {
                        HLog.e("$TAG 拉起失败第 ${failures.incrementAndGet()} 次: ${it.message}", it)
                    }
            } finally {
                starting.set(false)
            }
        }, "Hchat-CodexWebStart").apply { isDaemon = true }.start()
    }

    private fun app(context: Context): Context = context.applicationContext ?: context

    private fun prefs(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
