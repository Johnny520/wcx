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
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.collections.LruCache
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 入群欢迎语。
 *
 * 监听 `message` 表插入：`type == 10000`（系统消息）且正文命中「加入群聊」类文案时，
 * 延迟若干秒后向该群发送欢迎语，支持 `{nickname}` / `{group}` 变量。
 *
 * ## 与「群成员变动通知」的关系
 * 两者共用同一个底层 hook（`WeDatabaseListenerApi` 只 hook 一次 `insertWithOnConflict`，
 * 各 Feature 只是往 listener 列表注册），解析逻辑也共用 [GroupSystemMsgParser]，
 * 因此不会产生重复 hook；两个功能各自独立开关、互不影响。
 *
 * ## @ 新成员的实现说明（降级）
 * `WeMessageApi.sendText(toUser, text)` **没有** atList 参数（已读 `_dev` 源码确认），
 * 微信真正的 @ 是服务器看 `msgsource` 里的 `<atuserlist>` 才推「有人@我」提醒，
 * 而系统消息里只有昵称、没有新成员 wxid，且注入 atuserlist 需要拦截本机出网消息的
 * msgsource 合并（见 `MentionMembers` 的隐蔽 @ 实现，锚点
 * `(?s)<alnode[^>]*>.*?</alnode>`），链路长且依赖昵称→wxid 反查，不可靠。
 * 因此这里按规范降级为**纯文本 `@昵称` 前缀**：视觉上像 @，但不会给对方推提醒。
 */
@Feature(
    name = "入群欢迎语",
    categories = ["联系人与群组"],
    description = "新成员入群时自动发送欢迎语, 支持 {nickname} / {group} 变量、白名单群与延迟发送"
)
object GroupWelcomeMessage : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "GroupWelcomeMessage"

    /** 处理记录上限，避免长期运行内存增长 */
    private const val MAX_HANDLED = 256

    private const val SCOPE_ALL_GROUP = 0
    private const val SCOPE_WHITELIST = 1

    /** 单次欢迎流程总超时（含延迟），防止协程挂死 */
    private const val SEND_TIMEOUT_MS = 60_000L

    private var template by WePrefs.prefOption(
        "group_welcome_template",
        "欢迎 {nickname} 加入 {group}，请先看一下群公告～"
    )
    private var scopeMode by WePrefs.prefOption("group_welcome_scope", SCOPE_ALL_GROUP)
    private var whitelist by WePrefs.prefOption("group_welcome_whitelist", emptySet<String>())
    private var delaySeconds by WePrefs.prefOption("group_welcome_delay", 3)
    private var atNewMember by WePrefs.prefOption("group_welcome_at", true)

    /** 发送并发闸门：一次拉进几十个人时不至于把微信网络队列打满 */
    private val sendGate = Semaphore(2)

    private var scope: CoroutineScope? = null

    private val handledLock = Any()

    /** msgSvrId 去重（同一条系统消息可能被数据库回调多次触发） */
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
        if (event.kind != GroupSystemEventKind.JOIN) return

        if (scopeMode == SCOPE_WHITELIST && talker !in whitelist) {
            WeLogger.d(TAG, "skip $talker: not in whitelist")
            return
        }

        val templateSnapshot = template
        if (templateSnapshot.isBlank()) return

        val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
        if (!markHandled("$talker|$msgSvrId|${event.displayNames}")) return

        val nicknames = event.displayNames.ifBlank { "新成员" }
        val delayMs = delaySeconds.coerceIn(0, 120) * 1000L
        val atNewMemberFlag = atNewMember

        currentScope.launch {
            try {
                withTimeoutOrNull(SEND_TIMEOUT_MS) {
                    val groupName = runCatching { WeDatabaseApi.getDisplayName(talker) }
                        .getOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: talker
                    val body = renderTemplate(templateSnapshot, nicknames, groupName)
                    val text = if (atNewMemberFlag) "@$nicknames $body" else body

                    if (delayMs > 0) delay(delayMs)

                    val ok = sendGate.withPermit { WeMessageApi.sendText(talker, text) }
                    if (ok) {
                        WeLogger.i(TAG, "welcome sent to $talker for $nicknames")
                    } else {
                        WeLogger.e(TAG, "welcome send failed: group=$talker")
                        showToastSuspend("入群欢迎语发送失败（$groupName）")
                    }
                } ?: WeLogger.w(TAG, "welcome task timeout: group=$talker")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "welcome task failed: group=$talker", e)
                runCatching { showToastSuspend("入群欢迎语发送异常") }
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

    private fun renderTemplate(templateText: String, nickname: String, groupName: String): String =
        templateText
            .replace("{nickname}", nickname)
            .replace("{group}", groupName)

    // ==================== 设置界面 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var templateState by remember { mutableStateOf(template) }
            var delayState by remember { mutableStateOf(delaySeconds.toString()) }
            var scopeState by remember { mutableStateOf(scopeMode) }
            var atState by remember { mutableStateOf(atNewMember) }
            var whitelistState by remember { mutableStateOf(whitelist) }
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
                title = { Text("入群欢迎语") },
                text = {
                    DefaultColumn(scrollable = true) {
                        ListItem(
                            headlineContent = { Text("欢迎词模板") },
                            supportingContent = { Text("可用变量：{nickname} 新成员昵称、{group} 群名") }
                        )
                        OutlinedTextField(
                            value = templateState,
                            onValueChange = { templateState = it },
                            label = { Text("欢迎词模板") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = delayState,
                            onValueChange = { input -> delayState = input.filter { it.isDigit() }.take(3) },
                            label = { Text("发送延迟（秒，0~120）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("延迟可避开微信刚入群时的系统提示刷屏，建议 3 秒左右")
                        ListItem(
                            modifier = Modifier.clickable { atState = !atState },
                            trailingContent = { Switch(checked = atState, onCheckedChange = null) },
                            headlineContent = { Text("在欢迎语前 @ 新成员") },
                            supportingContent = {
                                Text("以纯文本「@昵称」形式发出：微信真正的 @ 提醒需要注入 atuserlist，当前版本未实现")
                            }
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                scopeState = if (scopeState == SCOPE_WHITELIST) {
                                    SCOPE_ALL_GROUP
                                } else {
                                    SCOPE_WHITELIST
                                }
                            },
                            trailingContent = {
                                Switch(checked = scopeState == SCOPE_WHITELIST, onCheckedChange = null)
                            },
                            headlineContent = { Text("仅对白名单群生效") },
                            supportingContent = {
                                Text(
                                    if (scopeState == SCOPE_WHITELIST) {
                                        "当前：白名单 ${whitelistState.size} 个群"
                                    } else {
                                        "当前：全部群"
                                    }
                                )
                            }
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = "选择生效的群聊",
                                        contacts = groups,
                                        initialSelectedWxIds = whitelistState,
                                        onDismiss = onDismiss,
                                        onConfirm = { selected ->
                                            whitelistState = selected
                                            onDismiss()
                                        }
                                    )
                                }
                            },
                            headlineContent = { Text("配置白名单群") },
                            supportingContent = { Text("点击选择群聊（共 ${groups.size} 个）") }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        template = templateState.ifBlank {
                            "欢迎 {nickname} 加入 {group}，请先看一下群公告～"
                        }
                        delaySeconds = (delayState.toIntOrNull() ?: 3).coerceIn(0, 120)
                        scopeMode = scopeState
                        atNewMember = atState
                        whitelist = whitelistState
                        WeLogger.i(TAG, "settings saved")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
