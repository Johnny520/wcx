package com.Johnny.wcx.features.items.batch

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 按类别批量清空会话。
 *
 * 设计要点（与仓库既有 features/items/batch 下实现保持一致，同时补齐批量任务必须做的事）：
 * 1. 只读数据库（rconversation / rcontact / bizinfo）在 Dispatchers.IO 上做；
 * 2. 真正删会话调的是微信自己的 ConversationStorage 方法（hideConversation / deleteConversation），
 *    它们会同步通知会话列表观察者，**必须回到主线程**执行，否则列表不刷新甚至崩 adapter；
 * 3. 限并发（Semaphore）+ 总超时 + 可中途取消，结束时统一 reloadConversations()。
 */
@Feature(
    name = "按类别批量清理会话",
    categories = ["批量操作"],
    description = "按公众号 / 订阅号 / 群聊 / 单聊批量清理会话（默认只从列表移除，保留聊天记录）"
)
object BatchClearConversationsByCategory : ClickableFeature() {

    private const val TAG = "BatchClearConversationsByCategory"

    /** 纯动作型入口，不需要开关。 */
    override val noSwitchWidget = true

    private const val PREF_CATEGORIES = "batch_clear_conv_categories"
    private const val PREF_PERMANENT = "batch_clear_conv_permanent"
    private const val PREF_ONLY_DND = "batch_clear_conv_only_dnd"
    private const val PREF_ONLY_NO_UNREAD = "batch_clear_conv_only_no_unread"

    /** 记住上次选择，避免每次都重点。 */
    private var savedCategories by WePrefs.prefOption(PREF_CATEGORIES, "")
    private var permanentDelete by WePrefs.prefOption(PREF_PERMANENT, false)
    private var onlyDnd by WePrefs.prefOption(PREF_ONLY_DND, false)
    private var onlyNoUnread by WePrefs.prefOption(PREF_ONLY_NO_UNREAD, false)

    /** 并发闸门：真正删会话在主线程串行执行，这里限制的是排队等待的任务数。 */
    private const val CONCURRENCY = 3

    /** 每个会话之间留一点间隔，给主线程刷新列表的机会，避免几百条通知一次怼上去卡死 LauncherUI。 */
    private const val PACE_MS = 40L

    /** 整个批量任务的总超时。 */
    private const val TOTAL_TIMEOUT_MS = 5 * 60 * 1000L

    /** 微信内部虚拟会话，既不是单聊也不是群聊，任何类别都不碰。 */
    private val SYSTEM_USERNAMES = setOf(
        "officialaccounts", "notification_messages", "notifymessage", "fmessage", "medianote",
        "floatbottle", "qqmail", "tmessage", "blogapp", "weixin", "helper_entry",
        "brandsessionholder", "brandservicesessionholder", "filehelper", "mphelper",
        "qqsafe", "weibo", "voiceassist", "exmail_tool", "qqfriend", "newsapp"
    )

    /**
     * 注意：`noSwitchWidget = true` 的动作型功能在设置页没有开关，`ClickableFeature.shouldEnableOnStartup`
     * 因此可能是 false —— `onEnable()` 不保证被调用。所以 scope 不能依赖 onEnable 初始化，
     * 这里改成「第一次用的时候懒创建」（仓库里其它 batch 功能是每次动作 new 一个 scope，同理）。
     */
    private var scopeRef: CoroutineScope? = null
    private var runningJob: Job? = null

    private fun ensureScope(): CoroutineScope {
        val existing = scopeRef
        if (existing != null && existing.isActive) return existing
        val created = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scopeRef = created
        return created
    }

    /** 会话类别。 */
    enum class Category(val key: String, val label: String) {
        OFFICIAL("official", "公众号（全部）"),
        SUBSCRIPTION("subscription", "订阅号（不含服务号）"),
        GROUP("group", "群聊"),
        SINGLE("single", "单聊")
    }

    override fun onEnable() {
        ensureScope()
    }

    override fun onDisable() {
        runningJob?.cancel()
        runningJob = null
        scopeRef?.cancel()
        scopeRef = null
    }

    // ------------------------------------------------------------------ UI

    override fun onClick(context: ComponentActivity) {
        val all = Category.values().toList()
        val restored = savedCategories.split(',')
            .mapNotNull { key -> all.firstOrNull { it.key == key } }
            .toSet()
        val initial = if (restored.isEmpty()) setOf(Category.SINGLE, Category.GROUP) else restored

        showComposeDialog(context) {
            var selected by remember { mutableStateOf(initial) }
            var permanent by remember { mutableStateOf(permanentDelete) }
            var dndOnly by remember { mutableStateOf(onlyDnd) }
            var noUnreadOnly by remember { mutableStateOf(onlyNoUnread) }

            AlertDialogContent(
                title = { Text("按类别批量清理会话") },
                text = {
                    DefaultColumn(scrollable = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                selected = if (selected.size == all.size) emptySet() else all.toSet()
                            }) { Text(if (selected.size == all.size) "全不选" else "全选") }
                            Text("已选 ${selected.size} / ${all.size} 类")
                        }
                        all.forEach { category ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    selected = if (category in selected) {
                                        selected - category
                                    } else {
                                        selected + category
                                    }
                                },
                                headlineContent = { Text(category.label) },
                                trailingContent = {
                                    Checkbox(checked = category in selected, onCheckedChange = null)
                                }
                            )
                        }

                        ListItem(
                            modifier = Modifier.clickable { dndOnly = !dndOnly },
                            headlineContent = { Text("仅清理免打扰会话") },
                            supportingContent = { Text("只处理微信里设置了消息免打扰的会话") },
                            trailingContent = { Switch(checked = dndOnly, onCheckedChange = null) }
                        )
                        ListItem(
                            modifier = Modifier.clickable { noUnreadOnly = !noUnreadOnly },
                            headlineContent = { Text("仅清理无未读会话") },
                            supportingContent = { Text("跳过还有未读消息的会话，避免漏看消息") },
                            trailingContent = { Switch(checked = noUnreadOnly, onCheckedChange = null) }
                        )
                        ListItem(
                            modifier = Modifier.clickable { permanent = !permanent },
                            headlineContent = { Text("彻底删除（含聊天记录）") },
                            supportingContent = { Text("默认只从会话列表移除，聊天记录保留") },
                            trailingContent = { Switch(checked = permanent, onCheckedChange = null) }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        if (selected.isEmpty()) {
                            showToast("请至少选择一个类别")
                            return@Button
                        }
                        savedCategories = selected.joinToString(",") { it.key }
                        permanentDelete = permanent
                        onlyDnd = dndOnly
                        onlyNoUnread = noUnreadOnly
                        onDismiss()
                        startBatch(context, selected, permanent, dndOnly, noUnreadOnly)
                    }) { Text("开始清理") }
                }
            )
        }
    }

    // ------------------------------------------------------------- 批量执行

    private class ProgressState {
        val processed = mutableStateOf(0)
        val total = mutableStateOf(0)
        val finished = mutableStateOf(false)
        val cancelled = mutableStateOf(false)
        val message = mutableStateOf("正在读取会话列表…")
    }

    private fun startBatch(
        context: ComponentActivity,
        categories: Set<Category>,
        permanent: Boolean,
        dndOnly: Boolean,
        noUnreadOnly: Boolean
    ) {
        val sc = ensureScope()
        val state = ProgressState()

        showComposeDialog(context) {
            val processed by state.processed
            val total by state.total
            val finished by state.finished
            val message by state.message

            AlertDialogContent(
                title = { Text("批量清理会话") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message)
                        Text(if (total > 0) "已处理 $processed / $total" else "已处理 $processed")
                    }
                },
                dismissButton = {
                    if (!finished) {
                        TextButton(onClick = {
                            state.cancelled.value = true
                            runningJob?.cancel()
                            WeLogger.i(TAG, "用户取消批量清理")
                        }) { Text("取消") }
                    }
                },
                confirmButton = {
                    if (finished) {
                        Button(onClick = onDismiss) { Text("完成") }
                    }
                }
            )
        }

        runningJob = sc.launch {
            try {
                state.message.value = "正在读取会话列表…"
                val targets = loadTargets(categories, dndOnly, noUnreadOnly)
                state.total.value = targets.size
                if (targets.isEmpty()) {
                    state.message.value = "没有符合条件（且显示在首页列表）的会话"
                    state.finished.value = true
                    return@launch
                }
                state.message.value = if (permanent) "正在彻底删除会话…" else "正在从列表移除会话…"

                val gate = Semaphore(CONCURRENCY)
                var done = 0
                var failed = 0

                val completed = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                    targets.forEach { talker ->
                        gate.withPermit {
                            if (state.cancelled.value) return@withPermit
                            val ok = removeOne(talker, permanent)
                            if (ok) done++ else failed++
                            state.processed.value = done + failed
                            delay(PACE_MS)
                        }
                    }
                    true
                }

                if (completed == null) {
                    state.message.value = "执行超时（已处理 ${done + failed} / ${targets.size}），其余跳过"
                    WeLogger.w(TAG, "batch clear timed out after $TOTAL_TIMEOUT_MS ms")
                } else {
                    state.message.value = "完成：成功 $done 个，失败 $failed 个"
                }
                state.finished.value = true
                refreshConversationList()
                showToastSuspend(
                    if (state.cancelled.value) {
                        "已取消：成功清理 $done 个会话"
                    } else {
                        "批量清理完成：成功 $done 个，失败 $failed 个"
                    }
                )
            } catch (e: CancellationException) {
                state.message.value = "已取消"
                state.finished.value = true
                // 协程已被取消，普通 withContext 会立刻再次抛 CancellationException，
                // 这里用 NonCancellable 保证取消路径上的列表刷新能跑完。
                runCatching { withContext(NonCancellable) { refreshConversationList() } }
                WeLogger.i(TAG, "批量清理被取消")
                throw e
            } catch (e: Throwable) {
                state.message.value = "执行失败：${e.javaClass.simpleName}"
                state.finished.value = true
                WeLogger.e(TAG, "批量清理失败", e)
                showToastSuspend("批量清理失败：${e.javaClass.simpleName}")
            }
        }
    }

    /** 读取 rconversation 并做类别 / 免打扰 / 未读过滤。全部在 IO 线程调用。 */
    private fun loadTargets(
        categories: Set<Category>,
        dndOnly: Boolean,
        noUnreadOnly: Boolean
    ): List<String> {
        // bizinfo 表在老版本 / 精简版可能不存在，JOIN 失败时退化为「没有服务号信息」再查一次，
        // 不能让一条 SQL 异常把整个功能打死。
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                """
                SELECT c.username AS username,
                       IFNULL(c.unReadCount, 0) AS unReadCount,
                       IFNULL(b.type, 0) AS bizType
                FROM rconversation c
                LEFT JOIN bizinfo b ON b.username = c.username
                ORDER BY c.conversationTime DESC
                """.trimIndent()
            )
        }.getOrElse { e ->
            WeLogger.w(TAG, "带 bizinfo 的查询失败，退化为不带服务号信息", e)
            runCatching {
                WeDatabaseApi.executeQuery(
                    """
                    SELECT c.username AS username,
                           IFNULL(c.unReadCount, 0) AS unReadCount,
                           0 AS bizType
                    FROM rconversation c
                    ORDER BY c.conversationTime DESC
                    """.trimIndent()
                )
            }.getOrElse { inner ->
                WeLogger.e(TAG, "读取会话列表失败", inner)
                return emptyList()
            }
        }

        val result = ArrayList<String>(rows.size)
        for (row in rows) {
            val username = row["username"]?.toString().orEmpty()
            if (username.isEmpty()) continue
            val unread = (row["unReadCount"] as? Number)?.toInt() ?: 0
            val bizType = (row["bizType"] as? Number)?.toInt() ?: 0

            if (username in SYSTEM_USERNAMES) continue
            if (!matchesCategory(username, bizType, categories)) continue
            if (noUnreadOnly && unread > 0) continue
            if (dndOnly && !runCatching { WeConversationApi.isDnd(username) }.getOrDefault(false)) continue
            result += username
        }
        return result
    }

    private fun matchesCategory(username: String, bizType: Int, categories: Set<Category>): Boolean {
        val isGroup = username.endsWith("@chatroom")
        val isOfficial = username.startsWith("gh_")
        return when {
            isGroup -> Category.GROUP in categories
            isOfficial -> {
                // bizinfo.type：0 = 订阅号（official）、1 = 服务号（service）。
                // 依据 8.0.80 MvvmAddressUIFragment 日志 "bizMergeEntry official=%d, service=%d"，
                // official 取 BizInfoStorage.X1(0)、service 取 X1(1)（见 ANCHORS.md）。
                val isService = bizType != 0
                Category.OFFICIAL in categories ||
                    (Category.SUBSCRIPTION in categories && !isService)
            }
            // 单聊：排除所有 @xxx 虚拟账号，只留 wxid_ / 自定义微信号这类真实联系人。
            username.contains("@") -> false
            else -> Category.SINGLE in categories
        }
    }

    /**
     * 清掉一个会话。
     *
     * hideConversation / deleteConversation 都会通过 ConversationStorage 同步通知会话列表观察者，
     * 必须在主线程调用（仓库里 BatchDeleteChatHistory / BatchHideConversations 同样这么做）。
     */
    private suspend fun removeOne(talker: String, permanent: Boolean): Boolean {
        return withContext(Dispatchers.Main) {
            runCatching {
                if (permanent) {
                    WeConversationApi.deleteConversation(talker)
                } else {
                    WeConversationApi.hideConversation(talker)
                }
            }.onFailure { e ->
                WeLogger.w(TAG, "清理会话失败：$talker", e)
            }.getOrDefault(false)
        }
    }

    private suspend fun refreshConversationList() {
        withContext(Dispatchers.Main) {
            runCatching { WeConversationApi.reloadConversations() }
                .onFailure { WeLogger.w(TAG, "刷新会话列表失败", it) }
        }
    }
}
