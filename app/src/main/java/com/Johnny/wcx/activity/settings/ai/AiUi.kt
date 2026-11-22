package com.Johnny.wcx.activity.settings.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette

/**
 * AI 页的共用小件。
 *
 * 只放「这个页面里多处用到」的东西，不放业务：颜色一律走 `WxPalette` 令牌，
 * 卡片一律 `cardSurface` / `SoftCard`，开关一律 Material3 `Switch`。
 */

/** 头像：没有地址或加载失败时显示首字色块，避免一排破图。 */
@Composable
fun AiAvatar(model: String, name: String, size: Int = 40) {
    val shape = RoundedCornerShape(if (size > 44) 12.dp else 6.dp)
    if (model.isBlank()) {
        AiAvatarFallback(name, size, shape)
        return
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(WxPalette.primarySoft()),
        contentAlignment = Alignment.Center,
    ) {
        // 底下的首字始终画着：图片透明或加载中时不会露出空白。
        AiAvatarLetter(name, size)
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size.dp)
                .clip(shape),
        )
    }
}

@Composable
private fun AiAvatarFallback(name: String, size: Int, shape: RoundedCornerShape) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(WxPalette.primarySoft()),
        contentAlignment = Alignment.Center,
    ) {
        AiAvatarLetter(name, size)
    }
}

@Composable
private fun AiAvatarLetter(name: String, size: Int) {
    Text(
        text = name.trim().take(1).ifBlank { "?" },
        fontSize = (size * 0.42f).sp,
        fontWeight = FontWeight.Medium,
        color = WxPalette.Primary,
    )
}

/** 列表行：右箭头 + 点击。用于「聊天目标 / 标签管理 / 记忆管理」。 */
@Composable
fun AiNavRow(
    title: String,
    subtitle: String = "",
    onClick: () -> Unit,
    showDivider: Boolean = true,
    trailing: String = "",
) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                color = WxPalette.textPrimary(),
            )
            Spacer(Modifier.weight(1f))
            if (trailing.isNotBlank()) {
                Text(
                    text = trailing,
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            if (subtitle.isNotBlank() && trailing.isBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            Icon(
                imageVector = MaterialSymbols.Outlined.Chevron_right,
                contentDescription = null,
                tint = WxPalette.textTertiary(),
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp),
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(WxPalette.divider())
            )
        }
    }
}

/** 开关行：左侧标题 + 可选副标题，右侧开关。 */
@Composable
fun AiSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String = "",
    subtitleOnClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    color = if (enabled) WxPalette.textPrimary() else WxPalette.textTertiary(),
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = WxPalette.textSecondary(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .then(
                                if (subtitleOnClick != null) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = subtitleOnClick,
                                    )
                                } else Modifier
                            ),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = WxPalette.Primary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = WxPalette.textTertiary().copy(alpha = 0.5f),
                ),
            )
        }
        if (showDivider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .height(0.5.dp)
                    .background(WxPalette.divider())
            )
        }
    }
}

/** 主按钮（实心绿）。 */
@Composable
fun AiPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) WxPalette.Primary else WxPalette.Primary.copy(alpha = 0.35f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

/** 次级按钮（描边）。 */
@Composable
fun AiSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val tint = color ?: WxPalette.textPrimary()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(WxPalette.insetSurface())
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 15.sp, color = tint)
    }
}

/** 空态卡：一句说明 + 一个动作。 */
@Composable
fun AiEmptyCard(
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    SoftCard {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = WxPalette.textPrimary())
        Text(body, fontSize = 13.sp, color = WxPalette.textSecondary(), lineHeight = 19.sp)
        if (action != null && onAction != null) {
            AiPrimaryButton(text = action, onClick = onAction)
        }
    }
}

/** 图标 + 文案的胶囊按钮（顶部「＋ 添加」之类）。 */
@Composable
fun AiIconPill(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(WxPalette.primarySoft())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = WxPalette.Primary, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 13.sp, color = WxPalette.Primary, fontWeight = FontWeight.Medium)
    }
}

/** 轻量输入框：圆角浅底 + 单行/多行。 */
@Composable
fun AiTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    minHeight: Int = 0,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.insetSurface())
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .then(if (minHeight > 0) Modifier.height(minHeight.dp) else Modifier),
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = WxPalette.textTertiary())
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                color = WxPalette.textPrimary(),
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(WxPalette.Primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
