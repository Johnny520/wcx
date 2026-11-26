package com.Johnny.wcx.features.items.chat

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.LaunchedEffect
import android.graphics.Bitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AiInputField
import com.Johnny.wcx.ui.style.SoftCard
import com.Johnny.wcx.ui.style.WxPalette
import com.Johnny.wcx.ui.utils.RequestFullScreenDialog
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * AI 工作台：聊天页双击空白处弹出的唯一入口。
 *
 * ## 为什么收成一个入口
 *
 * 之前 AI 能力散在输入栏长按、消息长按、朋友圈长按、AI 页里 —— 用户记不住哪个功能在哪，
 * 又要先点几个层级才用得上。现在统一成：**聊天页（群聊/私聊）双击 → 这个面板**，
 * 点一下任务就在当前会话上跑，结果当场复制或直接发出去。
 *
 * 留在 AI 页的只剩「管理类」：添加好友档案、添加群、开关与接口配置。
 *
 * ## 约定
 *
 * - 所有任务都只读本地聊天记录 + 调用用户自己配置的 AI 接口，不改任何微信数据；
 * - 任何一步失败都给可读文案，不静默；
 * - 总开关关着时双击不响应（由手势层拦住），面板本身也再判一次。
 */
object AiWorkbench {

    private const val TAG = "AiWorkbench"

    /** 群总结的自定义提示词：留空就用内置模板。 */
    private const val KEY_GROUP_PROMPT = "ai_group_summary_prompt"

    /** 总结类任务一次送多少条消息。多了会超上下文，少了不够判断。 */
    private const val SUMMARY_LINES = 150

    /** 双击后打开工作台。 */
    fun show(activity: Activity, talker: String, title: String, isGroup: Boolean) {
        if (talker.isBlank()) return
        if (!AiConfig.isMasterEnabled) {
            showToast("AI 助手总开关已关闭，请到「AI 助手 → 设置」里打开")
            return
        }
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            showComposeDialog(activity) {
                AiWorkbenchDialog(
                    talker = talker,
                    title = title,
                    isGroup = isGroup,
                    onDismiss = { onDismiss() },
                )
            }
        }
    }

    // ── 任务定义 ────────────────────────────────────────────────────────────

    /** 面板上的一个任务。 */
    internal enum class Task(
        val label: String,
        val emoji: String,
        val needsInput: Boolean = false,
        val inputHint: String = "",
        val groupOnly: Boolean = false,
    ) {
        SUMMARY("总结这段聊天", "🧾"),
        TODO("提取待办", "✅"),
        REPLY("帮我回", "💬"),
        ASK("追问 AI", "❓", needsInput = true, inputHint = "想问什么，比如「他们刚才在争什么」"),
        GROUP_SUMMARY("群总结", "📋", groupOnly = true),
        ANNOUNCE("写群公告", "📢", needsInput = true, inputHint = "公告主题，比如「周六组织爬山」", groupOnly = true),
        UNREAD("未读速览", "📮"),
        TODAY("今日摘要", "🌤"),
    }

    /** 该会话下能用的任务（私聊里不显示群专属）。 */
    internal fun tasksFor(isGroup: Boolean): List<Task> =
        Task.values().filter { isGroup || !it.groupOnly }

    // ── 执行 ────────────────────────────────────────────────────────────────

    /**
     * 跑一个任务，返回可直接展示的文本。
     *
     * 全部在 IO 线程执行（读库 + 网络），调用方负责切回主线程。
     */
    internal fun run(task: Task, talker: String, title: String, isGroup: Boolean, input: String): Result<String> =
        runCatching {
            val (system, user) = when (task) {
                Task.SUMMARY -> system to transcriptBlock(talker, title, SUMMARY_LINES) +
                        "\n\n请把上面的聊天整理成中文总结：3-6 条要点，最后一行给出「整体氛围」一句话。不要罗列每条消息。"

                Task.TODO -> system to transcriptBlock(talker, title, SUMMARY_LINES) +
                        "\n\n请从上面的聊天里提取【待办事项】：只保留真的要做的事，每条一行，格式「- 事项（谁 / 什么时候，若聊天里提到）」。没有就给「没有明确待办」。"

                Task.REPLY -> system to transcriptBlock(talker, title, 40) +
                        "\n\n请以「我」的身份，给最后一条消息写 3 条可以直接发出去的回复，语气自然、简短。格式：1. 2. 3. 只给回复内容。"

                Task.ASK -> system to transcriptBlock(talker, title, SUMMARY_LINES) +
                        "\n\n我的问题：$input\n请只回答这个问题，回答要具体、可执行。"

                Task.GROUP_SUMMARY -> system to transcriptBlock(talker, title, 200) +
                        "\n\n" + groupSummaryTemplate()

                Task.ANNOUNCE -> system to "群名：$title\n群公告主题：$input\n\n" +
                        "请写一条可以直接发到群里的公告：3-5 行，开头一句说清是什么事，中间给时间/地点/要求，结尾一句行动号召。不要用 Markdown 标题。"

                Task.UNREAD -> system to unreadBlock()

                Task.TODAY -> system to todayBlock()
            }
            val messages = buildList {
                add(AiConfig.ChatMessage("system", system))
                add(AiConfig.ChatMessage("user", user))
            }
            val result = AiConfig.chatWithTools(messages = messages)
            val text = result.text?.trim()
            if (text.isNullOrBlank()) {
                throw IOException(result.error ?: "AI 没有返回内容")
            }
            text
        }.onFailure { WeLogger.e(TAG, "任务 ${task.label} 执行失败", it) }

    private val system: String =
        "你是微信聊天助理，输出中文、口语化、不要客套话，不要解释你在做什么，直接给结果。"

    private fun transcriptBlock(talker: String, title: String, count: Int): String {
        val lines = AiChatContext.lines(talker, count)
        if (lines.isEmpty()) throw IOException("这个会话还没有可读的文字消息")
        return "会话：$title\n----\n" + lines.joinToString("\n") + "\n----"
    }

    /** 群总结模板：用户可在面板里改成自己的。 */
    internal fun groupSummaryTemplate(): String {
        val custom = runCatching { WePrefs.getStringOrDef(KEY_GROUP_PROMPT, "") }.getOrDefault("")
        if (custom.isNotBlank()) return custom.trim()
        return "请把这个群聊总结成一份可以直接发到群里的「群总结」：\n" +
                "1. 今天聊了什么（3-5 条要点）\n" +
                "2. 有结论/决定的事\n" +
                "3. 需要谁去做什么（待办）\n" +
                "4. 值得记住的金句或梗（最多 2 条）\n" +
                "每条一行，不要用 Markdown 标题，控制在 400 字以内。"
    }

    internal fun saveGroupSummaryTemplate(text: String) {
        runCatching { WePrefs.putString(KEY_GROUP_PROMPT, text.trim()) }
            .onFailure { WeLogger.e(TAG, "保存群总结提示词失败", it) }
    }

    private fun unreadBlock(): String {
        val unread = AiDigestSource.unreadTalkers(15)
        if (unread.isEmpty()) throw IOException("现在没有未读会话")
        val builder = StringBuilder("未读会话（名称 / 未读数 / 最近几条）：\n")
        unread.forEach { (talker, count) ->
            val name = AiDigestSource.displayName(talker)
            val lines = AiChatContext.lines(talker, 6)
            builder.append("\n【$name】未读 $count 条\n")
            if (lines.isEmpty()) builder.append("（没有可读文字）\n") else lines.forEach { builder.append(it).append('\n') }
        }
        builder.append("\n请给每个会话一句话说清「要不要马上看」，并按紧急程度排序输出。")
        return builder.toString()
    }

    private fun todayBlock(): String {
        val talkers = AiDigestSource.activeTodayTalkers(15)
        if (talkers.isEmpty()) throw IOException("今天还没有新消息")
        val builder = StringBuilder("今天有动静的会话：\n")
        talkers.forEach { talker ->
            val name = AiDigestSource.displayName(talker)
            val lines = AiChatContext.lines(talker, 5)
            builder.append("\n【$name】\n")
            if (lines.isEmpty()) builder.append("（没有可读文字）\n") else lines.forEach { builder.append(it).append('\n') }
        }
        builder.append("\n请按「需要我今天处理」和「只是闲聊」两类归纳，每类不超过 5 条，每条一行。")
        return builder.toString()
    }

    // ── 发送 / 复制 ─────────────────────────────────────────────────────────

    /** 页脚时间戳，例如「11-26 12:30」。 */
    internal fun timeLabel(): String =
        try {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date())
        } catch (_: Throwable) {
            ""
        }

    internal fun sendToChat(talker: String, text: String): Boolean = runCatching {
        val ok = WeMessageApi.sendText(talker, text)
        if (!ok) throw IOException("发送失败（微信发送接口不可用）")
        true
    }.getOrElse { error ->
        WeLogger.e(TAG, "发送到会话失败", error)
        false
    }
}

/**
 * 工作台面板。
 *
 * 布局：顶部标题与会话名 → 任务网格 → 结果区（可滚动）→ 复制 / 发送。
 * 群总结多一块「自定义提示词」入口。
 */
@Composable
private fun AiWorkbenchDialog(
    talker: String,
    title: String,
    isGroup: Boolean,
    onDismiss: () -> Unit,
) {
    RequestFullScreenDialog()

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val tasks = remember(isGroup) { AiWorkbench.tasksFor(isGroup) }

    var running by remember { mutableStateOf<AiWorkbench.Task?>(null) }
    var loading by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var pendingInput by remember { mutableStateOf<AiWorkbench.Task?>(null) }
    var showPromptEditor by remember { mutableStateOf(false) }
    var showImagePanel by remember { mutableStateOf(false) }
    var promptDraft by remember { mutableStateOf(AiWorkbench.groupSummaryTemplate()) }

    fun start(task: AiWorkbench.Task, value: String) {
        if (loading) return
        loading = true
        running = task
        content = ""
        errorText = ""
        pendingInput = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AiWorkbench.run(task, talker, title, isGroup, value)
            }
            loading = false
            result.onSuccess { content = it }
                .onFailure { errorText = it.message ?: "未知错误" }
        }
    }

    fun onTaskClick(task: AiWorkbench.Task) {
        if (loading) return
        if (task.needsInput) {
            pendingInput = task
            input = ""
            return
        }
        start(task, "")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .background(WxPalette.pageBackground())
                // 吃掉点击，避免点面板内部把面板关掉
                .clickable(enabled = false) {}
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // 顶部
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 工作台", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = WxPalette.textPrimary())
                    Text(
                        text = (if (isGroup) "群聊 · " else "私聊 · ") + title,
                        fontSize = 12.sp,
                        color = WxPalette.textSecondary(),
                    )
                }
                Text(
                    text = "关闭",
                    fontSize = 13.sp,
                    color = WxPalette.textSecondary(),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(WxPalette.insetSurface())
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            // 任务网格：两列
            SoftCard(contentPadding = 10) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tasks.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { task ->
                                TaskChip(
                                    task = task,
                                    active = running == task,
                                    enabled = !loading,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onTaskClick(task) },
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (isGroup) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { promptDraft = AiWorkbench.groupSummaryTemplate(); showPromptEditor = true }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("群总结提示词", fontSize = 12.sp, color = WxPalette.Accent)
                            Spacer(Modifier.weight(1f))
                            Text("自定义 ›", fontSize = 12.sp, color = WxPalette.textTertiary())
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 需要输入的任务：输入框 + 生成
            pendingInput?.let { task ->
                SoftCard(contentPadding = 12) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiInputField(
                            label = task.inputHint.ifBlank { "补充一句" },
                            value = input,
                            placeholder = "在这里输入",
                            onChange = { input = it },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("生成") { start(task, input) }
                            PillButton("取消", secondary = true) { pendingInput = null }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 结果区
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(WxPalette.cardBackground()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        loading -> Text(
                            text = "正在生成" + (running?.let { "（${it.label}）" } ?: "") + "…",
                            fontSize = 13.sp,
                            color = WxPalette.textSecondary(),
                        )

                        errorText.isNotBlank() -> Text("失败：$errorText", fontSize = 13.sp, color = Color(0xFFE04B4B))
                        content.isNotBlank() -> Text(content, fontSize = 14.sp, color = WxPalette.textPrimary())
                        else -> Text(
                            text = "点上面的任务，就在这个会话上生成。\n结果可以复制，也可以直接发到当前聊天。",
                            fontSize = 12.sp,
                            color = WxPalette.textSecondary(),
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 底部操作
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("复制", enabled = content.isNotBlank()) {
                    copyToClipboard(content)
                    showToast("已复制")
                }
                PillButton("生成图片", enabled = content.isNotBlank()) { showImagePanel = true }
                PillButton("发送到本会话", enabled = content.isNotBlank()) {
                    val text = content
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { AiWorkbench.sendToChat(talker, text) }
                        showToast(if (ok) "已发送" else "发送失败，已复制到剪贴板")
                        if (!ok) copyToClipboard(text)
                    }
                }
                PillButton("重跑", enabled = !loading && running != null, secondary = true) {
                    running?.let { start(it, input) }
                }
            }
        }
    }

    // 分享长图：选样式 → 预览 → 保存 / 发送
    if (showImagePanel) {
        ShareImagePanel(
            talker = talker,
            title = title,
            body = content,
            onDismiss = { showImagePanel = false },
        )
    }

    // 群总结提示词编辑
    if (showPromptEditor) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x88000000))
                .clickable { showPromptEditor = false },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(WxPalette.pageBackground())
                    .clickable(enabled = false) {}
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("群总结提示词", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = WxPalette.textPrimary())
                Text(
                    "留空用内置模板；这里写的内容会原样作为「群总结」的指令。",
                    fontSize = 11.sp,
                    color = WxPalette.textSecondary(),
                )
                AiInputField(
                    label = "提示词",
                    value = promptDraft,
                    placeholder = "例如：总结成 5 条要点 + 一条今日金句",
                    onChange = { promptDraft = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("保存") {
                        AiWorkbench.saveGroupSummaryTemplate(promptDraft)
                        showPromptEditor = false
                        showToast("已保存")
                    }
                    PillButton("恢复默认", secondary = true) {
                        AiWorkbench.saveGroupSummaryTemplate("")
                        promptDraft = AiWorkbench.groupSummaryTemplate()
                        showToast("已恢复默认")
                    }
                    PillButton("取消", secondary = true) { showPromptEditor = false }
                }
            }
        }
    }
}

@Composable
private fun TaskChip(
    task: AiWorkbench.Task,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) WxPalette.primarySoft() else WxPalette.insetSurface())
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(task.emoji, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = task.label,
            fontSize = 13.sp,
            color = if (active) WxPalette.Primary else WxPalette.textPrimary(),
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    enabled: Boolean = true,
    secondary: Boolean = false,
    onClick: () -> Unit,
) {
    val background = when {
        !enabled -> WxPalette.insetSurface()
        secondary -> WxPalette.insetSurface()
        else -> WxPalette.primarySoft()
    }
    val color = when {
        !enabled -> WxPalette.textTertiary()
        secondary -> WxPalette.textSecondary()
        else -> WxPalette.Primary
    }
    Text(
        text = text,
        fontSize = 13.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * 分享长图面板：四套样式实时切换，预览后可以存相册或直接发到当前会话。
 *
 * 渲染在 Default 线程做（长文折行 + 大位图），切换样式会重新画一张并回收上一张。
 */
@Composable
private fun ShareImagePanel(
    talker: String,
    title: String,
    body: String,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(AiShareImage.Style.CLASSIC) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var rendering by remember { mutableStateOf(true) }

    LaunchedEffect(style, body) {
        rendering = true
        val old = bitmap
        val next = withContext(Dispatchers.Default) {
            runCatching { AiShareImage.render(style, "$title · AI 总结", AiWorkbench.timeLabel(), body) }
                .getOrNull()
        }
        if (old != null && old !== next && !old.isRecycled) runCatching { old.recycle() }
        bitmap = next
        rendering = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(WxPalette.pageBackground())
                .clickable(enabled = false) {}
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("生成分享图", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = WxPalette.textPrimary())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiShareImage.Style.values().forEach { item ->
                    val selected = item == style
                    Text(
                        text = item.label,
                        fontSize = 12.sp,
                        color = if (selected) WxPalette.Primary else WxPalette.textSecondary(),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) WxPalette.primarySoft() else WxPalette.insetSurface())
                            .clickable { style = item }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(WxPalette.cardBackground()),
                contentAlignment = Alignment.Center,
            ) {
                val image = bitmap
                when {
                    rendering -> Text("正在生成图片…", fontSize = 13.sp, color = WxPalette.textSecondary())
                    image == null -> Text("生成失败，换个样式再试", fontSize = 13.sp, color = Color(0xFFE04B4B))
                    else -> androidx.compose.foundation.Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = "分享图预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("保存到相册", enabled = bitmap != null) {
                    val image = bitmap ?: return@PillButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            AiShareImage.saveToGallery(context, image, "wcx-ai-${System.currentTimeMillis()}")
                        }
                        showToast(if (ok) "已保存到相册" else "保存失败")
                    }
                }
                PillButton("发送到本会话", enabled = bitmap != null) {
                    val image = bitmap ?: return@PillButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            val file = AiShareImage.saveTemp(context, image) ?: return@withContext false
                            AiShareImage.sendToChat(talker, file)
                        }
                        showToast(if (ok) "已发送" else "发送失败，可先保存到相册再发")
                    }
                }
                PillButton("关闭", secondary = true) { onDismiss() }
            }
        }
    }
}
