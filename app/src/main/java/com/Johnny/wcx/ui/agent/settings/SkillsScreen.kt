package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.agent.skill.SkillStore
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Skills management (§ Skills): add/edit/delete skills and toggle each on/off globally. Skills are
 * `SKILL.md` files under `moduleData/agent/skills/<name>/`; only enabled ones are advertised to the
 * model (as a name+description catalog), and the model loads a skill's body via the `load_skill`
 * tool — the dynamic-discovery model.
 */
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // SkillStore is filesystem-backed (no Flow); reload via a tick after each mutation.
    var reloadTick by remember { mutableStateOf(0) }
    var skills by remember { mutableStateOf<List<SkillStore.Skill>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(reloadTick) {
        skills = withContext(Dispatchers.IO) { SkillStore.list() }
    }

    // null = closed; Skill(...) = editing existing; empty-name Skill = adding new.
    var editing by remember { mutableStateOf<SkillStore.Skill?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    AgentSettingsScaffold(title = "技能", onBack = onBack) {
        if (skills.isEmpty()) item { EmptyHint("还没有技能。技能是针对特定任务的操作手册，LLM 会按需加载。") }
        items(skills.size, key = { skills[it].name }) { i ->
            val s = skills[i]
            Card(Modifier.padding(bottom = 6.dp)) {
                SwitchPreference(
                    title = s.name,
                    summary = s.description.ifBlank { "（无简介）" },
                    checked = s.enabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            withContext(Dispatchers.IO) { SkillStore.setEnabled(s.name, on) }
                            reloadTick++
                        }
                    },
                )
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    TextButton(text = "编辑", onClick = { editing = s; showEditor = true }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        text = "删除",
                        onClick = { scope.launch { withContext(Dispatchers.IO) { SkillStore.delete(s.name) }; reloadTick++ } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        item {
            Button(
                onClick = { editing = null; showEditor = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text("添加技能") }
        }
    }

    SkillEditorDialog(
        show = showEditor,
        existing = editing,
        // Clear [editing] too: the editor's field state is keyed on it, so leaving it set would keep
        // the abandoned edits alive and re-show them the next time the same skill is opened.
        onDismiss = { showEditor = false; editing = null },
        onSave = { name, description, body ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { SkillStore.save(name, description, body) }
                if (ok == null) showToast("技能名称无效")
                else {
                    // Renaming isn't in-place: if the dir name changed, drop the old one.
                    editing?.name?.takeIf { it != ok }?.let { old ->
                        withContext(Dispatchers.IO) { SkillStore.delete(old) }
                    }
                    reloadTick++
                    showEditor = false
                }
            }
        },
    )
}

@Composable
private fun SkillEditorDialog(
    show: Boolean,
    existing: SkillStore.Skill?,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, body: String) -> Unit,
) {
    var name by remember(existing) { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember(existing) { mutableStateOf(existing?.description.orEmpty()) }
    var body by remember(existing) { mutableStateOf(existing?.body.orEmpty()) }

    WindowDialog(show = show, title = if (existing == null) "添加技能" else "编辑技能", onDismissRequest = onDismiss) {
        Column {
            TextField(value = name, onValueChange = { name = it }, label = "技能名称（同时作为目录名）", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextField(
                value = description,
                onValueChange = { description = it },
                label = "简介（决定 LLM 何时加载此技能）",
                useLabelAsPlaceholder = true,
                maxLines = 3
            )
            Spacer(Modifier.height(8.dp))
            TextField(value = body, onValueChange = { body = it }, label = "技能正文（SKILL.md 指令内容）", useLabelAsPlaceholder = true, maxLines = 12)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "保存",
                    onClick = { onSave(name, description, body) },
                    enabled = name.isNotBlank() && body.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
