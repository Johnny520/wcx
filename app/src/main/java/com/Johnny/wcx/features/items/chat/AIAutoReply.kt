package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.More_vert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections

// ── AIAutoReply ─────────────────────────────────────────────────────────────────

@SuppressLint("SetTextI18n")
@Feature(
    name = "AI 自动回复",
    categories = ["聊天"],
    description = "接入 AI 大模型自动回复消息，支持 OpenAI 兼容接口，可选择触发条件和指定群聊"
)
object AIAutoReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AIAutoReply"

    private var apiUrl by prefOption("ai_reply_api_url", "https://api.deepseek.com/chat/completions")
    private var apiKey by prefOption("ai_reply_api_key", "")
    private var model by prefOption("ai_reply_model", "deepseek-chat")
    private var systemPrompt by prefOption(
        "ai_reply_system_prompt",
        "你是一个乐于助人的AI助手，请用简洁友好的语气回复用户的消息。"
    )
    private var enableForPrivate by prefOption("ai_reply_enable_private", true)
    private var enableForGroup by prefOption("ai_reply_enable_group", false)
    private var groupTriggerKeyword by prefOption("ai_reply_group_keyword", "@AI")
    private var replyPrefix by prefOption("ai_reply_prefix", "[AI回复] ")
    private var replyDelay by prefOption("ai_reply_delay", 1000)

    private var triggerMode by prefOption("ai_reply_trigger_mode", 0)
    private var enabledGroups by prefOption("ai_reply_enabled_groups", emptySet<String>())
    private var useWhitelist by prefOption("ai_reply_use_group_whitelist", true)

    // 私聊黑白名单 — 与群聊黑白名单完全独立存储
    private var privateChatMode by prefOption("ai_reply_private_mode", 0) // 0=全部, 1=白名单, 2=黑名单
    private var privateEnabledContacts by prefOption("ai_reply_private_contacts", emptySet<String>())
    private var allowStrangerPrivateReply by prefOption("ai_reply_allow_stranger", false)

    // ── 调试日志 ────────────────────────────────────────────────────────────────
    private data class DebugLogEntry(
        val timestamp: String,
        val requestUrl: String,
        val requestBody: String,
        val responseCode: Int,
        val responseBody: String,
        val error: String?
    )

    private val debugLogs: MutableList<DebugLogEntry> = Collections.synchronizedList(mutableListOf())
    private const val MAX_DEBUG_LOGS = 50

    private val json = Json { ignoreUnknownKeys = true }

    enum class TriggerMode(val value: Int, val description: String) {
        AT_ONLY(0, "仅被 @ 时回复"),
        KEYWORD(1, "包含关键词时回复"),
        ALL(2, "群内任何消息都回复")
    }

    enum class PrivateChatMode(val value: Int, val description: String) {
        ALL(0, "全部人员"),
        WHITELIST(1, "白名单模式"),
        BLACKLIST(2, "黑名单模式")
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localApiUrl by remember { mutableStateOf(apiUrl) }
            var localApiKey by remember { mutableStateOf(apiKey) }
            var localModel by remember { mutableStateOf(model) }
            var localPrompt by remember { mutableStateOf(systemPrompt) }
            var localEnablePrivate by remember { mutableStateOf(enableForPrivate) }
            var localEnableGroup by remember { mutableStateOf(enableForGroup) }
            var localKeyword by remember { mutableStateOf(groupTriggerKeyword) }
            var localPrefix by remember { mutableStateOf(replyPrefix) }
            var localDelayMs by remember { mutableStateOf(replyDelay) }
            var localTriggerMode by remember { mutableStateOf(triggerMode) }
            var localUseWhitelist by remember { mutableStateOf(useWhitelist) }
            var showGroupSelector by remember { mutableStateOf(false) }
            var showPrivateContactSelector by remember { mutableStateOf(false) }
            var showFavMenu by remember { mutableStateOf(false) }
            var localPrivateChatMode by remember { mutableStateOf(privateChatMode) }
            var localAllowStranger by remember { mutableStateOf(allowStrangerPrivateReply) }
            var showDebugLog by remember { mutableStateOf(false) }

            val delayPresets = remember {
                listOf(
                    0 to "立即",
                    1000 to "1秒",
                    2000 to "2秒",
                    3000 to "3秒",
                    5000 to "5秒",
                    10000 to "10秒"
                )
            }

            if (showGroupSelector) {
                GroupSelectorScreen(
                    onDismiss = { showGroupSelector = false },
                    useWhitelist = localUseWhitelist,
                    onSave = { groups ->
                        enabledGroups = groups
                        showToast("已保存 ${groups.size} 个群聊")
                        showGroupSelector = false
                    }
                )
            } else if (showPrivateContactSelector) {
                PrivateContactSelectorScreen(
                    onDismiss = { showPrivateContactSelector = false },
                    useWhitelist = localPrivateChatMode == 1,
                    onSave = { contacts ->
                        privateEnabledContacts = contacts
                        showToast("已保存 ${contacts.size} 个联系人")
                        showPrivateContactSelector = false
                    }
                )
            } else if (showDebugLog) {
                DebugLogViewerScreen(
                    onDismiss = { showDebugLog = false }
                )
            } else {
                AlertDialogContent(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("AI 自动回复设置")
                            Box {
                                IconButton(onClick = { showFavMenu = true }) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.More_vert,
                                        contentDescription = "更多"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showFavMenu,
                                    onDismissRequest = { showFavMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("从收藏中选择回复") },
                                        onClick = {
                                            showFavMenu = false
                                            showToast("暂未接入微信收藏 API")
                                        }
                                    )
                                }
                            }
                        }
                    },
                    text = {
                        DefaultColumn(Modifier.padding(vertical = 8.dp), scrollable = true) {
                            Text("API 配置", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "建议优先使用免费API测试模块连通性，确认功能正常后，再使用付费Token",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = localApiUrl,
                                onValueChange = { localApiUrl = it },
                                label = { Text("API 地址") },
                                supportingText = { Text("自动去除首尾空格和不可见字符") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localApiKey,
                                onValueChange = { localApiKey = it },
                                label = { Text("API Key") },
                                supportingText = { Text("自动去除首尾空格和不可见字符") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localModel,
                                onValueChange = { localModel = it },
                                label = { Text("模型名称") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localPrompt,
                                onValueChange = { localPrompt = it },
                                label = { Text("系统提示词") },
                                maxLines = 3
                            )

                            Spacer(Modifier.padding(top = 12.dp))
                            Text("回复设置", style = MaterialTheme.typography.titleSmall)

                            ListItem(
                                modifier = Modifier.clickable { localEnablePrivate = !localEnablePrivate },
                                trailingContent = {
                                    Switch(checked = localEnablePrivate, onCheckedChange = null)
                                },
                                headlineContent = { Text("私聊自动回复") }
                            )

                            if (localEnablePrivate) {
                                Text(
                                    "私聊模式",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                PrivateChatMode.values().forEach { mode ->
                                    ListItem(
                                        modifier = Modifier.clickable { localPrivateChatMode = mode.value },
                                        trailingContent = {
                                            Text(if (localPrivateChatMode == mode.value) "✓" else "")
                                        },
                                        headlineContent = { Text(mode.description) },
                                        supportingContent = {
                                            Text(
                                                when (mode) {
                                                    PrivateChatMode.ALL -> "所有私聊正常触发回复"
                                                    PrivateChatMode.WHITELIST -> "仅名单内好友触发私聊自动回复"
                                                    PrivateChatMode.BLACKLIST -> "名单内好友不会触发回复，其余正常生效"
                                                }
                                            )
                                        }
                                    )
                                }

                                if (localPrivateChatMode != PrivateChatMode.ALL.value) {
                                    val modeLabel = if (localPrivateChatMode == PrivateChatMode.WHITELIST.value) "白名单" else "黑名单"
                                    ListItem(
                                        modifier = Modifier.clickable { showPrivateContactSelector = true },
                                        headlineContent = { Text("选择${modeLabel}联系人") },
                                        supportingContent = { Text("当前已选 ${privateEnabledContacts.size} 个联系人") }
                                    )
                                }

                                ListItem(
                                    modifier = Modifier.clickable { localAllowStranger = !localAllowStranger },
                                    trailingContent = {
                                        Switch(checked = localAllowStranger, onCheckedChange = null)
                                    },
                                    headlineContent = { Text("允许陌生人临时私聊触发") },
                                    supportingContent = { Text("开启后，非好友的临时会话也可触发自动回复") }
                                )
                            }
                            ListItem(
                                modifier = Modifier.clickable { localEnableGroup = !localEnableGroup },
                                trailingContent = {
                                    Switch(checked = localEnableGroup, onCheckedChange = null)
                                },
                                headlineContent = { Text("群聊自动回复") }
                            )

                            if (localEnableGroup) {
                                Text(
                                    "触发条件",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )

                                TriggerMode.values().forEach { mode ->
                                    ListItem(
                                        modifier = Modifier.clickable { localTriggerMode = mode.value },
                                        trailingContent = {
                                            Text(if (localTriggerMode == mode.value) "✓" else "")
                                        },
                                        headlineContent = { Text(mode.description) }
                                    )
                                }

                                if (localTriggerMode == TriggerMode.KEYWORD.value) {
                                    OutlinedTextField(
                                        value = localKeyword,
                                        onValueChange = { localKeyword = it },
                                        label = { Text("触发关键词") },
                                        singleLine = true
                                    )
                                }

                                ListItem(
                                    modifier = Modifier.clickable { localUseWhitelist = !localUseWhitelist },
                                    trailingContent = {
                                        Switch(checked = localUseWhitelist, onCheckedChange = null)
                                    },
                                    headlineContent = { Text(if (localUseWhitelist) "白名单模式" else "黑名单模式") },
                                    supportingContent = { Text(if (localUseWhitelist) "仅在选中的群聊中回复" else "在除选中群聊外的所有群聊中回复") }
                                )

                                ListItem(
                                    modifier = Modifier.clickable { showGroupSelector = true },
                                    headlineContent = { Text("选择群聊") },
                                    supportingContent = { Text("当前已选 ${enabledGroups.size} 个群聊") }
                                )
                            }

                            OutlinedTextField(
                                value = localPrefix,
                                onValueChange = { localPrefix = it },
                                label = { Text("回复前缀（可留空）") },
                                singleLine = true
                            )

                            Text("回复延迟", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                delayPresets.forEach { (ms, label) ->
                                    FilterChip(
                                        selected = localDelayMs == ms,
                                        onClick = { localDelayMs = ms },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Spacer(Modifier.padding(top = 12.dp))
                            Button(
                                onClick = { showDebugLog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("调试日志") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            apiUrl = localApiUrl.trim()
                            apiKey = localApiKey.trim()
                            model = localModel
                            systemPrompt = localPrompt
                            enableForPrivate = localEnablePrivate
                            enableForGroup = localEnableGroup
                            groupTriggerKeyword = localKeyword
                            replyPrefix = localPrefix
                            replyDelay = localDelayMs.coerceIn(0, 10000)
                            triggerMode = localTriggerMode
                            useWhitelist = localUseWhitelist
                            privateChatMode = localPrivateChatMode
                            allowStrangerPrivateReply = localAllowStranger
                            showToast("设置已保存")
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GroupSelectorScreen(
        onDismiss: () -> Unit,
        useWhitelist: Boolean,
        onSave: (Set<String>) -> Unit
    ) {
        val groups = remember {
            WeDatabaseApi.getGroups().filter { it.wxId.isNotBlank() }
        }
        val selected = remember { enabledGroups.toMutableSet() }
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("选择群聊") },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            if (useWhitelist) "选择需要开启 AI 自动回复的群聊" else "选择需要排除 AI 自动回复的群聊",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(groups, key = { it.wxId }) { group ->
                        val isSelected = remember { mutableStateOf(selected.contains(group.wxId)) }
                        ListItem(
                            modifier = Modifier.clickable {
                                isSelected.value = !isSelected.value
                                if (isSelected.value) {
                                    selected.add(group.wxId)
                                } else {
                                    selected.remove(group.wxId)
                                }
                            },
                            headlineContent = { Text(group.displayName) },
                            supportingContent = { Text(group.wxId) },
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
    private fun PrivateContactSelectorScreen(
        onDismiss: () -> Unit,
        useWhitelist: Boolean,
        onSave: (Set<String>) -> Unit
    ) {
        val contacts = remember {
            WeDatabaseApi.getFriends().filter { it.wxId.isNotBlank() }
        }
        val selected = remember { privateEnabledContacts.toMutableSet() }
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("选择联系人") },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            if (useWhitelist) "选择需要开启 AI 自动回复的联系人" else "选择需要排除 AI 自动回复的联系人",
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
        )}
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (apiKey.isBlank()) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        if (msgInfo.isSelfSender) return
        if (msgInfo.type?.isText != true) return

        val talker = msgInfo.talker
        val content = msgInfo.content ?: return
        val isGroup = talker.isGroupChatWxId
        val isStranger = !isGroup && WeDatabaseApi.getFriend(msgInfo.sender) == null

        if (isGroup) {
            if (!enableForGroup) return

            if (useWhitelist && talker !in enabledGroups) return
            if (!useWhitelist && talker in enabledGroups) return

            when (triggerMode) {
                TriggerMode.AT_ONLY.value -> {
                    val selfWxId = WeApi.selfWxId
                    val atPattern = Regex("@(${selfWxId}|${WeDatabaseApi.getDisplayName(selfWxId)})", RegexOption.IGNORE_CASE)
                    if (!atPattern.containsMatchIn(content)) return
                }
                TriggerMode.KEYWORD.value -> {
                    if (groupTriggerKeyword.isNotBlank() && !content.contains(groupTriggerKeyword)) return
                }
                TriggerMode.ALL.value -> {
                }
            }
        } else {
            if (!enableForPrivate) return
            // 私聊黑白名单过滤：陌生人不受黑白名单限制（由 allowStrangerPrivateReply 单独控制）
            if (!isStranger && privateChatMode != PrivateChatMode.ALL.value) {
                val senderWxId = msgInfo.sender
                if (privateChatMode == PrivateChatMode.WHITELIST.value) {
                    if (senderWxId !in privateEnabledContacts) return
                } else if (privateChatMode == PrivateChatMode.BLACKLIST.value) {
                    if (senderWxId in privateEnabledContacts) return
                }
            }
            // 陌生人过滤：非好友且未开启陌生人开关时跳过
            if (isStranger && !allowStrangerPrivateReply) return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                delay(replyDelay.toLong())

                val cleanContent = when {
                    isGroup && triggerMode == TriggerMode.AT_ONLY.value -> {
                        val selfWxId = WeApi.selfWxId
                        val selfName = WeDatabaseApi.getDisplayName(selfWxId)
                        content.replace("@$selfWxId", "").replace("@$selfName", "").trim()
                    }
                    isGroup && groupTriggerKeyword.isNotBlank() -> {
                        content.replace(groupTriggerKeyword, "").trim()
                    }
                    else -> content
                }

                if (cleanContent.isBlank()) return@runCatching

                val reply = callAI(cleanContent)
                if (reply.isNotBlank()) {
                    val finalReply = if (replyPrefix.isNotBlank()) "$replyPrefix$reply" else reply
                    WeMessageApi.sendText(talker, finalReply)
                }
            }.onFailure { e ->
                WeLogger.e(TAG, "AI reply failed", e)
            }
        }
    }

    private fun callAI(userMessage: String): String {
        val url = URL(apiUrl)
        val connection = url.openConnection() as HttpURLConnection

        val requestBody = buildJsonObject {
            put("model", model)
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
            put("temperature", 0.7)
        }
        val requestBodyStr = requestBody.toString()

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            connection.outputStream.use { os ->
                os.write(requestBodyStr.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull().orEmpty()
            }

            val errorMsg = when (responseCode) {
                401 -> "401 Unauthorized — API Key 无效或已过期"
                404 -> "404 Not Found — API 地址不存在"
                500 -> "500 Internal Server Error — 服务器内部错误"
                429 -> "429 Too Many Requests — 请求频率超限"
                403 -> "403 Forbidden — 无访问权限"
                else -> if (responseCode !in 200..299) "HTTP $responseCode" else null
            }

            addDebugLog(apiUrl, requestBodyStr, responseCode, responseBody, errorMsg)

            if (responseCode != 200) {
                WeLogger.e(TAG, "AI API returned $responseCode: $errorMsg")
                return ""
            }

            parseAIResponse(responseBody)
        } catch (e: java.net.SocketTimeoutException) {
            addDebugLog(apiUrl, requestBodyStr, -1, "", "Timeout — 请求超时")
            WeLogger.e(TAG, "AI API call timeout", e)
            ""
        } catch (e: java.net.ConnectException) {
            addDebugLog(apiUrl, requestBodyStr, -1, "", "ConnectException — 无法连接服务器")
            WeLogger.e(TAG, "AI API call connect failed", e)
            ""
        } catch (e: java.net.UnknownHostException) {
            addDebugLog(apiUrl, requestBodyStr, -1, "", "UnknownHostException — DNS 解析失败")
            WeLogger.e(TAG, "AI API call unknown host", e)
            ""
        } catch (e: Exception) {
            addDebugLog(apiUrl, requestBodyStr, -1, "", "Exception: ${e.message}")
            WeLogger.e(TAG, "AI API call failed", e)
            ""
        } finally {
            connection.disconnect()
        }
    }

    private fun addDebugLog(
        requestUrl: String,
        requestBody: String,
        responseCode: Int,
        responseBody: String,
        error: String?
    ) {
        val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now())
        val entry = DebugLogEntry(timestamp, requestUrl, requestBody, responseCode, responseBody, error)
        debugLogs.add(entry)
        while (debugLogs.size > MAX_DEBUG_LOGS) {
            debugLogs.removeAt(0)
        }
        WeLogger.i(TAG, "[DEBUG] $requestUrl -> $responseCode ${error ?: ""}")
    }

    private fun parseAIResponse(response: String): String {
        return try {
            val jsonObj = json.parseToJsonElement(response).jsonObject
            val choices = jsonObj["choices"]?.jsonArray
            if (choices.isNullOrEmpty()) return ""

            val firstChoice = choices[0].jsonObject
            val message = firstChoice["message"]?.jsonObject
            message?.get("content")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        } catch (e: Exception) {
            WeLogger.e(TAG, "Failed to parse AI response", e)
            ""
        }
    }
}

// ── 调试日志查看器（文件级 Composable）────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogViewerScreen(
    onDismiss: () -> Unit
) {
    val logs: List<DebugLogEntry> = remember { debugLogs.toList() }
    val listState = rememberLazyListState()

    AlertDialogContent(
        title = { Text("调试日志 (最近 ${logs.size} 条)") },
        text = {
            if (logs.isEmpty()) {
                Text("暂无日志记录", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs.size) { index ->
                        val log: DebugLogEntry = logs[index]
                        DefaultColumn {
                            Text(
                                "[${log.timestamp}]",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text("URL: ${log.requestUrl}", style = MaterialTheme.typography.bodySmall)
                            Text("Request: ${log.requestBody}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Response: ${log.responseCode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (log.responseCode == 200)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                            if (log.responseBody.isNotBlank()) {
                                Text(
                                    "Body: ${log.responseBody.take(500)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 5
                                )
                            }
                            if (log.error != null) {
                                Text(
                                    "Error: ${log.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3
                                )
                            }
                            Spacer(Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        confirmButton = {
            Button(onClick = {
                debugLogs.clear()
                showToast("日志已清除")
                onDismiss()
            }) { Text("清除日志") }
        }
    )
}