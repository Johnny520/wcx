package h.Hchat.hooks.items.script.agent

import org.json.JSONArray
import org.json.JSONObject

internal object ScriptPluginAgentProtocolTranscript {
    private const val MESSAGE_ID = "hchat_message_id"
    private const val RUNTIME_STATE = "hchat_runtime_state"

    fun providerMessages(encoded: String): JSONArray {
        val source = parse(encoded)
        return JSONArray().apply {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                put(providerMessage(message))
            }
        }
    }

    fun isValid(encoded: String): Boolean {
        if (encoded.isBlank()) return true
        return runCatching { JSONArray(encoded) }.getOrNull()?.let { messages ->
            (0 until messages.length()).all { messages.optJSONObject(it) != null }
        } == true
    }

    fun fromMessages(
        messages: JSONArray,
        currentMessageId: String,
        runtimeState: String
    ): String {
        val copy = JSONArray()
        var tagged = false
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val item = JSONObject(message.toString())
            if (!tagged && currentMessageId.isNotBlank() && item.optString("role") == "user") {
                var laterUserExists = false
                for (later in index + 1 until messages.length()) {
                    if (messages.optJSONObject(later)?.optString("role") == "user") {
                        laterUserExists = true
                        break
                    }
                }
                if (!laterUserExists) {
                    item.put(MESSAGE_ID, currentMessageId)
                    if (runtimeState.isNotBlank()) item.put(RUNTIME_STATE, runtimeState)
                    tagged = true
                }
            }
            copy.put(item)
        }
        return copy.toString()
    }

    fun containsMessage(encoded: String, messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val messages = parse(encoded)
        for (index in 0 until messages.length()) {
            if (messages.optJSONObject(index)?.optString(MESSAGE_ID) == messageId) return true
        }
        return false
    }

    fun latestRuntimeState(encoded: String): String {
        val messages = parse(encoded)
        for (index in messages.length() - 1 downTo 0) {
            messages.optJSONObject(index)?.optString(RUNTIME_STATE)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    fun toolCallIds(encoded: String): Set<String> {
        if (encoded.isBlank() || !isValid(encoded)) return emptySet()
        val ids = LinkedHashSet<String>()
        val messages = parse(encoded)
        for (index in 0 until messages.length()) {
            val calls = messages.optJSONObject(index)?.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                calls.optJSONObject(callIndex)?.optString("id")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(ids::add)
            }
        }
        return ids
    }

    fun append(
        encoded: String,
        message: JSONObject,
        messageId: String = "",
        runtimeState: String = ""
    ): String {
        val messages = parse(encoded)
        val item = JSONObject(message.toString())
        if (messageId.isNotBlank()) item.put(MESSAGE_ID, messageId)
        if (runtimeState.isNotBlank()) item.put(RUNTIME_STATE, runtimeState)
        messages.put(item)
        return messages.toString()
    }

    fun appendAll(encoded: String, additions: List<JSONObject>): String {
        if (additions.isEmpty()) return encoded
        val messages = parse(encoded)
        additions.forEach { messages.put(JSONObject(it.toString())) }
        return messages.toString()
    }

    fun closePendingToolCalls(encoded: String): String {
        if (encoded.isBlank() || !isValid(encoded)) return encoded
        val messages = parse(encoded)
        val completed = HashSet<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "tool") {
                message.optString("tool_call_id").takeIf { it.isNotBlank() }?.let(completed::add)
            }
        }
        val pending = ArrayList<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "assistant") continue
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                val id = calls.optJSONObject(callIndex)?.optString("id").orEmpty()
                if (id.isNotBlank() && id !in completed && id !in pending) pending += id
            }
        }
        if (pending.isEmpty()) return encoded
        val result = JSONObject().apply {
            put("isError", true)
            put("interrupted", true)
            put("message", "工具调用在结果写入前中断，客户端没有自动重放；请先读取当前状态再决定是否重试。")
        }.toString()
        pending.forEach { id ->
            messages.put(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", id)
                put("content", result)
            })
        }
        return messages.toString()
    }

    private fun parse(encoded: String): JSONArray {
        if (encoded.isBlank()) return JSONArray()
        return runCatching { JSONArray(encoded) }.getOrElse { JSONArray() }
    }

    private fun providerMessage(source: JSONObject): JSONObject {
        return JSONObject(source.toString()).apply {
            remove(MESSAGE_ID)
            remove(RUNTIME_STATE)
        }
    }
}
