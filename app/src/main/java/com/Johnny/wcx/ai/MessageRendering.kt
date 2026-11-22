package com.Johnny.wcx.ai

/**
 * 消息正文的统一渲染：文字清洗 + 媒体占位。
 *
 * 好友档案（[AiChatHistory]）与群总结（`GroupTranscript`）共用同一套规则 ——
 * `[图片]` / `[语音 3″]` / `[视频]` / `[表情包]` / `[卡片]` 只能有一份实现，
 * 否则两边对同一条消息的渲染结果会不一致，排查时无法互相对照。
 */
object MessageRendering {

    const val TYPE_TEXT = 1
    const val TYPE_IMAGE = 3
    const val TYPE_VOICE = 34
    const val TYPE_VIDEO = 43
    const val TYPE_STICKER = 47
    const val TYPE_CARD = 49
    const val TYPE_MICRO_VIDEO = 62

    /** 单条正文上限：粘贴的公告、转发的长文能把一次请求的预算吃光。 */
    const val MAX_LINE_CHARS = 200

    /** 文字。分片、关键词搜索、条数统计都只用它。 */
    val TEXT_ONLY = setOf(TYPE_TEXT)

    /** 好友档案放行的类型：文字 + 图片 + 语音 + 视频 + 表情包（不含卡片）。 */
    val CHAT_TYPES = setOf(
        TYPE_TEXT, TYPE_IMAGE, TYPE_VOICE, TYPE_VIDEO, TYPE_STICKER, TYPE_MICRO_VIDEO,
    )

    /** 群总结放行的类型：在好友档案基础上加卡片（只占位，不解析标题、不抓链接）。 */
    val GROUP_CHAT_TYPES = CHAT_TYPES + TYPE_CARD

    /**
     * 一条消息 → 给模型看的那一行。返回 null 表示这条应当丢弃：
     * XML 残留、未知类型、清洗后为空。
     */
    fun render(type: Int, content: String): String? {
        val text = if (type == TYPE_TEXT) sanitize(content) else placeholder(type, content)
        return text.takeIf { it.isNotEmpty() }
    }

    /** 压掉换行与连续空白；超长截断；以 `<` 开头的 XML 残留清空。 */
    fun sanitize(raw: String): String {
        var text = raw.replace('\n', ' ').replace('\r', ' ').trim()
        while (text.contains("  ")) text = text.replace("  ", " ")
        // 撤回、系统提示这类以 < 开头的 XML 残留对模型无意义。
        if (text.startsWith("<")) return ""
        if (text.length > MAX_LINE_CHARS) text = text.take(MAX_LINE_CHARS) + "…"
        return text
    }

    /**
     * 非文字消息的占位。
     *
     * 语音带时长：`<voicemsg length="3200">` 里是毫秒，一条 3 秒和一条 60 秒的语音，
     * 对「说了多少话」的判断完全不同，这个数字不要钱。取不到就退回 `[语音]`。
     */
    fun placeholder(type: Int, content: String): String = when (type) {
        TYPE_IMAGE -> "[图片]"
        TYPE_VOICE -> voiceSeconds(content)?.let { "[语音 $it″]" } ?: "[语音]"
        TYPE_VIDEO, TYPE_MICRO_VIDEO -> "[视频]"
        TYPE_STICKER -> "[表情包]"
        TYPE_CARD -> "[卡片]"
        else -> ""
    }

    /** 语音时长（秒，四舍五入）。超出 1~600 秒视为解析失败。 */
    fun voiceSeconds(content: String): Int? {
        val millis = VOICE_LENGTH.find(content)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
        return ((millis + 500) / 1000).toInt().takeIf { it in 1..600 }
    }

    /** `createTime` 归一成秒：毫秒值（13 位）除以 1000，秒值（10 位）原样返回。 */
    fun toSeconds(raw: Long): Long = if (raw > 10_000_000_000L) raw / 1000 else raw

    private val VOICE_LENGTH = Regex("\\slength=\"(\\d+)\"")
}
