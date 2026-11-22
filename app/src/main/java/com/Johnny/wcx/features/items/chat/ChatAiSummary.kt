package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.utils.strings.isGroupChatWxId
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Article
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 聊天记录 AI 总结。
 *
 * 入口：聊天输入栏长按菜单里的「AI 总结当前聊天」（走 [WeChatInputBarMenuApi]，不 hook 顶部菜单，
 * 避免菜单结构变化把聊天页拖挂）。
 *
 * 流程：读最近 N 条消息（`message` 表按 createTime desc）→ 过滤非文本消息并标注 [图片]/[语音] →
 * 拼 transcript（单次请求截断到 8000 字符）→ 调 AI（复用 AIAutoReply 的接口配置）→
 * 结果显示在可滚动对话框里，可复制。
 */
@Feature(
    name = "聊天记录 AI 总结",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中总结当前聊天最近 N 条消息\n复用「AI 自动回复」的接口配置，结果可复制"
)
object ChatAiSummary : SwitchFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "ChatAiSummary"

    /** 单次请求送进模型的 transcript 上限（字符）。 */
    private const val MAX_TRANSCRIPT_CHARS = 8000

    /** 单条消息最多保留的字数，防止一条长文吃掉全部预算。 */
    private const val MAX_ONE_MESSAGE_CHARS = 300

    private const val MIN_COUNT = 10
    private const val MAX_COUNT = 500

    private const val DEFAULT_PROMPT =
        "请阅读下面的聊天记录，用简体中文输出：\n" +
                "1. 三句话以内的整体概述；\n" +
                "2. 讨论的主要话题（要点列举）；\n" +
                "3. 已确定的结论与需要跟进的事项（没有就写「无」）。\n" +
                "不要复述原文，不要编造记录里没有的信息。"

    private var summaryCount by prefOption("chat_summary_count", 100)
    private var summaryPrompt by prefOption("chat_summary_prompt", DEFAULT_PROMPT)

    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    // ─── 菜单入口 ─────────────────────────────────────────────────────────────

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "ai_chat_summary",
            icon = MaterialSymbols.Outlined.Article,
            label = "AI 总结当前聊天",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                runCatching { showSummaryDialog(context) }
                    .onFailure { error ->
                        WeLogger.e(TAG, "打开总结弹窗失败", error)
                        showToast(context, "AI 总结失败：${error.message ?: "未知错误"}")
                    }
            }
        )
    )

    // ─── 弹窗 ─────────────────────────────────────────────────────────────────

    private fun showSummaryDialog(context: Context) {
        val talker = WeCurrentConversationApi.value
        if (talker.isBlank()) {
            showToast(context, "没识别到当前聊天，请先打开要总结的聊天")
            return
        }
        val count = summaryCount.coerceIn(MIN_COUNT, MAX_COUNT)
        val prompt = summaryPrompt.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT

        showComposeDialog(context) {
            var summary by remember { mutableStateOf("") }
            var errorText by remember { mutableStateOf("") }
            var loading by remember { mutableStateOf(true) }

            LaunchedEffect(talker, count, prompt) {
                val result = withContext(Dispatchers.IO) { summarize(talker, count, prompt) }
                loading = false
                result
                    .onSuccess { summary = it }
                    .onFailure { error ->
                        errorText = error.message ?: "未知错误"
                        WeLogger.e(TAG, "AI 总结失败", error)
                        showToast("AI 总结失败：$errorText")
                    }
            }

            AlertDialogContent(
                title = { Text("AI 总结（最近 $count 条）") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        when {
                            loading -> {
                                Text(
                                    "正在读取聊天记录并请求 AI…",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            errorText.isNotBlank() -> {
                                Text(
                                    "总结失败：$errorText",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "可以在「聊天记录 AI 总结」设置里减少条数，或在「AI 自动回复」里检查接口地址与 Key。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            else -> Text(summary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            copyToClipboard(context, summary)
                            showToast("总结已复制")
                        },
                        enabled = summary.isNotBlank()
                    ) { Text("复制总结") }
                }
            )
        }
    }

    // ─── 取消息 + 请求 AI ─────────────────────────────────────────────────────

    /** IO 线程执行：读库 → 拼 transcript → 调 AI。 */
    private fun summarize(talker: String, count: Int, prompt: String): Result<String> = runCatching {
        if (!WeDatabaseApi.isReady) {
            throw IOException("数据库尚未就绪，请稍后重试")
        }
        val transcript = buildTranscript(talker, count)
        if (transcript.isBlank()) throw IOException("没有读到可总结的文字消息")

        val userPrompt = "$prompt\n\n===== 聊天记录（最近 $count 条，最早在上）=====\n$transcript"
        TranslateSupport.chatCompletion(TranslateSupport.SYSTEM_SUMMARY_PROMPT, userPrompt).getOrThrow()
    }

    private data class Entry(val senderId: String, val time: Long, val body: String)

    /**
     * 读最近 [count] 条消息并拼成 transcript。
     * 沿用项目里既有的 `message` 表查询方式（见 WeMessageApi 的 `SELECT ... FROM message WHERE ...`）。
     */
    private fun buildTranscript(talker: String, count: Int): String {
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT createTime, isSend, type, content FROM message WHERE talker = ? " +
                        "ORDER BY createTime DESC LIMIT $count",
                arrayOf<Any>(talker)
            )
        }.getOrElse { error ->
            WeLogger.e(TAG, "查询 message 表失败", error)
            throw IOException("读取聊天记录失败：${error.message ?: "查询异常"}")
        }
        if (rows.isEmpty()) return ""

        val entries = rows.map { row ->
            val type = (row["type"] as? Number)?.toInt() ?: 0
            val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
            val createTime = (row["createTime"] as? Number)?.toLong() ?: 0L
            val content = row["content"] as? String ?: ""
            val split = splitSender(content, talker)
            val senderId = when {
                isSend != 0 -> "我"
                split.first.isNotBlank() -> split.first
                else -> talker
            }
            Entry(senderId, createTime, messageBody(type, split.second))
        }

        val names = resolveNames(entries.map { it.senderId }.filter { it != "我" }.toSet())

        // 从最新往回累加，保证被截断的是「更早」的内容
        var budget = MAX_TRANSCRIPT_CHARS
        var omitted = false
        val collected = ArrayList<String>()
        for (entry in entries.asReversed()) {
            if (entry.body.isBlank()) continue
            val speaker = names[entry.senderId] ?: entry.senderId
            val timeText = formatTime(entry.time)
            val prefix = if (timeText.isBlank()) "" else "$timeText "
            val line = "$prefix$speaker: ${entry.body}"
            if (line.length + 1 > budget) {
                omitted = true
                break
            }
            collected.add(line)
            budget -= line.length + 1
        }
        if (collected.isEmpty()) return ""

        val transcript = collected.asReversed().joinToString("\n")
        return if (omitted) "（更早的消息已省略）\n$transcript" else transcript
    }

    /**
     * 群聊消息的 content 形如 `wxid_xxx:\n正文`，拆成 (发送者, 正文)。
     * 私聊原样返回。前缀长度限制在 64 字符内且不含空白，避免误伤正文里的冒号。
     */
    private fun splitSender(content: String, talker: String): Pair<String, String> {
        if (!talker.isGroupChatWxId) return "" to content
        val index = content.indexOf(":\n")
        if (index in 1..64) {
            val prefix = content.substring(0, index)
            val clean = prefix.none { it == ' ' || it == '\n' || it == '\t' }
            if (clean) return prefix to content.substring(index + 2)
        }
        return "" to content
    }

    /** 非文本消息统一标注成 [图片] / [语音] 等，避免把 XML 塞给模型。 */
    private fun messageBody(typeCode: Int, content: String): String {
        val type = MessageType.fromCode(typeCode) ?: return "[未知类型]"
        val body = when {
            type.isText -> {
                val text = content.trim()
                // 引用消息的 content 一般是 XML，直接总结没有意义
                if (text.startsWith("<")) "[${type.displayName}]" else text
            }

            else -> "[${type.displayName}]"
        }
        if (body.isBlank()) return ""
        return if (body.length > MAX_ONE_MESSAGE_CHARS) {
            body.take(MAX_ONE_MESSAGE_CHARS) + "…"
        } else {
            body
        }
    }

    private fun resolveNames(senderIds: Set<String>): Map<String, String> {
        val ids = senderIds.filter { it.isNotBlank() && it != "我" }.take(64)
        if (ids.isEmpty()) return emptyMap()
        val placeholders = ids.joinToString(",") { "?" }
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT username, conRemark, nickname FROM rcontact WHERE username IN ($placeholders)",
                // Array<String> 不能直接 spread 成 Array<out Any>（数组不变型），先转成 Array<Any>
                ids.map { it as Any }.toTypedArray()
            )
        }.getOrElse { error ->
            WeLogger.w(TAG, "查询联系人昵称失败（降级为 wxid）", error)
            emptyList()
        }
        return rows.mapNotNull { row ->
            val wxid = row["username"] as? String ?: return@mapNotNull null
            val remark = (row["conRemark"] as? String).orEmpty().trim()
            val nickname = (row["nickname"] as? String).orEmpty().trim()
            val display = remark.ifBlank { nickname }.ifBlank { wxid }
            wxid to display
        }.toMap()
    }

    private fun formatTime(epochMillis: Long): String = runCatching {
        if (epochMillis <= 0L) {
            ""
        } else {
            timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
        }
    }.getOrDefault("")

    // ─── 设置面板 ─────────────────────────────────────────────────────────────

    @Composable
    override fun Ui() {
        var countText by remember { mutableStateOf(summaryCount.toString()) }
        var promptText by remember { mutableStateOf(summaryPrompt) }

        ListItem(
            headlineContent = { Text("总结条数") },
            supportingContent = {
                Text("读取最近 $countText 条消息（$MIN_COUNT–$MAX_COUNT）")
            },
            trailingContent = {
                OutlinedTextField(
                    value = countText,
                    onValueChange = { input ->
                        val digits = input.filter { it.isDigit() }.take(3)
                        countText = digits
                        digits.toIntOrNull()?.let { parsed ->
                            summaryCount = parsed.coerceIn(MIN_COUNT, MAX_COUNT)
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(0.35f)
                )
            }
        )

        ListItem(
            headlineContent = { Text("总结提示词") },
            supportingContent = {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { input ->
                        promptText = input
                        summaryPrompt = input
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )

        ListItem(
            headlineContent = { Text("接口") },
            supportingContent = {
                Text("复用「AI 自动回复」里的接口地址 / 模型 / API Key，无需重复配置")
            }
        )
    }
}