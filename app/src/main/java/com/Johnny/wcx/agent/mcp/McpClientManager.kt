package com.Johnny.wcx.agent.mcp

import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.data.entity.ProviderEntity
import com.Johnny.wcx.agent.tool.ProviderKind
import com.Johnny.wcx.utils.WeLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Owns the live set of [McpToolProvider]s (§4). Builds one per enabled MCP [ProviderEntity], drives
 * connect + exponential-backoff reconnect, keeps its tools/list cached, seeds their factory-default
 * permissions, and pushes the provider set into the [com.Johnny.wcx.agent.tool.ToolRegistry].
 *
 * A single shared Ktor [HttpClient] with the SSE plugin backs all transports (both Streamable HTTP
 * and SSE client sessions require it).
 */
object McpClientManager {

    private val TAG = "McpClientManager"

    private val httpClient: HttpClient by lazy {
        HttpClient(CIO) { install(SSE) }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // providerId -> live provider
    private val providers = ConcurrentHashMap<String, McpToolProvider>()

    // providerId -> its reconnect loop job
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    /** Called when the connected-provider set changes, so the caller can refresh the tool registry. */
    @Volatile
    var onProvidersChanged: (() -> Unit)? = null

    fun connectedProviders(): List<McpToolProvider> = providers.values.toList()

    /**
     * Reconciles the live provider set against the enabled MCP rows in Room. New enabled servers get
     * a provider + reconnect loop; removed/disabled ones are torn down. Idempotent — safe to call on
     * startup and whenever the server list changes.
     */
    suspend fun sync() {
        val rows = WeAgentRepository.getAllProviders()
            .filter { it.kind == ProviderKind.MCP && it.enabled }

        val wantedIds = rows.map { it.id }.toSet()

        // Tear down removed/disabled providers.
        providers.keys.filter { it !in wantedIds }.forEach { removeProvider(it) }

        // Add/keep wanted ones.
        for (row in rows) {
            if (providers.containsKey(row.id)) continue
            val provider = build(row) ?: continue
            providers[row.id] = provider
            startReconnectLoop(provider)
        }
        onProvidersChanged?.invoke()
    }

    private fun build(row: ProviderEntity): McpToolProvider? {
        val transport = row.transport ?: return null
        val url = row.endpointUrl?.takeIf { it.isNotBlank() } ?: return null
        val headers = parseHeaders(row.headersJson)
        return McpToolProvider(
            id = row.id,
            name = row.name,
            transport = transport,
            endpointUrl = url,
            headers = headers,
            httpClient = httpClient,
        )
    }

    private fun startReconnectLoop(provider: McpToolProvider) {
        reconnectJobs[provider.id]?.cancel()
        reconnectJobs[provider.id] = scope.launch {
            var attempt = 0
            while (isActive) {
                if (provider.state == McpConnectionState.CONNECTED) {
                    delay(HEALTHCHECK_INTERVAL_MS.milliseconds)
                    continue
                }
                provider.connect()
                if (provider.state == McpConnectionState.CONNECTED) {
                    attempt = 0
                    seedPermissions(provider)
                    onProvidersChanged?.invoke()
                } else {
                    attempt++
                    val backoff = minOf(BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceIn(0, 6)), MAX_BACKOFF_MS)
                    WeLogger.w(TAG, "reconnect '${provider.name}' in ${backoff}ms (attempt $attempt)")
                    delay(backoff.milliseconds)
                }
            }
        }
    }

    private suspend fun seedPermissions(provider: McpToolProvider) {
        runCatching {
            WeAgentRepository.seedMcpTools(provider.id, provider.listTools().map { it.name })
        }.onFailure { WeLogger.w(TAG, "seedMcpTools failed for '${provider.name}'", it) }
    }

    private fun removeProvider(id: String) {
        reconnectJobs.remove(id)?.cancel()
        providers.remove(id)?.let { p -> scope.launch { p.disconnect() } }
    }

    /** Manually re-fetch a server's tools/list (§4 "refresh tools"). */
    suspend fun refreshTools(providerId: String): Boolean =
        providers[providerId]?.refreshTools()?.also { onProvidersChanged?.invoke() } ?: false

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            (Json.parseToJsonElement(headersJson) as? JsonObject)
                ?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        }.getOrElse {
            WeLogger.w(TAG, "failed to parse MCP headers json", it); emptyMap()
        }
    }

    private const val HEALTHCHECK_INTERVAL_MS = 30_000L
    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 60_000L
}
