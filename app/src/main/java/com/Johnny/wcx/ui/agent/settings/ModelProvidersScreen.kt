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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.data.entity.ModelProviderEntity
import com.Johnny.wcx.agent.data.entity.ModelProviderType
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

/** Lists model providers; opens each for editing; adds a new one via dialog (§5.1/§5.2). */
@Composable
fun ModelProvidersScreen(
    onBack: () -> Unit,
    onOpenProvider: (String) -> Unit,
) {
    val providers by WeAgentRepository.observeModelProviders().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val showAdd = remember { mutableStateOf(false) }

    AgentSettingsScaffold(title = "模型提供方", onBack = onBack) {
        if (providers.isEmpty()) {
            item { EmptyHint("还没有模型提供方，点击下方按钮添加。") }
        }
        items(providers.size, key = { providers[it].id }) { i ->
            val p = providers[i]
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = p.name.ifBlank { p.baseUrl },
                    summary = "${p.type.label()} · ${p.baseUrl}",
                    onClick = { onOpenProvider(p.id) },
                )
            }
        }
        item {
            Button(
                onClick = { showAdd.value = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = AGENT_CONTENT_BOTTOM_INSET),
            ) { Text("添加提供方") }
        }
    }

    AddProviderDialog(showAdd) { name, type, baseUrl, apiKey ->
        scope.launch {
            WeAgentRepository.upsertModelProvider(
                ModelProviderEntity(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    name = name,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                )
            )
        }
    }
}

@Composable
private fun AddProviderDialog(
    show: androidx.compose.runtime.MutableState<Boolean>,
    onConfirm: (name: String, type: ModelProviderType, baseUrl: String, apiKey: String) -> Unit,
) {
    var name by remember(show.value) { mutableStateOf("") }
    var baseUrl by remember(show.value) { mutableStateOf("https://api.openai.com/v1") }
    var apiKey by remember(show.value) { mutableStateOf("") }
    var typeIndex by remember(show.value) { mutableIntStateOf(0) }
    val types = listOf(
        ModelProviderType.OPENAI_CHAT_COMPLETION,
        ModelProviderType.OPENAI_RESPONSES,
        ModelProviderType.ANTHROPIC_MESSAGES,
        ModelProviderType.GEMINI_GENERATE_CONTENT,
        ModelProviderType.GEMINI_INTERACTIONS
    )
    val selectedType = types[typeIndex]

    WindowDialog(show = show.value, title = "添加模型提供方", onDismissRequest = { show.value = false }) {
        Column {
            TextField(value = name, onValueChange = { name = it }, label = "名称", useLabelAsPlaceholder = true)
            Spacer(Modifier.height(8.dp))
            TextField(value = baseUrl, onValueChange = { baseUrl = it }, label = "Base URL", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            TextField(value = apiKey, onValueChange = { apiKey = it }, label = "API Key", useLabelAsPlaceholder = true, singleLine = true)
            Spacer(Modifier.height(8.dp))
            WindowDropdownPreference(
                title = "接口类型",
                items = types.map { it.label() },
                selectedIndex = typeIndex,
                onSelectedIndexChange = { typeIndex = it },
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                TextButton(text = "取消", onClick = { show.value = false }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = "添加",
                    onClick = {
                        onConfirm(name.ifBlank { selectedType.label() }, selectedType, baseUrl, apiKey)
                        show.value = false
                    },
                    enabled = baseUrl.isNotBlank(),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

fun ModelProviderType.label(): String = when (this) {
    ModelProviderType.OPENAI_CHAT_COMPLETION -> "OpenAI Chat Completion"
    ModelProviderType.OPENAI_RESPONSES -> "OpenAI Responses"
    ModelProviderType.ANTHROPIC_MESSAGES -> "Anthropic Messages"
    ModelProviderType.GEMINI_GENERATE_CONTENT -> "Gemini generateContent (legacy)"
    ModelProviderType.GEMINI_INTERACTIONS -> "Gemini Interactions"
}
