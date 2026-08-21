package com.Johnny.wcx.ui.agent.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.activity.agent.AgentSettingsScreen
import com.Johnny.wcx.agent.data.OverlayMode
import com.Johnny.wcx.agent.data.WeAgentRepository
import com.Johnny.wcx.agent.data.WeAgentSettings
import com.Johnny.wcx.agent.data.entity.ModelEntity
import com.Johnny.wcx.features.api.agent.WeAgentService
import com.Johnny.wcx.features.items.system.agent.WeAgentOverlayController
import com.Johnny.wcx.ui.content.MiuixSmallTitle
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference

/**
 * WeAgent settings home.
 */
@Composable
fun WeAgentHomeScreen(onOpen: (AgentSettingsScreen) -> Unit) {
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var dynamicTools by remember { mutableStateOf(false) }
    var overlayMode by remember { mutableStateOf(OverlayMode.ALWAYS) }
    var sendWhileRunning by remember { mutableStateOf("QUEUE_AFTER_TURN") }
    var maxRequests by remember { mutableStateOf(WeAgentSettings.DEFAULT_MAX_MODEL_REQUESTS.toString()) }
    var smallModelId by remember { mutableStateOf<String?>(null) }
    var defaultModelId by remember { mutableStateOf<String?>(null) }
    var defaultSystemPromptId by remember { mutableStateOf<String?>(null) }
    var defaultWorkspaceId by remember { mutableStateOf<String?>(null) }

    // These must come from the live DB flows, not a one-shot read: MiuixStackNavigator keeps the whole
    // stack composed, so this screen never leaves composition and a LaunchedEffect(Unit) would never
    // re-run. A model/prompt/workspace added on a child screen has to show up in these dropdowns as
    // soon as the user comes back.
    val models by remember { WeAgentRepository.observeModels() }.collectAsState(initial = emptyList())
    val systemPrompts by remember { WeAgentRepository.observeSystemPrompts() }.collectAsState(initial = emptyList())
    val workspaces by remember { WeAgentRepository.observeWorkspaces() }.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        dynamicTools = WeAgentSettings.toolLoadingMode() == com.Johnny.wcx.agent.tool.ToolLoadingMode.DYNAMIC
        overlayMode = WeAgentSettings.overlayMode()
        sendWhileRunning = WeAgentSettings.sendWhileRunningMode().name
        maxRequests = WeAgentSettings.maxModelRequests().toString()
        smallModelId = WeAgentSettings.smallModelId()
        defaultModelId = WeAgentSettings.defaultModelId()
        defaultSystemPromptId = WeAgentSettings.defaultSystemPromptId()
        defaultWorkspaceId = WeAgentSettings.defaultWorkspaceId()
        loaded = true
    }

    AgentSettingsScaffold(title = "WeAgent 设置", onBack = null) {
        // ---------- 界面 ----------
        item { MiuixSmallTitle("界面") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                if (loaded) {
                    WindowDropdownPreference(
                        title = "悬浮窗模式",
                        summary = "悬浮球何时显示（禁用后仍可从聊天工具栏唤起）",
                        items = OverlayMode.entries.map { it.label },
                        selectedIndex = OverlayMode.entries.indexOf(overlayMode),
                        onSelectedIndexChange = {
                            val mode = OverlayMode.entries[it]
                            overlayMode = mode
                            WeAgentOverlayController.setMode(mode)
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_OVERLAY_MODE, mode.name) }
                        },
                    )
                }
            }
        }

        // ---------- 模型 ----------
        item { MiuixSmallTitle("模型") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = "模型提供方",
                    summary = "配置 OpenAI / Anthropic / Gemini 服务器、API Key、模型",
                    onClick = { onOpen(AgentSettingsScreen.ModelProviders) },
                )
                if (loaded) {
                    // 文本在左，短输入框在右
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("每轮请求上限", modifier = Modifier.weight(1f))
                        TextField(
                            value = maxRequests,
                            onValueChange = { v -> maxRequests = v.filter { it.isDigit() }.take(3) },
                            label = "",
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    ModelDropdown(
                        title = "审批 / 标题小模型",
                        models = models,
                        selectedId = smallModelId,
                        noneLabel = "（与主模型相同）",
                    ) { id ->
                        smallModelId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SMALL_MODEL_ID, id.orEmpty()) }
                    }
                    WindowDropdownPreference(
                        title = "运行中发送行为",
                        items = listOf("队列（本轮结束后发送）", "引导（下次请求前插入）"),
                        selectedIndex = if (sendWhileRunning == "QUEUE_AS_STEER") 1 else 0,
                        onSelectedIndexChange = {
                            val mode = if (it == 1) "QUEUE_AS_STEER" else "QUEUE_AFTER_TURN"
                            sendWhileRunning = mode
                            WeAgentService.sendWhileRunningMode.value =
                                if (mode == "QUEUE_AS_STEER") WeAgentService.SendWhileRunningMode.QUEUE_AS_STEER
                                else WeAgentService.SendWhileRunningMode.QUEUE_AFTER_TURN
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_SEND_WHILE_RUNNING, mode) }
                        },
                    )
                }
            }
        }

        // ---------- 工具 ----------
        item { MiuixSmallTitle("工具") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = "内置工具",
                    summary = "微信操作 / 数据库 SQL / 文件与技能，逐项设置权限",
                    onClick = { onOpen(AgentSettingsScreen.BuiltinTools) },
                )
                ArrowPreference(
                    title = "MCP 服务器",
                    summary = "添加 Streamable HTTP / SSE 服务器",
                    onClick = { onOpen(AgentSettingsScreen.McpServers) },
                )
                if (loaded) {
                    SwitchPreference(
                        title = "动态工具发现",
                        summary = "仅提供 discover_tools 元工具，按需暴露其余工具（工具很多时省 token）",
                        checked = dynamicTools,
                        onCheckedChange = {
                            dynamicTools = it
                            scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_TOOL_LOADING_MODE, if (it) "DYNAMIC" else "STATIC") }
                        },
                    )
                }
                ArrowPreference(
                    title = "工作区",
                    summary = "文件工作区目录管理",
                    onClick = { onOpen(AgentSettingsScreen.Workspaces) },
                )
                ArrowPreference(
                    title = "记忆",
                    summary = "全局开关与记忆索引查看",
                    onClick = { onOpen(AgentSettingsScreen.Memory) },
                )
                ArrowPreference(
                    title = "外部服务",
                    summary = "Exa Search、Brave Search 等网络工具的 API Key",
                    onClick = { onOpen(AgentSettingsScreen.ExternalServices) },
                )
            }
        }

        // ---------- 上下文 ----------
        item { MiuixSmallTitle("上下文") }
        item {
            Card(Modifier.padding(bottom = 6.dp)) {
                ArrowPreference(
                    title = "提示词",
                    summary = "系统 / 每轮 / 条件 / 预设 提示词",
                    onClick = { onOpen(AgentSettingsScreen.Prompts) },
                )
                ArrowPreference(
                    title = "技能",
                    summary = "任务操作手册, 可被 LLM 动态发现并按需加载",
                    onClick = { onOpen(AgentSettingsScreen.Skills) },
                )
                ArrowPreference(
                    title = "触发器",
                    summary = "定时 / 新消息 / 数据库事件自动唤起 AI, 支持会话级与全局触发器",
                    onClick = { onOpen(AgentSettingsScreen.Triggers) },
                )
            }
        }

        // ---------- 默认 ----------
        if (loaded) {
            item { MiuixSmallTitle("默认") }
            item {
                Card(Modifier.padding(bottom = AGENT_CONTENT_BOTTOM_INSET)) {
                    ModelDropdown(
                        title = "默认模型",
                        models = models,
                        selectedId = defaultModelId,
                        noneLabel = "（使用第一个模型）",
                    ) { id ->
                        defaultModelId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_MODEL_ID, id.orEmpty()) }
                    }
                    GenericDropdown(
                        title = "默认系统提示词",
                        items = systemPrompts.map { it.id to it.name },
                        selectedId = defaultSystemPromptId,
                        noneLabel = "（无）",
                    ) { id ->
                        defaultSystemPromptId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_SYSTEM_PROMPT_ID, id.orEmpty()) }
                    }
                    GenericDropdown(
                        title = "默认工作区",
                        items = workspaces.map { it.id to it.name },
                        selectedId = defaultWorkspaceId,
                        noneLabel = "（无）",
                    ) { id ->
                        defaultWorkspaceId = id
                        scope.launch { WeAgentSettings.set(WeAgentSettings.KEY_DEFAULT_WORKSPACE_ID, id.orEmpty()) }
                    }
                }
            }
        }
    }

    // Persist the (validated) request cap as it changes — but only AFTER the initial load has
    // populated the field. Otherwise this effect fires on first composition with the default
    // ("50") and clobbers the stored value before LaunchedEffect(Unit) can read it back, so the
    // setting never appears to persist across screen opens.
    LaunchedEffect(maxRequests, loaded) {
        if (!loaded) return@LaunchedEffect
        // Blank means "still typing" — don't store anything yet.
        val typed = maxRequests.toIntOrNull() ?: return@LaunchedEffect
        val clamped = typed.coerceIn(1, 100)
        // Write the clamp back into the field as well, otherwise the UI would keep showing the raw
        // input (e.g. "500" or "0") while a different value is actually stored.
        if (clamped != typed) maxRequests = clamped.toString()
        WeAgentSettings.set(WeAgentSettings.KEY_MAX_MODEL_REQUESTS, clamped.toString())
    }
}

@Composable
private fun ModelDropdown(
    title: String,
    models: List<ModelEntity>,
    selectedId: String?,
    noneLabel: String,
    onSelected: (String?) -> Unit,
) = GenericDropdown(
    title = title,
    items = models.map { it.id to it.displayName.ifBlank { it.modelIdRemote } },
    selectedId = selectedId,
    noneLabel = noneLabel,
    onSelected = onSelected,
)

/** Dropdown over (id, label) pairs with an optional leading "none" entry mapping to null. */
@Composable
private fun GenericDropdown(
    title: String,
    items: List<Pair<String, String>>,
    selectedId: String?,
    noneLabel: String?,
    onSelected: (String?) -> Unit,
) {
    val ids = buildList { if (noneLabel != null) add(null); items.forEach { add(it.first) } }
    val labels = buildList { if (noneLabel != null) add(noneLabel); items.forEach { add(it.second) } }
    val selectedIndex = ids.indexOf(selectedId).coerceAtLeast(0)
    WindowDropdownPreference(
        title = title,
        items = labels,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { onSelected(ids[it]) },
    )
}
