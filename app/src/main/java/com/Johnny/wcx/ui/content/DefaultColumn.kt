package com.Johnny.wcx.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DefaultColumn(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val finalModifier = if (scrollable) {
        modifier.verticalScroll(rememberScrollState())
    } else {
        modifier
    }
    Column(
        modifier = finalModifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}
