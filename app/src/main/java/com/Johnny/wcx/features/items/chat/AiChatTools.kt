package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Checklist
import com.composables.icons.materialsymbols.outlined.Campaign
import com.composables.icons.materialsymbols.outlined.Edit_note
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AiInputField
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 实验室：聊天场景的轻量工具，入口都在**输入栏长按菜单**里
 * （微信输入框长按 → AI 工具），随手可用，不需要记路径。
 *
 * 四个工具都只读消息、只展示结果，不改任何微信数据：
 * - AI 提取待办：整理「事项 — 谁 — 何时」
 * - AI 帮我想怎么回：3 条候选回复
 * - AI 问答：问一句话，它翻记录回答
 * - AI 写群公告：给主题，出公告 + 欢迎语
 *
 * 朋友圈文案（[AiMomentsCopy]）没有聊天上下文，入口在「AI 助手 → 设置 → AI 工具」。
 */

// ── 共用：读最近消息 ─────────────────────────────────────────────────────────

/** 聊天上下文读取（工作台与各工具共用）。 */
internal object AiChatContext {

    private const val TAG = "AiChatContext"

    private const val MAX_CHARS = 6000

    private const val MAX_ONE_CHARS = 300

    /**
     * 读最近 [count] 条消息并拼成「我：xxx / 对方：xxx」。
     *
     * 群聊 content 常带 `wxid:` 前缀，剥掉后再送模型，避免把 wxid 发给服务商；
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

    /** 统一前置检查：没配置 / 没会话时给 toast 并返回 null。 */
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

/**
 * 通用结果弹窗。
 *
 * [inputLabel] 为空 = 打开即跑（如润色、待办）；非空 = 先让用户填一句再跑（如问答、公告）。
 * 结果统一可复制，不改任何微信数据。
 */
@Composable
internal fun AiRunDialog(
    title: String,
    loadingText: String,
    onDismiss: () -> Unit,
    inputLabel: String? = null,
    inputPlaceholder: String = "",
    copyEnabled: Boolean = true,
    run: suspend (String) -> Result<String>,
) {
    var input by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(inputLabel == null) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun start() {
        if (loading) return
        if (inputLabel != null && input.isBlank()) {
            errorText = "先输入内容再生成"
            return
        }
        loading = true
        content = ""
        errorText = ""
        copied = false
        val value = input
        scope.launch {
            val result = withContext(Dispatchers.IO) { run(value) }
            loading = false
            result.onSuccess { content = it }.onFailure { errorText = it.message ?: "未知错误" }
        }
    }

    LaunchedEffect(Unit) {
        if (inputLabel == null) start()
    }

    AlertDialogContent(
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (inputLabel != null) {
                    AiInputField(
                        label = inputLabel,
                        value = input,
                        placeholder = inputPlaceholder,
                        onChange = { input = it; errorText = "" },
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    when {
                        loading -> Text(loadingText, style = MaterialTheme.typography.bodyMedium)
                        errorText.isNotBlank() -> Text("失败：$errorText", style = MaterialTheme.typography.bodyMedium)
                        content.isNotBlank() -> Text(content, style = MaterialTheme.typography.bodyMedium)
                        inputLabel != null -> Text(
                            "填好后点「生成」。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("关闭") } },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (copyEnabled && content.isNotBlank()) {
                    TextButton(
                        onClick = {
                            copyToClipboard(content)
                            copied = true
                            showToast("已复制")
                        },
                    ) { Text(if (copied) "已复制" else "复制") }
                }
                if (inputLabel != null) {
                    TextButton(onClick = { start() }, enabled = !loading) {
                        Text(if (loading) "生成中…" else "生成")
                    }
                }
            }
        },
    )
}

/** 注册到输入栏菜单的公共壳：只关心「能不能点 / 点了做什么」。 */
private fun inputBarProvider(
    menuId: String,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    support: () -> Boolean,
    onClick: (Context) -> Unit,
): WeChatInputBarMenuApi.IActionItemsProvider =
    object : WeChatInputBarMenuApi.IActionItemsProvider {
        override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = menuId,
                icon = icon,
                label = label,
                isSupported = { _, _ -> support() },
                onClick = { context, _ ->
                    runCatching { onClick(context) }
                        .onFailure { error ->
                            WeLogger.e(tag, "打开弹窗失败", error)
                            showToast(context, "$label 失败：${error.message ?: "未知错误"}")
                        }
                },
            ),
        )
    }

// ── 工具一：待办提取 ─────────────────────────────────────────────────────────

@Feature(
    name = "AI 待办提取",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里把当前聊天整理成待办清单（只展示，不改聊天）",
)
object AiTodoExtract : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiTodoExtract"
    private const val COUNT = 120

    private const val SYSTEM_PROMPT =
        "你是会议纪要助手，只从给定聊天记录里提取明确的待办事项。不要编造、不要补全记录里没有的信息。"

    private val USER_PROMPT =
        "从下面的聊天记录里提取待办事项，用简体中文输出：\n" +
                "1. 每条一行，格式「事项 —— 谁负责（没有就写 未明确） —— 时间（没有就写 未明确）」；\n" +
                "2. 按紧急程度排序；\n" +
                "3. 没有明显待办就只回一句「没有明显的待办」。\n\n===== 聊天记录 =====\n"

    private val delegate = inputBarProvider(
        menuId = "ai_todo_extract",
        label = "AI 提取待办",
        icon = MaterialSymbols.Outlined.Checklist,
        tag = TAG,
        support = { true },
    ) { context -> showDialog(context) }

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = delegate.getActionItems()

    private fun showDialog(context: Context) {
        val talker = AiChatContext.readyOrToast(context) ?: return
        showComposeDialog(context) {
            AiRunDialog(
                title = "AI 待办（最近 $COUNT 条）",
                loadingText = "正在读聊天记录并整理待办…",
                onDismiss = { onDismiss() },
            ) { _ ->
                val lines = AiChatContext.lines(talker, COUNT)
                if (lines.isEmpty()) {
                    Result.failure(IllegalStateException("这个聊天没有可读的文字消息"))
                } else {
                    TranslateSupport.chatCompletion(SYSTEM_PROMPT, USER_PROMPT + lines.joinToString("\n"))
                }
            }
        }
    }
}

// ── 工具二：帮我回复 ─────────────────────────────────────────────────────────

@Feature(
    name = "AI 帮我回复",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里按上下文生成 3 条候选回复，复制即可发送",
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

    private val delegate = inputBarProvider(
        menuId = "ai_reply_draft",
        label = "AI 帮我想怎么回",
        icon = MaterialSymbols.Outlined.Chat,
        tag = TAG,
        support = { true },
    ) { context -> showDialog(context) }

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = delegate.getActionItems()

    private fun showDialog(context: Context) {
        val talker = AiChatContext.readyOrToast(context) ?: return
        showComposeDialog(context) {
            AiRunDialog(
                title = "AI 回复候选",
                loadingText = "正在读上下文并起草回复…",
                onDismiss = { onDismiss() },
            ) { _ ->
                val lines = AiChatContext.lines(talker, COUNT)
                if (lines.isEmpty()) {
                    Result.failure(IllegalStateException("这个聊天没有可读的文字消息"))
                } else {
                    TranslateSupport.chatCompletion(SYSTEM_PROMPT, USER_PROMPT + lines.joinToString("\n"))
                }
            }
        }
    }
}

// ── 工具三：聊天问答 ─────────────────────────────────────────────────────────

@Feature(
    name = "AI 聊天问答",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里问一句话，AI 翻当前聊天记录回答（问某个承诺、时间、金额最有用）",
)
object AiChatQa : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiChatQa"
    private const val COUNT = 300

    private const val SYSTEM_PROMPT =
        "你是聊天记录问答助手：只依据给出的聊天记录回答，记录里没有就直说「记录里没提到」，不要推测。"

    private val delegate = inputBarProvider(
        menuId = "ai_chat_qa",
        label = "AI 问答（问这段聊天）",
        icon = MaterialSymbols.Outlined.Edit_note,
        tag = TAG,
        support = { true },
    ) { context -> showDialog(context) }

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = delegate.getActionItems()

    private fun showDialog(context: Context) {
        val talker = AiChatContext.readyOrToast(context) ?: return
        showComposeDialog(context) {
            AiRunDialog(
                title = "AI 问答（最近 $COUNT 条）",
                loadingText = "正在读聊天记录并思考…",
                onDismiss = { onDismiss() },
                inputLabel = "你想知道什么",
                inputPlaceholder = "例如：他上次说什么时候交方案？",
            ) { question ->
                val lines = AiChatContext.lines(talker, COUNT)
                if (lines.isEmpty()) {
                    Result.failure(IllegalStateException("这个聊天没有可读的文字消息"))
                } else {
                    TranslateSupport.chatCompletion(
                        SYSTEM_PROMPT,
                        "问题：$question\n\n===== 聊天记录 =====\n" + lines.joinToString("\n"),
                    )
                }
            }
        }
    }
}

// ── 工具四：群公告 / 欢迎语 ──────────────────────────────────────────────────

@Feature(
    name = "AI 写群公告",
    categories = ["聊天"],
    description = "群聊输入栏长按菜单里给个主题，生成群公告和新成员欢迎语（只展示，需自己复制发送）",
)
object AiGroupAnnounce : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiGroupAnnounce"

    private const val SYSTEM_PROMPT =
        "你是微信群运营助手，写简洁、有礼、不啰嗦的中文通知，不用表情符号。"

    private val delegate = inputBarProvider(
        menuId = "ai_group_announce",
        label = "AI 写群公告",
        icon = MaterialSymbols.Outlined.Campaign,
        tag = TAG,
        // 只在群聊里出现，单聊点了没有意义。
        support = { WeCurrentConversationApi.value.isGroupChatWxId },
    ) { context -> showDialog(context) }

    override fun onEnable() {
        runCatching { WeChatInputBarMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册输入栏菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatInputBarMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销输入栏菜单项失败", it) }
    }

    override fun getActionItems(): List<WeChatInputBarMenuApi.ActionItem> = delegate.getActionItems()

    private fun showDialog(context: Context) {
        if (!AiConfig.isConfigured) {
            showToast(context, "请先到「AI 助手 → 设置 → AI 接口」填写配置")
            return
        }
        showComposeDialog(context) {
            AiRunDialog(
                title = "AI 写群公告",
                loadingText = "正在生成…",
                onDismiss = { onDismiss() },
                inputLabel = "公告主题",
                inputPlaceholder = "例如：本周六上午十点团建，群里接龙报名",
            ) { topic ->
                TranslateSupport.chatCompletion(
                    SYSTEM_PROMPT,
                    "主题：$topic\n\n请输出两段：\n" +
                            "公告：不超过 120 字，开头不要写「公告」二字；\n" +
                            "欢迎语：不超过 60 字，用来说给新入群的人。",
                )
            }
        }
    }
}

// ── 工具五：朋友圈文案（入口在 AI 助手的设置页） ─────────────────────────────

@Feature(
    name = "AI 朋友圈文案",
    categories = ["聊天"],
    description = "给关键词生成 3 条朋友圈文案（入口：AI 助手 → 设置 → AI 工具）",
)
object AiMomentsCopy : ApiFeature() {

    private const val TAG = "AiMomentsCopy"

    private const val SYSTEM_PROMPT =
        "你是文案助手，写朋友圈文案：口语、真实、不夸张，最多一个表情。"

    /** 由「AI 助手 → 设置 → AI 工具」调用；没有聊天上下文，因此不读消息。 */
    fun showDialog(context: Context) {
        if (!AiConfig.isConfigured) {
            showToast(context, "请先到「AI 助手 → 设置 → AI 接口」填写配置")
            return
        }
        showComposeDialog(context) {
            AiRunDialog(
                title = "AI 朋友圈文案",
                loadingText = "正在写…",
                onDismiss = { onDismiss() },
                inputLabel = "想写什么",
                inputPlaceholder = "例如：周末爬山，天气很好",
            ) { topic ->
                TranslateSupport.chatCompletion(
                    SYSTEM_PROMPT,
                    "关键词：$topic\n\n请写 3 条朋友圈文案，每条不超过 40 字，编号 1./2./3.，不要解释。",
                )
            }
        }
    }
}
