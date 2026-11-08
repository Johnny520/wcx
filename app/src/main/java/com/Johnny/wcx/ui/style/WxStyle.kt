package com.Johnny.wcx.ui.style

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WCX 视觉规范（iOS 分组卡片风格）。
 *
 * 设计基调：浅灰底（#F2F2F7）+ 纯白大圆角卡片 + 分区标题裸排 + 胶囊标签 + 品牌绿点缀。
 * 层次靠「灰底衬托白卡」而不是阴影，圆角统一 18/14/50 三档。
 */
object WxPalette {
    /** 页面底色 */
    val Background = Color(0xFFF2F2F7)

    /** 主色（品牌绿） */
    val Primary = Color(0xFF009944)
    val PrimarySoft = Color(0xFFE6F5EC)

    /** 语义色 */
    val Danger = Color(0xFFE53935)
    val DangerSoft = Color(0xFFFFEBEE)
    val Warning = Color(0xFFF9A825)
    val WarningSoft = Color(0xFFFFF8E1)
    val Info = Color(0xFF1E88E5)
    val InfoSoft = Color(0xFFE3F2FD)

    /** 文字 */
    val TextPrimary = Color(0xFF1C1C1E)
    val TextSecondary = Color(0xFF8E8E93)
    val TextTertiary = Color(0xFFC7C7CC)

    /** 卡片强调渐变（极浅，接近白，只做轻微区分） */
    val GradBlue = listOf(Color(0xFFF0F6FF), Color(0xFFE8F1FE))
    val GradMint = listOf(Color(0xFFEFFAF5), Color(0xFFE3F6EC))
    val GradPurple = listOf(Color(0xFFF6F1FD), Color(0xFFEDE4FA))
    val GradPink = listOf(Color(0xFFFDF1F3), Color(0xFFFAE6EA))
    val GradPlain = listOf(Color.White, Color.White)

    /** 卡片圆角 */
    val CardRadius = 20.dp
    val ChipRadius = 14.dp
    val PillRadius = 50.dp

    /** 页面底色（跟随深浅色）。深色用冷调近黑，不用死黑。 */
    @Composable
    fun pageBackground(): Color =
        if (isSystemInDarkTheme()) DarkBackground else Background

    /** 卡片底色（跟随深浅色）。深色比底板亮一档，靠明度差拉出层次。 */
    @Composable
    fun cardBackground(): Color =
        if (isSystemInDarkTheme()) Color(0xFF1A1D24) else Color.White

    /** 主文字色（跟随深浅色） */
    @Composable
    fun textPrimary(): Color =
        if (isSystemInDarkTheme()) Color(0xFFF2F2F7) else TextPrimary

    /** 次文字色 */
    @Composable
    fun textSecondary(): Color = TextSecondary

    // ── 高科技增强令牌 ──────────────────────────────────────────────────────
    /** 强调渐变两端：青绿 → 电光蓝。用于选中态、进度、关键按钮。 */
    val AccentStart = Color(0xFF00D4A0)
    val AccentEnd = Color(0xFF3D7EFF)
    val AccentViolet = Color(0xFF8B5CF6)

    /** 深色模式底板（比纯黑略亮、带冷调，避免死黑）。 */
    val DarkBackground = Color(0xFF0A0B0F)
    val DarkSurface = Color(0xFF161A21)
    val DarkSurfaceHigh = Color(0xFF1E222B)

    /** 页面氛围底：深色为冷调纵向渐变，浅色为中性微渐变。 */
    @Composable
    fun pageBackgroundBrush(): Brush = if (isSystemInDarkTheme()) {
        Brush.linearGradient(listOf(Color(0xFF0A0B0F), Color(0xFF11141A), Color(0xFF0A0B0F)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFF7F8FB), Color(0xFFEFF2F7)))
    }

    /** 卡片底：深色为「上亮下暗」冷调玻璃，浅色为白到极淡灰。 */
    @Composable
    fun cardBrush(): Brush = if (isSystemInDarkTheme()) {
        Brush.linearGradient(listOf(Color(0xFF1E222B), Color(0xFF161A21)))
    } else {
        Brush.linearGradient(listOf(Color.White, Color(0xFFFCFCFE)))
    }

    /** 卡片描边：左上亮、右下透明的渐变，勾出玻璃边缘高光。 */
    @Composable
    fun cardBorderBrush(): Brush = if (isSystemInDarkTheme()) {
        Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0AFFFFFF)))
    } else {
        Brush.linearGradient(listOf(Color(0x14000000), Color(0x05000000)))
    }

    /** 强调渐变画刷。 */
    fun accentBrush(): Brush = Brush.linearGradient(listOf(AccentStart, AccentEnd))

    /**
     * 把浅色的强调渐变映射成深色版本。
     *
     * 这些 Grad* 原本是给白卡配的极浅色；深色模式下直接用会「亮瞎」，
     * 这里统一降为同色系的暗调，调用方不需要自己判断深浅色。
     */
    @Composable
    fun darkenGradient(gradient: List<Color>): List<Color> {
        if (!isSystemInDarkTheme()) return gradient
        return when (gradient) {
            GradBlue -> listOf(Color(0xFF16233A), Color(0xFF121C2C))
            GradMint -> listOf(Color(0xFF122A24), Color(0xFF0F2019))
            GradPurple -> listOf(Color(0xFF1E1733), Color(0xFF171129))
            GradPink -> listOf(Color(0xFF2A1620), Color(0xFF1F1117))
            else -> gradient
        }
    }

    /** 卡片描边：浅色下几乎不可见，深色下一层极淡白边 */
    @Composable
    fun cardBorder(): Color =
        if (isSystemInDarkTheme()) Color(0x1AFFFFFF) else Color(0x0D000000)
}

/**
 * 主卡片（页面的基本容器）。
 *
 * 视觉：冷调玻璃 —— 渐变底 + 左上高光描边 + 20dp 圆角，层次靠「底色差 + 边缘高光」，
 * 不堆阴影。可点击时自带 0.972 按压回弹（spring），全局统一手感。
 * [glow] = true 时叠一圈强调渐变描边，用于「当前状态/主操作」卡片。
 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    gradient: List<Color> = WxPalette.GradPlain,
    contentPadding: Int = 16,
    glow: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val plain = gradient.isEmpty() || gradient == WxPalette.GradPlain
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.972f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "cardPress",
    )
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    val shape = RoundedCornerShape(WxPalette.CardRadius)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(clickModifier),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (plain) WxPalette.cardBrush()
                    else Brush.linearGradient(WxPalette.darkenGradient(gradient)),
                    shape,
                )
                .border(if (glow) 1.2.dp else 1.dp, if (glow) WxPalette.accentBrush() else WxPalette.cardBorderBrush(), shape)
                .padding(contentPadding.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * 页面氛围背景：底色渐变 + 两处极淡径向光斑（右上电光蓝、左中青绿）。
 *
 * 纯 Canvas 绘制，无图片资源、无模糊开销；深浅色各自一档透明度，
 * 让整页有「纵深」而不是一块死板纯色。
 */
@Composable
fun AtmosphereBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val glowBlue = if (dark) Color(0x243D7EFF) else Color(0x123D7EFF)
    val glowMint = if (dark) Color(0x1C00D4A0) else Color(0x0E00D4A0)
    Box(modifier.fillMaxSize().background(WxPalette.pageBackgroundBrush())) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val c1 = Offset(w * 0.86f, h * 0.06f)
            val r1 = w * 0.92f
            drawCircle(
                brush = Brush.radialGradient(listOf(glowBlue, Color.Transparent), center = c1, radius = r1),
                radius = r1,
                center = c1,
            )
            val c2 = Offset(w * 0.06f, h * 0.58f)
            val r2 = w * 0.78f
            drawCircle(
                brush = Brush.radialGradient(listOf(glowMint, Color.Transparent), center = c2, radius = r2),
                radius = r2,
                center = c2,
            )
        }
        content()
    }
}

/** 圆形图标容器：浅底 + 彩色图标，直径默认 40dp。 */
@Composable
fun RoundIconBox(
    icon: ImageVector,
    tint: Color = WxPalette.Primary,
    background: Color = Color.White,
    size: Int = 40,
    iconSize: Int = 20,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** 分区标题：灰色小字裸排，位于卡片组上方（iOS 分组列表观感）。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = WxPalette.TextSecondary) {
    Text(
        text = text,
        modifier = modifier.padding(start = 6.dp, top = 2.dp, bottom = 6.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color,
    )
}

/** 通用行：圆形图标 + 标题/副标题 + 右侧值或箭头区。 */
@Composable
fun StyleRow(
    icon: ImageVector? = null,
    title: String,
    subtitle: String? = null,
    iconTint: Color = WxPalette.Primary,
    iconBackground: Color = Color.White,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            RoundIconBox(icon = icon, tint = iconTint, background = iconBackground)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = WxPalette.textPrimary(),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = WxPalette.TextSecondary,
                )
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = 8.dp)) { trailing() }
        }
    }
}

/** 风险提示横幅：浅红底 + 红字，置于页面顶部。 */
@Composable
fun DangerBanner(title: String, message: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(WxPalette.CardRadius)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFFFFE9EA), Color(0xFFFFF6F6))), shape)
                .border(1.dp, Color(0x14E53935), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = WxPalette.Danger,
            )
            Text(
                text = message,
                fontSize = 12.sp,
                color = Color(0xFF7A3B3B),
                lineHeight = 18.sp,
            )
        }
    }
}

/** 状态小胶囊。 */
@Composable
fun StatusPill(text: String, color: Color = WxPalette.Primary, background: Color = WxPalette.PrimarySoft) {
    Surface(shape = RoundedCornerShape(WxPalette.PillRadius), color = background) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** 卡片内列表项分割线（左右不顶边）。 */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp)
            .height(1.dp)
            .background(if (isSystemInDarkTheme()) Color(0x1FFFFFFF) else Color(0x14000000)),
    )
}

/** 页面主标题：黑色大号粗体（iOS 大标题观感）。 */
@Composable
fun StyleTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        color = WxPalette.textPrimary(),
    )
}
