package com.Johnny.wcx.ai.group

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.items.chat.TranslateSupport
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作群进度分析：从群里最近的消息里梳理「在做的事」和「做到哪一步」。
 *
 * 只在群被标成「工作群」时才会调用，且由用户手动点「更新进度」触发 ——
 * 不挂在群总结的定时任务上，避免每次总结都多花一次模型额度。
 *
 * 结果落到 [GroupDigestConfig]（单群文件是权威），再同步一份到索引，
 * 列表页才能在群名后面显示百分比。
 */
object WorkGroupAnalyzer {

    private const val TAG = "WorkGroupAnalyzer"

    /** 往回看 7 天：更早的任务多半已经结束，会把进度稀释掉。 */
    private const val WINDOW_DAYS = 7L

    /** 送进模型的正文上限（字符）。 */
    private const val MAX_CHARS = 8000

    data class Progress(
        val percent: Int,
        val goals: List<String>,
        val note: String,
    )

    private const val SYSTEM_PROMPT =
        "你是项目进度助手：只根据群聊记录判断任务进展，不编造、不夸大，输出只能是 JSON。"

    private fun userPrompt(groupName: String, transcript: String): String = buildString {
        append("群名：").append(groupName.ifBlank { "工作群" }).append('\n')
        append("下面是这个群最近 7 天的聊天记录。\n\n")
        append("请梳理：群里正在推进的目标、每个目标做到哪一步、有没有卡住的地方。\n")
        append("只输出一个 JSON 对象，不要加解释或代码块标记，格式：\n")
        append("{\"percent\": 0到100的整数, \"goals\": [\"目标1（可带进度）\", \"目标2\"], \"note\": \"一句话说明进度依据\"}\n")
        append("要求：goals 最多 5 条，每条不超过 30 字；看不出任务就 percent 填 0、goals 为空、note 写「看不出明确任务」。\n\n")
        append("===== 群聊记录 =====\n")
        append(transcript)
    }

    /** 分析并落盘；返回最新进度。 */
    suspend fun analyze(groupId: String, groupName: String): Result<Progress> = withContext(Dispatchers.IO) {
        if (!AiConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("请先到「AI 助手 → 设置 → AI 接口」填写配置"))
        }
        val nowSec = System.currentTimeMillis() / 1000
        val fromSec = nowSec - WINDOW_DAYS * 86_400L
        val transcript = runCatching {
            val loaded = GroupTranscript.load(groupId, fromSec, nowSec)
            GroupTranscript.transcript(loaded.lines)
        }.getOrElse { error ->
            WeLogger.e(TAG, "读取群消息失败", error)
            return@withContext Result.failure(IllegalStateException("读取群消息失败：${error.message ?: "查询异常"}"))
        }
        if (transcript.isBlank()) {
            return@withContext Result.failure(IllegalStateException("最近 7 天这个群没有可读的文字消息"))
        }
        val result = TranslateSupport.chatCompletion(
            SYSTEM_PROMPT,
            userPrompt(groupName, transcript.take(MAX_CHARS)),
        )
        result.mapCatching { raw ->
            val parsed = parse(raw) ?: error("模型返回的内容无法解析，请换个模型再试")
            apply(groupId, parsed)
            parsed
        }
    }

    /** 把结果写回单群配置并同步索引。 */
    fun apply(groupId: String, progress: Progress) {
        val config = GroupDigestStore.update(groupId) { current ->
            current.copy(
                progressPercent = progress.percent.coerceIn(0, 100),
                progressGoals = progress.goals.take(5),
                progressNote = progress.note.take(120),
                progressUpdatedAt = System.currentTimeMillis(),
            )
        } ?: run {
            WeLogger.w(TAG, "群配置不存在，无法写入进度：$groupId")
            return
        }
        GroupDigestStore.upsertIndexEntry(config.toSummary())
        WeLogger.i(TAG, "工作群进度更新：${config.name} -> ${progress.percent}%")
    }

    /** 只改「是不是工作群」，关掉时不动已有进度（重新打开还能看到上次结果）。 */
    fun setWorkGroup(groupId: String, enabled: Boolean) {
        val config = GroupDigestStore.update(groupId) { it.copy(workGroup = enabled) } ?: return
        GroupDigestStore.upsertIndexEntry(config.toSummary())
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 容错解析：模型偶尔会包一层代码块或带前言，取出第一段 `{...}` 再解析。 */
    private fun parse(raw: String): Progress? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val body = raw.substring(start, end + 1)
        return runCatching {
            val obj: JsonObject = json.parseToJsonElement(body).jsonObject
            val percent = obj["percent"]?.jsonPrimitive?.intOrNull ?: 0
            val goals = obj["goals"]?.jsonArray?.mapNotNull {
                runCatching { it.jsonPrimitive.content }.getOrNull()?.takeIf { g -> g.isNotBlank() }
            }.orEmpty()
            val note = runCatching { obj["note"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            Progress(percent = percent.coerceIn(0, 100), goals = goals, note = note)
        }.getOrNull()
    }
}
