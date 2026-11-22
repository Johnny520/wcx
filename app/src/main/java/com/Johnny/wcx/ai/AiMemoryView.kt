package com.Johnny.wcx.ai

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 建议 / 解读时**默认注入**哪一段记忆，以及更早的那部分怎么取。
 *
 * 全量记忆标题（上限 2000 条）约 30k token，而一次建议真正用到的通常只有最近这段。
 * 默认只给「最近半年 ∪ 最新 30 条」，并且**不超过 [RECENT_MAX] 条**：
 * - 半年覆盖绝大多数日常话题；
 * - 最新 30 条是兜底：很久没聊的好友近半年可能是 0 条，没有兜底默认块就空了，
 *   模型每次都得多调一次工具才能知道这段关系里有什么；
 * - [RECENT_MAX] 是硬上限：半年内 700 条时只带最近 300 条，否则每次建议都多背 23k token。
 *
 * `recent` 之外的全部算「没有注入」，放在 [Split.earlier]，由 `load_earlier_memories`
 * 一次交付（见 [Pager]）。**改 [RECENT_MONTHS] 一个数就能恢复全量注入**（调大即可），
 * 这是时间窗口的唯一开关；[RECENT_MAX] 管的是条数。
 */
object AiMemoryView {

    /** 默认注入的时间跨度（月）。 */
    const val RECENT_MONTHS = 6

    /** 兜底条数：即使半年内没有记录，也带上最新这么多条。 */
    const val RECENT_MIN = 30

    /**
     * 注入的硬上限。
     *
     * 半年内 700 条时，只按时间窗切会让每次建议都带 700 条标题（约 23k token）。
     * 被挤出的条目仍然算「没有注入」，落在 `earlier` 里，一次取回时会带上它们。
     */
    const val RECENT_MAX = 300

    /**
     * 切分结果，两侧都保持**时间正序**且带全局序号。
     *
     * 序号必须是全档的：[AiTools.readMemory] 按同一份 `AiMemoryIndex` 解析，
     * 若默认块只给「最近 30 条」并重新编号，翻出来的 `#12` 与 `read_memory` 就对不上。
     */
    data class Split(
        val recent: List<Pair<Int, MemoryEntry>>,
        val earlier: List<Pair<Int, MemoryEntry>>,
    )

    fun of(profile: AiProfile): Split = split(profile.memories)

    /**
     * 按「最近半年 ∪ 最新 30 条」切分，再截到 [RECENT_MAX]。
     *
     * **精度不足就归入近期**：记忆日期有四种精度（`yyyy-MM-dd HH:mm` / `yyyy-MM-dd` /
     * `yyyy-MM` / `yyyy`），只有「2026」这种无法证明它早于半年线，宁可多带一条，
     * 也不能把可能是近期的事判成旧的 —— 判错会让模型以为「档案里没有」，而不是去翻。
     * `未知日期` 同理。
     */
    fun split(
        memories: List<MemoryEntry>,
        nowSec: Long = System.currentTimeMillis() / 1000,
    ): Split {
        val numbered = AiMemoryIndex.numbered(memories)
        if (numbered.size <= RECENT_MIN) return Split(numbered, emptyList())
        val cutoff = cutoffMonth(nowSec)
        val keep = HashSet<Int>(numbered.size)
        numbered.forEach { (number, entry) ->
            if (isRecent(entry.occurredAt, cutoff)) keep += number
        }
        numbered.takeLast(RECENT_MIN).forEach { (number, _) -> keep += number }
        val kept = numbered.filter { it.first in keep }
        // 只截时间窗内的部分：takeLast 保留最近的，最早的一批回到 earlier。
        val recent = if (kept.size <= RECENT_MAX) kept else kept.takeLast(RECENT_MAX)
        val recentNumbers = recent.mapTo(HashSet()) { it.first }
        return Split(
            recent = recent,
            earlier = numbered.filterNot { it.first in recentNumbers },
        )
    }

    /** 一条一条渲染成 `#12 2021-03-05 见面★`。 */
    fun render(entries: List<Pair<Int, MemoryEntry>>, separator: String = "、"): String =
        entries.joinToString(separator) { (number, entry) ->
            "#$number ${entry.occurredAt} ${entry.title}" + if (entry.important) "★" else ""
        }

    /**
     * 一次请求内的交付标记。
     *
     * 工具**没有参数**，靠这个类知道「已经给过什么」：交付过的部分不会再给，
     * 而不是把同一批条目再灌一遍。带 `before=日期` 的方案要求模型抄日期，
     * 抄错就会重复加载 —— 重复的几千 token 比多一个类贵得多。
     *
     * **一次给完，不分页**：模型不知道第 100 条之后有什么，就没法判断要不要翻第二页 ——
     * 它只能整体看一遍，按标题认出「是那次」，再用 `read_memory` 读详情。
     * 上限 1700 条约 25k token，建议请求其余部分约 17k，64k 窗口放得下。
     */
    class Pager internal constructor(private val earlier: List<Pair<Int, MemoryEntry>>) {

        @Volatile
        private var delivered = false

        val remaining: Int get() = if (delivered) 0 else earlier.size

        /** 一次性交付剩余部分；已经交付过则返回空。 */
        fun takeEarlier(): List<Pair<Int, MemoryEntry>> {
            if (delivered) return emptyList()
            delivered = true
            return earlier
        }
    }

    fun pager(profile: AiProfile): Pager = Pager(of(profile).earlier)

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 半年线的月份键 `yyyy-MM`。 */
    private fun cutoffMonth(nowSec: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowSec * 1000
        calendar.add(Calendar.MONTH, -RECENT_MONTHS)
        return SimpleDateFormat("yyyy-MM", Locale.US).format(calendar.time)
    }

    private fun isRecent(occurredAt: String, cutoffMonth: String): Boolean {
        val text = occurredAt.trim()
        if (text.isEmpty()) return true
        val normalized = AiDates.normalize(text) ?: return true
        // 只有年份时比到年：同一年或更晚都算近期，否则 2026 会被判成旧记录。
        return if (normalized.length == 4) {
            normalized >= cutoffMonth.substring(0, 4)
        } else {
            normalized.substring(0, 7) >= cutoffMonth
        }
    }
}
