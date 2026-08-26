package com.Johnny.wcx.agent.mcp

import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.agent.data.entity.McpTransport
import com.Johnny.wcx.agent.tool.ProviderKind
import com.Johnny.wcx.agent.tool.ProviderTool
import com.Johnny.wcx.agent.tool.ToolMode
import com.Johnny.wcx.agent.tool.ToolProvider
import com.Johnny.wcx.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Connection state of an [McpToolProvider], surfaced in the settings UI (§4). */
enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/**
 * One configured MCP server, adapted to the [ToolProvider] contract so it is structurally identical
 * to the builtin provider from the model's perspective (§3.4). Owns its MCP [Client] + transport,
 * caches its `tools/list` result, and reports connection [state]/[lastError] (§4).
 *
 * Reconnection with exponential backoff is orchestrated by [McpClientManager]; this class exposes
 * [connect]/[disconnect]/[refreshTools] as the primitives.
 */
class McpToolProvider(
    override val id: String,
    override val name: String,
    private val transport: McpTransport,
    private val endpointUrl: String,
    private val headers: Map<String, String>,
    private val httpClient: HttpClient,
) : ToolProvider {

    override val kind: ProviderKind = ProviderKind.MCP

    @Volatile
    var state: McpConnectionState = McpConnectionState.DISCONNECTED
        private set

    @Volatile
    var lastError: String? = null
        private set

    override val isAvailable: Boolean get() = state == McpConnectionState.CONNECTED

    private val connectMutex = Mutex()
    private var client: Client? = null

    // Cached tools/list, refreshed on connect and on manual refresh.
    @Volatile
    private var cachedTools: List<ProviderTool> = emptyList()

    override fun listTools(): List<ProviderTool> = cachedTools

    private fun requestBuilder(): HttpRequestBuilder.() -> Unit {
        val customHeaders = headers
        return { customHeaders.forEach { (k, v) -> headers.append(k, v) } }
    }

    /** Connects (idempotent guard via [connectMutex]) and caches the tool list. */
    suspend fun connect() = connectMutex.withLock {
        if (state == McpConnectionState.CONNECTED) return@withLock
        state = McpConnectionState.CONNECTING
        lastError = null
        runCatching {
            val t = when (transport) {
                McpTransport.STREAMABLE_HTTP ->
                    StreamableHttpClientTransport(httpClient, endpointUrl, requestBuilder = requestBuilder())

                McpTransport.SSE ->
                    SseClientTransport(httpClient, endpointUrl, requestBuilder = requestBuilder())
            }
            t.onClose { onTransportClosed() }
            t.onError { e -> onTransportError(e) }

            val c = Client(Implementation(name = "wekit-mcp-client", version = BuildConfig.VERSION_NAME))
            c.connect(t)
            client = c
            cachedTools = fetchTools(c)
            state = McpConnectionState.CONNECTED
            WeLogger.i(TAG, "connected to MCP server '$name' ($endpointUrl), ${cachedTools.size} tools")
        }.onFailure {
            state = McpConnectionState.FAILED
            lastError = it.message ?: it.javaClass.simpleName
            WeLogger.e(TAG, "failed to connect MCP server '$name'", it)
            runCatching { client?.close() }
            client = null
        }
    }

    suspend fun disconnect() = connectMutex.withLock {
        runCatching { client?.close() }
        client = null
        cachedTools = emptyList()
        state = McpConnectionState.DISCONNECTED
    }

    /** Re-fetches tools/list from a connected server. No-op if disconnected. */
    suspend fun refreshTools(): Boolean = connectMutex.withLock {
        val c = client ?: return@withLock false
        runCatching { cachedTools = fetchTools(c); true }.getOrElse {
            WeLogger.w(TAG, "refreshTools failed for '$name'", it); false
        }
    }

    private suspend fun fetchTools(c: Client): List<ProviderTool> =
        c.listTools().tools.map { tool ->
            ProviderTool(
                name = tool.name,
                description = tool.description ?: "",
                jsonSchema = buildSchema(tool.inputSchema.properties, tool.inputSchema.required),
                // MCP tools default to ENABLED — the user already trusted the server by adding it (§3.2).
                factoryDefaultMode = ToolMode.ENABLED,
            )
        }

    private fun buildSchema(properties: JsonObject?, required: List<String>?): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties ?: JsonObject(emptyMap()))
        if (required != null) {
            put("required", kotlinx.serialization.json.JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): String {
        val c = client ?: return "MCP server '$name' is not connected."
        return runCatching {
            val result = c.callTool(name = toolName, arguments = arguments.toPlainMap())
            val text = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
            if (result.isError == true) "工具调用返回错误：$text" else text.ifEmpty { "(no textual content)" }
        }.getOrElse { "MCP tool '$toolName' failed: ${it.message ?: it.javaClass.simpleName}" }
    }

    /** MCP callTool takes Map<String, Any?>; flatten our JsonObject to plain Kotlin values. */
    private fun JsonObject.toPlainMap(): Map<String, Any?> = mapValues { (_, v) -> McpJsonBridge.toPlain(v) }

    private fun onTransportClosed() {
        if (state == McpConnectionState.CONNECTED) {
            state = McpConnectionState.DISCONNECTED
            WeLogger.w(TAG, "MCP server '$name' transport closed")
        }
    }

    private fun onTransportError(e: Throwable) {
        lastError = e.message ?: e.javaClass.simpleName
        WeLogger.w(TAG, "MCP server '$name' transport error: $lastError")
    }

    companion object {
        private const val TAG = "McpToolProvider"
    }
}
