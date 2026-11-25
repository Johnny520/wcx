package com.Johnny.wcx.utils.crash

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getSystemService
import com.Johnny.wcx.utils.polyfills.getThreadId
import java.io.BufferedReader
import java.io.FileReader
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃信息收集工具类
 * 负责收集设备信息、应用信息、线程信息、堆栈信息等
 *
 * @author cwuom
 * @since 1.0.0
 */
object CrashInfoCollector {
    /**
     * 收集完整的崩溃信息
     *
     * @param context   上下文
     * @param throwable 异常对象
     * @param crashType 崩溃类型 (JAVA/NATIVE)
     * @return 格式化的崩溃信息
     */
    fun collectCrashInfo(context: Context, throwable: Throwable, crashType: String): String {
        // 崩溃头部信息

        val sb = "========================================\n" +
                "WCX Crash Report\n" +
                "========================================\n\n" +
                "Crash Time: " + currentTime + "\n" +
                "Crash Type: " + crashType + "\n\n" +
                "========================================\n" +
                "Crash Classification\n" +
                "========================================\n" +
                classifyCrash(throwable).render() + "\n" +
                "========================================\n" +
                "Device Information\n" +
                "========================================\n" +
                collectDeviceInfo() + "\n" +
                "========================================\n" +
                "Application Information\n" +
                "========================================\n" +
                collectAppInfo(context) + "\n" +
                "========================================\n" +
                "Module Information\n" +
                "========================================\n" +
                collectModuleInfo() + "\n" +
                "========================================\n" +
                "Memory Information\n" +
                "========================================\n" +
                collectMemoryInfo(context) + "\n" +
                "========================================\n" +
                "Thread Information\n" +
                "========================================\n" +
                collectThreadInfo() + "\n" +
                "========================================\n" +
                "Exception Stack Trace\n" +
                "========================================\n" +
                getStackTraceString(throwable) + "\n" +
                "========================================\n" +
                "All Threads Stack Trace\n" +
                "========================================\n" +
                collectAllThreadsStackTrace() + "\n" +
                "========================================\n" +
                "End of Crash Report\n" +
                "========================================\n"

        return sb
    }

    private val currentTime: String
        /**
         * 获取当前时间
         */
        get() {
            val sdf = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSS",
                Locale.getDefault()
            )
            return sdf.format(Date())
        }

    /**
     * 收集设备信息
     */
    private fun collectDeviceInfo(): String {
        val sb = StringBuilder()
        sb.append("Brand: ").append(Build.BRAND).append("\n")
        sb.append("Model: ").append(Build.MODEL).append("\n")
        sb.append("Device: ").append(Build.DEVICE).append("\n")
        sb.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n")
        sb.append("Product: ").append(Build.PRODUCT).append("\n")
        sb.append("Hardware: ").append(Build.HARDWARE).append("\n")
        sb.append("Board: ").append(Build.BOARD).append("\n")
        sb.append("Display: ").append(Build.DISPLAY).append("\n")
        sb.append("Fingerprint: ").append(Build.FINGERPRINT).append("\n")
        sb.append("Android Version: ").append(Build.VERSION.RELEASE).append("\n")
        sb.append("SDK Version: ").append(Build.VERSION.SDK_INT).append("\n")
        sb.append("Supported ABIs: ")
        for (abi in Build.SUPPORTED_ABIS) {
            sb.append(abi).append(" ")
        }
        sb.append("\n")
        return sb.toString()
    }

    /**
     * 收集应用信息
     */
    private fun collectAppInfo(context: Context): String {
        val sb = StringBuilder()
        try {
            val packageInfo =
                context.packageManager.getPackageInfo(context.packageName, 0)
            sb.append("Package Name: ").append(packageInfo.packageName).append("\n")
            sb.append("Version Name: ").append(packageInfo.versionName).append("\n")
            sb.append("Version Code: ").append(packageInfo.longVersionCode).append("\n")
            sb.append("Process ID: ").append(Process.myPid()).append("\n")
            sb.append("Thread ID: ").append(Thread.currentThread().getThreadId()).append("\n")
            sb.append("Process Name: ").append(getProcessName(context)).append("\n")
        } catch (e: Exception) {
            sb.append("Failed to collect app info: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    private fun collectModuleInfo(): String {
        return buildString {
            appendLine("Version Name: ${BuildConfig.VERSION_NAME}")
            appendLine("Version Code: ${BuildConfig.VERSION_CODE}")
            // 版本号保持纯版本号，具体构建落在日志里区分
            appendLine("Commit Hash: ${BuildConfig.COMMIT_HASH.ifEmpty { "unknown" }}")
            appendLine("Build Time: ${BuildConfig.BUILD_TIMESTAMP}")
        }
    }

    /**
     * 收集内存信息
     */
    private fun collectMemoryInfo(context: Context): String {
        val sb = StringBuilder()
        try {
            // 应用内存信息
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val usedMemory = totalMemory - freeMemory

            sb.append("Max Memory: ").append(maxMemory).append(" MB\n")
            sb.append("Total Memory: ").append(totalMemory).append(" MB\n")
            sb.append("Used Memory: ").append(usedMemory).append(" MB\n")
            sb.append("Free Memory: ").append(freeMemory).append(" MB\n")

            // Native内存信息
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            sb.append("Native Heap Size: ").append(memoryInfo.nativePss).append(" KB\n")
            sb.append("Dalvik Heap Size: ").append(memoryInfo.dalvikPss).append(" KB\n")
            sb.append("Total PSS: ").append(memoryInfo.totalPss).append(" KB\n")

            // 系统内存信息
            val activityManager = context.getSystemService<ActivityManager>()
            val systemMemInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(systemMemInfo)
            sb.append("System Available Memory: ").append(systemMemInfo.availMem / 1024 / 1024)
                .append(" MB\n")
            sb.append("System Total Memory: ").append(systemMemInfo.totalMem / 1024 / 1024)
                .append(" MB\n")
            sb.append("System Low Memory: ").append(systemMemInfo.lowMemory).append("\n")
        } catch (e: Exception) {
            sb.append("Failed to collect memory info: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 收集线程信息
     */
    private fun collectThreadInfo(): String {
        val sb = StringBuilder()
        try {
            val currentThread = Thread.currentThread()
            sb.append("Current Thread: ").append(currentThread.name).append("\n")
            sb.append("Thread ID: ").append(currentThread.getThreadId()).append("\n")
            sb.append("Thread Priority: ").append(currentThread.priority).append("\n")
            sb.append("Thread State: ").append(currentThread.state).append("\n")
            sb.append("Thread Group: ").append(currentThread.threadGroup!!.name ?: "null")
                .append("\n")

            // 活跃线程数
            var rootGroup = Thread.currentThread().threadGroup!!
            while (rootGroup.parent != null) {
                rootGroup = rootGroup.parent
            }
            sb.append("Active Thread Count: ").append(rootGroup.activeCount()).append("\n")
        } catch (e: Exception) {
            sb.append("Failed to collect thread info: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 获取异常堆栈字符串
     */
    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    /**
     * 收集所有线程的堆栈信息
     */
    private fun collectAllThreadsStackTrace(): String {
        val sb = StringBuilder()
        try {
            val allStackTraces = Thread.getAllStackTraces()
            sb.append("Total Threads: ").append(allStackTraces.size).append("\n\n")

            for (entry in allStackTraces.entries) {
                val thread = entry.key
                val stackTrace = entry.value

                sb.append("Thread: ").append(thread.name)
                    .append(" (ID: ").append(thread.getThreadId())
                    .append(", State: ").append(thread.state)
                    .append(", Priority: ").append(thread.priority)
                    .append(")\n")

                if (stackTrace != null && stackTrace.size > 0) {
                    for (element in stackTrace) {
                        sb.append("    at ").append(element.toString()).append("\n")
                    }
                } else {
                    sb.append("    (No stack trace available)\n")
                }
                sb.append("\n")
            }
        } catch (e: Exception) {
            sb.append("Failed to collect all threads stack trace: ").append(e.message).append("\n")
        }
        return sb.toString()
    }

    /**
     * 获取进程名称
     */
    private fun getProcessName(context: Context): String {
        try {
            val pid = Process.myPid()
            val activityManager =
                context.getSystemService<ActivityManager>()
            for (processInfo in activityManager.runningAppProcesses) {
                if (processInfo.pid == pid) {
                    return processInfo.processName
                }
            }

            // 备用方法：读取 /proc/self/cmdline
            val reader = BufferedReader(FileReader("/proc/self/cmdline"))
            var processName = reader.readLine()
            reader.close()
            if (processName != null) {
                processName = processName.trim { it <= ' ' }
                if (!processName.isEmpty()) {
                    return processName
                }
            }
        } catch (e: Exception) {
            WeLogger.e("CrashInfoCollector", "error while getting process name", e)
        }
        return "Unknown"
    }

    // ==================== 崩溃分类 ====================
    // 目的：在报告头部直接给出「是否模块导致」的初判，避免拿到日志后无从分辨。
    // 判定只依赖异常类型与调用栈是否出现模块包名，属于客观事实，不做模糊猜测。

    private data class CrashClass(
        val code: String,
        val title: String,
        val reason: String,
        val advice: String,
    ) {
        fun render(): String = buildString {
            appendLine("Category: $code")
            appendLine("Title: $title")
            appendLine("Reason: $reason")
            if (advice.isNotEmpty()) appendLine("Advice: $advice")
        }
    }

    /** 供 JavaCrashHandler 打日志用的一行分类摘要。 */
    fun classificationSummary(throwable: Throwable): String {
        val c = classifyCrash(throwable)
        return c.code + " | " + c.title
    }

    private val MODULE_FRAME_PREFIXES = arrayOf(
        "com.Johnny.wcx", "h.Hchat", "me.hd.wauxv", "bsh.", "org.luckypray.dexkit",
    )

    /** 沿 cause 链收集异常（最多 16 层，防自引用死循环）。 */
    private fun causeChain(t: Throwable): List<Throwable> {
        val out = ArrayList<Throwable>(4)
        var cur: Throwable? = t
        var guard = 0
        while (cur != null && guard < 16) {
            out.add(cur)
            cur = cur.cause
            guard++
        }
        return out
    }

    /** 异常链上是否出现模块相关包名。 */
    private fun stackContainsModuleFrame(t: Throwable): Boolean = causeChain(t).any { c ->
        c.stackTrace.any { e -> MODULE_FRAME_PREFIXES.any { p -> e.className.startsWith(p) } }
    }

    /**
     * 崩溃分类：
     * - MODULE         栈中含模块代码，属模块问题
     * - SYSTEM_RESTART system_server 已重启（DeadSystemException），崩溃是连带结果
     * - HOST_RESOURCE  宿主资源加载失败（资源 ID 与文件在运行时不匹配）
     * - HOST_ASSERT    宿主内部主动断言失败（数据/状态校验）
     * - HOST_OTHER     宿主自身其它异常
     */
    private fun classifyCrash(throwable: Throwable): CrashClass = try {
        val classNames = causeChain(throwable).map { it.javaClass.name }
        val hasModuleFrame = stackContainsModuleFrame(throwable)
        when {
            hasModuleFrame -> CrashClass(
                code = "MODULE",
                title = "调用栈包含模块代码，属模块相关问题",
                reason = "崩溃调用栈出现 com.Johnny.wcx / h.Hchat / 脚本运行时等模块包名。",
                advice = "请把完整日志反馈给维护者。",
            )

            classNames.any {
                it.endsWith("DeadSystemException") || it.endsWith("DeadSystemRuntimeException")
            } -> CrashClass(
                code = "SYSTEM_RESTART",
                title = "系统核心进程已重启，本崩溃是连带结果，非模块导致",
                reason = "DeadSystemException 表示 system_server 已死亡；崩溃发生时手机通常正在系统重启或异常中，所有应用的窗口通信都会受影响。",
                advice = "无需处理。如需排查重启原因，请查看系统侧记录（tombstone / dropbox），应用侧日志无法定位。",
            )

            classNames.any { it.contains("Resources\$NotFoundException") } -> CrashClass(
                code = "HOST_RESOURCE",
                title = "宿主资源加载失败，非模块导致",
                reason = "栈中无模块代码；宿主 APK 的资源 ID 与文件在运行时对不上，常见于系统深色模式资源重映射、主题包异常或非官方安装包。",
                advice = "可尝试：关闭系统深色模式对微信的适配、重启手机、使用微信自带修复工具（我-设置-帮助与反馈-扳手）。",
            )

            classNames.any {
                it.endsWith("UnsupportedOperationException") ||
                    it.endsWith("IllegalStateException") ||
                    it.endsWith("IllegalArgumentException")
            } -> CrashClass(
                code = "HOST_ASSERT",
                title = "宿主内部断言失败，非模块导致",
                reason = "栈中无模块代码；属于宿主自身的数据或状态校验失败（例如消息数据不完整）。",
                advice = "可尝试微信自带修复工具；若稳定复现，可临时关闭模块作用域对比验证。",
            )

            else -> CrashClass(
                code = "HOST_OTHER",
                title = "宿主自身异常，非模块导致",
                reason = "栈中未出现模块代码。",
                advice = "如怀疑与模块相关，可临时关闭模块作用域对比验证。",
            )
        }
    } catch (e: Throwable) {
        CrashClass(
            code = "UNKNOWN",
            title = "自动分类失败",
            reason = "分类过程异常：" + e.message,
            advice = "",
        )
    }
}
