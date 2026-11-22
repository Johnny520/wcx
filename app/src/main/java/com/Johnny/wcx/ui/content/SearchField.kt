package com.Johnny.wcx.ui.content

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Search
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局统一的搜索框：左侧放大镜 + 右侧关闭/清空。
 *
 * 主页搜索与开源许可搜索各写过一遍同样的「TextField + 前置放大镜 + 后置 X」，
 * 两处的图标尺寸、染色、内边距都靠手抄——这里收成一处。
 *
 * [onClose] 由调用方决定语义：主页是「清空关键词 → 收起搜索框」，许可页是「清空关键词」。
 */
@Composable
fun SearchField(
    state: TextFieldState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "搜索",
    closeDescription: String = "清除",
) {
    TextField(
        state = state,
        modifier = modifier,
        label = label,
        leadingIcon = {
            Icon(
                imageVector = MaterialSymbols.Outlined.Search,
                contentDescription = null,
                modifier = Modifier.padding(horizontal = 12.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Close,
                    contentDescription = closeDescription,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
    )
}
