package com.Johnny.wcx.ai.group

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群总结的提示词与输出解析。
 *
 * 三条约束（与 AI 档案的提示词同一套口径）：
 * 1. **模型看不到的东西不写**：片号、总片数、上一片结果、进度，一律不进提示词；
 * 2. 判断标准只写在 system，输出格式只写在 user 末尾，一件事只写一处；
 * 3. 不写字数之外的格式要求：points 的条数与长度在 [parse] 里截断，不靠模型自觉。
 */
object GroupDigestPrompts {

    fun system(): String = """
        你负责把一个微信群一段时间的聊天记录整理成摘要。

        只写讨论的内容本身：在聊什么、有哪些结论、还有什么没定。
        不要写谁说的，也不要出现成员昵称、wxid 或 @；需要指代时用「有人」「大家」。
        不评价、不推测、不补充记录里没有的背景，也不逐条复述原话。
        寒暄、刷屏、表情包与图片占位不算内容。
    """.trimIndent()

    /** 多片时每片只提炼要点，不和别的片合并（模型不知道有别的片）。 */
    fun sliceSystem(): String = """
        你负责提炼一个微信群一小段聊天记录里的要点。

        只写这一段里讨论的内容：在聊什么、有哪些结论、还有什么没定。
        不要写谁说的，也不要出现成员昵称、wxid 或 @；需要指代时用「有人」「大家」。
        不评价、不推测、不补充记录里没有的背景，也不逐条复述原话。
        寒暄、刷屏、表情包与图片占位不算内容；没有内容就把 points 留空。
    """.trimIndent()

    /** 最后一步：把几份要点合成一份。只合并不新增。 */
    fun mergeSystem(): String = """
        你负责把同一段时间的几份要点合并成一份摘要。

        只合并重复项、去掉寒暄，不新增要点里没有的内容。
        不要写谁说的，也不要出现成员昵称、wxid 或 @。
    """.trimIndent()

    fun user(groupName: String, fromSec: Long, toSec: Long, transcript: String): String = buildString {
        append("群名：").append(groupName.ifBlank { "未命名群" }).append('\n')
        append("时间：").append(range(fromSec, toSec)).append('\n')
        append("<聊天记录>\n").append(transcript).append("\n</聊天记录>\n\n")
        append(OUTPUT_RULE)
    }

    fun sliceUser(groupName: String, fromSec: Long, toSec: Long, transcript: String): String = buildString {
        append("群名：").append(groupName.ifBlank { "未命名群" }).append('\n')
        append("时间：").append(range(fromSec, toSec)).append('\n')
        append("<聊天记录>\n").append(transcript).append("\n</聊天记录>\n\n")
        append("只输出 JSON，不要解释：\n")
        append("""{"headline":"30 字以内","points":["40 字以内"],"todos":["谁要做什么，没有就空数组"]}""")
        append("\npoints 最多 6 条。")
    }

    fun mergeUser(
        groupName: String,
        fromSec: Long,
        toSec: Long,
        parts: List<String>,
    ): String = buildString {
        append("群名：").append(groupName.ifBlank { "未命名群" }).append('\n')
        append("时间：").append(range(fromSec, toSec)).append('\n')
        append("<要点>\n")
        parts.forEach { append(it).append('\n') }
        append("</要点>\n\n")
        append(OUTPUT_RULE)
    }

    private val OUTPUT_RULE = """
        只输出 JSON，不要解释：
        {"significant":true,"headline":"30 字以内","points":["40 字以内"],"todos":["谁要做什么，没有就空数组"]}
        points 最多 6 条；整段没有值得看的内容时 significant 写 false，其余字段留空。
    """.trimIndent()

    // ── 解析 ────────────────────────────────────────────────────────────────

    data class Parsed(
        val significant: Boolean,
        val headline: String,
        val points: List<String>,
        val todos: List<String>,
    )

    /**
     * 解析模型输出。返回 null 表示完全不是 JSON —— 调用方用原文兜底，不整条丢。
     *
     * 先剥 ``` 围栏：部分服务商会把 JSON 包进 markdown，直接 parse 会失败。
     */
    fun parse(raw: String): Parsed? {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        return Parsed(
            significant = obj.bool("significant") ?: true,
            headline = obj.string("headline").take(HEADLINE_MAX),
            points = obj.strings("points").take(MAX_POINTS).map { it.take(POINT_MAX) },
            todos = obj.strings("todos").take(MAX_POINTS).map { it.take(POINT_MAX) },
        )
    }

    /** 解析失败时用第一句当 headline，保证卡片上不是空的。 */
    fun fallbackHeadline(raw: String): String {
        val firstLine = raw.lineSequence()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .removePrefix("{")
            .removePrefix("\"")
        return firstLine.take(HEADLINE_MAX).ifBlank { "（无摘要）" }
    }

    private fun range(fromSec: Long, toSec: Long): String {
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return format.format(Date(fromSec * 1000L)) + " ~ " + format.format(Date(toSec * 1000L))
    }

    private fun JsonObject.bool(key: String): Boolean? =
        runCatching { get(key)?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private fun JsonObject.string(key: String): String =
        runCatching { get(key)?.jsonPrimitive?.content.orEmpty().trim() }.getOrDefault("")

    private fun JsonObject.strings(key: String): List<String> =
        runCatching {
            get(key)?.jsonArray?.mapNotNull { it.jsonPrimitive.content.trim().ifBlank { null } }
                ?: emptyList()
        }.getOrDefault(emptyList())

    private val json = Json { ignoreUnknownKeys = true }
}
