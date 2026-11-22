package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * 初始化编排：按时间片读记录 → 每片让模型一次写入标签与记忆。
 *
 * 重复项不在这里合并 —— 管理弹窗里有一个独立的「审查档案」按钮（`AiProfileAudit`），
 * 因为「通读记录整理」和「审查已有条目」是两件事，混在一起会拖长初始化、也让模型
 * 在最后一步顺手改写标签。
 *
 * 两种入口：
 * - **首次整理**：从最早一条记录开始，按年 / 月 / 对半切分。
 * - **增量整理**：从档案里最新一条事件的前一天开始，只读新增的聊天记录。
 *
 * 为什么按时间分片：一个聊了十年的好友文字记录可能上万条，一次请求放不进上下文，
 * 中途失败还要从头再来。分片后每片独立上下文，靠工具的 upsert 幂等性保证重跑不产生重复。
 *
 * **分片不等于截断**：片再大也不裁剪内容，窗口切到多小就发多少，一条不少 ——
 * 丢了对方说过的关键几句，写出来的事件就会与真实经过对不上。
 *
 * 线程模型：整段是阻塞的（查库 + 同步 HTTP），必须从后台线程启动，
 * 用 [start] 即可；进度写在档案的 `progress` 字段里，页面轮询文件读取。
 */
object AiProfileInitializer {

    private const val TAG = "AiProfileInitializer"

    /**
     * 单片的目标条数：超过就继续切窗，**不抽样、不截断**。
     *
     * 5000 条约 100k token。这个数字只决定「要不要再切一刀」，
     * 切到深度上限仍超时就整段发出去，不做任何裁剪。
     */
    private const val SLICE_MAX = 5_000

    /** 递归对半切的深度上限：再切下去单片已经是一两天，没有继续切的意义。 */
    private const val MAX_SPLIT_DEPTH = 4

    /** 一年里的有效文字少于这个数就跳过 —— 只聊过几句的年份写不出可信的标签。 */
    private const val YEAR_MIN_LINES = 10

    /** 逐月/对半切时，一段少于这个数也跳过（空月、只发过一两句的时段）。 */
    private const val MONTH_MIN_LINES = 3

    /**
     * 侧写的样本门槛：本人可用发言（3~40 字）少于这个数就不汇总。
     *
     * 只聊过几句的关系，汇总出来的只会是「说话简短、比较直接」这种放之四海皆准的话，
     * 对生成没有约束力，还会让档案页显示一段看起来很像回事的假侧写。
     */
    private const val STYLE_MIN_OWN_LINES = 30

    /** 「近期」占总跨度的比例：后 1/3 的时间。 */
    private const val STYLE_RECENT_DIVISOR = 3

    /** 跨度短于 90 天时不分早晚，全部按近期处理。 */
    private const val STYLE_MIN_SPAN_SEC = 90L * 86_400L

    /** 正在初始化的好友，防止连点两次启动两条流水线。 */
    private val running = ConcurrentHashMap.newKeySet<String>()

    fun isRunning(wxId: String): Boolean = running.contains(wxId)

    /** 是否有任何好友正在跑长任务。群总结用它避让，不能按 wxId 一个个查。 */
    fun isAnyRunning(): Boolean = running.isNotEmpty()

    /**
     * 进程启动时调用：把「上次没跑完」的档案改成失败态。
     *
     * 初始化是内存线程，微信被杀 / 被重装 / 用户强停后线程必然消失，但档案里还留着
     * `initializing`；不重置的话弹窗会一直轮询进度、按钮也不出现，用户没有任何重试入口。
     */
    fun recoverInterrupted() {
        AiProfileStore.listProfileIds().forEach { id ->
            // 先只读判断，确实需要复位才写盘。
            // 原来无条件 patchProfile，而 patchProfile 无条件 saveProfile + 重写 index.json
            // ——等于每次微信启动都把全部档案和索引重写一遍（300 个好友 = 300 次档案写
            // + 300 次索引全量重写），这是启动路径上最不该有的开销。
            val profile = AiProfileStore.readProfile(id) ?: return@forEach
            when {
                // 初始化：内存线程没了，状态必须复位成可重试的失败态。
                profile.state == AiProfileState.INITIALIZING -> {
                    AiProfileStore.patchProfile(id) {
                        it.copy(
                            state = AiProfileState.FAILED,
                            lastError = "上次整理被中断，请重新开始",
                            progress = "",
                            auditing = false,
                            // 这批观察没有对应任何产出（汇总没跑），留着只会白占几十 KB；
                            // 下次 run() 本来也会清。
                            styleNotes = emptyList(),
                        )
                    }
                    WeLogger.i(TAG, "重置被中断的初始化：$id")
                }
                // 审计：档案本身是好的，只把「检查中」这个标记清掉。
                // **progressDone / progressTotal 保留**：它们是「从第 N 片继续」的起点，
                // 进程被杀后用户还要靠它续跑，清了就只能从头再花一遍钱。
                profile.auditing -> {
                    AiProfileStore.patchProfile(id) {
                        it.copy(auditing = false, progress = "")
                    }
                    WeLogger.i(TAG, "重置被中断的档案检查：$id")
                }
            }
        }
    }

    /**
     * 一次整理的完整计划：**先让用户看过，再执行**。
     *
     * 历史太长时片数会到二十几片，而标签与记忆的上限（100 / 500）在前面几片就会打满 ——
     * 让用户自己选起点，比一律全量更合理，也让「多少条 / 预计几片」有一个可确认的载体。
     */
    data class Plan(
        /** 用户选的起始年；null 表示全部。 */
        val fromYear: Int?,
        /** 实际起点：所选年份的 1 月 1 日，或最早一条记录。 */
        val fromSec: Long,
        val toSec: Long,
        /** 所选范围内的文字条数。 */
        val totalLines: Int,
        /** 要跑的分片；空表示这段范围切不出片（原因见 [emptyReason]）。 */
        val slices: List<Slice>,
        /** 可选的起始年份，从新到旧；界面据此排选项。 */
        val years: List<Int>,
        /** 全量最早一天与总条数，用于「聊天记录」那一行。 */
        val allFromSec: Long,
        val allTotalLines: Int,
        /** 不能开始的原因（数据库未就绪 / 没有文字）；非 null 时按钮必须禁用。 */
        val emptyReason: String?,
    )

    /**
     * 按计划跑全量整理。返回 false 表示已在跑（或档案正忙）。
     *
     * 计划由 [preview] 生成：用户看到的条数与片数，和实际执行的是同一份分片，
     * 不会出现「显示 11 片、实际跑 13 片」。
     *
     * @param fromIndex 从第几片开始。上次中断时档案里的 `progressDone` 就是它 ——
     *   用户点「从第 N 片继续」时传进来，跳过前面已跑完的片，不用从头再花一遍钱。
     */
    fun start(wxId: String, plan: Plan, fromIndex: Int = 0): Boolean =
        runInBackground(wxId) { run(wxId, plan, fromIndex) }

    /** 增量整理：起点由档案里最新一条事件决定，不需要计划。 */
    fun startIncremental(wxId: String): Boolean =
        runInBackground(wxId) { runIncremental(wxId) }

    private fun runInBackground(wxId: String, block: () -> Boolean): Boolean {
        if (!running.add(wxId)) {
            // 连点：上一条流水线还在跑。界面靠这行日志才能分清「没启动」和「已经在跑」。
            WeLogger.d(TAG, "$wxId 已有整理在跑，忽略本次启动")
            return false
        }
        if (AiProfileAudit.isRunning(wxId) || AiProfileStore.readProfile(wxId)?.busy == true) {
            WeLogger.d(TAG, "$wxId 档案正忙（审查或整理中），忽略本次启动")
            running.remove(wxId)
            return false
        }
        Thread({
            try {
                block()
            } catch (e: Exception) {
                WeLogger.e(TAG, "初始化异常：$wxId", e)
                fail(wxId, e.message ?: e.javaClass.simpleName)
            } finally {
                running.remove(wxId)
            }
        }, "wcx-ai-init").start()
        return true
    }

    /**
     * 预览：统计记录范围与分片，**不写档案、不发请求**。
     *
     * 进入未初始化页面时调它。用户看过总量、选好起始年后再 [start] —— 统计没完成就不该能开始，
     * 否则「现在到底要跑多少」只能等跑完才知道。
     *
     * @param fromYear 起始年；null 表示全部。
     */
    fun preview(wxId: String, fromYear: Int? = null): Plan {
        val startedAt = System.currentTimeMillis()
        val toSec = AiChatHistory.tomorrowStart()
        val years = AiChatHistory.yearsDesc(wxId)
        val allFromSec = AiChatHistory.earliestSec(wxId) ?: 0L
        val fromSec = if (fromYear == null) allFromSec
        else AiChatHistory.windowOf(year = fromYear).fromSec
        val picked = if (fromYear == null) years else years.filter { it >= fromYear }

        val total = AiChatHistory.count(wxId, AiChatHistory.windowOfRange(fromSec, toSec))
        // 选了范围才需要第二次统计；选「全部」时两者相同。
        val allTotal = if (fromSec == allFromSec) total
        else AiChatHistory.count(wxId, AiChatHistory.windowOfRange(allFromSec, toSec))

        val slices = planSlices(wxId, picked)
        // 一条 I 级日志就能回答「统计走的是聚合还是逐次」：没有降级 W 就是聚合。
        WeLogger.i(
            TAG,
            "预览 $wxId：${AiPrompts.dateOf(fromSec)} 起，${total} 条 / ${slices.size} 片，" +
                "耗时 ${System.currentTimeMillis() - startedAt} ms",
        )
        return Plan(
            fromYear = fromYear,
            fromSec = fromSec,
            toSec = toSec,
            totalLines = total,
            slices = slices,
            years = years,
            allFromSec = allFromSec,
            allTotalLines = allTotal,
            emptyReason = if (slices.isEmpty()) emptyReasonOf(wxId, allTotal) else null,
        )
    }

    /**
     * 一段范围切不出片时，说明卡在哪。
     *
     * 区分「一条消息都没有」与「有消息但不是文字」：前者多半是 wxId 取错或库未就绪，
     * 后者说明这段关系里全是图片 / 语音。两种处置完全不同，不能只报一句「无记录」。
     */
    private fun emptyReasonOf(wxId: String, allTotal: Int): String {
        if (!com.Johnny.wcx.features.api.core.WeDatabaseApi.isReady) {
            return "读不到微信数据库（数据库服务未就绪），请重启微信后重试"
        }
        if (allTotal > 0) return "这一段里没有可整理的记录"
        val all = runCatching {
            com.Johnny.wcx.features.api.core.WeDatabaseApi.countMessagesBetween(
                wxId, 0L, AiChatHistory.tomorrowStart(), null
            )
        }.getOrDefault(-1)
        return if (all <= 0) "这个会话没有任何消息记录"
        else "这个会话的 $all 条消息里没有文字（只有图片 / 语音 / 表情）"
    }

    /** 一片 = 一个时间窗。`label` 直接显示在进度里（「2024 年 5 月 · 第 12/48 片」）。 */
    data class Slice(
        val window: AiChatHistory.TimeWindow,
        val label: String,
        val year: Int,
        val minLines: Int,
    )

    /**
     * 规划分片：**先按年；一年超过 [SLICE_MAX] 条就按月；单月仍超就再对半切**。
     *
     * 条数优先用一次聚合查询（[AiChatHistory.yearHistogram]）：逐次 `count` 在长历史会话上
     * 要几十秒，而预览要求「换一档范围就重算一次」。聚合拿不到时整段退回逐次统计 ——
     * 结果一样，只是慢。
     *
     * 一律不抽样。抽样（无论取头尾还是均匀取）都会让模型看不到完整对话，写出来的事件
     * 与上下文对不上；宁可多切几片、多发几次请求，也要保证每一片是连续的原文。
     *
     * @param years 要规划的年份，从新到旧（已按用户选择过滤）。
     */
    private fun planSlices(wxId: String, years: List<Int>): List<Slice> {
        if (years.isEmpty()) return emptyList()
        val histogram = AiChatHistory.yearHistogram(wxId)
        // 空分布不能当「没记录」：查询失败时也是空。有记录却拿不到分布就走逐次统计。
        val usable = histogram != null &&
            (histogram.isNotEmpty() || AiChatHistory.earliestSec(wxId) == null)
        if (!usable) {
            // 这是能力探测的结果，值得落盘：确认到底是走了聚合还是退回了逐次统计。
            WeLogger.w(TAG, "$wxId 年分布不可用，退回逐次统计（结果一致，只是慢）")
            return planSlicesByCount(wxId, years)
        }
        val byYear = histogram.orEmpty().toMap()
        return years.flatMap { year ->
            val total = byYear[year] ?: 0
            if (total < YEAR_MIN_LINES) return@flatMap emptyList()
            if (total <= SLICE_MAX) {
                return@flatMap listOf(
                    Slice(AiChatHistory.windowOf(year = year), "$year 年", year, YEAR_MIN_LINES)
                )
            }
            planYearByMonth(wxId, year)
        }
    }

    /** 降级路径：逐次 `count`。聚合不可用时用，结果与聚合一致。 */
    private fun planSlicesByCount(wxId: String, years: List<Int>): List<Slice> {
        val out = mutableListOf<Slice>()
        years.forEach { year ->
            val window = AiChatHistory.windowOf(year = year)
            val total = AiChatHistory.count(wxId, window)
            if (total < YEAR_MIN_LINES) return@forEach
            if (total <= SLICE_MAX) {
                out += Slice(window, "$year 年", year, YEAR_MIN_LINES)
                return@forEach
            }
            (1..12).forEach { month ->
                splitWindow(
                    wxId, AiChatHistory.windowOf(year = year, month = month),
                    "$year 年 $month 月", year, 0, out,
                )
            }
        }
        return out
    }

    /**
     * 超限的一年按月切；某个月仍超 [SLICE_MAX] 才继续对半切
     * （一天几十条的极端记录，正常关系走不到这一步）。
     */
    private fun planYearByMonth(wxId: String, year: Int): List<Slice> {
        val histogram = AiChatHistory.monthHistogram(wxId, year)
        if (histogram == null) {
            WeLogger.w(TAG, "$wxId $year 年的月分布不可用，退回逐月统计")
            val out = mutableListOf<Slice>()
            (1..12).forEach { month ->
                splitWindow(
                    wxId, AiChatHistory.windowOf(year = year, month = month),
                    "$year 年 $month 月", year, 0, out,
                )
            }
            return out
        }
        val byMonth = histogram.toMap()
        return (1..12).flatMap { month ->
            val total = byMonth[month] ?: 0
            if (total < MONTH_MIN_LINES) return@flatMap emptyList()
            val window = AiChatHistory.windowOf(year = year, month = month)
            if (total <= SLICE_MAX) {
                return@flatMap listOf(Slice(window, "$year 年 $month 月", year, MONTH_MIN_LINES))
            }
            val out = mutableListOf<Slice>()
            splitWindow(wxId, window, "$year 年 $month 月", year, 0, out)
            out
        }
    }

    /**
     * 递归对半切，直到单片不超过 [SLICE_MAX]。
     *
     * 切到 [MAX_SPLIT_DEPTH] 仍超时**整片照发**，不再按条数裁剪：一段两天内 5000 条
     * 的极端记录，宁可让模型多读，也不能把后半段悄悄丢掉。
     */
    private fun splitWindow(
        wxId: String,
        window: AiChatHistory.TimeWindow,
        label: String,
        year: Int,
        depth: Int,
        out: MutableList<Slice>,
        minLines: Int = MONTH_MIN_LINES,
    ) {
        val total = AiChatHistory.count(wxId, window)
        if (total < minLines) return
        if (total <= SLICE_MAX || depth >= MAX_SPLIT_DEPTH) {
            out += Slice(window, label, year, minLines)
            return
        }
        val mid = (window.fromSec + window.toSec) / 2
        if (mid <= window.fromSec || mid >= window.toSec) {
            out += Slice(window, label, year, minLines)
            return
        }
        // 子片的标签直接按自己的时间窗生成，不在父标签后面追加：
        // 追加会得到「2026 年 11 月 11-01~11-15 11-01~11-08」这种读不出所以然的名字。
        splitWindow(
            wxId, AiChatHistory.TimeWindow(window.fromSec, mid, label),
            rangeLabel(window.fromSec, mid), year, depth + 1, out, minLines,
        )
        splitWindow(
            wxId, AiChatHistory.TimeWindow(mid, window.toSec, label),
            rangeLabel(mid, window.toSec), year, depth + 1, out, minLines,
        )
    }

    /**
     * 增量整理的分片：从 [fromSec] 到今天，直接按时间对半切。
     *
     * 不套「年 → 月」两层：增量通常只有几天到几个月，按月会在第一段产生
     * 「11 月 18 日 ~ 11 月 30 日」这种没有意义的半截月。对半切得到的区间标签更直观。
     */
    private fun planIncremental(wxId: String, fromSec: Long, toSec: Long): List<Slice> {
        if (toSec <= fromSec) return emptyList()
        val out = mutableListOf<Slice>()
        val window = AiChatHistory.windowOfRange(fromSec, toSec)
        splitWindow(
            wxId = wxId,
            window = window,
            label = window.label,
            year = AiChatHistory.yearOf(fromSec),
            depth = 0,
            out = out,
            // 增量可能只有十几条：不能套用「整年少于 10 条就跳过」的规则。
            minLines = 1,
        )
        return out
    }

    /**
     * 增量整理的起点：档案里最新一条事件的前一天。
     *
     * 往前推一天是因为事件可能跨天（23:50 聊到 00:10），只从事件当天开始会漏掉前一晚的上下文。
     * 没有记忆时退到上次整理时间；两者都没有就返回 0（等价于全量）。
     */
    private fun incrementalStart(profile: AiProfile): Long {
        val latest = profile.memories.mapNotNull { AiDates.startSec(it.occurredAt) }.maxOrNull()
        val raw = latest ?: if (profile.initializedAt > 0) profile.initializedAt / 1000 else 0L
        if (raw <= 0) return 0L
        // 模型偶尔把事件年份写成未来（例如 2027）。起点一旦越过今天，增量窗口就永远是空的：
        // `planIncremental` 返回空 → 界面反复显示「没有新的文字记录」，只能删档重建。
        // 压到今天零点，至少能正常读到今天之前的记录。
        val todayStart = AiChatHistory.tomorrowStart() - 86_400L
        return (raw.coerceAtMost(todayStart) - 86_400L).coerceAtLeast(0L)
    }

    /** 「2026 年 11 月 1 日 ~ 8 日」/ 跨月时「2026-11-20 ~ 2026-12-03」。 */
    private fun rangeLabel(fromSec: Long, toSec: Long): String {
        val from = java.util.Calendar.getInstance().apply { timeInMillis = fromSec * 1000 }
        val to = java.util.Calendar.getInstance().apply {
            timeInMillis = (toSec - 1).coerceAtLeast(fromSec) * 1000
        }
        val sameMonth = from.get(java.util.Calendar.YEAR) == to.get(java.util.Calendar.YEAR) &&
            from.get(java.util.Calendar.MONTH) == to.get(java.util.Calendar.MONTH)
        return if (sameMonth) {
            val year = from.get(java.util.Calendar.YEAR)
            val month = from.get(java.util.Calendar.MONTH) + 1
            val dayFrom = from.get(java.util.Calendar.DAY_OF_MONTH)
            val dayTo = to.get(java.util.Calendar.DAY_OF_MONTH)
            if (dayFrom == dayTo) "$year 年 $month 月 $dayFrom 日"
            else "$year 年 $month 月 $dayFrom 日 ~ $dayTo 日"
        } else {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            format.format(from.time) + " ~ " + format.format(to.time)
        }
    }

    /**
     * 按计划执行（供 [start] 的后台线程调用，也可在测试里直接调）。
     *
     * @param fromIndex 从第几片开始，见 [start]。
     * 返回 true 表示走到 `ready`；false 表示中途失败（`lastError` 已写入档案，
     * `progressDone` 停在失败的那一片，供「从该片继续」）。
     */
    fun run(wxId: String, plan: Plan, fromIndex: Int = 0): Boolean {
        if (plan.slices.isEmpty()) {
            // 预览已经算出原因（库未就绪 / 没有文字），这里只负责把档案落到失败态。
            fail(wxId, plan.emptyReason ?: "没有读到文字记录")
            return false
        }
        return execute(wxId, plan, incrementalScan = false, fromIndex = fromIndex)
    }

    /**
     * 增量整理：只读「最新一条事件的前一天」到今天。
     *
     * 没有可用起点（没有任何记忆、也没整理过）时退回全量 —— 按十年跨度对半切会切出
     * 一片两年、两万条的超大请求，不如走正常的「年 → 月」规划。
     */
    fun runIncremental(wxId: String): Boolean {
        val base = AiProfileStore.readProfile(wxId) ?: run {
            WeLogger.w(TAG, "档案不存在，跳过初始化：$wxId")
            return false
        }
        val fromSec = incrementalStart(base)
        if (fromSec <= 0) return run(wxId, preview(wxId))

        val toSec = AiChatHistory.tomorrowStart()
        val slices = planIncremental(wxId, fromSec, toSec)
        if (slices.isEmpty()) {
            // 增量没有新记录是正常情况：不改状态、不报错，只更新提示。
            if (com.Johnny.wcx.features.api.core.WeDatabaseApi.isReady) {
                AiProfileStore.patchProfile(wxId) {
                    it.copy(
                        state = AiProfileState.READY,
                        lastError = null,
                        progress = "",
                        progressDone = 0,
                        progressTotal = 0,
                    )
                }
                WeLogger.i(TAG, "增量整理：$wxId 没有新的文字记录")
                return true
            }
            fail(wxId, "读不到微信数据库（数据库服务未就绪），请重启微信后重试")
            return false
        }
        return execute(
            wxId,
            Plan(
                fromYear = null,
                fromSec = fromSec,
                toSec = toSec,
                totalLines = 0,
                slices = slices,
                years = emptyList(),
                allFromSec = fromSec,
                allTotalLines = 0,
                emptyReason = null,
            ),
            incrementalScan = true,
        )
    }

    /**
     * 真正执行的公共段：写状态 → 逐片跑 → 汇总侧写 → 收尾。
     *
     * 分片已经在 [preview] / [runIncremental] 里算好，这里不再统计 —— 「正在统计记录」
     * 那几十秒发生在用户确认之前，点「开始」之后不该再等一遍。
     */
    private fun execute(
        wxId: String,
        plan: Plan,
        incrementalScan: Boolean,
        fromIndex: Int = 0,
    ): Boolean {
        val base = AiProfileStore.readProfile(wxId) ?: run {
            WeLogger.w(TAG, "档案不存在，跳过初始化：$wxId")
            return false
        }
        val mode = if (incrementalScan) "增量" else "全量"
        val slices = plan.slices
        val resumeFrom = fromIndex.coerceIn(0, slices.size)

        AiProfileStore.patchProfile(wxId) {
            it.copy(
                state = AiProfileState.INITIALIZING,
                lastError = null,
                progress = "$mode 准备中",
                // 续跑时从断点写回：中途被杀不能让进度倒退成 0，否则下次没有起点。
                progressDone = resumeFrom,
                progressTotal = slices.size,
                // 记住这次选的起始年：失败后重开弹窗，「从该片继续」必须用同一份分片，
                // 默认回到「全部」会让片号错位。0 与界面的 ALL_YEARS 同义。
                // 增量不改它：增量的分片是「昨天到今天」，与全量的起始年无关。
                planFromYear = if (incrementalScan) it.planFromYear else (plan.fromYear ?: 0),
                // 全量整理从零观察：上一次中断留下的观察没有对应任何产出，
                // 混进这次汇总会让同一现象被算两遍频次。增量不动风格，也就不清。
                styleNotes = if (incrementalScan) it.styleNotes else emptyList(),
            )
        }

        // 早期版本的 schema 没写字段名，模型给的日期读不到，落成了「未知日期」。
        // 这类条目没法排序，也没法和新写入的去重，整理时直接丢掉（一次性清理，不做兼容分支）。
        AiProfileStore.patchProfile(wxId) { profile ->
            val kept = profile.memories.filter { AiDates.normalize(it.occurredAt) != null }
            if (kept.size == profile.memories.size) profile
            else {
                WeLogger.i(TAG, "清理 $wxId 的 ${profile.memories.size - kept.size} 条无日期记忆")
                profile.copy(memories = kept)
            }
        }

        WeLogger.i(
            TAG,
            "开始${mode}整理 $wxId：${slices.size} 片" +
                if (incrementalScan) {
                    "（${AiChatHistory.windowOfRange(plan.fromSec, plan.toSec).label}）"
                } else {
                    ""
                },
        )

        var used = 0
        // 本人可用发言的累计条数：判断「够不够归纳出侧写」。只数不存内容 ——
        // 攒原话会让这里多占几十 MB，而侧写里也不保留整句样本。
        var ownLines = 0
        // 撞到上限被裁掉的条数：模型看不到、日志里也没有的话，用户只会看到「已初始化 ·
        // 2000 条记忆」，分不清「刚好 2000」和「被裁过一批」。
        var trimmedMemories = 0
        var trimmedTags = 0
        slices.forEachIndexed { index, slice ->
            // 续跑：前面已跑完的片直接跳，不发请求、不重复计费。
            if (index < resumeFrom) return@forEachIndexed
            updateProgress(wxId, "$mode · ${slice.label}", index, slices.size)
            // 不传 limit：这一片有多少读多少，切窗只影响分几次请求，不影响内容完整性。
            val data = AiChatHistory.load(wxId, slice.window)
            if (data.lines.size < slice.minLines) {
                WeLogger.d(TAG, "$wxId ${slice.label} 记录过少（${data.lines.size} 条），跳过")
                return@forEachIndexed
            }
            if (data.lines.size > 6_000) {
                // 只在异常密集的片段出现：不截断，但要知道这次请求为什么会慢/贵。
                WeLogger.w(TAG, "$wxId ${slice.label} 共 ${data.lines.size} 条，单次上下文较大")
            }
            // 只数文字：`[图片]`「[表情包]」这种占位符也是 4~5 个字，会被误当成本人发言样本。
            ownLines += data.lines.count { it.fromMe && it.isText && it.text.trim().length in 3..40 }
            val current = AiProfileStore.readProfile(wxId) ?: base
            val transcript = AiChatHistory.transcript(data.lines, withDate = true)
            // 只有全量整理才观察说话方式：增量的片是几天到几个月，写出来的「风格」
            // 是那几天的状态，汇总时会把它们当成 24 片之一，反而拉偏频次。
            val styleWindow = if (incrementalScan) {
                null
            } else {
                StyleWindow(slice.label, slice.window.fromSec, slice.window.toSec)
            }
            val ok = runSlice(
                wxId = wxId,
                system = AiPrompts.initSystem(withStyle = styleWindow != null),
                user = AiPrompts.initYearUser(slice.label, transcript, current),
                // 一片只发一次请求：本片记录已经全给，模型的唯一动作是写档案。
                // 多轮会把整段 transcript 反复重发（每轮都按输入计费），而后续轮次并不需要
                // 再看一遍记录。模型可以在同一次响应里发多个工具调用。
                yearHint = slice.year,
                styleWindow = styleWindow,
                debugLabel = slice.label,
                onTrimmed = { memories, tags ->
                    trimmedMemories += memories
                    trimmedTags += tags
                },
            )
            if (!ok) return false
            used += data.lines.size
        }

        // 汇总放在片循环之后：风格要看「哪些现象反复出现」，单片看不出频次。
        // 续跑不重算：`ownLines` 只统计本次跑的片，阈值判断会失真；侧写是加分项，
        // 中断后先保档案主体，用户可以再点一次「增量整理」之外的完整整理来补。
        if (!incrementalScan && resumeFrom == 0) {
            summarizeStyle(wxId, ownLines)
        }

        val now = System.currentTimeMillis()
        val startText = if (incrementalScan) {
            base.sourceRange.substringBefore(" ~ ").ifBlank { AiPrompts.dateOf(plan.fromSec) }
        } else {
            // 全量整理记的是**这次实际从哪天开始**（用户可能只选了最近三年），
            // 不是全量最早一条 —— 否则档案页会显示一个从没整理过的起点。
            AiPrompts.dateOf(plan.fromSec)
        }
        val finalProfile = AiProfileStore.patchProfile(wxId) {
            it.copy(
                state = AiProfileState.READY,
                initializedAt = now,
                lastError = null,
                progress = "",
                progressDone = slices.size,
                progressTotal = slices.size,
                sourceRange = startText + " ~ " + AiPrompts.dateOf(now / 1000),
                // 变动记录按「整理前后」的净变化算，而不是数工具调用次数：
                // 模型可能对同一条标签反复 upsert，按调用数会写出一个虚高的数字。
                changes = it.changes.recorded(
                    ProfileChange.diffOf(
                        before = base,
                        after = it,
                        source = if (incrementalScan) "增量整理" else "首次整理",
                        at = now,
                    )
                ),
            )
        }
        WeLogger.i(
            TAG,
            "${mode}整理完成：$wxId，$startText 起 / ${slices.size} 片 / $used 条记录，" +
                "${finalProfile?.tags?.size ?: 0} 个标签 / ${finalProfile?.memories?.size ?: 0} 条记忆",
        )
        // 撞到上限只在日志里留一笔，不弹给用户：初始化本身是成功的，只是有几条没进来。
        // 列表页副标题会显示「建议审查」，那里是用户能看见的入口。
        if (trimmedMemories > 0 || trimmedTags > 0) {
            WeLogger.w(
                TAG,
                "${mode}整理撞到上限：$wxId，裁掉 $trimmedMemories 条记忆 / $trimmedTags 个标签" +
                    "（上限 ${AiTools.MAX_MEMORIES} / ${AiTools.MAX_TAGS}）",
            )
        }
        return finalProfile != null
    }

    /**
     * 从 `update_profile` 的返回值里取出被裁掉的条数。
     *
     * 只读两个计数，不反序列化整个返回体：这里在片循环里调用，每片解析一遍 JSON 不划算，
     * 而字段名是 [AiTools] 写死的。
     */
    private fun trimmedOf(output: String): Pair<Int, Int> {
        fun pick(name: String): Int =
            Regex("\"$name\":(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return pick("dropped_by_quota") to pick("dropped_tags")
    }

    /**
     * 跑一片：system + user（这一片的全部原文）→ 模型发起工具调用 → 执行完结束。
     *
     * **工具集按场景固定**：
     * - `get_chat_history` 不给 —— 只有一轮，模型就算调了也拿不到结果，这一片的记录本来就是全文；
     * - `read_memory` 不给 —— 档案摘要里已经列了全部标签与记忆标题，没有需要再读的详情；
     * - 全量整理时 `update_profile` 额外带 `style` 字段（[styleWindow] 非空），增量不带。
     *
     * 工具集写死在函数里而不是做成参数：初始化只该有这两种形态，留开关迟早会被传错。
     */
    private fun runSlice(
        wxId: String,
        system: String,
        user: String,
        /** 当前片对应的年份：模型漏填日期时用它兜底，保证记忆至少按年可排。 */
        yearHint: Int? = null,
        /** 全量整理的片窗口；为 null 表示这是增量片，不观察说话方式。 */
        styleWindow: StyleWindow? = null,
        /** 写进 debug 文件名，便于对照是哪一片。 */
        debugLabel: String = "",
        /** 这一片写入后有多少条被上限裁掉（记忆, 标签）。 */
        onTrimmed: (Int, Int) -> Unit = { _, _ -> },
    ): Boolean {
        val messages = listOf(
            AiConfig.ChatMessage(role = "system", content = system),
            AiConfig.ChatMessage(role = "user", content = user),
        )
        val tools = AiTools.forInit(withStyle = styleWindow != null)
        val dumpPath = AiDebug.dumpPrompt(
            wxId, debugLabel, system, user, tools,
            thinking = AiConfig.isThinkingEnabled,
        )

        val result = AiConfig.chatWithTools(
            messages = messages,
            tools = tools,
            temperature = 0.3,
            // 单片是「按窗口全量」，不抽样也不按条数截断；十几万字的片加上深度思考，
            // 90s 实测不够（曾有一片直接超时判失败）。180s 留出余量。
            readTimeoutMs = 180_000,
            // 思考跟随全局开关。请求里不带 max_tokens，输出长度由服务商与模型决定；
            // 思考与工具调用抢的是同一份输出预算，服务商默认值偏小时 arguments 会被截成
            // 半截 JSON —— 那种片在下文按 finish_reason=length 记一笔 W。
        )
        AiDebug.appendResponse(dumpPath, result)
        if (result.error != null) {
            // 请求失败（鉴权 / 网络）说明整条流水线不可用，没必要继续后面几年。
            fail(wxId, result.error)
            return false
        }
        // 输出预算不足时（服务商默认上限偏小，或思考占掉了大部分），tool_calls 的 arguments
        // 会是半截 JSON。execute 入口已经会拦坏 JSON（回 ok:false），
        // 这里再按 finish_reason 记一笔，便于从日志定位「是哪一片的记录太长」。
        val truncated = result.finishReason == "length"
        if (!result.hasToolCalls) {
            // 模型只回文本、没写任何东西：这一片的内容就丢了，必须留痕。
            WeLogger.w(TAG, "$wxId 这一片没有任何工具调用：${result.text?.take(40).orEmpty()}")
            return true
        }

        // 有工具调用就把结果执行掉。**不再发第二次请求**：这一片的记录已经用完，
        // 模型也不需要看返回值 —— 写入结果只对日志和 debug 有意义。
        var failed = 0
        var styleMissing = false
        result.toolCalls.forEach { call ->
            val output = AiTools.execute(wxId, call, yearHint, styleWindow)
            // 没有第二次请求，返回值没人看：只把「执行失败」捞出来记一笔。
            // 否则模型调了工具、参数却被拒（例如一次写超上限），日志里和成功一模一样。
            if (!AiTools.resultOk(output)) {
                failed++
                WeLogger.w(TAG, "$wxId ${call.name} 未执行：${output.take(100)}")
            }
            // 撞到总量上限被裁掉的条数：模型看不到这个数字，但它决定用户要不要跑一次审查。
            if (call.name == AiTools.UPDATE_PROFILE) {
                val trimmed = trimmedOf(output)
                if (trimmed.first > 0 || trimmed.second > 0) {
                    onTrimmed(trimmed.first, trimmed.second)
                }
            }
            if (output.contains("\"style\":\"missing\"")) styleMissing = true
        }
        if (styleMissing) {
            // 并进 update_profile 之后仍然漏写：这一片没有观察，汇总时那段时间就没有证据。
            // 记出来才能判断是提示词不够硬，还是这一片确实没什么可说。
            WeLogger.w(TAG, "$wxId ${debugLabel.ifBlank { "这一片" }} 没写 style，这一段不会有观察")
        }
        if (truncated) {
            // 被截断时 style 与标签一起丢：它是 update_profile 里的字段，不在独立调用里，
            // 所以这里只报数，不再有「先丢哪个」的说法。
            WeLogger.w(
                TAG,
                "$wxId ${debugLabel.ifBlank { "这一片" }} 输出被截断（finish_reason=length）：" +
                    "执行 ${result.toolCalls.size} 个工具调用，失败 $failed",
            )
        }
        WeLogger.d(TAG, "$wxId 写入 ${result.toolCalls.size} 次工具调用")
        return true
    }

    /**
     * 片内观察 → 一段侧写。一次请求，没有循环。
     *
     * 三种情况直接跳过、不发请求：
     * - 用户手改过 summary：汇总唯一的产出就是 summary，没有别的字段可更新；
     * - 本人发言太少：写出来的只会是放之四海皆准的空话；
     * - 没有观察（所有片都没写 `style`）。
     *
     * 失败**不清 `style`**：一次网络抖动不该让用户丢掉已有的侧写。
     */
    private fun summarizeStyle(wxId: String, ownLines: Int) {
        val profile = AiProfileStore.readProfile(wxId) ?: return
        val notes = profile.styleNotes
        val latest = notes.maxOfOrNull { it.toSec } ?: 0L

        if (profile.style.userEdited) {
            // 只把观察截止日往后推，正文保留用户写的那一份。
            AiProfileStore.patchProfile(wxId) {
                it.copy(
                    style = if (latest > 0) {
                        it.style.copy(basedUntil = AiPrompts.monthOf(latest - 1))
                    } else {
                        it.style
                    },
                    styleNotes = emptyList(),
                )
            }
            WeLogger.i(TAG, "$wxId 侧写由用户手改，跳过汇总（${notes.size} 片观察）")
            return
        }

        if (notes.isEmpty()) {
            // 整片模型都没写 style：没有素材可汇总，什么也不改（旧侧写保留），
            // 也不标「样本不足」—— 那是「聊得少」，和「模型没写」是两回事。
            AiProfileStore.patchProfile(wxId) { it.copy(styleNotes = emptyList()) }
            WeLogger.w(TAG, "$wxId 没有片内观察，跳过侧写汇总")
            return
        }

        if (ownLines < STYLE_MIN_OWN_LINES) {
            AiProfileStore.patchProfile(wxId) {
                it.copy(
                    // 已经有侧写就不降级成「样本不足」：那是上一次整理的结果，仍然有效。
                    style = if (it.style.summary.isBlank()) {
                        it.style.copy(insufficient = true)
                    } else {
                        it.style
                    },
                    styleNotes = emptyList(),
                )
            }
            WeLogger.i(TAG, "$wxId 本人发言 $ownLines 条（${notes.size} 片），样本不足，跳过侧写")
            return
        }

        // 只改文案，不动 progressDone/Total：那对数字是「第几片」，这里的进度是「最后一步」，
        // 写成 N/N 会让界面显示「第 25/24 片」。
        AiProfileStore.patchProfile(wxId) {
            it.copy(progress = "正在归纳你的说话方式")
        }
        val (recent, early) = splitStyleNotes(notes)
        val system = AiPrompts.styleSummarySystem()
        val user = AiPrompts.styleSummaryUser(recent, early)
        val tools = AiTools.forStyle()
        val dumpPath = AiDebug.dumpPrompt(
            wxId, "风格汇总", system, user, tools,
            thinking = AiConfig.isThinkingEnabled,
        )
        val result = AiConfig.chatWithTools(
            messages = listOf(
                AiConfig.ChatMessage(role = "system", content = system),
                AiConfig.ChatMessage(role = "user", content = user),
            ),
            tools = tools,
            temperature = 0.3,
            readTimeoutMs = 180_000,
        )
        AiDebug.appendResponse(dumpPath, result)

        var wrote = false
        if (result.error != null) {
            WeLogger.w(TAG, "$wxId 侧写汇总失败：${result.error}")
        } else {
            if (result.finishReason == "length") {
                WeLogger.w(TAG, "$wxId 侧写汇总输出被截断（finish_reason=length）")
            }
            result.toolCalls.forEach { call ->
                val output = AiTools.execute(wxId, call)
                if (!AiTools.resultOk(output)) {
                    WeLogger.w(TAG, "$wxId ${call.name} 未执行：${output.take(100)}")
                } else if (call.name == AiTools.UPDATE_STYLE) {
                    wrote = true
                }
            }
        }

        // 成败都清空观察：成功已经写进 style；失败说明这批输入没产出，留着下次也会被清。
        // style 本身保留 —— 失败只代表这次没更新。
        val after = AiProfileStore.patchProfile(wxId) { it.copy(styleNotes = emptyList()) }
        if (wrote && after != null) {
            WeLogger.i(
                TAG,
                "风格汇总完成：$wxId，${after.style.summary.length} 字 / " +
                    "${AiPrompts.styleSections(after.style.summary)} 类，" +
                    "截止 ${after.style.basedUntil}（近期 ${recent.size} 片 / 早期 ${early.size} 片）",
            )
        }
    }

    /**
     * 按**时间**分近期 / 早期，不按片数。
     *
     * 片密度不一样（年片 / 月片 / 半月片），同样 8 片可能跨 8 年也可能跨 4 个月，
     * 按片数分组会让「近期」失真。取总跨度的后 1/3 时间算近期。
     *
     * 兜底：片数 < 3、或跨度 < 90 天、或近期不足 3 片时，全部按近期处理 ——
     * 一两个月的记录分不出「一直如此」和「最近这样」。
     */
    private fun splitStyleNotes(notes: List<StyleNote>): Pair<List<StyleNote>, List<StyleNote>> {
        val sorted = notes.sortedBy { it.fromSec }
        if (sorted.size < 3) return sorted to emptyList()
        val from = sorted.first().fromSec
        val to = sorted.maxOf { it.toSec }
        val span = to - from
        if (span < STYLE_MIN_SPAN_SEC) return sorted to emptyList()
        val cut = to - span / STYLE_RECENT_DIVISOR
        val recent = sorted.filter { it.toSec > cut }
        if (recent.size < 3) return sorted to emptyList()
        return recent to sorted.filter { it.toSec <= cut }
    }

    private fun updateProgress(wxId: String, label: String, done: Int, total: Int) {
        AiProfileStore.patchProfile(wxId) {
            it.copy(
                state = AiProfileState.INITIALIZING,
                progress = label,
                progressDone = done,
                progressTotal = total,
            )
        }
    }

    private fun fail(wxId: String, message: String) {
        WeLogger.w(TAG, "初始化失败：$wxId：$message")
        AiProfileStore.patchProfile(wxId) {
            it.copy(state = AiProfileState.FAILED, lastError = message, progress = "")
        }
    }
}
