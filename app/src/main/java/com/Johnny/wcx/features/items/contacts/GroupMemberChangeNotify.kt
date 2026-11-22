package com.Johnny.wcx.features.items.contacts

import com.Johnny.wcx.utils.strings.isGroupChatWxId
import android.content.ContentValues
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
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.collections.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 群成员变动通知。
 *
 * 与「入群欢迎语」共用同一套系统消息解析（[GroupSystemMsgParser]）与同一个底层
 * 数据库 insert hook（`WeDatabaseListenerApi`），两者互不依赖、各自独立开关。
 *
 * 支持把变动播报到「当前群」和/或「另一个指定群」（例如管理群 / 通知群）。
 */
@Feature(
    name = "群成员变动通知",
    categories = ["联系人与群组"],
    description = "成员加入 / 退出 / 被移出群聊时, 在群里或指定通知群发送提示消息"
)
object GroupMemberChangeNotify : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "GroupMemberChangeNotify"

    private const val MAX_HANDLED = 256
    private const val SEND_TIMEOUT_MS = 30_000L

    private var toCurrentGroup by WePrefs.prefOption("group_notify_to_current", true)
    private var extraEnabled by WePrefs.prefOption("group_notify_extra_enabled", false)
    private var extraGroup by WePrefs.prefOption("group_notify_extra_group", "")
    private var joinTemplate by WePrefs.prefOption("group_notify_join_tpl", "【群动态】{nickname} 加入了 {group}")
    private var leaveTemplate by WePrefs.prefOption("group_notify_leave_tpl", "【群动态】{nickname} 退出了 {group}")
    private var kickTemplate by WePrefs.prefOption("group_notify_kick_tpl", "【群动态】{nickname} 被移出了 {group}")

    private val sendGate = Semaphore(2)

    private var scope: CoroutineScope? = null

    private val handledLock = Any()
    private val handledKeys = LruCache<String, Boolean>(32, 0.75f, MAX_HANDLED)

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WeDatabaseListenerApi.addListener(this)
        WeLogger.i(TAG, "enabled")
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope?.cancel()
        scope = null
        synchronized(handledLock) { handledKeys.clear() }
        WeLogger.i(TAG, "disabled")
    }

    // ==================== 系统消息监听 ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val currentScope = scope ?: return

        val typeCode = values.getAsInteger("type") ?: return
        if (!GroupSystemMsgParser.isSystemMessage(typeCode)) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.isGroupChatWxId) return

        val event = GroupSystemMsgParser.parse(values.getAsString("content")) ?: return
        if (event.targets.isEmpty()) return
        if (!toCurrentGroup && !(extraEnabled && extraGroup.isNotBlank())) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
        if (!markHandled("$talker|$msgSvrId|${event.kind}|${event.displayNames}")) return

        val templateSnapshot = when (event.kind) {
            GroupSystemEventKind.JOIN -> joinTemplate
            GroupSystemEventKind.LEAVE -> leaveTemplate
            GroupSystemEventKind.KICK -> kickTemplate
        }
        if (templateSnapshot.isBlank()) return

        currentScope.launch {
            try {
                withTimeoutOrNull(SEND_TIMEOUT_MS) {
                    val groupName = runCatching { WeDatabaseApi.getDisplayName(talker) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: talker
                    val text = renderTemplate(
                        templateText = templateSnapshot,
                        nickname = event.displayNames,
                        groupName = groupName,
                        action = event.actionText,
                        operatorName = event.operator ?: ""
                    )

                    val targets = mutableListOf<String>()
                    if (toCurrentGroup) targets += talker
                    val extra = extraGroup.trim()
                    if (extraEnabled && extra.isGroupChatWxId && extra != talker) targets += extra

                    for (target in targets) {
                        val ok = sendGate.withPermit { WeMessageApi.sendText(target, text) }
                        if (ok) {
                            WeLogger.i(TAG, "notify sent to $target: ${event.kind}")
                        } else {
                            WeLogger.e(TAG, "notify send failed: group=$target kind=${event.kind}")
                        }
                    }
                } ?: WeLogger.w(TAG, "notify task timeout: group=$talker")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "notify task failed: group=$talker", e)
            }
        }
    }

    private fun markHandled(key: String): Boolean = synchronized(handledLock) {
        if (handledKeys.containsKey(key)) {
            false
        } else {
            handledKeys[key] = true
            true
        }
    }

    private fun renderTemplate(
        templateText: String,
        nickname: String,
        groupName: String,
        action: String,
        operatorName: String,
    ): String = templateText
        .replace("{nickname}", nickname)
        .replace("{group}", groupName)
        .replace("{action}", action)
        .replace("{operator}", operatorName)

    // ==================== 设置界面 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var toCurrentState by remember { mutableStateOf(toCurrentGroup) }
            var extraEnabledState by remember { mutableStateOf(extraEnabled) }
            var extraGroupState by remember { mutableStateOf(extraGroup) }
            var joinState by remember { mutableStateOf(joinTemplate) }
            var leaveState by remember { mutableStateOf(leaveTemplate) }
            var kickState by remember { mutableStateOf(kickTemplate) }
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
                title = { Text("群成员变动通知") },
                text = {
                    DefaultColumn(scrollable = true) {
                        ListItem(
                            modifier = Modifier.clickable { toCurrentState = !toCurrentState },
                            trailingContent = { Switch(checked = toCurrentState, onCheckedChange = null) },
                            headlineContent = { Text("发送到当前群") },
                            supportingContent = { Text("成员变动时, 在该群内发一条提示消息") }
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                extraEnabledState = !extraEnabledState
                            },
                            trailingContent = {
                                Switch(checked = extraEnabledState, onCheckedChange = null)
                            },
                            headlineContent = { Text("额外推送到指定群") },
                            supportingContent = {
                                Text("适合把多个群的成员变动汇总到一个管理群")
                            }
                        )
                        if (extraEnabledState) {
                            ListItem(
                                modifier = Modifier.clickable {
                                    showComposeDialog(context) {
                                        SingleContactSelector(
                                            title = "选择接收通知的群聊",
                                            contacts = groups,
                                            initialSelectedWxId = extraGroupState.takeIf { it.isNotBlank() },
                                            onDismiss = onDismiss,
                                            onConfirm = { selected ->
                                                extraGroupState = selected
                                                onDismiss()
                                            }
                                        )
                                    }
                                },
                                headlineContent = { Text("选择通知群") },
                                supportingContent = {
                                    Text(
                                        if (extraGroupState.isBlank()) {
                                            "尚未选择（共 ${groups.size} 个群可选）"
                                        } else {
                                            val name = groups.firstOrNull { it.wxId == extraGroupState }?.displayName
                                            name ?: extraGroupState
                                        }
                                    )
                                }
                            )
                        }
                        Text("提示文案模板（可用变量：{nickname} 成员昵称、{group} 群名、{action} 动作、{operator} 操作者）")
                        OutlinedTextField(
                            value = joinState,
                            onValueChange = { joinState = it },
                            label = { Text("加入群聊模板") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = leaveState,
                            onValueChange = { leaveState = it },
                            label = { Text("退出群聊模板") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = kickState,
                            onValueChange = { kickState = it },
                            label = { Text("被移出群聊模板") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        toCurrentGroup = toCurrentState
                        extraEnabled = extraEnabledState
                        extraGroup = extraGroupState
                        joinTemplate = joinState.ifBlank { "【群动态】{nickname} 加入了 {group}" }
                        leaveTemplate = leaveState.ifBlank { "【群动态】{nickname} 退出了 {group}" }
                        kickTemplate = kickState.ifBlank { "【群动态】{nickname} 被移出了 {group}" }
                        WeLogger.i(TAG, "settings saved")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
