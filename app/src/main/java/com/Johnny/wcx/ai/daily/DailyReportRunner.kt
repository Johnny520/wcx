package com.Johnny.wcx.ai.daily

import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.items.chat.TranslateSupport
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 每日总结的生成器：把「今天各会话发生了什么」压成一份可读的报告。
 *
 * 数据来源全部是本地库，不引入新的服务：
 * - 好友：建过档案的人，各自今天最近几条消息
 * - 群：今天有消息的群，各自最近几条消息
 * - 工作群：直接引用已经算好的进度与目标（不再单独请求模型）
 *
 * 只发一次模型请求，输出 JSON（正文 + 一句话摘要 + 未完成事项），再格式化成 Markdown。
 */
object DailyReportRunner {

    private const val TAG = "DailyReportRunner"

    private const val MAX_FRIENDS = 8
    private const val MAX_GROUPS = 6
    private const val LINES_PER_CONVERSATION = 8
    private const val MAX_CHARS = 12000

    private const val SYSTEM_PROMPT =
        "你是个人助理，负责写「今日总结」：只依据给出的聊天记录归纳，不编造、不夸大。"

    private val json = Json { ignoreUnknownKeys = true }

    data class Outcome(
        val body: String,
        val headline: String,
        val unfinished: List<String>,
        val friendCount: Int,
        val groupCount: Int,
        val workGroupCount: Int,
    )

    suspend fun generate(dateKey: String = DailyReportStore.todayKey()): Result<DailyReport> =
        withContext(Dispatchers.IO) {
            if (!AiConfig.isConfigured) {
                return@withContext Result.failure(IllegalStateException("请先到「AI 助手 → 设置 → AI 接口」填写配置"))
            }
            val startOfDay = startOfDayMillis()
            val friendIds = runCatching {
                AiProfileStore.loadIndex().profiles.map { it.wxId }
            }.getOrDefault(emptyList())

            val builder = StringBuilder()
            var friendCount = 0
            friendIds.take(MAX_FRIENDS).forEach { wxId ->
                val lines = todayLines(wxId, startOfDay)
                if (lines.isEmpty()) return@forEach
                val name = runCatching { AiProfileStore.readProfile(wxId)?.let { it.remark.ifBlank { it.nickname } } }
                    .getOrNull().orEmpty().ifBlank { wxId }
                builder.append("【好友：$name】\n")
                lines.forEach { builder.append(it).append('\n') }
                builder.append('\n')
                friendCount++
            }

            var groupCount = 0
            var workGroupCount = 0
            val workLines = StringBuilder()
            runCatching { GroupDigestStore.listGroupIds() }.getOrDefault(emptyList())
                .take(MAX_GROUPS)
                .forEach { groupId ->
                    val config = runCatching { GroupDigestStore.readConfig(groupId) }.getOrNull() ?: return@forEach
                    val lines = todayLines(groupId, startOfDay)
                    if (config.workGroup) {
                        workGroupCount++
                        workLines.append("【工作群：${config.name.ifBlank { groupId }}】")
                        if (config.progressPercent >= 0) {
                            workLines.append(" 当前进度 ${config.progressPercent}%")
                        }
                        if (config.progressGoals.isNotEmpty()) {
                            workLines.append("；目标：").append(config.progressGoals.joinToString("、"))
                        }
                        workLines.append('\n')
                    }
                    if (lines.isEmpty()) return@forEach
                    builder.append("【群：${config.name.ifBlank { groupId }}】\n")
                    lines.forEach { builder.append(it).append('\n') }
                    builder.append('\n')
                    groupCount++
                }

            if (builder.isBlank() && workLines.isBlank()) {
                return@withContext Result.failure(IllegalStateException("今天没有可总结的聊天"))
            }

            val user = buildString {
                append("今天是 ").append(dateKey).append("。\n")
                if (workLines.isNotBlank()) {
                    append("\n已知的工作群进度（直接采用，不要改数字）：\n")
                    append(workLines)
                }
                append("\n下面是今天各会话的消息（好友在前，群在后）：\n")
                append(builder.toString().take(MAX_CHARS))
                append("\n\n请只输出一个 JSON 对象，不要代码块标记，格式：\n")
                append("{\"headline\":\"一句话总结今天\",")
                append("\"body\":\"Markdown 正文，分三段：今天发生了什么 / 好友往来要点 / 群聊与工作进展\",")
                append("\"unfinished\":[\"还没做完的事，最多 5 条，没有就给空数组\"]}\n")
                append("要求：正文用简体中文，条目化，不要复述原文，不要编造记录里没有的事。")
            }

            val raw = TranslateSupport.chatCompletion(SYSTEM_PROMPT, user).getOrElse { error ->
                WeLogger.e(TAG, "生成每日总结失败", error)
                return@withContext Result.failure(error)
            }
            val outcome = parse(raw, friendCount, groupCount, workGroupCount)
            val report = DailyReport(
                dateKey = dateKey,
                createdAt = System.currentTimeMillis(),
                body = outcome.body,
                headline = outcome.headline,
                unfinished = outcome.unfinished,
                friendCount = outcome.friendCount,
                groupCount = outcome.groupCount,
                workGroupCount = outcome.workGroupCount,
            )
            if (!DailyReportStore.save(report)) {
                return@withContext Result.failure(IllegalStateException("报告保存失败，请检查存储权限"))
            }
            Result.success(report)
        }

    private fun parse(raw: String, friendCount: Int, groupCount: Int, workGroupCount: Int): Outcome {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val body = raw.substring(start, end + 1)
            val parsed = runCatching {
                val obj: JsonObject = json.parseToJsonElement(body).jsonObject
                val headline = obj["headline"]?.jsonPrimitive?.content.orEmpty()
                val text = obj["body"]?.jsonPrimitive?.content.orEmpty()
                val unfinished = obj["unfinished"]?.jsonArray?.mapNotNull {
                    runCatching { it.jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() }
                }.orEmpty()
                Outcome(text, headline, unfinished, friendCount, groupCount, workGroupCount)
            }.getOrNull()
            if (parsed != null && parsed.body.isNotBlank()) return parsed
        }
        // 模型没按 JSON 输出时，把原文当正文，至少不丢内容。
        return Outcome(
            body = raw.trim(),
            headline = raw.lineSequence().firstOrNull { it.isNotBlank() }?.take(40).orEmpty(),
            unfinished = emptyList(),
            friendCount = friendCount,
            groupCount = groupCount,
            workGroupCount = workGroupCount,
        )
    }

    /** 今天的消息，按「我 / 对方」渲染；群聊剥掉 `wxid:` 前缀。 */
    private fun todayLines(talker: String, since: Long): List<String> {
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT isSend, type, content FROM message WHERE talker = ? AND createTime >= ? " +
                        "ORDER BY createTime DESC LIMIT $LINES_PER_CONVERSATION",
                arrayOf<Any>(talker, since),
            )
        }.getOrElse {
            WeLogger.w(TAG, "读取会话消息失败：$talker", it)
            return emptyList()
        }
        return rows.asReversed().mapNotNull { row ->
            val type = (row["type"] as? Number)?.toInt() ?: 0
            val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
            val content = row["content"] as? String ?: ""
            val label = MessageType.fromCode(type)
            val text = if (label?.isText == true) {
                val stripped = content.substringAfter(":\n", content).trim()
                if (stripped.startsWith("<")) "[${label.displayName}]" else stripped
            } else {
                "[${label?.displayName ?: "非文本"}]"
            }
            if (text.isBlank()) null else "${if (isSend != 0) "我" else "对方"}：${text.take(120)}"
        }
    }

    private fun startOfDayMillis(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
