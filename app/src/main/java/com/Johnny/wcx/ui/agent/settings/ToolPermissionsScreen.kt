package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.tool.BuiltinToolProvider
import com.Johnny.wcx.agent.tool.ProviderTool
import com.Johnny.wcx.agent.tool.ToolMode
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * Lists the built-in tool providers (builtin-wechat / builtin-wechat-sql / builtin-fs). Each drills
 * into a per-provider [ToolPermissionListScreen]. Pinned & undeletable.
 */
@Composable
fun BuiltinProvidersScreen(onBack: () -> Unit, onOpenProvider: (providerId: String, name: String) -> Unit) {
    AgentSettingsScaffold(title = "内置工具", onBack = onBack) {
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                BuiltinToolProvider.all.forEach { p ->
                    ArrowPreference(
                        title = p.name,
                        summary = "${p.id} · ${p.seedInfos().size} 个工具",
                        onClick = { onOpenProvider(p.id, p.name) },
                    )
                }
            }
        }
    }
}

/**
 * Per-provider four-state permission editor (§3.2), reused for both a built-in provider and an MCP
 * server. [tools] are the provider's advertised tools (name + factory default). Changes persist
 * immediately via [WeAgentRepository.setToolMode] and take effect on the next request.
 */
@Composable
fun ToolPermissionListScreen(
    title: String,
    providerId: String,
    tools: List<Pair<String, ToolMode>>,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val perms by WeAgentRepository.observeToolPermissions().collectAsState(initial = emptyList())
    val permMap = perms.associate { (it.providerId to it.toolName) to it.mode }

    AgentSettingsScaffold(title = title, onBack = onBack) {
        if (tools.isEmpty()) item { EmptyHint("该提供方暂无可用工具。") }
        items(tools.size, key = { "${providerId}_${tools[it].first}" }) { i ->
            val (name, default) = tools[i]
            val mode = permMap[providerId to name] ?: default
            Card(Modifier.padding(bottom = 6.dp)) {
                ToolModeDropdown(name, mode) { newMode ->
                    scope.launch { WeAgentRepository.setToolMode(providerId, name, newMode) }
                }
            }
        }
    }
}

/** Convenience: builds the (name, factoryDefault) list for a built-in provider by id. */
fun builtinProviderTools(providerId: String): List<Pair<String, ToolMode>> =
    BuiltinToolProvider.all.firstOrNull { it.id == providerId }
        ?.seedInfos()?.map { it.name to it.defaultMode }
        ?: emptyList()

/** Convenience: builds the (name, factoryDefault) list from a set of [ProviderTool]s (MCP). */
fun providerToolPairs(tools: List<ProviderTool>): List<Pair<String, ToolMode>> =
    tools.map { it.name to it.factoryDefaultMode }

private val MODE_ORDER = listOf(ToolMode.ENABLED, ToolMode.MANUAL_APPROVAL, ToolMode.SMART_APPROVAL, ToolMode.DISABLED)

private fun ToolMode.label(): String = when (this) {
    ToolMode.ENABLED -> "直接允许"
    ToolMode.MANUAL_APPROVAL -> "手动审批"
    ToolMode.SMART_APPROVAL -> "智能审批"
    ToolMode.DISABLED -> "禁用"
}

@Composable
private fun ToolModeDropdown(name: String, mode: ToolMode, onChange: (ToolMode) -> Unit) {
    WindowDropdownPreference(
        title = name,
        items = MODE_ORDER.map { it.label() },
        selectedIndex = MODE_ORDER.indexOf(mode).coerceAtLeast(0),
        onSelectedIndexChange = { onChange(MODE_ORDER[it]) },
    )
}
