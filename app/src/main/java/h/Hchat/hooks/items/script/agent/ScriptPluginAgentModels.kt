package h.Hchat.hooks.items.script.agent

import java.util.UUID

object ScriptPluginAgentEventIds {
    fun toolGroup(turnId: String, parentAssistantMessageId: String): String {
        return "tool-group:$turnId:$parentAssistantMessageId"
    }
}

data class ScriptPluginAgentConfig(
    val apiBaseUrl: String,
    val apiPath: String,
    val apiKey: String,
    val model: String,
    val mcpServers: List<ScriptPluginAgentMcpServer> = emptyList(),
    val autoCompactEnabled: Boolean = true,
    val compactTokenThreshold: Int = 24_000,
    val webSearchEnabled: Boolean = true,
    val workspaceWriteApprovalMode: String = ScriptPluginAgentSettings.WRITE_APPROVAL_ASK,
    val promptCacheMode: String = ScriptPluginAgentSettings.DEFAULT_PROMPT_CACHE_MODE,
    val endpointMode: String = ScriptPluginAgentSettings.ENDPOINT_MODE_OPENAI_COMPATIBLE,
    val reasoningEffort: String = "default"
)

data class ScriptPluginAgentMcpServer(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val endpoint: String = "",
    val authorization: String = ""
)

data class ScriptPluginAgentProfile(
    val id: String,
    val name: String,
    val config: ScriptPluginAgentConfig
)

data class ScriptPluginAgentAttachment(
    val name: String,
    val path: String,
    val mimeType: String,
    val size: Long,
    val sourceUri: String = ""
)

data class ScriptPluginAgentDraft(
    val pluginName: String,
    val pluginId: String,
    val infoProp: String,
    val mainJava: String,
    val summary: String
)

data class ScriptPluginAgentWorkspaceChange(
    val pluginId: String,
    val pluginName: String,
    val stagingPath: String,
    val existed: Boolean,
    val baseFingerprint: String,
    val createdPaths: List<String>,
    val modifiedPaths: List<String>,
    val deletedPaths: List<String>,
    val diff: String,
    val draft: ScriptPluginAgentDraft?,
    val basePathStates: Map<String, String> = emptyMap(),
    val deletePlugin: Boolean = false,
    val warnings: List<ScriptPluginAgentIssue> = emptyList()
)

data class ScriptPluginAgentExisting(
    val pluginId: String,
    val pluginName: String,
    val infoProp: String,
    val mainJava: String
)

data class ScriptPluginAgentToolEvent(
    val id: String,
    val kind: String,
    val name: String,
    val arguments: String = "",
    val result: String = "",
    val diff: String = "",
    val status: String = "running",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val progress: String = "",
    val turnId: String = "",
    val toolCallId: String = "",
    val protocolName: String = "",
    val providerMetadata: String = "",
    val parentAssistantMessageId: String = "",
    val resultHandle: String = "",
    val resultLength: Int = 0,
    val truncated: Boolean = false,
    val nextOffset: Int = 0
)

data class ScriptPluginAgentWorkspaceToolConfirmation(
    val eventId: String,
    val toolName: String,
    val pluginId: String,
    val diff: String
)

enum class ScriptPluginAgentWorkspaceWriteDecision {
    CANCEL,
    APPROVE_ONCE,
    ALWAYS_ALLOW
}

data class ScriptPluginAgentNativeToolCall(
    val id: String,
    val protocolName: String,
    val kind: String,
    val originalName: String,
    val arguments: String,
    val providerMetadata: String = ""
)

data class ScriptPluginAgentQuotedMessage(
    val role: String,
    val content: String,
    val createdAt: Long
)

data class ScriptPluginAgentWorkspaceCheckpoint(
    val stagingPath: String,
    val pluginId: String,
    val existed: Boolean,
    val baseFingerprint: String,
    val stageFingerprint: String,
    val basePathStates: Map<String, String> = emptyMap(),
    val initialPluginName: String = pluginId,
    val revision: Int = 0,
    val checkedRevision: Int = -1,
    val shownRevision: Int = -1,
    val deletePlugin: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

data class ScriptPluginAgentResumeState(
    val turnId: String,
    val sourceUserMessageId: String,
    val taskGoal: String = "",
    val workContext: String = "",
    val workspaceCheckpoint: ScriptPluginAgentWorkspaceCheckpoint? = null,
    val autoOpen: Boolean = true,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = startedAt
)

data class ScriptPluginAgentChatMessage(
    val role: String,
    val content: String,
    val id: String = UUID.randomUUID().toString(),
    val turnId: String = "",
    val parentMessageId: String = "",
    val phase: String = role,
    val progress: String = "",
    val reasoning: String = "",
    val diff: String = "",
    val toolEvents: List<ScriptPluginAgentToolEvent> = emptyList(),
    val attachments: List<ScriptPluginAgentAttachment> = emptyList(),
    val quotedMessage: ScriptPluginAgentQuotedMessage? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "complete",
    val draftSnapshot: ScriptPluginAgentDraft? = null,
    val clearsDraft: Boolean = false,
    val streamId: String = "",
    val completedAt: Long = 0L
)

data class ScriptPluginAgentRequest(
    val existing: ScriptPluginAgentExisting?,
    val messages: List<ScriptPluginAgentChatMessage>,
    val currentDraft: ScriptPluginAgentDraft?,
    val targetPluginId: String = "",
    val searchContext: String = "",
    val mcpToolsContext: String = "",
    val mcpResultContext: String = "",
    val conversationSummary: String = "",
    val compactedMessageCount: Int = 0,
    val localFileContext: String = "",
    val localImagePaths: List<String> = emptyList(),
    val localToolsContext: String = "",
    val localToolResultContext: String = "",
    val workspaceToolsContext: String = "",
    val workspaceToolResultContext: String = "",
    val allowedLocalPaths: List<String> = emptyList(),
    val lockedTaskGoal: String = "",
    val agentWorkContext: String = "",
    val workspaceCheckpoint: ScriptPluginAgentWorkspaceCheckpoint? = null,
    /** Structured OpenAI tool-call history. It is sent as protocol messages, never displayed as chat text. */
    val nativeToolHistory: String = "",
    /** Immutable provider-neutral message transcript. New protocol messages are only appended. */
    val protocolTranscript: String = "",
    /** False for a new user turn, true after a tool was called inside the current turn. */
    val nativeToolHistoryAfterCurrentUser: Boolean = false,
    val sessionId: String = "",
    val turnId: String = "",
    /** Enabled Skill metadata only. Instructions are loaded through the Skill tools on demand. */
    val skillsContext: String = ""
)

data class ScriptPluginAgentTurn(
    val status: String,
    val reply: String,
    val draft: ScriptPluginAgentDraft?,
    val progress: String = "",
    val diff: String = "",
    val targetPluginId: String = "",
    val title: String = "",
    val searchQuery: String = "",
    val mcpToolName: String = "",
    val mcpArguments: String = "",
    val localToolName: String = "",
    val localToolArguments: String = "",
    val filePath: String = "",
    val taskGoal: String = "",
    val toolEvents: List<ScriptPluginAgentToolEvent> = emptyList(),
    val nativeToolCallId: String = "",
    val nativeToolCallName: String = "",
    val nativeToolCallArguments: String = "",
    val nativeToolHistory: String = "",
    val protocolTranscript: String = "",
    val nativeToolCalls: List<ScriptPluginAgentNativeToolCall> = emptyList(),
    val workspaceChange: ScriptPluginAgentWorkspaceChange? = null
)

data class ScriptPluginAgentStreamUpdate(
    val reply: String = "",
    val progress: String = "",
    val reasoning: String = "",
    val toolEvents: List<ScriptPluginAgentToolEvent>? = null,
    val phase: String = "assistant",
    val toolEventId: String = "",
    val streamId: String = "",
    val replyRevision: Boolean = false,
    val reasoningRevision: Boolean = false,
    val turnId: String = "",
    val parentMessageId: String = "",
    val resumeState: ScriptPluginAgentResumeState? = null,
    val checkpointNativeToolHistory: String? = null,
    val checkpointProtocolTranscript: String? = null,
    val checkpointConversationSummary: String? = null,
    val checkpointCompactedMessageCount: Int? = null
)

data class ScriptPluginAgentSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ScriptPluginAgentChatMessage>,
    val draft: ScriptPluginAgentDraft?,
    val targetPluginId: String = "",
    val conversationSummary: String = "",
    val nativeToolHistory: String = "",
    val protocolTranscript: String = "",
    val compactedMessageCount: Int = 0,
    val pinned: Boolean = false,
    val locked: Boolean = false,
    val sortOrder: Long = updatedAt,
    val resumeState: ScriptPluginAgentResumeState? = null,
    val checkpointSeq: Long = 0L
)

enum class ScriptPluginAgentIssueLevel {
    ERROR,
    WARNING
}

data class ScriptPluginAgentIssue(
    val level: ScriptPluginAgentIssueLevel,
    val message: String,
    val risky: Boolean = false
)

data class ScriptPluginAgentValidation(
    val issues: List<ScriptPluginAgentIssue>
) {
    val errors: List<ScriptPluginAgentIssue>
        get() = issues.filter { it.level == ScriptPluginAgentIssueLevel.ERROR }
    val warnings: List<ScriptPluginAgentIssue>
        get() = issues.filter { it.level == ScriptPluginAgentIssueLevel.WARNING }
    val canSave: Boolean
        get() = errors.isEmpty()
}
