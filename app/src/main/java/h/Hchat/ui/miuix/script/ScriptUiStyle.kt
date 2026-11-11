package h.Hchat.ui.miuix.script

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ui.style.WxPalette
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults

/**
 * 脚本子系统的视觉兼容层。
 *
 * 脚本页 UI 整体沿用 WCX 上游合作仓库（WCXLC-WeChatAI）的实现，那边用 ColorOS 风格
 * 的 [ColorosTheme] / [ColorosCards] 组件。这里把同名 API 映射到 WCX 自己的
 * [WxPalette] + miuix 组件上：**脚本功能、布局结构、文案与那边完全一致**，
 * 只是配色/卡片样式继续跟随 WCX 现有界面风格（iOS 分组列表风格），避免出现两套皮肤。
 */
internal object ColorosTheme {
    val tokens: Tokens get() = Tokens

    object Tokens {
        val textPrimary: Color
            @Composable get() = WxPalette.textPrimary()

        val textSecondary: Color
            @Composable get() = WxPalette.textSecondary()

        val accent: Color
            @Composable get() = WxPalette.Primary

        val bgSearchbar: Color
            @Composable get() = WxPalette.cardBackground()

        val bgPage: Color
            @Composable get() = WxPalette.pageBackground()

        val dividerHair: Color
            @Composable get() = WxPalette.cardBorder()

        val accentSoft: Color
            @Composable get() = WxPalette.PrimarySoft

        val textTertiary: Color
            @Composable get() = WxPalette.TextSecondary
    }
}

/** 卡片 / 行 / 分割线等容器组件（映射到 WCX 的白卡风格）。 */
internal object ColorosCards {

    @Composable
    fun SectionCard(
        modifier: Modifier = Modifier,
        inset: Boolean = true,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        Card(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
            cornerRadius = 18.dp,
        ) {
            Column(content = content)
        }
    }

    @Composable
    fun SectionRow(
        title: String,
        subtitle: String? = null,
        icon: (@Composable () -> Unit)? = null,
        trailing: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = WxPalette.textPrimary(),
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
            }
            if (trailing != null) trailing()
        }
    }

    @Composable
    fun GroupLabel(text: String, inset: Boolean = true) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = WxPalette.TextSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (inset) 16.dp else 0.dp, top = 14.dp, bottom = 6.dp),
        )
    }

    @Composable
    fun Hairline(startIndent: Int = 16) {
        Box(
            modifier = Modifier
                .padding(start = startIndent.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(WxPalette.cardBorder()),
        )
    }

    @Composable
    fun Chevron() {
        Text(
            text = "›",
            fontSize = 18.sp,
            color = WxPalette.TextSecondary,
        )
    }

    /** 彩色圆角图标底（白字图标用）。 */
    @Composable
    fun IconWell(
        color: Color,
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit,
    ) {
        Box(
            modifier = modifier
                .size(28.dp)
                .background(color, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** ColorOS 顶栏 → miuix [TopAppBar]（参数与 miuix 版一致）。 */
@Composable
internal fun ColorosTopAppBar(
    title: String,
    largeTitle: String,
    scrollBehavior: Any? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val behavior = MiuixScrollBehavior()
    TopAppBar(
        title = title,
        largeTitle = largeTitle,
        scrollBehavior = behavior,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}
