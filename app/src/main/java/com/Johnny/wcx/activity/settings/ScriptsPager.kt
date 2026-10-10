package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.utils.android.showToast
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.ui.miuix.ScriptPluginManagerTabPage
import h.Hchat.ui.miuix.ScriptPluginMiuixTabContent
import h.Hchat.ui.miuix.ScriptPluginReadmeDialog

/**
 * 「脚本」独立页 —— 采用 Hchat 同款 Miuix 脚本页：
 * 插件总开关 / 插件列表（启用开关 + 长按菜单）/ 插件管理页 / README 弹窗。
 *
 * 数据源与逻辑全部走已迁移的 Hchat 脚本子系统（ScriptPluginRuntime / ScriptPluginManager）。
 */
@Composable
fun ScriptsPager() {
    val context = LocalContext.current
    var showManager by remember { mutableStateOf(false) }
    var readmePlugin by remember { mutableStateOf<ScriptPluginRuntime.ScriptPlugin?>(null) }

    if (showManager) {
        ScriptPluginManagerTabPage(
            context = context,
            onBack = { showManager = false },
        )
    } else {
        MiuixListScaffold(title = "脚本") {
            item {
                ScriptPluginMiuixTabContent(
                    context = context,
                    onOpenManager = { showManager = true },
                    onOpenReadme = { plugin -> readmePlugin = plugin },
                    onOpenMarket = { showToast("插件市场：迁移中") },
                    onOpenAgent = { showToast("脚本 Agent：迁移中") },
                )
            }
            item {
                Spacer(Modifier.height(CONTENT_BOTTOM_INSET))
            }
        }
    }

    readmePlugin?.let { plugin ->
        ScriptPluginReadmeDialog(
            context = context,
            plugin = plugin,
            onClose = { readmePlugin = null },
        )
    }
}
