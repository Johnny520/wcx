package com.Johnny.wcx.features.items.batch

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 公众号批量取关。
 *
 * 取关路径分两级，用户在弹窗里可以切换：
 * 1. **原生（默认）**：调用微信自己的「公众号会话列表批量取消关注」实现
 *    （8.0.80 = hy.f0#unSubscribeAllContacts(List)，内部走 NetSceneBatchModContactType
 *    `/cgi-bin/micromsg-bin/batchmodcontacttype` + BizContactDeleteUtil，服务端真取关，
 *    并复用微信自己的进度框）；
 * 2. **本地解绑（降级）**：删 rcontact / bizinfo 里的记录并把会话从列表移除。
 *    只是本地解绑，**可能被服务端还原**（下次同步联系人时又回来）。
 */
@Feature(
    name = "公众号批量取关",
    categories = ["批量操作"],
    description = "列出全部关注的公众号，多选后批量取消关注（优先调用微信原生批量取关）"
)
object BatchUnsubscribeOfficialAccounts : ClickableFeature(), IResolveDex {

    private const val TAG = "BatchUnsubscribeOfficialAccounts"

    override val noSwitchWidget = true

    private const val PREF_NATIVE = "batch_unsub_official_use_native"
    private const val PREF_SORT_ACTIVE = "batch_unsub_official_sort_active"

    private var useNative by WePrefs.prefOption(PREF_NATIVE, true)
    private var sortByActive by WePrefs.prefOption(PREF_SORT_ACTIVE, true)

    private const val CONCURRENCY = 2
    private const val PACE_MS = 60L
    private const val TOTAL_TIMEOUT_MS = 5 * 60 * 1000L

    /** 原生取关提交后等多久再回头核对（微信是异步 netscene）。 */
    private const val VERIFY_DELAY_MS = 3_000L

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

    /**
     * 微信 Flutter 公众号会话插件（8.0.80 = hy.f0），实现了 pigeon 的
     * com.tencent.pigeon.biz.ConversationListApi，含 unSubscribeAllContacts(List) / unSubscribeContact(String)。
     *
     * 唯一性锚点：同时含 "MicroMsg.FlutterBizConversationPlugin" 与
     * "batch unsubscribe finished, reload now" 的类在全 APK 只有一个（即该类）。
     */
    private val classBizConversationPlugin by dexClass(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.FlutterBizConversationPlugin",
                "batch unsubscribe finished, reload now"
            )
        }
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

    // ------------------------------------------------------------------ 数据

    private data class BizAccount(
        val username: String,
        val nickname: String,
        val remark: String,
        val lastActive: Long
    ) {
        val title: String get() = remark.ifBlank { nickname }.ifBlank { username }
    }

    /** 读取全部关注的公众号（rcontact.username like 'gh_%'），join rconversation 取最后活跃时间。 */
    private fun loadAccounts(): List<BizAccount> {
        val rows = WeDatabaseApi.executeQuery(
            """
            SELECT r.username AS username,
                   IFNULL(r.nickname, '') AS nickname,
                   IFNULL(r.conRemark, '') AS conRemark,
                   IFNULL(c.conversationTime, 0) AS conversationTime
            FROM rcontact r
            LEFT JOIN rconversation c ON c.username = r.username
            WHERE r.username LIKE 'gh_%'
            """.trimIndent()
        )
        val result = ArrayList<BizAccount>(rows.size)
        for (row in rows) {
            val username = row["username"]?.toString().orEmpty()
            if (username.isEmpty()) continue
            result += BizAccount(
                username = username,
                nickname = row["nickname"]?.toString().orEmpty(),
                remark = row["conRemark"]?.toString().orEmpty(),
                lastActive = (row["conversationTime"] as? Number)?.toLong() ?: 0L
            )
        }
        return result
    }

    private fun sortAccounts(list: List<BizAccount>, byActive: Boolean): List<BizAccount> {
        return if (byActive) {
            list.sortedWith(compareByDescending<BizAccount> { it.lastActive }.thenBy { it.nickname })
        } else {
            list.sortedBy { it.nickname }
        }
    }

    // ------------------------------------------------------------------ UI

    override fun onClick(context: ComponentActivity) {
        val sc = ensureScope()
        showToast("正在读取公众号列表…")
        sc.launch {
            val accounts = withContext(Dispatchers.IO) {
                runCatching { loadAccounts() }
                    .onFailure { WeLogger.e(TAG, "读取公众号列表失败", it) }
                    .getOrDefault(emptyList())
            }
            withContext(Dispatchers.Main) {
                if (accounts.isEmpty()) {
                    showToast("没有读到关注的公众号（详见日志）")
                } else {
                    showSelector(context, accounts)
                }
            }
        }
    }

    private fun showSelector(context: ComponentActivity, accounts: List<BizAccount>) {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        showComposeDialog(context) {
            var selected by remember { mutableStateOf(emptySet<String>()) }
            var byActive by remember { mutableStateOf(sortByActive) }
            var native by remember { mutableStateOf(useNative) }

            val ordered = remember(byActive, accounts) { sortAccounts(accounts, byActive) }

            AlertDialogContent(
                title = { Text("公众号批量取关（共 ${accounts.size} 个）") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = {
                                selected = if (selected.size == accounts.size) {
                                    emptySet()
                                } else {
                                    accounts.map { it.username }.toSet()
                                }
                            }) { Text(if (selected.size == accounts.size) "取消全选" else "全选") }
                            Text("已选 ${selected.size}")
                        }
                        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                            items(ordered, key = { it.username }) { account ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        selected = if (account.username in selected) {
                                            selected - account.username
                                        } else {
                                            selected + account.username
                                        }
                                    },
                                    headlineContent = { Text(account.title) },
                                    supportingContent = {
                                        Text(
                                            if (account.lastActive > 0) {
                                                "最后活跃 " + runCatching {
                                                    formatter.format(Date(account.lastActive * 1000L))
                                                }.getOrDefault("未知")
                                            } else {
                                                "无会话记录"
                                            }
                                        )
                                    },
                                    trailingContent = {
                                        Checkbox(
                                            checked = account.username in selected,
                                            onCheckedChange = null
                                        )
                                    }
                                )
                            }
                        }
                        HorizontalDivider()
                        ListItem(
                            modifier = Modifier.clickable { byActive = !byActive },
                            headlineContent = { Text("按最后活跃排序") },
                            trailingContent = { Switch(checked = byActive, onCheckedChange = null) }
                        )
                        ListItem(
                            modifier = Modifier.clickable { native = !native },
                            headlineContent = { Text("使用微信原生批量取关") },
                            supportingContent = {
                                Text("关闭后改为本地解绑（可能被服务端还原）")
                            },
                            trailingContent = { Switch(checked = native, onCheckedChange = null) }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        if (selected.isEmpty()) {
                            showToast("请先选择要取关的公众号")
                            return@Button
                        }
                        sortByActive = byActive
                        useNative = native
                        onDismiss()
                        confirmUnsubscribe(context, selected.toList(), native)
                    }) { Text("确认取关") }
                }
            )
        }
    }

    private fun confirmUnsubscribe(
        context: ComponentActivity,
        usernames: List<String>,
        native: Boolean
    ) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("确认取关") },
                text = {
                    Text(
                        "将取消关注选中的 ${usernames.size} 个公众号。" +
                            if (native) {
                                "\n\n走微信原生批量取关（服务端生效，会弹出微信自己的进度框）。"
                            } else {
                                "\n\n当前为本地解绑降级方案：只删本地记录，可能被服务端还原。"
                            }
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        startUnsubscribe(context, usernames, native)
                    }) { Text("开始") }
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
        val message = mutableStateOf("准备中…")
    }

    private fun startUnsubscribe(
        context: ComponentActivity,
        usernames: List<String>,
        native: Boolean
    ) {
        val sc = ensureScope()
        val state = ProgressState()
        state.total.value = usernames.size

        showComposeDialog(context) {
            val processed by state.processed
            val total by state.total
            val finished by state.finished
            val message by state.message

            AlertDialogContent(
                title = { Text("公众号批量取关") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(message)
                        if (!native) {
                            Text("已处理 $processed / $total")
                        }
                    }
                },
                dismissButton = {
                    if (!finished) {
                        TextButton(onClick = {
                            state.cancelled.value = true
                            runningJob?.cancel()
                            WeLogger.i(TAG, "用户取消批量取关")
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
                if (native) {
                    state.message.value = "正在调用微信原生批量取关…"
                    val ok = withContext(Dispatchers.Main) {
                        nativeBatchUnsubscribe(context, usernames)
                    }
                    if (ok) {
                        state.message.value = "已提交，正在核对结果…"
                        delay(VERIFY_DELAY_MS)
                        val remain = withContext(Dispatchers.IO) { countStillFollowed(usernames) }
                        state.processed.value = usernames.size - remain.size
                        state.message.value = if (remain.isEmpty()) {
                            "微信原生取关成功：${usernames.size} 个已全部取关"
                        } else {
                            "已提交 ${usernames.size} 个，仍有 ${remain.size} 个在列表里" +
                                "（可能被服务端拒绝或网络未完成，可改用本地解绑）"
                        }
                        WeLogger.i(TAG, "native unsubscribe done, remaining=${remain.size}")
                    } else {
                        state.message.value = "原生取关不可用，已自动改用本地解绑…"
                        WeLogger.w(TAG, "nativeBatchUnsubscribe unavailable, falling back to local unbind")
                        val result = localUnbind(usernames, state)
                        state.message.value = "本地解绑完成：成功 ${result.first} 个，失败 ${result.second} 个"
                    }
                    state.finished.value = true
                    refreshConversationList()
                    showToastSuspend(state.message.value)
                } else {
                    state.message.value = "正在本地解绑…"
                    val result = localUnbind(usernames, state)
                    state.message.value =
                        "本地解绑完成：成功 ${result.first} 个，失败 ${result.second} 个（可能被服务端还原）"
                    state.finished.value = true
                    refreshConversationList()
                    showToastSuspend("本地解绑完成：成功 ${result.first} 个，失败 ${result.second} 个")
                }
            } catch (e: CancellationException) {
                state.message.value = "已取消"
                state.finished.value = true
                // 协程已被取消，普通 withContext 会立刻再次抛 CancellationException，
                // 这里用 NonCancellable 保证取消路径上的列表刷新能跑完。
                runCatching { withContext(NonCancellable) { refreshConversationList() } }
                throw e
            } catch (e: Throwable) {
                state.message.value = "执行失败：${e.javaClass.simpleName}"
                state.finished.value = true
                WeLogger.e(TAG, "批量取关失败", e)
                showToastSuspend("批量取关失败：${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 调用微信原生批量取关。必须在主线程调用（内部会弹微信自己的进度框）。
     *
     * 反射约定（8.0.80 实测，见 ANCHORS.md）：
     * - 构造器 (I)V；
     * - 类里唯一一个 Activity 类型字段用来绑定当前页面（8.0.80 名为 e）；
     * - 方法 unSubscribeAllContacts(List)。
     */
    private fun nativeBatchUnsubscribe(activity: Activity, usernames: List<String>): Boolean {
        if (classBizConversationPlugin.isPlaceholder) {
            WeLogger.w(TAG, "原生取关锚点未解析，跳过")
            return false
        }
        return runCatching {
            val clazz = classBizConversationPlugin.clazz
            val ctor = clazz.declaredConstructors.firstOrNull { candidate ->
                // 8.0.80 = hy.f0.<init>(I)V：唯一的单参构造器，参数是 int。
                candidate.parameterCount == 1 &&
                    candidate.parameterTypes.getOrNull(0) == Int::class.javaPrimitiveType
            } ?: run {
                WeLogger.w(TAG, "未找到 (I)V 构造器")
                return@runCatching false
            }
            ctor.isAccessible = true
            val plugin = ctor.newInstance(0)
            val container = plugin ?: return@runCatching false

            val activityField = clazz.declaredFields.firstOrNull { it.type == Activity::class.java }
            if (activityField == null) {
                WeLogger.w(TAG, "未找到 Activity 字段")
                return@runCatching false
            }
            activityField.isAccessible = true
            activityField.set(container, activity)

            val method = clazz.declaredMethods.firstOrNull { it.name == "unSubscribeAllContacts" }
            if (method == null) {
                WeLogger.w(TAG, "未找到 unSubscribeAllContacts(List)")
                return@runCatching false
            }
            method.isAccessible = true
            method.invoke(container, ArrayList(usernames))
            WeLogger.i(TAG, "已提交微信原生批量取关：${usernames.size} 个")
            true
        }.onFailure {
            WeLogger.e(TAG, "调用微信原生批量取关异常", it)
        }.getOrDefault(false)
    }

    /** 降级：删除 rcontact / bizinfo 记录并把会话从列表移除。返回 (成功数, 失败数)。 */
    private suspend fun localUnbind(usernames: List<String>, state: ProgressState): Pair<Int, Int> {
        val gate = Semaphore(CONCURRENCY)
        var done = 0
        var failed = 0
        val completed = withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
            usernames.forEach { username ->
                gate.withPermit {
                    if (state.cancelled.value) return@withPermit
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            WeDatabaseApi.delete("rcontact", "username=?", arrayOf(username))
                            WeDatabaseApi.delete("bizinfo", "username=?", arrayOf(username))
                            true
                        }.onFailure { WeLogger.w(TAG, "本地删除公众号记录失败：$username", it) }
                            .getOrDefault(false)
                    }
                    withContext(Dispatchers.Main) {
                        runCatching { WeConversationApi.hideConversation(username) }
                            .onFailure { WeLogger.w(TAG, "隐藏公众号会话失败：$username", it) }
                    }
                    if (ok) done++ else failed++
                    state.processed.value = done + failed
                    delay(PACE_MS)
                }
            }
            true
        }
        if (completed == null) {
            WeLogger.w(TAG, "本地解绑超时：已处理 ${done + failed} / ${usernames.size}")
        }
        return done to failed
    }

    /** 原生请求是异步的：等一会儿回头看还有哪些人留在 rcontact 里。IO 线程调用。 */
    private fun countStillFollowed(usernames: List<String>): List<String> {
        if (usernames.isEmpty()) return emptyList()
        val holders = usernames.joinToString(",") { "?" }
        val rows = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT username FROM rcontact WHERE username IN ($holders)",
                usernames.map { it as Any }.toTypedArray()
            )
        }.getOrElse { e ->
            WeLogger.w(TAG, "核对取关结果失败", e)
            return emptyList()
        }
        return rows.mapNotNull { it["username"]?.toString() }
    }

    private suspend fun refreshConversationList() {
        withContext(Dispatchers.Main) {
            runCatching { WeConversationApi.reloadConversations() }
                .onFailure { WeLogger.w(TAG, "刷新会话列表失败", it) }
        }
    }
}
