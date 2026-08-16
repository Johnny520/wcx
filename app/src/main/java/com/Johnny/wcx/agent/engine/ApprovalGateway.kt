package com.Johnny.wcx.agent.engine

import com.Johnny.wcx.agent.model.LlmMessage
import com.Johnny.wcx.agent.model.LlmRequest
import com.Johnny.wcx.agent.model.LlmRole
import com.Johnny.wcx.agent.model.LlmStreamEvent
import com.Johnny.wcx.agent.tool.ToolMode
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Outcome of an approval decision for a single tool call. */
sealed interface ApprovalDecision {
    object Allowed : ApprovalDecision

    /** Denied. [reason] explains why; [bySmartReview] distinguishes AI review from a user rejection (§2.2). */
    data class Denied(val reason: String?, val bySmartReview: Boolean) : ApprovalDecision
}

/** A pending tool call awaiting a human decision, handed to the UI layer. */
data class PendingApproval(
    val toolName: String,
    val providerName: String,
    val argumentsJson: String,
    /** Optional natural-language explanation the main model emitted alongside the call. */
    val modelExplanation: String?,
)

/** How the user resolved a [PendingApproval] in the UI. */
sealed interface ManualApprovalResult {
    object Approved : ManualApprovalResult

    /** Rejected. [reason] is the optional user-supplied reason (§2.2). */
    data class Rejected(val reason: String?) : ManualApprovalResult
}

/** UI-facing handler the engine calls to obtain a human decision; suspends until the user acts. */
fun interface ManualApprovalHandler {
    suspend fun requestApproval(pending: PendingApproval): ManualApprovalResult
}

/**
 * Resolves a tool call's [ToolMode] into an [ApprovalDecision]. ENABLED allows immediately; DISABLED
 * should never reach here (hidden from the model); MANUAL_APPROVAL suspends on [manualHandler];
 * SMART_APPROVAL fires an independent small-model request (§2.2) that does not share the session
 * context nor count toward its request budget.
 */
class ApprovalGateway(
    private val manualHandler: ManualApprovalHandler,
    private val smallModel: SmallModelRef?,
) {
    suspend fun decide(
        mode: ToolMode,
        toolName: String,
        providerName: String,
        argumentsJson: String,
        modelExplanation: String?,
    ): ApprovalDecision = when (mode) {
        ToolMode.ENABLED -> ApprovalDecision.Allowed
        ToolMode.DISABLED -> ApprovalDecision.Denied("Tool is disabled", bySmartReview = false)
        ToolMode.MANUAL_APPROVAL -> {
            val pending = PendingApproval(toolName, providerName, argumentsJson, modelExplanation)
            when (val res = manualHandler.requestApproval(pending)) {
                is ManualApprovalResult.Approved -> ApprovalDecision.Allowed
                is ManualApprovalResult.Rejected ->
                    ApprovalDecision.Denied(res.reason, bySmartReview = false)
            }
        }

        ToolMode.SMART_APPROVAL -> smartReview(toolName, argumentsJson, modelExplanation)
    }

    /**
     * Builds the tool-result text returned to the main model for a denied call, distinguishing the
     * origin of the reason (§2.2).
     */
    fun deniedResultText(decision: ApprovalDecision.Denied): String = when {
        decision.bySmartReview ->
            "工具调用被拒绝：${decision.reason ?: "未给出理由"}"

        decision.reason != null ->
            "工具调用被用户拒绝。用户给出的理由：${decision.reason}"

        else ->
            "工具调用被用户拒绝，用户未说明理由。"
    }

    private suspend fun smartReview(
        toolName: String,
        argumentsJson: String,
        modelExplanation: String?,
    ): ApprovalDecision {
        val model = smallModel ?: run {
            WeLogger.w(TAG, "smart approval configured but no small model; denying")
            return ApprovalDecision.Denied(
                "用户为该工具启用了「智能审批」但未配置审批小模型。请让用户在 WeAgent 设置中配置审批小模型或切换审批模式。",
                bySmartReview = true
            )
        }

        val prompt = buildString {
            append("你是一个 LLM 工具调用安全审查员。根据下面的信息，判断是否允许执行该工具调用。\n")
            append("只输出一个严格的 JSON 对象，格式为 {\"allow\": bool, \"reason\": string}，不要输出任何其他内容。\n\n")
            append("工具名：$toolName\n")
            append("入参：$argumentsJson\n")
            if (!modelExplanation.isNullOrBlank()) append("调用该工具 LLM 的解释：$modelExplanation\n")
        }

        val request = LlmRequest(
            modelIdRemote = model.modelIdRemote,
            messages = listOf(LlmMessage(role = LlmRole.USER, content = prompt)),
            tools = emptyList(),
            reasoningEffort = model.reasoningEffort,
            maxTokens = model.maxTokens,
            customJsonOverride = null,
            stream = false,
        )

        val text = runCatching {
            val events = model.client.stream(request).toList()
            (events.lastOrNull { it is LlmStreamEvent.Completed } as? LlmStreamEvent.Completed)
                ?.message?.content
                ?: (events.firstOrNull { it is LlmStreamEvent.Failed } as? LlmStreamEvent.Failed)
                    ?.let { throw it.error }
        }.getOrElse {
            WeLogger.e(TAG, "smart review request failed", it)
            return ApprovalDecision.Denied("审批小模型请求失败：${it.message}", bySmartReview = true)
        } ?: return ApprovalDecision.Denied("审批小模型无返回", bySmartReview = true)

        return parseDecision(text)
    }

    private fun parseDecision(text: String): ApprovalDecision {
        // Extract the first {...} block to tolerate stray prose around the JSON.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start !in 0..<end) {
            return ApprovalDecision.Denied("审批小模型返回无法解析：$text", bySmartReview = true)
        }
        val obj = runCatching {
            com.Johnny.wcx.agent.model.LlmJson.json
                .parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrElse {
            return ApprovalDecision.Denied("审批小模型返回无法解析：$text", bySmartReview = true)
        }
        val allow = runCatching { obj["allow"]?.jsonPrimitive?.let { it.booleanOrNull ?: it.content.toBoolean() } }.getOrNull() ?: false
        val reason = runCatching { obj["reason"]?.jsonPrimitive?.content }.getOrNull()
        return if (allow) ApprovalDecision.Allowed
        else ApprovalDecision.Denied(reason ?: "审批未通过", bySmartReview = true)
    }

    companion object {
        private const val TAG = "ApprovalGateway"
    }
}

/** A resolved small model for smart approval / title generation (§5.4). */
data class SmallModelRef(
    val client: com.Johnny.wcx.agent.model.LlmClient,
    val modelIdRemote: String,
    val reasoningEffort: String?,
    val maxTokens: Int? = null,
)
