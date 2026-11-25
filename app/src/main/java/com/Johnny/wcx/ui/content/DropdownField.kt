package com.Johnny.wcx.ui.content

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ui.style.WxPalette
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Check
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

/**
 * 设置类页面统一的下拉选择行：标题 + 当前值，点行弹出选项列表（选中项打勾）。
 *
 * 弹层走 [ScrimDropdownMenu]（全屏遮罩 + 锚定菜单）—— 之前用 miuix 的
 * `WindowDropdownPreference`，它的遮罩被画在内容宽度的 Dialog 窗口里，左右露出白线。
 *
 * 顺带把「选中值」收进副标题：miuix 版把值画在右侧、和副标题各占一行，行高虚高。
 *
 * 全模块设置类下拉都从这里发出——以后要调下拉的观感/行为只改这一处。
 */
@Composable
fun DropdownField(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    ScrimDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = modifier.fillMaxWidth(),
        anchor = {
            BasicComponent(
                title = title,
                summary = summary ?: items.getOrNull(selectedIndex),
                startAction = startAction,
                onClick = { expanded = true },
                enabled = enabled && items.isNotEmpty(),
            )
        },
    ) {
        items.forEachIndexed { index, label ->
            val isSelected = index == selectedIndex
            ScrimMenuItem(
                text = label,
                selected = isSelected,
                onClick = {
                    expanded = false
                    onSelectedIndexChange(index)
                },
                trailing = if (isSelected) {
                    {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Check,
                            contentDescription = "已选中",
                            tint = WxPalette.Primary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
