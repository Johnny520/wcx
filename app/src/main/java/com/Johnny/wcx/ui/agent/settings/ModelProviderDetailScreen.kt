package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off
import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.data.entity.ModelEntity
import com.Johnny.wcx.agent.data.entity.ModelProviderEntity
import com.Johnny.wcx.agent.data.entity.ModelProviderType
import com.Johnny.wcx.agent.model.ModelProviderManager
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.UUID

/** Edits one provider (name/url/key) and manages its models (id + reasoning gear + custom JSON). */
@Composable
fun ModelProviderDetailScreen(providerId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf<ModelProviderEntity?>(null) }

    // Connection fields are hoisted to screen scope so both "保存" and "自动导入模型" read the live,
    // possibly-unsaved values. The API key field holds the key exactly as stored — there is no
    // encryption anywhere in the pipeline, so nothing has to be encoded/decoded around it.
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(providerId) {
        val fresh = WeAgentRepository.getModelProvider(providerId)
        provider = fresh
        if (fresh != null) {
            name = fresh.name; baseUrl = fresh.baseUrl; apiKey = fresh.apiKey
        }
    }

    val models by WeAgentRepository.observeModelsForProvider(providerId).collectAsState(initial = emptyList())
    // null = closed; empty-id ModelEntity = adding; existing = editing.
    var editingModel by remember { mutableStateOf<ModelEntity?>(null) }
    // Auto-import state: fetched ids to pick from, plus loading/error.
    var importCandidates by remember { mutableStateOf<List<String>?>(null) }
    var importing by remember { mutableStateOf(false) }
    // API keys are stored in the clear, so at least don't render them in the clear.
    var showApiKey by remember { mutableStateOf(false) }

    val p = provider

    AgentSettingsScaffold(title = p?.name ?: "提供方", onBack = onBack) {
        if (p == null) {
            item { EmptyHint("加载中…") }
            return@AgentSettingsScaffold
        }

        item { SmallTitle("连接") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    TextField(value = name, onValueChange = { name = it }, label = "名称", useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = "Base URL", useLabelAsPlaceholder = true, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = "API Key",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showApiKey = !showApiKey }) {
                                Icon(
                                    imageVector = if (showApiKey) MaterialSymbols.Outlined.Visibility_off
                                    else MaterialSymbols.Outlined.Visibility,
                                    contentDescription = if (showApiKey) "隐藏" else "显示",
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(
                            text = "删除提供方",
                            onClick = { scope.launch { WeAgentRepository.deleteModelProvider(p.id); onBack() } },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            text = "保存",
                            onClick = {
                                scope.launch {
                                    val updated = p.copy(name = name, baseUrl = baseUrl, apiKey = apiKey)
                                    WeAgentRepository.upsertModelProvider(updated)
                                    ModelProviderManager.invalidate(p.id)
                                    // Keep the local copy in sync so the scaffold title reflects a rename
                                    // (LaunchedEffect(providerId) only runs once, on first composition).
                                    provider = updated
                                }
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item { SmallTitle("模型") }
        if (models.isEmpty()) item { EmptyHint("还没有模型。") }
        items(models.size, key = { models[it].id }) { i ->
            val m = models[i]
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = m.displayName.ifBlank { m.modelIdRemote },
                    summary = "id=${m.modelIdRemote}" +
                            (m.reasoningEffort?.let { " · effort=$it" } ?: "") +
                            (m.contextWindow?.let { " · ctx=$it" } ?: "") +
                            (m.maxTokens?.let { " · max=$it" } ?: "") +
                            if (m.supportsVision) " · 视觉" else "",
                    onClick = { editingModel = m }
                )
            }
        }
        item {
            Button(
                onClick = { editingModel = ModelEntity("", providerId, "", null, null, "", null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) { Text("添加模型") }
        }
        // Auto-import is only meaningful for the OpenAI-style /models endpoint.
        if (p.type != ModelProviderType.ANTHROPIC_MESSAGES) {
            item {
                TextButton(
                    text = if (importing) "获取模型列表中…" else "自动导入模型",
                    enabled = !importing,
                    onClick = {
                        importing = true
                        scope.launch {
                            // Use the live (possibly unsaved) connection fields, per project decision.
                            val result = ModelProviderManager.listRemoteModels(
                                p.copy(name = name, baseUrl = baseUrl, apiKey = apiKey)
                            )
                            importing = false
                            result.fold(
                                onSuccess = { importCandidates = it },
                                onFailure = { showToast("获取失败：${it.message}") },
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AGENT_CONTENT_BOTTOM_INSET),
                )
            }
        }
    }

    ImportModelsDialog(
        show = importCandidates != null,
        candidates = importCandidates.orEmpty(),
        existingRemoteIds = models.map { it.modelIdRemote }.toSet(),
        onDismiss = { importCandidates = null },
        onImport = { picked ->
            scope.launch {
                val added = WeAgentRepository.importModels(providerId, picked)
                showToast("已导入 $added 个模型")
            }
            importCandidates = null
        },
    )

    ModelDialog(
        show = editingModel != null,
        existing = editingModel ?: ModelEntity("", providerId, "", null, null, "", null),
        onDismiss = { editingModel = null },
        onDelete = editingModel?.id?.takeIf { it.isNotEmpty() }?.let { id -> { scope.launch { WeAgentRepository.deleteModel(id) }; editingModel = null } },
        onSave = { remoteId, display, effort, customJson, contextWindow, maxTokens, supportsVision ->
            editingModel?.let { m ->
                scope.launch {
                    WeAgentRepository.upsertModel(
                        m.copy(
                            id = m.id.ifEmpty { UUID.randomUUID().toString() },
                            providerId = providerId,
                            modelIdRemote = remoteId,
                            reasoningEffort = effort,
                            customJsonOverride = customJson,
                            displayName = display.ifBlank { remoteId },
                            contextWindow = contextWindow,
                            maxTokens = maxTokens,
                            supportsVision = supportsVision,
                        )
                    )
                }
            }
            editingModel = null
        },
    )
}

/** Reasoning-effort gears. "off" means omit the field entirely. */
private val EFFORT_GEARS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

/**
 * Model-import picker: lists ids fetched from the provider's `/models` endpoint. Ids already added
 * are shown checked+disabled; the rest start selected. Confirming imports the selected new ones.
 */
@Composable
private fun ImportModelsDialog(
    show: Boolean,
    candidates: List<String>,
    existingRemoteIds: Set<String>,
    onDismiss: () -> Unit,
    onImport: (List<String>) -> Unit,
) {
    // Pre-select every not-yet-added id. Keyed on [candidates] because the dialog is composed
    // unconditionally: on first composition nothing has been fetched yet, so an unkeyed remember
    // would freeze an empty selection (and carry the previous run's ticks into the next import).
    val selected = remember(candidates) {
        mutableStateListOf<String>().apply { addAll(candidates.filter { it !in existingRemoteIds }) }
    }

    WindowDialog(show = show, title = "导入模型（${candidates.size}）", onDismissRequest = onDismiss) {
        Column {
            if (candidates.isEmpty()) {
                Text("该提供方未返回任何模型。")
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates.size, key = { candidates[it] }) { i ->
                        val id = candidates[i]
                        val already = id in existingRemoteIds
                        val checked = already || id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !already) { if (id in selected) selected.remove(id) else selected.add(id) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (checked) "☑" else "☐", modifier = Modifier.width(28.dp))
                            Text(
                                id + if (already) "（已添加）" else "",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "导入（${selected.size}）",
                    onClick = { onImport(selected.toList()) },
                    enabled = selected.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ModelDialog(
    show: Boolean,
    existing: ModelEntity,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (remoteId: String, display: String, effort: String?, customJson: String?, contextWindow: Int?, maxTokens: Int?, supportsVision: Boolean) -> Unit,
) {
    // Every field is keyed on [existing] (and [show]): the dialog is composed unconditionally, with a
    // blank placeholder entity while nothing is being edited, so unkeyed state would capture those
    // blanks and 保存 would then overwrite the stored model's whole config with them.
    var remoteId by remember(existing, show) { mutableStateOf(existing.modelIdRemote) }
    var display by remember(existing, show) { mutableStateOf(existing.displayName) }
    var customJson by remember(existing, show) { mutableStateOf(existing.customJsonOverride.orEmpty()) }
    var contextWindow by remember(existing, show) { mutableStateOf(existing.contextWindow?.toString().orEmpty()) }
    var maxTokens by remember(existing, show) { mutableStateOf(existing.maxTokens?.toString().orEmpty()) }
    var supportsVision by remember(existing, show) { mutableStateOf(existing.supportsVision) }
    var effortIndex by remember(existing, show) { mutableIntStateOf(EFFORT_GEARS.indexOf(existing.reasoningEffort ?: "off").coerceAtLeast(0)) }

    WindowDialog(show = show, title = if (existing.id.isEmpty()) "添加模型" else "编辑模型", onDismissRequest = onDismiss) {
        Column {
            TextField(value = remoteId, onValueChange = { remoteId = it }, label = "模型 ID（传给 API）", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextField(value = display, onValueChange = { display = it }, label = "显示名称（可选）", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            WindowDropdownPreference(
                title = "思考强度",
                items = EFFORT_GEARS,
                selectedIndex = effortIndex,
                onSelectedIndexChange = { effortIndex = it },
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = contextWindow,
                onValueChange = { v -> contextWindow = v.filter { it.isDigit() }.take(9) },
                label = "上下文窗口（token，可选，用于显示占用百分比）",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = maxTokens,
                onValueChange = { v -> maxTokens = v.filter { it.isDigit() }.take(9) },
                label = "最大输出 token（可选，限制单次回复长度）",
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            TextField(value = customJson, onValueChange = { customJson = it }, label = "自定义 JSON 透传（可选）", useLabelAsPlaceholder = true, maxLines = 4)
            Spacer(Modifier.height(8.dp))
            SwitchPreference(
                title = "支持视觉（图片输入）",
                summary = "开启后 AI 才能使用 ui-screenshot 截图工具查看界面",
                checked = supportsVision,
                onCheckedChange = { supportsVision = it },
            )
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
                    onClick = {
                        val effort = EFFORT_GEARS[effortIndex].takeIf { it != "off" }
                        onSave(remoteId, display, effort, customJson.ifBlank { null }, contextWindow.toIntOrNull(), maxTokens.toIntOrNull(), supportsVision)
                    },
                    enabled = remoteId.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
