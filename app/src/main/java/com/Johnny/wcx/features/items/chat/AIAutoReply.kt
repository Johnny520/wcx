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
import androidx.compose.foundation.layout.padding
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

    private val json = Json { ignoreUnknownKeys = true }

    enum class TriggerMode(val value: Int, val description: String) {
        AT_ONLY(0, "仅被 @ 时回复"),
        KEYWORD(1, "包含关键词时回复"),
        ALL(2, "群内任何消息都回复")
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
            var showFavMenu by remember { mutableStateOf(false) }

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
                                            // TODO: 接入 WeMessageApi 或新增 FavApi 获取微信收藏列表
                                            //       当前项目中没有可用的微信收藏读取 API，待后续实现
                                            showToast("暂未接入微信收藏 API")
                                        }
                                    )
                                }
                            }
                        }
                    },
                    text = {
                        DefaultColumn(Modifier.padding(vertical = 8.dp)) {
                            Text("API 配置", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = localApiUrl,
                                onValueChange = { localApiUrl = it },
                                label = { Text("API 地址") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localApiKey,
                                onValueChange = { localApiKey = it },
                                label = { Text("API Key") },
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
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            apiUrl = localApiUrl
                            apiKey = localApiKey
                            model = localModel
                            systemPrompt = localPrompt
                            enableForPrivate = localEnablePrivate
                            enableForGroup = localEnableGroup
                            groupTriggerKeyword = localKeyword
                            replyPrefix = localPrefix
                            replyDelay = localDelayMs.coerceIn(0, 10000)
                            triggerMode = localTriggerMode
                            useWhitelist = localUseWhitelist
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

        AlertDialogContent(
            title = { Text("选择群聊") },
            text = {
                DefaultColumn {
                    Text(
                        if (useWhitelist) "选择需要开启 AI 自动回复的群聊" else "选择需要排除 AI 自动回复的群聊",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    groups.forEach { group ->
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

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (apiKey.isBlank()) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        if (msgInfo.isSelfSender) return
        if (msgInfo.type?.isText != true) return

        val talker = msgInfo.talker
        val content = msgInfo.content ?: return
        val isGroup = talker.isGroupChatWxId

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

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

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

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                WeLogger.e(TAG, "AI API returned $responseCode")
                return ""
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            parseAIResponse(responseBody)
        } catch (e: Exception) {
            WeLogger.e(TAG, "AI API call failed", e)
            ""
        } finally {
            connection.disconnect()
        }
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