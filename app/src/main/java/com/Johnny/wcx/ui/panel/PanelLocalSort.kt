package com.Johnny.wcx.ui.panel

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Sort
import com.Johnny.wcx.features.items.chat.panel.LocalSortMode

internal fun panelLocalSortAction(
    mode: LocalSortMode,
    enabled: Boolean = true,
    onModeChange: (LocalSortMode) -> Unit,
    onStartCustomOrder: () -> Unit,
) = PanelAction(
    icon = MaterialSymbols.Outlined.Sort,
    label = mode.label,
    enabled = enabled,
    showLabel = true,
    onLongClick = onStartCustomOrder.takeIf { mode == LocalSortMode.CUSTOM && enabled },
    onClick = { onModeChange(mode.next()) },
)

internal fun panelReorderActions(
    onCancel: () -> Unit,
    onSave: () -> Unit,
) = listOf(
    PanelAction(MaterialSymbols.Outlined.Close, "取消", onClick = onCancel),
    PanelAction(MaterialSymbols.Outlined.Save, "保存", onClick = onSave),
)

internal fun <T> List<T>.moveItem(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }
