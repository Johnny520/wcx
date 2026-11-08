package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.Johnny.wcx.ui.style.WxPalette

/**
 * 模块自检页用的三个小组件。
 *
 * 原先直接复用 `h.Hchat.ui.miuix` 里的同名实现；脚本子系统整体下线后其源码一并删除，
 * 这里按原视觉重写成自包含版本（同包 internal，仅自检页使用）。
 */

@Composable
internal fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        cornerRadius = 18.dp,
    ) {
        Column { content() }
    }
}

@Composable
internal fun ActionRow(title: String, summary: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background =
        if (pressed) MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = WxPalette.textPrimary(),
                fontWeight = FontWeight.Medium,
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

@Composable
internal fun InsetDivider(start: Dp = 16.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(1.dp)
            .background(WxPalette.cardBorder())
    )
}

/**
 * 模块自检页的页面骨架（顶栏 + 内容）。
 *
 * 原实现来自 `h.Hchat.ui.miuix.PageScaffold`，脚本子系统下线后改为自包含版本：
 * 保留「返回 + 标题」顶栏与内容区，不再依赖 Hchat 的大标题折叠逻辑。
 */
@Composable
internal fun PageScaffold(
    title: String,
    @Suppress("UNUSED_PARAMETER") largeTitle: String,
    @Suppress("UNUSED_PARAMETER") scrollBehavior: Any? = null,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(WxPalette.pageBackgroundBrush())
            // 关键：之前没有状态栏内边距，标题直接顶进状态栏，被时间/信号图标压住。
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(text = "返回", color = MiuixTheme.colorScheme.primary, fontSize = 15.sp)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = title,
                    color = WxPalette.textPrimary(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = WxPalette.textSecondary(),
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        content(PaddingValues(0.dp))
    }
}
