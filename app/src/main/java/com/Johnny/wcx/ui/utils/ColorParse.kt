package com.Johnny.wcx.ui.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * ARGB 颜色解析与夜间适配。
 *
 * 项目里原有 5 份 `parseColor`，语义其实分两类，这里分别收口、**不混用**：
 *
 * - [parseArgbStrict]：给「用户自己填的色值」。必须带 `#`（`#RRGGBB` / `#AARRGGBB`），
 *   与 androidx 的 `String.toColorInt()` 完全一致；解析失败返回 null，由调用方决定回退值。
 * - [parseArgbFlexible]：给「主题包的 manifest.json / colors.json」。可能是 Int / Long，
 *   也可能是不带 `#` 的 6/8 位十六进制，甚至任意长度的十六进制整数。
 *
 * 两者不要合并：合并只会把「用户填错」悄悄变成「按另一种格式猜对了」，反而掩盖配置错误。
 */
fun parseArgbStrict(value: String?): Int? =
    value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { it.toColorInt() }.getOrNull() }

fun parseArgbFlexible(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is String -> {
        val s = value.trim().removePrefix("#")
        when (s.length) {
            6 -> runCatching { "#FF$s".toColorInt() }.getOrNull()
            8 -> runCatching { "#$s".toColorInt() }.getOrNull()
            else -> s.toIntOrNull(16)?.let { (0xFF shl 24) or it }
        }
    }
    else -> null
}

/**
 * 暗色/亮色模式适配：暗色模式把颜色提亮到 HSV 明度不低于 [minValue]，
 * 保证深色背景上仍可读（类似微信原生暗色白字）；亮色模式原样返回。
 */
fun adaptNightColor(ctx: Context?, color: Int, minValue: Float = 0.78f): Int {
    if (ctx == null) return color
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    if (!night) return color
    val hsv = FloatArray(3)
    Color.colorToHSV(color, hsv)
    hsv[2] = maxOf(hsv[2], minValue)
    return Color.HSVToColor(Color.alpha(color), hsv)
}
