package h.Hchat.ui.miuix

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.linkparse.LinkParseSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.FeatureSettingsProvider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

private sealed class LinkParseRoute {
    data object Main : LinkParseRoute()
    data class ContactPicker(val request: ContactPickerRequest) : LinkParseRoute()
}

@Composable
internal fun LinkParseMiuixPage(context: Context, provider: FeatureSettingsProvider, onBack: () -> Unit) {
    val settings = remember { LinkParseSettings(context) }
    var route by remember { mutableStateOf<LinkParseRoute>(LinkParseRoute.Main) }
    var allowedTalkers by remember { mutableStateOf(settings.allowedTalkers()) }

    SettingsRouteTransition(
        targetState = route,
        label = "LinkParseRoute",
        depthOf = { if (it is LinkParseRoute.Main) 0 else 1 }
    ) { currentRoute ->
        when (currentRoute) {
            LinkParseRoute.Main -> LinkParseMainPage(
                context = context,
                provider = provider,
                allowedTalkers = allowedTalkers,
                onBack = onBack,
                onPickTalkers = {
                    route = LinkParseRoute.ContactPicker(
                        ContactPickerRequest(
                            title = "允许他人链接解析的聊天",
                            mode = ContactPickerMode.BOTH,
                            multiSelect = true,
                            existingValue = allowedTalkers.joinToString("|"),
                            onValue = {},
                            enableLabels = true
                        )
                    )
                }
            )
            is LinkParseRoute.ContactPicker -> ContactPickerPage(
                context = context,
                request = currentRoute.request,
                onBack = { route = LinkParseRoute.Main },
                onConfirm = { picked ->
                    allowedTalkers = picked.map { it.id }.filter { it.isNotBlank() }.toSet()
                    settings.saveAllowedTalkers(allowedTalkers)
                    Toast.makeText(context, "链接解析范围已保存", Toast.LENGTH_SHORT).show()
                    route = LinkParseRoute.Main
                }
            )
        }
    }
}

@Composable
private fun LinkParseMainPage(
    context: Context,
    provider: FeatureSettingsProvider,
    allowedTalkers: Set<String>,
    onBack: () -> Unit,
    onPickTalkers: () -> Unit
) {
    val prefs = remember { HchatStorage.preferences(context, LinkParseSettings.PREFS_NAME) }
    val scroll = MiuixScrollBehavior()
    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scroll,
        bottomBar = { BottomActionBar("返回", onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "链接解析") }
            item {
                SettingsCard {
                    SwitchRow(
                        prefs,
                        LinkParseSettings.KEY_ENABLE,
                        "启用链接解析",
                        "解析你发送的公开作品链接；他人仅限允许的聊天",
                        false
                    )
                    InsetDivider()
                    SwitchRow(prefs, LinkParseSettings.KEY_DOUYIN, "抖音", "识别公开作品链接", false)
                    InsetDivider()
                    SwitchRow(prefs, LinkParseSettings.KEY_XHS, "小红书", "识别公开作品链接", false)
                    InsetDivider()
                    ActionRow(
                        "允许他人链接解析的聊天",
                        if (allowedTalkers.isEmpty()) "未选择" else "已选 ${allowedTalkers.size} 个聊天",
                        onPickTalkers
                    )
                    InsetDivider()
                    SwitchRow(
                        prefs,
                        LinkParseSettings.KEY_GROUP_AT_ME_ONLY,
                        "群聊仅 @我触发",
                        "仅限制允许聊天中的其他群成员；不影响你自己和私聊",
                        false
                    )
                }
            }
        }
    }
}
