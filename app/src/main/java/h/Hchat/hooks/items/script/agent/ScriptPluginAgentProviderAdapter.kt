package h.Hchat.hooks.items.script.agent

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

internal object ScriptPluginAgentProviderAdapter {
    data class PreparedRequest(
        val body: JSONObject,
        val headers: Map<String, String>
    )

    data class ToolCall(
        val index: Int,
        val id: String,
        val name: String,
        val arguments: String,
        val providerMetadata: String = ""
    )

    data class StreamDelta(
        val content: String = "",
        val reasoning: String = "",
        val toolCalls: List<ToolCall> = emptyList(),
        val completed: Boolean = false
    )

    fun prepare(
        config: ScriptPluginAgentConfig,
        openAiBody: JSONObject,
        stream: Boolean,
        promptCacheEnabled: Boolean = true
    ): PreparedRequest {
        if (config.endpointMode == ScriptPluginAgentSettings.ENDPOINT_MODE_DEEPSEEK && hasImageInput(openAiBody)) {
            throw IllegalArgumentException("DeepSeek 原生 Chat Completions 不支持图片输入")
        }
        val body = when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> {
                anthropicBody(config, openAiBody, stream, promptCacheEnabled)
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> geminiBody(openAiBody)
            else -> openAiBody(config, openAiBody, stream)
        }
        ScriptPluginAgentReasoning.apply(config, body)
        return PreparedRequest(body, headers(config))
    }

    fun headers(config: ScriptPluginAgentConfig): Map<String, String> {
        val key = config.apiKey.trim()
        return when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> buildMap {
                put("anthropic-version", "2023-06-01")
                if (key.isNotBlank()) put("x-api-key", key)
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> buildMap {
                if (key.isNotBlank()) put("x-goog-api-key", key)
            }
            else -> buildMap {
                if (key.isNotBlank()) put("Authorization", "Bearer $key")
            }
        }
    }

    fun nativeStreamDelta(config: ScriptPluginAgentConfig, root: JSONObject): StreamDelta? {
        if (config.endpointMode == ScriptPluginAgentSettings.ENDPOINT_MODE_OPENROUTER) {
            root.optJSONObject("error")?.let { error ->
                throw IllegalStateException(error.optString("message").ifBlank { "OpenRouter 流式请求失败" })
            }
        }
        return when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> anthropicStreamDelta(root)
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> geminiStreamDelta(root)
            else -> null
        }
    }

    fun nativeResponseContent(config: ScriptPluginAgentConfig, root: JSONObject): String? {
        return when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> anthropicResponseText(root)
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> geminiResponseText(root)
            else -> null
        }
    }

    fun nativeResponseToolCalls(config: ScriptPluginAgentConfig, root: JSONObject): List<ToolCall>? {
        return when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> anthropicResponseToolCalls(root)
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> geminiResponseToolCalls(root)
            else -> null
        }
    }

    fun modelRequestUrl(config: ScriptPluginAgentConfig): String? {
        val base = ScriptPluginAgentSettings.normalizedApiAddress(
            config.apiBaseUrl,
            config.endpointMode,
            config.apiPath
        )
        if (base.isBlank()) return null
        val source = Uri.parse(base)
        return when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> {
                val segments = source.encodedPath.orEmpty().split('/').filter { it.isNotBlank() }
                val messagesIndex = segments.indexOfLast { it.equals("messages", ignoreCase = true) }
                val prefix = messagesIndex.takeIf { it >= 0 }?.let { segments.take(it) } ?: segments
                source.buildUpon()
                    .encodedPath("/" + (prefix + "models").joinToString("/"))
                    .clearQuery()
                    .appendQueryParameter("limit", "1000")
                    .build()
                    .toString()
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> source.buildUpon()
                    .encodedPath(source.encodedPath.orEmpty().trimEnd('/') + "/models")
                    .clearQuery()
                    .appendQueryParameter("pageSize", "1000")
                    .build()
                    .toString()
            else -> null
        }
    }

    private fun anthropicBody(
        config: ScriptPluginAgentConfig,
        source: JSONObject,
        stream: Boolean,
        promptCacheAllowed: Boolean
    ): JSONObject {
        val messages = source.optJSONArray("messages") ?: JSONArray()
        val resultMessages = JSONArray()
        val system = JSONArray()
        val promptCacheEnabled = promptCacheAllowed && supportsPromptCache(config)
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "system" || role == "developer") {
                val text = canonicalText(message.opt("content"))
                if (text.isNotBlank()) {
                    system.put(JSONObject().apply {
                        put("type", "text")
                        put("text", text)
                        if (
                            message.optBoolean("hchat_cache_control", false) &&
                            promptCacheEnabled
                        ) {
                            put("cache_control", JSONObject().put("type", "ephemeral"))
                        }
                    })
                }
                continue
            }
            val blocks = when (role) {
                "tool" -> JSONArray().put(JSONObject().apply {
                    put("type", "tool_result")
                    put("tool_use_id", message.optString("tool_call_id"))
                    put("content", canonicalText(message.opt("content")))
                    put("is_error", toolResultIsError(message.opt("content")))
                })
                else -> anthropicContent(message.opt("content"))
            }
            if (role == "assistant") {
                val calls = message.optJSONArray("tool_calls")
                if (calls != null) {
                    for (callIndex in 0 until calls.length()) {
                        val call = calls.optJSONObject(callIndex) ?: continue
                        val function = call.optJSONObject("function") ?: continue
                        val id = call.optString("id", "toolu_$callIndex")
                        val name = function.optString("name")
                        blocks.put(JSONObject().apply {
                            put("type", "tool_use")
                            put("id", id)
                            put("name", name)
                            put("input", jsonObject(function.optString("arguments", "{}")))
                        })
                    }
                }
            }
            val targetRole = if (role == "assistant") "assistant" else "user"
            appendMessage(resultMessages, targetRole, blocks)
        }
        if (promptCacheEnabled) markAnthropicCacheBreakpoint(resultMessages)
        return JSONObject().apply {
            put("model", source.optString("model"))
            put("max_tokens", 16_384)
            put("temperature", source.optDouble("temperature", 0.2))
            put("stream", stream)
            if (system.length() > 0) {
                put(
                    "system",
                    if (promptCacheEnabled) system else buildString {
                        for (index in 0 until system.length()) {
                            if (isNotEmpty()) append("\n\n")
                            append(system.optJSONObject(index)?.optString("text").orEmpty())
                        }
                    }
                )
            }
            put("messages", resultMessages)
            source.optJSONArray("tools")?.let { tools ->
                put("tools", JSONArray().apply {
                    for (index in 0 until tools.length()) {
                        val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                        put(JSONObject().apply {
                            put("name", function.optString("name"))
                            put("description", function.optString("description"))
                            put("input_schema", function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                        })
                    }
                })
                put("tool_choice", JSONObject().put("type", "auto").put("disable_parallel_tool_use", false))
            }
        }
    }

    fun supportsPromptCache(config: ScriptPluginAgentConfig): Boolean {
        if (config.endpointMode != ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC) return false
        if (config.promptCacheMode == ScriptPluginAgentSettings.PROMPT_CACHE_OFF) return false
        if (config.promptCacheMode == ScriptPluginAgentSettings.PROMPT_CACHE_FORCE) return true
        val address = ScriptPluginAgentSettings.normalizedApiAddress(
            config.apiBaseUrl,
            config.endpointMode,
            config.apiPath
        )
        return Uri.parse(address).host.equals("api.anthropic.com", ignoreCase = true)
    }

    private fun markAnthropicCacheBreakpoint(messages: JSONArray) {
        for (messageIndex in messages.length() - 1 downTo 0) {
            val content = messages.optJSONObject(messageIndex)?.optJSONArray("content") ?: continue
            for (blockIndex in content.length() - 1 downTo 0) {
                content.optJSONObject(blockIndex)?.let { block ->
                    block.put("cache_control", JSONObject().put("type", "ephemeral"))
                    return
                }
            }
        }
    }

    private fun openAiBody(config: ScriptPluginAgentConfig, source: JSONObject, stream: Boolean): JSONObject {
        val body = JSONObject(source.toString()).put("stream", stream)
        val messages = body.optJSONArray("messages") ?: return body
        val keepReasoning = config.endpointMode == ScriptPluginAgentSettings.ENDPOINT_MODE_DEEPSEEK &&
            ScriptPluginAgentReasoning.normalizedEffort(config.reasoningEffort) != "none" &&
            ScriptPluginAgentReasoning.options(config.endpointMode, config.model).size > 1
        for (messageIndex in 0 until messages.length()) {
            val message = messages.optJSONObject(messageIndex) ?: continue
            message.remove("hchat_cache_control")
            if (!keepReasoning) message.remove("reasoning_content")
            val calls = message.optJSONArray("tool_calls") ?: continue
            for (callIndex in 0 until calls.length()) {
                val call = calls.optJSONObject(callIndex) ?: continue
                val metadata = call.optString("provider_metadata")
                    .takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                call.remove("provider_metadata")
                metadata?.optJSONObject("extra_content")?.let { call.put("extra_content", it) }
            }
        }
        return body
    }

    private fun anthropicContent(value: Any?): JSONArray {
        if (value !is JSONArray) {
            val text = canonicalText(value)
            return JSONArray().apply {
                if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
            }
        }
        return JSONArray().apply {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "text" -> part.optString("text").takeIf { it.isNotBlank() }?.let { text ->
                        put(JSONObject().put("type", "text").put("text", text))
                    }
                    "image_url" -> imageData(part.optJSONObject("image_url")?.optString("url").orEmpty())
                        ?.let { image ->
                            put(JSONObject().apply {
                                put("type", "image")
                                put("source", JSONObject().apply {
                                    put("type", if (image.base64) "base64" else "url")
                                    if (image.base64) {
                                        put("media_type", image.mimeType)
                                        put("data", image.value)
                                    } else {
                                        put("url", image.value)
                                    }
                                })
                            })
                        }
                }
            }
        }
    }

    private fun geminiBody(source: JSONObject): JSONObject {
        val messages = source.optJSONArray("messages") ?: JSONArray()
        val contents = JSONArray()
        val systemParts = JSONArray()
        val toolNames = HashMap<String, String>()
        val toolResponseIds = HashMap<String, String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role == "system" || role == "developer") {
                systemParts.put(JSONObject().put("text", canonicalText(message.opt("content"))))
                continue
            }
            val parts = when (role) {
                "tool" -> JSONArray().put(JSONObject().put("functionResponse", JSONObject().apply {
                    val id = message.optString("tool_call_id")
                    toolResponseIds[id]?.takeIf { it.isNotBlank() }?.let { put("id", it) }
                    put("name", toolNames[id].orEmpty().ifBlank { "tool" })
                    put("response", JSONObject().put("result", canonicalText(message.opt("content"))))
                }))
                else -> geminiContent(message.opt("content"))
            }
            if (role == "assistant") {
                val calls = message.optJSONArray("tool_calls")
                if (calls != null) {
                    for (callIndex in 0 until calls.length()) {
                        val call = calls.optJSONObject(callIndex) ?: continue
                        val function = call.optJSONObject("function") ?: continue
                        val id = call.optString("id")
                        val name = function.optString("name")
                        toolNames[id] = name
                        val storedPart = call.optString("provider_metadata")
                            .takeIf { it.isNotBlank() }
                            ?.let { runCatching { JSONObject(it).optJSONObject("part") }.getOrNull() }
                        val responseId = storedPart?.optJSONObject("functionCall")?.optString("id").orEmpty()
                        toolResponseIds[id] = responseId
                        parts.put(storedPart ?: JSONObject().put("functionCall", JSONObject().apply {
                            put("name", name)
                            put("args", jsonObject(function.optString("arguments", "{}")))
                        }))
                    }
                }
            }
            appendMessage(contents, if (role == "assistant") "model" else "user", parts)
        }
        return JSONObject().apply {
            if (systemParts.length() > 0) {
                put("systemInstruction", JSONObject().put("parts", systemParts))
            }
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", source.optDouble("temperature", 0.2))
                if (source.has("response_format")) put("responseMimeType", "application/json")
            })
            source.optJSONArray("tools")?.let { tools ->
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
                    for (index in 0 until tools.length()) {
                        val function = tools.optJSONObject(index)?.optJSONObject("function") ?: continue
                        put(JSONObject().apply {
                            put("name", function.optString("name"))
                            put("description", function.optString("description"))
                            put(
                                "parametersJsonSchema",
                                function.optJSONObject("parameters") ?: JSONObject().put("type", "object")
                            )
                        })
                    }
                })))
                put("toolConfig", JSONObject().put(
                    "functionCallingConfig",
                    JSONObject().put("mode", "AUTO")
                ))
            }
        }
    }

    private fun geminiContent(value: Any?): JSONArray {
        if (value !is JSONArray) return JSONArray().put(JSONObject().put("text", canonicalText(value)))
        return JSONArray().apply {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "text" -> put(JSONObject().put("text", part.optString("text")))
                    "image_url" -> imageData(part.optJSONObject("image_url")?.optString("url").orEmpty())
                        ?.takeIf { it.base64 }
                        ?.let { image ->
                            put(JSONObject().put("inlineData", JSONObject().apply {
                                put("mimeType", image.mimeType)
                                put("data", image.value)
                            }))
                        }
                }
            }
        }
    }

    private fun appendMessage(target: JSONArray, role: String, parts: JSONArray) {
        if (parts.length() == 0) return
        val previous = target.optJSONObject(target.length() - 1)
        if (previous?.optString("role") == role) {
            val existing = previous.optJSONArray("content") ?: previous.optJSONArray("parts")
            if (existing != null) {
                for (index in 0 until parts.length()) existing.put(parts.opt(index))
                return
            }
        }
        val key = if (role == "model") "parts" else if (role == "assistant") "content" else {
            if (parts.optJSONObject(0)?.has("type") == true) "content" else "parts"
        }
        target.put(JSONObject().put("role", role).put(key, parts))
    }

    private fun anthropicStreamDelta(root: JSONObject): StreamDelta {
        if (root.optString("type") == "error") {
            throw IllegalStateException(root.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Anthropic 流式请求失败" })
        }
        val index = root.optInt("index", 0)
        return when (root.optString("type")) {
            "content_block_start" -> {
                val block = root.optJSONObject("content_block") ?: return StreamDelta()
                if (block.optString("type") == "tool_use") {
                    StreamDelta(toolCalls = listOf(ToolCall(
                        index,
                        block.optString("id"),
                        block.optString("name"),
                        block.optJSONObject("input")?.takeIf { it.length() > 0 }?.toString().orEmpty()
                    )))
                } else StreamDelta()
            }
            "content_block_delta" -> {
                val delta = root.optJSONObject("delta") ?: return StreamDelta()
                when (delta.optString("type")) {
                    "text_delta" -> StreamDelta(content = delta.optString("text"))
                    "thinking_delta" -> StreamDelta(reasoning = delta.optString("thinking"))
                    "input_json_delta" -> StreamDelta(toolCalls = listOf(ToolCall(
                        index, "", "", delta.optString("partial_json")
                    )))
                    else -> StreamDelta()
                }
            }
            "message_stop" -> StreamDelta(completed = true)
            else -> StreamDelta()
        }
    }

    private fun geminiStreamDelta(root: JSONObject): StreamDelta {
        val candidate = geminiCandidate(root) ?: return StreamDelta()
        val finishReason = candidate.optString("finishReason")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = ArrayList<ToolCall>()
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            val text = part.optString("text")
            if (part.optBoolean("thought", false)) reasoning.append(text) else content.append(text)
            part.optJSONObject("functionCall")?.let { call ->
                val name = call.optString("name")
                val id = geminiToolCallId(call, part, index)
                val metadata = JSONObject().put("part", part).toString()
                calls += ToolCall(
                    calls.size,
                    id,
                    name,
                    call.optJSONObject("args")?.toString().orEmpty(),
                    metadata
                )
            }
        }
        return StreamDelta(
            content.toString(),
            reasoning.toString(),
            calls,
            finishReason.isNotBlank()
        )
    }

    private fun anthropicResponseText(root: JSONObject): String {
        val content = root.optJSONArray("content") ?: return ""
        return buildString {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }.trim()
    }

    private fun anthropicResponseToolCalls(root: JSONObject): List<ToolCall> {
        val content = root.optJSONArray("content") ?: return emptyList()
        return buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") != "tool_use") continue
                add(ToolCall(index, block.optString("id"), block.optString("name"), block.optJSONObject("input")?.toString().orEmpty()))
            }
        }
    }

    private fun geminiResponseText(root: JSONObject): String {
        val parts = geminiCandidate(root)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return buildString {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (!part.optBoolean("thought", false)) append(part.optString("text"))
            }
        }.trim()
    }

    private fun geminiResponseToolCalls(root: JSONObject): List<ToolCall> {
        val parts = geminiCandidate(root)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return emptyList()
        return buildList {
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                val call = part.optJSONObject("functionCall") ?: continue
                val name = call.optString("name")
                val id = geminiToolCallId(call, part, index)
                val metadata = JSONObject().put("part", part).toString()
                add(ToolCall(size, id, name, call.optJSONObject("args")?.toString().orEmpty(), metadata))
            }
        }
    }

    private fun geminiCandidate(root: JSONObject): JSONObject? {
        root.optJSONObject("error")?.let { error ->
            throw IllegalStateException(error.optString("message").ifBlank { "Gemini 请求失败" })
        }
        root.optJSONObject("promptFeedback")?.let { feedback ->
            val reason = feedback.optString("blockReason")
            if (reason.isNotBlank()) {
                val message = feedback.optString("blockReasonMessage")
                throw IllegalStateException(
                    "Gemini 已拦截请求: $reason" +
                        message.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                )
            }
        }
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val finishReason = candidate.optString("finishReason")
        if (finishReason.isNotBlank() && finishReason !in setOf("STOP", "MAX_TOKENS")) {
            val message = candidate.optString("finishMessage")
            throw IllegalStateException(
                "Gemini 生成失败: $finishReason" +
                    message.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
            )
        }
        return candidate
    }

    private fun geminiToolCallId(call: JSONObject, part: JSONObject, partIndex: Int): String {
        return call.optString("id").ifBlank {
            val fingerprint = Integer.toHexString(part.toString().hashCode())
            "gemini-$partIndex-$fingerprint"
        }
    }

    private data class ImageData(val base64: Boolean, val mimeType: String, val value: String)

    private fun imageData(url: String): ImageData? {
        if (!url.startsWith("data:")) return url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?.let { ImageData(false, "", it) }
        val separator = url.indexOf(",")
        val marker = ";base64"
        if (separator <= 5 || !url.substring(5, separator).endsWith(marker)) return null
        val metadata = url.substring(5, separator)
        return ImageData(true, metadata.removeSuffix(marker), url.substring(separator + 1))
    }

    private fun canonicalText(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index)
                if (part?.optString("type") == "text") append(part.optString("text"))
            }
        }
        else -> value.toString()
    }

    private fun jsonObject(value: String): JSONObject {
        return runCatching { JSONObject(value) }.getOrElse { JSONObject() }
    }

    private fun toolResultIsError(value: Any?): Boolean {
        return runCatching {
            val json = JSONObject(canonicalText(value))
            json.optBoolean("isError", false) || json.optBoolean("error", false)
        }.getOrDefault(false)
    }

    private fun hasImageInput(root: JSONObject): Boolean {
        val messages = root.optJSONArray("messages") ?: return false
        for (index in 0 until messages.length()) {
            val content = messages.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (partIndex in 0 until content.length()) {
                if (content.optJSONObject(partIndex)?.optString("type") == "image_url") return true
            }
        }
        return false
    }
}
