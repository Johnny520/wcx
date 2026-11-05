package com.Johnny.wcx.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val CardRadius = 18.dp
    val ChipRadius = 14.dp
    val PillRadius = 50.dp

    /** 页面底色（跟随深浅色） */
    @Composable
    fun pageBackground(): Color =
        if (isSystemInDarkTheme()) Color(0xFF000000) else Background

    /** 卡片底色（跟随深浅色） */
    @Composable
    fun cardBackground(): Color =
        if (isSystemInDarkTheme()) Color(0xFF1C1C1E) else Color.White

    /** 主文字色（跟随深浅色） */
    @Composable
    fun textPrimary(): Color =
        if (isSystemInDarkTheme()) Color(0xFFF2F2F7) else TextPrimary

    /** 次文字色 */
    @Composable
    fun textSecondary(): Color = TextSecondary

    /** 卡片描边：浅色下几乎不可见，深色下一层极淡白边 */
    @Composable
    fun cardBorder(): Color =
        if (isSystemInDarkTheme()) Color(0x1AFFFFFF) else Color(0x0D000000)
}

/** 纯白圆角卡片（页面的基本容器）。传 gradient 时用作强调卡。 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    gradient: List<Color> = WxPalette.GradPlain,
    contentPadding: Int = 16,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val plain = gradient.isEmpty() || gradient === WxPalette.GradPlain || gradient == WxPalette.GradPlain
    val brush = if (plain) {
        val c = if (dark) Color(0xFF1C1C1E) else Color.White
        Brush.linearGradient(listOf(c, c))
    } else {
        Brush.linearGradient(gradient)
    }
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    val shape = RoundedCornerShape(WxPalette.CardRadius)
    Surface(
        modifier = modifier.fillMaxWidth().then(clickModifier),
        shape = shape,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(brush)
                .border(1.dp, WxPalette.cardBorder(), shape)
                .padding(contentPadding.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
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
