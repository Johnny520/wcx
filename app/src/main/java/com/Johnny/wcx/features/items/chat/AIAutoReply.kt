package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.SwitchRow
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
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
    description = "接入 AI 大模型自动回复消息，支持 OpenAI 兼容接口（借鉴 GodHook 设计理念）"
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

    private val json = Json { ignoreUnknownKeys = true }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

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
            var localDelay by remember { mutableStateOf(replyDelay.toString()) }

            AlertDialogContent(
                title = { Text("AI 自动回复设置") },
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

                        SwitchRow(
                            checked = localEnablePrivate,
                            onCheckedChange = { localEnablePrivate = it },
                            text = "私聊自动回复"
                        )
                        SwitchRow(
                            checked = localEnableGroup,
                            onCheckedChange = { localEnableGroup = it },
                            text = "群聊自动回复"
                        )

                        if (localEnableGroup) {
                            OutlinedTextField(
                                value = localKeyword,
                                onValueChange = { localKeyword = it },
                                label = { Text("群聊触发关键词") },
                                singleLine = true
                            )
                        }

                        OutlinedTextField(
                            value = localPrefix,
                            onValueChange = { localPrefix = it },
                            label = { Text("回复前缀（可留空）") },
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = localDelay,
                            onValueChange = { localDelay = it },
                            label = { Text("回复延迟（毫秒）") },
                            singleLine = true
                        )
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
                        replyDelay = localDelay.toIntOrNull()?.coerceIn(0, 10000) ?: 1000
                        showToast("设置已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (apiKey.isBlank()) return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        if (msgInfo.isSelfSender) return
        if (msgInfo.type?.isText != true) return

        val talker = msgInfo.talker
        val content = msgInfo.content ?: return
        val isGroup = talker.isGroupChatWxId()

        if (isGroup) {
            if (!enableForGroup) return
            if (groupTriggerKeyword.isNotBlank() && !content.contains(groupTriggerKeyword)) return
        } else {
            if (!enableForPrivate) return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                delay(replyDelay.toLong())

                val cleanContent = if (isGroup && groupTriggerKeyword.isNotBlank()) {
                    content.replace(groupTriggerKeyword, "").trim()
                } else {
                    content
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
