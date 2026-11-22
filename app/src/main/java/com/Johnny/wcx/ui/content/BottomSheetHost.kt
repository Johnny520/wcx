package com.Johnny.wcx.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ui.style.WxPalette
import top.yukonga.miuix.kmp.basic.Text

/**
 * 底部浮层外壳：遮罩 + 从底部升起的圆角卡片。
 *
 * 两种渲染方式，按内容是否需要「跟随页面状态实时刷新」选：
 *
 * - `overlay = false`（默认）：就地渲染。内容与页面同一棵 Compose 树，能读到页面 state
 *   的每次变化 —— 分类半屏里点开关、切二级分类都属于这种。**调用点必须在页面根**
 *   （全屏容器），否则 `fillMaxSize` 只会填满父容器，遮罩就盖不到状态栏/导航栏。
 * - `overlay = true`：挂到 `decorView`（[OverlayHost]），遮罩天然整屏。内容在独立的
 *   Compose 树里执行，**读不到页面 state 的后续更新**，只适合打开即定稿的只读清单
 *   （日志级别的错误/警告明细）。
 *
 * [header] 由调用方画标题行（一级标题、二级返回、关闭按钮各不相同），
 * [content] 通常是 `LazyColumn(Modifier.fillMaxSize())`。
 */
@Composable
fun BottomSheetHost(
    onDismiss: () -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    heightFraction: Float = 0.8f,
    overlay: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheet: @Composable BoxScope.() -> Unit = {
        SheetCard(
            heightFraction = heightFraction,
            header = header,
            content = content,
        )
    }
    if (overlay) {
        OverlayHost(onDismissRequest = onDismiss, dense = true, content = sheet)
        return
    }
    Box(modifier = modifier.fillMaxSize()) {
        Scrim(onDismiss = onDismiss, dense = true)
        sheet()
    }
}

@Composable
private fun BoxScope.SheetCard(
    heightFraction: Float,
    header: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .fillMaxHeight(heightFraction)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(WxPalette.cardBackground())
            // 吞掉卡片区域的点击，避免穿透到后面的遮罩把自己关掉。
            .clickable(interactionSource = sheetInteraction, indication = null) {}
            .navigationBarsPadding()
            .padding(top = 16.dp),
    ) {
        header()
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/**
 * 浮层里的文字动作（「关闭」「复制全部」「‹ 分类」）：统一字号、强调色与胶囊热区。
 *
 * 这三处原本各写一遍 `clip(RoundedCornerShape(50)).clickable().padding(...)`，
 * 热区大小和字号都不一样。
 */
@Composable
fun SheetTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    color: Color = WxPalette.Primary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, fontSize = fontSize.sp, color = color)
    }
}
