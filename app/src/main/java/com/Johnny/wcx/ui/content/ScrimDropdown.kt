package com.Johnny.wcx.ui.content

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.style.cardSurface
import com.Johnny.wcx.ui.utils.XposedLifecycleOwner
import com.Johnny.wcx.ui.utils.setLifecycleOwner
import com.Johnny.wcx.ui.utils.theme.ThemeSettings
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text

// ═══════════════════════════════════════════════════════════════════════════
//  覆盖层总入口
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 全模块唯一的「全屏覆盖层」宿主：把一层 Compose 视图挂到 Activity 的 `decorView`。
 *
 * 为什么所有弹层都走这一条路：
 * - miuix 的窗口版菜单 / `WindowDialog` → 遮罩画在「贴合内容宽度」的 Dialog 窗口里，
 *   白色主题下屏幕左右各露一条白线；
 * - Compose `Popup` → 窗口高度被系统栏裁掉，白线变成上下两条；
 * - 原生 `Dialog` + `windowIsFloating=false` → MIUI 上窗口 `frame=[0,0,0,0]`，直接不显示。
 *
 * `decorView` 是窗口根 `FrameLayout`，`MATCH_PARENT` 子 View 天然整屏（edge-to-edge 下含状态栏
 * 与导航栏），遮罩尺寸不再依赖任何窗口参数。底部半屏（[BottomSheetHost]）一直是这条路径，
 * 所以它从来没露过边。
 *
 * **重要**：[content] 运行在独立的 Compose 树（自己的 Recomposer）里。它读不到调用方 `state`
 * 的后续更新 —— 只适合「打开瞬间就定稿」的内容（菜单、确认框、只读清单）。
 * 需要跟随外部状态实时刷新的交互面板，请用就地渲染的 [BottomSheetHost]。
 */
@Composable
fun OverlayHost(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    dense: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val activity = LocalActivity.current
    val dismiss = rememberUpdatedState(onDismissRequest)
    // 挂到 decorView 的 ComposeView 需要自己的生命周期所有者（与 showComposeDialog 同一套）。
    val lifecycleOwner = remember { XposedLifecycleOwner.create() }

    DisposableEffect(activity) {
        val decor = activity?.window?.decorView as? ViewGroup
        if (decor == null) return@DisposableEffect onDispose { }

        val overlay = ComposeView(activity).apply {
            isSaveEnabled = false
            setLifecycleOwner(lifecycleOwner)
            setContent {
                // 返回键先关覆盖层（overlay 盖在内容之上，返回键仍走 Activity 的分发器）。
                BackHandler(enabled = dismissible) { dismiss.value() }
                Box(modifier.fillMaxSize()) {
                    Scrim(
                        onDismiss = { dismiss.value() },
                        dense = dense,
                        dismissible = dismissible,
                    )
                    content()
                }
            }
        }
        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        onDispose {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            lifecycleOwner.onDestroy()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  锚定菜单
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 贴着 anchor 弹出的菜单：全屏遮罩 + 内容自适应宽度的卡片。
 *
 * 定位分两步，各自在自己的 Compose 树里完成：
 * 1. 父树：[anchor] 布局后把**窗口坐标**记进 `anchorRect`；
 * 2. 覆盖层：打开时把 `anchorRect` 当作**普通值快照**传进去，卡片再用自身测量到的尺寸
 *    算最终偏移。
 *
 * 第 2 步不能用 `state` 直接跨树读 —— overlay 是独立 Recomposer，父树 state 变了它不会重组，
 * 之前就因此出现过「菜单位置停在上一次打开的那一行」「筛选按钮的气泡跑到屏幕左边」。
 *
 * @param alignEnd true 时菜单右边缘贴屏幕右边（三点菜单），false 时左边缘贴 anchor 左边（设置行）
 */
@Composable
fun ScrimDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    edgeMargin: Dp = 12.dp,
    maxHeight: Dp = 360.dp,
    anchor: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // anchor 的窗口坐标（父树状态，每次布局都会刷新）。
    var anchorRect by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier = modifier.onGloballyPositioned { coords -> anchorRect = coords.boundsInWindow() },
    ) {
        anchor()
    }

    if (!expanded) return

    // 快照：进入覆盖层后不再读取父树 state，避免「读到旧值且永不刷新」。
    val rect = anchorRect
    OverlayHost(onDismissRequest = onDismissRequest) {
        DropdownSurface(
            anchorRect = rect,
            alignEnd = alignEnd,
            edgeMargin = edgeMargin,
            maxHeight = maxHeight,
        ) {
            content()
        }
    }
}

@Composable
private fun DropdownSurface(
    anchorRect: Rect,
    alignEnd: Boolean,
    edgeMargin: Dp,
    maxHeight: Dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    // 覆盖层视图 = 整个窗口，用它拿真实屏宽/屏高（LocalConfiguration 会扣掉系统栏）。
    val windowSize = LocalWindowInfo.current.containerSize
    val gap = with(density) { 6.dp.roundToPx() }
    val margin = with(density) { edgeMargin.roundToPx() }
    val minUseful = with(density) { 150.dp.roundToPx() }
    val maxH = with(density) { maxHeight.roundToPx() }
    val shape = RoundedCornerShape(14.dp)

    // 卡片自身尺寸：与卡片同在覆盖层这棵树里，变化能正常触发重组。
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    // anchor 上下各自能放多高。上下各自扣掉系统栏，否则菜单会压到手势条/状态栏上。
    val navBarH = WindowInsets.navigationBars.getBottom(density)
    val statusBarH = WindowInsets.statusBars.getTop(density)
    val spaceBelow = (windowSize.height - navBarH - anchorRect.bottom.toInt() - gap - margin).coerceAtLeast(0)
    val spaceAbove = (anchorRect.top.toInt() - statusBarH - gap - margin).coerceAtLeast(0)
    // 优先向下：只要下面还放得下一屏的最小可用高度，就贴着 anchor 下方 + 内部滚动，
    // 而不是整块翻到上方 —— 长列表（调色板样式 9 项）翻上去会离点击的行很远。
    val preferBelow = spaceBelow >= minUseful || spaceBelow >= spaceAbove
    val available = (if (preferBelow) spaceBelow else spaceAbove).coerceAtMost(maxH)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .onSizeChanged { cardSize = it }
                .offset {
                    val x = if (alignEnd) {
                        // 右边缘贴屏幕右边
                        (windowSize.width - cardSize.width - margin).coerceAtLeast(margin)
                    } else {
                        // 左边缘贴 anchor 左边，并保证不越过右边界
                        anchorRect.left.toInt()
                            .coerceIn(margin, (windowSize.width - cardSize.width - margin).coerceAtLeast(margin))
                    }
                    IntOffset(
                        x,
                        if (preferBelow) {
                            anchorRect.bottom.toInt() + gap
                        } else {
                            (anchorRect.top.toInt() - cardSize.height - gap).coerceAtLeast(margin)
                        },
                    )
                }
                // 宽度随内容（子项都不 fillMaxWidth）：miuix 原版就是内容自适应。
                .widthIn(max = 300.dp)
                // 高度受「anchor 这一侧剩下的空间」和调用方上限共同约束，超出内部滚动。
                .heightIn(max = with(density) { available.toDp() })
                .shadow(10.dp, shape, clip = false)
                .cardSurface(shape)
                .padding(vertical = 6.dp)
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

/** 居中的遮罩对话框：全屏遮罩 + 一张卡片。取色器、二次确认这类弹窗用它。 */
@Composable
fun ScrimDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    val shape = RoundedCornerShape(24.dp)
    OverlayHost(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .shadow(24.dp, shape, clip = false)
                .cardSurface(shape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = WxPalette.textPrimary(),
                )
            }
            content()
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
//  小组件
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 菜单里的一行：图标 + 文案（+ 尾部内容）。
 *
 * 刻意**不** `fillMaxWidth()`：菜单宽度要由内容决定（和 miuix 原版一致）。
 * 用 M3 的 `DropdownMenuItem` 时它内部是 `fillMaxWidth()`，会把菜单撑到约束上限，
 * 视觉上就是「选项框被拉得很宽、比例失调」。
 */
@Composable
fun ScrimMenuItem(
    text: String,
    icon: ImageVector? = null,
    selected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = WxPalette.textSecondary(),
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            fontSize = 15.sp,
            color = if (selected) WxPalette.Primary else WxPalette.textPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

/**
 * 全模块统一的遮罩层：铺满父容器 + 点击关闭。
 *
 * 所有覆盖层都必须用它，不要再各写一份 `background(Color(0xB3000000))`：
 * 遮罩没人统一时最典型的后果就是「覆盖不全、屏幕两侧露白线」。
 *
 * [dense] = true 用于底部半屏（内容大、需要更强的聚焦），下拉菜单用默认档。
 */
@Composable
fun Scrim(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    dismissible: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val dark = !ThemeSettings.themeMode.resolve()
    val alpha = when {
        dense && !dark -> 0.50f
        dense -> 0.62f
        !dark -> 0.30f
        else -> 0.46f
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = alpha))
            // 始终拦截点击（否则会穿透到下层内容）；dismissible=false 时只拦截不关闭。
            .clickable(interactionSource = interaction, indication = null) {
                if (dismissible) onDismiss()
            },
    )
}
