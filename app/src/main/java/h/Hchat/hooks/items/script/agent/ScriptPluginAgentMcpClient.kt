package h.Hchat.hooks.items.script.agent

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ScriptPluginAgentMcpClient(
    private val endpoint: String,
    private val authorization: String,
    private val cancellation: ScriptPluginAgentCancellation? = null
) {
    private val requestId = AtomicLong(0L)
    private var sessionId: String = ""
    private var protocolVersion = REQUESTED_PROTOCOL_VERSION
    private var initialized = false
    private var serverInstructions: String = ""

    fun listTools(): String {
        initialize()
        val tools = JSONArray()
        val visitedCursors = HashSet<String>()
        var cursor = ""
        var pageCount = 0
        var catalogBytes = 0L
        do {
            cancellation?.throwIfCancelled()
            check(++pageCount <= MAX_TOOL_PAGES) { "MCP 工具目录超过 $MAX_TOOL_PAGES 页" }
            check(cursor.isBlank() || visitedCursors.add(cursor)) { "MCP 工具目录返回循环 cursor" }
            val params = JSONObject().apply {
                if (cursor.isNotBlank()) put("cursor", cursor)
            }
            val result = request("tools/list", params)
            val page = result.optJSONArray("tools") ?: error("MCP tools/list 缺少 tools 数组")
            catalogBytes += page.toString().toByteArray(Charsets.UTF_8).size
            check(catalogBytes <= ScriptPluginAgentMcpResponse.MAX_RESPONSE_BYTES) { "MCP 工具目录超过大小限制" }
            check(tools.length() + page.length() <= MAX_TOOLS) { "MCP 工具目录超过 $MAX_TOOLS 个工具" }
            for (index in 0 until page.length()) tools.put(page.opt(index))
            cursor = result.optString("nextCursor", "").trim()
        } while (cursor.isNotBlank())
        return JSONObject().apply {
            if (serverInstructions.isNotBlank()) put("instructions", serverInstructions)
            put("tools", tools)
        }.toString()
    }

    fun callTool(name: String, arguments: JSONObject): String {
        require(name.isNotBlank()) { "MCP 工具名为空" }
        initialize()
        val result = request(
            "tools/call",
            JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            }
        )
        return result.toString()
    }

    private fun initialize() {
        if (initialized) return
        sessionId = ""
        protocolVersion = REQUESTED_PROTOCOL_VERSION
        val result = request(
            "initialize",
            JSONObject().apply {
                put("protocolVersion", REQUESTED_PROTOCOL_VERSION)
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply {
                    put("name", "Hchat Plugin Agent")
                    put("version", "1.0")
                })
            }
        )
        val negotiatedVersion = result.optString("protocolVersion", "").trim()
        check(negotiatedVersion in SUPPORTED_PROTOCOL_VERSIONS) {
            "MCP 服务器返回不支持的协议版本: ${negotiatedVersion.ifBlank { "空" }}"
        }
        protocolVersion = negotiatedVersion
        serverInstructions = result.optString("instructions", "").trim().take(4_000)
        notify("notifications/initialized", JSONObject())
        initialized = true
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val id = requestId.incrementAndGet()
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return requireNotNull(post(payload, expectedId = id)).also { response ->
            response.optJSONObject("error")?.let { error ->
                throw IllegalStateException("MCP $method 失败: ${error.optString("message", error.toString())}")
            }
        }.optJSONObject("result") ?: throw IllegalStateException("MCP $method 缺少 result")
    }

    private fun notify(method: String, params: JSONObject) {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        post(payload, expectedId = null)
    }

    private fun post(payload: JSONObject, expectedId: Long?): JSONObject? {
        cancellation?.throwIfCancelled()
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .apply {
                if (payload.optString("method") != "initialize") header("MCP-Protocol-Version", protocolVersion)
                if (sessionId.isNotBlank()) header("Mcp-Session-Id", sessionId)
                if (authorization.isNotBlank()) header("Authorization", authorization)
            }
            .post(object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                // A failed POST may already have executed a tool. Do not replay its body.
                override fun isOneShot() = true
            })
            .build()
        val call = httpClient.newCall(request)
        cancellation?.bind(call)
        return try {
            call.execute().use { response ->
                cancellation?.throwIfCancelled()
                if (response.header("Mcp-Session-Id").orEmpty().isNotBlank()) {
                    sessionId = response.header("Mcp-Session-Id").orEmpty()
                }
                if (!response.isSuccessful) throw IllegalStateException("MCP HTTP ${response.code}")
                if (expectedId == null) return@use null
                val responseBody = response.body ?: error("MCP 返回为空")
                ScriptPluginAgentMcpResponse.read(
                    responseBody.byteStream(),
                    response.header("Content-Type").orEmpty(),
                    expectedId
                ) { cancellation?.throwIfCancelled() }
            }
        } catch (error: Throwable) {
            if (cancellation?.isCancellation(error) == true) {
                throw java.util.concurrent.CancellationException("Agent 已中断")
            }
            throw error
        } finally {
            cancellation?.unbind(call)
        }
    }

    private companion object {
        const val REQUESTED_PROTOCOL_VERSION = "2025-06-18"
        // Retain existing JSON-RPC compatibility; legacy HTTP+SSE's separate GET endpoint is not implemented.
        val SUPPORTED_PROTOCOL_VERSIONS = setOf("2024-11-05", "2025-03-26", REQUESTED_PROTOCOL_VERSION)
        const val MAX_TOOL_PAGES = 100
        const val MAX_TOOLS = 2_000
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
