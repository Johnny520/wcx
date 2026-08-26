package com.Johnny.wcx.features.items.payment

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.serialization.DefaultJson
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.Serializable
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Per-group member allow/deny list for [AutoOpenRedPackets]. After a red packet clears the
 * conversation-level (talker) whitelist/blacklist, group packets are additionally filtered by
 * their *sender* (the group member wxid): each group independently either whitelists (grab only
 * from listed members) or blacklists (grab from everyone but the listed members). Persisted as a
 * nested groupId -> members list in red_packet_group_members.json.
 */
object RedPacketGroupMemberFilter {
    private const val TAG = "RedPacketGroupMemberFilter"

    private val configFile by lazy { KnownPaths.moduleData / "red_packet_group_members.json" }

    private var cache: List<GroupMemberRule>? = null

    @Serializable
    data class GroupMemberRule(
        val groupId: String = "",
        val useWhitelist: Boolean = false,
        val members: List<String> = emptyList()
    )

    private fun loadRules(): List<GroupMemberRule> {
        cache?.let { return it }
        val rules = runCatching {
            val file = configFile
            if (!file.exists()) return emptyList()
            DefaultJson.decodeFromString(ListSerializer(GroupMemberRule.serializer()), file.readText())
                .map { it.copy(members = it.members.filter { m -> m.isNotBlank() }) }
                .filter { it.groupId.isGroupChatWxId }
        }.onFailure {
            WeLogger.w(TAG, "failed to decode group member rules from $configFile", it)
        }.getOrDefault(emptyList())
        cache = rules
        return rules
    }

    private fun saveRules(rules: List<GroupMemberRule>) {
        cache = rules
        runCatching {
            configFile.writeText(DefaultJson.encodeToString(rules))
        }.onFailure {
            WeLogger.w(TAG, "failed to save group member rules to $configFile", it)
        }
    }

    private fun ruleFor(groupId: String): GroupMemberRule? =
        loadRules().firstOrNull { it.groupId == groupId }

    /**
     * Whether a red packet from [sender] in group [groupId] should be grabbed. Groups without a
     * rule (or with an empty rule) impose no restriction and always return true. A whitelist rule
     * grabs only from listed members; a blacklist rule grabs from everyone except listed members.
     */
    fun shouldGrab(groupId: String, sender: String): Boolean {
        val rule = ruleFor(groupId) ?: return true
        if (rule.members.isEmpty()) return true
        return if (rule.useWhitelist) sender in rule.members else sender !in rule.members
    }

    fun showManagerDialog(context: Context) {
        showComposeDialog(context) {
            var rules by remember { mutableStateOf(loadRules()) }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("群聊指定群成员") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("对指定群聊的红包按发送成员过滤。未配置的群聊不受影响。")
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (rules.isEmpty()) {
                                item { Text("暂无配置, 点击「添加群聊」来创建一条规则") }
                            }
                            items(rules, key = { it.groupId }) { rule ->
                                GroupRuleRow(rule) {
                                    showEditRuleDialog(
                                        context = context,
                                        rule = rule,
                                        onUpdated = { rules = loadRules() },
                                        onDeleted = { rules = loadRules() }
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                    TextButton(onClick = {
                        showAddGroupDialog(context) { rules = loadRules() }
                    }) { Text("添加群聊") }
                }
            )
        }
    }

    private fun showAddGroupDialog(context: Context, onAdded: () -> Unit) {
        showComposeDialog(context) {
            SingleContactSelector(
                title = "选择群聊",
                contacts = remember { WeDatabaseApi.getGroups() },
                initialSelectedWxId = null,
                onDismiss = onDismiss,
                onConfirm = { groupId ->
                    onDismiss()
                    val existing = ruleFor(groupId)
                        ?: GroupMemberRule(groupId = groupId)
                    if (loadRules().none { it.groupId == groupId }) {
                        saveRules(loadRules() + existing)
                        onAdded()
                    }
                    showEditRuleDialog(
                        context = context,
                        rule = existing,
                        onUpdated = onAdded,
                        onDeleted = onAdded
                    )
                }
            )
        }
    }

    private fun showEditRuleDialog(
        context: Context,
        rule: GroupMemberRule,
        onUpdated: () -> Unit,
        onDeleted: () -> Unit
    ) {
        showComposeDialog(context) {
            var useWhitelist by remember { mutableStateOf(rule.useWhitelist) }
            var members by remember { mutableStateOf(rule.members.toSet()) }
            var selectingMembers by remember { mutableStateOf(false) }

            if (selectingMembers) {
                ContactsSelector(
                    title = if (useWhitelist) "选择白名单成员" else "选择黑名单成员",
                    contacts = remember { WeDatabaseApi.getGroupMembers(rule.groupId) },
                    initialSelectedWxIds = members,
                    onDismiss = { selectingMembers = false },
                    onConfirm = {
                        members = it
                        selectingMembers = false
                    }
                )
                return@showComposeDialog
            }

            val groupName = remember(rule.groupId) { WeDatabaseApi.getDisplayName(rule.groupId) }

            AlertDialogContent(
                title = { Text(groupName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelist = !useWhitelist },
                            trailingContent = { Switch(checked = useWhitelist, onCheckedChange = { useWhitelist = it }) },
                            supportingContent = { Text(if (useWhitelist) "仅抢选中成员的红包" else "对选中成员跳过抢红包") },
                            headlineContent = { Text(if (useWhitelist) "黑名单 [> 白名单 <]" else "[> 黑名单 <] 白名单") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { selectingMembers = true },
                            supportingContent = { Text("已选择 ${members.size} 个成员, 点击选择") },
                            headlineContent = { Text(if (useWhitelist) "配置白名单成员" else "配置黑名单成员") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        saveRules(loadRules().filterNot { it.groupId == rule.groupId })
                        onDeleted()
                        onDismiss()
                    }) { Text("删除") }
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val next = rule.copy(useWhitelist = useWhitelist, members = members.toList().sorted())
                        val current = loadRules()
                        val updated = if (current.any { it.groupId == next.groupId }) {
                            current.map { if (it.groupId == next.groupId) next else it }
                        } else {
                            current + next
                        }
                        saveRules(updated)
                        onUpdated()
                        showToast("已保存, 重启微信生效")
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    @Composable
    private fun GroupRuleRow(rule: GroupMemberRule, onClick: () -> Unit) {
        val name = remember(rule.groupId) { WeDatabaseApi.getDisplayName(rule.groupId) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Text(name)
            Text(
                if (rule.useWhitelist) "白名单: ${rule.members.size} 个成员"
                else "黑名单: ${rule.members.size} 个成员"
            )
        }
    }
}
