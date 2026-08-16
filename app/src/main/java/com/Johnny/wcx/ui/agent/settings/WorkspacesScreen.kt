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
import com.Johnny.wcx.agent.data.entity.WorkspaceEntity
import com.Johnny.wcx.agent.workspace.WorkspaceStore
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

/** Workspace directory management (§7). Each workspace's name doubles as its on-disk folder. No
 *  global switch — whether a workspace is used is per-session state. Tapping a row edits (renames). */
@Composable
fun WorkspacesScreen(onBack: () -> Unit) {
    val workspaces by WeAgentRepository.observeWorkspaces().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val showAdd = remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceEntity?>(null) }

    AgentSettingsScaffold(title = "工作区", onBack = onBack) {
        if (workspaces.isEmpty()) item { EmptyHint("还没有工作区。会话可绑定一个工作区以启用文件读写。") }
        items(workspaces.size, key = { workspaces[it].id }) { i ->
            val w = workspaces[i]
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = w.name,
                    summary = "/workspace/ → ${w.name}",
                    onClick = { editing = w },
                )
            }
        }
        item {
            Button(
                onClick = { showAdd.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text("添加工作区") }
        }
    }

    AddWorkspaceDialog(showAdd) { name ->
        when (val v = WorkspaceStore.validateWorkspaceName(name)) {
            is WorkspaceStore.NameValidation.Invalid -> showToast(v.reason)
            WorkspaceStore.NameValidation.Ok -> scope.launch {
                val n = name.trim()
                WorkspaceStore.workspaceDir(n) // create the real folder
                WeAgentRepository.upsertWorkspace(WorkspaceEntity(UUID.randomUUID().toString(), n))
            }
        }
    }

    EditWorkspaceDialog(
        show = editing != null,
        initialName = editing?.name.orEmpty(),
        onDismiss = { editing = null },
        onDelete = { editing?.let { w -> scope.launch { WeAgentRepository.deleteWorkspace(w.id) } }; editing = null },
        onRename = { newName ->
            editing?.let { w ->
                when (val v = WorkspaceStore.validateWorkspaceName(newName)) {
                    is WorkspaceStore.NameValidation.Invalid -> showToast(v.reason)
                    WorkspaceStore.NameValidation.Ok -> scope.launch {
                        if (WorkspaceStore.renameWorkspaceDir(w.name, newName.trim())) {
                            WeAgentRepository.upsertWorkspace(w.copy(name = newName.trim()))
                        } else {
                            showToast("重命名失败：目标名称已存在或无效")
                        }
                    }.also { editing = null }
                }
            }
        },
    )
}

@Composable
private fun EditWorkspaceDialog(
    show: Boolean,
    initialName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    WindowDialog(show = show, title = "编辑工作区", onDismissRequest = onDismiss) {
        Column {
            TextField(value = name, onValueChange = { name = it }, label = "工作区名称（会重命名真实文件夹）", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "删除", onClick = onDelete, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = "保存",
                    onClick = { onRename(name) },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AddWorkspaceDialog(
    show: androidx.compose.runtime.MutableState<Boolean>,
    onConfirm: (String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    WindowDialog(show = show.value, title = "添加工作区", onDismissRequest = { show.value = false }) {
        Column {
            TextField(value = name, onValueChange = { name = it }, label = "工作区名称（同时作为目录名）", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = { show.value = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "添加",
                    onClick = { onConfirm(name); show.value = false },
                    enabled = name.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
