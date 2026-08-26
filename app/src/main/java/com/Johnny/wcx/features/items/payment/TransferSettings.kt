package com.Johnny.wcx.features.items.payment

import com.Johnny.wcx.R

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp


import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.items.AtomicJsonConfigStore
import com.Johnny.wcx.features.items.AutomationContactSettingsSelector
import com.Johnny.wcx.features.items.AutomationKeywordMode
import com.Johnny.wcx.features.items.AutomationKeywordRule
import com.Johnny.wcx.features.items.AutomationTimeRangeRule
import com.Johnny.wcx.features.items.AutomationToggleRule
import com.Johnny.wcx.features.items.automationKeywordSummary
import com.Johnny.wcx.features.items.formatAutomationMinute
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.io.path.div
import kotlin.random.Random

internal object TransferSettings {
    private const val TAG = "TransferSettings"
    private const val CONFIG_VERSION = 1
    private const val MAX_DELAY_DIGITS = 7

    @Serializable
    data class AmountRangeRule(
        val enabled: Boolean = false,
        val minimumYuan: String = "",
        val maximumYuan: String = ""
    ) {
        fun matches(totalFeeCents: Long): Boolean {
            if (!enabled) return true
            val minimum = minimumYuan.toCentsOrNull()
            val maximum = maximumYuan.toCentsOrNull()
            if (minimumYuan.isNotBlank() && minimum == null) return false
            if (maximumYuan.isNotBlank() && maximum == null) return false
            if (minimum != null && totalFeeCents < minimum) return false
            if (maximum != null && totalFeeCents > maximum) return false
            return true
        }
    }

    @Serializable
    data class DelayRule(
        val enabled: Boolean = true,
        val baseMs: String = "500",
        val randomRangeMs: String = "300"
    ) {
        fun millis(): Long {
            if (!enabled) return 0L
            val base = (baseMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            val range = (randomRangeMs.toLongOrNull() ?: 0L).coerceAtLeast(0L)
            if (range == 0L) return base
            val safeRange = range.coerceAtMost(Long.MAX_VALUE - 1)
            return (base + Random.nextLong(-safeRange, safeRange + 1)).coerceAtLeast(0L)
        }
    }

    @Serializable
    data class ReplyRule(
        val enabled: Boolean = false,
        val text: String = ""
    )

    @Serializable
    data class RuleSet(
        val accept: AutomationToggleRule = AutomationToggleRule(enabled = true),
        val timeRange: AutomationTimeRangeRule = AutomationTimeRangeRule(),
        val amountRange: AmountRangeRule = AmountRangeRule(),
        val memoKeyword: AutomationKeywordRule = AutomationKeywordRule(),
        val delay: DelayRule = DelayRule(),
        val notification: AutomationToggleRule = AutomationToggleRule(),
        val autoReply: ReplyRule = ReplyRule()
    ) {
        fun accepts(totalFeeCents: Long, payMemo: String): Boolean =
            accept.enabled &&
                    timeRange.matches() &&
                    amountRange.matches(totalFeeCents) &&
                    memoKeyword.matches(payMemo)
    }

    @Serializable
    data class RuleOverrides(
        val accept: AutomationToggleRule? = null,
        val timeRange: AutomationTimeRangeRule? = null,
        val amountRange: AmountRangeRule? = null,
        val memoKeyword: AutomationKeywordRule? = null,
        val delay: DelayRule? = null,
        val notification: AutomationToggleRule? = null,
        val autoReply: ReplyRule? = null
    ) {
        fun overriddenCount(): Int = listOf(
            accept,
            timeRange,
            amountRange,
            memoKeyword,
            delay,
            notification,
            autoReply
        ).count { it != null }

        fun isEmpty(): Boolean = overriddenCount() == 0
    }

    @Serializable
    private data class StoredConfig(
        val version: Int = CONFIG_VERSION,
        val global: RuleSet = RuleSet(),
        val contacts: Map<String, RuleOverrides> = emptyMap(),
        val groupMembers: Map<String, Map<String, RuleOverrides>> = emptyMap()
    )

    private enum class RuleKey {
        ACCEPT,
        TIME_RANGE,
        AMOUNT_RANGE,
        MEMO_KEYWORD,
        DELAY,
        NOTIFICATION,
        AUTO_REPLY
    }

    private val store by lazy {
        AtomicJsonConfigStore(
            file = KnownPaths.moduleData / "auto_accept_transfer_settings.json",
            serializer = StoredConfig.serializer(),
            tag = TAG,
            initialValue = ::migrateLegacyConfig
        )
    }

    fun resolve(talker: String, payer: String): RuleSet {
        val config = store.get()
        var rules = config.global.apply(config.contacts[talker])
        if (talker.isGroupChatWxId && payer.isNotBlank()) {
            rules = rules.apply(config.groupMembers[talker]?.get(payer))
        }
        return rules
    }

    fun showMainDialog(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("自动接收转账") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = "全局设置",
                                description = "配置默认接收条件与接收后的操作",
                                onClick = { showGlobalDialog(context) },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = "分联系人设置",
                                description = "为联系人、群聊或群成员覆盖全局设置",
                                onClick = { showContactSelector(context) },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } }
            )
        }
    }

    private fun showGlobalDialog(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(androidx.compose.ui.platform.LocalContext.current)
            var draft by remember { mutableStateOf(store.get().global) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val validationError = validate(localizedContext, draft)

            val editMode = editText
            if (editMode != null) {
                PaymentTextEditDialog(editMode, onClose = { editText = null })
                return@showComposeDialog
            }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("全局设置") },
                text = {
                    RuleSetEditor(
                        rules = draft,
                        overriddenKeys = null,
                        parentLabel = "",
                        validationError = validationError,
                        onActivate = {},
                        onReset = {},
                        onChange = { _, updated -> draft = updated },
                        onEditText = { editText = it },
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            store.update { it.copy(version = CONFIG_VERSION, global = draft) }
                            showToast("全局设置已保存")
                            onDismiss()
                        }
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    private fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(androidx.compose.ui.platform.LocalContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            AutomationContactSettingsSelector(
                title = "分联系人设置",
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact ->
                    val own = contactOverrides(contact.wxId).overriddenCount()
                    val members = memberOverridesCount(contact.wxId)
                    when {
                        contact.wxId.isGroupChatWxId && own + members > 0 ->
                            "群聊设置 - 已配置"
                        contact.wxId.isGroupChatWxId -> "群聊设置"
                        own > 0 -> localizedContext.resources.getQuantityString(
                            R.plurals.automation_overrides,
                            own,
                            own,
                        )
                        else -> "跟随全局设置"
                    }
                },
                isConfigured = { contact ->
                    contactOverrides(contact.wxId).overriddenCount() > 0 ||
                            memberOverridesCount(contact.wxId) > 0
                },
                onDismiss = onDismiss,
                onOpen = { contact ->
                    if (contact.wxId.isGroupChatWxId) {
                        showGroupSettingsDialog(context, contact.wxId) { revision++ }
                    } else {
                        showOverrideDialog(
                            context = context,
                            title = PaymentUiText.Raw(contact.displayName.ifBlank { contact.wxId }),
                            parentLabelRes = "全局设置",
                            parent = store.get().global,
                            initial = contactOverrides(contact.wxId),
                            onSave = {
                                setContactOverrides(contact.wxId, it)
                                revision++
                            }
                        )
                    }
                }
            )
        }
    }

    private fun showGroupSettingsDialog(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val groupOverrideCount = remember(revision) { contactOverrides(groupId).overriddenCount() }
            val memberCount = remember(revision) { memberOverridesCount(groupId) }
            AlertDialogContent(
                title = { Text(groupName) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            PaymentNavigationRow(
                                title = "群聊全局设置",
                                description = if (groupOverrideCount == 0) {
                                    "跟随全局设置"
                                } else {
                                    pluralStringResource(
                                        R.plurals.automation_overrides,
                                        groupOverrideCount,
                                        groupOverrideCount,
                                    )
                                },
                                onClick = {
                                    showOverrideDialog(
                                        context = context,
                                        title = PaymentUiText.Resource("群聊全局设置"),
                                        parentLabelRes = "全局设置",
                                        parent = store.get().global,
                                        initial = contactOverrides(groupId),
                                        onSave = {
                                            setContactOverrides(groupId, it)
                                            revision++
                                            onUpdated()
                                        }
                                    )
                                },
                            )
                        }
                        item {
                            PaymentNavigationRow(
                                title = "群聊分群成员设置",
                                description = if (memberCount == 0) {
                                    "所有成员跟随群聊全局设置"
                                } else {
                                    pluralStringResource(
                                        R.plurals.automation_configured_members,
                                        memberCount,
                                        memberCount,
                                    )
                                },
                                onClick = {
                                    showGroupMemberSelector(context, groupId) {
                                        revision++
                                        onUpdated()
                                    }
                                },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } }
            )
        }
    }

    private fun showGroupMemberSelector(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(androidx.compose.ui.platform.LocalContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val members = remember(groupId) {
                runCatching { WeDatabaseApi.getGroupMembers(groupId) }
                    .onFailure { WeLogger.e(TAG, "failed to load members of $groupId", it) }
                    .getOrDefault(emptyList())
            }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            AutomationContactSettingsSelector(
                title = "".format(groupName),
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = groupMemberOverrides(groupId, member.wxId).overriddenCount()
                    if (count == 0) {
                        "跟随群聊全局设置"
                    } else {
                        localizedContext.resources.getQuantityString(
                            R.plurals.automation_overrides,
                            count,
                            count,
                        )
                    }
                },
                isConfigured = { member ->
                    groupMemberOverrides(groupId, member.wxId).overriddenCount() > 0
                },
                onDismiss = onDismiss,
                onOpen = { member ->
                    showOverrideDialog(
                        context = context,
                        title = PaymentUiText.Raw(member.displayName.ifBlank { member.wxId }),
                        parentLabelRes = "群聊全局设置",
                        parent = store.get().global.apply(contactOverrides(groupId)),
                        initial = groupMemberOverrides(groupId, member.wxId),
                        onSave = {
                            setGroupMemberOverrides(groupId, member.wxId, it)
                            revision++
                            onUpdated()
                        }
                    )
                }
            )
        }
    }

    private fun showOverrideDialog(
        context: Context,
        title: PaymentUiText,
        parentLabelRes: String,
        parent: RuleSet,
        initial: RuleOverrides,
        onSave: (RuleOverrides) -> Unit
    ) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(androidx.compose.ui.platform.LocalContext.current)
            var draft by remember { mutableStateOf(initial) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val effective = parent.apply(draft)
            val validationError = validate(localizedContext, effective, draft.keys())

            val editMode = editText
            if (editMode != null) {
                PaymentTextEditDialog(editMode, onClose = { editText = null })
                return@showComposeDialog
            }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text(title.resolve()) },
                text = {
                    RuleSetEditor(
                        rules = effective,
                        overriddenKeys = draft.keys(),
                        parentLabel = parentLabelRes,
                        validationError = validationError,
                        onActivate = { draft = draft.withRule(it, effective) },
                        onReset = { draft = draft.withoutRule(it) },
                        onChange = { key, updated -> draft = draft.withRule(key, updated) },
                        onEditText = { editText = it },
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast("设置已保存")
                            onDismiss()
                        }
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    @Composable
    private fun RuleSetEditor(
        rules: RuleSet,
        overriddenKeys: Set<RuleKey>?,
        parentLabel: String,
        validationError: String?,
        onActivate: (RuleKey) -> Unit,
        onReset: (RuleKey) -> Unit,
        onChange: (RuleKey, RuleSet) -> Unit,
        onEditText: (PaymentTextEditMode) -> Unit
    ) {
        val isGlobal = overriddenKeys == null
        fun overridden(key: RuleKey): Boolean? = overriddenKeys?.let { key in it }
        fun editable(key: RuleKey): Boolean = overriddenKeys == null || key in overriddenKeys

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                item(key = "accept") {
                    PaymentRuleRow(
                        title = "默认接收转账",
                        summary = when {
                            isGlobal && rules.accept.enabled -> "默认接收所有联系人的转账, 分联系人设置可单独关闭"
                            isGlobal -> "默认不接收任何联系人的转账, 分联系人设置可单独开启"
                            rules.accept.enabled -> "在当前范围内接收转账"
                            else -> "在当前范围内跳过转账"
                        },
                        checked = rules.accept.enabled,
                        overridden = overridden(RuleKey.ACCEPT),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.ACCEPT) },
                        onReset = { onReset(RuleKey.ACCEPT) },
                        onCheckedChange = {
                            onChange(RuleKey.ACCEPT, rules.copy(accept = rules.accept.copy(enabled = it)))
                        },
                    )
                }

                item(key = "time_range") {
                    PaymentRuleRow(
                        title = "时间段接收转账",
                        summary = if (rules.timeRange.enabled) {
                            "${formatAutomationMinute(rules.timeRange.startMinute)} - ${formatAutomationMinute(rules.timeRange.endMinute)}"
                        } else "不限制接收时间",
                        checked = rules.timeRange.enabled,
                        overridden = overridden(RuleKey.TIME_RANGE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.TIME_RANGE) },
                        onReset = { onReset(RuleKey.TIME_RANGE) },
                        onCheckedChange = {
                            onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = rules.timeRange.copy(enabled = it)))
                        },
                    )
                }
                timeRangeItems(
                    rule = rules.timeRange,
                    editable = editable(RuleKey.TIME_RANGE),
                    visible = rules.timeRange.enabled,
                    onChange = { onChange(RuleKey.TIME_RANGE, rules.copy(timeRange = it)) },
                )

                item(key = "amount_range") {
                    PaymentRuleRow(
                        title = "转账金额范围",
                        summary = amountSummary(rules.amountRange),
                        checked = rules.amountRange.enabled,
                        overridden = overridden(RuleKey.AMOUNT_RANGE),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.AMOUNT_RANGE) },
                        onReset = { onReset(RuleKey.AMOUNT_RANGE) },
                        onCheckedChange = {
                            onChange(RuleKey.AMOUNT_RANGE, rules.copy(amountRange = rules.amountRange.copy(enabled = it)))
                        },
                    )
                }
                item(key = "amount_minimum", animatedVisibility = rules.amountRange.enabled) {
                    val minimumTitle = "最低金额 (元, 可留空)"
                    PaymentValueRow(
                        title = minimumTitle,
                        value = rules.amountRange.minimumYuan,
                        enabled = editable(RuleKey.AMOUNT_RANGE),
                        valueHint = "不限",
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = minimumTitle,
                                    initial = rules.amountRange.minimumYuan,
                                    keyboardType = KeyboardType.Decimal,
                                    filter = ::sanitizeAmount,
                                    onCommit = {
                                        onChange(
                                            RuleKey.AMOUNT_RANGE,
                                            rules.copy(amountRange = rules.amountRange.copy(minimumYuan = it))
                                        )
                                    },
                                )
                            )
                        },
                    )
                }
                item(key = "amount_maximum", animatedVisibility = rules.amountRange.enabled) {
                    val maximumTitle = "最高金额 (元, 可留空)"
                    PaymentValueRow(
                        title = maximumTitle,
                        value = rules.amountRange.maximumYuan,
                        enabled = editable(RuleKey.AMOUNT_RANGE),
                        valueHint = "不限",
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = maximumTitle,
                                    initial = rules.amountRange.maximumYuan,
                                    keyboardType = KeyboardType.Decimal,
                                    filter = ::sanitizeAmount,
                                    onCommit = {
                                        onChange(
                                            RuleKey.AMOUNT_RANGE,
                                            rules.copy(amountRange = rules.amountRange.copy(maximumYuan = it))
                                        )
                                    },
                                )
                            )
                        },
                    )
                }

                item(key = "memo_keyword") {
                    PaymentRuleRow(
                        title = "转账备注关键词",
                        summary = automationKeywordSummary(
                            rules.memoKeyword,
                            "不限制转账备注",
                        ),
                        checked = rules.memoKeyword.enabled,
                        overridden = overridden(RuleKey.MEMO_KEYWORD),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.MEMO_KEYWORD) },
                        onReset = { onReset(RuleKey.MEMO_KEYWORD) },
                        onCheckedChange = {
                            onChange(
                                RuleKey.MEMO_KEYWORD,
                                rules.copy(memoKeyword = rules.memoKeyword.copy(enabled = it))
                            )
                        },
                    )
                }
                keywordItems(
                    keyPrefix = "memo_keyword",
                    rule = rules.memoKeyword,
                    editable = editable(RuleKey.MEMO_KEYWORD),
                    visible = rules.memoKeyword.enabled,
                    modes = AutomationKeywordMode.entries,
                    onChange = { onChange(RuleKey.MEMO_KEYWORD, rules.copy(memoKeyword = it)) },
                    onEditText = onEditText,
                )

                item(key = "delay") {
                    PaymentRuleRow(
                        title = "接收延迟",
                        summary = if (rules.delay.enabled) {
                            "基础 %1\$s ms, 随机偏移 ±%2\$s ms".format(
                                rules.delay.baseMs.ifBlank { "0" },
                                rules.delay.randomRangeMs.ifBlank { "0" },
                            )
                        } else "立即接收转账",
                        checked = rules.delay.enabled,
                        overridden = overridden(RuleKey.DELAY),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.DELAY) },
                        onReset = { onReset(RuleKey.DELAY) },
                        onCheckedChange = {
                            onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(enabled = it)))
                        },
                    )
                }
                delayItems(
                    baseMs = rules.delay.baseMs,
                    randomRangeMs = rules.delay.randomRangeMs,
                    editable = editable(RuleKey.DELAY),
                    visible = rules.delay.enabled,
                    maxDigits = MAX_DELAY_DIGITS,
                    onBaseChange = {
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(baseMs = it)))
                    },
                    onRandomRangeChange = {
                        onChange(RuleKey.DELAY, rules.copy(delay = rules.delay.copy(randomRangeMs = it)))
                    },
                    onEditText = onEditText,
                )

                item(key = "notification") {
                    PaymentRuleRow(
                        title = "接收后通知",
                        summary = if (rules.notification.enabled) {
                            "显示收到的金额与来源"
                        } else {
                            "不显示通知"
                        },
                        checked = rules.notification.enabled,
                        overridden = overridden(RuleKey.NOTIFICATION),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.NOTIFICATION) },
                        onReset = { onReset(RuleKey.NOTIFICATION) },
                        onCheckedChange = {
                            onChange(
                                RuleKey.NOTIFICATION,
                                rules.copy(notification = rules.notification.copy(enabled = it))
                            )
                        },
                    )
                }

                item(key = "auto_reply") {
                    PaymentRuleRow(
                        title = "接收后自动回复",
                        summary = if (rules.autoReply.enabled) {
                            "成功后向来源会话发送消息"
                        } else {
                            "不自动回复"
                        },
                        checked = rules.autoReply.enabled,
                        overridden = overridden(RuleKey.AUTO_REPLY),
                        parentLabel = parentLabel,
                        onActivate = { onActivate(RuleKey.AUTO_REPLY) },
                        onReset = { onReset(RuleKey.AUTO_REPLY) },
                        onCheckedChange = {
                            onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(enabled = it)))
                        },
                    )
                }
                item(key = "auto_reply_text", animatedVisibility = rules.autoReply.enabled) {
                    val replyTitle = "回复内容"
                    val amountPlaceholder = "使用 \$amount 表示转账金额"
                    PaymentValueRow(
                        title = replyTitle,
                        value = rules.autoReply.text,
                        enabled = editable(RuleKey.AUTO_REPLY),
                        valueHint = amountPlaceholder,
                        onClick = {
                            onEditText(
                                PaymentTextEditMode(
                                    title = replyTitle,
                                    initial = rules.autoReply.text,
                                    supportingText = amountPlaceholder,
                                    onCommit = {
                                        onChange(RuleKey.AUTO_REPLY, rules.copy(autoReply = rules.autoReply.copy(text = it)))
                                    },
                                )
                            )
                        },
                    )
                }

                if (validationError != null) {
                    item(key = "validation_error") { PaymentErrorRow(validationError) }
                }
            }
        }
    }

    private fun RuleSet.apply(overrides: RuleOverrides?): RuleSet {
        if (overrides == null) return this
        return copy(
            accept = overrides.accept ?: accept,
            timeRange = overrides.timeRange ?: timeRange,
            amountRange = overrides.amountRange ?: amountRange,
            memoKeyword = overrides.memoKeyword ?: memoKeyword,
            delay = overrides.delay ?: delay,
            notification = overrides.notification ?: notification,
            autoReply = overrides.autoReply ?: autoReply
        )
    }

    private fun RuleOverrides.keys(): Set<RuleKey> = buildSet {
        if (accept != null) add(RuleKey.ACCEPT)
        if (timeRange != null) add(RuleKey.TIME_RANGE)
        if (amountRange != null) add(RuleKey.AMOUNT_RANGE)
        if (memoKeyword != null) add(RuleKey.MEMO_KEYWORD)
        if (delay != null) add(RuleKey.DELAY)
        if (notification != null) add(RuleKey.NOTIFICATION)
        if (autoReply != null) add(RuleKey.AUTO_REPLY)
    }

    private fun RuleOverrides.withRule(key: RuleKey, rules: RuleSet): RuleOverrides = when (key) {
        RuleKey.ACCEPT -> copy(accept = rules.accept)
        RuleKey.TIME_RANGE -> copy(timeRange = rules.timeRange)
        RuleKey.AMOUNT_RANGE -> copy(amountRange = rules.amountRange)
        RuleKey.MEMO_KEYWORD -> copy(memoKeyword = rules.memoKeyword)
        RuleKey.DELAY -> copy(delay = rules.delay)
        RuleKey.NOTIFICATION -> copy(notification = rules.notification)
        RuleKey.AUTO_REPLY -> copy(autoReply = rules.autoReply)
    }

    private fun RuleOverrides.withoutRule(key: RuleKey): RuleOverrides = when (key) {
        RuleKey.ACCEPT -> copy(accept = null)
        RuleKey.TIME_RANGE -> copy(timeRange = null)
        RuleKey.AMOUNT_RANGE -> copy(amountRange = null)
        RuleKey.MEMO_KEYWORD -> copy(memoKeyword = null)
        RuleKey.DELAY -> copy(delay = null)
        RuleKey.NOTIFICATION -> copy(notification = null)
        RuleKey.AUTO_REPLY -> copy(autoReply = null)
    }

    private fun validate(context: Context, rules: RuleSet, keys: Set<RuleKey>? = null): String? {
        fun validates(key: RuleKey) = keys == null || key in keys
        if (validates(RuleKey.AMOUNT_RANGE) && rules.amountRange.enabled) {
            val minimumText = rules.amountRange.minimumYuan
            val maximumText = rules.amountRange.maximumYuan
            if (minimumText.isBlank() && maximumText.isBlank()) {
                return "请至少填写一个金额边界"
            }
            val minimum = minimumText.toCentsOrNull()
            val maximum = maximumText.toCentsOrNull()
            if (minimumText.isNotBlank() && minimum == null) {
                return "请输入有效的最低金额"
            }
            if (maximumText.isNotBlank() && maximum == null) {
                return "请输入有效的最高金额"
            }
            if (minimum != null && maximum != null && minimum > maximum) {
                return "最低金额不能高于最高金额"
            }
        }
        if (validates(RuleKey.MEMO_KEYWORD)) {
            rules.memoKeyword.validationError("转账备注关键词")?.let { return it }
        }
        if (validates(RuleKey.DELAY) && rules.delay.enabled) {
            if (rules.delay.baseMs.toLongOrNull() == null) {
                return "请输入有效的基础延迟"
            }
            if (rules.delay.randomRangeMs.toLongOrNull() == null) {
                return "请输入有效的随机偏移范围"
            }
        }
        if (validates(RuleKey.AUTO_REPLY) && rules.autoReply.enabled && rules.autoReply.text.isBlank()) {
            return "自动回复内容不能为空"
        }
        return null
    }

    @Composable
    private fun amountSummary(rule: AmountRangeRule): String {
        if (!rule.enabled) return "不限制转账金额"
        val unrestricted = "不限"
        val minimum = rule.minimumYuan.ifBlank { unrestricted }
        val maximum = rule.maximumYuan.ifBlank { unrestricted }
        return "%1\$s - ".format(minimum, maximum)
    }

    private fun sanitizeAmount(value: String): String {
        val filtered = value.filter { it.isDigit() || it == '.' }.take(14)
        val dot = filtered.indexOf('.')
        if (dot < 0) return filtered
        return filtered.take(dot + 1) + filtered.drop(dot + 1).filter(Char::isDigit).take(2)
    }

    private fun String.toCentsOrNull(): Long? {
        if (isBlank()) return null
        return runCatching {
            BigDecimal(this)
                .movePointRight(2)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
                .takeIf { it >= 0L }
        }.getOrNull()
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()).distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(TAG, "failed to load contacts", it)
    }.getOrDefault(emptyList())

    private fun contactOverrides(wxId: String): RuleOverrides =
        store.get().contacts[wxId] ?: RuleOverrides()

    private fun groupMemberOverrides(groupId: String, memberId: String): RuleOverrides =
        store.get().groupMembers[groupId]?.get(memberId) ?: RuleOverrides()

    private fun memberOverridesCount(groupId: String): Int =
        store.get().groupMembers[groupId]?.count { !it.value.isEmpty() } ?: 0

    private fun setContactOverrides(wxId: String, overrides: RuleOverrides) {
        store.update { config ->
            val contacts = config.contacts.toMutableMap()
            if (overrides.isEmpty()) contacts.remove(wxId) else contacts[wxId] = overrides
            config.copy(version = CONFIG_VERSION, contacts = contacts)
        }
    }

    private fun setGroupMemberOverrides(groupId: String, memberId: String, overrides: RuleOverrides) {
        store.update { config ->
            val groups = config.groupMembers.toMutableMap()
            val members = groups[groupId].orEmpty().toMutableMap()
            if (overrides.isEmpty()) members.remove(memberId) else members[memberId] = overrides
            if (members.isEmpty()) groups.remove(groupId) else groups[groupId] = members
            config.copy(version = CONFIG_VERSION, groupMembers = groups)
        }
    }

    private fun migrateLegacyConfig(): StoredConfig {
        val hasLegacyPrefs = LEGACY_PREF_KEYS.any(WePrefs::containsKey)
        if (!hasLegacyPrefs) return StoredConfig()

        val useWhitelist = WePrefs.getBoolOrDef("transfer_use_whitelist", false)
        val selected = if (useWhitelist) {
            WePrefs.getStringSetOrDef("transfer_whitelist", emptySet())
        } else {
            WePrefs.getStringSetOrDef("transfer_blacklist", emptySet())
        }
        val delayBase = WePrefs.getStringOrDef("transfer_delay_custom", "500")
        val delayRange = WePrefs.getStringOrDef("transfer_delay_random_range", "300")
        val global = RuleSet(
            accept = AutomationToggleRule(enabled = !useWhitelist),
            delay = DelayRule(enabled = true, baseMs = delayBase, randomRangeMs = delayRange),
            notification = AutomationToggleRule(WePrefs.getBoolOrDef("transfer_notification", false)),
            autoReply = WePrefs.getStringOrDef("transfer_auto_reply", "").let {
                ReplyRule(enabled = it.isNotBlank(), text = it)
            }
        )
        val contacts = selected.associateWith {
            RuleOverrides(accept = AutomationToggleRule(enabled = useWhitelist))
        }
        WeLogger.i(TAG, "migrated legacy transfer settings")
        return StoredConfig(global = global, contacts = contacts)
    }

    private val LEGACY_PREF_KEYS = listOf(
        "transfer_notification",
        "transfer_use_whitelist",
        "transfer_whitelist",
        "transfer_blacklist",
        "transfer_delay_custom",
        "transfer_delay_random_range",
        "transfer_auto_reply"
    )
}
