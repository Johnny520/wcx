package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * 模型可调用的工具：取聊天记录、一次性写入标签与记忆（全量整理附带用户说话方式）、汇总侧写。
 *
 * 为什么标签和记忆合成一个工具（`update_profile`）：拆成两个时模型要连发两次调用，
 * 每轮都多一次往返、多一份 token，而它们本来就是「看完这一段记录 → 记下新发现」的同一件事。
 * 合成之后，一段记录对应一次调用，标签与记忆天然出自同一次阅读。
 *
 * 两条设计约束：
 *
 * 1. **目标好友由代码传入（`wxId`），不放进 schema。** 让模型指定「操作谁」等于给它一个
 *    写错对象的口子 —— 一次幻觉就会把 A 的标签写到 B 上。
 * 2. **返回值是给模型看的 JSON**：成功带计数，失败带可纠正的原因，模型能自己修参数。
 */
object AiTools {

    private const val TAG = "AiTools"

    const val GET_CHAT_HISTORY = "get_chat_history"
    const val UPDATE_PROFILE = "update_profile"
    const val READ_MEMORY = "read_memory"
    const val PROPOSE_AUDIT = "propose_audit"

    /**
     * 建议 / 解读专用：把默认窗口（最近半年 ∪ 最新 30 条）之外的更早记忆标题一次取回。
     *
     * 单独一个工具而不是给 `read_memory` 加参数：那个工具的语义是「我知道是哪条，给我详情」，
     * 翻页是「我不知道有什么，给我看看」。两种返回形状塞进一个工具，description 要为每种
     * 参数组合解释一遍，省下的工具位又还回去了；而审查自己就有全量记忆，根本用不到翻页。
     */
    const val LOAD_EARLIER_MEMORIES = "load_earlier_memories"

    /** 汇总：把片内观察合并成一段侧写。只有它一个工具。 */
    const val UPDATE_STYLE = "update_style"

    /**
     * 审查提案的**单片上限**：审查按每片 150 条切，这几个数字都按片算。
     *
     * 另有 `MAX_TOTAL_*` 按整次审查算（14 片累计），防止一次审查把档案删空。
     */
    private const val MAX_DELETE_TAGS = 100
    private const val MAX_DELETE_MEMORIES = 150
    private const val MAX_MERGE_GROUPS = 12
    private const val MAX_MERGE_ITEMS = 20
    private const val MAX_MARK_IMPORTANT = 40
    private const val MAX_MERGE_TAGS = 40

    /** 合并标签的值上限。现有 value 均值 36.8 字，截到 20 会切掉一半内容。 */
    const val VALUE_MAX = 40

    /** 审查切片：每片多少条记忆。一片约 36k 输入，14 片约 7 分钟。 */
    const val AUDIT_SLICE_MAX = 150

    /** 一次审查累计的提案上限，按「2000 条压到 500 要删 1500」估算。 */
    private const val MAX_TOTAL_DELETE_MEMORIES = 1_500
    private const val MAX_TOTAL_MERGE_GROUPS = 200

    /** 建议理由的字数：它要显示在提案页的每条下面，太长读不完。 */
    private const val REASON_MAX = 30

    /**
     * `read_memory` 单次最多读多少条详情。
     *
     * 100 条 × detail 200 字 ≈ 2 万 token，审计「一次核对完再合并」够用，也不至于把上下文灌满；
     * 原来的 20 条会让审计分 5 次读，而审计只有 4 轮，读不完就先被轮数截断。
     */
    private const val MAX_READ_ITEMS = 100

    /**
     * 字段字数上限。**只在 schema 的字段说明里写一次**，代码按同一组常量截断，
     * 提示词里不再重复 —— 两边各写一遍一定会走偏（evidence 曾经是 40 / 60 两个值）。
     */
    const val TITLE_MAX = 20
    const val DETAIL_MAX = 200
    const val EVIDENCE_MAX = 40

    /**
     * 片内观察的字数上限。**这是护栏不是目标**：提示词里让模型按片长写
     * （500 条以下只写 3~4 条也行），这里只挡住明显跑飞的一条。
     */
    const val STYLE_SUMMARY_MAX = 240
    const val STYLE_ITEM_MAX = 40
    const val BEHAVIOR_ITEM_MAX = 50

    /** 单片最多收多少条现象 / 场景。提示词要 4~7 条，给到 8 是余量。 */
    private const val STYLE_ITEMS_MAX = 8

    /**
     * 「关键事件」的判定标准。
     *
     * 也只写在工具层：`memories[].important`（整理时标）与 `propose_audit.mark_important`
     * （审查补标）共用这一份，提示词里不再重复。schema 是字段语义的唯一来源。
     */
    const val IMPORTANT_CRITERIA =
        "第一次（见面/约会/通话）、争吵与和解、明显情绪起伏、承诺、关系转折、重大决定、" +
            "家庭或健康变故、金钱往来"

    /**
     * 记忆总量上限。
     *
     * 2000 条按「标题 + 日期一条约 15 token」算，全量标题约 30k —— 这已经超过建议场景愿意
     * 每轮背的量，所以注入侧还有 [AiMemoryView.RECENT_MAX] 兜住默认窗口，不是靠这个上限。
     * 这里只负责「档案不会无限增长」，压缩由审查负责。
     */
    const val MAX_MEMORIES = 2_000

    /** 关键词搜索默认返回多少条；全量搜索一次给太多会占满上下文。 */
    private const val SEARCH_LIMIT = 200

    /** 关键词搜索结果上限：再多了模型也读不完，应加时间范围。 */
    private const val MAX_SEARCH_LIMIT = 500

    /** 标签总量上限。超出时按 confidence 保留最可信的；压缩由审查的「归并」档负责。 */
    const val MAX_TAGS = 200

    private val json = Json { ignoreUnknownKeys = true }

    // ── 场景工具集 ──────────────────────────────────────────────────────────

    /**
     * 每个场景的工具集**只在这里定义一次**。
     *
     * 运行时的 `runSlice` / `AiProfileAudit.run` / `AiSuggestEngine.generate` / 风格汇总
     * 和 debug 预览页都调这些函数 —— 改一处两边同时变，不会再出现「预览显示两个工具、实际只发一个」。
     * 新增场景就加一个函数，同时把它加进预览页。
     */

    /**
     * 初始化：`update_profile`；全量整理再带上 `style` 字段。
     *
     * - 不给 `get_chat_history`：单片只发一次请求，调了也拿不到结果；这一片的记录本来就是全文；
     * - 不给 `read_memory`：档案摘要已经列了全部标签与记忆标题。
     *
     * **`style` 为什么并进 `update_profile` 而不是单独一个 `note_style` 工具**：
     * 实测同一响应里下发两个工具时，4 片只有 1 片调了第二个 —— 模型写完一大段标签与记忆
     * 就认为这一片结束了，`finish_reason` 是 `stop` 而不是被截断。并进同一次调用后，
     * 「写三段内容」比「调两个工具」可靠得多；代价是截断时 style 会和标签一起丢，
     * 但 style 本来就是加分项，丢它不影响档案主体。
     */
    fun forInit(withStyle: Boolean = false): JsonArray = definitions(
        withHistory = false,
        withMemory = false,
        withUpdate = true,
        withStyle = withStyle,
    )

    /**
     * 审查标签：只剩 `propose_audit` 的标签两个字段。
     *
     * **没有 `update_profile`、没有 `read_memory`**：清理必须先提案；
     * 标签一片就能看完，不需要按序号读详情。
     */
    fun forAuditTags(): JsonArray = definitions(
        withHistory = false,
        withMemory = false,
        withUpdate = false,
        withAuditTags = true,
    )

    /** 审查记忆：只剩 `propose_audit` 的事件三个字段。正文由 user 一起给，不用读。 */
    fun forAuditMemories(): JsonArray = definitions(
        withHistory = false,
        withMemory = false,
        withUpdate = false,
        withAuditMemories = true,
    )

    /** 建议 / 解读 / 自动回复：只读，绝不能写档案。 */
    fun forSuggest(): JsonArray = definitions(
        withHistory = true,
        withMemory = true,
        withUpdate = false,
        withEarlierMemory = true,
    )

    /** 风格汇总：单轮，只能写侧写，读不到聊天记录（汇总只合并片内观察）。 */
    fun forStyle(): JsonArray = definitions(
        withHistory = false,
        withMemory = false,
        withUpdate = false,
        withUpdateStyle = true,
    )

    /**
     * 按开关拼装。**private**：只允许通过上面几个场景函数取工具集，
     * 否则「初始化该给哪些工具」这件事又会出现第二份定义。
     */
    private fun definitions(
        withHistory: Boolean = true,
        withMemory: Boolean = true,
        withUpdate: Boolean = true,
        withAuditTags: Boolean = false,
        withAuditMemories: Boolean = false,
        withStyle: Boolean = false,
        withUpdateStyle: Boolean = false,
        withEarlierMemory: Boolean = false,
    ): JsonArray = buildJsonArray {
        if (withHistory) add(historyTool())
        if (withMemory) add(readMemoryTool())
        if (withEarlierMemory) add(loadEarlierMemoriesTool())
        if (withUpdate) add(updateProfileTool(withStyle))
        if (withUpdateStyle) add(updateStyleTool())
        // 标签片与记忆片的 schema 不同：各只背自己那一半，14 片 × 省下的 1k 字符是实打实的。
        if (withAuditTags) add(proposeAuditTool(withTags = true, withMemories = false))
        if (withAuditMemories) add(proposeAuditTool(withTags = false, withMemories = true))
    }

    // ── schema ──────────────────────────────────────────────────────────────

    private fun historyTool(): JsonObject = tool(
        name = GET_CHAT_HISTORY,
        description = "读取与该好友的聊天记录。图片、语音、视频、表情包显示为占位" +
            "（`[图片]` / `[语音 3″]` / `[表情包]`）；开了「识别图片」时，对话里最后那张图" +
            "会另外附给你看。\n" +
            "- 想确认某件事：先用 keyword 搜原话（人名、地点、事由），一次拿到带时间的原句。\n" +
            "  搜不到不代表没聊过 —— 换一个说法再搜一次，口语和书面词常常对不上。\n" +
            "- 还搜不到，或本来就要通读一段时间：再用 year / month / day / 区间读完整记录。\n" +
            "- limit 是返回上限（默认 3000）；total 与 truncated 说明有没有截断，截断了就缩小区间。",
        properties = buildJsonObject {
            put("year", intProp("年份，例如 2019"))
            put("month", intProp("月份 1-12，需同时给 year"))
            put("day", intProp("日期 1-31，需同时给 year 与 month"))
            put("from_date", strProp("起始日期，格式 2019 / 2019-05 / 2019-05-03"))
            put("to_date", strProp("结束日期，格式同上，包含该日期整段"))
            put("keyword", strProp("按关键词搜索全量记录，返回带时间的原句；单独使用时不必给日期"))
            put(
                "limit",
                intProp(
                    "最多返回多少条：普通读取默认 3000（上限 5000），" +
                        "关键词搜索默认 $SEARCH_LIMIT（上限 $MAX_SEARCH_LIMIT）"
                ),
            )
        },
    )

    /**
     * 初始化用的写入工具：只增不改删。
     *
     * 删标签 / 删事件不在这里做：整理阶段的任务是「读记录、写档案」，判断「哪条该删」是审查的
     * 职责，而审查走的是「提案 → 用户确认」那条路（`propose_audit`）。留着 `remove_*` 只会让
     * 模型在整理时顺手删东西，且每个分片都多背一段 schema。
     *
     * @param withStyle 带上 `style`：**描述用户本人**（「我」这一侧），与标签描述对方恰好相反。
     *   只有全量初始化才带，增量不带（几天的状态不是人格）。
     */
    private fun updateProfileTool(withStyle: Boolean = false): JsonObject = tool(
        name = UPDATE_PROFILE,
        description = buildString {
            append("一次性写入标签与记忆")
            if (withStyle) append("，以及用户本人的说话方式")
            append("。\n")
            // 这里**不写条数**：初始化不列现有事件（[AiPrompts.initYearUser] 只给标签），
            // 模型看不到档案里已有多少条记忆，写「2000 条封顶」它既没法据此调整，还会读成
            // 「名额还很宽」。数量信息只在它真能看到的地方给（标签的 `tagRule`）。
            append("- 标签是对方的画像（不是聊天摘要），同 category+key 覆盖旧值；")
            append("同一维度只用一个键，值变了用原键覆盖。\n")
            append("- 记忆是双方之间的一件事（不是一句话），同 occurred_at+title 覆盖旧条目；")
            append("琐碎闲聊合并成一条，不要把每句话单独记。\n")
            append("- 全部内容用**一次**调用写完，不要分多次；没有新信息就不要调用。")
            if (withStyle) {
                append("\n- style 是**用户本人**的说话方式，**必须写**，和标签在同一层一起交上来。")
                append("只记看得见的现象，不写人格结论（「嘴硬心软」「很讲义气」一律不收）。")
            }
        },
        properties = buildJsonObject {
            put("tags", tagsArrayProp())
            put("memories", memoriesArrayProp())
            if (withStyle) put("style", styleObjectProp())
        },
    )

    /**
     * `update_profile.style` 的结构。
     *
     * 与图片段同名工具（原来的 `note_style`）合并成字段：两个工具在同一响应里时，
     * 模型经常只调第一个。并成一个调用后，「写三段内容」是它熟悉的形态。
     */
    private fun styleObjectProp(): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", "用户本人（「我」这一侧）的说话方式；只记现象，不下人格结论。")
        put("properties", buildJsonObject {
            put(
                "summary",
                strProp(
                    "整体怎么说话、有哪几种状态，$STYLE_SUMMARY_MAX 字以内。" +
                        "写现象（「话少，回得快，很少主动问」），不写评价。"
                ),
            )
            put(
                "style",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "4~7 条语言现象，每条 $STYLE_ITEM_MAX 字以内：句长、拆条、标点、表情、" +
                            "口头禅、情绪怎么表现、被追问时怎么变。每条必须可数可验证" +
                            "（「单条基本不超过 12 字」可以，「文笔好」不行）。",
                    )
                    put("items", buildJsonObject { put("type", "string") })
                },
            )
            put(
                "behavior",
                buildJsonObject {
                    put("type", "array")
                    put(
                        "description",
                        "4~7 条「什么场景 → 他做了什么」，每条 $BEHAVIOR_ITEM_MAX 字以内：" +
                            "对方求助 / 抱怨 / 分享 / 冷淡 / 邀约、被追问、要拒绝时分别怎么做。" +
                            "必须是行为（「对方抱怨工作 → 先跟着骂两句，再给一个具体办法」可以）。",
                    )
                    put("items", buildJsonObject { put("type", "string") })
                },
            )
        })
    }

    /** 汇总的唯一出口。只写 summary：`basedUntil` 由代码从观察窗推出来。 */
    private fun updateStyleTool(): JsonObject = tool(
        name = UPDATE_STYLE,
        description = "把给出的观察合并成一份「用户本人的说话方式」侧写。只调用一次。",
        properties = buildJsonObject {
            put(
                "summary",
                strProp(
                    "侧写正文，分节文本：【整体】【消息形态】【口头禅】【情绪】【压力下】" +
                        "【场景反应】【边界】【梗与称呼】。每一条都要能改变一句话的写法。"
                ),
            )
        },
    )

    /**
     * 审查提案工具：**只提交建议，不修改档案**。
     *
     * 两个片共用一个工具名，schema 按片裁剪：
     * - 标签片：`merge_tags` + `delete_tags`；
     * - 记忆片：`delete_memories` + `merge_memories` + `mark_important`。
     *
     * 审查场景刻意不下发 `update_profile` —— 「清理前必须让用户确认」不能靠提示词约束，
     * 必须让模型在能力上就删不掉东西。
     */
    private fun proposeAuditTool(withTags: Boolean, withMemories: Boolean): JsonObject = tool(
        name = PROPOSE_AUDIT,
        description = "提交清理建议。只写建议、不修改档案，用户确认后才执行。\n" +
            "没有需要处理的就**不要调用**。条目用档案里的序号 `#N` 指定，" +
            "序号不存在会返回 missing。",
        properties = buildJsonObject {
            if (withTags) {
                put(
                    "merge_tags",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "建议合并的标签组（最多 $MAX_MERGE_TAGS 组）。同一维度被拆成多个键时用，"
                                + "合并后只留一条。",
                        )
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("category", strProp("合并后的分类"))
                                put("key", strProp("合并后的键，用稳定维度名，不带作品或日期"))
                                put(
                                    "value",
                                    strProp(
                                        "合并后的值，$VALUE_MAX 字以内。冲突的（换工作、搬家）只写最新结论；" +
                                            "不冲突的（喜欢的游戏）全部并列写",
                                    ),
                                )
                                put(
                                    "keys",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("description", "要合并掉的原键，格式「分类/键」，至少 2 个")
                                        put("items", buildJsonObject { put("type", "string") })
                                    },
                                )
                                put("reason", strProp("为什么合并，写具体依据，最多 $REASON_MAX 字"))
                            })
                            put("required", strArray("category", "key", "value", "keys", "reason"))
                        })
                    },
                )
                put(
                    "delete_tags",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "建议删除的标签（最多 $MAX_DELETE_TAGS 条）")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("category", strProp("分类，与档案一致"))
                                put("key", strProp("键，与档案一致"))
                                put("reason", strProp("具体依据，最多 $REASON_MAX 字；不要只写「重复」「不重要」"))
                            })
                            put("required", strArray("category", "key", "reason"))
                        })
                    },
                )
            }
            if (withMemories) {
                put(
                    "delete_memories",
                    buildJsonObject {
                        put("type", "array")
                        put("description", "建议删除的事件（最多 $MAX_DELETE_MEMORIES 条）")
                        put("items", numberRefSchema())
                    },
                )
                put(
                    "merge_memories",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "建议合并的事件组（最多 $MAX_MERGE_GROUPS 组，每组 2~$MAX_MERGE_ITEMS 条）。",
                        )
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("title", strProp("合并后的标题，例如「日常闲聊」，$TITLE_MAX 字以内"))
                                put(
                                    "detail",
                                    strProp(
                                        "合并后的正文，$DETAIL_MAX 字以内。写清时间、地点、做了什么、结果、承诺",
                                    ),
                                )
                                put("numbers", intArrayProp("被合并的条目序号（#N），每组 2~$MAX_MERGE_ITEMS 条"))
                                put("reason", strProp("为什么合并，写具体依据，最多 $REASON_MAX 字"))
                            })
                            put("required", strArray("title", "numbers", "reason"))
                        })
                    },
                )
                put(
                    "mark_important",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "建议标记为关键事件的条目（最多 $MAX_MARK_IMPORTANT 条）。" +
                                "够重要但还没标的，在这里补上。判定标准：$IMPORTANT_CRITERIA。",
                        )
                        put("items", numberRefSchema())
                    },
                )
            }
            put("summary", strProp("一句话总结发现了什么，60 字以内"))
        },
    )

    /** 提案里引用一条记忆的最小结构：序号 + 理由。 */
    private fun numberRefSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("number", intProp("记忆序号（档案里的 #N）"))
            put("reason", strProp("具体依据，最多 $REASON_MAX 字；不要只写「不重要」"))
        })
        put("required", strArray("number", "reason"))
    }

    private fun readMemoryTool(): JsonObject = tool(
        name = READ_MEMORY,
        description = "按序号读取记忆的正文。档案里每条记忆前的 `#N` 就是它的序号；" +
            "只有标题、需要起因经过时用它。一次最多 $MAX_READ_ITEMS 条；" +
            "也可以用 keyword 按标题/描述检索。",
        properties = buildJsonObject {
            put(
                "numbers",
                buildJsonObject {
                    put("type", "array")
                    put("description", "要读的记忆序号，对应档案里的 #N；可一次给多个")
                    put("items", buildJsonObject { put("type", "integer") })
                },
            )
            put("keyword", strProp("按关键词在标题与描述里检索，可单独使用"))
        },
    )

    /**
     * 取回更早的记忆：**无参数，一次给完**。
     *
     * 建议 / 解读默认只注入最近的一部分记忆（见 [AiMemoryView]），更早的标题不在上下文里。
     * 交付过的部分不会再给（标记在 [AiMemoryView.Pager]），所以反复调用不会重复灌同一批条目 ——
     * 这是「无参数」换来的：带 `before=日期` 的方案要求模型抄日期，抄错就会重复加载。
     *
     * 不分页：模型不知道「第 101 条」之后有什么，没法判断要不要翻下一页。它只能整体看一遍，
     * 按标题认出「是那次」，再用 `read_memory` 读详情。
     */
    private fun loadEarlierMemoriesTool(): JsonObject = tool(
        name = LOAD_EARLIER_MEMORIES,
        description = "把默认窗口之外、还没给过的记忆标题一次取回。默认窗口里没有、" +
            "或关键词搜不到时用它看全貌。已经给过的不再给；" +
            "这里只有标题，要看某条的经过再用 read_memory。",
        properties = buildJsonObject { },
    )

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject,
        vararg required: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    // 不写 required 时模型会漏字段（曾把 occurred_at 写成 occurredAt），
                    // 加在 schema 里比在 system 里重复一遍格式更有效。
                    if (required.isNotEmpty()) put("required", strArray(*required))
                })
            })
        }

    private fun tagsArrayProp(): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "标签数组")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("category", buildJsonObject {
                    put("type", "string")
                    put("description", "属性分类")
                    put(
                        "enum",
                        strArray(
                            "基础", "关系", "兴趣", "家庭", "性格", "作息", "健康", "经济", "自定义",
                        ),
                    )
                })
                put("key", strProp("属性名，同一属性始终用同一个键"))
                put(
                    "value",
                    strProp(
                        "具体值（「骑行」而不是「运动」），不写某一次发生的事" +
                            "（日期 + 金额 + 经过，那是记忆）；生日这类固定日期照常写"
                    ),
                )
                put("confidence", numberProp("0 到 1：明确说过 0.9、多次提到 0.8、间接推断 0.6；低于 0.5 丢弃"))
                put("evidence", strProp("原文片段，最多 $EVIDENCE_MAX 字"))
            })
            put("required", strArray("category", "key", "value"))
        })
    }

    private fun memoriesArrayProp(): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", "记忆数组")
        put("items", buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                // 字段名必须写进 schema：只给 `type: object` 时模型会自己起名（实测填 occurredAt），
                // 代码按 occurred_at 读就读不到，最后全变成「未知日期」。
                put(
                    "occurred_at",
                    strProp("记录行首的时间，精确到分钟：yyyy-MM-dd HH:mm；只有日期就写 yyyy-MM-dd"),
                )
                put("title", strProp("一件事的标题，$TITLE_MAX 字以内"))
                put("detail", strProp("写清起因、经过、结果、还没做完的部分，$DETAIL_MAX 字以内"))
                put(
                    "important",
                    buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "是否关键事件，逐条判断：$IMPORTANT_CRITERIA；日常闲聊一律 false。",
                        )
                    },
                )
            })
            put("required", strArray("occurred_at", "title", "detail"))
        })
    }

    private fun strArray(vararg values: String): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

    private fun intProp(desc: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
    }

    /** 整数数组（记忆序号列表）。 */
    private fun intArrayProp(desc: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", desc)
        put("items", buildJsonObject { put("type", "integer") })
    }

    private fun strProp(desc: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
    }

    private fun numberProp(desc: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", desc)
    }

    // ── 执行 ────────────────────────────────────────────────────────────────

    /**
     * @param yearHint 当前片对应的年份：模型漏填日期时用它兜底，保证记忆至少按年可排。
     * @param styleWindow 当前片的时间窗：决定 `update_profile.style` 能不能落成一条观察。
     *   为 null（增量整理）时即使模型写了 style 也不会存 —— 增量的片不该产生观察。
     * @param memoryPager 本次建议 / 解读的交付标记：记住「默认窗口之外已经给过哪些」。
     *   只有 `load_earlier_memories` 用它；其他场景不传。
     */
    fun execute(
        wxId: String,
        call: AiConfig.ToolCall,
        yearHint: Int? = null,
        styleWindow: StyleWindow? = null,
        memoryPager: AiMemoryView.Pager? = null,
    ): String {
        return try {
            // 输出被服务商的长度上限截断时 arguments 是一段坏 JSON；toObject() 会静默返回空对象，
            // update_profile 于是写了个空档案并回复 ok，整片记录就这么丢了。
            // 在入口统一拦一道，模型收到错误后可以拆小重试。
            if (call.arguments.isNotBlank() && parseObject(call.arguments) == null) {
                return error("参数不是合法 JSON（多为单次输出过长被截断），减少单次写入量后重试。")
            }
            when (call.name) {
                GET_CHAT_HISTORY -> history(wxId, call.arguments)
                UPDATE_PROFILE -> updateProfile(wxId, call.arguments, yearHint, styleWindow)
                READ_MEMORY -> readMemory(wxId, call.arguments)
                LOAD_EARLIER_MEMORIES -> loadEarlierMemories(wxId, memoryPager)
                PROPOSE_AUDIT -> proposeAudit(wxId, call.arguments)
                UPDATE_STYLE -> updateStyle(wxId, call.arguments)
                // 旧版本下发过独立工具 note_style；模型在重放历史响应时可能仍带着它，
                // 回一句可理解的错误，而不是「未知工具」。
                "note_style" -> error("style 现在写在 update_profile 里，请合并到那次调用。")
                else -> error("未知工具：" + call.name)
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "工具执行失败：${call.name}", e)
            error("参数不合法：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * 把过长的工具结果折掉，控制多轮请求的上下文。
     *
     * 审计 / 建议都是「读一批 → 判断 → 再读一批」，而 `read_memory` 一次最多回 100 条
     * × 200 字。十轮下来 messages 能滚到几十万字符，既慢又贵，极端时整轮 400。
     *
     * 只替换 tool 消息的**内容**，不删消息本身：OpenAI 协议要求 assistant(`tool_calls`)
     * 与 tool 结果成对出现，删掉任意一侧服务端直接报错。
     */
    fun trimToolOutputs(messages: MutableList<AiConfig.ChatMessage>, softLimit: Int = 120_000) {
        var total = messages.sumOf { message ->
            (message.content?.length ?: 0) +
                (message.toolCalls?.sumOf { it.arguments.length } ?: 0)
        }
        if (total <= softLimit) return
        for (index in messages.indices) {
            if (total <= softLimit) break
            val message = messages[index]
            val body = message.content ?: continue
            if (message.role != "tool" || body.length <= 400) continue
            // 留开头一段：模型还能认出这是哪次查询的结果。
            val shortened = body.take(300) +
                "…（原结果 " + body.length + " 字符，其余已省略）"
            total -= body.length - shortened.length
            messages[index] = message.copy(content = shortened)
        }
    }

    // ── get_chat_history ────────────────────────────────────────────────────

    private fun history(wxId: String, arguments: String): String {
        val args = arguments.toObject()
        val year = args.int("year")
        val month = args.int("month")
        val day = args.int("day")
        // 缺上层的日期参数时 windowOf 会静默落成「全部历史」（month 无 year、day 无 month
        // 都会走到那个兜底分支），模型会以为自己查的是某个月，实际拿到的是最早 3000 条。
        // 这里直接回可纠正的错误，比给一份错数据好。
        if (year == null && (month != null || day != null)) {
            return error("month / day 必须与 year 一起给；只按月份查请补上 year。")
        }
        if (day != null && month == null) {
            return error("day 必须与 month 一起给。")
        }
        val window = AiChatHistory.windowOf(
            year = year,
            month = month,
            day = day,
            fromDate = args.str("from_date"),
            toDate = args.str("to_date"),
        )
        val keyword = args.str("keyword").orEmpty().trim()

        // 关键词搜索走库里的 LIKE：不再先取 3000 条再内存过滤，
        // 那样只能搜到窗口边缘的一段，跨年的记录搜不全。
        if (keyword.isNotEmpty()) {
            val limit = (args.int("limit") ?: SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
            val hits = AiChatHistory.search(wxId, keyword, window, limit)
            return buildJsonObject {
                put("ok", true)
                put("range", window.label)
                put("keyword", keyword)
                put("returned", hits.size)
                put("note", when {
                    hits.isEmpty() -> "没有匹配「$keyword」的文字消息。"
                    hits.size >= limit -> "匹配较多，只返回最近 $limit 条，可以加时间范围缩小。"
                    else -> "按时间正序，每条都是原文。"
                })
                put("transcript", AiChatHistory.transcript(hits, withDate = true))
            }.toString()
        }

        val limit = (args.int("limit") ?: 3_000).coerceIn(1, 5_000)
        val slice = AiChatHistory.load(wxId, window, limit)

        return buildJsonObject {
            put("ok", true)
            put("range", window.label)
            put("total", slice.total)
            put("returned", slice.lines.size)
            // 判断「被截断」只能用 sampled（库里条数 > 上限）。
            // 不能用 lines.size < total：total 数的是放行的全部类型，lines 是 sanitize 后
            // 非空的行，XML 卡片被过滤掉之后这个不等式恒成立，等于永远在误报。
            put("truncated", slice.sampled)
            put("note", when {
                slice.lines.isEmpty() -> "这个范围内没有消息。"
                slice.sampled ->
                    "该范围共 " + slice.total + " 条消息，只返回了最早 " + slice.lines.size +
                        " 条，请缩小区间或改用关键词搜索。"
                else -> ""
            })
            put("transcript", AiChatHistory.transcript(slice.lines, withDate = true))
        }.toString()
    }

    // ── read_memory ─────────────────────────────────────────────────────────

    /**
     * 按序号批量读记忆详情。
     *
     * 序号由 [AiMemoryIndex] 按时间正序派生，提示词里渲染成 `#N`，这里用同一份列表解析，
     * 所以模型给序号不会错位 —— 比原来抄「日期 + 标题」既省 token，也不会因为漏字而找不到。
     * 越界的序号进 `missing`，模型能自己纠正。
     */
    private fun readMemory(wxId: String, arguments: String): String {
        val args = arguments.toObject()
        val profile = AiProfileStore.readProfile(wxId)
            ?: return error("这位好友还没有档案。")
        val keyword = args.str("keyword").orEmpty().trim()
        val numbers = args.intArray("numbers")
        if (numbers.isEmpty() && keyword.isEmpty()) {
            return error("请给 numbers（记忆序号）或用 keyword 检索。")
        }

        // 一次排序同时拿到「序号 → 条目」和「条目 id → 序号」，不要为两个映射各排一遍。
        val numbered = AiMemoryIndex.numbered(profile.memories)
        val byNumber = numbered.toMap()
        val numberOf = numbered.associate { (number, entry) -> entry.id to number }
        val hits = LinkedHashMap<String, MemoryEntry>()
        val missing = mutableListOf<String>()
        // 模型点名的条目优先；keyword 只用来补足剩余名额 ——
        // 反过来先放检索结果时，会把「我明确要读的这几条」挤出上限。
        numbers.take(MAX_READ_ITEMS).forEach { number ->
            val entry = byNumber[number]
            if (entry == null) missing += "#$number" else hits[entry.id] = entry
        }
        if (keyword.isNotEmpty() && hits.size < MAX_READ_ITEMS) {
            val room = MAX_READ_ITEMS - hits.size
            profile.memories
                .asSequence()
                .filter { it.title.contains(keyword, true) || it.detail.contains(keyword, true) }
                .take(room)
                .forEach { hits[it.id] = it }
        }

        return buildJsonObject {
            put("ok", true)
            put("returned", hits.size)
            put("note", when {
                hits.isEmpty() -> "没有匹配的记忆，序号可能超出档案范围。"
                missing.isNotEmpty() -> "有 " + missing.size + " 个序号不存在，核对后再试。"
                else -> ""
            })
            put("events", buildJsonArray {
                hits.values.forEach { entry ->
                    add(buildJsonObject {
                        put("number", numberOf[entry.id] ?: 0)
                        put("occurred_at", entry.occurredAt)
                        put("title", entry.title)
                        put("detail", entry.detail)
                        if (entry.important) put("important", true)
                    })
                }
            })
            if (missing.isNotEmpty()) put("missing", buildJsonArray {
                missing.forEach { add(JsonPrimitive(it)) }
            })
        }.toString()
    }

    // ── load_earlier_memories ───────────────────────────────────────────────

    /**
     * 取回默认窗口之外的记忆标题：**一次给完**。
     *
     * 标题用「一行一条」的纯文本而不是对象数组：1700 条约 25k token，对象形式要 60k ——
     * 这个工具的场合本来就是「关键词搜不到，便宜地看一眼全貌」。
     * 序号仍是全档序号，模型可以直接拿它去 `read_memory(numbers=[...])` 读详情。
     *
     * **不分页**：模型不知道第 100 条之后有什么，没法判断要不要翻第二页；它只能整体看一遍，
     * 按标题认出「是那次」。分页在这里只制造了一个它无法判断的岔路口。
     */
    private fun loadEarlierMemories(wxId: String, pager: AiMemoryView.Pager?): String {
        val profile = AiProfileStore.readProfile(wxId)
            ?: return error("这位好友还没有档案。")
        // 没有游标（非常规调用）时退回全量剩余：宁可多给一次，也好过什么都取不到。
        val earlier = pager?.takeEarlier() ?: AiMemoryView.of(profile).earlier
        if (earlier.isEmpty()) {
            return buildJsonObject {
                put("ok", true)
                put("returned", 0)
                put("note", "没有更早的记忆了。")
            }.toString()
        }
        return buildJsonObject {
            put("ok", true)
            put("returned", earlier.size)
            put("remaining", pager?.remaining ?: 0)
            put("note", "只有标题；要看经过用 read_memory(numbers=[序号])。")
            put("titles", AiMemoryView.render(earlier, "\n"))
        }.toString()
    }

    // ── propose_audit ───────────────────────────────────────────────────────

    /** 提案里的一条建议：解析后的真实条目 + 模型给的理由。 */
    private class ResolvedMemory(val entry: MemoryEntry, val reason: String)

    /**
     * 把模型的清理建议解析成 [AiAuditPlan] 存进档案。
     *
     * 条目一律按**当前档案**重新解析：模型抄错标题、档案在审查期间被改过，都在这里挡掉，
     * 不会产生一份「执行时才报错」的提案。解析不到的进 `missing`，模型能自己改。
     */
    private fun proposeAudit(wxId: String, arguments: String): String {
        val args = arguments.toObject()
        val profile = AiProfileStore.readProfile(wxId)
            ?: return error("这位好友还没有档案。")

        val missing = mutableListOf<String>()
        var droppedGroups = 0
        var droppedTagGroups = 0
        // 序号 → 条目：整批建议共用同一份映射，保证「模型看到的 #N」与这里解析的完全一致。
        val byNumber = AiMemoryIndex.byNumber(profile.memories)
        // 标签合并组按档案预校验：模型抄回来的键可能拼错或带空白，而 `apply` 里 targets < 2
        // 会整组跳过 —— 这里不挡掉，提案页就会显示「合并 3 组」，执行时却一组都落不下去。
        val existingTagIds = profile.tags.mapTo(HashSet()) { TagRef.id(it.category, it.key) }

        // ── 标签：合并组先解析，delete 里命中合并目标的不单独处理 ──
        val mergeTags = mutableListOf<AuditTagGroup>()
        args.array("merge_tags").forEach { element ->
            val category = element.str("category").orEmpty().trim()
            val key = element.str("key").orEmpty().trim()
            if (category.isEmpty() || key.isEmpty()) {
                droppedTagGroups++
                return@forEach
            }
            // 只留档案里真实存在的键。`apply` 会把「目标键已在档案里」也算进被合并的一方，
            // 所以校验口径必须一致，否则「目标键 + 1 个旧键」这种能落地的 2 条会被误丢。
            val refs = element.stringArray("keys").filter { ref ->
                TagRef.parse(ref)?.let { (refCategory, refKey) ->
                    TagRef.id(refCategory, refKey) in existingTagIds
                } == true
            }
            val targets = refs.mapNotNull { TagRef.parse(it) }
                .mapTo(HashSet()) { TagRef.id(it.first, it.second) } + TagRef.id(category, key)
            if (targets.count { it in existingTagIds } < 2) {
                droppedTagGroups++
                return@forEach
            }
            mergeTags += AuditTagGroup(
                category = category,
                key = key,
                value = element.str("value").orEmpty().trim().take(VALUE_MAX),
                keys = refs.take(MAX_MERGE_ITEMS),
                reason = element.str("reason").orEmpty().trim().take(REASON_MAX),
            )
        }
        val (deleteTags, tagMissing) = resolveTags(profile, args.array("delete_tags"))
        missing += tagMissing

        val (markImportant, markMissing) = resolveByNumber(byNumber, args.array("mark_important"))
        missing += markMissing

        // 合并组必须先解析：同一批建议里「删 A」和「合并 A、B」并存时，A 只能二选一。
        // 合并保留内容、删除丢弃内容，所以合并优先，删除列表里把命中合并的条目剔掉 ——
        // 否则执行时先删后合并，组内不足 2 条会被静默跳过，用户勾了却没生效。
        val groups = mutableListOf<AuditMergeGroup>()
        val mergedIds = HashSet<String>()
        args.array("merge_memories").forEach { element ->
            // 合并组只给序号，日期取组内最早一条：让模型再写一遍日期只会多一个出错点。
            val (items, itemMissing) = resolveNumbers(byNumber, element.intArray("numbers"))
            missing += itemMissing
            if (items.size < 2) {
                droppedGroups++
                return@forEach
            }
            val kept = items.take(MAX_MERGE_ITEMS)
            val date = kept.minByOrNull { AiDates.sortKey(it.occurredAt) }?.occurredAt
                ?: AiDates.UNKNOWN
            kept.forEach { mergedIds += it.id }
            groups += AuditMergeGroup(
                occurredAt = date,
                title = element.str("title").orEmpty().trim().ifBlank { "日常闲聊" }.take(TITLE_MAX),
                detail = element.str("detail").orEmpty().trim().take(DETAIL_MAX),
                items = kept.map { AuditMemoryRef(it.occurredAt, it.title) },
                reason = element.str("reason").orEmpty().trim().take(REASON_MAX),
            )
        }

        val (deleteMemories, memoryMissing) = resolveByNumber(byNumber, args.array("delete_memories"))
        missing += memoryMissing
        val overlapped = deleteMemories.count { it.entry.id in mergedIds }
        val effectiveDelete = deleteMemories.filterNot { it.entry.id in mergedIds }

        val plan = AiAuditPlan(
            createdAt = System.currentTimeMillis(),
            mergeTags = mergeTags.take(MAX_MERGE_TAGS),
            deleteTags = deleteTags.take(MAX_DELETE_TAGS),
            deleteMemories = effectiveDelete.take(MAX_DELETE_MEMORIES)
                .map { AuditMemoryRef(it.entry.occurredAt, it.entry.title, it.reason) },
            mergeGroups = groups.take(MAX_MERGE_GROUPS),
            markImportant = markImportant.take(MAX_MARK_IMPORTANT)
                .map { AuditMemoryRef(it.entry.occurredAt, it.entry.title, it.reason) },
            note = args.str("summary").orEmpty().trim().take(60),
        )

        if (plan.isEmpty) {
            // 「空提案」有两种：档案确实干净，以及模型提了但一条都没解析出来
            // （合并组 keys 格式不对、组内不足 2 条）。后者必须回 ok:false ——
            // 否则这一片会被记成「成功」，建议被静默吞掉，日志里也查不到。
            val dropped = droppedGroups + droppedTagGroups
            if (dropped > 0) {
                WeLogger.w(
                    TAG,
                    "$wxId 审查建议全被丢弃：合并组 $droppedGroups 组 / 标签组 $droppedTagGroups 组，" +
                        "missing=${missing.size}",
                )
            }
            return buildJsonObject {
                put("ok", missing.isEmpty() && dropped == 0)
                put(
                    "note",
                    when {
                        missing.isNotEmpty() -> "序号都没对上档案，请核对 #N 后重试。"
                        dropped > 0 ->
                            "有 $dropped 组建议没解析成功（组内至少 2 条、格式「分类/键」），请检查后重试。"
                        else -> "没有需要处理的。"
                    },
                )
                if (missing.isNotEmpty()) {
                    put("missing", buildJsonArray {
                        missing.distinct().take(20).forEach { add(JsonPrimitive(it)) }
                    })
                }
            }.toString()
        }

        // ── 累加：14 片各提交一次，后一片不能覆盖前一片 ──
        val existing = profile.pendingAudit
        val merged = if (existing == null) plan else existing.copy(
            mergeTags = (existing.mergeTags + plan.mergeTags)
                .distinctBy { TagRef.id(it.category, it.key) },
            deleteTags = (existing.deleteTags + plan.deleteTags)
                .distinctBy { TagRef.id(it.category, it.key) },
            deleteMemories = (existing.deleteMemories + plan.deleteMemories)
                .distinctBy { it.occurredAt + "\u0000" + it.title },
            mergeGroups = (existing.mergeGroups + plan.mergeGroups)
                .distinctBy { g -> g.items.map { it.occurredAt + "\u0000" + it.title }.sorted() },
            markImportant = (existing.markImportant + plan.markImportant)
                .distinctBy { it.occurredAt + "\u0000" + it.title },
            // 14 片的 summary 拼起来没有意义，保留第一次非空的。
            note = existing.note.ifBlank { plan.note },
        )

        // 超限只丢超出的那一类，其余照收：整片拒绝会让后面几片的合并与标记一起白跑。
        val overDelete = merged.deleteMemories.size > MAX_TOTAL_DELETE_MEMORIES
        val overGroups = merged.mergeGroups.size > MAX_TOTAL_MERGE_GROUPS
        val next = merged.copy(
            deleteMemories = if (overDelete) existing?.deleteMemories.orEmpty() else merged.deleteMemories,
            mergeGroups = if (overGroups) existing?.mergeGroups.orEmpty() else merged.mergeGroups,
        )
        // 丢掉的这一片必须留痕：调用方只看 ok，返回里的 capped 没人读，
        // 不记日志就完全查不到「为什么删得比预期少」。
        if (overDelete) {
            WeLogger.w(
                TAG,
                "$wxId 删除已达上限（累计 ${existing?.deleteMemories?.size ?: 0}/" +
                    "$MAX_TOTAL_DELETE_MEMORIES），本片 ${plan.deleteMemories.size} 条未收下",
            )
        }
        if (overGroups) {
            WeLogger.w(
                TAG,
                "$wxId 合并组已达上限（累计 ${existing?.mergeGroups?.size ?: 0}/" +
                    "$MAX_TOTAL_MERGE_GROUPS），本片 ${plan.mergeGroups.size} 组未收下",
            )
        }

        if (AiProfileStore.patchProfile(wxId) { it.copy(pendingAudit = next) } == null) {
            return error("建议保存失败")
        }

        return buildJsonObject {
            put("ok", true)
            put("merge_tags", plan.mergeTags.size)
            put("delete_tags", plan.deleteTags.size)
            put("delete_memories", plan.deleteMemories.size)
            put("merge_groups", plan.mergeGroups.size)
            put("mark_important", plan.markImportant.size)
            put("total_memories", next.deleteMemories.size)
            if (droppedGroups > 0) put("dropped_groups", droppedGroups)
            if (droppedTagGroups > 0) put("dropped_tag_groups", droppedTagGroups)
            if (overlapped > 0) put("merged_instead_of_deleted", overlapped)
            if (overDelete) put("capped", "删除已达上限，本片 ${plan.deleteMemories.size} 条未收下")
            if (overGroups) put("capped_groups", "合并组已达上限，本片 ${plan.mergeGroups.size} 组未收下")
            if (missing.isNotEmpty()) {
                put("missing", buildJsonArray {
                    missing.distinct().take(20).forEach { add(JsonPrimitive(it)) }
                })
            }
            put("note", "建议已提交，用户确认后才执行。")
        }.toString()
    }

    /** `[{number, reason}]` → 条目 + 理由；序号不存在的进 missing。 */
    private fun resolveByNumber(
        byNumber: Map<Int, MemoryEntry>,
        elements: List<JsonObject>,
    ): Pair<List<ResolvedMemory>, List<String>> {
        val hits = mutableListOf<ResolvedMemory>()
        val missing = mutableListOf<String>()
        val seen = HashSet<String>()
        elements.forEach { element ->
            val number = element.int("number") ?: return@forEach
            val entry = byNumber[number]
            if (entry == null) {
                missing += "#$number"
            } else if (seen.add(entry.id)) {
                hits += ResolvedMemory(entry, element.str("reason").orEmpty().trim().take(REASON_MAX))
            }
        }
        return hits to missing
    }

    /** `[3, 4, 5]` → 条目（合并组用，没有逐条理由）。越界序号进 missing。 */
    private fun resolveNumbers(
        byNumber: Map<Int, MemoryEntry>,
        numbers: List<Int>,
    ): Pair<List<MemoryEntry>, List<String>> {
        val missing = numbers.filterNot { it in byNumber }.map { "#$it" }
        val hits = numbers.mapNotNull { byNumber[it] }.distinctBy { it.id }
        return hits to missing
    }

    private fun resolveTags(
        profile: AiProfile,
        elements: List<JsonObject>,
    ): Pair<List<AuditTagRef>, List<String>> {
        val hits = mutableListOf<AuditTagRef>()
        val missing = mutableListOf<String>()
        val seen = HashSet<String>()
        elements.forEach { element ->
            val category = element.str("category").orEmpty().trim()
            val key = element.str("key").orEmpty().trim()
            if (category.isEmpty() || key.isEmpty()) return@forEach
            val matched = profile.tags.firstOrNull { it.category == category && it.key == key }
            if (matched == null) {
                missing += "$category/$key"
            } else if (seen.add(matched.id)) {
                hits += AuditTagRef(
                    category = matched.category,
                    key = matched.key,
                    reason = element.str("reason").orEmpty().trim().take(REASON_MAX),
                )
            }
        }
        return hits to missing
    }

    // ── update_profile ──────────────────────────────────────────────────────

    /**
     * 写入本片整理出的标签与记忆。
     *
     * **读改写不在同一个锁里**（`readProfile` → `saveProfile`）：只有初始化会走到这里，
     * 每片一次且单线程；初始化期间设置页只渲染进度、不暴露任何开关，审查的 propose 也
     * 走 `patchProfile`，所以实际不存在第二个写者。其它写路径一律用 `patchProfile`。
     */
    private fun updateProfile(
        wxId: String,
        arguments: String,
        yearHint: Int?,
        window: StyleWindow?,
    ): String {
        val args = arguments.toObject()
        val profile = AiProfileStore.readProfile(wxId)
            ?: return error("这位好友还没有档案，请先初始化。")

        val tagItems = args.array("tags")
        val memoryItems = args.array("memories")

        // ── 标签：同 category+key 覆盖 ──
        val tags = LinkedHashMap<String, ProfileTag>()
        profile.tags.forEach { tags[it.id] = it }

        // 这一片的最后时刻：`StyleWindow.toSec` 是右开边界（下一天零点），减 1 秒才是真正
        // 最后时刻。不减会让 2021 年的片写成 2022-01，跨一年。增量（window = null）取今天。
        val fallbackStamp = window?.toSec?.minus(1)?.takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000)

        var addedTags = 0
        var updatedTags = 0
        var ignoredTags = 0
        var truncatedEvidences = 0
        tagItems.forEach { element ->
            val category = element.str("category").orEmpty().trim()
            val key = element.str("key").orEmpty().trim()
            val value = element.str("value").orEmpty().trim()
            if (category.isEmpty() || key.isEmpty() || value.isEmpty()) {
                ignoredTags++
                return@forEach
            }
            val confidence = element.float("confidence") ?: 0.6f
            // 「看不出来就不写」不能只写在提示词里：低置信度直接丢弃。
            if (confidence < 0.5f) {
                ignoredTags++
                return@forEach
            }
            // 字数上限只在 schema 里写一次；超出就截断，并把次数回给模型，避免它下次还写超。
            val evidence = element.str("evidence").orEmpty().trim()
            if (evidence.length > EVIDENCE_MAX) truncatedEvidences++
            // 值没变说明没有新证据：沿用旧时间，否则每次增量都会把所有标签刷成今天，
            // 审查的「这条超过一年没更新」就永远不成立。
            val id = category + "\u0000" + key
            val old = tags[id]
            val stamp = if (old != null && old.value == value) {
                old.updatedAt.takeIf { it > 0 } ?: fallbackStamp
            } else {
                fallbackStamp
            }
            val tag = ProfileTag(
                category = category,
                key = key,
                value = value,
                confidence = confidence.coerceIn(0f, 1f),
                evidence = evidence.take(EVIDENCE_MAX),
                updatedAt = stamp,
            )
            if (tags.containsKey(tag.id)) updatedTags++ else addedTags++
            tags[tag.id] = tag
        }

        // ── 记忆：同 occurred_at+title 覆盖 ──
        val memories = LinkedHashMap<String, MemoryEntry>()
        profile.memories.forEach { memories[it.id] = it }

        val now = System.currentTimeMillis()
        var addedMemories = 0
        var updatedMemories = 0
        var truncatedTitles = 0
        var truncatedDetails = 0
        memoryItems.forEach { element ->
            val rawDate = element.str("occurred_at").orEmpty()
            val title = element.str("title").orEmpty().trim()
            if (title.isEmpty()) return@forEach
            val detail = element.str("detail").orEmpty().trim()
            if (title.length > TITLE_MAX) truncatedTitles++
            if (detail.length > DETAIL_MAX) truncatedDetails++
            val occurredAt = AiDates.normalize(rawDate) ?: yearHint?.toString() ?: AiDates.UNKNOWN
            val id = memoryId(occurredAt, title)
            // 模型这次没写 important 时沿用旧值：初始化会给同一件事重写一次，
            // 不能因为漏字段就把上次标好的关键事件冲掉。
            val important = element.bool("important") ?: memories[id]?.important ?: false
            val entry = MemoryEntry(
                id = id,
                occurredAt = occurredAt,
                title = title.take(TITLE_MAX),
                detail = detail.take(DETAIL_MAX),
                createdAt = now,
                important = important,
            )
            if (memories.containsKey(entry.id)) updatedMemories++ else addedMemories++
            memories[entry.id] = entry
        }

        // 总量上限：关键事件优先保留，其余按日期留最新的。
        // 只按日期留的话，「第一次见面」「吵过一次」这些最旧的事件会最先被新事件挤掉。
        val memoryList = AiProfileLimits.memories(memories.values)
        val dropped = memories.size - memoryList.size

        // 标签总量上限：按 confidence 从高到低保留（同分保持写入顺序）。
        val tagList = AiProfileLimits.tags(tags.values)
        val droppedTags = tags.size - tagList.size
        if (droppedTags > 0) {
            // 原来静默丢弃：模型看不到自己写了什么被挤掉，日志里也查不到，
            // 出问题只能靠猜「为什么这条标签没了」。
            val keptIds = tagList.mapTo(HashSet()) { it.id }
            val lost = tags.values.asSequence()
                .filter { it.id !in keptIds }
                .take(5)
                .joinToString("、") { it.category + "/" + it.key }
            WeLogger.w(TAG, "$wxId 标签裁尾 $droppedTags 条（保留 ${tagList.size}/$MAX_TAGS）：$lost")
        }

        // ── style：只在自己的片里记，增量片不记 ──
        // 时间窗由代码补（[StyleWindow]），同片重跑按 `period` 覆盖而不是追加 ——
        // 否则「重新整理」跑两遍会让同样的现象出现两次，汇总时被当成更高频。
        var styleNote: StyleNote? = null
        if (window != null) {
            val styleArgs = args.obj("style")
            val summary = styleArgs?.str("summary").orEmpty().trim().take(STYLE_SUMMARY_MAX)
            val styleItems = styleArgs?.stringArray("style").orEmpty()
                .map { it.take(STYLE_ITEM_MAX) }
                .filter { it.isNotEmpty() }
                .take(STYLE_ITEMS_MAX)
            val behavior = styleArgs?.stringArray("behavior").orEmpty()
                .map { it.take(BEHAVIOR_ITEM_MAX) }
                .filter { it.isNotEmpty() }
                .take(STYLE_ITEMS_MAX)
            if (summary.isNotEmpty() || styleItems.isNotEmpty() || behavior.isNotEmpty()) {
                styleNote = StyleNote(
                    period = window.period,
                    fromSec = window.fromSec,
                    toSec = window.toSec,
                    summary = summary,
                    style = styleItems,
                    behavior = behavior,
                )
            }
        }

        val ok = AiProfileStore.saveProfile(
            profile.copy(
                tags = tagList,
                memories = memoryList,
                styleNotes = when {
                    styleNote == null -> profile.styleNotes
                    else -> profile.styleNotes.filterNot { it.period == styleNote.period } + styleNote
                },
            )
        )
        if (!ok) return error("写入档案失败")

        return buildJsonObject {
            put("ok", true)
            put("tags_added", addedTags)
            put("tags_updated", updatedTags)
            put("tags_total", tagList.size)
            put("memories_added", addedMemories)
            put("memories_updated", updatedMemories)
            put("memories_total", memoryList.size)
            if (window != null) put("style", if (styleNote == null) "missing" else "ok")
            if (ignoredTags > 0) put("ignored_tags", ignoredTags)
            // 「按容量上限裁掉的」，不是「裁最旧的」：关键事件优先留下，被裁的可能是新条目。
            if (dropped > 0) put("dropped_by_quota", dropped)
            if (droppedTags > 0) put("dropped_tags", droppedTags)
            if (truncatedTitles > 0) put("truncated_titles", truncatedTitles)
            if (truncatedDetails > 0) put("truncated_details", truncatedDetails)
            if (truncatedEvidences > 0) put("truncated_evidences", truncatedEvidences)
            if (truncatedTitles + truncatedDetails + truncatedEvidences > 0) {
                put("note", "有字段超出字数上限已截断，下次按 schema 里的字数写。")
            }
        }.toString()
    }

    // ── update_style ────────────────────────────────────────────────────────

    /**
     * 汇总结果落盘：只有 `summary` 一个字段，其余由代码算。
     *
     * `basedUntil` 取**观察窗的最晚一天**：汇总不看聊天记录，唯一的日期来源就是这些片。
     * 用 `toSec - 1` 是因为窗口是左闭右开（`toSec` 是下一天零点）。
     */
    private fun updateStyle(wxId: String, arguments: String): String {
        val summary = arguments.toObject().str("summary").orEmpty().trim()
        if (summary.isEmpty()) return error("summary 为空，没有可写入的侧写。")
        var basedUntil = ""
        val saved = AiProfileStore.patchProfile(wxId) { profile ->
            val latest = profile.styleNotes.maxOfOrNull { it.toSec } ?: 0L
            if (latest > 0) basedUntil = AiPrompts.monthOf(latest - 1)
            profile.copy(
                style = profile.style.copy(
                    summary = summary,
                    basedUntil = basedUntil.ifBlank { profile.style.basedUntil },
                    // 模型刚写回来的，不再标记为「用户改过」。
                    userEdited = false,
                    insufficient = false,
                )
            )
        } ?: return error("写入档案失败")

        return buildJsonObject {
            put("ok", true)
            put("chars", summary.length)
            put("sections", AiPrompts.styleSections(summary))
            put("based_until", saved.style.basedUntil)
        }.toString()
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    /** `sha1(occurredAt + title)` 前 16 位：同一件事重复写不会产生第二条。 */
    fun memoryId(occurredAt: String, title: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((occurredAt + "\u0000" + title).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun error(message: String): String =
        buildJsonObject { put("ok", false); put("error", message) }.toString()

    /**
     * 工具返回值是不是成功。
     *
     * 审查的切片循环用它区分「这一片干净」和「建议没落盘」：后者要记 W，否则一片白跑
     * 和一片无建议在日志里长得一样。JSON 解析封在工具层，审查不需要知道返回结构。
     */
    fun resultOk(response: String): Boolean = runCatching {
        (parseObject(response)?.get("ok") as? JsonPrimitive)?.booleanOrNull
    }.getOrNull() == true

    /**
     * 严格解析工具参数。
     *
     * 结尾少 `}` 之类的问题**不做本地修补**：补出来的结构虽然能解析，但那是在替模型兜底，
     * 错误率不会因此下降，也看不出模型到底哪一步没写完。宁可让它失败、记 W，
     * 再从「参数必须是完整 JSON」的 schema 约束和日志里去治。
     */
    private fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    private fun String.toObject(): JsonObject = parseObject(this) ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.intArray(key: String): List<Int> = runCatching {
        this[key]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
    }.getOrElse { emptyList() }

    /** 字符串数组（观察里的现象 / 场景）：空白项直接丢掉，不让它们占条数。 */
    private fun JsonObject.stringArray(key: String): List<String> = runCatching {
        this[key]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }.getOrElse { emptyList() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.floatOrNull

    private fun JsonObject.array(key: String): List<JsonObject> = runCatching {
        this[key]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
    }.getOrElse { emptyList() }

    /** 单个嵌套对象（`update_profile.style`）。缺失或类型不对都返回 null，不抛。 */
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
