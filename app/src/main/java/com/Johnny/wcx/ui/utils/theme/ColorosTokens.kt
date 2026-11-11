package com.Johnny.wcx.ui.utils.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors

/**
 * ColorOS 17（Aquamorphic）token。
 *
 * 浅 / 深主色来自 2026-10-20 PLK110 设置页逐像素实测。
 * 标了 derived 的项系统设置页没有对应控件，落地后再微调：
 * - water 渐变、深色图标底 `#377EF730`
 * - 深色卡内分割线按 `#3A3A3A` 量级
 *
 * 自定义取色时只覆盖画布（页底 / 卡片 / 文字），accent 仍走 seed。
 * 纯黑模式把页底和卡片压成 `#000000`，accent 不动。
 */
@Immutable
data class ColorosTokens(
    val bgPage: Color,
    val bgCard: Color,
    val bgSearchbar: Color,
    val bgCardTranslucent: Color,
    val textTitle: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val waterCardStart: Color,
    val waterCardEnd: Color,
    /** WaterCard 右半区有机色块颜色（参考图风格，无渐变）。 */
    val waterCardRight: Color,
    val shadowCard: Color,
    val dividerHair: Color,
    val switchOffTrack: Color,
    val switchThumb: Color,
    val chevron: Color,
    val terminalBg: Color,
    /** 点击整块铺上的遮罩，浅色用黑、深色用白。 */
    val pressOverlay: Color,
) {
    val waterBrush: Brush
        get() = Brush.linearGradient(listOf(waterCardStart, waterCardEnd))

    /** 浅色 water 卡是淡蓝底，必须用深字；深色卡才走白字。 */
    val waterOnCard: Color
        get() = if (waterCardStart.luminance() > 0.5f) textPrimary else Color.White

    /** Filled 按钮字色：强调色底上统一白字。 */
    val onAccent: Color
        get() = Color.White

    /**
     * 语义色跟当前 accent 同饱和/明度，只换色相：
     * 自定义取色或壁纸取色时，绿/橙/红不会还停在系统默认。
     */
    val wellSuccess: Color
        get() = accent.rehue(145f)
    val wellWarning: Color
        get() = accent.rehue(38f)
    val wellDanger: Color
        get() = accent.rehue(8f)
    val wellPurple: Color
        get() = accent.rehue(280f)
    val wellCyan: Color
        get() = accent.rehue(190f)
    val wellPink: Color
        get() = accent.rehue(340f)
    val wellIndigo: Color
        get() = accent.rehue(250f)

    val waterOnCardSecondary: Color
        get() = if (waterCardStart.luminance() > 0.5f) textSecondary else Color.White.copy(alpha = 0.72f)

    fun toMiuix(base: Colors, keepAccent: Boolean): Colors {
        val a = if (keepAccent) base.primary else accent
        // 开关圆点走 onPrimary（白）。onAccent 也是白，但不能混用语义。
        val onA = switchThumb
        val aContainer = if (keepAccent) base.primaryContainer else accentSoft
        val onAContainer = if (keepAccent) base.onPrimaryContainer else accent
        return Colors(
            primary = a,
            onPrimary = onA,
            primaryVariant = if (keepAccent) base.primaryVariant else a,
            onPrimaryVariant = switchThumb,
            error = base.error,
            onError = base.onError,
            errorContainer = base.errorContainer,
            onErrorContainer = base.onErrorContainer,
            disabledPrimary = base.disabledPrimary,
            disabledOnPrimary = base.disabledOnPrimary,
            disabledPrimaryButton = base.disabledPrimaryButton,
            disabledOnPrimaryButton = base.disabledOnPrimaryButton,
            disabledPrimarySlider = base.disabledPrimarySlider,
            primaryContainer = aContainer,
            onPrimaryContainer = onAContainer,
            // 关态开关走灰轨，不要跟强调色。miuix Switch 关态读 secondary。
            secondary = switchOffTrack,
            onSecondary = switchThumb,
            secondaryVariant = switchOffTrack,
            onSecondaryVariant = switchThumb,
            disabledSecondary = base.disabledSecondary,
            disabledOnSecondary = base.disabledOnSecondary,
            disabledSecondaryVariant = base.disabledSecondaryVariant,
            disabledOnSecondaryVariant = base.disabledOnSecondaryVariant,
            secondaryContainer = if (keepAccent) base.secondaryContainer else aContainer,
            onSecondaryContainer = if (keepAccent) base.onSecondaryContainer else onAContainer,
            secondaryContainerVariant = if (keepAccent) base.secondaryContainerVariant else aContainer,
            onSecondaryContainerVariant = if (keepAccent) base.onSecondaryContainerVariant else onAContainer,
            tertiaryContainer = if (keepAccent) base.tertiaryContainer else aContainer,
            onTertiaryContainer = if (keepAccent) base.onTertiaryContainer else onAContainer,
            tertiaryContainerVariant = if (keepAccent) base.tertiaryContainerVariant else aContainer,
            background = bgPage,
            onBackground = textPrimary,
            onBackgroundVariant = textSecondary,
            surface = bgPage,
            onSurface = textPrimary,
            surfaceVariant = bgSearchbar,
            onSurfaceSecondary = textSecondary,
            onSurfaceVariantSummary = textSecondary,
            onSurfaceVariantActions = chevron,
            disabledOnSurface = textTertiary,
            surfaceContainer = bgCard,
            onSurfaceContainer = textPrimary,
            onSurfaceContainerVariant = textSecondary,
            surfaceContainerHigh = bgCard,
            onSurfaceContainerHigh = textPrimary,
            surfaceContainerHighest = bgCard,
            onSurfaceContainerHighest = textPrimary,
            outline = dividerHair,
            dividerLine = dividerHair,
            windowDimming = base.windowDimming,
            sliderKeyPoint = if (keepAccent) base.sliderKeyPoint else a,
            sliderKeyPointForeground = switchThumb,
            sliderBackground = switchOffTrack,
        )
    }

    /** 模块自定义颜色：强调色和 water 卡跟着 seed，画布仍是 ColorOS。 */
    fun withAccent(accent: Color): ColorosTokens {
        val light = bgPage.luminance() > 0.5f
        return copy(
            accent = accent,
            accentSoft = accent.copy(alpha = if (light) 0.12f else 0.19f),
            waterCardStart = lerp(waterCardStart, accent, if (light) 0.28f else 0.22f),
            waterCardEnd = lerp(waterCardEnd, accent, if (light) 0.10f else 0.08f),
        )
    }

    fun toMaterial(base: ColorScheme, keepAccent: Boolean): ColorScheme = base.copy(
        primary = if (keepAccent) base.primary else accent,
        // 开关圆点读 onPrimary（白），不要用 onAccent 以外的语义色。
        onPrimary = switchThumb,
        primaryContainer = if (keepAccent) base.primaryContainer else accentSoft,
        onPrimaryContainer = if (keepAccent) base.onPrimaryContainer else accent,
        background = bgPage,
        onBackground = textPrimary,
        surface = bgPage,
        onSurface = textPrimary,
        surfaceVariant = bgSearchbar,
        onSurfaceVariant = textSecondary,
        surfaceDim = bgPage,
        surfaceBright = bgCard,
        surfaceContainerLowest = bgPage,
        surfaceContainerLow = bgCard,
        surfaceContainer = bgCard,
        surfaceContainerHigh = bgCard,
        surfaceContainerHighest = bgCard,
        outline = dividerHair,
        outlineVariant = dividerHair,
    )

    companion object {
        val Light = ColorosTokens(
            bgPage = Color(0xFFF0F1F2),
            bgCard = Color.White,
            bgSearchbar = Color(0xFFF6F7F7),
            bgCardTranslucent = Color(0xCCFFFFFF),
            textTitle = Color.Black,
            textPrimary = Color(0xFF191919),
            textSecondary = Color(0xFF757575),
            textTertiary = Color(0xFFAEAEB2),
            accent = Color(0xFF07C160),
            accentSoft = Color(0x1F07C160),
            waterCardStart = Color(0xFFE3ECFD), // derived
            waterCardEnd = Color(0xFFF4F8FF), // derived
            waterCardRight = Color(0x00000000), // v5: 去掉蓝色 S 块，透明
            shadowCard = Color(0x14141E32), // 极淡软阴影
            dividerHair = Color(0xFFE8EAED),
            switchOffTrack = Color(0xFFE5E7EB),
            switchThumb = Color.White,
            chevron = Color(0xFFBDBDBD),
            terminalBg = Color(0xFF0E1114),
            pressOverlay = Color(0x1F000000),
        )

        val Dark = ColorosTokens(
            bgPage = Color(0xFF181818),
            bgCard = Color(0xFF303030),
            bgSearchbar = Color(0xFF252525),
            bgCardTranslucent = Color(0xCC303030),
            textTitle = Color.White,
            textPrimary = Color(0xFFEBEBEB),
            textSecondary = Color(0xFFA0A0A0),
            textTertiary = Color(0xFF6F6F6F),
            accent = Color(0xFF07C160),
            accentSoft = Color(0x3007C160),
            waterCardStart = Color(0xFF303030),
            waterCardEnd = Color(0xFF303030),
            waterCardRight = Color(0x00000000),
            shadowCard = Color(0x40000000),
            dividerHair = Color(0xFF3A3A3A),
            switchOffTrack = Color(0xFF646464),
            switchThumb = Color.White,
            chevron = Color(0xFF6F6F6F),
            terminalBg = Color(0xFF0E1114),
            pressOverlay = Color(0x33FFFFFF),
        )

        fun forMode(dark: Boolean, pureBlack: Boolean): ColorosTokens {
            val base = if (dark) Dark else Light
            if (!pureBlack) return base
            return base.copy(
                bgPage = Color.Black,
                bgCard = Color.Black,
                bgSearchbar = Color.Black,
                bgCardTranslucent = Color.Black.copy(alpha = 0.8f),
            )
        }
    }
}

object ColorosMetrics {
    val radiusCard = 24.dp
    val radiusDialog = 20.dp
    val radiusIconWell = 12.dp
    val radiusSwitch = 20.dp
    val radiusButton = 16.dp
    val radiusChip = 20.dp
    val radiusBadge = 12.dp
    val radiusField = 12.dp
    val iconWellSize = 36.dp
    val iconSize = 36.dp
    /** 行内/井内图标。原先 16 和 20 收成这一档。 */
    val iconGlyph = 18.dp
    /** 图标按钮热区。 */
    val iconButton = 32.dp
    /** 进度条、日志等级标。 */
    val radiusHair = 4.dp
    /** 列表底部给悬浮操作条留空。 */
    val overlayClearance = 96.dp
    /** COUI-Kit Switch：44×24，thumb 18，边距 3。 */
    val switchTrackW = 44.dp
    val switchTrackH = 24.dp
    val switchThumb = 18.dp
    val switchThumbPad = 3.dp
    /** COUI Group 组间距 16dp。 */
    val spacingGroup = 16.dp
    /** COUI 卡片水平留白 16dp（父级无页边时）。 */
    val spacingSide = 16.dp
    val spacingCardPad = 20.dp
    /** 主页入口/状态/功能总览小卡内边距，避免 8 和 14 两套规格。 */
    val cardPadding = 16.dp
    val cardTitle = 13.sp
    val cardLabel = 12.sp
    val spacingRowV = 12.dp
    val spacingRowH = 16.dp
    /** COUI 页边 16dp，与分组标题 32dp、行内 16dp 对齐。 */
    val pageHorizontal = 16.dp
    val spacingTile = 8.dp
    val spacingTiny = 4.dp
    val hairline = 0.33.dp
    fun cardShape(
        topStart: Boolean = true,
        topEnd: Boolean = true,
        bottomEnd: Boolean = true,
        bottomStart: Boolean = true,
    ): androidx.compose.ui.graphics.Shape =
        if (topStart && topEnd && bottomEnd && bottomStart) {
            androidx.compose.foundation.shape.RoundedCornerShape(radiusCard)
        } else {
            ColorosG2Shape(
                radius = radiusCard,
                topStart = topStart,
                topEnd = topEnd,
                bottomEnd = bottomEnd,
                bottomStart = bottomStart,
            )
        }
    val buttonHeight = 48.dp
    /** 二级页顶栏在状态栏 inset 之外再空一截。 */
    val pageTopExtra = 16.dp
    /** 微信内主页/功能页标题到状态栏的间距，对齐桌面大标题的视觉距离。 */
    val homeHeaderTopExtra = 48.dp
    val titleLg = 28.sp
    val titleMd = 20.sp
    val dialogTitle = 17.sp
    val dialogBodySize = 15.sp
    val collapsedTitle = 18.sp
    val body = 16.sp
    val buttonText = 15.sp
    val caption = 14.sp
    val chipText = 13.sp
    val overline = 12.sp
    val tileLabel = 12.sp
    val dialogMaxWidth = 300.dp
    val dialogCompactMaxWidth = 300.dp
    val dialogCompactMinWidth = 220.dp
    val dialogCompactSide = 40.dp
    val dialogButtonSize = 16.sp
    val dialogButtonHeight = 48.dp
    val dialogGapTitleToButton = 32.dp
    val avatarSize = 48.dp
    val radiusAvatar = 8.dp
    val listRowHeight = 64.dp
    /** COUI-Kit Item rowMinHeight / iconSpacing。 */
    val rowMinHeight = 48.dp
    val iconSpacing = 16.dp
    val chipHeight = 28.dp
    val inputHeight = 48.dp
    val checkBoxSize = 22.dp
    val radioSize = 22.dp
    val tabHeight = 40.dp
    val tabTextSize = 14.sp
    val sidePanelRadius = 28.dp
    val titleLgLine = 34.sp
    val titleMdLine = 26.sp
    val dialogTitleLine = 24.sp
    val collapsedTitleLine = 24.sp
    val bodyLine = 22.sp
    val captionLine = 20.sp
    val overlineLine = 16.sp
}

private fun Color.rehue(hue: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = hue
    hsv[1] = hsv[1].coerceAtLeast(0.42f)
    hsv[2] = hsv[2].coerceIn(0.52f, 0.92f)
    val shifted = Color(android.graphics.Color.HSVToColor((alpha * 255f).toInt().coerceIn(0, 255), hsv))
    // 掺一点当前 accent，自定义取色时绿/橙/红会跟着走，而不是系统默认 iOS 色。
    return lerp(shifted, this, 0.22f)
}

val LocalColorosTokens = compositionLocalOf { ColorosTokens.Light }

object ColorosTheme {
    val tokens: ColorosTokens
        @Composable
        get() = LocalColorosTokens.current

    val metrics: ColorosMetrics
        get() = ColorosMetrics
}
