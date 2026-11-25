package com.Johnny.wcx.ai.daily

import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * 每日总结：每天一份，回答「今天发生了什么、还有什么没做完」。
 *
 * 存储与档案同一套目录（`ai/daily/YYYY-MM-DD.json`），一天一个文件：
 * 重跑同一天直接覆盖，不保留多版本 —— 报告是给人看的，不是日志。
 */
@Serializable
data class DailyReport(
    /** `yyyy-MM-dd`。 */
    val dateKey: String,
    val createdAt: Long = 0,
    /** 报告正文（Markdown 纯文本）。 */
    val body: String = "",
    /** 一句话摘要，列表页显示。 */
    val headline: String = "",
    /** 还没做完的事，首页/列表可以单独提示。 */
    val unfinished: List<String> = emptyList(),
    /** 覆盖到的好友数 / 群数，列表副标题用。 */
    val friendCount: Int = 0,
    val groupCount: Int = 0,
    val workGroupCount: Int = 0,
)

object DailyReportStore {

    private const val TAG = "DailyReportStore"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val rootDir: Path by lazy {
        (AiProfileStore.root / "daily").also { runCatching { it.createDirectories() } }
    }

    val root: Path get() = rootDir

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun todayKey(): String = LocalDate.now().format(dateFormatter)

    private fun fileOf(dateKey: String): Path = root.resolve("$dateKey.json")

    fun read(dateKey: String): DailyReport? {
        val file = fileOf(dateKey)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<DailyReport>(file.readText()) }
            .getOrElse {
                WeLogger.w(TAG, "报告解析失败：$dateKey", it)
                null
            }
    }

    fun save(report: DailyReport): Boolean = runCatching {
        fileOf(report.dateKey).writeText(json.encodeToString(report))
        true
    }.getOrElse {
        WeLogger.e(TAG, "保存报告失败：${report.dateKey}", it)
        false
    }

    /** 最近 [limit] 天的报告，最新在前；没有文件的日期直接跳过。 */
    fun recent(limit: Int = 14): List<DailyReport> {
        val list = mutableListOf<DailyReport>()
        var day = LocalDate.now()
        var scanned = 0
        while (list.size < limit && scanned < 60) {
            val key = day.format(dateFormatter)
            read(key)?.let { list += it }
            day = day.minusDays(1)
            scanned++
        }
        return list
    }

    fun delete(dateKey: String): Boolean = runCatching {
        fileOf(dateKey).deleteIfExists()
    }.isSuccess

    /** 目录里有哪些日期（诊断用）。 */
    fun existingKeys(): List<String> = runCatching {
        Files.list(root).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".json") }
                .map { it.removeSuffix(".json") }
                .sorted()
                .toList()
        }
    }.getOrElse { emptyList() }
}
