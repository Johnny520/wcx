package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
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
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
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
import com.composables.icons.materialsymbols.outlined.Delete
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@SuppressLint("SetTextI18n")
@Feature(
    name = "关键词自动回复",
    categories = ["聊天"],
    description = "独立关键词匹配自动回复，私聊和群聊规则独立，支持批量关键词配置与自定义回复文本"
)
object KeywordAutoReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "KeywordAutoReply"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    // ==================== 私聊规则 ====================
    private var privateEnabled by prefOption("kw_reply_private_enabled", false)
    private var privateRulesJson by prefOption("kw_reply_private_rules", "[]")

    // ==================== 群聊规则 ====================
    private var groupEnabled by prefOption("kw_reply_group_enabled", false)
    private var groupRulesJson by prefOption("kw_reply_group_rules", "[]")

    // ==================== 全局设置 ====================
    private var cooldownSeconds by prefOption("kw_reply_cooldown", 10)
    private var ignoreSelf by prefOption("kw_reply_ignore_self", true)

    @Serializable
    data class KeywordRule(
        val keywords: List<String> = emptyList(),
        val replyText: String = "",
        val matchMode: Int = 0 // 0 = exact match, 1 = contains
    )

    enum class MatchMode(val value: Int, val description: String) {
        EXACT(0, "精确匹配"),
        CONTAINS(1, "消息包含关键词")
    }

    // 冷却记录：talker -> 上次回复时间戳
    private val cooldownMap = mutableMapOf<String, Long>()

    private fun getPrivateRules(): List<KeywordRule> {
        return runCatching {
            json.decodeFromString<List<KeywordRule>>(privateRulesJson)
        }.getOrDefault(emptyList())
    }

    private fun getGroupRules(): List<KeywordRule> {
        return runCatching {
            json.decodeFromString<List<KeywordRule>>(groupRulesJson)
        }.getOrDefault(emptyList())
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        cooldownMap.clear()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val msgInfo = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return

        if (ignoreSelf && msgInfo.isSelfSender) return
        if (msgInfo.type?.isText != true) return

        val talker = msgInfo.talker
        val content = msgInfo.content ?: return
        val isGroup = talker.isGroupChatWxId

        if (isGroup) {
            if (!groupEnabled) return
            val rules = getGroupRules()
            if (rules.isEmpty()) return
            processRules(talker, content, rules)
        } else {
            if (!privateEnabled) return
            val rules = getPrivateRules()
            if (rules.isEmpty()) return
            processRules(talker, content, rules)
        }
    }

    private fun processRules(talker: String, content: String, rules: List<KeywordRule>) {
        for (rule in rules) {
            val matched = rule.keywords.any { keyword ->
                if (keyword.isBlank()) return@any false
                when (rule.matchMode) {
                    MatchMode.EXACT.value -> content.trim() == keyword.trim()
                    MatchMode.CONTAINS.value -> content.contains(keyword, ignoreCase = true)
                    else -> false
                }
            }

            if (matched && rule.replyText.isNotBlank()) {
                // 冷却检查
                val now = System.currentTimeMillis()
                val lastReply = cooldownMap[talker]
                if (lastReply != null && (now - lastReply) < cooldownSeconds * 1000L) {
                    WeLogger.d(TAG, "cooldown active for $talker, skipped")
                    return
                }
                cooldownMap[talker] = now
                // 清理过期冷却记录
                cooldownMap.entries.removeAll { (now - it.value) > 300_000L }

                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        WeMessageApi.sendText(talker, rule.replyText)
                        WeLogger.i(TAG, "keyword reply sent to $talker")
                    }.onFailure { e ->
                        WeLogger.e(TAG, "keyword reply failed", e)
                    }
                }
                return
            }
        }
    }

    // ==================== UI ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localPrivateEnabled by remember { mutableStateOf(privateEnabled) }
            var localGroupEnabled by remember { mutableStateOf(groupEnabled) }
            var localCooldown by remember { mutableStateOf(cooldownSeconds) }
            var localIgnoreSelf by remember { mutableStateOf(ignoreSelf) }

            val privateRules = remember { mutableStateListOf<KeywordRule>().also { it.addAll(getPrivateRules()) } }
            val groupRules = remember { mutableStateListOf<KeywordRule>().also { it.addAll(getGroupRules()) } }

            AlertDialogContent(
                title = { Text("关键词自动回复设置") },
                text = {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        Text("全局设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                        OutlinedTextField(
                            value = localCooldown.toString(),
                            onValueChange = { v -> v.toIntOrNull()?.let { localCooldown = it.coerceIn(0, 3600) } },
                            label = { Text("冷却时间（秒）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        ListItem(
                            modifier = Modifier.clickable { localIgnoreSelf = !localIgnoreSelf },
                            trailingContent = {
                                Switch(checked = localIgnoreSelf, onCheckedChange = null)
                            },
                            headlineContent = { Text("忽略自己发送的消息") },
                            supportingContent = { Text("开启后不会回复自己发出的消息") }
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 私聊规则
                        Text("私聊规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                        ListItem(
                            modifier = Modifier.clickable { localPrivateEnabled = !localPrivateEnabled },
                            trailingContent = {
                                Switch(checked = localPrivateEnabled, onCheckedChange = null)
                            },
                            headlineContent = { Text("启用私聊自动回复") }
                        )

                        if (localPrivateEnabled) {
                            RulesEditor(
                                rules = privateRules,
                                onRulesChanged = { privateRules.clear(); privateRules.addAll(it) }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // 群聊规则
                        Text("群聊规则", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                        ListItem(
                            modifier = Modifier.clickable { localGroupEnabled = !localGroupEnabled },
                            trailingContent = {
                                Switch(checked = localGroupEnabled, onCheckedChange = null)
                            },
                            headlineContent = { Text("启用群聊自动回复") }
                        )

                        if (localGroupEnabled) {
                            RulesEditor(
                                rules = groupRules,
                                onRulesChanged = { groupRules.clear(); groupRules.addAll(it) }
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        privateEnabled = localPrivateEnabled
                        groupEnabled = localGroupEnabled
                        cooldownSeconds = localCooldown
                        ignoreSelf = localIgnoreSelf
                        privateRulesJson = json.encodeToString(privateRules.toList())
                        groupRulesJson = json.encodeToString(groupRules.toList())
                        showToast("设置已保存")
                        onDismiss()
                    }) { Text("保存") }
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RulesEditor(
        rules: MutableList<KeywordRule>,
        onRulesChanged: (List<KeywordRule>) -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            rules.forEachIndexed { index, rule ->
                var localKeywords by remember(rule) { mutableStateOf(rule.keywords.joinToString("\n")) }
                var localReplyText by remember(rule) { mutableStateOf(rule.replyText) }
                var localMatchMode by remember(rule) { mutableStateOf(rule.matchMode) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("规则 ${index + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = {
                            rules.removeAt(index)
                            onRulesChanged(rules.toList())
                        }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Delete,
                                contentDescription = "删除规则"
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MatchMode.values().forEach { mode ->
                            FilterChip(
                                selected = localMatchMode == mode.value,
                                onClick = {
                                    localMatchMode = mode.value
                                    rules[index] = rule.copy(matchMode = localMatchMode)
                                    onRulesChanged(rules.toList())
                                },
                                label = { Text(mode.description) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = localKeywords,
                        onValueChange = { v ->
                            localKeywords = v
                            rules[index] = rule.copy(
                                keywords = v.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            )
                            onRulesChanged(rules.toList())
                        },
                        label = { Text("关键词（每行一个）") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = localReplyText,
                        onValueChange = { v ->
                            localReplyText = v
                            rules[index] = rule.copy(replyText = v)
                            onRulesChanged(rules.toList())
                        },
                        label = { Text("回复文本（支持多行）") },
                        minLines = 2,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }

            Button(
                onClick = {
                    rules.add(KeywordRule())
                    onRulesChanged(rules.toList())
                },
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Text("添加规则")
            }
        }
    }
}