package com.Johnny.wcx.ui.utils.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * 简化版注入主题：用模块自身 MaterialTheme 包裹注入到微信内的 Compose 内容。
 * （WeKit 版本依赖其多语言/i18n 主题体系，本模块不连带，使用标准 MaterialTheme。）
 */
@Composable
fun InjectedUiTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    MaterialTheme {
        content()
    }
}