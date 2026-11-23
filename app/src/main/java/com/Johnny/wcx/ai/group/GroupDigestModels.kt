package com.Johnny.wcx.ai.group

import kotlinx.serialization.Serializable

/**
 * 群聊总结的数据模型与纯计算。
 *
 * 时间单位有两条线，不要混：
 *  - 落盘的运行时刻（`anchorAt` / `lastSlotEnd` / `lastRunAt` / `createdAt`）都是**毫秒**；
 *  - 摘要的 `fromSec` / `toSec` 是**秒**，直接对应 `WeDatabaseApi.getMessagesBetween` 的参数。
 *
 * 这里只放数据与无副作用的计算（网格、窗口、裁尾），读写盘在 [GroupDigestStore]。
 */

@Serializable
data class GroupIndex(
    val version: Int = CURRENT_VERSION,
    val groups: List<GroupSummary> = emptyList(),
) {
    companion object {
        /**
         * 旧索引没有新字段时靠它触发重建。
         * 群配置本身带默认值，加字段不会让旧文件读不出来，所以只在「派生字段口径变了」时升。
         */
        const val CURRENT_VERSION = 1
    }
}

/**
 * 索引条目：列表页与入口红点只读这一份，不读单群文件。
 *
 * 字段都是**派生值**（来自 [GroupDigestConfig]），丢了可以从单群文件重建。
 */
@Serializable
data class GroupSummary(
    val groupId: String,
    val name: String = "",
    val avatarUrl: String = "",
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** 开启 / 改频率的时刻；下次时间由它算，不存派生值。 */
    val anchorAt: Long = 0,
    val unread: Int = 0,
    val lastRunAt: Long = 0,
    val lastHeadline: String = "",
    /** [GroupDigestStatus] 里的取值；空串表示从未跑过。 */
    val lastStatus: String = "",
    /** 暂停 / 失败时给列表显示的原因，正常时为空。 */
    val lastError: String = "",
    /** 工作群：开了之后可以按 AI 分析任务进度，列表页群名后显示百分比。 */
    val workGroup: Boolean = false,
    /** 进度百分比 0~100；-1 表示还没评估过。 */
    val progressPercent: Int = -1,
)

/**
 * 单群配置 + 摘要历史。
 *
 * **这是唯一权威**：调度、窗口、未读、删除都以它为准，`index.json` 只是缓存。
 */
@Serializable
data class GroupDigestConfig(
    val groupId: String,
    val name: String = "",
    val avatarUrl: String = "",
    val enabled: Boolean = false,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** 开启 / 改频率的时刻（毫秒）。 */
    val anchorAt: Long = 0,
    /** 已覆盖到哪一刻（毫秒）；失败 / busy / 空窗口都不推进。 */
    val lastSlotEnd: Long = 0,
    val lastRunAt: Long = 0,
    val lastStatus: String = "",
    val lastError: String = "",
    /** 连续失败次数，成功一次即清零。 */
    val failures: Int = 0,
    /** 时间正序（老 → 新）；裁尾删前面，列表页倒序显示。 */
    val digests: List<GroupDigest> = emptyList(),
    /** 工作群模式：AI 会梳理群里的任务目标并跟踪进度。 */
    val workGroup: Boolean = false,
    /** 预估完成度 0~100；-1 = 还没评估。 */
    val progressPercent: Int = -1,
    /** 当前的主要目标（AI 从聊天里梳理出来的，最多 5 条）。 */
    val progressGoals: List<String> = emptyList(),
    /** 一句话说明为什么是这个进度。 */
    val progressNote: String = "",
    val progressUpdatedAt: Long = 0,
) {

    val intervalMs: Long
        get() = intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L

    val unread: Int get() = digests.count { !it.read }

    /** 下一次该跑的时刻；只用于 UI 显示「下次 17:00」。 */
    fun nextRunAt(nowMs: Long): Long = nextRunAtOf(anchorAt, intervalMinutes, nowMs)

    /**
     * 是否到了该跑的时刻。
     *
     * 基准取 `max(anchorAt, lastSlotEnd)`：首次开启（`lastSlotEnd = 0`）时从锚点算，
     * 已经跑过的从覆盖点算。时钟回拨时差值为负，自然返回 false。
     */
    fun isDue(nowMs: Long): Boolean {
        if (!enabled || anchorAt <= 0L) return false
        val baseline = maxOf(anchorAt, lastSlotEnd)
        return nowMs - baseline >= intervalMs
    }

    /** 本次窗口起点（毫秒）。见 [windowFromOf]。 */
    fun windowFrom(nowMs: Long): Long = windowFromOf(lastSlotEnd, intervalMs, nowMs)

    /** 本次窗口 `[from, now)`，秒，直接喂给消息查询。 */
    fun windowSec(nowMs: Long): Pair<Long, Long> =
        windowFrom(nowMs) / 1000L to nowMs / 1000L

    /**
     * 裁尾：90 天 / 100 条，取先到者。摘要按时间裁尾（与好友档案按重要度裁尾不同）。
     * 内容没变时返回自身，调用方可以据此跳过写盘。
     */
    fun trimmed(nowMs: Long): GroupDigestConfig {
        val cutoff = nowMs - KEEP_DAYS_MS
        val kept = digests.filter { it.createdAt >= cutoff }.takeLast(MAX_DIGESTS)
        return if (kept.size == digests.size) this else copy(digests = kept)
    }

    /**
     * 追加一条摘要并同步运行态。
     *
     * `lastSlotEnd` 取摘要窗口终点的毫秒值：查询是左闭右开，下一窗从同一秒开始不会重复。
     * 同一 `id`（窗口终点毫秒）重复写入时覆盖，天然幂等。
     */
    fun withDigest(digest: GroupDigest): GroupDigestConfig = copy(
        digests = (digests.filterNot { it.id == digest.id } + digest).sortedBy { it.createdAt },
        lastRunAt = digest.createdAt,
        lastSlotEnd = digest.toSec * 1000L,
        lastStatus = GroupDigestStatus.OK,
        lastError = "",
        failures = 0,
    ).trimmed(digest.createdAt)

    /** 只改状态、不写摘要（空窗口 / busy / 失败）。 */
    fun withStatus(
        status: String,
        nowMs: Long,
        error: String = "",
        failed: Boolean = false,
    ): GroupDigestConfig = copy(
        lastRunAt = if (status == GroupDigestStatus.BUSY) lastRunAt else nowMs,
        lastStatus = status,
        lastError = error,
        failures = if (failed) failures + 1 else if (status == GroupDigestStatus.OK) 0 else failures,
    )

    /** 该群全部标记已读。没有任何未读时返回自身。 */
    fun markRead(): GroupDigestConfig {
        if (digests.none { !it.read }) return this
        return copy(digests = digests.map { if (it.read) it else it.copy(read = true) })
    }

    fun toSummary(): GroupSummary = GroupSummary(
        groupId = groupId,
        name = name,
        avatarUrl = avatarUrl,
        enabled = enabled,
        intervalMinutes = intervalMinutes,
        anchorAt = anchorAt,
        unread = unread,
        lastRunAt = lastRunAt,
        // 派生值，不单独存：单群文件里永远能从最后一条摘要拿到，索引只做列表缓存。
        lastHeadline = digests.lastOrNull()?.headline.orEmpty(),
        lastStatus = lastStatus,
        lastError = lastError,
        workGroup = workGroup,
        progressPercent = progressPercent,
    )
}

@Serializable
data class GroupDigest(
    /** 窗口终点毫秒，幂等键。 */
    val id: String,
    val fromSec: Long,
    val toSec: Long,
    val createdAt: Long,
    val headline: String,
    val points: List<String> = emptyList(),
    val todos: List<String> = emptyList(),
    /** 实际参与生成的消息条数，不是窗口总数。 */
    val messageCount: Int = 0,
    val read: Boolean = false,
    val manual: Boolean = false,
)

/** `lastStatus` 的取值；空串表示从未跑过。 */
object GroupDigestStatus {
    const val OK = "ok"
    const val EMPTY = "empty"
    const val FAILED = "failed"
    const val BUSY = "busy"
    const val PAUSED = "paused"
    const val UNCONFIGURED = "unconfigured"
    const val TRUNCATED = "truncated"
}

// ── 常量 ────────────────────────────────────────────────────────────────────

const val DEFAULT_INTERVAL_MINUTES = 1440
const val MIN_INTERVAL_MINUTES = 60
const val MAX_INTERVAL_MINUTES = 168 * 60

/** 管理页的频率快捷项，单位分钟。 */
val PRESET_INTERVALS = intArrayOf(60, 360, 720, 1440, 2880, 4320)

/** 窗口内文字少于这个数就跳过，不请求模型。 */
const val MIN_TEXT_LINES = 3

const val MAX_DIGESTS = 100
const val KEEP_DAYS = 90L
const val KEEP_DAYS_MS = KEEP_DAYS * 24L * 3600_000L

/** 窗口上限，只防时钟异常；正常补跑可以比这短得多。 */
const val MAX_SPAN_MS = 7L * 24 * 3600_000L

/** 单次运行最多取多少条消息（全类型）。 */
const val MAX_MESSAGES_PER_RUN = 3000

/** 切片：每片最多 800 行 / 20k 字符；超过 8 片只保留最近 8 片。 */
const val MAX_CHUNK_LINES = 800
const val MAX_CHUNK_CHARS = 20_000
const val MAX_CHUNKS = 8

const val HEADLINE_MAX = 30
const val POINT_MAX = 40
const val MAX_POINTS = 6

// ── 纯计算 ──────────────────────────────────────────────────────────────────

/**
 * 下一次该跑的时刻（毫秒）。网格以 `anchorAt` 为原点，**不随执行耗时漂移**：
 * 5:00 开启、2 小时一次，永远是 7:00 / 9:00 / 11:00，不是「上次跑完 + 2 小时」。
 *
 * 返回 `anchorAt` 表示时钟回拨（`now < anchorAt`），由调用方决定是否重锚。
 */
fun nextRunAtOf(anchorAt: Long, intervalMinutes: Int, nowMs: Long): Long {
    if (anchorAt <= 0L) return 0L
    val interval = intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES) * 60_000L
    if (nowMs <= anchorAt) return anchorAt
    return anchorAt + ((nowMs - anchorAt) / interval + 1) * interval
}

/**
 * 本次窗口起点。
 *
 * - 从上次覆盖点开始，**不按槽位截断**：进程死 10 小时就补 10 小时，不是只补最后 2 小时；
 * - 首次运行（`lastSlotEnd = 0`）取 `now - interval`；
 * - 上限 `MAX_SPAN` 只防时钟异常，与频率无关 —— 否则 48 / 72 小时频率永远只总结后一半。
 */
fun windowFromOf(lastSlotEnd: Long, intervalMs: Long, nowMs: Long): Long {
    val start = if (lastSlotEnd > 0L) lastSlotEnd else nowMs - intervalMs
    return maxOf(start, nowMs - MAX_SPAN_MS)
}
