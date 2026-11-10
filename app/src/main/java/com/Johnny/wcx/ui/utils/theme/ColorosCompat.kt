package com.Johnny.wcx.ui.utils.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ScrollBehavior

/**
 * Python 脚本页所需的 ColorOS 风格组件的最小实现。
 *
 * 合作仓库 WCXLC-WeChatAI 的 `ColorosComponents.kt` 依赖第三方 COUI（`io.github.itoseo:coui`），
 * 该库会带入自己的 compose-bom-alpha，与 WCX 现有 material3 版本冲突（对方仓库也注明
 * 「不吃 COUI 的 compose-bom，避免 M3 涟漪再次坏掉」）。为了不动 WCX 现有触摸/涟漪行为，
 * 这里只用 Compose 原生组件补齐 Python 页实际用到的三个入口 + 一个顶栏。
 */
object ColorosCards {

    /** 开关：直接复用 material3 Switch，保留 `onCheckedChange == null` 的「不可切换」语义。 */
    @Composable
    fun Switch(
        checked: Boolean,
        onCheckedChange: ((Boolean) -> Unit)?,
        modifier: Modifier = Modifier,
        enabled: Boolean = true,
    ) {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }

    /** 单行/多行输入框。 */
    @Composable
    fun Input(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String,
        modifier: Modifier = Modifier,
        singleLine: Boolean = true,
        maxLines: Int = if (singleLine) 1 else 4,
        enabled: Boolean = true,
        keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
        visualTransformation: VisualTransformation = VisualTransformation.None,
        trailingIcon: @Composable (() -> Unit)? = null,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = ColorosTheme.tokens.textTertiary) }
            } else {
                null
            },
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
        )
    }

    /** 输入框（TextFieldValue 版本，供需要光标/选区控制的场景使用）。 */
    @Composable
    fun Input(
        value: androidx.compose.ui.text.input.TextFieldValue,
        onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
        placeholder: String,
        modifier: Modifier = Modifier,
        singleLine: Boolean = true,
        maxLines: Int = if (singleLine) 1 else 4,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            placeholder = if (placeholder.isNotEmpty()) {
                { Text(placeholder, color = ColorosTheme.tokens.textTertiary) }
            } else {
                null
            },
            singleLine = singleLine,
            maxLines = maxLines,
        )
    }

    /** 确认/提示对话框。 */
    @Composable
    fun Alert(
        title: String,
        message: String,
        onDismiss: () -> Unit,
        confirmText: String = "确定",
        onConfirm: (() -> Unit)? = null,
        dismissText: String? = null,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title, color = ColorosTheme.tokens.textTitle, fontWeight = FontWeight.SemiBold) },
            text = { Text(message, color = ColorosTheme.tokens.textPrimary) },
            confirmButton = {
                TextButton(onClick = { onConfirm?.invoke() ?: onDismiss() }) { Text(confirmText) }
            },
            dismissButton = when {
                dismissText == null && neutralText == null -> null
                else -> {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (neutralText != null) {
                                TextButton(onClick = { onNeutral?.invoke() ?: onDismiss() }) {
                                    Text(neutralText, color = ColorosTheme.tokens.textSecondary)
                                }
                            }
                            if (dismissText != null) {
                                TextButton(onClick = onDismiss) {
                                    Text(dismissText, color = ColorosTheme.tokens.textSecondary)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

/**
 * ColorOS 风格顶栏（大标题 + 可选返回/动作区）。
 *
 * `scrollBehavior` 仅用于保持与调用方签名一致（调用方传入 miuix 的 ScrollBehavior），
 * 本实现不参与折叠，避免与现有 MiuixListScaffold 的滚动行为互相干扰。
 */
@Composable
fun ColorosTopAppBar(
    title: String,
    largeTitle: String = title,
    @Suppress("UNUSED_PARAMETER") scrollBehavior: ScrollBehavior,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon?.invoke()
            Text(
                text = largeTitle,
                modifier = Modifier.weight(1f),
                color = ColorosTheme.tokens.textTitle,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
        bottomContent?.invoke()
    }
}

/**
 * 按系统深浅色提供 ColorOS tokens。
 *
 * [LocalColorosTokens] 的默认值是浅色，如果不 provide，深色下会出现白底深字与页面背景不一致；
 * Python 脚本页整体包一层即可。
 */
@Composable
fun ColorosThemeProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalColorosTokens provides if (isSystemInDarkTheme()) ColorosTokens.Dark else ColorosTokens.Light,
        content = content,
    )
}
