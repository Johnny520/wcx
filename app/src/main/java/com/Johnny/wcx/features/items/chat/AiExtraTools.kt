package com.Johnny.wcx.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.History
import com.composables.icons.materialsymbols.outlined.Schedule
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Summarize
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId

/**
 * AI 实验室：三个「跨会话 / 时间维度」的工具。
 *
 * 入口都在聊天页长按输入框，都不改微信数据：
 * - AI 周报：本周各会话进展汇总成一份周报
 * - AI 搜聊天：给关键词，跨所有会话找出谁提过、说了什么
 * - AI 群纪要：群聊里把最近消息整理成会议纪要（结论 / 待办 / 待确认）
 */

// ── 工具九：周报 ─────────────────────────────────────────────────────────────

@Feature(
    name = "AI 周报",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里把本周各会话的进展汇总成一份周报（写工作总结很有用）",
)
object AiWeeklyReport : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiWeeklyReport"
    private const val MAX_CONVERSATIONS = 10
    private const val PER_CONVERSATION = 15

    private const val SYSTEM_PROMPT =
        "你是周报助手：只根据聊天记录归纳事实，不编造、不夸大，输出简洁的中文条目。"

    private const val USER_PROMPT =
        "下面是本周各会话的记录。请用简体中文输出一份周报：\n" +
                "1. 本周主要进展（3-5 条，每条一句，注明来自哪个会话）；\n" +
                "2. 待跟进事项（谁 + 什么时候，没有就写「无」）；\n" +
                "3. 需要注意的风险或变化（没有就写「无」）。\n" +
                "不要复述原文。\n\n===== 本周会话 =====\n"

    private fun report(): Result<String> {
        val talkers = AiDigestSource.activeTalkersSince(AiDigestSource.startOfWeek(), MAX_CONVERSATIONS)
        if (talkers.isEmpty()) return Result.failure(IllegalStateException("本周还没有聊天记录"))
        val builder = StringBuilder()
        talkers.forEach { talker ->
            val lines = AiDigestSource.recentLines(talker, PER_CONVERSATION)
            if (lines.isEmpty()) return@forEach
            builder.append("【${AiDigestSource.displayName(talker)}】\n")
            lines.forEach { builder.append(it).append('\n') }
            builder.append('\n')
        }
        if (builder.isBlank()) return Result.failure(IllegalStateException("本周会话里没有可读的文字消息"))
        return TranslateSupport.chatCompletion(SYSTEM_PROMPT, USER_PROMPT + builder)
    }

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
            id = "ai_weekly_report",
            icon = MaterialSymbols.Outlined.Summarize,
            label = "AI 周报",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                if (!AiDigestSource.configuredOrToast(context)) return@ActionItem
                runCatching {
                    showComposeDialog(context) {
                        AiRunDialog(
                            title = "AI 周报（本周）",
                            loadingText = "正在汇总本周会话…",
                            onDismiss = { onDismiss() },
                        ) { _ -> report() }
                    }
                }.onFailure { error ->
                    WeLogger.e(TAG, "打开周报失败", error)
                    showToast(context, "AI 周报失败：${error.message ?: "未知错误"}")
                }
            },
        ),
    )
}

// ── 工具十：跨会话搜索 ───────────────────────────────────────────────────────

@Feature(
    name = "AI 搜聊天",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里给个关键词，AI 在所有会话里找出来并告诉你是谁、说了什么",
)
object AiSearchChat : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiSearchChat"
    private const val MAX_HITS = 120

    private const val SYSTEM_PROMPT =
        "你是聊天记录检索助手：只根据给出的命中记录回答，不推测、不编造。"

    private const val USER_PROMPT =
        "下面是按关键词搜到的聊天记录（带会话名）。请用简体中文输出：\n" +
                "1. 先一句话总结「谁在哪个会话提过这件事」；\n" +
                "2. 再按会话列出关键原话要点（每个会话最多 3 条）；\n" +
                "3. 如果记录里其实无关，就直说「没有相关的聊天」。\n\n===== 命中记录 =====\n"

    /** 在 message 表里按内容模糊搜索，按会话聚合成「会话名 + 原话」。 */
    private fun search(keyword: String): Result<String> {
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT talker, isSend, content FROM message WHERE content LIKE ? ESCAPE '\\' " +
                        "ORDER BY createTime DESC LIMIT $MAX_HITS",
                arrayOf<Any>("%$escaped%"),
            )
        }.getOrElse { error ->
            WeLogger.e(TAG, "搜索消息失败", error)
            return Result.failure(IllegalStateException("搜索失败：${error.message ?: "查询异常"}"))
        }
        if (rows.isEmpty()) return Result.failure(IllegalStateException("没有找到包含「$keyword」的聊天"))
        val grouped = LinkedHashMap<String, MutableList<String>>()
        rows.forEach { row ->
            val talker = row["talker"] as? String ?: return@forEach
            val content = (row["content"] as? String ?: "").trim()
            if (talker.isBlank() || content.isBlank()) return@forEach
            val text = content.substringAfter(":\n", content).trim()
            if (text.startsWith("<")) return@forEach
            grouped.getOrPut(talker) { mutableListOf() }
                .add(text.take(120))
        }
        if (grouped.isEmpty()) return Result.failure(IllegalStateException("没有找到包含「$keyword」的文字聊天"))
        val builder = StringBuilder()
        grouped.forEach { (talker, hits) ->
            builder.append("【${AiDigestSource.displayName(talker)}】\n")
            hits.take(8).forEach { builder.append("- ").append(it).append('\n') }
            builder.append('\n')
        }
        return TranslateSupport.chatCompletion(
            SYSTEM_PROMPT,
            "关键词：$keyword\n\n" + USER_PROMPT + builder,
        )
    }

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
            id = "ai_search_chat",
            icon = MaterialSymbols.Outlined.Search,
            label = "AI 搜聊天",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                if (!AiDigestSource.configuredOrToast(context)) return@ActionItem
                runCatching {
                    showComposeDialog(context) {
                        AiRunDialog(
                            title = "AI 搜聊天",
                            loadingText = "正在所有会话里搜索…",
                            onDismiss = { onDismiss() },
                            inputLabel = "搜什么",
                            inputPlaceholder = "例如：合同 / 发票 / 周五",
                        ) { keyword ->
                            val trimmed = keyword.trim()
                            if (trimmed.isEmpty()) {
                                Result.failure(IllegalStateException("先输入关键词"))
                            } else {
                                search(trimmed)
                            }
                        }
                    }
                }.onFailure { error ->
                    WeLogger.e(TAG, "打开搜索失败", error)
                    showToast(context, "AI 搜聊天失败：${error.message ?: "未知错误"}")
                }
            },
        ),
    )
}

// ── 工具十一：群纪要（仅群聊） ───────────────────────────────────────────────

@Feature(
    name = "AI 群纪要",
    categories = ["聊天"],
    description = "群聊输入栏长按菜单里把最近消息整理成会议纪要（结论 / 待办 / 待确认）",
)
object AiGroupMinutes : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiGroupMinutes"
    private const val COUNT = 200

    private const val SYSTEM_PROMPT =
        "你是会议纪要助手：只记录群里真实说过的内容，不编造、不替人下结论。"

    private const val USER_PROMPT =
        "下面是群聊最近的记录。请用简体中文输出会议纪要：\n" +
                "1. 已经达成的结论（有就列，没有写「无」）；\n" +
                "2. 待办事项：事项 —— 谁 —— 时间（没有就写「未明确」）；\n" +
                "3. 还没决定、需要再确认的点；\n" +
                "4. 不要复述原文，不要编造。\n\n===== 群聊记录 =====\n"

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
            id = "ai_group_minutes",
            icon = MaterialSymbols.Outlined.Schedule,
            label = "AI 群纪要",
            // 只在群聊里出现
            isSupported = { _, _ -> WeCurrentConversationApi.value.isGroupChatWxId },
            onClick = { context, _ ->
                val talker = WeCurrentConversationApi.value
                if (talker.isBlank() || !talker.isGroupChatWxId) {
                    showToast(context, "请先在群聊里使用")
                    return@ActionItem
                }
                if (!AiDigestSource.configuredOrToast(context)) return@ActionItem
                runCatching {
                    showComposeDialog(context) {
                        AiRunDialog(
                            title = "AI 群纪要（最近 $COUNT 条）",
                            loadingText = "正在整理群聊纪要…",
                            onDismiss = { onDismiss() },
                        ) { _ ->
                            val lines = AiDigestSource.recentLines(talker, COUNT)
                            if (lines.isEmpty()) {
                                Result.failure(IllegalStateException("这个群最近没有可读的文字消息"))
                            } else {
                                TranslateSupport.chatCompletion(SYSTEM_PROMPT, USER_PROMPT + lines.joinToString("\n"))
                            }
                        }
                    }
                }.onFailure { error ->
                    WeLogger.e(TAG, "打开群纪要失败", error)
                    showToast(context, "AI 群纪要失败：${error.message ?: "未知错误"}")
                }
            },
        ),
    )
}

// ── 工具十二：该回谁（对方在等我） ───────────────────────────────────────────

@Feature(
    name = "AI 该回谁",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里找出「对方最后一条还没被我回复」的会话，并给一句怎么回",
)
object AiNeedReply : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiNeedReply"

    /** 只看最近 7 天活跃的会话，再往前翻意义不大。 */
    private const val LOOKBACK_DAYS = 7
    private const val MAX_CONVERSATIONS = 12
    private const val PER_CONVERSATION = 6

    private const val SYSTEM_PROMPT =
        "你是消息待办助手：只根据聊天记录判断「对方是否在等我回复」，不编造对话内容。"

    private const val USER_PROMPT =
        "下面是若干会话的最近几条消息（最后一条是各自最新）。请用简体中文输出：\n" +
                "1. 只列出「最后一句是对方说的、需要我回」的会话，格式「会话名：对方说了什么（一句话）」；\n" +
                "2. 每条后面给一句建议怎么回，最多 20 字；\n" +
                "3. 纯通知、广告、不需要回复的会话不要列；\n" +
                "4. 一个都没有就只回一句「没有需要回复的消息」。\n\n===== 候选会话 =====\n"

    private fun run(): Result<String> {
        val since = System.currentTimeMillis() - LOOKBACK_DAYS * 24L * 3600L * 1000L
        val talkers = AiDigestSource.activeTalkersSince(since, MAX_CONVERSATIONS)
        if (talkers.isEmpty()) return Result.failure(IllegalStateException("最近没有聊天记录"))
        val builder = StringBuilder()
        var candidates = 0
        talkers.forEach { talker ->
            val lines = AiDigestSource.recentLines(talker, PER_CONVERSATION)
            if (lines.isEmpty()) return@forEach
            // 只有「最后一句不是我说的」才可能是在等我回复，先粗筛，细判交给模型。
            if (!lines.last().startsWith("对方：")) return@forEach
            candidates++
            builder.append("【${AiDigestSource.displayName(talker)}】\n")
            lines.forEach { builder.append(it).append('\n') }
            builder.append('\n')
        }
        if (candidates == 0) return Result.failure(IllegalStateException("最近没有等你回复的会话"))
        return TranslateSupport.chatCompletion(SYSTEM_PROMPT, USER_PROMPT + builder)
    }

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
            id = "ai_need_reply",
            icon = MaterialSymbols.Outlined.History,
            label = "AI 该回谁",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                if (!AiDigestSource.configuredOrToast(context)) return@ActionItem
                runCatching {
                    showComposeDialog(context) {
                        AiRunDialog(
                            title = "AI 该回谁",
                            loadingText = "正在找在等你回复的会话…",
                            onDismiss = { onDismiss() },
                        ) { _ -> run() }
                    }
                }.onFailure { error ->
                    WeLogger.e(TAG, "打开待回复列表失败", error)
                    showToast(context, "AI 该回谁失败：${error.message ?: "未知错误"}")
                }
            },
        ),
    )
}
