package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.system.Os
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID

object ScriptPluginAgentToolResultStore {
    const val DEFAULT_PAGE_CHARS = 24_000
    const val MAX_PAGE_CHARS = 48_000
    private const val PREVIEW_CHARS = 12_000
    private const val MAX_NATIVE_HISTORY_CHARS = 120_000

    data class StoredResult(
        val preview: String,
        val modelContent: String,
        val handle: String,
        val totalChars: Int,
        val truncated: Boolean,
        val nextOffset: Int
    )

    private data class ResultPrefix(
        val content: String,
        val truncated: Boolean
    )

    fun store(
        context: Context,
        sessionId: String,
        result: String,
        alreadyPaged: Boolean = false
    ): StoredResult {
        val content = result
        if (alreadyPaged) {
            return StoredResult(
                preview = content,
                modelContent = content,
                handle = "",
                totalChars = content.length,
                truncated = false,
                nextOffset = 0
            )
        }
        if (content.length <= DEFAULT_PAGE_CHARS) {
            return StoredResult(
                preview = content,
                modelContent = content,
                handle = "",
                totalChars = content.length,
                truncated = false,
                nextOffset = 0
            )
        }
        val safeSession = safeName(sessionId.ifBlank { "session" })
        val handleId = UUID.randomUUID().toString().replace("-", "")
        val directory = File(resultRoot(context), safeSession).apply { mkdirs() }
        val target = File(directory, "$handleId.txt")
        val temporary = File(directory, "$handleId.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(content.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        try {
            Os.rename(temporary.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw IllegalStateException("保存完整工具结果失败", error)
            }
        }
        val handle = "$safeSession:$handleId"
        return StoredResult(
            preview = content.take(PREVIEW_CHARS) + "\n\n[结果较长，完整内容可分页读取]",
            modelContent = pageEnvelope(handle, content, 0, DEFAULT_PAGE_CHARS),
            handle = handle,
            totalChars = content.length,
            truncated = true,
            nextOffset = DEFAULT_PAGE_CHARS
        )
    }

    fun readPage(context: Context, arguments: JSONObject): String {
        val handle = arguments.optString("handle", "").trim()
        if (handle.isBlank()) return error("结果 handle 为空")
        val content = readAll(context, handle).getOrElse {
            return error(it.message ?: "工具结果不存在")
        }
        val offset = arguments.optInt("offset", 0).coerceIn(0, content.length)
        val maxChars = arguments.optInt("max_chars", DEFAULT_PAGE_CHARS)
            .coerceIn(1_000, MAX_PAGE_CHARS)
        return pageEnvelope(handle, content, offset, maxChars)
    }

    fun readAll(context: Context, handle: String): Result<String> {
        return runCatching {
            val file = resultFile(context, handle)
            require(file.isFile) { "工具结果已不存在" }
            file.readText(Charsets.UTF_8)
        }
    }

    fun deleteSession(context: Context, sessionId: String) {
        File(resultRoot(context), safeName(sessionId.ifBlank { "session" })).deleteRecursively()
    }

    fun deleteHandle(context: Context, handle: String) {
        val parts = handle.split(':', limit = 2)
        if (parts.size != 2) return
        val session = safeName(parts[0])
        val id = safeName(parts[1])
        if (session != parts[0] || id != parts[1]) return
        File(File(resultRoot(context), session), "$id.txt").delete()
    }

    fun copySessionResults(
        context: Context,
        sourceSessionId: String,
        targetSessionId: String,
        messages: List<ScriptPluginAgentChatMessage>
    ): List<ScriptPluginAgentChatMessage> {
        val sourceSession = safeName(sourceSessionId)
        val targetSession = safeName(targetSessionId)
        val sourceDirectory = File(resultRoot(context), sourceSession)
        val targetDirectory = File(resultRoot(context), targetSession).apply { mkdirs() }
        return runCatching {
            messages.map messageLoop@ { message ->
                if (message.toolEvents.isEmpty()) return@messageLoop message
                message.copy(
                    toolEvents = message.toolEvents.map eventLoop@ { event ->
                        if (event.resultHandle.isBlank()) return@eventLoop event
                        val parts = event.resultHandle.split(':', limit = 2)
                        require(parts.size == 2 && parts[0] == sourceSession) {
                            "分支工具结果不属于当前会话"
                        }
                        val id = safeName(parts[1])
                        require(id == parts[1]) { "工具结果 handle 无效" }
                        val source = File(sourceDirectory, "$id.txt")
                        val target = File(targetDirectory, "$id.txt")
                        require(source.isFile) { "分支所需的完整工具结果已不存在" }
                        source.copyTo(target, overwrite = true)
                        event.copy(resultHandle = "$targetSession:$id")
                    }
                )
            }
        }.getOrElse { error ->
            targetDirectory.deleteRecursively()
            throw IllegalStateException("复制分支工具结果失败", error)
        }
    }

    fun rebuildNativeToolHistory(
        context: Context,
        messages: List<ScriptPluginAgentChatMessage>
    ): String {
        val history = org.json.JSONArray()
        val seenCallIds = HashSet<String>()
        messages.filter { message -> message.toolEvents.isNotEmpty() }.forEach { message ->
            val calls = message.toolEvents.filter {
                it.status != "running" && it.status != "queued" && it.status != "interrupted" &&
                    it.toolCallId.isNotBlank() && it.protocolName.isNotBlank() && seenCallIds.add(it.toolCallId)
            }
            if (calls.isEmpty()) return@forEach
            history.put(JSONObject().apply {
                put("role", "assistant")
                put("tool_calls", org.json.JSONArray().apply {
                    calls.forEach { event ->
                        put(JSONObject().apply {
                            put("id", event.toolCallId)
                            put("type", "function")
                            if (event.providerMetadata.isNotBlank()) {
                                put("provider_metadata", event.providerMetadata)
                            }
                            put("function", JSONObject().apply {
                                put("name", event.protocolName)
                                put("arguments", event.arguments.ifBlank { "{}" })
                            })
                        })
                    }
                })
            })
            calls.forEach { event ->
                val content = if (event.resultHandle.isNotBlank()) {
                    readPrefix(context, event.resultHandle, DEFAULT_PAGE_CHARS).fold(
                        onSuccess = { prefix ->
                            if (prefix.truncated) {
                                pageEnvelope(
                                    handle = event.resultHandle,
                                    content = prefix.content,
                                    offset = 0,
                                    maxChars = DEFAULT_PAGE_CHARS,
                                    totalChars = event.resultLength.coerceAtLeast(prefix.content.length + 1)
                                )
                            } else {
                                prefix.content
                            }
                        },
                        onFailure = { error("完整工具结果已不存在") }
                    )
                } else {
                    event.result
                }.orEmpty()
                history.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", event.toolCallId)
                    put("content", content)
                })
            }
            trimNativeHistory(history)
        }
        return history.takeIf { it.length() > 0 }?.toString().orEmpty()
    }

    fun mergeNativeToolHistory(
        context: Context,
        current: String,
        messages: List<ScriptPluginAgentChatMessage>
    ): String {
        val rebuilt = runCatching { org.json.JSONArray(rebuildNativeToolHistory(context, messages)) }
            .getOrElse { org.json.JSONArray() }
        if (rebuilt.length() == 0) {
            return runCatching { org.json.JSONArray(current) }
                .getOrNull()
                ?.takeIf { it.length() > 0 }
                ?.toString()
                .orEmpty()
        }
        val rebuiltIds = nativeToolCallIds(rebuilt)
        val merged = org.json.JSONArray()
        val existing = runCatching { org.json.JSONArray(current) }.getOrElse { org.json.JSONArray() }
        var index = 0
        while (index < existing.length()) {
            val item = existing.optJSONObject(index)
            val calls = item?.takeIf { it.optString("role") == "assistant" }
                ?.optJSONArray("tool_calls")
            if (calls == null) {
                item?.let { merged.put(it) }
                index++
                continue
            }
            val callIds = buildSet {
                for (callIndex in 0 until calls.length()) {
                    calls.optJSONObject(callIndex)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
            val groupLength = 1 + calls.length()
            if (callIds.none { it in rebuiltIds }) {
                repeat(groupLength.coerceAtMost(existing.length() - index)) { offset ->
                    existing.optJSONObject(index + offset)?.let { merged.put(it) }
                }
            }
            index += groupLength
        }
        for (rebuiltIndex in 0 until rebuilt.length()) {
            rebuilt.optJSONObject(rebuiltIndex)?.let { merged.put(it) }
        }
        while (nativeAssistantCount(merged) > 1 && merged.toString().length > MAX_NATIVE_HISTORY_CHARS) {
            val first = merged.optJSONObject(0)
            val toolCount = if (first?.optString("role") == "assistant") {
                first.optJSONArray("tool_calls")?.length() ?: 0
            } else {
                0
            }
            merged.remove(0)
            repeat(toolCount.coerceAtMost(merged.length())) { merged.remove(0) }
        }
        return merged.takeIf { it.length() > 0 }?.toString().orEmpty()
    }

    private fun nativeToolCallIds(history: org.json.JSONArray): Set<String> {
        return buildSet {
            for (index in 0 until history.length()) {
                val calls = history.optJSONObject(index)?.optJSONArray("tool_calls") ?: continue
                for (callIndex in 0 until calls.length()) {
                    calls.optJSONObject(callIndex)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }
    }

    private fun nativeAssistantCount(history: org.json.JSONArray): Int {
        var count = 0
        for (index in 0 until history.length()) {
            if (history.optJSONObject(index)?.optString("role") == "assistant") count++
        }
        return count
    }

    private fun trimNativeHistory(history: org.json.JSONArray) {
        while (nativeAssistantCount(history) > 1 && history.toString().length > MAX_NATIVE_HISTORY_CHARS) {
            val first = history.optJSONObject(0)
            val toolCount = if (first?.optString("role") == "assistant") {
                first.optJSONArray("tool_calls")?.length() ?: 0
            } else {
                0
            }
            history.remove(0)
            repeat(toolCount.coerceAtMost(history.length())) { history.remove(0) }
        }
    }

    private fun readPrefix(context: Context, handle: String, maxChars: Int): Result<ResultPrefix> {
        return runCatching {
            val file = resultFile(context, handle)
            require(file.isFile) { "工具结果已不存在" }
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(maxChars)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read <= 0) break
                    count += read
                }
                ResultPrefix(
                    content = String(buffer, 0, count),
                    truncated = reader.read() >= 0
                )
            }
        }
    }

    private fun pageEnvelope(
        handle: String,
        content: String,
        offset: Int,
        maxChars: Int,
        totalChars: Int = content.length
    ): String {
        val end = (offset + maxChars).coerceAtMost(content.length)
        return JSONObject().apply {
            put("handle", handle)
            put("offset", offset)
            put("totalChars", totalChars)
            put("content", content.substring(offset, end))
            put("truncated", end < totalChars)
            if (end < totalChars) put("nextOffset", end)
        }.toString()
    }

    private fun resultFile(context: Context, handle: String): File {
        val parts = handle.split(':', limit = 2)
        require(parts.size == 2) { "结果 handle 无效" }
        val session = safeName(parts[0])
        val id = safeName(parts[1])
        require(session == parts[0] && id == parts[1]) { "结果 handle 无效" }
        return File(File(resultRoot(context), session), "$id.txt")
    }

    private fun error(message: String): String {
        return JSONObject().apply {
            put("isError", true)
            put("message", message)
        }.toString()
    }

    private fun resultRoot(context: Context): File {
        return File(ScriptPluginRuntime.scriptDir(context).parentFile, "Agent/tool-results").apply { mkdirs() }
    }

    private fun safeName(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96).ifBlank { "item" }
    }
}
