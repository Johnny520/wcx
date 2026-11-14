package com.Johnny.wcx.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主线程卡死取证看门狗。
 *
 * 背景：用户反馈「在主界面点某个会话，一下卡住，划任务栏也卡，最后强杀重启」。
 * 微信进程主线程一旦进入死锁 / 死循环 / 超长同步 IO，模块自己的日志也写不出来
 * （WeLogger 在主线程上调），现场完全没有证据。原先的实现依赖
 * [Looper.setMessageLogging]，但它从未被安装，且每条消息都要走一遍字符串判断，
 * 还会与微信自己的性能统计抢同一个全局回调。
 *
 * 现在改为「心跳」方案：
 * - 主线程每 [BEAT_MS] 投递一次心跳，更新 [lastBeatAt]（一次赋值，几乎零开销）；
 * - 独立后台线程每 [POLL_MS] 检查心跳时间，超过 [BLOCK_WARN_MS] 没有任何心跳
 *   就判定主线程卡死，抓主线程堆栈落盘；
 * - 抓栈放在**新起的临时线程**里，避免检测线程自己被 `Thread.getStackTrace()`
 *   的 suspend 卡住；
 * - 卡死恢复后补记一条「恢复」，能算出这次卡了多久（区分「卡 3 秒」和「卡 40 秒」）。
 *
 * 记录同时写进 WCX 运行日志（WeLogger，I 级会落盘）与 SharedPreferences，
 * 后者供「模块自检」页展示（进程被杀后仍能看到）。
 */
object MainThreadWatchdog {

    private const val TAG = "MainThreadWatchdog"

    /** 心跳间隔：主线程每 500ms 更新一次时间戳。 */
    private const val BEAT_MS = 500L

    /** 心跳超过该时长未更新即判定主线程卡死。 */
    private const val BLOCK_WARN_MS = 3_000L

    /** 检测线程轮询间隔。 */
    private const val POLL_MS = 1_000L

    /** 同一次卡死内两次抓栈的最小间隔，避免刷屏。 */
    private const val REPORT_GAP_MS = 5_000L

    /** 同一次卡死最多抓几次栈（卡 40 秒也只需要几个快照就能看出卡在哪）。 */
    private const val MAX_SNAPSHOT_PER_BLOCK = 3

    private const val PREFS = "wcx_main_thread_watchdog"
    private const val KEY_RECENT = "recent"
    private const val MAX_KEEP = 8

    @Volatile
    private var installed = false

    /** 主线程最后一次心跳时间（uptimeMillis）。 */
    @Volatile
    private var lastBeatAt = 0L

    /** 当前是否处于（已上报的）卡死状态。 */
    @Volatile
    private var inBlock = false

    /** 本次卡死开始时间。 */
    @Volatile
    private var blockStartAt = 0L

    /** 本次卡死已抓栈次数。 */
    @Volatile
    private var snapshots = 0

    @Volatile
    private var lastReportAt = 0L

    /** 主线程最近处理的消息描述（仅用于报告，不参与判定）。 */
    @Volatile
    private var msgDesc = ""

    private var beatHandler: Handler? = null

    private val beating = AtomicBoolean(false)

    fun install() {
        if (installed) return
        installed = true
        runCatching {
            val mainLooper = Looper.getMainLooper()
            val mainThread = mainLooper.thread

            // 只记录「当前在处理哪条消息」，不参与卡死判定：startsWith 两次 + 一次引用赋值。
            runCatching {
                mainLooper.setMessageLogging { line ->
                    if (!line.isNullOrEmpty() && line.startsWith(">>>")) msgDesc = line
                }
            }

            lastBeatAt = SystemClock.uptimeMillis()
            beatHandler = Handler(mainLooper)
            startBeating()

            Thread({
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                while (true) {
                    runCatching { Thread.sleep(POLL_MS) }
                    runCatching { check(mainThread) }
                }
            }, "WCX-MainWatchdog").apply { isDaemon = true }.start()

            WeLogger.i(TAG, "main thread watchdog installed (beat=${BEAT_MS}ms, warn=${BLOCK_WARN_MS}ms)")
        }.onFailure {
            WeLogger.w(TAG, "install failed: " + (it.message ?: it.javaClass.simpleName))
        }
    }

    /** 主线程心跳链：卡死时这条 Runnable 排不到执行，心跳自然停摆。 */
    private fun startBeating() {
        if (!beating.compareAndSet(false, true)) return
        val handler = beatHandler ?: return
        val task = object : Runnable {
            override fun run() {
                lastBeatAt = SystemClock.uptimeMillis()
                handler.postDelayed(this, BEAT_MS)
            }
        }
        handler.postDelayed(task, BEAT_MS)
        // 主线程恢复后重启心跳链（卡死期间 postDelayed 会积压，恢复后可能一次性跑一批）。
        beating.set(true)
    }

    private fun check(mainThread: Thread) {
        val now = SystemClock.uptimeMillis()
        val silent = now - lastBeatAt

        if (silent < BLOCK_WARN_MS) {
            if (inBlock) {
                // 恢复：补记一条，能算出这次卡了多久。
                val total = now - blockStartAt
                inBlock = false
                snapshots = 0
                runCatching { record("主线程恢复，本次卡住 ${total}ms") }
                // 心跳可能因积压丢失，重新拉起。
                beating.set(false)
                startBeating()
            }
            return
        }

        if (!inBlock) {
            inBlock = true
            blockStartAt = now - silent
            snapshots = 0
            lastReportAt = 0L
        }
        if (snapshots >= MAX_SNAPSHOT_PER_BLOCK) return
        if (now - lastReportAt < REPORT_GAP_MS) return
        lastReportAt = now
        snapshots++

        val desc = msgDesc
        val no = snapshots
        // 抓栈放到临时线程：Thread.getStackTrace 需要 suspend 目标线程，
        // 目标卡在 native 时可能一起挂住，不能拖住检测线程。
        Thread({
            runCatching {
                val stack = mainThread.stackTrace
                val sb = StringBuilder()
                sb.append("主线程卡死 ≥").append(silent).append("ms（第 ").append(no).append(" 次快照）")
                if (desc.isNotEmpty()) sb.append("，最近消息：").append(desc.take(120))
                sb.append('\n')
                stack.take(30).forEach { sb.append("  at ").append(it).append('\n') }
                record(sb.toString().trimEnd())
                WeLogger.e(TAG, sb.toString().trimEnd())
            }
        }, "WCX-MainWatchdogDump-$no").apply { isDaemon = true }.start()
    }

    /** 落盘：SharedPreferences（供模块自检展示）+ 已由调用方写入运行日志。 */
    private fun record(text: String) {
        val ctx: Context? = HostInfo.application ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val head = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = head + " " + text
            val list = (prefs.getString(KEY_RECENT, "").orEmpty()
                .split('\n').filter { it.isNotBlank() } + entry).takeLast(MAX_KEEP)
            prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
        }
    }

    /** 供诊断页展示的最近卡死记录（新的在前）。 */
    fun recent(context: Context): List<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT, "").orEmpty()
            .split('\n').filter { it.isNotBlank() }
            .reversed()
    }.getOrDefault(emptyList())
}
