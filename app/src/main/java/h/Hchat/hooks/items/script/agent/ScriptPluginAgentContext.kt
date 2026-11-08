package h.Hchat.hooks.items.script.agent

import org.json.JSONArray
import org.json.JSONObject

object ScriptPluginAgentContext {
    /** The next assistant reply may reuse this empty UI placeholder; it is not summarized yet. */
    fun compactionBoundary(messages: List<ScriptPluginAgentChatMessage>): Int {
        val last = messages.lastOrNull()
        val reusablePlaceholder = last?.role == "assistant" && last.status == "streaming" &&
            last.streamId.isBlank() && last.content.isBlank() && last.reasoning.isBlank() && last.toolEvents.isEmpty()
        return messages.size - if (reusablePlaceholder) 1 else 0
    }

    fun modelMessagesForTurn(
        messages: List<ScriptPluginAgentChatMessage>,
        currentTurnId: String
    ): List<ScriptPluginAgentChatMessage> {
        return messages.mapNotNull { message ->
            if (message.role != "tool" || message.turnId == currentTurnId) {
                return@mapNotNull message
            }
            val retainedEvents = message.toolEvents.filterNot { event ->
                event.kind == "workspace" || event.protocolName.startsWith("hchat_workspace_")
            }
            when {
                retainedEvents.isEmpty() -> null
                retainedEvents.size == message.toolEvents.size -> message
                else -> message.copy(toolEvents = retainedEvents)
            }
        }
    }

    fun estimateTokens(
        summary: String,
        messages: List<ScriptPluginAgentChatMessage>,
        draft: ScriptPluginAgentDraft?,
        nativeToolHistory: String = "",
        protocolTranscript: String = ""
    ): Int {
        val hasProtocolTranscript = protocolTranscript.isNotBlank() &&
            ScriptPluginAgentProtocolTranscript.isValid(protocolTranscript)
        var tokens = if (hasProtocolTranscript) {
            estimateProtocolTokens(protocolTranscript)
        } else {
            estimateTextTokens(summary) + estimateTextTokens(nativeToolHistory)
        }
        val pendingMessages = if (hasProtocolTranscript) {
            val pendingUserIndex = messages.indexOfLast { message ->
                message.role == "user" &&
                    !ScriptPluginAgentProtocolTranscript.containsMessage(protocolTranscript, message.id)
            }
            if (pendingUserIndex >= 0) messages.drop(pendingUserIndex) else emptyList()
        } else {
            messages
        }
        pendingMessages.forEach { message ->
            tokens += estimateTextTokens(message.content) + estimateTextTokens(message.diff) + 8
            tokens += estimateTextTokens(message.quotedMessage?.content.orEmpty())
            if (!hasProtocolTranscript && nativeToolHistory.isBlank()) message.toolEvents.forEach { event ->
                tokens += estimateTextTokens(event.name) + estimateTextTokens(event.arguments) +
                    estimateTextTokens(event.result) + estimateTextTokens(event.diff)
            }
            message.attachments.forEach { attachment ->
                tokens += estimateTextTokens(attachment.name) + if (attachment.mimeType.startsWith("image/")) {
                    4_000
                } else {
                    (attachment.size.coerceIn(1_000L, 512L * 1024L) / 2L).toInt()
                }
            }
        }
        if (draft != null) {
            tokens += estimateTextTokens(draft.pluginId + draft.pluginName + draft.summary) + 64
        }
        return tokens.coerceAtLeast(1)
    }

    /** A conservative estimate, not provider usage; CJK must not be counted as ASCII / 4. */
    fun estimateTextTokens(text: String): Int {
        var ascii = 0L
        var nonAscii = 0L
        text.forEach { if (it.code < 128) ascii++ else nonAscii++ }
        return ((ascii + 2) / 3 + nonAscii * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun estimateProtocolTokens(encoded: String): Int {
        val messages = ScriptPluginAgentProtocolTranscript.providerMessages(encoded)
        var tokens = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val content = message.opt("content")
            val metadata = JSONObject(message.toString()).apply { remove("content") }
            tokens += estimateTextTokens(metadata.toString()) + 8
            if (content !is JSONArray) {
                tokens += estimateTextTokens(content?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty())
                continue
            }
            for (partIndex in 0 until content.length()) {
                val part = content.optJSONObject(partIndex)
                tokens += when (part?.optString("type")) {
                    "image", "image_url", "input_image" -> 4_000
                    else -> estimateTextTokens(content.opt(partIndex)?.toString().orEmpty())
                }
            }
        }
        return tokens
    }

    fun shouldCompact(estimatedTokens: Int, threshold: Int, lastAttemptTokens: Int): Boolean {
        if (estimatedTokens < threshold) return false
        // An unchanged failed request must keep its prefix and must not retry compaction forever.
        return lastAttemptTokens < 0 ||
            estimatedTokens.toLong() >= lastAttemptTokens.toLong() + (threshold / 4).coerceAtLeast(500)
    }
}
