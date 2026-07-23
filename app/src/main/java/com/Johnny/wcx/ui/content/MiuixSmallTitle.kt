package com.Johnny.wcx.ui.content

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MiuixTheme.colorScheme.onBackgroundVariant,
    insideMargin: PaddingValues = PaddingValues(14.dp, 8.dp)
) {
    Text(
        modifier = modifier.padding(insideMargin),
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = textColor,
    )
}
