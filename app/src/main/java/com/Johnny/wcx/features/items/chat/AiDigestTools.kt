package com.Johnny.wcx.features.items.chat

import android.content.Context
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Mark_as_unread
import com.composables.icons.materialsymbols.outlined.Person_add
import com.composables.icons.materialsymbols.outlined.Today
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import java.util.Calendar

/**
 * AI 实验室：跨会话的三个工具。
 *
 * 它们都需要读「不止当前这一条会话」，所以不放在消息长按菜单里，
 * 入口是聊天页长按输入框（未读速览 / 今日摘要）与单聊里长按消息（写备注）。
 *
 * 全部只读消息、只展示结果，可以随手复制，不改任何微信数据。
 */

/** 跨会话读取：统一在这里做 SQL 与消息清洗。 */
internal object AiDigestSource {

    private const val TAG = "AiDigestSource"

    private const val MAX_ONE_CHARS = 200

    /** 未读会话：按未读数排序，最多 [limit] 个。 */
    fun unreadTalkers(limit: Int): List<Pair<String, Int>> {
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT username, unReadCount + unReadMuteCount + atCount AS total " +
                        "FROM rconversation WHERE total > 0 ORDER BY total DESC LIMIT $limit",
            )
        }.getOrElse { error ->
            WeLogger.e(TAG, "查询未读会话失败", error)
            return emptyList()
        }
        return rows.mapNotNull { row ->
            val talker = row["username"] as? String ?: return@mapNotNull null
            val total = (row["total"] as? Number)?.toInt() ?: 0
            if (talker.isBlank() || total <= 0) null else talker to total
        }
    }

    /** 从某时刻起有消息的会话：按最后一条消息时间倒序。 */
    fun activeTalkersSince(since: Long, limit: Int): List<String> {
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT talker, MAX(createTime) AS last FROM message WHERE createTime >= ? " +
                        "GROUP BY talker ORDER BY last DESC LIMIT $limit",
                arrayOf<Any>(since),
            )
        }.getOrElse { error ->
            WeLogger.e(TAG, "查询活跃会话失败", error)
            return emptyList()
        }
        return rows.mapNotNull { (it["talker"] as? String)?.takeIf { t -> t.isNotBlank() } }
    }

    /** 今天有消息的会话。 */
    fun activeTodayTalkers(limit: Int): List<String> = activeTalkersSince(startOfToday(), limit)

    /** 本周一 00:00（周报用）。 */
    fun startOfWeek(): Long {
        val calendar = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    /** 今天 00:00。 */
    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 某会话最近 [count] 条消息，返回「我：xxx / 对方：xxx」。 */
    fun recentLines(talker: String, count: Int): List<String> {
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT isSend, type, content FROM message WHERE talker = ? " +
                        "ORDER BY createTime DESC LIMIT $count",
                arrayOf<Any>(talker),
            )
        }.getOrElse { error ->
            WeLogger.e(TAG, "查询会话消息失败", error)
            return emptyList()
        }
        return rows.asReversed().mapNotNull { row ->
            val type = (row["type"] as? Number)?.toInt() ?: 0
            val isSend = (row["isSend"] as? Number)?.toInt() ?: 0
            val raw = row["content"] as? String ?: ""
            val body = bodyOf(type, raw)
            if (body.isBlank()) null else "${if (isSend != 0) "我" else "对方"}：$body"
        }
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

    /** 会话显示名（群名 / 备注名 / 昵称），失败时退回 id。 */
    fun displayName(talker: String): String =
        runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: talker

    /** 没配置时统一提示；配置了返回 true。 */
    fun configuredOrToast(context: Context): Boolean {
        if (AiConfig.isConfigured) return true
        showToast(context, "请先到「AI 助手 → 设置 → AI 接口」填写配置")
        return false
    }
}

// ── 工具六：未读速览（输入栏菜单） ───────────────────────────────────────────

@Feature(
    name = "AI 未读速览",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里把当前所有未读会话的要点整理成一段（不标记已读）",
)
object AiUnreadDigest : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiUnreadDigest"
    private const val MAX_CONVERSATIONS = 10
    private const val PER_CONVERSATION = 6

    private const val SYSTEM_PROMPT =
        "你是消息整理助手：把多个会话的未读内容压缩成要点，只输出结论，不复述原文。"

    private const val USER_PROMPT =
        "下面是各个会话的未读内容（每个会话标了名字与未读数）。请用简体中文输出：\n" +
                "1. 每个会话一行，格式「会话名（未读 N 条）：一句话要点」；\n" +
                "2. 需要我回复的会话排在最前面，并在行末标「需回复」；\n" +
                "3. 不要编造内容。\n\n===== 未读会话 =====\n"

    private fun summary(): Result<String> {
        val unread = AiDigestSource.unreadTalkers(MAX_CONVERSATIONS)
        if (unread.isEmpty()) return Result.failure(IllegalStateException("当前没有未读消息"))
        val builder = StringBuilder()
        unread.forEach { (talker, count) ->
            val name = AiDigestSource.displayName(talker)
            val lines = AiDigestSource.recentLines(talker, PER_CONVERSATION)
            if (lines.isEmpty()) return@forEach
            builder.append("【$name（未读 $count 条）】\n")
            lines.forEach { builder.append(it).append('\n') }
            builder.append('\n')
        }
        if (builder.isBlank()) return Result.failure(IllegalStateException("未读会话里没有可读的文字消息"))
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
            id = "ai_unread_digest",
            icon = MaterialSymbols.Outlined.Mark_as_unread,
            label = "AI 未读速览",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                if (!AiDigestSource.configuredOrToast(context)) return@ActionItem
                runCatching {
                    showComposeDialog(context) {
                        AiRunDialog(
                            title = "AI 未读速览",
                            loadingText = "正在整理未读会话…",
                            onDismiss = { onDismiss() },
                        ) { _ -> summary() }
                    }
                }.onFailure { error ->
                    WeLogger.e(TAG, "打开未读速览失败", error)
                    showToast(context, "AI 未读速览失败：${error.message ?: "未知错误"}")
                }
            },
        ),
    )
}

// ── 工具七：今日摘要（输入栏菜单） ───────────────────────────────────────────

@Feature(
    name = "AI 今日摘要",
    categories = ["聊天"],
    description = "聊天输入栏长按菜单里把今天所有会话的进展汇总成一段（不读也不会漏事）",
)
object AiTodayDigest : ApiFeature(), WeChatInputBarMenuApi.IActionItemsProvider {

    private const val TAG = "AiTodayDigest"
    private const val MAX_CONVERSATIONS = 8
    private const val PER_CONVERSATION = 10

    private const val SYSTEM_PROMPT =
        "你是日程整理助手：把今天多个会话的进展压缩成要点，只输出结论，不复述原文。"

    private const val USER_PROMPT =
        "下面是今天各会话的消息。请用简体中文输出：\n" +
                "1. 先一句话总结今天整体在忙什么；\n" +
                "2. 再按会话列出进展与待跟进（每个会话一行）；\n" +
                "3. 没有实质内容的会话可以忽略，不要编造。\n\n===== 今日会话 =====\n"

    private fun summary(): Result<String> {
        val talkers = AiDigestSource.activeTodayTalkers(MAX_CONVERSATIONS)
        if (talkers.isEmpty()) return Result.failure(IllegalStateException("今天还没有消息"))
        val builder = StringBuilder()
        talkers.forEach { talker ->
            val lines = AiDigestSource.recentLines(talker, PER_CONVERSATION)
            if (lines.isEmpty()) return@forEach
            builder.append("【${AiDigestSource.displayName(talker)}】\n")
            lines.forEach { builder.append(it).append('\n') }
            builder.append('\n')
        }
        if (builder.isBlank()) return Result.failure(IllegalStateException("今天的会话里没有可读的文字消息"))
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
            id = "ai_today_digest",
            icon = MaterialSymbols.Outlined.Today,
            label = "AI 今日摘要",
            isSupported = { _, _ -> true },
            onClick = { context, _ ->
                if (!AiDigestSource.configuredOrToast(context)) return@ActionItem
                runCatching {
                    showComposeDialog(context) {
                        AiRunDialog(
                            title = "AI 今日摘要",
                            loadingText = "正在汇总今天的会话…",
                            onDismiss = { onDismiss() },
                        ) { _ -> summary() }
                    }
                }.onFailure { error ->
                    WeLogger.e(TAG, "打开今日摘要失败", error)
                    showToast(context, "AI 今日摘要失败：${error.message ?: "未知错误"}")
                }
            },
        ),
    )
}

// ── 工具八：给这个人写备注（消息长按，仅单聊） ───────────────────────────────

@Feature(
    name = "AI 写备注",
    categories = ["聊天"],
    description = "单聊里长按任意消息，按聊天内容生成 3 条备注建议（复制后自己填到备注里）",
)
object AiContactRemark : ApiFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiContactRemark"
    private const val MENU_ID = 777202
    private const val COUNT = 60

    private const val SYSTEM_PROMPT =
        "你是微信备注助手：给一个联系人写备注，要具体、能帮人记住「这人是谁、怎么认识的」，不用形容词。"

    override fun onEnable() {
        runCatching { WeChatMessageContextMenuApi.addProvider(this) }
            .onFailure { WeLogger.e(TAG, "注册消息菜单项失败", it) }
    }

    override fun onDisable() {
        runCatching { WeChatMessageContextMenuApi.removeProvider(this) }
            .onFailure { WeLogger.e(TAG, "注销消息菜单项失败", it) }
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ID,
            "AI 写备注",
            ChatInfoIcon,
            MaterialSymbols.Outlined.Person_add,
            // 备注只对单聊里的好友有意义：群聊的 talker 是群 id，客服/公众号也不是「人」。
            { message ->
                val talker = message.talker
                talker.isNotBlank() && !talker.isGroupChatWxId &&
                        !talker.startsWith("gh_") && !talker.startsWith("weixin")
            },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { view, _, message ->
            val talker = message.talker
            showComposeDialog(view.context) {
                AiRunDialog(
                    title = "AI 备注建议",
                    loadingText = "正在看 ${AiDigestSource.displayName(talker)} 的聊天…",
                    onDismiss = { onDismiss() },
                ) { _ ->
                    val lines = AiDigestSource.recentLines(talker, COUNT)
                    if (lines.isEmpty()) {
                        Result.failure(IllegalStateException("这个会话没有可读的文字消息"))
                    } else {
                        TranslateSupport.chatCompletion(
                            SYSTEM_PROMPT,
                            "聊天对象：${AiDigestSource.displayName(talker)}\n\n" +
                                    "聊天记录：\n" + lines.joinToString("\n") +
                                    "\n\n请给 3 条备注建议，每条不超过 16 字，编号 1./2./3.，不要解释。",
                        )
                    }
                }
            }
        },
    )
}

/** 供「AI 助手 → 设置 → AI 工具」调用的占位入口：未读速览与今日摘要需要聊天页上下文。 */
object AiDigestHint {

    fun tellWhereToUse(context: Context) {
        if (!AiConfig.isConfigured) {
            showToast(context, "请先到「AI 助手 → 设置 → AI 接口」填写配置")
            return
        }
        val talker = WeCurrentConversationApi.value
        showToast(
            context,
            if (talker.isBlank()) {
                "打开任意聊天 → 长按输入框 → 选「AI 未读速览」或「AI 今日摘要」"
            } else {
                "长按输入框 → 选「AI 未读速览」或「AI 今日摘要」"
            },
        )
    }
}
