package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import de.robv.android.xposed.XC_MethodHook
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SuppressLint("SetTextI18n")
@Feature(
    name = "消息类型过滤屏蔽",
    categories = ["聊天"],
    description = "按消息类型过滤屏蔽，支持私聊/群聊/公众号独立规则，模板管理，黑/白名单，拦截日志"
)
object MessageFilterShield : ClickableFeature(),
    WeDatabaseListenerApi.IInsertListener,
    WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "MessageFilterShield"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 持久化偏好 ====================
    private var masterEnabled by prefOption("mfs_master_enabled", false)

    private var privateRulesJson by prefOption("mfs_private_rules", "{}")
    private var groupRulesJson by prefOption("mfs_group_rules", "{}")
    private var oaRulesJson by prefOption("mfs_oa_rules", "{}")

    private var strategy by prefOption("mfs_strategy", 0) // 0 = hide, 1 = discard
    private var listMode by prefOption("mfs_list_mode", 0) // 0 = blacklist, 1 = whitelist
    private var targetListJson by prefOption("mfs_target_list", "[]")
    private var enableLog by prefOption("mfs_enable_log", false)

    // 拦截日志（仅内存，不持久化，重启后清空）
    private val shieldLog = mutableListOf<ShieldLogEntry>()

    // 需要被丢弃的消息 msgSvrId 集合（用于 IQueryListener 过滤）
    private val discardMsgIds = mutableSetOf<Long>()

    @Serializable
    data class ShieldRuleSet(
        val enabled: Boolean = false,
        val blockedTypes: Set<Int> = emptySet() // MessageType code
    )

    @Serializable
    data class ShieldLogEntry(
        val time: Long = System.currentTimeMillis(),
        val talker: String = "",
        val sender: String = "",
        val typeName: String = "",
        val strategy: String = ""
    )

    enum class FilterStrategy(val value: Int, val description: String) {
        HIDE(0, "仅隐藏消息（UI 层面不显示）"),
        DISCARD(1, "直接丢弃消息（不写入数据库）")
    }

    enum class ListType(val value: Int, val description: String) {
        BLACKLIST(0, "黑名单模式"),
        WHITELIST(1, "白名单模式")
    }

    // 可屏蔽的消息类型
    private val shieldableMessageTypes = listOf(
        MessageType.RED_PACKET to "红包",
        MessageType.SPECIAL_RED_PACKET to "裂变红包",
        MessageType.TRANSFER to "转账",
        MessageType.STICKER to "表情",
        MessageType.SO_GOU_EMOJI to "搜狗表情",
        MessageType.VIDEO_ACCOUNT to "视频号",
        MessageType.VIDEO_ACCOUNT_CARD to "视频号名片",
        MessageType.VIDEO_ACCOUNT_LIVE to "视频号直播",
        MessageType.ACCOUNT_VIDEO to "视频号视频",
        MessageType.LINK to "链接",
        MessageType.MUSIC to "音乐链接",
        MessageType.PRODUCT to "商品链接",
        MessageType.GROUP_NOTE to "群笔记",
        MessageType.PAT to "拍一拍",
        MessageType.RED_PACKET_COVER to "红包封面",
        MessageType.CARD to "名片",
        MessageType.FILE to "文件",
        MessageType.IMAGE to "图片",
        MessageType.VIDEO to "视频",
        MessageType.VOICE to "语音",
        MessageType.LOCATION to "位置",
        MessageType.SYSTEM_LOCATION to "系统位置",
        MessageType.MICRO_VIDEO to "小视频"
    )

    private fun getPrivateRules(): ShieldRuleSet {
        return runCatching { json.decodeFromString<ShieldRuleSet>(privateRulesJson) }.getOrDefault(ShieldRuleSet())
    }

    private fun getGroupRules(): ShieldRuleSet {
        return runCatching { json.decodeFromString<ShieldRuleSet>(groupRulesJson) }.getOrDefault(ShieldRuleSet())
    }

    private fun getOaRules(): ShieldRuleSet {
        return runCatching { json.decodeFromString<ShieldRuleSet>(oaRulesJson) }.getOrDefault(ShieldRuleSet())
    }

    private fun getTargetList(): Set<String> {
        return runCatching { json.decodeFromString<Set<String>>(targetListJson) }.getOrDefault(emptySet())
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WeChatMessageViewApi.removeListener(this)
        discardMsgIds.clear()
    }

    // ==================== 消息插入监听（丢弃策略） ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (!masterEnabled) return
        if (strategy != FilterStrategy.DISCARD.value) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        if (msgInfo.isSelfSender) return

        val talker = msgInfo.talker
        val typeCode = msgInfo.typeCode

        if (shouldIntercept(talker, typeCode)) {
            discardMsgIds.add(msgInfo.serverId)
            logShield(msgInfo, "丢弃")
            WeLogger.i(TAG, "discarded message: talker=$talker, type=$typeCode, msgSvrId=${msgInfo.serverId}")
        }
    }

    // ==================== 消息 View 创建监听（隐藏策略） ====================

    override fun onCreateView(param: XC_MethodHook.MethodHookParam, view: View) {
        if (!masterEnabled) return
        if (strategy != FilterStrategy.HIDE.value) return

        runCatching {
            val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
            if (msgInfo.isSelfSender) return

            val talker = msgInfo.talker
            val typeCode = msgInfo.typeCode

            if (shouldIntercept(talker, typeCode)) {
                view.visibility = View.GONE
                view.layoutParams?.let { lp ->
                    lp.height = 0
                    lp.width = 0
                }
                logShield(msgInfo, "隐藏")
                WeLogger.d(TAG, "hidden message: talker=$talker, type=$typeCode")
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "onCreateView failed", e)
        }
    }

    private fun shouldIntercept(talker: String, typeCode: Int): Boolean {
        // 确定适用的规则集
        val rules = when {
            talker.isGroupChatWxId -> getGroupRules()
            talker.startsWith("gh_") -> getOaRules()
            else -> getPrivateRules()
        }

        if (!rules.enabled) return false
        if (typeCode !in rules.blockedTypes) return false

        // 名单过滤
        val targetList = getTargetList()
        if (targetList.isNotEmpty()) {
            val inList = talker in targetList
            when (listMode) {
                ListType.BLACKLIST.value -> {
                    // 黑名单模式：名单中的会话不拦截
                    if (inList) return false
                }
                ListType.WHITELIST.value -> {
                    // 白名单模式：仅拦截名单中的会话
                    if (!inList) return false
                }
            }
        }

        return true
    }

    private fun logShield(msgInfo: MessageInfo, strategyName: String) {
        if (!enableLog) return
        val typeName = MessageType.fromCode(msgInfo.typeCode)?.displayName ?: "未知(${msgInfo.typeCode})"
        shieldLog.add(
            ShieldLogEntry(
                time = System.currentTimeMillis(),
                talker = msgInfo.talker,
                sender = msgInfo.sender,
                typeName = typeName,
                strategy = strategyName
            )
        )
        // 限制日志数量
        if (shieldLog.size > 500) {
            shieldLog.removeAt(0)
        }
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localMasterEnabled by remember { mutableStateOf(masterEnabled) }
            var localStrategy by remember { mutableStateOf(strategy) }
            var localListMode by remember { mutableStateOf(listMode) }
            var localEnableLog by remember { mutableStateOf(enableLog) }

            var localPrivateRules by remember { mutableStateOf(getPrivateRules()) }
            var localGroupRules by remember { mutableStateOf(getGroupRules()) }
            var localOaRules by remember { mutableStateOf(getOaRules()) }

            var localTargetList by remember { mutableStateOf(getTargetList().toMutableSet()) }
            var showTargetSelector by remember { mutableStateOf(false) }
            var showShieldLog by remember { mutableStateOf(false) }

            if (showTargetSelector) {
                TargetSelectorScreen(
                    onDismiss = { showTargetSelector = false },
                    onSave = { targets ->
                        localTargetList = targets
                        showTargetSelector = false
                    }
                )
            } else if (showShieldLog) {
                ShieldLogScreen(
                    onDismiss = { showShieldLog = false }
                )
            } else {
                AlertDialogContent(
                    title = { Text("消息类型过滤屏蔽") },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            // 总开关
                            ListItem(
                                modifier = Modifier.clickable { localMasterEnabled = !localMasterEnabled },
                                trailingContent = {
                                    Switch(checked = localMasterEnabled, onCheckedChange = null)
                                },
                                headlineContent = { Text("启用屏蔽消息", fontWeight = FontWeight.SemiBold) },
                                supportingContent = { Text("关闭后所有过滤规则失效") }
                            )

                            if (localMasterEnabled) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 拦截策略
                                Text("拦截策略", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FilterStrategy.values().forEach { s ->
                                    ListItem(
                                        modifier = Modifier.clickable { localStrategy = s.value },
                                        trailingContent = {
                                            Text(if (localStrategy == s.value) "✓" else "")
                                        },
                                        headlineContent = { Text(s.description) }
                                    )
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 名单模式
                                Text("名单模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                ListType.values().forEach { lt ->
                                    ListItem(
                                        modifier = Modifier.clickable { localListMode = lt.value },
                                        trailingContent = {
                                            Text(if (localListMode == lt.value) "✓" else "")
                                        },
                                        headlineContent = { Text(lt.description) },
                                        supportingContent = {
                                            Text(
                                                when (lt) {
                                                    ListType.BLACKLIST -> "名单内会话不拦截，其余全部拦截"
                                                    ListType.WHITELIST -> "仅拦截名单内会话，其余不受影响"
                                                }
                                            )
                                        }
                                    )
                                }

                                ListItem(
                                    modifier = Modifier.clickable { showTargetSelector = true },
                                    headlineContent = { Text("管理名单") },
                                    supportingContent = { Text("当前已选 ${localTargetList.size} 个会话") }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 私聊规则
                                Text("私聊规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                RuleSetEditor(
                                    rules = localPrivateRules,
                                    onRulesChanged = { localPrivateRules = it }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 群聊规则
                                Text("群聊规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                RuleSetEditor(
                                    rules = localGroupRules,
                                    onRulesChanged = { localGroupRules = it }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 公众号规则
                                Text("公众号规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                RuleSetEditor(
                                    rules = localOaRules,
                                    onRulesChanged = { localOaRules = it }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 屏蔽日志
                                ListItem(
                                    modifier = Modifier.clickable { localEnableLog = !localEnableLog },
                                    trailingContent = {
                                        Switch(checked = localEnableLog, onCheckedChange = null)
                                    },
                                    headlineContent = { Text("记录屏蔽日志") },
                                    supportingContent = { Text("开启后记录所有被拦截的消息（仅内存，重启微信后清空）") }
                                )

                                if (localEnableLog && shieldLog.isNotEmpty()) {
                                    ListItem(
                                        modifier = Modifier.clickable { showShieldLog = true },
                                        headlineContent = { Text("查看屏蔽日志") },
                                        supportingContent = { Text("当前共 ${shieldLog.size} 条记录") }
                                    )
                                }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            masterEnabled = localMasterEnabled
                            strategy = localStrategy
                            listMode = localListMode
                            enableLog = localEnableLog
                            privateRulesJson = json.encodeToString(localPrivateRules)
                            groupRulesJson = json.encodeToString(localGroupRules)
                            oaRulesJson = json.encodeToString(localOaRules)
                            targetListJson = json.encodeToString(localTargetList)
                            if (!localEnableLog) shieldLog.clear()
                            showToast("设置已保存")
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    @Composable
    private fun RuleSetEditor(
        rules: ShieldRuleSet,
        onRulesChanged: (ShieldRuleSet) -> Unit
    ) {
        var localEnabled by remember(rules) { mutableStateOf(rules.enabled) }
        var localBlockedTypes by remember(rules) { mutableStateOf(rules.blockedTypes.toMutableSet()) }

        Column(modifier = Modifier.fillMaxWidth()) {
            ListItem(
                modifier = Modifier.clickable {
                    localEnabled = !localEnabled
                    onRulesChanged(rules.copy(enabled = localEnabled))
                },
                trailingContent = {
                    Switch(checked = localEnabled, onCheckedChange = null)
                },
                headlineContent = { Text("启用此规则集") }
            )

            if (localEnabled) {
                Text(
                    "选择要屏蔽的消息类型",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    shieldableMessageTypes.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            row.forEach { (type, name) ->
                                val checked = type.code in localBlockedTypes
                                FilterChip(
                                    selected = checked,
                                    onClick = {
                                        if (checked) localBlockedTypes.remove(type.code)
                                        else localBlockedTypes.add(type.code)
                                        onRulesChanged(rules.copy(enabled = true, blockedTypes = localBlockedTypes))
                                    },
                                    label = { Text(name) },
                                    modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TargetSelectorScreen(
        onDismiss: () -> Unit,
        onSave: (MutableSet<String>) -> Unit
    ) {
        val contacts = remember {
            WeDatabaseApi.getContacts()
        }
        val selected = remember { getTargetList().toMutableSet() }
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("选择名单会话") },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            if (listMode == ListType.BLACKLIST.value) "选择不拦截的会话" else "选择需要拦截的会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(contacts, key = { it.wxId }) { contact ->
                        val isSelected = remember { mutableStateOf(selected.contains(contact.wxId)) }
                        ListItem(
                            modifier = Modifier.clickable {
                                isSelected.value = !isSelected.value
                                if (isSelected.value) {
                                    selected.add(contact.wxId)
                                } else {
                                    selected.remove(contact.wxId)
                                }
                            },
                            headlineContent = { Text(contact.displayName) },
                            supportingContent = { Text(contact.wxId) },
                            trailingContent = {
                                Text(if (isSelected.value) "✓" else "")
                            }
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSave(selected)
                    onDismiss()
                }) { Text("保存") }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ShieldLogScreen(
        onDismiss: () -> Unit
    ) {
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("屏蔽日志") },
            text = {
                if (shieldLog.isEmpty()) {
                    Text("暂无拦截记录", modifier = Modifier.padding(16.dp))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(shieldLog.reversed(), key = { "${it.time}-${it.talker}" }) { entry ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "${entry.typeName} - ${entry.strategy}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "会话: ${entry.talker}\n发送者: ${entry.sender}\n时间: ${
                                            java.text.SimpleDateFormat(
                                                "MM-dd HH:mm:ss",
                                                java.util.Locale.getDefault()
                                            ).format(java.util.Date(entry.time))
                                        }"
                                    )
                                }
                            )
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    shieldLog.clear()
                    onDismiss()
                }) { Text("清空日志") }
            },
            confirmButton = {
                Button(onClick = onDismiss) { Text("关闭") }
            }
        )
    }
}