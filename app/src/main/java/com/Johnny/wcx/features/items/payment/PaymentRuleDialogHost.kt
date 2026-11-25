package com.Johnny.wcx.features.items.payment

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import com.Johnny.wcx.R
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.items.AutomationContactSettingsSelector
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.safeGetQuantityString
import com.Johnny.wcx.utils.strings.isGroupChatWxId

/** 传给 [PaymentRuleDialogHost] 的编辑器参数包，避免八个位置参数排成一串。 */
internal class RuleEditorArgs<S, K>(
    val rules: S,
    val overriddenKeys: Set<K>,
    val parentLabel: String,
    val validationError: String?,
    val onActivate: (K) -> Unit,
    val onReset: (K) -> Unit,
    val onChange: (K, S) -> Unit,
    val onEditText: (PaymentTextEditMode) -> Unit,
)

/**
 * 「规则覆盖」设置页的对话框骨架，转账与红包共用。
 *
 * 两个功能各自持有一份 `RuleSet` / `RuleOverrides` / `RuleKey`，字段不同
 * （转账是金额区间与备注关键字，红包是延迟、时段与关键字），所以类型不做合并；
 * 这里只共享与规则内容无关的外壳：
 *
 * 1. 分联系人设置（含群聊入口）
 * 2. 群聊分层设置（群聊全局 / 群成员）
 * 3. 按人覆盖编辑（草稿态、校验、保存）
 *
 * 这几段原先在两个文件里各写了一遍、近乎逐字相同 —— 改动只落在一边时两套设置页就会分叉。
 * 各功能只需在构造时传入数据访问、规则运算、副标题文案与编辑器本体。
 */
internal class PaymentRuleDialogHost<S, O, K>(
    private val logTag: String,
    private val globalRules: () -> S,
    private val contactOverrides: (String) -> O,
    private val groupMemberOverrides: (String, String) -> O,
    private val setContactOverrides: (String, O) -> Unit,
    private val setGroupMemberOverrides: (String, String, O) -> Unit,
    private val memberOverridesCount: (String) -> Int,
    private val overriddenCount: (O) -> Int,
    private val applyRules: (S, O?) -> S,
    private val keysOf: (O) -> Set<K>,
    private val withRule: (O, K, S) -> O,
    private val withoutRule: (O, K) -> O,
    private val validateRules: (Context, S, Set<K>?) -> String?,
    /** 分联系人列表的副标题：两套规则对「群聊已配置」的说法不同，由调用方决定。 */
    private val contactSubtitle: (Context, IWeContact) -> String,
    /** 规则编辑器本体（字段不同，留在各功能里）。 */
    private val ruleEditor: @Composable (RuleEditorArgs<S, K>) -> Unit,
) {

    /** 分联系人设置。群聊会先进「群聊分层设置」，普通联系人直接编辑覆盖。 */
    fun showContactSelector(context: Context) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val contacts = remember { loadContacts() }
            AutomationContactSettingsSelector(
                title = "分联系人设置",
                contacts = contacts,
                selectionKey = revision,
                subtitle = { contact -> contactSubtitle(localizedContext, contact) },
                isConfigured = { contact ->
                    overriddenCount(contactOverrides(contact.wxId)) > 0 ||
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
                            parentLabel = "全局设置",
                            parent = globalRules(),
                            initial = contactOverrides(contact.wxId),
                            onSave = {
                                setContactOverrides(contact.wxId, it)
                                revision++
                            },
                        )
                    }
                },
            )
        }
    }

    /** 群聊：群聊全局覆盖 + 分群成员覆盖两个入口。 */
    fun showGroupSettingsDialog(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            var revision by remember { mutableIntStateOf(0) }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            val groupOverrideCount = remember(revision) { overriddenCount(contactOverrides(groupId)) }
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
                                        parentLabel = "全局设置",
                                        parent = globalRules(),
                                        initial = contactOverrides(groupId),
                                        onSave = {
                                            setContactOverrides(groupId, it)
                                            revision++
                                            onUpdated()
                                        },
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
                dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            )
        }
    }

    private fun showGroupMemberSelector(context: Context, groupId: String, onUpdated: () -> Unit) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalContext.current)
            var revision by remember { mutableIntStateOf(0) }
            val members = remember(groupId) {
                runCatching { WeDatabaseApi.getGroupMembers(groupId) }
                    .onFailure { WeLogger.e(logTag, "failed to load members of $groupId", it) }
                    .getOrDefault(emptyList())
            }
            val groupName = remember(groupId) { WeDatabaseApi.getDisplayName(groupId) }
            AutomationContactSettingsSelector(
                title = groupName,
                contacts = members,
                selectionKey = revision,
                subtitle = { member ->
                    val count = overriddenCount(groupMemberOverrides(groupId, member.wxId))
                    if (count == 0) {
                        "跟随群聊全局设置"
                    } else {
                        localizedContext.resources.safeGetQuantityString(
                            R.plurals.automation_overrides,
                            count,
                            count,
                        )
                    }
                },
                isConfigured = { member ->
                    overriddenCount(groupMemberOverrides(groupId, member.wxId)) > 0
                },
                onDismiss = onDismiss,
                onOpen = { member ->
                    showOverrideDialog(
                        context = context,
                        title = PaymentUiText.Raw(member.displayName.ifBlank { member.wxId }),
                        parentLabel = "群聊全局设置",
                        parent = applyRules(globalRules(), contactOverrides(groupId)),
                        initial = groupMemberOverrides(groupId, member.wxId),
                        onSave = {
                            setGroupMemberOverrides(groupId, member.wxId, it)
                            revision++
                            onUpdated()
                        },
                    )
                },
            )
        }
    }

    /** 按人覆盖编辑：草稿 → 与父级规则合并 → 校验 → 保存。 */
    private fun showOverrideDialog(
        context: Context,
        title: PaymentUiText,
        parentLabel: String,
        parent: S,
        initial: O,
        onSave: (O) -> Unit,
    ) {
        showComposeDialog(context) {
            val localizedContext by rememberUpdatedState(LocalContext.current)
            var draft by remember { mutableStateOf(initial) }
            var editText by remember { mutableStateOf<PaymentTextEditMode?>(null) }
            val effective = applyRules(parent, draft)
            val validationError = validateRules(localizedContext, effective, keysOf(draft))

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
                    ruleEditor(
                        RuleEditorArgs(
                            rules = effective,
                            overriddenKeys = keysOf(draft),
                            parentLabel = parentLabel,
                            validationError = validationError,
                            onActivate = { key -> draft = withRule(draft, key, effective) },
                            onReset = { key -> draft = withoutRule(draft, key) },
                            onChange = { key, updated -> draft = withRule(draft, key, updated) },
                            onEditText = { editText = it },
                        ),
                    )
                },
                confirmButton = {
                    Button(
                        enabled = validationError == null,
                        onClick = {
                            onSave(draft)
                            showToast("设置已保存")
                            onDismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    private fun loadContacts(): List<IWeContact> = runCatching {
        (WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()).distinctBy(IWeContact::wxId)
    }.onFailure {
        WeLogger.e(logTag, "failed to load contacts", it)
    }.getOrDefault(emptyList())
}
