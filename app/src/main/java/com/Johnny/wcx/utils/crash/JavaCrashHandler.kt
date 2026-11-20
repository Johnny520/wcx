package com.Johnny.wcx.utils.crash

import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.crash.CrashInfoCollector.collectCrashInfo
import com.Johnny.wcx.utils.polyfills.getThreadId
import kotlin.system.exitProcess
import java.util.concurrent.atomic.AtomicBoolean

object JavaCrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "JavaCrashHandler"

    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    private val isHandling = AtomicBoolean(false)

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    fun uninstall() {
        if (defaultHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(defaultHandler)
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        if (!isHandling.compareAndSet(false, true)) {
            WeLogger.e(
                TAG,
                "recursive crash detected, delegating to default handler"
            )
            runCatching { WeLogger.flush() }
            defaultHandler?.uncaughtException(thread, throwable)
            return
        }

        try {
            WeLogger.e(TAG, "========================================")
            WeLogger.e(TAG, "Uncaught exception detected!")
            WeLogger.e(
                TAG,
                "Thread: " + thread.name + " (ID: " + thread.getThreadId() + ")"
            )
            WeLogger.e(TAG, "Exception: " + throwable.javaClass.name)
            WeLogger.e(TAG, "Message: " + throwable.message)
            runCatching {
                WeLogger.e(TAG, "Classification: " + CrashInfoCollector.classificationSummary(throwable))
            }
            WeLogger.e(TAG, "========================================")

            // 收集崩溃信息
            val crashInfo = collectCrashInfo(HostInfo.application, throwable, "JAVA")

            // 保存崩溃日志（标记为Java崩溃）
            val logPath = CrashLogsManager.saveCrashLog(crashInfo, true)
            if (logPath != null) {
                WeLogger.i(TAG, "java crash log saved to: $logPath")
            } else {
                WeLogger.e(TAG, "failed to save Java crash log")
            }

            WeLogger.e(TAG, "crash details", throwable)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "error while handling crash", e)
        } finally {
            isHandling.set(false)

            // 调用默认处理器，让应用正常崩溃
            if (defaultHandler != null) {
                WeLogger.i(TAG, "delegating to default handler")
            } else {
                // 如果没有默认处理器，手动终止进程
                WeLogger.e(TAG, "no default handler, killing process")
            }

            // The logger is asynchronous; make the crash details durable before the process exits.
            runCatching { WeLogger.flush() }

            // 模块自己的后台线程/协程抛异常时只记录、不结束宿主进程。
            // Android 上任意线程的未捕获异常都会结束整个进程，用户看到的就是「微信像被强行停止」。
            if (isModuleBackgroundCrash(thread, throwable)) {
                WeLogger.e(
                    TAG,
                    "module background thread crashed, host process kept alive: " + thread.name,
                )
                return
            }

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable)
            } else {
                exitProcess(1)
            }
        }
    }

    private val MODULE_PACKAGE_PREFIXES = arrayOf(
        "com.Johnny.wcx", "h.Hchat", "me.hd.wauxv", "bsh.", "org.luckypray.dexkit",
    )

    /**
     * 判定是否为「模块自身后台线程」的崩溃。
     *
     * 只拦截同时满足以下条件的崩溃（仅记录、不杀进程）：
     * 1) 不是主线程——主线程崩溃仍交给宿主，避免掩盖真正的启动失败；
     * 2) 堆栈里出现模块/脚本运行时的包名；
     * 3) 线程是 daemon，或线程名属于模块命名规范。
     */
    private fun isModuleBackgroundCrash(thread: Thread, throwable: Throwable): Boolean {
        if (thread === android.os.Looper.getMainLooper()?.thread) return false
        val involvesModule = throwable.stackTrace.any { element ->
            MODULE_PACKAGE_PREFIXES.any { prefix -> element.className.startsWith(prefix) }
        }
        if (!involvesModule) return false
        val name = thread.name
        return thread.isDaemon ||
            name.startsWith("WCX") ||
            name.startsWith("Hchat") ||
            name.startsWith("hchat") ||
            name.startsWith("WeKit") ||
            name.startsWith("DefaultDispatcher") ||
            name.startsWith("kotlinx.coroutines")
    }
}
