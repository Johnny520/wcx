package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Checklist
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 实验室：聊天页的两个轻量工具，入口都在输入栏长按菜单里。
 *
 * 共用一套「读最近消息 → 拼上下文 → 调接口」的流程，只是提示词不同；
 * 两者都不改微信数据，只把结果展示出来供复制，误操作成本为零。
 */

// ── 共用：读最近消息 ─────────────────────────────────────────────────────────

private object AiChatContext {

    private const val TAG = "AiChatContext"

    /** 送进模型的上下文上限（字符）。 */
    private const val MAX_CHARS = 6000

    /** 单条消息最多保留的字数，防止一条长文吃掉全部预算。 */
    private const val MAX_ONE_CHARS = 300

    /**
     * 读最近 [count] 条消息并拼成「我：xxx / 对方：xxx」的形式。
     *
     * 群聊消息的 content 常带 `wxid:\n` 前缀，这里直接剥掉，不把 wxid 送给服务商；
     * 发言人只区分「我」与「对方」，够用来判断语气与上下文。
     */
    fun lines(talker: String, count: Int): List<String> {
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT createTime, isSend, type, content FROM message WHERE talker = ? " +
                        "ORDER BY createTime DESC LIMIT $count",
                arrayOf<Any>(talker),
            )
        }.getOrElse { error ->
            WeLogger.e(TAG, "查询 message 表失败", error)
            return emptyList()
        }
        if (rows.isEmpty()) return emptyList()

        val collected = ArrayList<String>()
        var budget = MAX_CHARS
        for (row in rows) {
            val type = (row["type"] as? Number)?.toInt() ?: 0
            val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
            val content = row["content"] as? String ?: ""
            val body = bodyOf(type, content)
            if (body.isBlank()) continue
            val speaker = if (isSend != 0) "我" else "对方"
            val line = "$speaker：$body"
            if (line.length + 1 > budget) break
            collected.add(line)
            budget -= line.length + 1
        }
        return collected.asReversed()
    }

    private fun bodyOf(typeCode: Int, raw: String): String {
        val type = MessageType.fromCode(typeCode)
        val text = if (type?.isText == true) {
            val stripped = stripSenderPrefix(raw).trim()
            // 引用消息的 content 是 XML，交给模型没有意义。
            if (stripped.startsWith("<")) "[${type.displayName}]" else stripped
        } else {
            "[${type?.displayName ?: "非文本"}]"
        }
        if (text.isBlank()) return ""
        return if (text.length > MAX_ONE_CHARS) text.take(MAX_ONE_CHARS) + "…" else text
    }

    private fun stripSenderPrefix(content: String): String {
        val marker = content.indexOf(":\n")
        if (marker <= 0 || marker > 40) return content
        return content.substring(marker + 2)
    }

    fun readyOrToast(context: Context): String? {
        if (!AiConfig.isConfigured) {
            showToast(context, "请先到「AI 助手 → 设置 → AI 接口」填写配置")
            return null
        }
        val talker = WeCurrentConversationApi.value
        if (talker.isBlank()) {
            showToast(context, "没识别到当前聊天，请先打开要处理的聊天")
            return null
        }
        return talker
    }
}

/** 结果弹窗：纯文本 + 复制，不改任何微信数据。 */
@Composable
private fun ResultDialog(
    title: String,
    loadingText: String,
    copiable: Boolean,
    run: suspend () -> Result<String>,
    onDismiss: () -> Unit,
) {
    var content by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { run() }
        loading = false
        result.onSuccess { content = it }.onFailure { errorText = it.message ?: "未知错误" }
    }

    AlertDialogContent(
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    loading -> Text(loadingText, style = MaterialTheme.typography.bodyMedium)
                    errorText.isNotBlank() -> Text("失败：$errorText", style = MaterialTheme.typography.bodyMedium)
                    content.isBlank() -> Text("没有可用的结果", style = MaterialTheme.typography.bodyMedium)
                    else -> Text(content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("关闭") } },
        confirmButton = {
            if (copiable && content.isNotBlank()) {
                TextButton(
                    onClick = {
                        copyToClipboard(context, content)
                        copied = true
                        showToast("已复制")
                    },
                ) { Text(if (copied) "已复制" else "复制") }
            }
        },
    )
}

// ── 功能一：待办提取 ─────────────────────────────────────────────────────────

@Feature(
    name = "AI 待办提取",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中把当前聊天里的待办事项整理成清单（只展示，不改聊天）",
)
object AiTodoExtract : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiTodoExtract"

    private const val COUNT = 120

    private const val SYSTEM_PROMPT =
        "你是会议纪要助手，只从给定聊天记录里提取明确的待办事项。" +
                "不要编造、不要补全记录里没有的信息。"

    private val USER_PROMPT =
        "从下面的聊天记录里提取待办事项，用简体中文输出：\n" +
                "1. 每条一行，格式「事项 —— 谁负责（没有就写 未明确） —— 时间（没有就写 未明确）」；\n" +
                "2. 按紧急程度排序；\n" +
                "3. 没有明显待办就只回一句「没有明显的待办」。\n\n===== 聊天记录 =====\n"

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "ai_todo_extract",
            icon = MaterialSymbols.Outlined.Checklist,
            label = "AI 提取待办",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                runCatching { showDialog(context) }
                    .onFailure { error ->
                        WeLogger.e(TAG, "打开待办弹窗失败", error)
                        showToast(context, "AI 提取待办失败：${error.message ?: "未知错误"}")
                    }
            },
        ),
    )

    private fun showDialog(context: Context) {
        val talker = AiChatContext.readyOrToast(context) ?: return
        showComposeDialog(context) {
            ResultDialog(
                title = "AI 待办（最近 $COUNT 条）",
                loadingText = "正在读聊天记录并整理待办…",
                copiable = true,
                run = { run(talker) },
                onDismiss = { onDismiss() },
            )
        }
    }

    private fun run(talker: String): Result<String> {
        val lines = AiChatContext.lines(talker, COUNT)
        if (lines.isEmpty()) return Result.failure(IllegalStateException("这个聊天没有可读的文字消息"))
        val user = USER_PROMPT + lines.joinToString("\n")
        return TranslateSupport.chatCompletion(SYSTEM_PROMPT, user)
    }
}

// ── 功能二：帮我回复 ─────────────────────────────────────────────────────────

@Feature(
    name = "AI 帮我回复",
    categories = ["聊天"],
    description = "在聊天输入栏长按菜单中根据上下文生成 3 条候选回复，复制即可发送",
)
object AiReplyDraft : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiReplyDraft"

    private const val COUNT = 40

    private const val SYSTEM_PROMPT =
        "你是一个帮人起草微信回复的助手：口语、简短、不用书面语，不解释、不加引号。"

    private val USER_PROMPT =
        "根据下面的聊天记录，替我写 3 条可以直接发送的回复候选，用简体中文，每条不超过 40 字：\n" +
                "1. 先理解对方最后一句在问什么或要什么；\n" +
                "2. 三条语气不同（干脆的 / 委婉的 / 更热情的），各自一行，用「1. 2. 3.」编号；\n" +
                "3. 不要编造我不知道的事实。\n\n===== 聊天记录 =====\n"

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "ai_reply_draft",
            icon = MaterialSymbols.Outlined.Chat,
            label = "AI 帮我想怎么回",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                runCatching { showDialog(context) }
                    .onFailure { error ->
                        WeLogger.e(TAG, "打开回复弹窗失败", error)
                        showToast(context, "AI 起草失败：${error.message ?: "未知错误"}")
                    }
            },
        ),
    )

    private fun showDialog(context: Context) {
        val talker = AiChatContext.readyOrToast(context) ?: return
        showComposeDialog(context) {
            ResultDialog(
                title = "AI 回复候选",
                loadingText = "正在读上下文并起草回复…",
                copiable = true,
                run = { run(talker) },
                onDismiss = { onDismiss() },
            )
        }
    }

    private fun run(talker: String): Result<String> {
        val lines = AiChatContext.lines(talker, COUNT)
        if (lines.isEmpty()) return Result.failure(IllegalStateException("这个聊天没有可读的文字消息"))
        val user = USER_PROMPT + lines.joinToString("\n")
        return TranslateSupport.chatCompletion(SYSTEM_PROMPT, user)
    }
}
