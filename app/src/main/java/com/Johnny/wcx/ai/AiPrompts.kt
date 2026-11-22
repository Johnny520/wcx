package com.Johnny.wcx.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 提示词的唯一来源。
 *
 * 约定：每个函数返回一段纯文本，**debug 面板直接渲染同样的函数**，
 * 保证「看到的提示词」与「发出去的提示词」是同一份 —— 不另存一份给人看的副本，
 * 否则两边一定会走偏。
 */
object AiPrompts {

    // ── 初始化 ──────────────────────────────────────────────────────────────

    /**
     * @param withStyle 是否在 `update_profile` 里要求 `style` 字段。增量整理不要 ——
     *   几天的状态不是人格，写进来只会污染汇总时的频次。
     */
    fun initSystem(withStyle: Boolean = true): String = buildString {
        appendLine("你是「知友」的好友档案整理助手。读聊天记录，为微信好友（下称「对方」）建立档案。")
        appendLine()
        appendLine("- 标签只描述对方：每条都读成「对方是 ___」，读不通就不写；记录里「我」这一侧的信息")
        appendLine("  一律不写，包括对方转述的关于用户的事（「你最近很忙吧」说的是用户）。")
        appendLine("- 标签只收「一个月后仍然成立」的属性：")
        appendLine("  能写：性格/金钱焦虑 = 常因想赚快钱而焦虑；作息/晚睡 = 经常两三点才睡。")
        appendLine("  不写：某一次发生的事 —— 「11 月 22 日吞了 10 颗」「那天借了 3 万」这类经过写进记忆。")
        appendLine("  例外：固定日期（生日、纪念日）和长期水平（月薪、固定开销）仍然是属性，照常写。")
        appendLine("  意图类：反复提及且是长期打算 → 标签；只提过一次或「最近想买」→ 记忆。")
        appendLine("- 琐碎的日常寒暄不要单独成条，合并成一条「日常闲聊」。")
        appendLine("- `[图片]` `[语音 3″]` `[表情包]` 是没有内容的占位，只用来看上下文；不要单独为它们写事件。")
        appendLine("- 只写记录里能确认的，不猜、不编造。")
        if (withStyle) {
            // 只留「对象相反」这一条：schema 里已经写了「只记现象、不下结论、必须写」，
            // 这里再说一遍只是让模型背两段意思相同的话。
            appendLine("- style 写的是用户本人（「我」这一侧），和标签的对象相反。")
        }
        append("只输出工具调用，不寒暄、不写 markdown。")
    }

    /** 单个分片的一轮：system + 该片记录 + 已有标签（不列事件）。 */
    fun initYearUser(label: String, transcript: String, profile: AiProfile): String {
        // 只给标签：这一段要整理的就是「这段时间的记录」，历史事件标题对它没有用，
        // 列出来只会让每片多背几千 token（逐月分片时摘要能比正文长）。
        // 重复项由「审查档案」单独处理。
        val summary = archiveSummary(profile)
        // 不给「今天」：这一片是固定历史区间，每行记录自带完整日期；再塞一个当前日期
        // 只会让模型把「明年」按今天算，写错事件年份。
        return """
            时间范围：$label
            聊天记录：
            $transcript

            ${tagRule(profile)}
            已有标签：
            $summary

            整理成档案。
        """.trimIndent()
    }

    /**
     * 标签写法里**只有 user 才能给**的那一条。
     *
     * 「同一维度只用一个键、值变了覆盖」在 `update_profile` 的 schema 里，这里不重复。
     * 到了收紧档才提醒「只更新已有键」：这个数字是代码侧的事实，模型不需要提前知道上限。
     */
    private fun tagRule(profile: AiProfile): String {
        val used = profile.tags.size
        return buildString {
            append("新增标签只在出现新的长期属性时用。")
            if (used >= AiProfileLimits.TAG_TIGHT_MAX) append("\n尽量只更新已有键。")
        }
    }

    // ── 审计（管理弹窗里的独立按钮，不在初始化流程里） ────────────────────

    /**
     * 审查拆成两种片：**标签一片、记忆若干片**。
     *
     * 两份 system 各自只带相关的规则，不互相污染：标签片看不到事件，记忆片看不到标签。
     * 档位规则来自 [AiProfileLimits.Stage.rule]，这里只把它拼进去，不写第二份。
     *
     * **只写「怎么判断」**——什么算幻觉、什么算噪音、什么该合并。字段名、条数、理由字数、
     * 关键事件的判定标准都在 tool schema 里，提示词不重复。审查只产出建议、不直接改档案，
     * 这一点由 [AiProfileAudit] 的工具集保证（没有 `update_profile`）。
     *
     * **不写「同维度怎么取值」**：合并后的 value 怎么写（冲突取最新、不冲突并列）是
     * `merge_tags.value` 的字段语义，在这里再写一份会和「合并成一个键」这个动作互相抵消。
     *
     * **`read_memory` 不再参与审查**：切片时每片 150 条，标题和正文一起给，不需要抽检。
     */
    fun auditTagSystem(guide: AiProfileLimits.Guide): String = """
        你是「知友」的档案审查员。标签描述的是「对方这个人」的长期属性。

        - 读成「对方是 ___」读不通的（写的是用户、或对方转述的用户信息），删。
        - value 泛泛（运动 / 数码）或只靠一句寒暄撑着：删；具体、有多条事件能对上：留。
        - value 带日期、金额，或描述某一次发生的事：本质是记忆，删（生日、纪念日除外）。

        按档位执行：${guide.tag.rule}

        拿不准就不提，也不要为了凑数硬删。
    """.trimIndent()

    fun auditTagUser(profile: AiProfile, guide: AiProfileLimits.Guide): String = """
        好友：${profile.nickname}（备注：${profile.remark.ifBlank { "无" }}）

        ${guide.tagLine}：
        ${tagAuditList(profile)}
    """.trimIndent()

    fun auditMemorySystem(guide: AiProfileLimits.Guide): String = """
        你是「知友」的档案审查员。记忆是双方之间发生过的事。

        重要 = 关键事件（★），或含长期有用的信息：工作、住址、家人、健康、钱、关系进展、
        明确的好恶、答应过的事。寒暄、天气、转发、重复的「在吗」「好的」不重要。

        按档位执行：${guide.memory.rule}

        同一天、同一话题的多条并成一条；关键事件默认不要并进普通组，只有「重要也合并」档才允许。
    """.trimIndent()

    /**
     * 一片记忆的正文：**标题 + detail 全给**，这是切片方案的价值所在。
     *
     * 序号是全档序号，`propose_audit` 按同一份列表解析，不会错位。
     * **不写「第几片」「只审这一片」**：每一次请求对模型都是全新对话，它不知道也不关心
     * 还有别的片；提醒一句只会让它怀疑自己的判断范围。
     */
    fun auditMemoryUser(slice: List<Pair<Int, MemoryEntry>>): String {
        if (slice.isEmpty()) return "没有记忆。"
        return buildString {
            slice.forEach { (number, entry) ->
                append("#$number ")
                if (entry.important) append("★ ")
                appendLine("${entry.occurredAt} ${entry.title}")
                if (entry.detail.isNotBlank()) appendLine("  " + entry.detail)
            }
            appendLine()
            append("判断不了的保持原样。")
        }
    }

    // ── 人格侧写（用户本人，不是对方） ──────────────────────────────────────

    /**
     * 汇总：把各片的观察合并成一段侧写。
     *
     * 这里写的是**怎么取舍**。字段、条数、调用方式在 `update_style` 的 schema 里，不重复。
     * 不写死字数上限：字数由场景数量决定 —— 十年好友有十来个高频场景，写到八九百字正常；
     * 只认识半年的人三四百字也够。写背景故事自然就空。
     */
    fun styleSummarySystem(): String = """
        你是「知友」的说话风格分析师。下面是同一个人在不同时间段的说话方式观察。
        把它们合并成一份「这个人平时怎么说话」的侧写 —— 写出来的话要像他本人发的，
        而不是像客服或助手。

        按八节写，节名用【】标出。**有证据才写，没证据的整节省略，不凑字**：
        【整体】对这个人是什么姿态、关系基调（对熟人 / 对不熟的人分别什么样）、在意什么；
          有明显变化时补一句演变。
        【消息形态】一句话长什么样：句长、拆条、标点、表情、缩写、错别字习惯。
        【口头禅】常用的原话、固定说法、玩笑方式。
        【情绪】高兴 / 不爽 / 担心时分别怎么表现 —— 话变多还是变少、标点怎么变、用哪个词替代。
        【压力下】被追问、被逼急时怎么变，之后怎么收场。
        【场景反应】对方求助 / 抱怨 / 分享 / 冷淡 / 邀约时分别怎么做。
        【边界】不聊什么、什么时候收、怎么转移话题。
        【梗与称呼】怎么叫对方、两人之间的固定梗。

        怎么取舍：
        - 只写反复出现的。只在某一段时间出现的，是那段时间的状态，不写进侧写。
        - 具体到能指导一句话：写「单条基本不超过 15 字」，不写「说话简短」；
          写「不爽时句号变多，用『嗯』代替『行』」，不写「情绪内敛」。
        - 不写背景故事与心理学术语（童年、职业、价值观、回避型、高敏感），
          一律写成能改变一句话的行为。
        - 这份侧写只描述用户本人；对方的信息不写。
        - 观察之间互相矛盾时，以【近期】为准。

        怎么组织：
        - 【近期】用来判定现在怎么说话；【早期】只用来判断「是不是一直如此」。
        - 频次只在【近期】内统计，避免「早年爱长句、现在爱短句」被写成两条并列特征。
        - 演变只在【整体】里写一句，不混进其他节。
    """.trimIndent()

    /**
     * 汇总的输入：**代码分组，模型只做合并**。
     *
     * @param recent 落在总跨度后 1/3 的片，用来判定当前风格
     * @param early 更早的片，只用来判断「是不是一直如此」
     */
    fun styleSummaryUser(recent: List<StyleNote>, early: List<StyleNote>): String = buildString {
        appendLine("【近期 · 用于判定现在怎么说话】")
        appendLine(renderNotes(recent))
        if (early.isNotEmpty()) {
            appendLine()
            appendLine("【早期 · 只用于判断是否一直如此】")
            appendLine(renderNotes(early))
        }
    }

    private fun renderNotes(notes: List<StyleNote>): String =
        notes.joinToString("\n\n") { note ->
            buildString {
                append(note.period)
                if (note.summary.isNotBlank()) append("\n这一段：").append(note.summary)
                if (note.style.isNotEmpty()) {
                    append("\n怎么说：\n").append(note.style.joinToString("\n") { "- " + it })
                }
                if (note.behavior.isNotEmpty()) {
                    append("\n什么反应：\n").append(note.behavior.joinToString("\n") { "- " + it })
                }
            }
        }

    /**
     * 侧写里的【整体】一节，取开头一段用于列表行摘要（没有分节标记时取第一行）。
     */
    fun styleHeadline(summary: String): String {
        if (summary.isBlank()) return ""
        val start = summary.indexOf("【整体】")
        val body = if (start >= 0) {
            val from = start + "【整体】".length
            val next = summary.indexOf('【', from)
            summary.substring(from, if (next >= 0) next else summary.length)
        } else {
            // 用户手改后可能没有分节标记：取开头一句，总比不注入强。
            summary.lineSequence().firstOrNull().orEmpty()
        }
        return body.trim().trimEnd('。').take(STYLE_HEADLINE_MAX)
    }

    /** 侧写里有几节：日志用它判断「模型是不是只写了两段就交了」。 */
    fun styleSections(summary: String): Int =
        Regex("""【[^】]{1,8}】""").findAll(summary).count()

    private const val STYLE_HEADLINE_MAX = 120

    // ── 智能建议 ────────────────────────────────────────────────────────────

    /**
     * 建议的 system。
     *
     * **不写固定句式，也不写固定字数**：原来那套「随口接一句 / 说自己的事 / 把话头递回去」
     * 和「30 字以内」是给「没有侧写」的情况兜底的，一旦侧写存在，它们会把任何人都压成
     * 同一种节奏 —— 爱抬杠的人被逼出「说自己的事」，话少的人被逼出 30 字。
     *
     * 现在的分工：
     * - **安全红线**（不承诺、不提钱、不越级）永远保留，这是底线不是风格；
     * - **长度、语气、是否给建议、三条怎么不同**全部交给侧写判断；
     * - 侧写为空（未整理、样本不足）时才退回一段最朴素的兜底，且只给量级不给句式。
     *
     * 另一条约束是「一条消息就是他会说完的整段话」：模型不会像人那样一条条发，
     * 所以本来会分几条说的内容要一次说完，但不能被压成半句话。
     *
     * **标签与记忆不在这里**：它们描述的是「对方」，和这一段讲的「你是谁」是两种视角，
     * 混在一起模型容易把对方的属性读成自己的。它们在 [suggestUser] 里，紧挨着最后一句。
     */
    fun suggestSystem(profile: AiProfile): String {
        val goal = profile.goal
        val stage = goal?.stage?.ifBlank { goal.relation }?.ifBlank { "未知" } ?: "未知"
        val goalText = buildString {
            if (goal?.relation?.isNotBlank() == true) append(goal.relation)
            if (goal?.purpose?.isNotBlank() == true) {
                if (isNotEmpty()) append(" · ")
                append(goal.purpose)
            }
        }.ifBlank { "用户没有填写" }
        val style = profile.style.summary.trim()

        return buildString {
            appendLine("你就是这个人，正在微信上回对方。写出来的是你会真的发出去的那一条，不是最合适的回答。")
            appendLine()
            if (style.isNotEmpty()) {
                appendLine("动笔前，先确定：")
                appendLine("1. 你现在对这个人、这个话题是什么姿态，会在意什么、是什么情绪。")
                appendLine("2. 【场景反应】【情绪】【压力下】里，最接近的那一条是什么。")
                appendLine("3. 【消息形态】【口头禅】决定这句话打成什么样：几个字、什么标点、有没有口头禅。")
                appendLine("侧写里没写到的情形，按「你遇到这种事会怎么反应」推断，而不是按一般人的反应。")
            } else {
                appendLine("按一个普通人发微信来写：短、具体、说人话。")
            }
            appendLine()
            appendLine("一条消息就是你会说的全部。")
            if (style.isNotEmpty()) {
                appendLine("你平时会说三句的，这里连起来说完；只回一个「行」的，就只写「行」。")
                appendLine("不是删短，也不是铺开写成一段 —— 是你把话说完的样子。")
                appendLine("写多长由你决定，没有字数规定。")
                appendLine("对方连发几条说的是同一件事时，回一条就够，不必逐条接；但该接的点不要漏。")
            } else {
                appendLine("能一句说完就不写两句，也不要只蹦半个字；通常 3~30 字，")
                appendLine("对方明确问了需要解释的事才放宽到 50 字左右。")
            }
            appendLine("分点、分段、「首先其次」都不属于聊天。")
            appendLine()
            appendLine("三条都是你真会发的：")
            appendLine("差异来自你可能有的不同反应；只有一种反应时，就在这一种里换程度")
            appendLine("（更敷衍 / 更具体 / 更主动），不为凑数写一条。")
            appendLine()
            appendLine("不能写（永远压过风格）：")
            appendLine("- 承诺做不到的事、提钱、提具体见面地点、发链接、越级（朋友不写恋爱暗示，")
            appendLine("  暧昧不写承诺，恋人不故意冷淡）。")
            appendLine("- 复述或总结对方刚说的话；书面连接词（因此、不过、而且）；")
            appendLine("  「确实」「理解」「希望对你有帮助」。")
            appendLine("- 对方冷淡、回复变短、拒绝过邀约时收敛，不连续追问。")
            if (style.isNotEmpty()) {
                appendLine()
                appendLine("你平时的说话方式：")
                appendLine(style)
            }
            appendLine()
            appendLine("关系尺度：当前阶段 $stage · 聊天目标 $goalText")
            appendLine()
            appendLine("只输出一行 JSON，不要代码块、不要解释：")
            append("{\"read\":\"对方这句话想要什么\",\"replies\":[\"...\",\"...\",\"...\"]}")
        }
    }

    /**
     * 这一次请求的**全部素材**：当天记录、本人原话、对方的档案。
     *
     * 档案放在这里而不是 system，有两个原因：
     * - **人称**：system 讲的是「你是谁」（用户本人的侧写），标签记忆讲的是「对方是谁」。
     *   两段混在同一段文本里，模型很容易把对方的属性读成自己的。
     * - **注意力**：user 是模型最后读到的内容。档案放在记录之后，读到时还热着；
     *   放在 system 开头则被几百字侧写和一堆规则隔开。
     *
     * 顺序按「由近及远」：今天的对话 → 本人原话 → 对方档案。
     * **不再单列「对方最后一句」**：当天记录的最后一段就是它，抄一遍只是多几十到几百 token。
     *
     * @param dayLabel 这段记录是哪一天，已带日期，例如「今天（2026-11-20）」
     *   「昨天到今天（2026-11-19 ~ 2026-11-20）」。跨天时必须在这里说清楚，
     *   否则模型会把 23:59 和 00:01 读成同一天里的顺序错误。
     * @param samples 用户本人最近发过的话。**只在没有侧写时使用**：那段兜底只说了
     *   「短、具体、说人话」，需要几条真实句子锚定口气。有侧写时它是第二套模板 ——
     *   模型会照着样本的句式写，而不是按侧写里的人格写，等于把 500 字侧写降级成 10 条样本。
     */
    fun suggestUser(
        profile: AiProfile,
        transcript: String,
        dayLabel: String = "今天（" + today() + "）",
        samples: String = "",
        /** 是否附了一张图：补一句说明，让模型知道图和记录怎么对应。 */
        hasImage: Boolean = false,
    ): String = buildString {
        appendLine("${dayLabel}的聊天：")
        appendLine(transcript.ifBlank { "（没有聊天记录）" })
        // 有侧写就不给样本：他自己的话在聊天记录里本来就有，单摘出来再叮嘱一遍
        // 「照这个长度写」只会把人格压成样本的句式。
        if (samples.isNotBlank() && profile.style.summary.isBlank()) {
            appendLine()
            appendLine("你最近发过的话（长度、断句照这个来）：")
            appendLine(samples)
        }
        appendLine()
        appendLine("对方的档案（用来理解他的话，不要当话题翻出来）：")
        appendLine("标签：${tagLine(profile)}")
        appendLine(memoryBlock(profile))
        if (hasImage) {
            appendLine()
            append("附带了对方这一轮发的图片，对应记录里这一串 [图片] 中的一张。")
        }
    }

    // ── 档案摘要（喂给模型看，不是给用户看） ────────────────────────────────

    /**
     * **初始化分片**用的已有标签列表，调用方自己加标题。
     *
     * 不列事件是刻意的：这一片要整理的是「这段时间的记录」，历史事件对它没有用
     * （不需要去重、不需要引用旧事件），列出来只会让每片多背几千 token —— 上限提到 2000
     * 条之后更不能列。连「事件 N 条」也不说：那句话只会让模型去想「要不要跟它们去重」，
     * 一个它本来不会想到的任务。重复项由「审查档案」单独处理。
     *
     * 带 `（2026-11）` 是为了让模型判断「这条是 2021 年写的，这次有更新就覆盖」。
     * `updatedAt = 0`（旧档案）不加括号，不假装知道时间。
     *
     * 审查标签用 [tagAuditList]，记忆用 [auditMemoryUser]（标题 + 正文）。
     */
    fun archiveSummary(profile: AiProfile): String =
        if (profile.tags.isEmpty()) "（暂无）"
        else profile.tags.joinToString("\n") { tag ->
            val stamp = monthOf(tag.updatedAt)
            "- " + tag.category + "/" + tag.key + "：" + tag.value +
                if (stamp.isNotEmpty()) "（$stamp）" else ""
        }

    /**
     * 审查标签：带上置信度、更新时间与引用原文。
     *
     * 时间只给月份：模型据此判断「哪条是最新结论」。**不加过期符号** ——
     * 职业、性格、家庭结构这类长期属性不因时间久就失效，标一个符号反而诱导模型删掉它们。
     */
    fun tagAuditList(profile: AiProfile): String =
        if (profile.tags.isEmpty()) "（暂无标签）"
        else profile.tags.joinToString("\n") { tag ->
            val stamp = monthOf(tag.updatedAt)
            val meta = buildList {
                add(String.format(Locale.US, "%.1f", tag.confidence))
                if (stamp.isNotEmpty()) add(stamp)
                add("原话：" + tag.evidence.ifBlank { "无" })
            }.joinToString("；")
            "- " + tag.category + "/" + tag.key + " = " + tag.value + "（$meta）"
        }

    /**
     * 档案在**建议 / 解读 / 自动回复**时实际占用的 token 估算。
     *
     * 口径就是那几条读流水线默认注入的内容：`tagLine`（标签全量）+ 记忆的**默认窗口**
     * （最近半年 ∪ 最新 30 条，见 [AiMemoryView]）+ 侧写全文；不含 detail（只在模型主动
     * `read_memory` 时进上下文），也不含被翻出来的更早记忆（那是一次性的工具结果）。
     * 不能用 `archiveSummary` 估：那是初始化口径，只有标签、没有记忆标题，会严重低估。
     */
    fun profileTokens(profile: AiProfile): Int {
        val style = profile.style.summary
        val text = tagLine(profile) + "\n" + AiMemoryView.render(AiMemoryView.of(profile).recent) +
            if (style.isBlank()) "" else "\n" + style
        return AiTokens.estimate(text)
    }

    private fun tagLine(profile: AiProfile): String =
        if (profile.tags.isEmpty()) "（暂无）"
        else profile.tags.joinToString("、") { it.category + "/" + it.key + "=" + it.value }

    /**
     * 建议 / 解读注入的记忆：**只有默认窗口**（最近半年 ∪ 最新 30 条），更早的只报条数。
     *
     * 全量标题（上限 2000 条约 30k token）在多数对话里用不到，却每次请求都要带；
     * 更早的部分由 `load_earlier_memories` 一次取回。这里必须说清「还有多少没列出来」，
     * 否则模型会以为档案里那些不存在，宁可不用也不去翻。
     *
     * 只说「下面是最新的 N 条」而不写「最近半年」：半年线与 30 条兜底取并集，
     * 有时会带上略早于半年的几条，写死「最近半年」就成了假话。
     *
     * 序号是**全档序号**：翻出来的 `#12` 与这里看到的 `#358` 属于同一套编号，
     * `read_memory(numbers=[12])` 直接可用，不会错位。
     */
    private fun memoryBlock(profile: AiProfile): String {
        if (profile.memories.isEmpty()) return "记忆（★ 为关键事件）：（暂无）"
        val split = AiMemoryView.of(profile)
        val head = if (split.earlier.isEmpty()) {
            "记忆（★ 为关键事件）"
        } else {
            "记忆（★ 为关键事件；列了 ${split.recent.size} 条，" +
                "另有更早的 ${split.earlier.size} 条没有列出）"
        }
        return "$head：" + AiMemoryView.render(split.recent).ifBlank { "（没有可列出的记录）" }
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun dateOf(sec: Long): String =
        if (sec <= 0) "未知" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sec * 1000))

    /** `yyyy-MM`：侧写的观察截止日。只到月，不用假装知道精确到哪天。 */
    fun monthOf(sec: Long): String =
        if (sec <= 0) "" else SimpleDateFormat("yyyy-MM", Locale.US).format(Date(sec * 1000))
}
