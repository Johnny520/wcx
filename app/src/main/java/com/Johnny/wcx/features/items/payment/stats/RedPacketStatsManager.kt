package com.Johnny.wcx.features.items.payment.stats

import android.content.Context
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * 抢红包金额统计的本地数据管理。
 *
 * 线程约束：所有数据库读写/文件导出都在 IO 协程执行，严禁主线程 IO。
 * 容错：全部逻辑 try-catch；任一环节异常仅记日志，不影响抢红包核心流程与微信稳定性。
 */
object RedPacketStatsManager {

    private const val TAG = "RedPacketStatsManager"

    /** 功能开关配置键：与 @Feature(name=...) 保持一致（工程以 @Feature name 为 SP 键）。 */
    private const val ENABLED_KEY = "抢红包金额统计"

    /** 最近记录列表展示条数上限（避免一次性加载过多数据）。 */
    private const val RECENT_LIMIT = 200

    /** 进程级 IO 作用域：写库/导出都是短任务，随进程存活，不持有 Activity 引用。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 开关关闭时调用方不应进入本类任何读写路径。 */
    fun isEnabled(): Boolean =
        runCatching { WePrefs.getBoolOrFalse(ENABLED_KEY) }.getOrDefault(false)

    /**
     * 抢到红包成功后写入一条记录（非阻塞，内部切 IO 线程）。
     * [money] 为元；金额 <= 0 或异常直接跳过，不入库。
     */
    fun addSuccessRecord(
        senderName: String?,
        chatName: String?,
        money: Double,
        msgType: String,
        timestamp: Long,
    ) {
        if (money <= 0) return

        val db = runCatching { RedPacketStatsDatabase.get(HostInfo.application) }
            .onFailure { WeLogger.e(TAG, "failed to open stats database", it) }
            .getOrNull() ?: return

        ioScope.launch {
            runCatching {
                db.dao().insert(
                    RedPacketRecordEntity(
                        timestamp = timestamp,
                        senderName = senderName.orEmpty(),
                        chatName = chatName.orEmpty(),
                        money = money,
                        msgType = msgType,
                    )
                )
            }.onFailure { WeLogger.e(TAG, "failed to insert red packet record", it) }
        }
    }

    /** 统计汇总：累计/今日/本周/平均金额。 */
    data class Summary(
        val totalCount: Long = 0L,
        val totalAmount: Double = 0.0,
        val todayCount: Long = 0L,
        val todayAmount: Double = 0.0,
        val weekAmount: Double = 0.0,
        val avgAmount: Double = 0.0,
    )

    suspend fun loadSummary(): Summary = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val db = RedPacketStatsDatabase.get(HostInfo.application)
            val total = db.dao().totalStats()
            val today = db.dao().sinceStats(dayStartMillis(now))
            val week = db.dao().sinceStats(weekStartMillis(now))
            Summary(
                totalCount = total.count,
                totalAmount = total.total,
                todayCount = today.count,
                todayAmount = today.total,
                weekAmount = week.total,
                avgAmount = if (total.count > 0) total.total / total.count else 0.0,
            )
        }.onFailure { WeLogger.e(TAG, "failed to load stats summary", it) }
            .getOrDefault(Summary())
    }

    suspend fun loadRecentRecords(): List<RedPacketRecordEntity> = withContext(Dispatchers.IO) {
        runCatching {
            RedPacketStatsDatabase.get(HostInfo.application).dao().recentRecords(RECENT_LIMIT)
        }.onFailure { WeLogger.e(TAG, "failed to load recent records", it) }
            .getOrDefault(emptyList())
    }

    suspend fun clearAll(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            RedPacketStatsDatabase.get(HostInfo.application).dao().clearAll()
            true
        }.onFailure { WeLogger.e(TAG, "failed to clear red packet records", it) }
            .getOrDefault(false)
    }

    data class ExportResult(val success: Boolean, val message: String)

    /**
     * 导出全部记录为文本，写入模块数据目录（应用私有路径，无需存储权限）。
     * 捕获 IOException / SecurityException，不崩溃。
     */
    suspend fun exportText(): ExportResult = withContext(Dispatchers.IO) {
        try {
            val db = RedPacketStatsDatabase.get(HostInfo.application)
            val records = db.dao().allRecords()
            val total = db.dao().totalStats()
            val now = System.currentTimeMillis()

            val text = buildString {
                appendLine("抢红包金额统计导出")
                appendLine("导出时间：${formatTime(now)}")
                appendLine("累计：${total.count} 笔，总额 ¥${formatMoney(total.total)}")
                appendLine("平均：¥${formatMoney(if (total.count > 0) total.total / total.count else 0.0)}")
                appendLine("----------------------------------------")
                if (records.isEmpty()) {
                    appendLine("（暂无记录）")
                } else {
                    records.forEach { record ->
                        appendLine(
                            "${formatTime(record.timestamp)} | " +
                                "${record.senderName} | ${record.chatName} | " +
                                "¥${formatMoney(record.money)} | ${record.msgType}"
                        )
                    }
                }
            }

            val targetDir = runCatching { KnownPaths.moduleData }
                .onFailure { WeLogger.e(TAG, "failed to resolve export directory", it) }
                .getOrNull() ?: return@withContext ExportResult(false, "导出目录不可用")

            runCatching { targetDir.createDirectories() }
                .onFailure { WeLogger.e(TAG, "failed to create export directory", it) }
                .getOrNull() ?: return@withContext ExportResult(false, "创建导出目录失败")

            val fileName = "red_packet_stats_export_${formatFileStamp(now)}.txt"
            val targetFile = targetDir / fileName
            targetFile.writeText(text, Charsets.UTF_8)
            ExportResult(true, targetFile.toString())
        } catch (e: IOException) {
            WeLogger.e(TAG, "failed to export red packet stats (IOException)", e)
            ExportResult(false, "导出失败：${e.message.orEmpty()}")
        } catch (e: SecurityException) {
            WeLogger.e(TAG, "failed to export red packet stats (SecurityException)", e)
            ExportResult(false, "导出失败：存储权限不足")
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to export red packet stats", e)
            ExportResult(false, "导出失败：${e.message.orEmpty()}")
        }
    }

    /** 今日 00:00:00.000 的时间戳。 */
    private fun dayStartMillis(now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /** 本周一 00:00:00.000 的时间戳（周一为一周起始，符合国内习惯）。 */
    private fun weekStartMillis(now: Long): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // DAY_OF_WEEK: SUNDAY=1 ... SATURDAY=7；换算为距本周一的天数
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) + 5) % 7
            add(Calendar.DAY_OF_MONTH, -daysSinceMonday)
        }
        return calendar.timeInMillis
    }

    private fun formatMoney(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun formatTime(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }

    private fun formatFileStamp(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.US,
            "%04d%02d%02d_%02d%02d%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND),
        )
    }
}
