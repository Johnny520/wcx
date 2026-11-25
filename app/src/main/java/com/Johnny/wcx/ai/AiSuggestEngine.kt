package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 智能建议：拿当天的文字记录 + 档案，产出「对方意图的解读」与「三条可直接发送的回复」。
 *
 * **这是一个小 agent，不是一次问答**：
 *
 * - 档案里的记忆只带标题，且**默认只带最近半年 ∪ 最新 30 条、最多 300 条**
 *   （全量上限 2000 条约 30k token，多数对话用不到，却每次请求都要付）；
 * - 模型判断「需要知道某件事的经过」时，自己调 `read_memory` 批量读详情；
 *   对方提到更早的事、默认窗口里没有时，调 `load_earlier_memories` 一次取回更早的标题；
 *   需要更早的原话时调 `get_chat_history`；
 * - 读够了才输出结论，最多 [MAX_ROUNDS] 轮工具往返。
 *
 * 另一处刻意的设计：**解析失败要退回原文**。模型偶尔会包代码块、加一句「好的，以下是」，
 * 解析不出来时至少把文字显示出来，而不是整块报「格式错误」。
 */
object AiSuggestEngine {

    private const val TAG = "AiSuggestEngine"

    /**
     * 建议的轮次与预算。
     *
     * 10 轮：正常 1~2 轮就够，但「先读记忆、再补一段记录、再核对另一件事」是合理路径，
     * 给到 10 轮，避免档案很深时被轮数截断在半路。
     *
     * 8 分钟总预算：10 轮 × 45s 加收口也接近 8 分钟，卡住时用户会以为界面死了。
     */
    private val LIMITS = AiAgentLoop.Limits(
        maxRounds = 10,
        // 交互式场景，单轮不宜超过这个数。
        singleTimeoutMs = 45_000L,
        totalBudgetMs = 8 * 60_000L,
        minRoundMs = 15_000L,
        minClosingMs = 10_000L,
    )

    /** 单轮思考在「思考过程」里最多显示这么多字：再长就不是给人看的了，也拖慢排版。 */
    private const val MAX_THINKING_CHARS = 2000

    /**
     * 当天文字条数少于这个数时，把昨天一起读进来。
     *
     * 4 条：凌晨刚过常常已经有 1~2 句（对方随手补的那句），按「今天 0 条」判断会漏掉
     * 前一天那半段对话 —— 那才是这次要回的东西。
     */
    private const val TODAY_MIN_LINES = 4

    data class Suggestion(
        val read: String = "",
        val replies: List<String> = emptyList(),
        val error: String? = null,
        /** 每一轮「想了什么、做了什么」，展开后给用户看过程。 */
        val steps: List<Step> = emptyList(),
    )

    /**
     * 一轮的轨迹。
     *
     * @param thinking 该轮的思考内容（`reasoning_content`）。关掉思考时为空。
     * @param actions 该轮发起的工具调用，已翻成人话（「查阅 3 条事件详情」）。
     */
    data class Step(
        val round: Int,
        val thinking: String,
        val actions: List<String>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 生成解读 + 三条回复。阻塞调用（查库 + HTTP + 工具），放在后台线程。
     *
     * @param shouldStop 调用方是否已经不需要结果（浮层被关掉、点了刷新）：每轮开始与收口前各查一次。
     */
    fun suggest(
        wxId: String,
        shouldStop: () -> Boolean = { false },
    ): Suggestion {
        val profile = AiProfileStore.readProfile(wxId)
            ?: return Suggestion(error = "这位好友还没有档案")
        // 整理/审查期间档案是只读的：读到写了一半的标签会给出错误判断，
        // 也避免两条流水线互相覆盖。统一在这里拦，自动回复与手动菜单都走这条路径。
        if (AiRunState.isBusy(wxId, profile)) {
            return Suggestion(error = "正在整理档案或审查档案，稍后再试")
        }
        // 当天全量：不设条数上限，热聊的一天也要看到完整上下文。
        val today = AiChatHistory.load(wxId, AiChatHistory.todayWindow())
        // 凌晨刚过时「今天」往往只有一两句，上一次对话停在 23:59：少于这个数就连昨天一起读，
        // 否则模型看到的是一段从半句话开始的对话。两天都空才算「最近没有」。
        // 只数文字：几张图、几个表情不代表「今天聊过」。
        val crossDay = today.lines.count { it.isText } < TODAY_MIN_LINES
        val slice = if (crossDay) AiChatHistory.load(wxId, AiChatHistory.lastDayWindow()) else today
        // 跨天时必须带日期：否则 23:59 与 00:01 会被读成同一天里的顺序错误。
        val transcript = AiChatHistory.transcript(slice.lines, withDate = crossDay)
        if (transcript.isBlank()) {
            return Suggestion(error = "最近没有聊天记录")
        }
        val dayLabel = if (crossDay) {
            "昨天到今天（" + AiPrompts.dateOf(slice.window.fromSec) + " ~ " +
                AiPrompts.dateOf(slice.window.toSec - 1) + "）"
        } else {
            "今天（" + AiPrompts.dateOf(slice.window.fromSec) + "）"
        }
        // 默认只注入最近半年，交付标记让「取更早的记忆」不会把同一批条目灌两遍。
        val memoryPager = AiMemoryView.pager(profile)

        val system = AiPrompts.suggestSystem(profile)
        // 开了「识别图片」就把**触发这次的这张图**挂到 user 消息上。读不到（还没落盘）
        // 就跳过，transcript 里仍是 `[图片]` 占位 —— 不为一张图卡住整次建议。
        val imageUrls = if (AiConfig.recognizeImages) {
            listOfNotNull(AiImageSource.latestDataUrl(slice.lines))
        } else {
            emptyList()
        }
        val messages = mutableListOf(
            AiConfig.ChatMessage(role = "system", content = system),
            AiConfig.ChatMessage(
                role = "user",
                content = AiPrompts.suggestUser(
                    profile = profile,
                    transcript = transcript,
                    dayLabel = dayLabel,
                    samples = styleSamples(slice.lines),
                    hasImage = imageUrls.isNotEmpty(),
                ),
                images = imageUrls,
            ),
        )
        // 只读：建议过程绝不写档案。给 update_profile 等于允许模型在生成回复时
        // 顺手改标签与记忆，且用户完全看不到这次修改。
        val tools = AiTools.forSuggest()
        // 说话类请求：用设置页的「说话温度」（默认 0.9）。
        val temperature = AiConfig.temperature

        // debug 下把这次真正发出去的提示词也落一份：确认「模型看到了哪些档案标题、
        // 当天多少条记录、工具给了几个」时，只看初始化文件是不够的。
        val dumpPath = AiDebug.dumpPrompt(
            wxId = wxId,
            label = "suggest",
            system = system,
            user = messages[1].content.orEmpty(),
            tools = tools,
            thinking = AiConfig.isThinkingEnabled,
        )

        val steps = mutableListOf<Step>()
        val outcome = AiAgentLoop.run(
            wxId = wxId,
            messages = messages,
            tools = tools,
            temperature = temperature,
            limits = LIMITS,
            dumpPath = dumpPath,
            logTag = TAG,
            shouldStop = shouldStop,
            onRound = { round, result ->
                val thinking = result.reasoning.orEmpty().trim()
                val actions = result.toolCalls.map { actionText(it) }
                // 关掉思考时，最后一轮往往既没有思考也没有工具调用：不记空行，
                // 否则「思考过程」里会出现一条只有「第 N 轮」的空壳。
                if (thinking.isNotEmpty() || actions.isNotEmpty()) {
                    steps += Step(
                        round = round,
                        thinking = if (thinking.length > MAX_THINKING_CHARS) {
                            thinking.take(MAX_THINKING_CHARS) +
                                "\n…（共 ${thinking.length} 字，已省略后半）"
                        } else {
                            thinking
                        },
                        actions = actions,
                    )
                }
            },
            execute = { call -> AiTools.execute(wxId, call, memoryPager = memoryPager) },
        )
        // 既没有正文也没有错误 = 调用方已经不需要结果（shouldStop 命中），不是失败：
        // 返回空结果让它自然丢弃，不当成「模型返回为空」报给用户。
        if (outcome.text == null && outcome.error == null) return Suggestion(steps = steps)
        outcome.error?.let { message ->
            // 带图时把话说明白：最常见的失败是模型不支持图片输入，关掉开关即可。
            val hint = if (imageUrls.isNotEmpty()) "（本次附带了图片，可能是模型不支持图片输入）" else ""
            return Suggestion(error = message + hint, steps = steps)
        }
        return parse(outcome.text.orEmpty()).copy(steps = steps)
    }

    /**
     * 从记录里挑几文本人发言当样本。
     *
     * 只要文字：`[图片]`「[表情包]」这种占位符长度正好落在 3~40 之间，
     * 不排除的话会被当成「他平时这么说话」，反而教模型去发占位符。
     */
    private fun styleSamples(lines: List<AiChatHistory.Line>): String =
        lines.asReversed()
            .filter { it.fromMe && it.isText }
            .map { it.text.trim() }
            .filter { it.length in 3..40 }
            .distinct()
            .take(10)
            .toList()
            .asReversed()
            .joinToString("\n") { "- $it" }

    /** 把工具调用翻成一句人话（不带「正在」），思考过程里直接当动作条目用。 */
    private fun actionText(call: AiConfig.ToolCall): String = when (call.name) {
        AiTools.READ_MEMORY -> {
            val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
                .getOrNull()
            // 参数名是 numbers（记忆序号）。这里曾经读 items，是改成序号方案前的旧字段，
            // 结果 count 恒为 0，状态永远只显示「正在查阅事件详情…」。
            val count = runCatching { args?.get("numbers")?.jsonArray?.size ?: 0 }
                .getOrDefault(0)
            val keyword = args?.get("keyword")?.jsonPrimitive?.contentOrNull.orEmpty()
            when {
                count > 0 -> "查阅 $count 条事件详情"
                keyword.isNotBlank() -> "检索「" + keyword.take(12) + "」相关记忆"
                else -> "查阅事件详情"
            }
        }

        AiTools.GET_CHAT_HISTORY -> "翻更早的聊天记录"
        AiTools.LOAD_EARLIER_MEMORIES -> "翻更早的记忆"
        else -> "调用 " + call.name
    }

    // ── 解析 ────────────────────────────────────────────────────────────────

    /**
     * 回复压成**一行**。
     *
     * 微信按宽度自己折行，模型再写 `\n` 就会发出去一个空行。句子之间用一个空格接上即可：
     * 换行、制表、连续空格、全角空白都折成一个半角空格，首尾不留空白。
     */
    fun oneLine(text: String): String = buildString {
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace()) {
                // 开头不补空格：isNotEmpty() 为 false 时保持 false。
                if (isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) append(' ')
                pendingSpace = false
                append(ch)
            }
        }
    }

    /**
     * 解析 `{"read":"…","replies":["…","…","…"]}`。
     *
     * 容错顺序：直接解析 → 去掉代码块围栏再解析 → 截取第一个 `{` 到最后一个 `}`
     * → 都失败就把原文当解读（模型往往只是没按 JSON 输出，内容本身可用）。
     */
    private fun parse(raw: String): Suggestion {
        if (raw.isBlank()) return Suggestion(error = "模型返回为空")
        val trimmed = raw.trim()

        candidateObjects(trimmed).forEach { candidate ->
            val parsed = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull()
                ?: return@forEach
            val read = parsed["read"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val replies = runCatching {
                parsed["replies"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                        ?.let(::oneLine)
                        ?.takeIf { text -> text.isNotEmpty() }
                }.orEmpty()
            }.getOrElse { emptyList() }
            if (read.isNotBlank() || replies.isNotEmpty()) {
                return Suggestion(read = read, replies = replies.take(3))
            }
        }

        WeLogger.d(TAG, "返回不是预期 JSON，按纯文本处理：" + trimmed.take(80))
        return Suggestion(read = stripFence(trimmed).take(120), replies = emptyList())
    }

    private fun stripFence(raw: String): String = raw.trim()
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```").trim()

    private fun candidateObjects(raw: String): List<String> {
        val out = mutableListOf<String>()
        out += raw
        val stripped = stripFence(raw)
        if (stripped != raw) out += stripped
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) out += raw.substring(start, end + 1)
        return out
    }
}
