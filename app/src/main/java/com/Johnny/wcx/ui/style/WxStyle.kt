package com.Johnny.wcx.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.MaterialTheme
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
 * WCX 新版视觉规范（马卡龙风格）。
 *
 * 设计基调：浅灰白底 + 柔和渐变卡片 + 大圆角 + 圆形图标容器 + 绿色主色。
 * 目标不是复刻某个 App，而是统一「清爽、低饱和、留白足够」的观感。
 */
object WxPalette {
    /** 页面底色 */
    val Background = Color(0xFFF7F9FA)

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

    /** 卡片渐变（浅 → 稍深） */
    val GradBlue = listOf(Color(0xFFE9F3FE), Color(0xFFD6E9FC))
    val GradMint = listOf(Color(0xFFE4F8F2), Color(0xFFCFEFE6))
    val GradPurple = listOf(Color(0xFFF3ECFB), Color(0xFFE5D8F7))
    val GradPink = listOf(Color(0xFFFDECEF), Color(0xFFF9D7DE))
    val GradPlain = listOf(Color.White, Color(0xFFFFFFFF))

    /** 卡片圆角 */
    val CardRadius = 20.dp
    val ChipRadius = 14.dp
}

/** 柔和渐变卡片（FkWeChat 风格的主体容器）。 */
@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    gradient: List<Color> = WxPalette.GradPlain,
    contentPadding: Int = 16,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().then(clickModifier),
        shape = RoundedCornerShape(WxPalette.CardRadius),
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(gradient))
                .padding(contentPadding.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** 圆形图标容器：白/浅底 + 彩色图标，直径默认 40dp。 */
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

/** 分组标题：绿色小字，位于卡片上方左侧。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = WxPalette.Primary) {
    Text(
        text = text,
        modifier = modifier.padding(start = 6.dp, bottom = 6.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
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
                color = Color(0xFF2B2B2B),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = Color(0xFF8A8A8A),
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(WxPalette.CardRadius),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFFFFE9EA), Color(0xFFFFF6F6))))
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
    Surface(shape = RoundedCornerShape(WxPalette.ChipRadius), color = background) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** 轻微的下沉分隔线（卡片内分组）。 */
@Composable
fun SoftDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .background(Color(0x14000000)),
    )
}

/** 标题样式：页面主标题（绿色加粗）。 */
@Composable
fun StyleTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = WxPalette.Primary,
        style = MaterialTheme.typography.titleLarge,
    )
}
