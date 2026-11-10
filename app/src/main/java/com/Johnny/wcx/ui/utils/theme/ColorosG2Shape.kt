package com.Johnny.wcx.ui.utils.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * ColorOS 17 G2 超椭圆（Lp 等值线），比 [androidx.compose.foundation.shape.RoundedCornerShape]
 * 更接近系统设置页卡片。权重 3 对齐 COUI-Kit Group 默认。
 *
 * 可按角开关，给虚拟化分组卡用：首行只圆上角、末行只圆下角。
 */
@Immutable
data class ColorosG2Shape(
    val radius: Dp,
    val cornerWeight: Float = 3f,
    val topStart: Boolean = true,
    val topEnd: Boolean = true,
    val bottomEnd: Boolean = true,
    val bottomStart: Boolean = true,
) : Shape {

    init {
        require(radius.value.isFinite() && radius.value >= 0f)
        require(cornerWeight.isFinite() && cornerWeight >= 2f)
    }

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val r = (radius.value * density.density).coerceIn(0f, size.minDimension.coerceAtLeast(0f) / 2f)
        if (r == 0f) {
            return Outline.Rectangle(Rect(0f, 0f, size.width, size.height))
        }
        val rtl = layoutDirection == LayoutDirection.Rtl
        val tl = if (rtl) topEnd else topStart
        val tr = if (rtl) topStart else topEnd
        val br = if (rtl) bottomStart else bottomEnd
        val bl = if (rtl) bottomEnd else bottomStart
        val weight = cornerWeight.toDouble()
        val diagonal = 0.5.pow(1.0 / weight)
        // 与 COUI-Kit CouiShape 相同：半角斜率不超过 1 的区间采样，误差上限 0.05px。
        val segments = ceil(sqrt(r * (weight - 1.0) * diagonal / (4.0 * 0.05)))
            .toInt()
            .coerceAtLeast(1)
        val half = DoubleArray(segments + 1) { i ->
            (1.0 - (diagonal * i / segments).pow(weight)).pow(1.0 / weight)
        }
        val path = Path()
        if (tl) path.moveTo(r, 0f) else path.moveTo(0f, 0f)

        fun sample(index: Int): Pair<Float, Float> {
            val first = index <= segments
            val hi = if (first) index else segments * 2 - index
            val a = diagonal * hi / segments
            val b = half[hi]
            val x = (r * if (first) a else b).toFloat()
            val y = (r * if (first) b else a).toFloat()
            return x to y
        }

        if (tr) {
            for (i in 0..segments * 2) {
                val (x, y) = sample(i)
                path.lineTo(size.width - r + x, r - y)
            }
        } else {
            path.lineTo(size.width, 0f)
        }

        if (br) {
            for (i in 0..segments * 2) {
                val (x, y) = sample(i)
                path.lineTo(size.width - r + y, size.height - r + x)
            }
        } else {
            path.lineTo(size.width, size.height)
        }

        if (bl) {
            for (i in 0..segments * 2) {
                val (x, y) = sample(i)
                path.lineTo(r - x, size.height - r + y)
            }
        } else {
            path.lineTo(0f, size.height)
        }

        if (tl) {
            for (i in 0..segments * 2) {
                val (x, y) = sample(i)
                path.lineTo(r - y, r - x)
            }
        } else {
            path.lineTo(0f, 0f)
        }

        path.close()
        return Outline.Generic(path)
    }
}
