package com.Johnny.wcx.ai

import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.JsonArray

/**
 * 「模型 + 工具」多轮对话的公共骨架：问 → 执行工具 → 回填结果 → 再问 → 到上限收口。
 *
 * 审查与建议原来是两份逐行同构的循环，差异只有超时、温度与结果怎么解析。抄两遍的直接后果
 * 是参数已经悄悄分叉（收口时间 15s / 10s、剩余预算门槛 20s / 15s）—— 那不是设计，
 * 是两次手写时漏掉的。这里只留一份，两边用 [Limits] 表达差异。
 *
 * **不碰提示词、不解析结果、不写档案**：这些由调用方各自负责，循环只负责轮次与预算。
 */
object AiAgentLoop {

    private const val TAG = "AiAgentLoop"

    /**
     * 一次循环的参数。
     *
     * 审查要读 100 条记忆详情，单轮时间比交互式建议长得多；所以「单轮多久」「多久算跑不完」
     * 必须由调用方给，不能在这里写死。
     */
    data class Limits(
        val maxRounds: Int,
        val singleTimeoutMs: Long,
        val totalBudgetMs: Long,
        /** 剩余预算低于这个值就不再开新的一轮（开了也跑不完）。 */
        val minRoundMs: Long,
        /** 收口请求至少给这么多时间：预算刚好用完也要有机会把结论说出来。 */
        val minClosingMs: Long,
    )

    /**
     * @param messages 会被就地追加 assistant 与 tool 消息；最终结论在返回的 [Outcome.text]。
     * @param dumpPath debug 落盘路径（[AiDebug.dumpPrompt] 的返回值），null 表示不记录。
     * @param logTag 日志前缀，保留调用方的 TAG，便于按原有关键字检索。
     * @param onRoundStart 每轮开始（界面可据此显示「正在核对…」）。
     * @param onToolStart 执行每个工具调用之前（界面可据此显示「正在查阅 3 条事件详情…」）。
     * @param onRound 每一轮拿到响应后（含最后的收口轮）。界面用它记录「这一轮想了什么、做了什么」。
     * @param onClosing 发收口请求之前。
     * @param conclusionReady 结论是否已经就绪：**每轮工具执行后**、以及收口请求之前都会问一次。
     *        返回 true 表示调用方自己已经拿到要的结果（审查：提案已落盘），立即结束、不再请求模型。
     *        实测模型在 `propose_audit` 之后还会再回一段「删了 X 个标签」的总结，
     *        那一轮要把整段上下文重发一遍，对结果没有任何影响。
     * @param shouldStop 调用方是否已经不需要这次结果（浮层被关掉、用户点了刷新）。
     *        请求本身是阻塞调用，取消协程只在它返回之后才生效 —— 没有这个钩子，
     *        关掉卡片之后模型仍会把剩下几轮跑满（单轮最长 45s、总共 8 分钟）。
     *        检查点三处：每轮开始前、响应带回工具调用后（工具还没执行）、收口请求前。
     */
    fun run(
        wxId: String,
        messages: MutableList<AiConfig.ChatMessage>,
        tools: JsonArray?,
        temperature: Double,
        limits: Limits,
        dumpPath: String? = null,
        logTag: String = TAG,
        onRoundStart: (Int) -> Unit = {},
        onToolStart: (AiConfig.ToolCall) -> Unit = {},
        onRound: (Int, AiConfig.ChatResult) -> Unit = { _, _ -> },
        onClosing: () -> Unit = {},
        conclusionReady: () -> Boolean = { false },
        shouldStop: () -> Boolean = { false },
        execute: (AiConfig.ToolCall) -> String,
    ): Outcome {
        val deadline = System.currentTimeMillis() + limits.totalBudgetMs
        var round = 0

        while (round < limits.maxRounds) {
            if (shouldStop()) {
                WeLogger.d(logTag, "$wxId 调用方已不需要结果，第 $round 轮前停止")
                return Outcome(text = null, error = null, rounds = round, closed = true)
            }
            val remain = deadline - System.currentTimeMillis()
            // 预算不够再跑一轮就提前收口：开一轮要几十秒到几分钟，超时的结果也用不上。
            if (remain < limits.minRoundMs) {
                WeLogger.i(logTag, "$wxId 剩余预算 ${remain / 1000}s，提前收口")
                break
            }
            round++
            onRoundStart(round)
            // 工具结果越攒越多时先折掉最早的几批，避免第 N 轮请求体无限膨胀。
            AiTools.trimToolOutputs(messages)
            val result = AiConfig.chatWithTools(
                messages = messages,
                tools = tools,
                temperature = temperature,
                // 单轮超时同时受总预算约束，避免最后一轮把总时长拖出去。
                readTimeoutMs = minOf(limits.singleTimeoutMs, remain).toInt(),
            )
            AiDebug.appendResponse(dumpPath, result, round)
            onRound(round, result)
            result.error?.let { return Outcome(text = null, error = it, rounds = round, closed = false) }
            if (!result.hasToolCalls) {
                return Outcome(text = result.text, error = null, rounds = round, closed = false)
            }
            // 调用方已经不要结果了：这一轮的工具（`read_memory` 一次要读上百条记忆）不必再执行。
            // 响应是在请求返回后才知道有没有工具调用的，所以这里是「能省的第一时间点」。
            if (shouldStop()) {
                WeLogger.d(logTag, "$wxId 调用方已不需要结果，跳过第 $round 轮的工具调用")
                return Outcome(text = null, error = null, rounds = round, closed = true)
            }

            messages += AiConfig.ChatMessage(
                role = "assistant",
                content = result.text,
                toolCalls = result.toolCalls,
            )
            result.toolCalls.forEach { call ->
                onToolStart(call)
                val output = execute(call)
                messages += AiConfig.ChatMessage(
                    role = "tool",
                    content = output,
                    toolCallId = call.id,
                )
            }
            WeLogger.d(logTag, "$wxId 第 $round 轮：${result.toolCalls.size} 次工具调用")
            // 结论已就绪就不再开下一轮：模型提交完建议后还会再回一段总结，
            // 那一轮要把整段上下文重发一遍，对结果没有任何影响。
            if (conclusionReady()) {
                return Outcome(text = null, error = null, rounds = round, closed = true)
            }
        }

        if (conclusionReady()) {
            return Outcome(text = null, error = null, rounds = round, closed = true)
        }
        // 收口这一发也是完整上下文 + 几十秒，调用方不要结果就不要发。
        if (shouldStop()) {
            WeLogger.d(logTag, "$wxId 调用方已不需要结果，跳过收口请求")
            return Outcome(text = null, error = null, rounds = round, closed = true)
        }

        // 轮数或预算用尽：不给工具再要一次，强制它把结论说完。
        onClosing()
        AiTools.trimToolOutputs(messages)
        val closingBudget = (deadline - System.currentTimeMillis()).coerceAtLeast(limits.minClosingMs)
        val closing = AiConfig.chatWithTools(
            messages = messages,
            tools = null,
            temperature = temperature,
            readTimeoutMs = minOf(limits.singleTimeoutMs, closingBudget).toInt(),
        )
        AiDebug.appendResponse(dumpPath, closing, round + 1)
        onRound(round + 1, closing)
        return Outcome(
            text = closing.text,
            error = closing.error,
            rounds = round + 1,
            closed = true,
        )
    }

    /**
     * @param closed true 表示没有拿到「模型自己说完」的正文：要么是轮数 / 预算用尽后的收口，
     *        要么是 [run] 的 `conclusionReady` 提前结束（结论由调用方自己持有）。
     * @param text 提前结束或收口请求被挡掉时为 null。
     */
    data class Outcome(
        val text: String?,
        val error: String?,
        val rounds: Int,
        val closed: Boolean,
    )
}
