package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.data.entity.McpTransport
import com.Johnny.wcx.agent.data.entity.ProviderEntity
import com.Johnny.wcx.agent.mcp.McpClientManager
import com.Johnny.wcx.agent.tool.ProviderKind
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

/** Lists MCP servers (row → detail; no inline delete) and adds new ones (§4). */
@Composable
fun McpServersScreen(onBack: () -> Unit, onOpenServer: (serverId: String) -> Unit) {
    val allProviders by WeAgentRepository.observeProviders().collectAsState(initial = emptyList())
    val servers = allProviders.filter { it.kind == ProviderKind.MCP }
    val scope = rememberCoroutineScope()
    val showAdd = remember { mutableStateOf(false) }

    AgentSettingsScaffold(title = "MCP 服务器", onBack = onBack) {
        if (servers.isEmpty()) item { EmptyHint("还没有 MCP 服务器。") }
        items(servers.size, key = { servers[it].id }) { i ->
            val s = servers[i]
            val live = McpClientManager.connectedProviders().firstOrNull { it.id == s.id }
            val status = live?.state?.name ?: "DISCONNECTED"
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = s.name.ifBlank { s.endpointUrl ?: s.id },
                    summary = "${s.transport?.name ?: "?"} · $status" + (live?.lastError?.let { " · $it" } ?: ""),
                    onClick = { onOpenServer(s.id) },
                )
            }
        }
        item {
            Button(
                onClick = { showAdd.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text("添加服务器") }
        }
    }

    AddMcpDialog(showAdd) { name, transport, url, headersJson ->
        scope.launch {
            WeAgentRepository.upsertMcpProvider(
                ProviderEntity(
                    id = UUID.randomUUID().toString(),
                    kind = ProviderKind.MCP,
                    name = name.ifBlank { url },
                    transport = transport,
                    endpointUrl = url,
                    headersJson = headersJson.ifBlank { null },
                    enabled = true,
                )
            )
        }
    }
}

/**
 * MCP server detail: refresh/status, delete (moved here from the list), and a per-tool permission
 * list like the built-in providers (§4). Tools come from the live connected provider, if any.
 */
@Composable
fun McpServerDetailScreen(serverId: String, onBack: () -> Unit) {
    val allProviders by WeAgentRepository.observeProviders().collectAsState(initial = emptyList())
    val server = allProviders.firstOrNull { it.id == serverId }
    val scope = rememberCoroutineScope()
    val perms by WeAgentRepository.observeToolPermissions().collectAsState(initial = emptyList())
    val permMap = perms.associate { it.providerId to it.toolName to it.mode }

    val live = McpClientManager.connectedProviders().firstOrNull { it.id == serverId }
    val tools = live?.listTools().orEmpty()

    AgentSettingsScaffold(title = server?.name ?: "MCP 服务器", onBack = onBack) {
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = "连接状态",
                    summary = (live?.state?.name ?: "DISCONNECTED") + (live?.lastError?.let { " · $it" } ?: "") + " · 点击刷新工具",
                    onClick = { scope.launch { McpClientManager.refreshTools(serverId) } },
                )
                server?.let {
                    ArrowPreference(title = "地址", summary = "${it.transport?.name ?: "?"} · ${it.endpointUrl}", onClick = {})
                }
                TextButton(
                    text = "删除此服务器",
                    onClick = { scope.launch { WeAgentRepository.deleteMcpProvider(serverId) }; onBack() },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        item { top.yukonga.miuix.kmp.basic.SmallTitle("工具权限") }
        if (tools.isEmpty()) item { EmptyHint("未连接或无工具。连接后可在此设置每个工具的权限。") }
        items(tools.size, key = { "${serverId}_${tools[it].name}" }) { i ->
            val t = tools[i]
            val mode = permMap[serverId to t.name] ?: t.factoryDefaultMode
            Card(Modifier.padding(bottom = 6.dp)) {
                McpToolModeDropdown(t.name, mode) { newMode ->
                    scope.launch { WeAgentRepository.setToolMode(serverId, t.name, newMode) }
                }
            }
        }
    }
}

private val MCP_MODE_ORDER = listOf(
    com.Johnny.wcx.agent.tool.ToolMode.ENABLED,
    com.Johnny.wcx.agent.tool.ToolMode.MANUAL_APPROVAL,
    com.Johnny.wcx.agent.tool.ToolMode.SMART_APPROVAL,
    com.Johnny.wcx.agent.tool.ToolMode.DISABLED,
)

private fun com.Johnny.wcx.agent.tool.ToolMode.mcpLabel(): String = when (this) {
    com.Johnny.wcx.agent.tool.ToolMode.ENABLED -> "直接允许"
    com.Johnny.wcx.agent.tool.ToolMode.MANUAL_APPROVAL -> "手动审批"
    com.Johnny.wcx.agent.tool.ToolMode.SMART_APPROVAL -> "智能审批"
    com.Johnny.wcx.agent.tool.ToolMode.DISABLED -> "禁用"
}

@Composable
private fun McpToolModeDropdown(name: String, mode: com.Johnny.wcx.agent.tool.ToolMode, onChange: (com.Johnny.wcx.agent.tool.ToolMode) -> Unit) {
    WindowDropdownPreference(
        title = name,
        items = MCP_MODE_ORDER.map { it.mcpLabel() },
        selectedIndex = MCP_MODE_ORDER.indexOf(mode).coerceAtLeast(0),
        onSelectedIndexChange = { onChange(MCP_MODE_ORDER[it]) },
    )
}

@Composable
private fun AddMcpDialog(
    show: MutableState<Boolean>,
    onConfirm: (name: String, transport: McpTransport, url: String, headersJson: String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    var url by remember(show.value) { mutableStateOf("") }
    var headers by remember(show.value) { mutableStateOf("") }
    var transportIndex by remember(show.value) { mutableIntStateOf(0) }
    val transports = listOf(McpTransport.STREAMABLE_HTTP, McpTransport.SSE)

    WindowDialog(show = show.value, title = "添加 MCP 服务器", onDismissRequest = { show.value = false }) {
        Column {
            TextField(value = name, onValueChange = { name = it }, label = "名称", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextField(value = url, onValueChange = { url = it }, label = "服务器 URL", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            WindowDropdownPreference(
                title = "传输方式",
                items = listOf("Streamable HTTP", "SSE"),
                selectedIndex = transportIndex,
                onSelectedIndexChange = { transportIndex = it },
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = headers,
                onValueChange = { headers = it },
                label = "自定义请求头 JSON（可选，如 {\"Authorization\":\"Bearer ...\"}）",
                useLabelAsPlaceholder = true,
                maxLines = 3
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = { show.value = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "添加",
                    onClick = { onConfirm(name, transports[transportIndex], url, headers); show.value = false },
                    enabled = url.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
