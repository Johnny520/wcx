package h.Hchat.hooks.items.script.agent

import org.json.JSONArray
import org.json.JSONObject

internal class ScriptPluginAgentMcpClients(
    servers: List<ScriptPluginAgentMcpServer>,
    private val cancellation: ScriptPluginAgentCancellation
) {
    private val entries = servers.filter { it.enabled }
        .map { server ->
            Entry(
                server = server,
                namespace = namespace(server),
                client = ScriptPluginAgentMcpClient(
                    endpoint = server.endpoint,
                    authorization = server.authorization,
                    cancellation = cancellation
                )
            )
        }
        .sortedWith(compareBy<Entry> { it.namespace }.thenBy { it.server.id })
    private val routes = LinkedHashMap<String, ToolRoute>()

    fun listTools(): String {
        routes.clear()
        val tools = JSONArray()
        val serverViews = JSONArray()
        entries.forEach { entry ->
            cancellation.throwIfCancelled()
            val view = JSONObject().apply {
                put("id", entry.server.id)
                put("name", entry.server.name)
                put("namespace", entry.namespace)
            }
            runCatching { JSONObject(entry.client.listTools()) }
                .onSuccess { catalog ->
                    catalog.optString("instructions", "").takeIf { it.isNotBlank() }?.let {
                        view.put("instructions", it)
                    }
                    val listedTools = catalog.optJSONArray("tools") ?: JSONArray()
                    val sortedTools = (0 until listedTools.length())
                        .mapNotNull { index -> listedTools.optJSONObject(index) }
                        .sortedBy { it.optString("name", "") }
                    for (source in sortedTools) {
                        val originalName = source.optString("name", "").trim()
                        if (originalName.isBlank()) continue
                        var exposedName = exposedName(entry.namespace, originalName)
                        var collisionIndex = 2
                        while (routes.containsKey(exposedName)) {
                            exposedName = "${exposedName}_$collisionIndex"
                            collisionIndex++
                        }
                        routes[exposedName] = ToolRoute(entry.client, originalName)
                        tools.put(JSONObject(source.toString()).apply {
                            put("name", exposedName)
                            val description = optString("description", "").trim()
                            put(
                                "description",
                                if (description.isBlank()) "${entry.server.name} 提供的工具" else "[${entry.server.name}] $description"
                            )
                        })
                    }
                    view.put("toolCount", listedTools.length())
                }
                .onFailure {
                    if (cancellation.isCancellation(it)) throw it
                    val message = it.message ?: it.javaClass.simpleName
                    view.put("error", message)
                    view.put("toolCount", 0)
                }
            serverViews.put(view)
        }
        return JSONObject().apply {
            put("servers", serverViews)
            put("tools", tools)
        }.toString()
    }

    fun callTool(name: String, arguments: JSONObject): String {
        val route = routes[name]
            ?: throw IllegalArgumentException("没有找到已启用 MCP 工具: $name")
        return route.client.callTool(route.originalName, arguments)
    }

    private data class Entry(
        val server: ScriptPluginAgentMcpServer,
        val namespace: String,
        val client: ScriptPluginAgentMcpClient
    )

    private data class ToolRoute(
        val client: ScriptPluginAgentMcpClient,
        val originalName: String
    )

    private companion object {
        fun namespace(server: ScriptPluginAgentMcpServer): String {
            val name = server.name.lowercase()
                .replace(Regex("[^a-z0-9_-]+"), "_")
                .trim('_')
                .take(24)
                .ifBlank { "server" }
            val id = server.id.replace(Regex("[^A-Za-z0-9]+"), "").take(10).ifBlank { "mcp" }
            return "${name}_$id"
        }

        fun exposedName(namespace: String, originalName: String): String {
            val tool = originalName.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_')
            return "mcp__${namespace}__${tool.ifBlank { "tool" }}"
        }
    }
}
