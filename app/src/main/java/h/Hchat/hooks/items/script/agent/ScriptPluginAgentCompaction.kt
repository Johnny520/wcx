package h.Hchat.hooks.items.script.agent

import org.json.JSONArray
import org.json.JSONObject

/** Summary input only: protocol replay continues to use the untouched stored transcript. */
internal object ScriptPluginAgentCompaction {
    private val omittedProtocolFields = setOf(
        "reasoning", "reasoning_content", "reasoning_details", "thinking", "thinking_blocks",
        "analysis", "signature", "thoughtSignature", "thought_signature", "thinking_signature",
        "reasoning_signature", "encrypted_content", "provider_metadata",
        "extra_content", "hchat_runtime_state", "hchat_cache_control"
    )
    private val reasoningTypes = setOf("reasoning", "thinking", "redacted_thinking", "analysis")
    private val imageDataUri = Regex("data:image/[^\\s;,\"']+;base64,[A-Za-z0-9+/=_-]*", RegexOption.IGNORE_CASE)

    fun chunks(
        messages: List<ScriptPluginAgentChatMessage>,
        protocolTranscript: String = "",
        maxChars: Int = 48_000
    ): List<String> {
        require(maxChars >= 64) { "压缩片段上限至少为 64 字符，以保留消息和片段编号" }
        val protocol = runCatching { JSONArray(protocolTranscript) }.getOrNull()?.takeIf { array ->
            array.length() > 0 && (0 until array.length()).all { array.optJSONObject(it) != null }
        }
        val records = if (protocol != null) {
            protocolRecordsWithSupplements(protocol, messages)
        } else {
            messages.asSequence().map(::chatRecord)
        }
        val output = ArrayList<String>()
        val current = StringBuilder()
        records.forEachIndexed { index, record ->
            // A page cannot outnumber its characters. Reserve space for the longest possible label.
            val capacity = maxChars - pageLabel(index + 1, record.length, record.length).length
            require(capacity >= 2) { "压缩片段上限不足以容纳消息编号" }
            val parts = ArrayList<String>()
            var start = 0
            while (start < record.length) {
                var end = (start.toLong() + capacity).coerceAtMost(record.length.toLong()).toInt()
                if (end < record.length && record[end - 1].isHighSurrogate() && record[end].isLowSurrogate()) end--
                parts += record.substring(start, end)
                start = end
            }
            if (parts.isEmpty()) parts += ""
            parts.forEachIndexed { partIndex, part ->
                val page = pageLabel(index + 1, partIndex + 1, parts.size) + part
                if (current.isNotEmpty() && current.length + 2L + page.length > maxChars) {
                    output += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(page)
            }
        }
        if (current.isNotEmpty()) output += current.toString()
        return output
    }

    private fun pageLabel(message: Int, page: Int, total: Int) = "### 消息 $message · 片段 $page/$total\n"

    private fun protocolRecordsWithSupplements(
        protocol: JSONArray,
        messages: List<ScriptPluginAgentChatMessage>
    ): Sequence<String> {
        val entries = (0 until protocol.length()).map { protocol.getJSONObject(it) }
        val userIds = entries.filter { it.optString("role") == "user" }
            .map { it.optString("hchat_message_id") }.filter { it.isNotBlank() }.toSet()
        val legacyUsers = entries.filter {
            it.optString("role") == "user" && it.optString("hchat_message_id").isBlank()
        }.map { protocolContent(it.opt("content")) }.toMutableList()
        val completedCallIds = entries.filter {
            it.optString("role") == "tool" && !interruptedResult(it.opt("content"))
        }.map { it.optString("tool_call_id") }.filter { it.isNotBlank() }.toSet()
        val completedEvents = messages.flatMap { it.toolEvents }
            .filter { it.status in setOf("success", "complete", "error") }
            .associateBy { it.toolCallId.ifBlank { it.id } }
        val seenUsers = HashSet<String>()
        val seenEvents = HashSet<String>()
        return sequence {
            entries.forEach { yield(protocolRecord(it)) }
            messages.forEachIndexed { index, source ->
                val missingUser = source.role == "user" && seenUsers.add(source.id) && source.id !in userIds &&
                    !consumeLegacyUser(legacyUsers, source)
                val missingEvents = source.toolEvents.mapNotNull { event ->
                    val key = event.toolCallId.ifBlank { event.id }
                    val completed = completedEvents[key] ?: return@mapNotNull null
                    if (!seenEvents.add(key) || completed.toolCallId in completedCallIds ||
                        (completed.toolCallId.isBlank() && compatibleResultCovered(entries, completed))) null else completed
                }
                if (!missingUser && missingEvents.isEmpty()) return@forEachIndexed
                val supplement = if (missingUser) source.copy(toolEvents = missingEvents) else source.copy(
                    content = "", reasoning = "", diff = "", attachments = emptyList(), quotedMessage = null,
                    toolEvents = missingEvents
                )
                yield(buildString {
                    append("补充事实：原聊天第 ").append(index + 1).append(" 条，创建时间=").append(source.createdAt).append('\n')
                    append("这是原记录未被协议完整覆盖的内容，不是新发送的用户要求；不能因为补充区排列靠后就覆盖历史中更晚的决定。\n")
                    if (missingEvents.isNotEmpty()) {
                        append("以下工具已由客户端确认完成；同一 tool_call_id 的协议缺失/中断占位以此完成事实为准，禁止重放工具。\n")
                    }
                    append(chatRecord(supplement))
                })
            }
        }
    }

    /** Older transcripts tagged only the last user. Consume each untagged match once, in UI order. */
    private fun consumeLegacyUser(legacyUsers: MutableList<String>, message: ScriptPluginAgentChatMessage): Boolean {
        val text = message.quotedMessage?.let { quote ->
            "[用户引用的历史消息，仅用于解析本轮指代]\n来源角色: " +
                (if (quote.role == "assistant") "Agent" else "用户") + "\n" + quote.content +
                "\n[/引用]\n用户当前消息:\n" + message.content
        } ?: message.content
        if (text.isBlank()) return false
        val match = legacyUsers.indexOfFirst { it == text || it.startsWith("$text\n\n") }
        if (match < 0) return false
        legacyUsers.removeAt(match)
        return true
    }

    private fun interruptedResult(content: Any?): Boolean {
        val json = content as? JSONObject ?: (content as? String)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        return json?.optBoolean("interrupted", false) == true
    }

    /** Compatibility tools have no call ID; compare the actual stored envelope rather than tool name alone. */
    private fun compatibleResultCovered(entries: List<JSONObject>, event: ScriptPluginAgentToolEvent): Boolean {
        if (event.result.isBlank()) return false
        return entries.any { entry ->
            val text = entry.opt("content") as? String ?: return@any false
            if (!text.contains("<hchat_tool_result>")) return@any false
            val envelope = runCatching {
                JSONObject(text.substringAfter("<hchat_tool_result>").substringBefore("</hchat_tool_result>"))
            }.getOrNull() ?: return@any false
            envelope.optString("tool") in setOf(event.name, event.protocolName) &&
                equivalentJson(envelope.opt("arguments"), event.arguments) &&
                equivalentJson(envelope.opt("result"), event.result) && !interruptedResult(envelope.opt("result"))
        }
    }

    private fun equivalentJson(left: Any?, right: String): Boolean {
        fun canonical(text: String): String = runCatching { JSONObject(text).toString() }.getOrElse { text }
        return canonical(left?.toString().orEmpty()) == canonical(right)
    }

    private fun protocolRecord(message: JSONObject): String = buildString {
        append("来源=协议 · role=").append(message.optString("role", "unknown")).append('\n')
        message.keys().forEach { key ->
            if (key == "role" || key in omittedProtocolFields || message.isNull(key)) return@forEach
            append(key).append(": ")
            when (key) {
                "content" -> append(if (message.optString("role") == "tool") {
                    cleanData(message.opt(key)).toString()
                } else {
                    protocolContent(message.opt(key))
                })
                "tool_calls" -> {
                    val calls = message.optJSONArray(key)
                    if (calls == null) append(cleanData(message.opt(key))) else {
                        for (index in 0 until calls.length()) {
                            if (index > 0) append('\n')
                            val call = calls.optJSONObject(index)
                            append(if (call == null) cleanData(calls.opt(index)) else cleanProtocolObject(call))
                        }
                    }
                }
                else -> append(cleanData(message.opt(key)))
            }
            append('\n')
        }
    }

    private fun protocolContent(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).map { protocolContent(value.opt(it)) }
            .filter { it.isNotEmpty() }.joinToString("\n")
        is JSONObject -> when {
            value.optString("type") in reasoningTypes || value.optBoolean("thought", false) -> ""
            value.optString("type") in setOf("text", "input_text", "output_text") ->
                cleanData(value.opt("text")).toString()
            else -> cleanProtocolObject(value).toString()
        }
        else -> cleanData(value).toString()
    }

    private fun cleanProtocolObject(source: JSONObject): Any = cleanData(JSONObject().apply {
        source.keys().forEach { key ->
            if (key !in omittedProtocolFields) put(key, source.opt(key))
        }
    })

    /** Only binary image payloads are removed from business data, including tool arguments/results. */
    private fun cleanData(value: Any?): Any = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) put(cleanData(value.opt(index)))
        }
        is JSONObject -> {
            val mime = value.optString("mimeType", value.optString("mime_type", value.optString("media_type")))
            val isImage = value.optString("type") in setOf("image", "image_url", "input_image", "output_image") ||
                mime.startsWith("image/")
            JSONObject().apply {
                value.keys().forEach { key ->
                    put(key, if (isImage && key in setOf("data", "base64")) "[图片内容已省略]" else cleanData(value.opt(key)))
                }
            }
        }
        is String -> {
            val text = imageDataUri.replace(value, "[图片内容已省略]")
            // MCP results and function arguments can contain another JSON document as a string.
            val trimmed = text.trimStart()
            val parsed = when {
                trimmed.startsWith("{") -> runCatching { JSONObject(text) }.getOrNull()
                trimmed.startsWith("[") -> runCatching { JSONArray(text) }.getOrNull()
                else -> null
            }
            if (parsed == null) text else {
                val cleaned = cleanData(parsed).toString()
                // Keep the original text/formatting when parsing found no image payload to remove.
                if (cleaned == parsed.toString()) text else cleaned
            }
        }
        else -> value
    }

    private fun chatRecord(message: ScriptPluginAgentChatMessage): String = buildString {
        append("来源=聊天记录 · role=").append(message.role).append(" · 状态=").append(message.status).append('\n')
        append("消息 ID: ").append(message.id).append('\n')
        if (message.content.isNotEmpty()) append("正文:\n").append(cleanData(message.content)).append('\n')
        message.quotedMessage?.let { quoted ->
            append("引用=").append(quoted.role).append(" · 时间=").append(quoted.createdAt).append('\n')
            append(cleanData(quoted.content)).append('\n')
        }
        message.attachments.forEach { attachment ->
            append("附件: ").append(attachment.name).append(" | ").append(attachment.mimeType)
            append(" | ").append(attachment.size).append(" bytes | ").append(attachment.path)
            append(" | ").append(cleanData(attachment.sourceUri)).append('\n')
        }
        if (message.diff.isNotEmpty()) append("代码差异:\n").append(cleanData(message.diff)).append('\n')
        message.toolEvents.forEach { event ->
            append("工具调用: ").append(event.name).append(" [").append(event.protocolName).append(']')
            append(" | 状态=").append(event.status).append(" | event ID=").append(event.id)
            append(" | tool_call_id=").append(event.toolCallId).append(" | 完成时间=").append(event.finishedAt).append('\n')
            if (event.arguments.isNotEmpty()) append("参数:\n").append(cleanData(event.arguments)).append('\n')
            if (event.result.isNotEmpty()) append("结果:\n").append(cleanData(event.result)).append('\n')
            if (event.diff.isNotEmpty()) append("工具差异:\n").append(cleanData(event.diff)).append('\n')
            if (event.resultHandle.isNotEmpty()) {
                append("完整结果 handle: ").append(event.resultHandle)
                append(" | 总字符=").append(event.resultLength)
                append(" | 下一偏移=").append(event.nextOffset)
                append(" | truncated=").append(event.truncated).append('\n')
            }
        }
    }
}
