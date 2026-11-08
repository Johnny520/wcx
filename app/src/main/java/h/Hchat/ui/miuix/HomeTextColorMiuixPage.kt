package h.Hchat.ui.miuix

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import h.Hchat.hooks.items.hometextcolor.HomeTextColorSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.FeatureSettingsProvider
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle

@Composable
internal fun HomeTextColorMiuixPage(
    context: Context,
    provider: FeatureSettingsProvider,
    onBack: () -> Unit
) {
    val sp = remember { HchatStorage.preferences(context, HomeTextColorSettings.PREFS_NAME) }
    var enabled by remember {
        mutableStateOf(
            sp.getBoolean(HomeTextColorSettings.KEY_ENABLE, HomeTextColorSettings.DEFAULT_ENABLE)
        )
    }
    var titleColor by remember {
        mutableStateOf(
            sp.getString(
                HomeTextColorSettings.KEY_TITLE_COLOR,
                HomeTextColorSettings.DEFAULT_TITLE_COLOR
            ).orEmpty()
        )
    }
    var subtitleColor by remember {
        mutableStateOf(
            sp.getString(
                HomeTextColorSettings.KEY_SUBTITLE_COLOR,
                HomeTextColorSettings.DEFAULT_SUBTITLE_COLOR
            ).orEmpty()
        )
    }
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()

    PageScaffold(
        title = provider.title(),
        largeTitle = provider.title(),
        scrollBehavior = scrollBehavior,
        bottomBar = {
            BottomActionBar(
                primaryText = "保存设置",
                onPrimaryClick = {
                    val cleanedTitle = HomeTextColorSettings.cleanColorSpec(titleColor)
                    val cleanedSubtitle = HomeTextColorSettings.cleanColorSpec(subtitleColor)
                    sp.edit()
                        .putString(HomeTextColorSettings.KEY_TITLE_COLOR, cleanedTitle)
                        .putString(HomeTextColorSettings.KEY_SUBTITLE_COLOR, cleanedSubtitle)
                        .apply()
                    titleColor = cleanedTitle
                    subtitleColor = cleanedSubtitle
                    Toast.makeText(context, "设置已保存", Toast.LENGTH_SHORT).show()
                },
                secondaryText = "返回",
                onSecondaryClick = onBack
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            state = listState,
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 84.dp
            )
        ) {
            item { SmallTitle(text = "基础") }
            item {
                SettingsCard {
                    SwitchRow(
                        checked = enabled,
                        title = "启用首页文字颜色",
                        summary = "应用到微信、通讯录、发现和我",
                        onCheckedChange = {
                            enabled = it
                            sp.edit().putBoolean(HomeTextColorSettings.KEY_ENABLE, it).apply()
                        }
                    )
                }
            }
            if (enabled) {
                item { SmallTitle(modifier = Modifier.padding(top = 10.dp), text = "颜色") }
                item {
                    SettingsCard {
                        ColorPickerRow(
                            title = "标题颜色",
                            summary = "会话名称、联系人名称和菜单标题",
                            value = titleColor,
                            onReset = { titleColor = HomeTextColorSettings.DEFAULT_TITLE_COLOR }
                        ) { titleColor = it.take(19) }
                        InsetDivider()
                        ColorPickerRow(
                            title = "副标题颜色",
                            summary = "消息摘要、账号信息和辅助文字",
                            value = subtitleColor,
                            onReset = { subtitleColor = HomeTextColorSettings.DEFAULT_SUBTITLE_COLOR }
                        ) { subtitleColor = it.take(19) }
                    }
                }
            }
        }
    }
}
