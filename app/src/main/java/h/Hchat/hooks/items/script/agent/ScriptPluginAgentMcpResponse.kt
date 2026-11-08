package h.Hchat.hooks.items.script.agent

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream

internal object ScriptPluginAgentMcpResponse {
    const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    fun read(
        input: InputStream,
        contentType: String,
        expectedId: Long,
        checkCancelled: () -> Unit = {}
    ): JSONObject {
        val limited = object : FilterInputStream(input) {
            private var consumed = 0L
            private fun account(count: Int): Int {
                if (count > 0) consumed += count
                check(consumed <= MAX_RESPONSE_BYTES) { "MCP 响应超过 $MAX_RESPONSE_BYTES 字节限制" }
                return count
            }

            override fun read(): Int {
                checkCancelled()
                val value = input.read()
                account(if (value < 0) 0 else 1)
                return value
            }

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                checkCancelled()
                val bounded = minOf(length.toLong(), MAX_RESPONSE_BYTES - consumed + 1).toInt()
                return account(input.read(bytes, offset, bounded))
            }
        }
        if (contentType.substringBefore(';').trim().equals("text/event-stream", ignoreCase = true)) {
            return readEventStream(limited, expectedId, checkCancelled)
        }
        val bytes = ByteArrayOutputStream()
        limited.copyTo(bytes)
        checkCancelled()
        val text = bytes.toString(Charsets.UTF_8.name()).trim().removePrefix("\uFEFF")
        check(text.isNotBlank()) { "MCP 返回为空" }
        return response(JSONObject(text), expectedId) ?: error("MCP 返回的请求 ID 不匹配")
    }

    private fun readEventStream(input: InputStream, expectedId: Long, checkCancelled: () -> Unit): JSONObject {
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val data = StringBuilder()
            var hasData = false
            var firstLine = true
            while (true) {
                checkCancelled()
                val raw = reader.readLine() ?: break
                val line = if (firstLine) raw.removePrefix("\uFEFF") else raw
                firstLine = false
                if (line.isEmpty()) {
                    if (hasData && data.isNotBlank()) {
                        response(JSONObject(data.toString()), expectedId)?.let { return it }
                    }
                    data.setLength(0)
                    hasData = false
                } else if (!line.startsWith(':') && line.substringBefore(':') == "data") {
                    if (hasData) data.append('\n')
                    data.append(line.substringAfter(':', "").removePrefix(" "))
                    hasData = true
                }
            }
        }
        // EOF without a blank line does not complete an SSE event.
        error("MCP SSE 中没有完整且匹配的请求响应")
    }

    private fun response(message: JSONObject, expectedId: Long): JSONObject? {
        val id = message.opt("id")
        if (message.has("method") || id !is Number || id.toLong() != expectedId || id.toDouble() != expectedId.toDouble()) {
            return null
        }
        check(message.optString("jsonrpc") == "2.0") { "MCP 返回无效 JSON-RPC 版本" }
        check(message.has("result") != message.has("error")) { "MCP 返回缺少 result/error 或两者同时存在" }
        return message
    }
}
