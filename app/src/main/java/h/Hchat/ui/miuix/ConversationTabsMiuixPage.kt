package h.Hchat.ui.miuix

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.conversationgroup.ConversationGroup
import h.Hchat.hooks.items.conversationgroup.ConversationGroupStore
import h.Hchat.hooks.items.conversationtabs.ConversationTab
import h.Hchat.hooks.items.conversationtabs.ConversationTabDisplay
import h.Hchat.hooks.items.conversationtabs.ConversationTabKind
import h.Hchat.hooks.items.conversationtabs.ConversationTabsConfig
import h.Hchat.hooks.items.conversationtabs.ConversationTabsIconPicker
import h.Hchat.hooks.items.conversationtabs.ConversationTabsIconPickResult
import h.Hchat.hooks.items.conversationtabs.ConversationTabsIconStore
import h.Hchat.hooks.items.conversationtabs.ConversationTabsStore
import java.util.UUID
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun ConversationTabsMiuixPage(context: Context, onBack: () -> Unit) {
    val account = remember { ConversationTabsStore.accountKey() }
    var config by remember { mutableStateOf(ConversationTabsStore.load(context)) }
    var route by remember { mutableStateOf("list") }
    var draft by remember { mutableStateOf(ConversationTab("", "", ConversationTabKind.CUSTOM)) }
    var position by remember { mutableStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    val latestConfig by rememberUpdatedState(config)
    val imported = remember { mutableSetOf<String>() }
    val listState = rememberLazyListState()
    val editorState = rememberLazyListState()

    fun message(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    fun save(next: ConversationTabsConfig): Boolean {
        if (account.isBlank() || account != ConversationTabsStore.accountKey()) {
            message("当前账号已变化，请返回后重新打开标签分组")
            return false
        }
        val normalized = ConversationTabsConfig.normalize(next)
        if (!ConversationTabsStore.save(context, normalized)) { message("标签配置保存失败"); return false }
        config = normalized
        return true
    }

    DisposableEffect(Unit) {
        onDispose {
            alive = false
            val retained = latestConfig.tabs.map { it.iconPath }.toSet()
            imported.filterNot { it in retained }.forEach { ConversationTabsIconStore.delete(context, it) }
        }
    }

    fun pickIcon() {
        val activity = ScriptPluginAgentUi.findAgentActivity(context) ?: return
        val id = draft.id
        ConversationTabsIconPicker.launch(activity, id) { result ->
            when (result) {
                is ConversationTabsIconPickResult.Saved -> {
                    if (!alive || draft.id != id || route != "editor") {
                        ConversationTabsIconStore.delete(context, result.path)
                    } else {
                        imported.add(result.path)
                        draft = draft.copy(iconPath = result.path)
                    }
                }
                ConversationTabsIconPickResult.FAILED -> message("图片导入失败，请重新选择")
                ConversationTabsIconPickResult.CANCELLED -> Unit
            }
        }
    }

    SettingsRouteTransition(targetState = route, label = "ConversationTabsRoute",
        depthOf = { when (it) { "list" -> 0; "editor" -> 1; else -> 2 } }) { page ->
        when (page) {
            "picker" -> ConversationGroupSettingsUi.ConversationGroupConversationPickerPage(
                context = context,
                group = ConversationGroup(id = "tab-" + draft.id, name = draft.name,
                    conversationIds = draft.conversationIds.toList()),
                groups = remember { ConversationGroupStore.load(context) },
                onBack = { route = "editor" },
                onConfirm = { draft = draft.copy(conversationIds = it.toSet()); route = "editor" }
            )
            "editor" -> {
                val scroll = MiuixScrollBehavior()
                val existing = config.tabs.any { it.id == draft.id }
                val onlyAll = draft.kind == ConversationTabKind.ALL &&
                    config.tabs.count { it.kind == ConversationTabKind.ALL } <= 1
                PageScaffold(title = if (existing) "编辑标签" else "新增标签",
                    largeTitle = if (existing) "编辑标签" else "新增标签", scrollBehavior = scroll,
                    onBack = { route = "list" }, bottomBar = {
                        BottomActionBar(primaryText = "保存标签", onPrimaryClick = {
                            if (draft.name.isBlank()) message("请输入标签名称") else {
                                val oldIconPath = config.tabs.firstOrNull { it.id == draft.id }?.iconPath.orEmpty()
                                val tabs = config.tabs.filterNot { it.id == draft.id }.toMutableList()
                                tabs.add(position.coerceIn(0, tabs.size), draft)
                                if (save(config.copy(tabs = tabs))) {
                                    if (oldIconPath.isNotBlank() && oldIconPath != draft.iconPath &&
                                        config.tabs.none { it.iconPath == oldIconPath }
                                    ) {
                                        ConversationTabsIconStore.delete(context, oldIconPath)
                                    }
                                    message("标签已保存"); route = "list"
                                }
                            }
                        }, secondaryText = "返回", onSecondaryClick = { route = "list" })
                    }) { padding ->
                    LazyColumn(modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
                        state = editorState, contentPadding = PaddingValues(
                            top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 84.dp)) {
                        item { SmallTitle(text = "会话范围") }
                        item {
                            SettingsCard {
                                InputRow("标签名称", "最多 24 个字", draft.name) { draft = draft.copy(name = it.take(24)) }
                                InsetDivider()
                                PopupChoiceRow(title = "会话类型", summary = draft.kind.title,
                                    options = ConversationTabKind.values().map { PopupChoice(it.title, it.name) },
                                    currentValue = draft.kind.name, enabled = !onlyAll,
                                    onValueChanged = { draft = draft.copy(kind = ConversationTabKind.valueOf(it)) })
                                if (draft.kind == ConversationTabKind.CUSTOM) {
                                    InsetDivider()
                                    ActionRow("选择会话", "已选择 " + draft.conversationIds.size + " 个；可以与其他标签重叠") {
                                        route = "picker"
                                    }
                                }
                            }
                        }
                        item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "标签外观") }
                        item {
                            SettingsCard {
                                PopupChoiceRow(title = "显示方式", summary = draft.display.title,
                                    options = ConversationTabDisplay.values().map { PopupChoice(it.title, it.name) },
                                    currentValue = draft.display.name,
                                    onValueChanged = { draft = draft.copy(display = ConversationTabDisplay.valueOf(it)) })
                                if (draft.display != ConversationTabDisplay.NAME) {
                                    InsetDivider()
                                    InputRow("图标符号", "可填写表情或符号；未设置图片时使用", draft.icon) {
                                        draft = draft.copy(icon = it.take(16))
                                    }
                                    InsetDivider()
                                    ActionRow("自定义图片图标", if (draft.iconPath.isBlank()) "从系统文件管理器选择图片" else "已选择图片，点击更换") { pickIcon() }
                                    if (draft.iconPath.isNotBlank()) {
                                        InsetDivider()
                                        ActionRow("移除图片图标", "改用上面的图标符号") { draft = draft.copy(iconPath = "") }
                                    }
                                }
                                InsetDivider()
                                val count = config.tabs.size + if (existing) 0 else 1
                                PopupChoiceRow(title = "标签位置", summary = "第 " + (position + 1) + " 个",
                                    options = (0 until count).map { PopupChoice("第 " + (it + 1) + " 个", it.toString()) },
                                    currentValue = position.toString(), onValueChanged = { position = it.toInt() })
                            }
                        }
                        if (existing && !onlyAll) item {
                            SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                                ActionRow("删除标签", "只移除这个标签，不修改会话和聊天记录") {
                                    if (save(config.copy(tabs = config.tabs.filterNot { it.id == draft.id }))) route = "list"
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val scroll = MiuixScrollBehavior()
                PageScaffold(title = "标签分组", largeTitle = "标签分组", scrollBehavior = scroll,
                    onBack = onBack, bottomBar = { BottomActionBar(primaryText = "返回", onPrimaryClick = onBack) }) { padding ->
                    LazyColumn(modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
                        state = listState, contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp,
                            bottom = padding.calculateBottomPadding() + 84.dp)) {
                        item {
                            SettingsCard {
                                SwitchRow(checked = config.enabled, title = "启用标签分组",
                                    summary = "在微信会话首页上方显示可横向滚动的标签栏",
                                    onCheckedChange = { save(config.copy(enabled = it)) })
                            }
                        }
                        if (config.enabled) {
                            item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "标签") }
                            config.tabs.forEachIndexed { index, tab ->
                                item(key = tab.id) {
                                    SettingsCard(modifier = Modifier.padding(top = 6.dp)) {
                                        ActionRow(tab.name, tab.kind.title + " · " + tab.display.title) {
                                            draft = tab; position = index; route = "editor"
                                        }
                                    }
                                }
                            }
                            if (config.tabs.size < ConversationTabsConfig.MAX_TABS) item {
                                SettingsCard(modifier = Modifier.padding(top = 10.dp)) {
                                    ActionRow("新增标签", "按会话类型分类，或自选会话") {
                                        draft = ConversationTab(UUID.randomUUID().toString(), "", ConversationTabKind.CUSTOM)
                                        position = config.tabs.size; route = "editor"
                                    }
                                }
                            }
                            item { SmallTitle(text = "全部保留原生首页；分类标签展开已有分组与公众号中的会话，不移动原始会话。图标后台缩放并缓存。") }
                        }
                    }
                }
            }
        }
    }
}
