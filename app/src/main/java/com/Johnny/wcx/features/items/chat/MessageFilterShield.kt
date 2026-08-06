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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import de.robv.android.xposed.XC_MethodHook
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SuppressLint("SetTextI18n")
@Feature(
    name = "消息类型过滤屏蔽",
    categories = ["聊天"],
    description = "按消息类型过滤屏蔽，支持私聊/群聊/公众号独立规则，生效范围控制，模板管理，黑/白名单，拦截日志"
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

    // 需要被丢弃的消息 msgSvrId 集合
    private val discardMsgIds = mutableSetOf<Long>()

    // ==================== 数据模型 ====================

    /** 屏蔽规则生效范围 */
    enum class ShieldScope(val value: Int, val description: String) {
        ALL_CHATS(0, "全部会话"),
        SELECTED_CHATS(1, "仅选中群聊"),
        EXCLUDED_CHATS(2, "排除选中群聊")
    }

    @Serializable
    data class ShieldRuleSet(
        val enabled: Boolean = false,
        val blockedTypes: Set<Int> = emptySet(),
        val scope: Int = ShieldScope.ALL_CHATS.value,
        val scopeChats: Set<String> = emptySet()
    )

    @Serializable
    data class ShieldLogEntry(
        val time: Long = System.currentTimeMillis(),
        val talker: String = "",
        val sender: String = "",
        val typeName: String = "",
        val strategy: String = "",
        val scope: String = "",
        val hit: Boolean = false
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

        val (intercepted, ruleScope) = shouldInterceptWithScope(talker, typeCode)
        if (intercepted) {
            discardMsgIds.add(msgInfo.serverId)
            logShield(msgInfo, "丢弃", ruleScope, true)
            WeLogger.i(TAG, "discarded message: talker=$talker, type=$typeCode, scope=$ruleScope, msgSvrId=${msgInfo.serverId}")
        } else {
            logShield(msgInfo, "放过", ruleScope, false)
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

            val (intercepted, ruleScope) = shouldInterceptWithScope(talker, typeCode)
            if (intercepted) {
                view.visibility = View.GONE
                view.layoutParams?.let { lp ->
                    lp.height = 0
                    lp.width = 0
                }
                logShield(msgInfo, "隐藏", ruleScope, true)
                WeLogger.d(TAG, "hidden message: talker=$talker, type=$typeCode, scope=$ruleScope")
            } else {
                logShield(msgInfo, "放过", ruleScope, false)
            }
        }.onFailure { e ->
            WeLogger.e(TAG, "onCreateView failed", e)
        }
    }

    /** 返回 (是否拦截, 生效范围描述) */
    private fun shouldInterceptWithScope(talker: String, typeCode: Int): Pair<Boolean, String> {
        return runCatching {
            val rules = when {
                talker.isGroupChatWxId -> getGroupRules()
                talker.startsWith("gh_") -> getOaRules()
                else -> getPrivateRules()
            }

            if (!rules.enabled) return Pair(false, "规则集未启用")

            if (typeCode !in rules.blockedTypes) return Pair(false, "类型不在屏蔽列表")

            // === 生效范围检查 ===
            val scope = ShieldScope.entries.find { it.value == rules.scope } ?: ShieldScope.ALL_CHATS
            when (scope) {
                ShieldScope.SELECTED_CHATS -> {
                    if (talker !in rules.scopeChats) {
                        WeLogger.d(TAG, "scope skip: talker=$talker not in SELECTED_CHATS, scopeChats=${rules.scopeChats}")
                        return Pair(false, "不在选中群聊范围内")
                    }
                }
                ShieldScope.EXCLUDED_CHATS -> {
                    if (talker in rules.scopeChats) {
                        WeLogger.d(TAG, "scope skip: talker=$talker in EXCLUDED_CHATS, scopeChats=${rules.scopeChats}")
                        return Pair(false, "在排除群聊范围内")
                    }
                }
                ShieldScope.ALL_CHATS -> { /* 不过滤 */ }
            }

            // === 全局名单过滤 ===
            val targetList = getTargetList()
            if (targetList.isNotEmpty()) {
                val inList = talker in targetList
                when (listMode) {
                    ListType.BLACKLIST.value -> {
                        if (inList) return Pair(false, "在黑名单中")
                    }
                    ListType.WHITELIST.value -> {
                        if (!inList) return Pair(false, "不在白名单中")
                    }
                }
            }

            Pair(true, scope.description)
        }.getOrDefault(Pair(false, "异常"))
    }

    /** 兼容旧接口 */
    private fun shouldIntercept(talker: String, typeCode: Int): Boolean {
        return shouldInterceptWithScope(talker, typeCode).first
    }

    private fun logShield(msgInfo: MessageInfo, action: String, scope: String, hit: Boolean) {
        if (!enableLog) return
        val typeName = MessageType.fromCode(msgInfo.typeCode)?.displayName ?: "未知(${msgInfo.typeCode})"
        shieldLog.add(
            ShieldLogEntry(
                time = System.currentTimeMillis(),
                talker = msgInfo.talker,
                sender = msgInfo.sender,
                typeName = typeName,
                strategy = action,
                scope = scope,
                hit = hit
            )
        )
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

            // 用于 RuleSetEditor 的 scope 聊天选择
            var showScopeChatSelector by remember { mutableStateOf(false) }
            var scopeChatSelectorForRules by remember { mutableStateOf<ShieldRuleSet?>(null) }
            var scopeChatSelectorOnSave by remember { mutableStateOf<((Set<String>) -> Unit)?>(null) }

            if (showScopeChatSelector && scopeChatSelectorForRules != null) {
                ScopeChatSelectorScreen(
                    rules = scopeChatSelectorForRules!!,
                    onDismiss = { showScopeChatSelector = false },
                    onSave = { selectedChats ->
                        scopeChatSelectorOnSave?.invoke(selectedChats)
                        showScopeChatSelector = false
                    }
                )
            } else if (showTargetSelector) {
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
                                    onRulesChanged = { localPrivateRules = it },
                                    onOpenScopeChatSelector = { rules, onSaveCb ->
                                        scopeChatSelectorForRules = rules
                                        scopeChatSelectorOnSave = onSaveCb
                                        showScopeChatSelector = true
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 群聊规则
                                Text("群聊规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                RuleSetEditor(
                                    rules = localGroupRules,
                                    onRulesChanged = { localGroupRules = it },
                                    onOpenScopeChatSelector = { rules, onSaveCb ->
                                        scopeChatSelectorForRules = rules
                                        scopeChatSelectorOnSave = onSaveCb
                                        showScopeChatSelector = true
                                    }
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                // 公众号规则
                                Text("公众号规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                RuleSetEditor(
                                    rules = localOaRules,
                                    onRulesChanged = { localOaRules = it },
                                    onOpenScopeChatSelector = { rules, onSaveCb ->
                                        scopeChatSelectorForRules = rules
                                        scopeChatSelectorOnSave = onSaveCb
                                        showScopeChatSelector = true
                                    }
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
        onRulesChanged: (ShieldRuleSet) -> Unit,
        onOpenScopeChatSelector: (ShieldRuleSet, (Set<String>) -> Unit) -> Unit
    ) {
        var localEnabled by remember(rules) { mutableStateOf(rules.enabled) }
        var localBlockedTypes by remember(rules) { mutableStateOf(rules.blockedTypes.toMutableSet()) }
        var localScope by remember(rules) { mutableStateOf(rules.scope) }
        var localScopeChats by remember(rules) { mutableStateOf(rules.scopeChats.toMutableSet()) }

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
                // === 生效范围 ===
                Text(
                    "生效范围",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                ShieldScope.entries.forEach { scope ->
                    ListItem(
                        modifier = Modifier.clickable {
                            localScope = scope.value
                            onRulesChanged(rules.copy(enabled = true, scope = localScope))
                        },
                        trailingContent = {
                            Text(if (localScope == scope.value) "✓" else "")
                        },
                        headlineContent = { Text(scope.description) },
                        supportingContent = {
                            Text(
                                when (scope) {
                                    ShieldScope.ALL_CHATS -> "所有会话均生效"
                                    ShieldScope.SELECTED_CHATS -> "仅对选中群聊生效，已选 ${localScopeChats.size} 个群"
                                    ShieldScope.EXCLUDED_CHATS -> "排除选中群聊，已选 ${localScopeChats.size} 个群"
                                }
                            )
                        }
                    )
                }

                // 选中/排除群聊的管理按钮
                if (localScope == ShieldScope.SELECTED_CHATS.value || localScope == ShieldScope.EXCLUDED_CHATS.value) {
                    ListItem(
                        modifier = Modifier.clickable {
                            onOpenScopeChatSelector(
                                rules.copy(scope = localScope, scopeChats = localScopeChats)
                            ) { selectedChats ->
                                localScopeChats = selectedChats.toMutableSet()
                                onRulesChanged(
                                    rules.copy(
                                        enabled = true,
                                        scope = localScope,
                                        scopeChats = localScopeChats
                                    )
                                )
                            }
                        },
                        headlineContent = {
                            Text(
                                if (localScope == ShieldScope.SELECTED_CHATS.value) "选择生效群聊"
                                else "选择排除群聊"
                            )
                        },
                        supportingContent = { Text("当前已选 ${localScopeChats.size} 个群聊") }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // === 消息类型选择 ===
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
                                        onRulesChanged(
                                            rules.copy(
                                                enabled = true,
                                                blockedTypes = localBlockedTypes,
                                                scope = localScope,
                                                scopeChats = localScopeChats
                                            )
                                        )
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

    /** 选择生效范围群聊的弹窗（带搜索） */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ScopeChatSelectorScreen(
        rules: ShieldRuleSet,
        onDismiss: () -> Unit,
        onSave: (Set<String>) -> Unit
    ) {
        val groups = remember {
            WeDatabaseApi.getContacts().filter { it.wxId.isGroupChatWxId }
        }
        val selected = remember(rules) { rules.scopeChats.toMutableSet() }
        val listState = rememberLazyListState()
        var searchQuery by remember { mutableStateOf("") }

        val filteredGroups = remember(searchQuery, groups) {
            if (searchQuery.isBlank()) groups
            else groups.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true) ||
                        contact.wxId.contains(searchQuery, ignoreCase = true)
            }
        }

        val title = if (rules.scope == ShieldScope.SELECTED_CHATS.value) "选择生效群聊" else "选择排除群聊"

        AlertDialogContent(
            title = { Text(title) },
            text = {
                DefaultColumn {
                    // 搜索框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索群名或群ID") },
                        singleLine = true
                    )

                    if (filteredGroups.isEmpty()) {
                        Text(
                            "无匹配的群聊",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredGroups, key = { it.wxId }) { contact ->
                                val isSelected = remember(selected) { mutableStateOf(selected.contains(contact.wxId)) }
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
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSave(selected)
                }) { Text("保存") }
            }
        )
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
        var searchQuery by remember { mutableStateOf("") }

        val filteredContacts = remember(searchQuery, contacts) {
            if (searchQuery.isBlank()) contacts
            else contacts.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true) ||
                        contact.wxId.contains(searchQuery, ignoreCase = true)
            }
        }

        AlertDialogContent(
            title = { Text("选择名单会话") },
            text = {
                DefaultColumn {
                    // 搜索框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索群名/昵称/微信号") },
                        singleLine = true
                    )

                    Text(
                        if (listMode == ListType.BLACKLIST.value) "选择不拦截的会话" else "选择需要拦截的会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    if (filteredContacts.isEmpty()) {
                        Text(
                            "无匹配的会话",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredContacts, key = { it.wxId }) { contact ->
                                val isSelected = remember(selected) { mutableStateOf(selected.contains(contact.wxId)) }
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
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSave(selected)
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
                            val hitLabel = if (entry.hit) "已拦截" else "放过"
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "${entry.typeName} - ${entry.strategy} ($hitLabel)",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "会话: ${entry.talker}\n发送者: ${entry.sender}\n" +
                                                "生效范围: ${entry.scope}\n时间: ${
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