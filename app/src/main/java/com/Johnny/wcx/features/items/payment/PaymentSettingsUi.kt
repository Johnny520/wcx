package com.Johnny.wcx.features.items.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Check
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Chevron_right

import com.Johnny.wcx.features.items.AutomationKeywordMode
import com.Johnny.wcx.features.items.AutomationKeywordRule
import com.Johnny.wcx.features.items.AutomationTimeRangeRule
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.WeTimeOfDayField
import com.Johnny.wcx.ui.content.m3.BaseItemContainer
import com.Johnny.wcx.ui.content.m3.BaseSupportingWidget
import com.Johnny.wcx.ui.content.m3.BaseWidget
import com.Johnny.wcx.ui.content.m3.DropDownMenuWidget
import com.Johnny.wcx.ui.content.m3.DropdownOption
import com.Johnny.wcx.ui.content.m3.SegmentedColumnScope
import com.Johnny.wcx.ui.content.m3.SwitchWidget

/**
 * Free-form value being edited through an in-dialog edit view. The payment dialogs live in a
 * single [com.Johnny.wcx.ui.utils.showComposeDialog] window, so text values swap the dialog
 * content instead of opening a second window.
 */
internal class PaymentTextEditMode(
    val title: String,
    val initial: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val filter: (String) -> String = { it },
    val supportingText: String? = null,
    val validator: ((String) -> String?)? = null,
    val onCommit: (String) -> Unit,
)

@Composable
internal fun PaymentTextEditDialog(
    mode: PaymentTextEditMode,
    onClose: () -> Unit,
) {
    var draft by remember(mode) { mutableStateOf(mode.initial) }
    val validationError = mode.validator?.invoke(draft)

    AlertDialogContent(
        title = { Text(mode.title) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = mode.filter(it) },
                keyboardOptions = KeyboardOptions(keyboardType = mode.keyboardType),
                supportingText = (validationError ?: mode.supportingText)?.let { text -> { Text(text) } },
                isError = validationError != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                enabled = validationError == null,
                onClick = { mode.onCommit(draft); onClose() },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("取消") }
        },
    )
}

/** Navigation entry inside a payment settings dialog, e.g. "Global settings" with a chevron. */
@Composable
internal fun PaymentNavigationRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = description,
        onClick = onClick,
        trailingContent = {
            Icon(
                imageVector = MaterialSymbols.Outlined.Chevron_right,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * Switch row for one automation rule. [overridden] is null in the global editor (plain toggle);
 * in a per-contact editor it drives the follow-parent semantics: a non-activated rule shows the
 * parent summary, activates on row tap, and exposes a reset action once activated.
 */
@Composable
internal fun PaymentRuleRow(
    title: String,
    summary: String,
    checked: Boolean,
    overridden: Boolean?,
    parentLabel: String = "",
    onActivate: () -> Unit = {},
    onReset: () -> Unit = {},
    onCheckedChange: (Boolean) -> Unit,
) {
    if (overridden == null) {
        SwitchWidget(
            iconPlaceholder = false,
            title = title,
            description = summary,
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        return
    }

    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = if (overridden) {
            summary
        } else {
            "跟随%1\$s: ".format(parentLabel, summary)
        },
        onClick = { if (overridden) onCheckedChange(!checked) else onActivate() },
        trailingDivider = true,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(enabled = overridden, onClick = onReset) {
                Text("重置")
            }
            Switch(
                checked = checked,
                enabled = overridden,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedIconColor = MaterialTheme.colorScheme.primary,
                    uncheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                thumbContent = {
                    Icon(
                        imageVector = if (checked) MaterialSymbols.Outlined.Check else MaterialSymbols.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize)
                    )
                },
            )
        }
    }
}

/** Switch-less variant of [PaymentRuleRow] for rules whose value is a choice, not a toggle. */
@Composable
internal fun PaymentModeRuleRow(
    title: String,
    summary: String,
    overridden: Boolean?,
    parentLabel: String,
    onActivate: () -> Unit,
    onReset: () -> Unit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = if (overridden == false) {
            "跟随%1\$s: ".format(parentLabel, summary)
        } else {
            summary
        },
        onClick = if (overridden == false) onActivate else null,
        trailingContent = {
            if (overridden != null) {
                TextButton(enabled = overridden, onClick = onReset) {
                    Text("重置")
                }
            }
        },
    )
}

/** Row showing a stored value that is edited through an in-dialog [PaymentTextEditMode]. */
@Composable
internal fun PaymentValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    valueHint: String? = null,
    trailingIcon: ImageVector = MaterialSymbols.Outlined.Edit,
) {
    BaseWidget(
        iconPlaceholder = false,
        title = title,
        description = value.ifBlank { valueHint },
        enabled = enabled,
        onClick = onClick,
        trailingContent = { Icon(trailingIcon, null) },
    )
}

@Composable
internal fun PaymentErrorRow(message: String) {
    BaseWidget(iconPlaceholder = false, title = message, isError = true)
}

/** Time-range fields shown while the owning rule is enabled. */
internal fun SegmentedColumnScope.timeRangeItems(
    rule: AutomationTimeRangeRule,
    editable: Boolean,
    visible: Boolean,
    onChange: (AutomationTimeRangeRule) -> Unit,
) {
    item(key = "time_range_fields", animatedVisibility = visible) {
        BaseItemContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WeTimeOfDayField(
                    modifier = Modifier.weight(1f),
                    label = "开始",
                    minuteOfDay = rule.startMinute,
                    enabled = editable,
                    onMinuteChange = { onChange(rule.copy(startMinute = it)) }
                )
                WeTimeOfDayField(
                    modifier = Modifier.weight(1f),
                    label = "结束",
                    minuteOfDay = rule.endMinute,
                    enabled = editable,
                    onMinuteChange = { onChange(rule.copy(endMinute = it)) }
                )
            }
        }
    }
}

/** Keyword rule controls: match mode, ignore-case, keyword list / regex. */
internal fun SegmentedColumnScope.keywordItems(
    keyPrefix: String,
    rule: AutomationKeywordRule,
    editable: Boolean,
    visible: Boolean,
    modes: List<AutomationKeywordMode>,
    onChange: (AutomationKeywordRule) -> Unit,
    onEditText: (PaymentTextEditMode) -> Unit,
    inlineTextFields: Boolean = false,
) {
    item(key = "${keyPrefix}_mode", animatedVisibility = visible) {
        DropDownMenuWidget(
            iconPlaceholder = false,
            title = "匹配方式",
            description = null,
            value = rule.mode,
            options = modes.map { mode ->
                DropdownOption(
                    value = mode,
                    label = when (mode) {
                        AutomationKeywordMode.STRING_LIST -> "包含"
                        AutomationKeywordMode.EXACT -> "相等"
                        AutomationKeywordMode.REGEX -> "Regex"
                    },
                )
            },
            enabled = editable,
            onValueChange = { onChange(rule.copy(mode = it)) },
        )
    }
    item(key = "${keyPrefix}_ignore_case", animatedVisibility = visible) {
        SwitchWidget(
            iconPlaceholder = false,
            title = "忽略大小写",
            checked = rule.ignoreCase,
            enabled = editable,
            onCheckedChange = { onChange(rule.copy(ignoreCase = it)) },
        )
    }
    if (rule.mode == AutomationKeywordMode.REGEX) {
        item(key = "${keyPrefix}_regex", animatedVisibility = visible) {
            val regexTitle = "Regex"
            val invalidRegexMessage = "正则表达式格式不正确"
            val regexError = invalidRegexMessage.takeIf { runCatching { Regex(rule.regex) }.isFailure }
            if (inlineTextFields) {
                BaseSupportingWidget(
                    title = regexTitle,
                    description = "尚未填写正则表达式",
                ) {
                    OutlinedTextField(
                        value = rule.regex,
                        enabled = editable,
                        onValueChange = { onChange(rule.copy(regex = it)) },
                        supportingText = regexError?.let { error -> { Text(error) } },
                        isError = regexError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            } else {
                PaymentValueRow(
                    title = regexTitle,
                    value = rule.regex,
                    enabled = editable,
                    valueHint = "尚未填写正则表达式",
                    onClick = {
                        onEditText(
                            PaymentTextEditMode(
                                title = regexTitle,
                                initial = rule.regex,
                                validator = { input ->
                                    invalidRegexMessage.takeIf { runCatching { Regex(input) }.isFailure }
                                },
                                onCommit = { onChange(rule.copy(regex = it)) },
                            )
                        )
                    },
                )
            }
        }
        return
    }
    rule.strings.forEach { keyword ->
        item(key = "${keyPrefix}_entry_$keyword", animatedVisibility = visible) {
            BaseWidget(
                iconPlaceholder = false,
                title = keyword,
                enabled = editable,
                trailingContent = {
                    IconButton(enabled = editable, onClick = { onChange(rule.copy(strings = rule.strings - keyword)) }) {
                        Icon(MaterialSymbols.Outlined.Delete, null)
                    }
                },
            )
        }
    }
    item(key = "${keyPrefix}_add", animatedVisibility = visible) {
        val addTitle = "新关键词"
        if (inlineTextFields) {
            var pendingKeyword by remember { mutableStateOf("") }
            BaseSupportingWidget(title = addTitle) {
                OutlinedTextField(
                    value = pendingKeyword,
                    enabled = editable,
                    onValueChange = { pendingKeyword = it },
                    trailingIcon = {
                        IconButton(
                            enabled = editable && pendingKeyword.trim().isNotEmpty(),
                            onClick = {
                                val keyword = pendingKeyword.trim()
                                if (keyword !in rule.strings) {
                                    onChange(rule.copy(strings = rule.strings + keyword))
                                }
                                pendingKeyword = ""
                            },
                        ) {
                            Icon(MaterialSymbols.Outlined.Add, null)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        } else {
            PaymentValueRow(
                title = addTitle,
                value = "",
                enabled = editable,
                onClick = {
                    onEditText(
                        PaymentTextEditMode(
                            title = addTitle,
                            initial = "",
                            onCommit = { input ->
                                val keyword = input.trim()
                                if (keyword.isNotEmpty() && keyword !in rule.strings) {
                                    onChange(rule.copy(strings = rule.strings + keyword))
                                }
                            },
                        )
                    )
                },
                trailingIcon = MaterialSymbols.Outlined.Add,
            )
        }
    }
}

/** Delay value rows shown while the delay rule is enabled. */
internal fun SegmentedColumnScope.delayItems(
    baseMs: String,
    randomRangeMs: String,
    editable: Boolean,
    visible: Boolean,
    maxDigits: Int,
    onBaseChange: (String) -> Unit,
    onRandomRangeChange: (String) -> Unit,
    onEditText: (PaymentTextEditMode) -> Unit,
) {
    val digitFilter: (String) -> String = { it.filter(Char::isDigit).take(maxDigits) }

    item(key = "delay_base", animatedVisibility = visible) {
        val baseTitle = "基础延迟 (毫秒)"
        PaymentValueRow(
            title = baseTitle,
            value = baseMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    PaymentTextEditMode(
                        title = baseTitle,
                        initial = baseMs,
                        keyboardType = KeyboardType.Number,
                        filter = digitFilter,
                        onCommit = onBaseChange,
                    )
                )
            },
        )
    }
    item(key = "delay_random", animatedVisibility = visible) {
        val randomTitle = "随机偏移范围 (±毫秒)"
        PaymentValueRow(
            title = randomTitle,
            value = randomRangeMs,
            enabled = editable,
            valueHint = "0",
            onClick = {
                onEditText(
                    PaymentTextEditMode(
                        title = randomTitle,
                        initial = randomRangeMs,
                        keyboardType = KeyboardType.Number,
                        filter = digitFilter,
                        onCommit = onRandomRangeChange,
                    )
                )
            },
        )
    }
}
