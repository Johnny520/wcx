package com.Johnny.wcx.features.items.contacts

/**
 * 群系统消息解析器（`message.type == 10000`）。
 *
 * ## 为什么是「文案匹配」而不是 dex 锚点
 * 群系统消息的正文由**微信服务器下发**（例如 `你邀请"张三"加入了群聊`），
 * 8.0.80 的 dex 字符串池里**不存在** `加入了群聊` / `退出了群聊` 这类常量
 * （已用 `apk_strings80.txt` 全量 grep 复核，零命中），因此无法用 DexKit 锚点定位，
 * 只能按服务端文案做多模式匹配——这也是同类模块（微x、WeKit 等）的通行做法。
 *
 * 为了尽量稳，这里：
 * 1. 关键词多套并列（含历史文案与英文文案），任一命中即算；
 * 2. 昵称只从成对引号里取（服务端对昵称统一加引号），取不到就**放弃**该事件，
 *    宁可漏发也不发错人；
 * 3. 先判「移出」再判「退出」再判「加入」，三条文案互不为子串，顺序仅为确定性。
 *
 * ## 已覆盖的文案（8.0.80 实测样例）
 * - 加入：`"张三"加入了群聊` / `你邀请"张三"加入了群聊` / `"张三"邀请"李四"加入了群聊`
 *   / `"张三"通过扫描"李四"分享的二维码加入群聊` / `"张三"通过群聊邀请确认加入群聊`
 * - 退出：`"张三"退出了群聊`
 * - 移出：`你已将"张三"移出了群聊` / `"张三"将"李四"移出了群聊`
 *
 * ## 已知限制
 * - 一次邀请多人（`你邀请"张三"和"李四"加入了群聊`）在「加入」场景会尽量取全部昵称，
 *   但「移出/退出」场景只取首个；
 * - 服务端改文案（极罕见）会导致匹配失败，此时功能静默跳过，不会误发。
 */
internal enum class GroupSystemEventKind {
    /** 有新成员入群 */
    JOIN,

    /** 成员主动退群 */
    LEAVE,

    /** 成员被移出（踢人） */
    KICK,
}

internal data class GroupSystemEvent(
    /** 变动类型 */
    val kind: GroupSystemEventKind,
    /** 变动成员昵称；判定失败为空列表（调用方应跳过） */
    val targets: List<String>,
    /** 操作者昵称（`你` / 另一位成员），无法判定时为 null */
    val operator: String?,
    /** 原始正文，便于日志与模板兜底 */
    val raw: String,
) {
    /** 主昵称，多人时用「、」连接 */
    val displayNames: String
        get() = targets.joinToString("、")

    /** 动作中文描述，供通知模板使用 */
    val actionText: String
        get() = when (kind) {
            GroupSystemEventKind.JOIN -> "加入"
            GroupSystemEventKind.LEAVE -> "退出"
            GroupSystemEventKind.KICK -> "被移出"
        }
}

internal object GroupSystemMsgParser {

    /** 微信「系统消息」类型码；与 `MessageType.SYSTEM.code` 一致（这里写字面量避免弃用告警） */
    const val SYSTEM_MSG_TYPE = 10000

    /** 群文本消息类型码；与 `MessageType.TEXT.code` 一致 */
    const val GROUP_TEXT_MSG_TYPE = 1

    /** 防御：异常超长正文直接忽略 */
    private const val MAX_CONTENT_LENGTH = 512

    /** 昵称统一被服务端包在成对引号里（全角、直角、半角都出现过） */
    private val QUOTED_NAME = Regex("[“\"「『]([^”\"」』]{1,64})[”\"」』]")

    private val JOIN_KEYWORDS = listOf(
        "加入了群聊",
        "加入群聊",
        "加入了该群聊",
        "通过扫描",
        "通过群聊邀请",
        "invited",
        "joined the group",
    )

    private val KICK_KEYWORDS = listOf(
        "移出了群聊",
        "移出群聊",
        "被移出群",
        "removed from the group",
        "kicked",
    )

    private val LEAVE_KEYWORDS = listOf(
        "退出了群聊",
        "退出群聊",
        "left the group",
    )

    fun isSystemMessage(typeCode: Int): Boolean = typeCode == SYSTEM_MSG_TYPE

    /**
     * 解析群系统消息正文；不是成员变动类消息时返回 null。
     */
    fun parse(content: String?): GroupSystemEvent? {
        val raw = content?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_CONTENT_LENGTH) return null

        val kind = when {
            KICK_KEYWORDS.any { raw.contains(it, ignoreCase = true) } -> GroupSystemEventKind.KICK
            LEAVE_KEYWORDS.any { raw.contains(it, ignoreCase = true) } -> GroupSystemEventKind.LEAVE
            JOIN_KEYWORDS.any { raw.contains(it, ignoreCase = true) } -> GroupSystemEventKind.JOIN
            else -> return null
        }

        val names = QUOTED_NAME.findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        if (names.isEmpty()) return null

        val targets = pickTargets(kind, raw, names)
        if (targets.isEmpty()) return null

        val operator = if (names.size >= 2) names.firstOrNull { it !in targets } else null
        return GroupSystemEvent(kind, targets, operator, raw)
    }

    /**
     * 从成对引号里的昵称列表中挑出「变动对象」。
     *
     * - `你邀请"A"加入了群聊`：操作者是「你」，对象是第一个昵称；
     * - `"A"邀请"B"加入了群聊`：对象是**最后一个**昵称；
     * - `"A"通过扫描"B"分享的二维码加入群聊`：对象是**第一个**昵称（A 自己扫的码）；
     * - `你已将"A"移出了群聊`：对象是第一个；`"A"将"B"移出了群聊`：对象是最后一个。
     */
    private fun pickTargets(
        kind: GroupSystemEventKind,
        raw: String,
        names: List<String>,
    ): List<String> {
        val selfOperated = raw.startsWith("你")
        return when {
            names.size == 1 -> names
            selfOperated -> if (kind == GroupSystemEventKind.JOIN) names else names.take(1)
            raw.contains("通过扫描") || raw.contains("通过") -> names.take(1)
            else -> listOf(names.last())
        }
    }
}