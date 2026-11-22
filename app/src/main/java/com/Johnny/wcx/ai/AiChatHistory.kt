package com.Johnny.wcx.ai

import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.WeMessage
import java.util.Calendar

/**
 * 聊天记录读取层：把「年 / 月 / 日 / 区间」翻译成时间窗，按条数上限取样。
 *
 * 四条硬规则：
 * 1. **读这五类**：文字、图片、语音、视频、表情包。后四类进 [Line.text] 的是一行占位
 *    （`[图片]` / `[语音 3″]` / `[表情包]`），不是原文 —— 它们对「对方刚说过什么」有信息量，
 *    但内容不由这里解析。应用消息（49）、系统消息、撤回、通话记录一律不放。
 * 2. **分片只看文字**：[count] / [yearHistogram] / [monthHistogram] 按 `type = 1`，
 *    片数与预览的「N 条记录」不受媒体影响；[load] 内部算 `total` 时按放行的全部类型数
 *    （用来判断有没有被 [limit] 截断），两者口径不同，不要合并。
 * 3. **不抽样**：窗口由调用方切小，这里原样返回；只有超过安全上限才截断并标记 `sampled`。
 *    抽样会让模型看不到完整对话，写出来的事件与上下文对不上。
 * 4. 每条消息截断到 [MessageRendering.MAX_LINE_CHARS]，并压掉换行 —— 单条超长文本（粘贴的公告、
 *    转发的长文）能把一次请求的预算吃光。
 *
 * **所有方法都是阻塞的**（查库），调用方必须在 IO 线程 / 协程里。
 */
object AiChatHistory {

    const val TYPE_TEXT = MessageRendering.TYPE_TEXT

    /** 微信消息类型：1 = 文字。分片、关键词搜索、预览计数都只用它。 */
    val TEXT_ONLY = MessageRendering.TEXT_ONLY

    /** 读聊天记录时放行的类型：文字 + 图片 + 语音 + 视频 + 表情包。 */
    val CHAT_TYPES = MessageRendering.CHAT_TYPES

    /** [load] 的「全量」标记：窗口内有多少读多少。 */
    const val UNBOUNDED = 0

    /**
     * 按年统计文字条数：一次聚合拿到全部年份的分布。
     *
     * 逐次 `count(year)` 在长历史会话上要几十次查询，而初始化前的预览要求「换一档范围就
     * 重算一次」，逐次查询等不起。`createTime / ?` 把秒 / 毫秒归一
     * （见 `WeDatabaseApi.messageTimeScale`），`localtime` 让年份按本地时区切，
     * 与 [windowOf] 的窗口边界一致。
     */
    private val SQL_YEAR_HISTOGRAM = """
        SELECT CAST(strftime('%Y', createTime / ?, 'unixepoch', 'localtime') AS INTEGER) AS y,
               COUNT(*) AS cnt
        FROM message
        WHERE talker = ?
          AND createTime > 0
          AND createTime < ?
          AND type = 1
        GROUP BY y
        ORDER BY y DESC
    """.trimIndent()

    /** 按月份统计某一年内的文字条数（年片超限时才用）。 */
    private val SQL_MONTH_HISTOGRAM = """
        SELECT CAST(strftime('%m', createTime / ?, 'unixepoch', 'localtime') AS INTEGER) AS m,
               COUNT(*) AS cnt
        FROM message
        WHERE talker = ?
          AND createTime >= ?
          AND createTime < ?
          AND type = 1
        GROUP BY m
        ORDER BY m
    """.trimIndent()

    /** 一个时间窗。`label` 直接用于提示词与工具返回值，用户能看懂。 */
    data class TimeWindow(val fromSec: Long, val toSec: Long, val label: String)

    /**
     * 一行聊天记录。
     *
     * [text] 可能是文字原文，也可能是非文字消息的占位（`[图片]` / `[语音 3″]` / `[表情包]`）——
     * **下游不需要再判类型**：`transcript`、分片、风格样本都直接用它。
     * 只有「这句话算不算本人发言」这类统计需要看 [isText]。
     */
    data class Line(
        val at: Long,
        val fromMe: Boolean,
        val text: String,
        /** 微信 type：1 文字 / 3 图片 / 34 语音 / 43、62 视频 / 47 表情包。 */
        val kind: Int = TYPE_TEXT,
        /** 图片的 `msgSvrId`，用来按需取图；其它类型为 0。 */
        val mediaId: Long = 0,
    ) {
        val isText: Boolean get() = kind == TYPE_TEXT
        val isImage: Boolean get() = kind == MessageRendering.TYPE_IMAGE
    }

    data class Slice(
        val lines: List<Line>,
        /**
         * 窗口内放行类型（文字 / 图片 / 语音 / 视频 / 表情）的消息总数（取样前）。
         * [load] 用 UNBOUNDED 时不查计数（省一次 COUNT），此时等于 [lines] 的条数。
         */
        val total: Int,
        /** true 表示被 SQL 的 LIMIT 截断，模型看到的不是完整记录。 */
        val sampled: Boolean,
        val window: TimeWindow,
    )

    // ── 时间窗 ──────────────────────────────────────────────────────────────

    /**
     * 解析工具参数成时间窗。
     *
     * 优先级：`day` > `month` > `year`；`fromDate` / `toDate` 与它们互斥，
     * 同时给时以区间为准。全空表示「全部历史」，上限到明天零点（含今天）。
     */
    fun windowOf(
        year: Int? = null,
        month: Int? = null,
        day: Int? = null,
        fromDate: String? = null,
        toDate: String? = null,
    ): TimeWindow {
        val from = fromDate?.let { parseBound(it, end = false) }
        val to = toDate?.let { parseBound(it, end = true) }
        if (from != null || to != null) {
            val start = from ?: 0L
            val end = to ?: tomorrowStart()
            return TimeWindow(start, maxOf(end, start + 86_400L), labelOf(fromDate, toDate))
        }

        if (year != null) {
            if (month != null && month in 1..12) {
                if (day != null && day in 1..31) {
                    return TimeWindow(
                        millis(year, month, day),
                        millis(year, month, day) + 86_400L,
                        "$year-$month-$day",
                    )
                }
                return TimeWindow(
                    millis(year, month, 1),
                    millis(year, month, 1).plusMonths(1),
                    "$year 年 $month 月",
                )
            }
            return TimeWindow(
                millis(year, 1, 1),
                millis(year, 1, 1).plusMonths(12),
                "$year 年",
            )
        }

        return TimeWindow(0L, tomorrowStart(), "全部历史")
    }

    /** 今天 00:00 到明天 00:00（本地时区）。智能建议只用当天记录。 */
    fun todayWindow(): TimeWindow {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val from = millis(year, month, day)
        return TimeWindow(from, from + 86_400L, "$year-$month-$day")
    }

    /**
     * 昨天 00:00 到明天 00:00。
     *
     * 凌晨刚过时「今天」常常只有一两句，上一次对话还停在 23:59；这时要连着昨天一起读，
     * 否则模型看到的是一段从半句话开始的对话。只在当天记录很少时由上层使用。
     */
    fun lastDayWindow(): TimeWindow {
        val today = todayWindow()
        val from = today.fromSec - 86_400L
        return TimeWindow(from, today.toSec, isoRangeLabel(from, today.toSec))
    }

    /** 明天 00:00（秒）。增量整理与工具的全历史窗口都用它当右端。 */
    fun tomorrowStart(): Long {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis / 1000
    }

    /** 取 [startSec, endSec) 一段区间，标签按实际范围生成。 */
    fun windowOfRange(startSec: Long, endSec: Long): TimeWindow {
        val start = startSec.coerceAtLeast(0L)
        val end = endSec.coerceAtLeast(start + 1)
        return TimeWindow(start, end, isoRangeLabel(start, end))
    }

    fun yearOf(sec: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = sec * 1000
        return calendar.get(Calendar.YEAR)
    }

    // ── 读取 ────────────────────────────────────────────────────────────────

    /**
     * 读一个时间窗，**不抽样、不按条数截断**。
     *
     * 早先按 3000 条截断，后果是「这一片靠后的对话整段消失」：模型看不到对方说过的关键几句，
     * 写出来的事件和真实经过对不上。理解一个人需要完整上下文，所以现在只有显式传
     * [limit]（工具调用）才截断；初始化与智能建议传 [UNBOUNDED]，窗口由上层切小，
     * 切到多小就给多少，一条不少。
     *
     * @param limit 传 [UNBOUNDED] 表示全量；正数表示安全上限（只有工具路径用）。
     */
    fun load(wxId: String, window: TimeWindow, limit: Int = UNBOUNDED): Slice {
        if (wxId.isBlank()) return Slice(emptyList(), 0, false, window)
        val unbounded = limit <= 0
        val cap = if (unbounded) Int.MAX_VALUE else limit
        // 不设上限时 `total` 没有任何用处：`sampled` 只可能 false，工具那条路径又总是带 limit。
        // 少跑一次全窗口 COUNT —— 初始化每片、每次建议都省一次。
        val total = if (unbounded) -1 else WeDatabaseApi.countMessagesBetween(
            wxId, window.fromSec, window.toSec, CHAT_TYPES
        )
        if (total == 0) return Slice(emptyList(), 0, false, window)

        val raw = WeDatabaseApi.getMessagesBetween(
            wxId, window.fromSec, window.toSec, cap, CHAT_TYPES
        )

        val lines = raw.mapNotNull { renderLine(it) }
        return Slice(
            lines = lines,
            total = if (unbounded) lines.size else total,
            sampled = !unbounded && total > cap,
            window = window,
        )
    }

    /**
     * 一条消息 → 一行。非文字渲染成占位符，[Line.text] 里直接是给模型看的那一行。
     *
     * `type = 1` 里也混着 XML（小程序卡片、引用残留），[MessageRendering.sanitize] 会把它清成空、
     * 这里一并丢掉 —— 和「媒体占位」是两回事。
     */
    private fun renderLine(message: WeMessage): Line? {
        val type = message.typeCode
        val text = MessageRendering.render(type, message.content) ?: return null
        // 库里的 createTime 可能是秒也可能是毫秒（见 WeDatabaseApi.messageTimeScale），
        // 对外统一成秒：后续所有格式化、按年分组都按秒算。
        return Line(
            at = MessageRendering.toSeconds(message.createTime),
            fromMe = message.isSend == 1,
            text = text,
            kind = type,
            mediaId = if (type == MessageRendering.TYPE_IMAGE) message.msgSvrId else 0,
        )
    }

    /**
     * 全量关键词搜索：直接在库里按正文 LIKE，不受 [load] 的 limit 限制。
     *
     * 返回按时间正序的原始句子（带日期），用于「记忆里没有、但记得说过某句话」的场景。
     * 库查询是倒序取最近 N 条，这里反转成正序，模型读起来才顺。
     */
    fun search(wxId: String, keyword: String, window: TimeWindow, limit: Int = 200): List<Line> {
        if (wxId.isBlank() || keyword.isBlank()) return emptyList()
        return WeDatabaseApi.searchMessages(
            wxId, keyword, window.fromSec, window.toSec, limit, TEXT_ONLY
        )
            .mapNotNull { renderLine(it) }
            .sortedBy { it.at }
    }

    /**
     * 某个时间窗里的文字消息条数（规划分片用，不读内容）。
     *
     * 只数 `type = 1`：一片按 5000 条**文字**切，图、语音、表情不参与 ——
     * 否则「同一个窗口有多少人说话」会被表情包数量带偏，片数跟着漂。
     */
    fun count(wxId: String, window: TimeWindow): Int {
        if (wxId.isBlank()) return 0
        return WeDatabaseApi.countMessagesBetween(wxId, window.fromSec, window.toSec, TEXT_ONLY)
    }

    /**
     * 按年统计文字条数，一次查询拿全部年份。
     *
     * 分片规划原本逐年 `count`：长历史会话是几十次查询，而初始化前的预览要求「换一档范围就
     * 重算一次」，逐次查询等不起。改用一条 `GROUP BY` 后，预览与切换范围都是毫秒级。
     *
     * @return 年份 → 条数（从新到旧）；返回 null 表示数据库未就绪。**空列表不能当作
     *   「没有记录」**：查询失败时 `executeQuery` 也返回空，调用方要再用 [earliestSec]
     *   确认有没有记录，决定是降级逐次统计还是直接报空。
     */
    fun yearHistogram(wxId: String): List<Pair<Int, Int>>? {
        if (wxId.isBlank() || !WeDatabaseApi.isReady) return null
        return histogram(wxId, SQL_YEAR_HISTOGRAM, "y", 0L)
    }

    /** 某一年的按月分布（年片超限时用）；返回 null 表示要逐月 `count` 兜底。 */
    fun monthHistogram(wxId: String, year: Int): List<Pair<Int, Int>>? {
        if (wxId.isBlank() || !WeDatabaseApi.isReady) return null
        val window = windowOf(year = year)
        return histogram(wxId, SQL_MONTH_HISTOGRAM, "m", window.fromSec)
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 聚合查询的公共部分：归一 `createTime` 单位、绑定参数、把行翻成 `键 → 条数`。
     *
     * @param keyColumn 分组列名（年 `y` / 月 `m`）。
     * @param fromSec 起点；传 0 表示从最早开始（年查询的 SQL 里没有起点参数）。
     */
    private fun histogram(
        wxId: String,
        sql: String,
        keyColumn: String,
        fromSec: Long,
    ): List<Pair<Int, Int>>? {
        val scale = WeDatabaseApi.messageTimeScale()
        val toSec = tomorrowStart()
        val rows = if (fromSec > 0) {
            WeDatabaseApi.executeQuery(sql, arrayOf(scale, wxId, fromSec * scale, toSec * scale))
        } else {
            WeDatabaseApi.executeQuery(sql, arrayOf(scale, wxId, toSec * scale))
        }
        return rows.mapNotNull { row ->
            val key = (row[keyColumn] as? Number)?.toInt() ?: return@mapNotNull null
            val count = (row["cnt"] as? Number)?.toInt() ?: 0
            if (key <= 0 || count <= 0) null else key to count
        }
    }

    /** 该会话最早一条文字消息的时间（秒）；没有记录返回 null。用于初始化提示词里的「首次聊天」。 */
    fun earliestSec(wxId: String): Long? {
        if (wxId.isBlank()) return null
        val raw = WeDatabaseApi.getMessagesBetween(
            wxId, 0L, tomorrowStart(), 1, TEXT_ONLY
        ).firstOrNull()?.createTime ?: return null
        return MessageRendering.toSeconds(raw)
    }

    /** 有文字记录的年份，从新到旧。初始化按年切片就用这个。 */
    fun yearsDesc(wxId: String): List<Int> {
        val earliest = earliestSec(wxId) ?: return emptyList()
        val firstYear = yearOf(earliest)
        val current = yearOf(System.currentTimeMillis() / 1000)
        if (firstYear <= 0 || firstYear > current) return listOf(current)
        return (current downTo firstYear).toList()
    }

    // ── 输出 ────────────────────────────────────────────────────────────────

    /**
     * 转成提示词里的 transcript。一行一条：`[09:12] 我: 早`。
     *
     * [withDate] 为 true 时带上日期（跨年的切片必须带，否则模型分不清这条是哪一年的）。
     */
    fun transcript(lines: List<Line>, withDate: Boolean = false, myName: String = "我"): String {
        if (lines.isEmpty()) return ""
        val pattern = if (withDate) "yyyy-MM-dd HH:mm" else "HH:mm"
        val format = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
        return lines.joinToString("\n") { line ->
            val who = if (line.fromMe) myName else "对方"
            "[" + format.format(java.util.Date(line.at * 1000)) + "] " + who + ": " + line.text
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun labelOf(from: String?, to: String?): String = when {
        from != null && to != null -> "$from ~ $to"
        from != null -> "$from 至今"
        to != null -> "至 $to"
        else -> "全部历史"
    }

    /** `2019` / `2019-05` / `2019-5` / `2019-05-03` 都能解析。`end = true` 取该单位的下一单位起点。 */
    private fun parseBound(raw: String, end: Boolean): Long? {
        val parts = raw.trim().split("-", "/", ".").mapNotNull { it.trim().toIntOrNull() }
        if (parts.isEmpty()) return null
        val year = parts[0]
        if (year < 1990 || year > 2100) return null
        val month = parts.getOrNull(1)
        val day = parts.getOrNull(2)
        return when {
            day != null && day in 1..31 -> {
                val base = millis(year, month ?: 1, day)
                if (end) base + 86_400L else base
            }
            month != null && month in 1..12 -> {
                val base = millis(year, month, 1)
                if (end) base.plusMonths(1) else base
            }
            else -> {
                val base = millis(year, 1, 1)
                if (end) base.plusMonths(12) else base
            }
        }
    }

    /** 本地时区的某日零点（秒）。 */
    private fun millis(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, 0, 0, 0)
        return calendar.timeInMillis / 1000
    }

    /**
     * 「2026-11-18」/「2026-11-18 ~ 2026-11-20」，用于区间片的进度文案。
     *
     * 名字带 `iso` 是为了和 `AiProfileInitializer.rangeLabel` 区分：那个按中文月份写
     * （「2026 年 11 月 1 日 ~ 8 日」），给用户看；这个只用于机器对账的区间标记。
     */
    private fun isoRangeLabel(fromSec: Long, toSec: Long): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val from = format.format(java.util.Date(fromSec * 1000))
        val to = format.format(java.util.Date((toSec - 1).coerceAtLeast(fromSec) * 1000))
        return if (from == to) from else "$from ~ $to"
    }

    private fun Long.plusMonths(months: Int): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = this * 1000
        calendar.add(Calendar.MONTH, months)
        return calendar.timeInMillis / 1000
    }
}
