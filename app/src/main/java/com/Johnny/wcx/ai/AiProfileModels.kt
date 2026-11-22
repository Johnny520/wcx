package com.Johnny.wcx.ai

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * AI 好友档案的数据模型。
 *
 * 全部字段都可缺省：模型通过 tool 增量写入，早期版本写下的档案必然缺少后来新增的字段，
 * 反序列化不能因此失败。所以每个字段都给默认值，配合 `ignoreUnknownKeys`
 * 双向兼容（新版本读旧文件、旧版本读新文件都不炸）。
 */
@Serializable
data class AiProfile(
    val wxId: String,
    val nickname: String = "",
    val remark: String = "",
    /** A-Z 分组键：取 `rcontact.pyInitial` 首字母，大写；非 A-Z 与空值统一归 `#`。 */
    val pyInitial: String = "",
    /** 全拼，仅用于组内二级排序（中文昵称按字符串排会乱）。 */
    val quanPin: String = "",
    val avatarUrl: String = "",
    /** 见 [AiProfileState]。 */
    val state: String = AiProfileState.UNINITIALIZED,
    val autoReply: Boolean = false,
    val smartSuggest: Boolean = false,
    val goal: ChatGoal? = null,
    val tags: List<ProfileTag> = emptyList(),
    val memories: List<MemoryEntry> = emptyList(),
    val createdAt: Long = 0,
    val initializedAt: Long = 0,
    val lastError: String? = null,
    /** 初始化覆盖的时间范围，例如 `2019-05-03 ~ 2026-11-18`。 */
    val sourceRange: String = "",
    /**
     * 上次全量整理选的起始年；0 = 全部（与界面的 `ALL_YEARS` 同义）。
     *
     * 只用于「失败后从该片继续」：续跑必须用同一份分片，重开弹窗时默认回到「全部」
     * 会让片号错位。增量整理不写它。
     */
    val planFromYear: Int = 0,
    /** 当前阶段文案，例如 `2026 年` / `合并重复项…`；空串表示未在初始化。 */
    val progress: String = "",
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    /** 「检查重复项」是否正在跑。进程重启后会被 [AiProfileInitializer.recoverInterrupted] 复位。 */
    val auditing: Boolean = false,
    /**
     * 审查提案：模型只提建议，用户确认后才由 [AiProfileAudit.apply] 落盘。
     *
     * 存进档案而不是内存，是因为审查可能跑几分钟，中途切页面、锁屏、进程被杀都要能拿回来。
     */
    val pendingAudit: AiAuditPlan? = null,
    /**
     * 档案变动历史：每次整理 / 审查后记一条计数，最新的在最后。
     *
     * 只记计数不记条目内容 —— 档案里已经是当前状态，这里要回答的是「这段时间它变了什么」，
     * 存第二份副本只会让档案随历史一起膨胀，还要处理条目被删后的悬空引用。
     */
    val changes: List<ProfileChange> = emptyList(),
    /**
     * 用户本人的说话风格侧写（不是对方）。全量整理时逐片取证、最后汇总成一段；
     * 增量整理不动它。见 [ReplyStyle]。
     */
    val style: ReplyStyle = ReplyStyle(),
    /**
     * 片内观察的中间产物：每片一条，汇总完即清空。
     *
     * 存进档案是因为初始化可能跑十几分钟、中途被杀；只放内存的话重跑要从第一片重新观察。
     * **注入时不带它** —— 它是取证记录，不是结论。
     */
    val styleNotes: List<StyleNote> = emptyList(),
) {
    val isReady: Boolean get() = state == AiProfileState.READY

    /**
     * 正在整理或审查：这段时间档案是「只读」的，建议/解读/自动回复一律拒绝，
     * 避免模型读到写了一半的档案，也避免两条流水线互相覆盖。
     */
    val busy: Boolean get() = state == AiProfileState.INITIALIZING || auditing
}

object AiProfileState {
    const val UNINITIALIZED = "uninitialized"
    const val INITIALIZING = "initializing"
    const val READY = "ready"
    const val FAILED = "failed"
}

@Serializable
data class ChatGoal(
    /** 关系：朋友 / 同事 / 同学 / 家人 / 暧昧 / 恋人 / 其他 */
    val relation: String = "",
    /** 用户自由填写的目的，例如「想追她」。 */
    val purpose: String = "",
    /**
     * 模型维护的关系进度：陌生 / 认识 / 朋友 / 暧昧 / 恋人。
     *
     * **当前恒为空，这是刻意的**：`update_profile` 的 schema 里没有这个字段，`GoalEditor` 也只
     * 透传，所以没人写。它预留给「夜间自动增量更新档案」——那时候由模型在整理完当天记录后
     * 判断一次进度并写入，提示词里 `stage.ifBlank { relation }` 自然就会用上新值。
     *
     * 不要因为「只写不读 / 恒为空」把它当死字段删掉。
     */
    val stage: String = "",
) {
    val isEmpty: Boolean get() = relation.isBlank() && purpose.isBlank()
}

@Serializable
data class ProfileTag(
    /** 基础 / 关系 / 兴趣 / 家庭 / 性格 / 自定义 */
    val category: String,
    val key: String,
    val value: String,
    val confidence: Float = 0.6f,
    /** 原文片段，≤40 字，用于回答「这条从哪来的」。 */
    val evidence: String = "",
    /**
     * 最后一次被证据支持的时间（秒）。0 = 旧档案或未知。
     *
     * 只用来判断「同一维度里哪条是最新结论」，不参与任何配额计算。
     * 旧档案没有这个字段，默认 0，不需要迁移；回退旧版本时被丢弃，档案主体不受影响。
     */
    val updatedAt: Long = 0,
) {
    /** upsert 的身份：同分类同键视为同一条。 */
    val id: String get() = "$category\u0000$key"
}

/**
 * 标签引用的格式转换：`"分类/键"` ↔ `category\0key`。
 *
 * `update_profile.replaces`、`propose_audit.merge_tags.keys`、`delete_tags` 三处都用这一份，
 * 避免「解析一处、拼 id 另一处」各写一遍后两边分叉。
 *
 * 比 [ProfileTag.id] 多一步 trim：提案里的分类 / 键是模型抄回来的，可能带空白；档案里的
 * id 是写入时原样的，不能改语义（改了等于全部旧标签换 id）。
 */
object TagRef {

    /** 第一个 `/` 之前是分类，之后全是键。空分类、空键、没有分隔符都返回 null。 */
    fun parse(text: String): Pair<String, String>? {
        val index = text.indexOf('/')
        if (index <= 0 || index == text.lastIndex) return null
        val category = text.substring(0, index).trim()
        val key = text.substring(index + 1).trim()
        if (category.isEmpty() || key.isEmpty()) return null
        return category to key
    }

    fun id(category: String, key: String): String = category.trim() + "\u0000" + key.trim()

    /** 一组引用归一成 id 集合；解析不了的一律跳过。 */
    fun ids(refs: List<String>): Set<String> =
        refs.mapNotNull(::parse).mapTo(HashSet()) { id(it.first, it.second) }
}

@Serializable
data class MemoryEntry(
    /** `sha1(occurredAt + title).take(16)`，用于 upsert 去重。 */
    val id: String,
    /** `yyyy-MM-dd HH:mm` / `yyyy-MM-dd` / `yyyy-MM` / `yyyy`。 */
    val occurredAt: String,
    /** 一级精简标题，≤20 字。 */
    val title: String,
    /** 二级描述，≤200 字；默认不注入提示词，按需用 read_memory 读。 */
    val detail: String,
    val createdAt: Long = 0,
    /**
     * 关键事件：第一次（见面/约会/通话）、争吵与和解、明显情绪起伏、承诺、关系转折、
     * 重大决定、家庭或健康变故、金钱往来。
     *
     * 列表里用微信绿标出来；普通闲聊不标。由模型在初始化/审查时判断，代码不推断。
     */
    val important: Boolean = false,
)

// ── 变动记录 ────────────────────────────────────────────────────────────────

/**
 * 一次整理 / 审查对档案造成的**净变化**。
 *
 * 记的是计数而不是条目本身：删掉的条目已经不存在了，存下来也无法还原上下文；
 * 用户要看的是「哪天、因为什么、动了多少」，这几项足以判断这次整理值不值得。
 */
@Serializable
data class ProfileChange(
    /** 发生时间（毫秒）。 */
    val at: Long = 0,
    /** `首次整理` / `增量整理` / `审查`。 */
    val source: String = "",
    val tagsAdded: Int = 0,
    val tagsUpdated: Int = 0,
    val tagsRemoved: Int = 0,
    /** 合并掉的标签组数（把多个键并成一条）。 */
    val tagsMerged: Int = 0,
    val memoriesAdded: Int = 0,
    /** 合并组数。 */
    val memoriesMerged: Int = 0,
    /** 被并进合并组的条目数。 */
    val memoriesMergedItems: Int = 0,
    val memoriesRemoved: Int = 0,
    val memoriesMarked: Int = 0,
) {
    val isEmpty: Boolean
        get() = tagsAdded == 0 && tagsUpdated == 0 && tagsRemoved == 0 && tagsMerged == 0 &&
            memoriesAdded == 0 && memoriesMerged == 0 &&
            memoriesRemoved == 0 && memoriesMarked == 0

    /** 一行详情：「新增 3 个标签、合并 2 组（4 条）」。 */
    fun detail(): String = buildList {
        if (tagsAdded > 0) add("新增 $tagsAdded 个标签")
        if (tagsUpdated > 0) add("更新 $tagsUpdated 个标签")
        if (tagsRemoved > 0) add("删除 $tagsRemoved 个标签")
        if (tagsMerged > 0) add("合并 $tagsMerged 组标签")
        if (memoriesAdded > 0) add("新增 $memoriesAdded 条记忆")
        if (memoriesMerged > 0) add("合并 $memoriesMerged 组（$memoriesMergedItems 条）")
        if (memoriesRemoved > 0) add("删除 $memoriesRemoved 条记忆")
        if (memoriesMarked > 0) add("标记 $memoriesMarked 条关键")
    }.joinToString("、").ifBlank { "没有变化" }

    companion object {

        /**
         * 整理前后的净变化：标签按 `分类 + 键` 比对，记忆按 `id`（日期 + 标题）比对。
         *
         * 模型把一条记忆改标题会算成「删一条 + 加一条」，而不是「改一条」—— 没有可靠的
         * 「这是同一条」的判据，宁可少推断，也不要给出一个看起来精确、实际猜出来的数字。
         */
        fun diffOf(before: AiProfile, after: AiProfile, source: String, at: Long): ProfileChange {
            val oldTags = before.tags.associateBy { it.id }
            val newTags = after.tags.associateBy { it.id }
            val oldMemories = before.memories.mapTo(HashSet()) { it.id }
            val newMemories = after.memories.mapTo(HashSet()) { it.id }
            return ProfileChange(
                at = at,
                source = source,
                tagsAdded = newTags.keys.count { it !in oldTags },
                tagsUpdated = newTags.keys.count { id ->
                    val old = oldTags[id] ?: return@count false
                    val current = newTags.getValue(id)
                    old.value != current.value || old.confidence != current.confidence
                },
                tagsRemoved = oldTags.keys.count { it !in newTags },
                memoriesAdded = newMemories.count { it !in oldMemories },
                memoriesRemoved = oldMemories.count { it !in newMemories },
            )
        }
    }
}

/** 追加一条变动记录：空记录不写，只留最近 [AiProfileLimits.MAX_CHANGES] 条。 */
fun List<ProfileChange>.recorded(change: ProfileChange): List<ProfileChange> =
    if (change.isEmpty) this
    else (this + change).takeLast(AiProfileLimits.MAX_CHANGES)

// ── 说话风格 ────────────────────────────────────────────────────────────────

/**
 * 用户本人的说话方式侧写。
 *
 * **只有一段文本**：八个小节（整体 / 消息形态 / 口头禅 / 情绪 / 压力下 / 场景反应 / 边界 /
 * 梗与称呼）写在 [summary] 里，用【】分节。不拆成 persona / habits / patterns 三组字段 ——
 * 同一批信息存三份，用户改了一处另一处不会变，注入时还要处理谁压过谁。
 *
 * 它描述的是**用户**，与标签（只描述对方）相反，所以不进 `tags`。
 */
@Serializable
data class ReplyStyle(
    /** 侧写正文，分节文本。常规 450~700 字，场景多可到 900；代码不截断。 */
    val summary: String = "",
    /** 观察截止日 `yyyy-MM`：这段侧写基于哪段时间的记录，档案页显示「截止」。 */
    val basedUntil: String = "",
    /** 用户手改过 [summary]：下次汇总不覆盖它。 */
    val userEdited: Boolean = false,
    /** 上次全量整理本人发言太少，没做汇总。用于档案页显示「样本不足」。 */
    val insufficient: Boolean = false,
)

/**
 * 一片的观察，**只记现象不下人格结论**。
 *
 * 「嘴硬心软」这类判断需要跨时间的一致性证据，单片只能看到一段状态；汇总时按频次取舍，
 * 反复出现的才算人格。
 *
 * [period] / [fromSec] / [toSec] **不在模型的 schema 里**：自然语言日期会被写成
 * 「2026年5月」「5 月」，按 period 覆盖就失效了。这三个由代码在 `update_profile.style`
 * 落盘时注入。
 */
@Serializable
data class StyleNote(
    /** 片标签原样，例如「2026 年 5 月」；同片重跑按它覆盖。 */
    val period: String = "",
    /** 片的时间窗，汇总按时间分「近期 / 早期」用。 */
    val fromSec: Long = 0,
    val toSec: Long = 0,
    /** 这一段整体怎么说话、有哪几种状态。 */
    val summary: String = "",
    /** 语言现象：句长 / 标点 / 表情 / 口头禅 / 情绪表现 / 压力下的变化。 */
    val style: List<String> = emptyList(),
    /** 场景 → 反应：对方抱怨 → 先骂两句再给办法。 */
    val behavior: List<String> = emptyList(),
)

/** `update_profile.style` 落盘时由代码补进 [StyleNote] 的时间窗；模型看不到它。 */
data class StyleWindow(val period: String, val fromSec: Long, val toSec: Long)

// ── 记忆序号 ────────────────────────────────────────────────────────────────

/**
 * 记忆序号：按时间正序，**越早的事件序号越小，从 1 开始**。
 *
 * 序号**不落盘**，每次从当前列表现算。原因是一次写入可能插进一条更早的事件，
 * 落盘的话要么出现重号、要么留下空洞；派生值天然没有这个问题，而且「提示词里看到的 #N」
 * 与「工具解析的 #N」永远来自同一份列表，不会错位。
 *
 * 同一时刻（同一天、甚至同一分钟）的条目按 [MemoryEntry.id] 兜底排序，
 * 保证同一份档案每次算出的序号完全一致 —— 否则模型这轮读 #12、下轮 #12 就换了人。
 */
object AiMemoryIndex {

    /** 时间正序（最早的在前）。 */
    fun sorted(memories: List<MemoryEntry>): List<MemoryEntry> {
        if (memories.size < 2) return memories
        // 先算键再排：比较器每次比较会对两侧各求一次值，直接写在比较器里
        // 等于每条记忆被算 O(n·log n) 次。
        return memories
            .map { AiDates.sortKey(it.occurredAt) to it }
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }
    }

    /** 序号 → 条目。越界的序号不在表里，调用方据此回 missing。 */
    fun byNumber(memories: List<MemoryEntry>): Map<Int, MemoryEntry> =
        numbered(memories).toMap()

    /** 序号 + 条目（时间正序）。需要同时拿「第几条」和「条目本身」时用它，只排一次序。 */
    fun numbered(memories: List<MemoryEntry>): List<Pair<Int, MemoryEntry>> =
        sorted(memories).mapIndexed { index, entry -> (index + 1) to entry }
}

// ── 总量上限 ────────────────────────────────────────────────────────────────

/**
 * 标签 / 记忆的总量裁剪与审查档位，**唯一实现**。
 *
 * 原来 `update_profile` 与 `AiProfileAudit.apply` 各写一份「标签按 confidence 留 100、
 * 记忆按日期留 500」，两处一分叉就会出现「整理时保住了关键事件、审查时又把它丢掉」。
 *
 * 记忆的排序键是 **important 优先，其次日期倒序**：关键事件（第一次见面、争吵与和解）
 * 恰恰是最旧的，只按日期留会让它们最先被新事件挤出去。
 *
 * **裁尾与压缩是两件独立的事**：裁尾兜底防止无限增长（只改上限），审查负责把内容压成
 * 该有的密度（[reviewGuide]）。档位只看当前条数，不看条目年龄。
 */
object AiProfileLimits {

    /** 变动历史保留条数。一次整理一条，60 条够看清半年。 */
    const val MAX_CHANGES = 60

    // ── 审查档位 ────────────────────────────────────────────────────────────

    /**
     * 一个档位：名字 + 做法。记忆档没有目标条数，标签档有（200 是硬上限，不压就写不进新属性）。
     *
     * [rule] 是提示词里那一句的唯一副本：审查时直接拼进 system，不在提示词里写第二份。
     */
    data class Stage(val name: String, val rule: String, val target: Int = 0)

    data class Guide(
        val memory: Stage,
        val tag: Stage,
        val tagCount: Int,
    ) {
        /** 「标签 142 个 · 收紧 · 目标 120 个以内」；目标不小于现有条数时不显示。 */
        val tagLine: String
            get() = "标签 $tagCount 个 · ${tag.name}" +
                if (tag.target in 1 until tagCount) " · 目标 ${tag.target} 个以内" else ""
    }

    private const val MEMORY_NORMAL_MAX = 800
    private const val MEMORY_IMPORTANT_MAX = 1200
    private const val TAG_NORMAL_MAX = 100
    /** 标签进入「收紧」档的条数；初始化提示词到了这个数才提醒「尽量只更新已有键」。 */
    const val TAG_TIGHT_MAX = 160
    private const val TAG_TIGHT_TARGET = 120
    private const val TAG_MERGE_TARGET = 140

    private val MEMORY_NORMAL = Stage(
        name = "正常",
        rule = "不重要的闲聊合并成一条；重要的保持原样，经过照留。",
    )
    private val MEMORY_IMPORTANT_ONLY = Stage(
        name = "只留重要",
        rule = "关键事件和重要条目保留，其余整条放弃。",
    )
    private val MEMORY_MERGE_ALL = Stage(
        name = "重要也合并",
        rule = "不重要的整条放弃；同类重要经历合并，每组 3~5 条，" +
            "detail 写清时间、地点、做了什么、结果、承诺。",
    )
    private val TAG_NORMAL = Stage(
        name = "正常",
        rule = "只合并同一维度被拆出的近义键。",
    )
    private val TAG_TIGHT = Stage(
        name = "收紧",
        rule = "同分类内相近维度也合并；无证据支撑的泛泛值删掉。",
        target = TAG_TIGHT_TARGET,
    )
    private val TAG_MERGE = Stage(
        name = "归并",
        rule = "每个维度只留一条，同分类下键名相近的并入最新那条。",
        target = TAG_MERGE_TARGET,
    )

    /**
     * 审查档位：**只看条数**。审查提示词、档案页、日志都从这里取，不各算一份。
     *
     * 超过上限时按最后一档处理：正常到不了（写满就被裁到上限），留作防御。
     */
    fun reviewGuide(profile: AiProfile): Guide {
        val memories = profile.memories.size
        val memory = when {
            memories <= MEMORY_NORMAL_MAX -> MEMORY_NORMAL
            memories <= MEMORY_IMPORTANT_MAX -> MEMORY_IMPORTANT_ONLY
            else -> MEMORY_MERGE_ALL
        }
        val tags = profile.tags.size
        val tag = when {
            tags <= TAG_NORMAL_MAX -> TAG_NORMAL
            tags <= TAG_TIGHT_MAX -> TAG_TIGHT.copy(target = minOf(tags, TAG_TIGHT_TARGET))
            else -> TAG_MERGE.copy(target = minOf(tags, TAG_MERGE_TARGET))
        }
        return Guide(memory = memory, tag = tag, tagCount = tags)
    }

    fun tags(tags: Collection<ProfileTag>): List<ProfileTag> =
        if (tags.size <= AiTools.MAX_TAGS) tags.toList()
        else tags.sortedByDescending { it.confidence }.take(AiTools.MAX_TAGS)

    /**
     * 关键事件在前，同档按日期倒序（最近的关系状态更有参考价值）。
     *
     * 日期键先算一次再排，理由同 [AiMemoryIndex.sorted]：比较器里算键会重复 O(n·log n) 次。
     */
    fun memories(memories: Collection<MemoryEntry>): List<MemoryEntry> {
        if (memories.size < 2) return memories.toList()
        val sorted = memories
            .map { Ranked(it, AiDates.sortKey(it.occurredAt)) }
            .sortedWith(compareByDescending<Ranked> { it.important }.thenByDescending { it.sortKey })
            .map { it.entry }
        return if (sorted.size <= AiTools.MAX_MEMORIES) sorted else sorted.take(AiTools.MAX_MEMORIES)
    }

    /** 排序中间体：`sortKey` 只在构造时算一次。 */
    private class Ranked(val entry: MemoryEntry, val sortKey: String) {
        val important: Boolean get() = entry.important
    }
}

// ── 只读判定 ────────────────────────────────────────────────────────────────

/**
 * 「档案正在被整理或审查」的**唯一判定**，读路径统一走这里。
 *
 * 判定散在三处（建议引擎、自动回复的触发与发送前复查）时，新增一个读入口只要漏写一个
 * 条件，就会在整理期间读到写了一半的档案，或者拿着旧标签生成回复。
 *
 * 写流水线自己的 `start` 守卫不复用这个函数：它们刚把自己加进 `running` 集合，
 * 用这里会把自己也算成「忙」，永远启动不了。
 */
object AiRunState {

    fun isBusy(wxId: String, profile: AiProfile? = null): Boolean {
        if (AiProfileInitializer.isRunning(wxId) || AiProfileAudit.isRunning(wxId)) return true
        val current = profile ?: AiProfileStore.readProfile(wxId) ?: return false
        return current.busy
    }

    /**
     * 是否有任何好友正在初始化 / 审查。
     *
     * 后台任务（群总结）跑之前要看这个：它们和好友档案抢的是同一条网络与同一个服务商配额。
     * **不能用 [isBusy]** —— 那是按 wxId 查的，群 id 永远查不到。
     */
    fun hasLongTask(): Boolean =
        AiProfileInitializer.isAnyRunning() || AiProfileAudit.isAnyRunning()
}

// ── 审查提案 ────────────────────────────────────────────────────────────────

/**
 * 一次审查的产出：**只描述要改什么，不直接改档案**。
 *
 * 为什么拆成提案：模型判断「这条标签是幻觉」「这十条都是闲聊」并不可靠，
 * 直接删掉就无法恢复。交给用户勾选，成本只有一次确认，收益是永远不会静默丢数据。
 */
@Serializable
data class AiAuditPlan(
    val createdAt: Long = 0,
    /** 建议合并的标签组。字段顺序与 `apply` 的执行顺序一致：先并标签，再删。 */
    val mergeTags: List<AuditTagGroup> = emptyList(),
    val deleteTags: List<AuditTagRef> = emptyList(),
    val deleteMemories: List<AuditMemoryRef> = emptyList(),
    val mergeGroups: List<AuditMergeGroup> = emptyList(),
    val markImportant: List<AuditMemoryRef> = emptyList(),
    /** 模型的一句话总结，显示在提案页顶部。 */
    val note: String = "",
) {
    /** 涉及的总条目数（合计算被合并的条数）。 */
    val total: Int
        get() = mergeTags.sumOf { it.keys.size } + deleteTags.size + deleteMemories.size +
            mergeGroups.sumOf { it.items.size } + markImportant.size

    val isEmpty: Boolean
        get() = mergeTags.isEmpty() && deleteTags.isEmpty() && deleteMemories.isEmpty() &&
            mergeGroups.isEmpty() && markImportant.isEmpty()
}

/**
 * 把同维度的多个标签键并成一条。
 *
 * [value] 的写法在 schema 里写死了两条：冲突的（换工作、搬家）只留最新结论，
 * 不冲突的（喜欢的游戏）全部并列 —— 否则模型会把「保安」和「售后」拼成一条。
 */
@Serializable
data class AuditTagGroup(
    val category: String = "",
    val key: String = "",
    val value: String = "",
    /** 被合并掉的原键，格式「分类/键」。目标键本身也在档案里时，`apply` 会一并算进被合并的一方。 */
    val keys: List<String> = emptyList(),
    val reason: String = "",
)

@Serializable
data class AuditTagRef(
    val category: String = "",
    val key: String = "",
    /** 为什么该删，≤30 字，直接显示给用户。 */
    val reason: String = "",
)

@Serializable
data class AuditMemoryRef(
    val occurredAt: String = "",
    val title: String = "",
    val reason: String = "",
)

/**
 * 把多条不重要的事件并成一条。
 *
 * [detail] 可缺省：模型写就用它，不写就回退成各条标题拼接 —— 后者保证合并结果忠实于原条目，
 * 前者是「重要也合并」档能留下具体经过的唯一途径（20 条标题拼起来只剩前 10 条的标题）。
 */
@Serializable
data class AuditMergeGroup(
    val occurredAt: String = "",
    val title: String = "",
    /** 合并后的正文；空串表示用各条标题拼接。 */
    val detail: String = "",
    val items: List<AuditMemoryRef> = emptyList(),
    val reason: String = "",
)

/**
 * `index.json` 的条目：AI 页列表只读这个，不解析完整档案。
 *
 * 字段刻意与 [AiProfile] 的展示需求一致，这样列表渲染不需要再读 `profiles/&lt;wxId&gt;.json`
 * —— 300 个好友时那是 300 次读盘。
 */
@Serializable
data class AiProfileSummary(
    val wxId: String,
    val nickname: String = "",
    val remark: String = "",
    val pyInitial: String = "",
    /** 组内二级排序键，取 `quanPin`，空值回落 nickname 小写。 */
    val sortKey: String = "",
    val avatarUrl: String = "",
    val state: String = AiProfileState.UNINITIALIZED,
    /**
     * 该好友是否开着自动回复。
     *
     * 复制进索引是为了让消息落库时的过滤**不读完整档案**：全局 hook 每条消息都会进来，
     * 而绝大多数会话没有开自动回复，读一份 1MB 的档案只为了看一个布尔值太贵。
     */
    val autoReply: Boolean = false,
    /** 审查中标记：列表副标题要显示「正在审查档案…」，而 state 此时仍是 ready。 */
    val auditing: Boolean = false,
    /** 初始化进度文案，与 [AiProfile.progress] 同义；列表页直接显示，不必再读完整档案。 */
    val progress: String = "",
    val tagCount: Int = 0,
    val memoryCount: Int = 0,
    val lastError: String? = null,
) {
    val subtitle: String
        get() = when {
            // 审查期间的 progress 是「审查 3/15」；初始化态才读它会是同一字段的另一种写法。
            auditing -> progress.ifBlank { "正在审查档案…" }
            state == AiProfileState.INITIALIZING -> progress.ifBlank { "正在初始化…" }
            state == AiProfileState.FAILED -> "初始化失败：" + (lastError ?: "未知原因")
            state == AiProfileState.READY ->
                "已初始化 · $memoryCount 条记忆" + if (nearCapacity) " · 建议审查" else ""
            else -> "等待初始化"
        }

    /**
     * 接近容量上限：下一次整理会开始裁尾，提示用户跑一次审查。
     *
     * 不自动接一次审查 —— 初始化已经跑了十几分钟，再自动接七分钟没有心理准备。
     */
    val nearCapacity: Boolean get() = memoryCount >= NEAR_MEMORIES || tagCount >= NEAR_TAGS

    companion object {
        const val NEAR_MEMORIES = 1900
        const val NEAR_TAGS = 190
    }
}

/** `index.json` 的整体结构：带版本号，字段变化时可以按版本迁移。 */
@Serializable
data class AiProfileIndex(
    val version: Int = CURRENT_VERSION,
    val profiles: List<AiProfileSummary> = emptyList(),
) {
    companion object {
        /**
         * 2 起 [AiProfileSummary] 带 `autoReply`。
         *
         * 旧索引读出来时这个字段全是默认值 false，**不能用来判断开关** ——
         * [AiProfileStore.loadIndex] 见到旧版本会重建一次，把真实值补进去。
         */
        const val CURRENT_VERSION = 2
    }
}

/**
 * `friends.json` 的条目：微信通讯录快照。
 *
 * 单独存一份而不是每次进 AI 页现查数据库，原因有两个：
 * 一是添加好友页要按拼音排序、还要标「已添加」，现查要 join 三张表；
 * 二是好友快照可以和档案摘要一起做 diff，判断谁把好友删了。
 */
@Serializable
data class AiFriend(
    val wxId: String,
    val nickname: String = "",
    val remark: String = "",
    val pyInitial: String = "",
    val quanPin: String = "",
    val avatarUrl: String = "",
) {
    /** 通讯录里的显示名：有备注用备注，否则用昵称。 */
    val displayName: String get() = remark.ifBlank { nickname }

    /** A-Z 分组键：非字母一律归 `#`，与微信通讯录一致。 */
    val groupKey: String get() = AiLetterIndex.groupOf(pyInitial.ifBlank { quanPin })

    val sortKey: String get() = quanPin.ifBlank { nickname.lowercase() }
}

@Serializable
data class AiFriendSnapshot(
    val version: Int = 1,
    val exportedAt: Long = 0,
    val friends: List<AiFriend> = emptyList(),
)

/** A-Z 分组与索引条的唯一实现，列表页与好友页共用。 */
object AiLetterIndex {

    const val OTHER = "#"

    val letters: List<String> = ('A'..'Z').map { it.toString() } + OTHER

    /** 取首字母并归一到 A-Z / `#`。传入的可以是 `pyInitial`，也可以是全拼。 */
    fun groupOf(raw: String): String {
        val first = raw.trim().firstOrNull()?.uppercaseChar() ?: return OTHER
        return if (first in 'A'..'Z') first.toString() else OTHER
    }
}

/**
 * 记忆日期的唯一解析与排序口径。
 *
 * 模型给的写法五花八门（`2024-05-03 14:30` / `2024-5-3` / `2024/5/3` / `2024年5月3日` / `2024-05`），
 * 直接拿字符串排序会乱（`2024-5-3` 排在 `2024-11-01` 后面），「未知日期」也会跑到最前面。
 * 所以：入库先归一到 `yyyy-MM-dd HH:mm` / `yyyy-MM-dd` / `yyyy-MM` / `yyyy`，
 * 排序统一走 [sortKey]。
 *
 * **时间要精确到分钟**：聊天记录每行都带 `HH:mm`，同一件事常常一天里聊了好几次，
 * 只有日期时排序会把它们挤在一起，也分不清先后。
 */
object AiDates {

    const val UNKNOWN = "未知日期"

    private val DATE_TIME =
        Regex("""(\d{4})\D{1,3}(\d{1,2})\D{1,3}(\d{1,2})\D{1,3}(\d{1,2}):(\d{2})""")
    private val FULL = Regex("""(\d{4})\D{1,3}(\d{1,2})\D{1,3}(\d{1,2})""")
    private val MONTH = Regex("""(\d{4})\D{1,3}(\d{1,2})""")
    private val YEAR = Regex("""(\d{4})""")

    /** 归一成 `yyyy-MM-dd HH:mm` / `yyyy-MM-dd` / `yyyy-MM` / `yyyy`；识别不出返回 null。 */
    fun normalize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (text.contains("未知") || text.contains("不详") || text.equals("null", true)) return null

        DATE_TIME.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val day = match.groupValues[3].toIntOrNull() ?: return@let
            val hour = match.groupValues[4].toIntOrNull() ?: return@let
            val minute = match.groupValues[5].toIntOrNull() ?: return@let
            if (year in 1990..2100 && month in 1..12 && day in 1..31 &&
                hour in 0..23 && minute in 0..59
            ) {
                return String.format(
                    Locale.US, "%04d-%02d-%02d %02d:%02d", year, month, day, hour, minute
                )
            }
        }
        FULL.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val day = match.groupValues[3].toIntOrNull() ?: return@let
            if (year in 1990..2100 && month in 1..12 && day in 1..31) {
                return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
            }
        }
        MONTH.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            if (year in 1990..2100 && month in 1..12) {
                return String.format(Locale.US, "%04d-%02d", year, month)
            }
        }
        YEAR.find(text)?.let { match ->
            val year = match.groupValues[1].toIntOrNull() ?: return@let
            if (year in 1990..2100) return String.format(Locale.US, "%04d", year)
        }
        return null
    }

    /**
     * 排序键：统一补到 `yyyy-MM-dd HH:mm`，未知一律 `0000-00-00 00:00`（倒序时落到底部）。
     * 只精确到日的补 `00:00`，只到月的补 `-00 00:00` —— 这样跨精度比较也不会乱。
     */
    fun sortKey(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ZERO_KEY
        // 入库时已经归一成这四种定宽写法（见 [normalize]），直接补零即可。
        // 排序时比较器会对两侧各求一次键，500 条记忆就是上万次调用，
        // 每次再跑四轮正则纯属浪费 —— 这里先按形状判一次，命中就不进正则。
        val normalized = if (isNormalized(text)) text else normalize(text) ?: return ZERO_KEY
        return when (normalized.length) {
            4 -> normalized + "-00-00 00:00"
            7 -> normalized + "-00 00:00"
            10 -> normalized + " 00:00"
            else -> normalized
        }
    }

    /** [sortKey] 的落地值：未知日期倒序时排到最后。 */
    const val ZERO_KEY = "0000-00-00 00:00"

    /** `yyyy` / `yyyy-MM` / `yyyy-MM-dd` / `yyyy-MM-dd HH:mm` 的形状检查，不跑正则。 */
    private fun isNormalized(text: String): Boolean {
        val length = text.length
        if (length != 4 && length != 7 && length != 10 && length != 16) return false
        for (index in 0 until length) {
            val char = text[index]
            val ok = when (index) {
                4, 7 -> char == '-'
                10 -> char == ' '
                13 -> char == ':'
                else -> char in '0'..'9'
            }
            if (!ok) return false
        }
        return true
    }

    /** 展示用：`2024-05-03 14:30` 原样；`2024-05` → 「2024 年 5 月」；`2024` → 「2024 年」。 */
    fun display(raw: String?): String {
        val normalized = normalize(raw) ?: return raw?.takeIf { it.isNotBlank() } ?: UNKNOWN
        return when (normalized.length) {
            4 -> normalized + " 年"
            7 -> normalized.substring(0, 4) + " 年 " + normalized.substring(5, 7).trimStart('0') + " 月"
            else -> normalized
        }
    }

    /**
     * 归一后的日期换算成本地时区的秒级时间戳；只到月/年的取该段时间的起点。
     *
     * 只用于「增量整理从哪里开始」这类边界计算，不参与排序（排序仍走 [sortKey]）。
     */
    fun startSec(raw: String?): Long? {
        val normalized = normalize(raw) ?: return null
        val calendar = java.util.Calendar.getInstance()
        calendar.clear()
        val year = normalized.substring(0, 4).toIntOrNull() ?: return null
        val month = if (normalized.length >= 7) normalized.substring(5, 7).toIntOrNull() ?: 1 else 1
        val day = if (normalized.length >= 10) normalized.substring(8, 10).toIntOrNull() ?: 1 else 1
        val hour = if (normalized.length >= 16) normalized.substring(11, 13).toIntOrNull() ?: 0 else 0
        val minute = if (normalized.length >= 16) normalized.substring(14, 16).toIntOrNull() ?: 0 else 0
        calendar.set(year, month - 1, day, hour, minute, 0)
        return calendar.timeInMillis / 1000
    }
}

/**
 * token 估算：给用户看的用量指示，不追求和具体分词器完全一致。
 *
 * 口径：ASCII 约 4 字符 1 token，CJK 约 1 字符 1 token。
 * 中文模型（Qwen / DeepSeek / GLM）实测普遍在 1~1.5 字符/token，按 1:1 估偏保守，
 * 用来判断「档案有没有把上下文吃掉大半」足够了。
 */
object AiTokens {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var wide = 0
        text.forEach { char -> if (char.code < 128) ascii++ else wide++ }
        return (ascii + 3) / 4 + wide
    }

    /** `820` / `3.4k`，避免在窄行里放四位数。 */
    fun format(tokens: Int): String = when {
        tokens < 1_000 -> tokens.toString()
        else -> String.format(Locale.US, "%.1fk", tokens / 1000.0)
    }
}
