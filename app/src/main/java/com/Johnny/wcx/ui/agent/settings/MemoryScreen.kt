package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.agent.data.WeAgentSettings
import com.Johnny.wcx.agent.tool.BuiltinToolProvider
import com.Johnny.wcx.agent.workspace.WorkspaceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Memory (§8): a global on/off switch plus a read-only view of the parsed MEMORY.md index. No CRUD
 * here — memory files are managed by the agent itself. If the index fails to parse, a warning is
 * shown that clarifies it is only a display issue and does not affect the agent.
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    // null while loading; ParseResult afterwards.
    var index by remember { mutableStateOf<MemoryIndex?>(null) }

    LaunchedEffect(Unit) {
        enabled = WeAgentSettings.memoryEnabled()
        index = withContext(Dispatchers.IO) { parseMemoryIndex() }
        loaded = true
    }

    AgentSettingsScaffold(title = "记忆", onBack = onBack) {
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = "启用记忆",
                    summary = "允许 AI 通过 /memory/ 读写持久记忆（MEMORY.md 索引 + 记忆文件）",
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        BuiltinToolProvider.fsToolsVisible = on || false // recomputed fully below
                        scope.launch {
                            WeAgentSettings.set(WeAgentSettings.KEY_MEMORY_ENABLED, on.toString())
                            BuiltinToolProvider.fsToolsVisible = on || WeAgentSettings.workspaceEnabled()
                        }
                    },
                )
            }
        }

        if (!loaded) {
            item { EmptyHint("加载中…") }; return@AgentSettingsScaffold
        }

        item { SmallTitle("记忆索引（MEMORY.md）") }
        val idx = index
        when {
            idx == null || idx.parseFailed -> item {
                Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                    Text(
                        "⚠ 记忆索引解析失败，仅影响此处的展示，不影响 AI 使用记忆的能力。",
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            idx.entries.isEmpty() -> item { EmptyHint("记忆索引为空。AI 会在需要时自行写入记忆。") }
            else -> item {
                Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                    idx.entries.forEach { e ->
                        ArrowPreference(title = e.title, summary = e.description, onClick = {})
                    }
                }
            }
        }
    }
}

private data class MemoryIndexEntry(val title: String, val description: String)
private data class MemoryIndex(val entries: List<MemoryIndexEntry>, val parseFailed: Boolean)

/**
 * Parses MEMORY.md's index lines of the form `- [Title](file.md) — description`. Any exception is
 * treated as a parse failure (surfaced as a non-blocking warning).
 */
private fun parseMemoryIndex(): MemoryIndex = runCatching {
    val text = WorkspaceStore.readMemoryIndex()
    val re = Regex("""^\s*[-*]\s*\[([^\]]+)]\([^)]*\)\s*[—\-:]*\s*(.*)$""")
    val entries = text.lineSequence().mapNotNull { line ->
        re.find(line)?.let { MemoryIndexEntry(it.groupValues[1].trim(), it.groupValues[2].trim()) }
    }.toList()
    MemoryIndex(entries, parseFailed = false)
}.getOrElse { MemoryIndex(emptyList(), parseFailed = true) }
