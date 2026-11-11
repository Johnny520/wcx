package com.Johnny.wcx.ui.content.m3

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ui.utils.theme.ColorosTheme

/** ColorOS 17 风格滑条轨道高度。 */
val ColorosSliderTrackHeight = 14.dp

/** 滑块直径，比轨道略大，和开关圆点同一量级。 */
val ColorosSliderThumbSize = 22.dp

/**
 * ColorOS 17 风格滑块：白色圆点，取色和开关圆点同一套 [ColorosTheme.tokens.switchThumb]。
 */
@Composable
fun ColorosSliderThumb(
    sliderState: SliderState,
    enabled: Boolean = true,
) {
    val thumb = ColorosTheme.tokens.switchThumb
    val color = if (enabled) thumb else thumb.copy(alpha = 0.38f)

    Box(
        modifier = Modifier
            .size(ColorosSliderThumbSize)
            .drawBehind {
                drawCircle(color = color, radius = size.minDimension / 2f)
            },
    )
}

/**
 * ColorOS 17 风格滑条轨道：纯圆角胶囊，不画刻度点。
 *
 * 所有滑条都走这一个实现，避免默认的 Material 竖线刻度和别的页面风格不一。
 * 用法：[androidx.compose.material3.Slider] 的 `track` 槽。
 *
 * @param stepCount 保留参数以兼容旧调用，轨道不再绘制圆点。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColorosSliderTrack(
    sliderState: SliderState,
    stepCount: Int = 0,
) {
    val colors = MaterialTheme.colorScheme
    val active = colors.primary
    val inactive = colors.primary.copy(alpha = 0.14f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ColorosSliderTrackHeight),
    ) {
        val trackHeight = size.height
        val radius = trackHeight / 2f
        val inset = radius
        val innerWidth = size.width - inset * 2f

        drawRoundRect(
            color = inactive,
            cornerRadius = CornerRadius(radius, radius),
        )

        val start = sliderState.valueRange.start
        val end = sliderState.valueRange.endInclusive
        val span = end - start
        val fraction = if (span <= 0f) 0f
        else ((sliderState.value - start) / span).coerceIn(0f, 1f)
        val endX = inset + innerWidth * fraction

        val activeWidth = (endX - inset).coerceAtLeast(0f)
        if (activeWidth > 0f) {
            drawRoundRect(
                color = active,
                size = Size(activeWidth + radius, trackHeight),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}
