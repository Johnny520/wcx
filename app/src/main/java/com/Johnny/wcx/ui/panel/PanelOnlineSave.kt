package com.Johnny.wcx.ui.panel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

data class PanelSaveProgress(
    val title: String,
    val total: Int,
    val completed: Int = 0,
    val failed: Int = 0,
)

@Composable
fun PanelSaveProgressOverlay(
    progress: PanelSaveProgress,
    onCancel: () -> Unit,
) {
    PanelFullOverlay(onDismiss = onCancel, allowImplicitDismiss = false) {
        Text(progress.title, style = MaterialTheme.typography.titleMedium)
        val processed = progress.completed + progress.failed
        LinearProgressIndicator(
            progress = { processed.toFloat() / progress.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "已完成 $processed/${progress.total}，失败 ${progress.failed}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("中断") }
        }
    }
}
