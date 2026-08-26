package com.Johnny.wcx.agent.engine

import com.Johnny.wcx.agent.data.entity.ApprovalStatus
import com.Johnny.wcx.agent.data.entity.ConditionalPromptEntity
import com.Johnny.wcx.agent.model.LlmClient
import com.Johnny.wcx.agent.model.LlmMessage
import com.Johnny.wcx.agent.model.LlmRole
import com.Johnny.wcx.agent.model.LlmStreamEvent
import com.Johnny.wcx.agent.model.LlmToolCall
import com.Johnny.wcx.agent.model.LlmToolSpec
import com.Johnny.wcx.agent.tool.ToolLoadingMode
import com.Johnny.wcx.agent.tool.ToolRegistry
import com.Johnny.wcx.agent.tool.WireTool
import com.Johnny.wcx.agent.ui.UiImageSink
import com.Johnny.wcx.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Everything the engine needs to run one turn, resolved by the caller (WeAgentService) from the
 * session's model + the current prompt/settings state.
 */
class TurnConfig(
    val client: LlmClient,
    val modelIdRemote: String,
    val reasoningEffort: String?,
    val customJsonOverride: JsonObject?,
    /** The session's bound system prompt content, or null. */
    val systemPromptContent: String?,
    /** Globally-enabled per-turn prompt contents (prepended to each user message). */
    val perTurnPrompts: List<String>,
    /** Globally-enabled conditional prompts (regex-matched against each reply). */
    val conditionalPrompts: List<ConditionalPromptEntity>,
    val toolLoadingMode: ToolLoadingMode,
    val maxModelRequests: Int,
    /** Per-model max output tokens, or null to omit the field (provider default). */
    val maxTokens: Int? = null,
    /**
     * Queue-after-turn steer-hook: called by the engine at the top of every while-loop iteration. If
     * non-null and returning a non-blank string, the engine injects it as a transient USER message
     * before the next model request (not persisted). The callback should consume the message
     * atomically so each steer fires once.
     */
    val onFetchSteerMessage: (() -> String?)? = null,
)

/**
 * The Agent Loop (§2). Given the prior conversation and a new user message, it repeatedly calls the
 * model, executes any tool calls it returns (gated by [ApprovalGateway] and persisted via
 * [historySink]), feeds results back, and loops until the model returns no tool call, an error
 * occurs, or the per-turn request cap is hit.
 *
 * The loop is transport-agnostic: it works in provider-neutral [LlmMessage] space and delegates
 * wire translation to the [LlmClient]. It emits [AgentEvent]s for the UI as a cold [Flow]; cancel
 * the collecting coroutine to abort the turn.
 */
class AgentSessionEngine(
    private val registry: ToolRegistry,
    private val approvalGateway: ApprovalGateway,
    private val promptComposer: PromptComposer,
    private val historySink: HistorySink,
) {
    /**
     * Persists conversation state as the loop advances. Implemented over Room by WeAgentService;
     * kept as an interface so the engine has no direct DB dependency and stays testable.
     */
    interface HistorySink {
        suspend fun onAssistantMessage(content: String?, reasoning: String?, reasoningSignature: String?, toolCalls: List<LlmToolCall>)
        suspend fun onToolResult(callId: String, toolName: String, providerId: String, argumentsJson: String, resultText: String, status: ApprovalStatus)
        suspend fun onUserMessage(content: String)
    }

    /**
     * Runs one turn. [priorMessages] is the full provider-neutral history (excluding the system
     * message, which is composed here). [userMessage] is the raw new user input.
     *
     * Only the raw [userMessage] is persisted/displayed; the per-turn prompt prefix (§6) is applied
     * transiently to the copy sent to the model, so it never compounds across reloads. The previous
     * assistant reply is NOT re-prepended — it is already present in [priorMessages].
     */
    fun runTurn(
        config: TurnConfig,
        priorMessages: List<LlmMessage>,
        userMessage: String,
    ): Flow<AgentEvent> = channelFlow {
        try {
            val systemMessage = promptComposer.composeSystemMessage(config.systemPromptContent)
            // Persist/display the raw user message; the model gets the per-turn-augmented copy.
            historySink.onUserMessage(userMessage)
            val sentUserText = promptComposer.composeTurnUserMessage(config.perTurnPrompts, userMessage)

            // Working message list for this turn: [system] + prior + this user message.
            val messages = ArrayList<LlmMessage>()
            messages += LlmMessage(role = LlmRole.SYSTEM, content = systemMessage)
            messages += priorMessages
            messages += LlmMessage(role = LlmRole.USER, content = sentUserText)

            // Per-turn dynamic-discovery state (exposed tool names discovered so far).
            val discovered = LinkedHashSet<String>()

            var requestIndex = 0

            while (true) {
                currentCoroutineContext().ensureActive()

                // Steer-hook: inject a transient user message from the queued-mechanism before the
                // next API request (not persisted — purely ephemeral steering input).
                val steerText = config.onFetchSteerMessage?.invoke()?.takeIf { it.isNotBlank() }
                if (steerText != null) {
                    messages += LlmMessage(role = LlmRole.USER, content = steerText)
                    // The raw text isn't persisted to the history (it's steering, not a real user
                    // utterance), but the model will respond to it.
                }

                if (requestIndex >= config.maxModelRequests) {
                    send(AgentEvent.MaxRequestsReached(config.maxModelRequests))
                    return@channelFlow
                }
                requestIndex++
                send(AgentEvent.RequestStarted(requestIndex))

                val wireTools = registry.requestTools(config.toolLoadingMode, discovered)
                val request = com.Johnny.wcx.agent.model.LlmRequest(
                    modelIdRemote = config.modelIdRemote,
                    messages = messages.toList(),
                    tools = wireTools.map { it.toSpec() },
                    reasoningEffort = config.reasoningEffort,
                    customJsonOverride = config.customJsonOverride,
                    maxTokens = config.maxTokens,
                    stream = true,
                )

                // Stream one model response.
                val textBuf = StringBuilder()
                val reasoningBuf = StringBuilder()
                var completed: LlmStreamEvent.Completed? = null
                var failure: Throwable? = null

                config.client.stream(request).collect { ev ->
                    when (ev) {
                        is LlmStreamEvent.TextDelta -> {
                            textBuf.append(ev.text); send(AgentEvent.TextDelta(ev.text))
                        }

                        is LlmStreamEvent.ReasoningDelta -> {
                            reasoningBuf.append(ev.text); send(AgentEvent.ReasoningDelta(ev.text))
                        }

                        is LlmStreamEvent.Completed -> completed = ev
                        is LlmStreamEvent.Failed -> failure = ev.error
                    }
                }

                val err = failure
                if (err != null) {
                    send(AgentEvent.TurnFailed(err))
                    return@channelFlow
                }
                completed?.usage?.let { send(AgentEvent.UsageUpdated(it)) }
                val assistant = completed?.message
                    ?: LlmMessage(role = LlmRole.ASSISTANT, content = textBuf.toString().ifEmpty { null })

                messages += assistant
                historySink.onAssistantMessage(assistant.content, assistant.reasoning, assistant.reasoningSignature, assistant.toolCalls)

                // No tool calls -> round over, turn ends (§2.1).
                if (assistant.toolCalls.isEmpty()) {
                    // Conditional prompts (§6): if any regex matches, append a system-reminder user
                    // message and continue the loop with another request.
                    val reminders = promptComposer.matchConditionalPrompts(
                        config.conditionalPrompts, assistant.content.orEmpty()
                    )
                    if (reminders.isNotEmpty() && requestIndex < config.maxModelRequests) {
                        reminders.forEach { messages += LlmMessage(role = LlmRole.USER, content = it) }
                        continue
                    }
                    send(AgentEvent.TurnCompleted(assistant.content))
                    return@channelFlow
                }

                // Execute each tool call, appending a TOOL message per call.
                for (call in assistant.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    send(AgentEvent.ToolCallStarted(call.id, call.name, call.argumentsJson))
                    val (text, status, providerId) = executeToolCall(call, assistant.content, discovered) { toolName ->
                        send(AgentEvent.ToolAwaitingApproval(call.id, toolName))
                    }
                    messages += LlmMessage(role = LlmRole.TOOL, content = text, toolCallId = call.id)
                    historySink.onToolResult(call.id, call.name, providerId, call.argumentsJson, text, status)
                    send(AgentEvent.ToolCallFinished(call.id, call.name, status, text))
                }

                // Vision: a tool (ui-screenshot) may have staged images into the UiImageSink this
                // round. Inject them as a transient USER message so the model sees them next request.
                // These are intentionally NOT persisted via historySink — screenshots are heavy and
                // only relevant to the live turn (mirrors how Anthropic thinking blocks aren't replayed).
                val stagedImages = currentCoroutineContext()[UiImageSink]?.drain().orEmpty()
                if (stagedImages.isNotEmpty()) {
                    messages += LlmMessage(
                        role = LlmRole.USER,
                        content = "以下是刚才工具捕获的界面截图（${stagedImages.size} 张）：",
                        images = stagedImages,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            WeLogger.e(TAG, "turn failed", e)
            send(AgentEvent.TurnFailed(e))
        }
    }

    /**
     * Handles one tool call end-to-end: the discover_tools meta-tool, permission gating, and
     * execution. Returns (resultText, approvalStatus, providerId).
     */
    private suspend fun executeToolCall(
        call: LlmToolCall,
        modelExplanation: String?,
        discovered: MutableSet<String>,
        onAwaitingApproval: suspend (String) -> Unit,
    ): Triple<String, ApprovalStatus, String> {
        val args = parseArgs(call.argumentsJson)

        // discover_tools meta-tool (§3.3): handled by the engine, always allowed.
        if (call.name == ToolRegistry.DISCOVER_TOOLS_NAME) {
            val text = ToolDiscovery.handle(registry, args, discovered)
            return Triple(text, ApprovalStatus.AUTO_ALLOWED, "builtin")
        }

        val tool = registry.findByExposedName(call.name)
            ?: return Triple("Unknown tool: ${call.name}", ApprovalStatus.AUTO_ALLOWED, "")

        if (tool.mode == com.Johnny.wcx.agent.tool.ToolMode.MANUAL_APPROVAL) onAwaitingApproval(call.name)

        val decision = approvalGateway.decide(
            mode = tool.mode,
            toolName = tool.exposedName,
            providerName = tool.provider.name,
            argumentsJson = call.argumentsJson,
            modelExplanation = modelExplanation,
        )

        return when (decision) {
            is ApprovalDecision.Allowed -> {
                val status = when (tool.mode) {
                    com.Johnny.wcx.agent.tool.ToolMode.MANUAL_APPROVAL -> ApprovalStatus.USER_APPROVED
                    com.Johnny.wcx.agent.tool.ToolMode.SMART_APPROVAL -> ApprovalStatus.AI_APPROVED
                    else -> ApprovalStatus.AUTO_ALLOWED
                }
                val result = runCatching { registry.execute(tool, args) }
                    .getOrElse { "工具执行失败：${it.message ?: it.javaClass.simpleName}" }
                Triple(result, status, tool.provider.id)
            }

            is ApprovalDecision.Denied -> {
                val status = if (decision.bySmartReview) ApprovalStatus.AI_REJECTED else ApprovalStatus.USER_REJECTED
                Triple(approvalGateway.deniedResultText(decision), status, tool.provider.id)
            }
        }
    }

    private fun parseArgs(argumentsJson: String): JsonObject =
        runCatching { com.Johnny.wcx.agent.model.LlmJson.json.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    private fun WireTool.toSpec(): LlmToolSpec =
        LlmToolSpec(name = exposedName, description = description, parametersSchema = jsonSchema)

    private companion object {
        const val TAG = "AgentSessionEngine"
    }
}
