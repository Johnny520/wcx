package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.data.entity.ConditionalPromptEntity
import com.Johnny.wcx.agent.data.entity.PerTurnPromptEntity
import com.Johnny.wcx.agent.data.entity.PresetPromptEntity
import com.Johnny.wcx.agent.data.entity.SystemPromptEntity
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

/**
 * Prompts (§6), flattened — the "role/profile" concept is gone. One page with four sections:
 *  - 系统提示词: named prompts, bound per-session; no switch (exist / not).
 *  - 每轮提示词: each has a global enable switch (prepended to every user message when on).
 *  - 条件提示词: each has a global enable switch (regex-matched against replies when on).
 *  - 预设提示词: reusable snippets to insert into the input; no switch.
 * Tapping an item edits it; each section has an add button at the bottom.
 */
@Composable
fun PromptsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val systemPrompts by WeAgentRepository.observeSystemPrompts().collectAsState(initial = emptyList())
    val perTurn by WeAgentRepository.observePerTurnPrompts().collectAsState(initial = emptyList())
    val conditionals by WeAgentRepository.observeConditionalPrompts().collectAsState(initial = emptyList())
    val presets by WeAgentRepository.observePresetPrompts().collectAsState(initial = emptyList())

    // Editors: null = closed. Empty-id entity = adding new.
    var editSystem by remember { mutableStateOf<SystemPromptEntity?>(null) }
    var editPerTurn by remember { mutableStateOf<PerTurnPromptEntity?>(null) }
    var editConditional by remember { mutableStateOf<ConditionalPromptEntity?>(null) }
    var editPreset by remember { mutableStateOf<PresetPromptEntity?>(null) }

    AgentSettingsScaffold(title = "提示词", onBack = onBack) {
        // -------- 系统提示词 --------
        item { SmallTitle("系统提示词") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                if (systemPrompts.isEmpty()) EmptyHint("还没有系统提示词。会话可绑定其中一个。")
                systemPrompts.forEach { sp ->
                    ArrowPreference(title = sp.name, summary = sp.content.take(48), onClick = { editSystem = sp })
                }
                AddRow("新增系统提示词") { editSystem = SystemPromptEntity("", "", "") }
            }
        }

        // -------- 每轮提示词 --------
        item { SmallTitle("每轮提示词") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                if (perTurn.isEmpty()) EmptyHint("还没有每轮提示词。开启后会追加到每条用户消息前。")
                perTurn.forEach { p ->
                    SwitchPreference(
                        title = p.title.ifBlank { p.content.take(24) },
                        summary = p.content.take(48),
                        checked = p.enabled,
                        onCheckedChange = { on -> scope.launch { WeAgentRepository.upsertPerTurnPrompt(p.copy(enabled = on)) } },
                    )
                    TextButton(text = "编辑", onClick = { editPerTurn = p }, modifier = Modifier.padding(horizontal = 12.dp))
                }
                AddRow("新增每轮提示词") { editPerTurn = PerTurnPromptEntity("", "", "", true) }
            }
        }

        // -------- 条件提示词 --------
        item { SmallTitle("条件提示词") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                if (conditionals.isEmpty()) EmptyHint("还没有条件提示词。开启后按正则匹配模型回复并注入内容。")
                conditionals.forEach { c ->
                    SwitchPreference(
                        title = "/${c.regex}/",
                        summary = c.content.take(48),
                        checked = c.enabled,
                        onCheckedChange = { on -> scope.launch { WeAgentRepository.upsertConditionalPrompt(c.copy(enabled = on)) } },
                    )
                    TextButton(text = "编辑", onClick = { editConditional = c }, modifier = Modifier.padding(horizontal = 12.dp))
                }
                AddRow("新增条件提示词") { editConditional = ConditionalPromptEntity("", "", "", true) }
            }
        }

        // -------- 预设提示词 --------
        item { SmallTitle("预设提示词") }
        item {
            Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                if (presets.isEmpty()) EmptyHint("还没有预设提示词。可在对话输入框的 + 菜单里插入。")
                presets.forEach { p ->
                    ArrowPreference(title = p.title, summary = p.content.take(48), onClick = { editPreset = p })
                }
                AddRow("新增预设提示词") { editPreset = PresetPromptEntity("", "", "") }
            }
        }
    }

    // -------- Editors --------
    TwoFieldEditor(
        show = editSystem != null,
        title = if (editSystem?.id.isNullOrEmpty()) "新增系统提示词" else "编辑系统提示词",
        field1Label = "名称", field1 = editSystem?.name.orEmpty(),
        field2Label = "系统提示词内容", field2 = editSystem?.content.orEmpty(), field2MaxLines = 12,
        onDismiss = { editSystem = null },
        onDelete = editSystem?.id?.takeIf { it.isNotEmpty() }?.let { id -> { scope.launch { WeAgentRepository.deleteSystemPrompt(id) }; editSystem = null } },
        onSave = { name, content ->
            editSystem?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertSystemPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            name = name,
                            content = content
                        )
                    )
                }
            }
            editSystem = null
        },
    )
    TwoFieldEditor(
        show = editPerTurn != null,
        title = if (editPerTurn?.id.isNullOrEmpty()) "新增每轮提示词" else "编辑每轮提示词",
        field1Label = "标题（可选）", field1 = editPerTurn?.title.orEmpty(),
        field2Label = "每轮提示词内容", field2 = editPerTurn?.content.orEmpty(), field2MaxLines = 8,
        onDismiss = { editPerTurn = null },
        onDelete = editPerTurn?.id?.takeIf { it.isNotEmpty() }
            ?.let { id -> { scope.launch { WeAgentRepository.deletePerTurnPrompt(id) }; editPerTurn = null } },
        onSave = { title, content ->
            editPerTurn?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertPerTurnPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            title = title,
                            content = content
                        )
                    )
                }
            }
            editPerTurn = null
        },
    )
    TwoFieldEditor(
        show = editConditional != null,
        title = if (editConditional?.id.isNullOrEmpty()) "新增条件提示词" else "编辑条件提示词",
        field1Label = "触发正则", field1 = editConditional?.regex.orEmpty(),
        field2Label = "注入内容", field2 = editConditional?.content.orEmpty(), field2MaxLines = 8,
        onDismiss = { editConditional = null },
        onDelete = editConditional?.id?.takeIf { it.isNotEmpty() }
            ?.let { id -> { scope.launch { WeAgentRepository.deleteConditionalPrompt(id) }; editConditional = null } },
        onSave = { regex, content ->
            editConditional?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertConditionalPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            regex = regex,
                            content = content
                        )
                    )
                }
            }
            editConditional = null
        },
    )
    TwoFieldEditor(
        show = editPreset != null,
        title = if (editPreset?.id.isNullOrEmpty()) "新增预设提示词" else "编辑预设提示词",
        field1Label = "标题", field1 = editPreset?.title.orEmpty(),
        field2Label = "预设内容", field2 = editPreset?.content.orEmpty(), field2MaxLines = 8,
        onDismiss = { editPreset = null },
        onDelete = editPreset?.id?.takeIf { it.isNotEmpty() }?.let { id -> { scope.launch { WeAgentRepository.deletePresetPrompt(id) }; editPreset = null } },
        onSave = { title, content ->
            editPreset?.let { entity ->
                scope.launch {
                    WeAgentRepository.upsertPresetPrompt(
                        entity.copy(
                            id = entity.id.ifEmpty { UUID.randomUUID().toString() },
                            title = title,
                            content = content
                        )
                    )
                }
            }
            editPreset = null
        },
    )
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    TextButton(text = label, onClick = onClick, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

/**
 * A generic two-field editor dialog (name/title + a multiline body), with optional delete. Used for
 * all four prompt kinds since they share the same shape.
 */
@Composable
private fun TwoFieldEditor(
    show: Boolean,
    title: String,
    field1Label: String,
    field1: String,
    field2Label: String,
    field2: String,
    field2MaxLines: Int,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String) -> Unit,
) {
    // Keyed on the incoming values (and [show]): all four editors are composed unconditionally, so on
    // first composition nothing is being edited and [field1]/[field2] are blank. Unkeyed state would
    // freeze those blanks and 保存 would then wipe the prompt's name/regex with an empty string.
    var f1 by remember(field1, show) { mutableStateOf(field1) }
    var f2 by remember(field2, show) { mutableStateOf(field2) }
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            TextField(value = f1, onValueChange = { f1 = it }, label = field1Label, useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextField(value = f2, onValueChange = { f2 = it }, label = field2Label, useLabelAsPlaceholder = true, maxLines = field2MaxLines)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                if (onDelete != null) {
                    TextButton(text = "删除", onClick = onDelete, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "保存",
                    onClick = { onSave(f1, f2) },
                    enabled = f2.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
