package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.MessageRendering
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.WeMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 群消息读取层：取数、剥发送者前缀、渲染占位、切片。
 *
 * 与单聊（`AiChatHistory`）的差别只有三处：
 * 1. 正文是 `发送者wxid:\n内容`，要先拆成 `(sender, text)`；
 * 2. 显示名要按「备注 → 群名片 → 昵称」解析，整群只解析一次；
 * 3. 同一条消息要带上「谁说的」，否则摘要分不清主次。
 *
 * **所有方法都是阻塞的**（查库），调用方必须在 IO 线程 / 协程里。
 */
object GroupTranscript {

    data class Line(
        val atSec: Long,
        val senderId: String,
        val senderName: String,
        val text: String,
        val isText: Boolean,
    )

    data class Transcript(
        val lines: List<Line>,
        /** 被 [MAX_MESSAGES_PER_RUN] 截断：摘要里要标注只覆盖最近一段。 */
        val sampled: Boolean,
    )

    /** 窗口内**文字**条数，用于触发阈值；媒体不算。 */
    fun textCount(groupId: String, fromSec: Long, toSec: Long): Int =
        WeDatabaseApi.countMessagesBetween(groupId, fromSec, toSec, MessageRendering.TEXT_ONLY)

    /**
     * 取窗口内的消息，时间正序返回。
     *
     * **必须 `newestFirst = true`**：SQL 是 `ORDER BY createTime ASC LIMIT ?`，默认顺序下
     * 大群先被截掉的是最新消息，摘要会写成几小时前的开头。
     */
    fun load(groupId: String, fromSec: Long, toSec: Long): Transcript {
        if (groupId.isBlank() || toSec <= fromSec) return Transcript(emptyList(), sampled = false)
        val raw = runCatching {
            WeDatabaseApi.getMessagesBetween(
                convId = groupId,
                fromSec = fromSec,
                toSec = toSec,
                limit = MAX_MESSAGES_PER_RUN,
                typeCodes = MessageRendering.GROUP_CHAT_TYPES,
                newestFirst = true,
            )
        }.getOrElse {
            // 查询失败不能当成「群里没人说话」：那会让调用方把窗口推进过去，这段永久丢失。
            return Transcript(emptyList(), sampled = false)
        }
        if (raw.isEmpty()) return Transcript(emptyList(), sampled = false)
        val names = memberNames(groupId)
        return Transcript(
            lines = raw.asReversed().mapNotNull { render(it, names) },
            sampled = raw.size >= MAX_MESSAGES_PER_RUN,
        )
    }

    /**
     * 按「20k 字符或 800 行」切片；超过 8 片只保留**最近** 8 片。
     *
     * 不按时间切：同一天的消息被切开会让模型把一件事写成两半。
     */
    fun chunk(lines: List<Line>): List<List<Line>> {
        if (lines.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<Line>>()
        var current = ArrayList<Line>()
        var chars = 0
        for (line in lines) {
            val cost = line.text.length + line.senderName.length + 8
            if (current.isNotEmpty() && (current.size >= MAX_CHUNK_LINES || chars + cost > MAX_CHUNK_CHARS)) {
                chunks += current
                current = ArrayList()
                chars = 0
            }
            current += line
            chars += cost
        }
        if (current.isNotEmpty()) chunks += current
        return if (chunks.size <= MAX_CHUNKS) chunks else chunks.takeLast(MAX_CHUNKS)
    }

    /** 一片的正文：`[09:12] 张三: 早`。窗口最长 7 天，日期只到月日。 */
    fun transcript(lines: List<Line>, withDate: Boolean = true): String {
        if (lines.isEmpty()) return ""
        val format = SimpleDateFormat(if (withDate) "MM-dd HH:mm" else "HH:mm", Locale.US)
        return lines.joinToString("\n") { line ->
            "[" + format.format(Date(line.atSec * 1000L)) + "] " + line.senderName + ": " + line.text
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** wxid → 显示名：备注 → 群名片 → 昵称。整群只查两次库。 */
    private fun memberNames(groupId: String): Map<String, String> {
        val contacts = runCatching { WeDatabaseApi.getGroupMembers(groupId) }.getOrDefault(emptyList())
        if (contacts.isEmpty()) return emptyMap()
        val cards = runCatching { WeDatabaseApi.getGroupMemberDisplayNames(groupId) }
            .getOrDefault(emptyMap())
        return contacts.associate { contact ->
            val name = contact.remarkName
                .ifBlank { cards[contact.wxId].orEmpty() }
                .ifBlank { contact.nickname }
                .ifBlank { "群成员" }
            contact.wxId to name
        }
    }

    private fun render(message: WeMessage, names: Map<String, String>): Line? {
        val type = message.typeCode
        // 系统消息（入群提示、撤回、拍一拍）不在 GROUP_CHAT_TYPES 里，兜底再挡一次。
        if (type == 9999 || type == 10000) return null
        val (senderId, body) = splitSender(message.content)
        val text = MessageRendering.render(type, body) ?: return null
        val fromMe = message.isSend == 1
        return Line(
            atSec = MessageRendering.toSeconds(message.createTime),
            senderId = if (fromMe) "me" else senderId,
            senderName = if (fromMe) "我" else names[senderId] ?: "群成员",
            text = text,
            isText = type == MessageRendering.TYPE_TEXT,
        )
    }

    /**
     * 拆 `发送者:\n内容`。
     *
     * 只认「首行 1~64 个 ASCII 账号字符 + 换行」：正文里的 `注意：\n…`（中文冒号）、
     * `note: something`（有空格）都不能动。自己发的消息也走一遍，带前缀就剥、不带就原样。
     */
    fun splitSender(raw: String): Pair<String, String> {
        val match = SENDER_PREFIX.find(raw) ?: return "" to raw
        return match.groupValues[1] to match.groupValues[2]
    }

    private val SENDER_PREFIX = Regex("""^([A-Za-z0-9_@\-]{1,64}):\n([\s\S]*)$""")
}
