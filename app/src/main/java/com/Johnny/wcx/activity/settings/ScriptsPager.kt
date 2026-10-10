package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.items.scripting_java.JavaScriptingHook
import com.Johnny.wcx.utils.android.showToast
import kotlin.io.path.name
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「脚本」独立页（对齐 Hchat 插件中心形态）。
 *
 * 数据源：模块私有目录 `scripts_java/`（每个脚本一个文件夹，含 `main.java` + `info.prop`）。
 * 启用状态 = 目录下是否存在 `disabled.flag`。
 * 点击条目进入脚本详情配置页（读写脚本 `config.prop`）。
 */
@Composable
fun ScriptsPager() {
    val entries = remember { JavaScriptingHook.listScriptEntries() }
    val enabledStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            entries.forEach { put(it.dir.name, it.enabled) }
        }
    }
    var detailName by remember { mutableStateOf<String?>(null) }

    MiuixListScaffold(title = "脚本") {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                BasicComponent(onClick = null) {
                    Text(
                        text = "脚本插件",
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = BasicComponentDefaults.titleColor().color,
                    )
                    Text(
                        text = "本地目录 scripts_java/；开关控制启用 / 禁用，点击条目查看并修改脚本配置",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = BasicComponentDefaults.summaryColor().color,
                    )
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                BasicComponent(onClick = null) {
                    Text(
                        text = "暂无脚本",
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        color = BasicComponentDefaults.titleColor().color,
                    )
                    Text(
                        text = "把脚本放进 scripts_java/（每个脚本一个文件夹：main.java + info.prop）",
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = BasicComponentDefaults.summaryColor().color,
                    )
                }
            }
        } else {
            itemsIndexed(entries, key = { _, e -> e.dir.name }) { index, entry ->
                val key = entry.dir.name
                val enabled = enabledStates[key] ?: entry.enabled
                Column(
                    modifier = Modifier
                        .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                        .groupedCardItem(index, entries.size),
                ) {
                    BasicComponent(
                        onClick = { detailName = key },
                        endActions = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { on ->
                                    if (JavaScriptingHook.setScriptEnabled(entry.dir, on)) {
                                        enabledStates[key] = on
                                    } else {
                                        showToast("切换脚本状态失败")
                                    }
                                },
                            )
                        },
                    ) {
                        Text(
                            text = entry.info.name,
                            fontSize = MiuixTheme.textStyles.headline1.fontSize,
                            fontWeight = FontWeight.Medium,
                            color = BasicComponentDefaults.titleColor().color,
                        )
                        val summary = buildString {
                            append(key)
                            append(if (enabled) " · 已启用" else " · 已禁用")
                            entry.info.version?.let { append(" · 版本 ").append(it) }
                            entry.info.author?.let { append(" · ").append(it) }
                        }
                        Text(
                            text = summary,
                            fontSize = MiuixTheme.textStyles.body2.fontSize,
                            color = BasicComponentDefaults.summaryColor().color,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }

    val selected = entries.firstOrNull { it.dir.name == detailName }
    if (selected != null) {
        JavaScriptingHook.ScriptDetailScreen(
            entry = selected,
            onBack = { detailName = null },
        )
    }
}
