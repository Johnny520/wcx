package h.Hchat.ui.miuix

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import h.Hchat.hooks.items.moments.MomentsUploadTailPresets
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun MomentsUploadTailPresetPicker(
    show: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onPresetSelected: (MomentsUploadTailPresets.Preset) -> Unit
) {
    if (!show) return

    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val visiblePresets = remember(normalizedQuery) {
        if (normalizedQuery.isEmpty()) MomentsUploadTailPresets.options
        else MomentsUploadTailPresets.options.filter {
            it.name.lowercase(Locale.ROOT).contains(normalizedQuery)
        }
    }

    WindowDialog(
        show = true,
        title = "选择内置尾巴",
        onDismissRequest = onDismiss,
        content = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                item {
                    InputRow("搜索名称", "", query, onValueChange = onQueryChange)
                }
                if (visiblePresets.isEmpty()) {
                    item {
                        Text(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            text = "没有匹配的名称",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(visiblePresets, key = { it.appId }) { preset ->
                        ActionRow(preset.name, "") { onPresetSelected(preset) }
                    }
                }
            }
        }
    )
}
