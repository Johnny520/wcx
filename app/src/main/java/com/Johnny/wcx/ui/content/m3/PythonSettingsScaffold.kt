package com.Johnny.wcx.ui.content.m3

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.Johnny.wcx.ui.utils.theme.ColorosCards
import com.Johnny.wcx.ui.utils.theme.ColorosMetrics
import com.Johnny.wcx.ui.utils.theme.ColorosTheme
import com.Johnny.wcx.ui.utils.theme.ColorosTopAppBar
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold

val SETTINGS_CONTENT_BOTTOM_INSET = ColorosMetrics.spacingSide

/**
 * ColorOS 功能设置页脚手架：与模块子页同款顶栏，列表走 [SegmentedColumn] 白卡。
 */
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    /** 给 WeAgent / 红包统计这类自绘卡片用；走 SegmentedColumn 的页保持 0，避免 12+16 叠边。 */
    contentHorizontalPadding: Dp = 0.dp,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val layoutDirection = LocalLayoutDirection.current
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorosTheme.tokens.bgPage)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ColorosTopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = MaterialSymbols.Outlined.Arrow_back,
                                contentDescription = "返回",
                                tint = ColorosTheme.tokens.textPrimary,
                            )
                        }
                    }
                },
                actions = actions,
            )
        },
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + contentHorizontalPadding,
                end = innerPadding.calculateEndPadding(layoutDirection) + contentHorizontalPadding,
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding(),
            ),
        ) {
            content()
            item { Spacer(Modifier.height(SETTINGS_CONTENT_BOTTOM_INSET)) }
        }
    }
}

/**
 * [SettingsScaffold] 的编辑器变体：内容直接放进有限高度的 Box，而不是 LazyColumn item。
 * 供自带纵向滚动的组件（如脚本编辑的 BasicTextField）使用，避免嵌套滚动导致的
 * infinity max height 闪退。
 */
@Composable
fun SettingsEditorScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorosTheme.tokens.bgPage)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            ColorosTopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            top.yukonga.miuix.kmp.basic.Icon(
                                imageVector = MaterialSymbols.Outlined.Arrow_back,
                                contentDescription = "返回",
                                tint = ColorosTheme.tokens.textPrimary,
                            )
                        }
                    }
                },
                actions = actions,
            )
        },
        popupHost = {},
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            content()
        }
    }
}

@Composable
fun SettingsConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String? = "取消",
    extraLabel: String? = null,
    destructive: Boolean = false,
    loading: Boolean = false,
    icon: ImageVector? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onExtra: (() -> Unit)? = null,
) {
    if (!show) return
    ColorosCards.Alert(
        title = title,
        message = message,
        onDismiss = onDismiss,
        confirmText = confirmLabel,
        onConfirm = onConfirm,
        dismissText = dismissLabel,
        neutralText = extraLabel,
        onNeutral = onExtra,
    )
}

@Composable
fun rememberCreationBackGuard(
    guardActive: Boolean,
    onBack: () -> Unit,
    dialogTitle: String = "放弃创建？",
    dialogMessage: String = "当前填写的内容尚未保存，放弃后将丢失。",
    confirmLabel: String = "放弃",
): () -> Unit {
    var showDiscard by remember { mutableStateOf(false) }
    BackHandler(enabled = guardActive) { showDiscard = true }
    SettingsConfirmDialog(
        show = showDiscard,
        title = dialogTitle,
        message = dialogMessage,
        confirmLabel = confirmLabel,
        dismissLabel = "取消",
        destructive = true,
        onConfirm = {
            showDiscard = false
            onBack()
        },
        onDismiss = { showDiscard = false },
    )
    return { if (guardActive) showDiscard = true else onBack() }
}

@Composable
fun SettingsListActionButton(
    label: String,
    icon: ImageVector,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier,
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
fun SettingsActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = SETTINGS_CONTENT_BOTTOM_INSET),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}
