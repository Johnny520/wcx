package h.Hchat.crash

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs

internal data class SystemExitRecord(
    val timestamp: Long,
    val details: String
)

@TargetApi(Build.VERSION_CODES.R)
internal object CrashExitInfoApi30 {
    private const val SYSTEM_TRACE_LIMIT = 768 * 1024
    private const val EXIT_MATCH_WINDOW_MS = 5 * 60 * 1000L

    fun findNativeExit(
        context: Application,
        previousLaunch: Long,
        lastHandledTimestamp: Long,
        capturedAt: Long,
        capturedPid: Int
    ): SystemExitRecord? {
        return findExit(
            context = context,
            reason = ApplicationExitInfo.REASON_CRASH_NATIVE,
            previousLaunch = previousLaunch,
            lastHandledTimestamp = lastHandledTimestamp,
            capturedAt = capturedAt,
            capturedPid = capturedPid
        )
    }

    fun findAnrExit(
        context: Application,
        previousLaunch: Long,
        lastHandledTimestamp: Long
    ): SystemExitRecord? {
        return findExit(
            context = context,
            reason = ApplicationExitInfo.REASON_ANR,
            previousLaunch = previousLaunch,
            lastHandledTimestamp = lastHandledTimestamp,
            capturedAt = 0L,
            capturedPid = 0
        )
    }

    private fun findExit(
        context: Application,
        reason: Int,
        previousLaunch: Long,
        lastHandledTimestamp: Long,
        capturedAt: Long,
        capturedPid: Int
    ): SystemExitRecord? {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return null
        val candidates = manager.getHistoricalProcessExitReasons(context.packageName, 0, 32)
            .asSequence()
            .filter { it.reason == reason }
            .filter { capturedPid <= 0 || it.pid == capturedPid }
            .filter {
                capturedPid > 0 || it.processName.isNullOrBlank() ||
                    it.processName == context.packageName
            }
            .filter { it.timestamp > lastHandledTimestamp }
            .toList()
        val info = when {
            capturedAt > 0L -> candidates
                .filter { abs(it.timestamp - capturedAt) <= EXIT_MATCH_WINDOW_MS }
                .maxByOrNull { it.timestamp }
                ?: candidates
                    .filter { previousLaunch <= 0L || it.timestamp >= previousLaunch }
                    .maxByOrNull { it.timestamp }
            previousLaunch > 0L -> candidates
                .filter { it.timestamp >= previousLaunch }
                .maxByOrNull { it.timestamp }
            else -> null
        } ?: return null
        return SystemExitRecord(info.timestamp, formatExitInfo(info))
    }

    private fun formatExitInfo(info: ApplicationExitInfo): String = buildString {
        appendLine("进程: ${info.processName.orEmpty()}")
        appendLine("PID: ${info.pid}")
        appendLine("UID: real=${info.realUid}, package=${info.packageUid}")
        appendLine("原因: ${reasonName(info.reason)} (${info.reason})")
        info.description.orEmpty().takeIf { it.isNotBlank() }?.let { appendLine("说明: $it") }
        appendLine("状态: ${info.status}")
        appendLine("重要性: ${info.importance}")
        appendLine("PSS/RSS: ${info.pss} KB / ${info.rss} KB")
        val isAnr = info.reason == ApplicationExitInfo.REASON_ANR
        val traceName = if (isAnr) {
            "系统 ANR Trace"
        } else {
            "系统 Tombstone"
        }
        val trace = readSystemTrace(info, traceName)
        if (trace != null) {
            appendLine()
            appendLine("--- $traceName ---")
            append(trace)
        } else {
            appendLine("$traceName: 当前系统未提供")
        }
    }.trimEnd()

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程退出"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "资源使用过量"
        ApplicationExitInfo.REASON_EXIT_SELF -> "进程自行退出"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存"
        ApplicationExitInfo.REASON_OTHER -> "其他"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化"
        ApplicationExitInfo.REASON_SIGNALED -> "收到信号"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "用户请求退出"
        else -> "未知"
    }

    private fun readSystemTrace(info: ApplicationExitInfo, traceName: String): String? {
        return runCatching {
            info.traceInputStream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (total < SYSTEM_TRACE_LIMIT) {
                    val read = input.read(buffer, 0, minOf(buffer.size, SYSTEM_TRACE_LIMIT - total))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                val bytes = output.toByteArray()
                when {
                    bytes.isEmpty() -> null
                    !isMostlyText(bytes) ->
                        "系统返回了 ${bytes.size} 字节二进制 $traceName，无法直接显示文本。"
                    else -> String(bytes, StandardCharsets.UTF_8).trimEnd() +
                        if (total >= SYSTEM_TRACE_LIMIT) "\n[$traceName 超过保存上限]" else ""
                }
            }
        }.getOrNull()
    }

    private fun isMostlyText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var printable = 0
        val sampled = minOf(bytes.size, 4096)
        for (index in 0 until sampled) {
            val value = bytes[index].toInt() and 0xff
            if (value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 0x80) {
                printable++
            }
        }
        return printable * 100 / sampled >= 85
    }
}
