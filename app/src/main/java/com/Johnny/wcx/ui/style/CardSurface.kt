package com.Johnny.wcx.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 画一张卡片底：纯白圆角 + 1dp 极淡描边，跟随深浅色。
 *
 * 全库「画一张卡」只有两种写法：
 * - 内容直接放进卡里 → 用 [SoftCard]；
 * - 需要自己控制布局（列表行虚拟化、按压缩放、自定义 padding）→ 用本函数。
 * 之前第三种「手写 clip + background + border」散落在多处，圆角和描边数值各不相同。
 */
@Composable
fun Modifier.cardSurface(
    shape: Shape = RoundedCornerShape(WxPalette.CardRadius),
    background: Color? = null,
): Modifier = this
    .background(background ?: WxPalette.cardBackground(), shape)
    .border(1.dp, WxPalette.cardBorder(), shape)
