package com.Johnny.wcx.features.items.contacts

import com.Johnny.wcx.utils.strings.isGroupChatWxId
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 群公告快捷发布。
 *
 * ## 当前实现：**降级为群消息广播**（原生公告接口未接通，原因见下）
 *
 * 已定位到 8.0.80 原生发公告链路（完整记录见 ANCHORS.md）：
 * `com.tencent.mm.plugin.announcement.ChatroomNoticeUI.L7(String, String, boolean)`：
 * 1. 构造 protobuf 请求 `gd5.ma6`（字段 `d`=群 id，`e`/`g`=公告正文/XML，`m`=是否纯文本）；
 * 2. 构造 `com.tencent.mm.modelbase.l`（`c`=`/cgi-bin/micromsg-bin/setchatroomannouncement`，
 *    `d`=993（cmd id），`a`=请求、`b`=`gd5.na6` 响应）；
 * 3. `l.a()` → `com.tencent.mm.modelbase.i().l(o).k()` → `f06.g`，**必须**再 `.J(qv5.b)` 绑定
 *    生命周期宿主（原生传的是 ChatroomNoticeUI 自己）并 `.G(ov5.a)` 注册回调才会真正发出去。
 *
 * 也就是说：这条链路绑死在「宿主 Activity 的 lifecycle + 观察者链」上，模块里没有等价的
 * `qv5.b` 宿主，硬调会抛 NPE 或静默不发；8.0.80 也不存在 `IChatroomInfoService.setChatroomNotice`
 * 这类可直接调用的服务接口（`com.tencent.mm.chatroom.*` 只剩 UI 类，model 层全部混淆）。
 * 按规范「定位不到可靠锚点不要编造」，这里**不做假的原生调用**，改为：
 * 选中群 → 发送一条带 `【群公告】` 前缀的群消息（可选是否加前缀），并保证：
 * - 群列表查询走 IO 线程；
 * - 群发限并发（Semaphore(2)）+ 总超时（60s）；
 * - 逐群统计成败并提示，不吞异常。
 */
@Feature(
    name = "群公告快捷发布",
    categories = ["联系人与群组"],
    description = "选择群聊并输入公告内容, 一键把公告广播到选中的群（当前版本以群消息形式发布）"
)
object GroupNoticePublisher : ClickableFeature() {

    private const val TAG = "GroupNoticePublisher"

    private const val PUBLISH_TIMEOUT_MS = 60_000L

    private var usePrefix by WePrefs.prefOption("group_notice_use_prefix", true)
    private var lastContent by WePrefs.prefOption("group_notice_last_content", "")

    private val publishGate = Semaphore(2)

    private var scope: CoroutineScope? = null

    override val noSwitchWidget = true
    override val alwaysEnabled = true

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onDisable() {
        scope?.cancel()
        scope = null
    }

    private fun ensureScope(): CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }

    // ==================== 发布 ====================

    private fun publish(groupIds: Set<String>, content: String, prefix: Boolean) {
        val targets = groupIds.filter { it.isGroupChatWxId }
        if (targets.isEmpty()) return
        val text = if (prefix) "【群公告】$content" else content
        ensureScope().launch {
            try {
                val successCount = withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
                    coroutineScope {
                        targets.map { groupId ->
                            async {
                                publishGate.withPermit {
                                    runCatching { WeMessageApi.sendText(groupId, text) }
                                        .onFailure { WeLogger.e(TAG, "publish failed: group=$groupId", it) }
                                        .getOrDefault(false)
                                }
                            }
                        }.awaitAll().count { it }
                    }
                } ?: run {
                    WeLogger.e(TAG, "publish timeout, targets=${targets.size}")
                    null
                }

                if (successCount == null) {
                    showToastSuspend("群公告发布超时，请稍后在群内确认")
                } else {
                    val failed = targets.size - successCount
                    WeLogger.i(TAG, "publish done: success=$successCount failed=$failed")
                    showToastSuspend(
                        if (failed == 0) {
                            "已发布到 $successCount 个群"
                        } else {
                            "发布完成：成功 $successCount 个，失败 $failed 个"
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "publish task failed", e)
                runCatching { showToastSuspend("群公告发布异常：${e.javaClass.simpleName}") }
            }
        }
    }

    // ==================== 设置/发布界面 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selectedGroups by remember { mutableStateOf(emptySet<String>()) }
            var contentState by remember { mutableStateOf(lastContent) }
            var prefixState by remember { mutableStateOf(usePrefix) }
            var groups by remember { mutableStateOf<List<IWeContact>>(emptyList()) }

            // 群列表查询是数据库 IO，不能放主线程
            LaunchedEffect(Unit) {
                groups = withContext(Dispatchers.IO) {
                    runCatching { WeDatabaseApi.getGroups() }
                        .onFailure { WeLogger.e(TAG, "load groups failed", it) }
                        .getOrDefault(emptyList())
                }
            }

            AlertDialogContent(
                title = { Text("群公告快捷发布") },
                text = {
                    DefaultColumn(scrollable = true) {
                        Text("当前版本以「群消息」形式发布（微信原生公告接口绑定在宿主页面生命周期上，模块内暂无法可靠调用）。")
                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = "选择要发布公告的群",
                                        contacts = groups,
                                        initialSelectedWxIds = selectedGroups,
                                        onDismiss = onDismiss,
                                        onConfirm = { selected ->
                                            selectedGroups = selected
                                            onDismiss()
                                        }
                                    )
                                }
                            },
                            headlineContent = { Text("选择群聊") },
                            supportingContent = {
                                Text(
                                    if (selectedGroups.isEmpty()) {
                                        "尚未选择（共 ${groups.size} 个群可选）"
                                    } else {
                                        "已选 ${selectedGroups.size} 个群"
                                    }
                                )
                            }
                        )
                        OutlinedTextField(
                            value = contentState,
                            onValueChange = { contentState = it },
                            label = { Text("公告内容") },
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ListItem(
                            modifier = Modifier.clickable { prefixState = !prefixState },
                            trailingContent = { Switch(checked = prefixState, onCheckedChange = null) },
                            headlineContent = { Text("添加「【群公告】」前缀") },
                            supportingContent = { Text("便于群成员一眼看出这是公告") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val content = contentState.trim()
                        when {
                            selectedGroups.isEmpty() -> {
                                showToastSuspendInDialog(context, "请先选择要发布的群")
                            }

                            content.isEmpty() -> {
                                showToastSuspendInDialog(context, "公告内容不能为空")
                            }

                            else -> {
                                usePrefix = prefixState
                                lastContent = content
                                publish(selectedGroups, content, prefixState)
                                onDismiss()
                            }
                        }
                    }) { Text("发布") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    /** 弹窗内的同步提示：Button 的回调不是 suspend 上下文，直接起一个 IO 协程走 suspend 版 Toast */
    private fun showToastSuspendInDialog(ctx: Context, message: String) {
        ensureScope().launch {
            runCatching { showToastSuspend(ctx, message) }
                .onFailure { WeLogger.e(TAG, "toast failed: $message", it) }
        }
    }
}
