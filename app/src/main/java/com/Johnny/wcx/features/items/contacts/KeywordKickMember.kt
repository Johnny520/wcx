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
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeGroupApi
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
import com.Johnny.wcx.utils.android.showToast
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
 * 关键词踢人。
 *
 * 监听群文本消息 → 命中关键词 → 撤回（可选）→ 调微信 `delchatroommember` 踢人 → 群内提示（可选）。
 *
 * ## 安全护栏（缺一不可）
 * 1. **只对白名单群生效**：白名单为空时功能等于关闭，避免「装完就乱踢」；
 * 2. **只有群主/管理员才真正执行**：自己不是群主也不是管理员时直接跳过（服务端也会拒）；
 * 3. **操作对象若是群主/管理员则跳过**（可配置），防止误踢管理者；
 * 4. 自己发的消息、非文本消息、撤回的系统消息一律忽略；
 * 5. **频率限制**：每个群每分钟最多 N 次（默认 5），防止一条恶意关键词把全群清空；
 * 6. 同一 `msgSvrId` 只处理一次（数据库回调可能重复触发）。
 *
 * ## 锚点（8.0.80，已用 anchor/tools 验证，详见 ANCHORS.md）
 * - 踢人接口：`WeGroupApi.delMember()` → NetScene `tn.q.<init>(String, List, int)`，
 *   CGI 常量 `/cgi-bin/micromsg-bin/delchatroommember`；
 * - 成员角色：`com.tencent.mm.storage.a3.r0(String) → vo.b`（成员数据，首个 int 字段即身份位），
 *   配合 chatroom 行的 `field_roomowner` 判断群主；管理员位为 `flags and 2048 != 0`
 *   （与既有功能「显示群成员身份」同一套锚点）。
 */
@Feature(
    name = "关键词踢人",
    categories = ["联系人与群组"],
    description = "群内消息命中关键词时自动撤回并踢出该成员, 仅对白名单群且仅群主/管理员生效"
)
object KeywordKickMember : ClickableFeature(), WeDatabaseListenerApi.IInsertListener, IResolveDex {

    private const val TAG = "KeywordKickMember"

    private const val MAX_HANDLED = 256
    private const val KICK_TIMEOUT_MS = 30_000L

    /** 身份位：群主 / 管理员 / 普通成员 */
    private const val ROLE_OWNER = 1
    private const val ROLE_ADMIN = 2
    private const val ROLE_MEMBER = 3

    /** 8.0.80 群成员数据里的「管理员」标志位 */
    private const val ADMIN_FLAG = 2048

    private var keywordsRaw by WePrefs.prefOption("group_kick_keywords", "")
    private var whitelistGroups by WePrefs.prefOption("group_kick_groups", emptySet<String>())
    private var recallMessage by WePrefs.prefOption("group_kick_recall", true)
    private var notifyOnKick by WePrefs.prefOption("group_kick_notify", true)
    private var skipAdminSender by WePrefs.prefOption("group_kick_skip_admin", true)
    private var rateLimitPerMinute by WePrefs.prefOption("group_kick_rate_limit", 5)
    private var noticeTemplate by WePrefs.prefOption(
        "group_kick_notice_tpl",
        "〔自动管理〕成员 {nickname} 触发关键词「{keyword}」，已被移出本群"
    )

    /**
     * 群成员数据查询：`com.tencent.mm.storage.a3.r0(String) → vo.b`（8.0.80）。
     *
     * 锚点组合：特征串 `getChatroomData hashMap is null!`（dex 全量扫描唯一命中该方法的 const-string）
     * + 单参数 String + 返回对象。`searchPackages` 限定 storage 包，进一步收窄。
     */
    private val methodGetChatroomMemberData by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("getChatroomData hashMap is null!")
            paramCount(1)
            paramTypes("java.lang.String")
        }
    }

    /** 踢人并发闸门：同一时刻只放行 2 个踢人请求，避免网络队列被打爆 */
    private val kickGate = Semaphore(2)

    private var scope: CoroutineScope? = null

    private val handledLock = Any()
    private val handledKeys = LruCache<String, Boolean>(32, 0.75f, MAX_HANDLED)

    /** 每个群的踢人时间戳（用于每分钟频率限制） */
    private val kickTimestamps = HashMap<String, ArrayDeque<Long>>()

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        WeDatabaseListenerApi.addListener(this)
        if (methodGetChatroomMemberData.isPlaceholder) {
            WeLogger.w(TAG, "群成员角色锚点未解析：管理员判定将退化为仅群主判定（不会误踢，只会少踢）")
        }
        WeLogger.i(TAG, "enabled")
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope?.cancel()
        scope = null
        synchronized(handledLock) {
            handledKeys.clear()
            kickTimestamps.clear()
        }
        WeLogger.i(TAG, "disabled")
    }

    // ==================== 消息监听 ====================

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val currentScope = scope ?: return

        val typeCode = values.getAsInteger("type") ?: return
        if (typeCode != GroupSystemMsgParser.GROUP_TEXT_MSG_TYPE) return

        val talker = values.getAsString("talker") ?: return
        if (!talker.isGroupChatWxId) return

        // 护栏 1：只对白名单群生效（空白名单 = 功能关闭）
        val whitelistSnapshot = whitelistGroups
        if (whitelistSnapshot.isEmpty() || talker !in whitelistSnapshot) return

        val isSend = values.getAsInteger("isSend") ?: 0
        if (isSend != 0) return

        val content = values.getAsString("content") ?: return
        val keywords = parseKeywords()
        if (keywords.isEmpty()) return

        val hitKeyword = keywords.firstOrNull { content.contains(it, ignoreCase = true) } ?: return

        // 群消息正文形如 "wxid_xxx:\n正文"
        val senderWxId = content.substringBefore(':', "").trim()
        if (senderWxId.isEmpty() || senderWxId.length > 64 || senderWxId.any { it == '\n' || it == ' ' }) {
            WeLogger.d(TAG, "skip: cannot resolve sender from content")
            return
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: 0L
        if (!markHandled("$talker|$msgSvrId")) return

        currentScope.launch {
            try {
                withTimeoutOrNull(KICK_TIMEOUT_MS) {
                    handleKick(
                        groupId = talker,
                        senderWxId = senderWxId,
                        keyword = hitKeyword,
                        msgSvrId = msgSvrId,
                    )
                } ?: WeLogger.w(TAG, "kick task timeout: group=$talker sender=$senderWxId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WeLogger.e(TAG, "kick task failed: group=$talker sender=$senderWxId", e)
            }
        }
    }

    private suspend fun handleKick(
        groupId: String,
        senderWxId: String,
        keyword: String,
        msgSvrId: Long,
    ) {
        val selfWxId = WeApi.selfWxId
        if (selfWxId.isEmpty()) {
            WeLogger.w(TAG, "self wxId unavailable, skip")
            return
        }
        if (senderWxId == selfWxId) return

        // 护栏 2：自己必须是群主或管理员
        val selfRole = resolveRole(groupId, selfWxId)
        if (selfRole == ROLE_MEMBER) {
            WeLogger.w(TAG, "skip kick in $groupId: self is not owner/admin")
            return
        }

        // 护栏 3：不要踢群主/管理员
        if (skipAdminSender) {
            val senderRole = resolveRole(groupId, senderWxId)
            if (senderRole != ROLE_MEMBER) {
                WeLogger.i(TAG, "skip kick $senderWxId: owner/admin in $groupId")
                return
            }
        }

        // 护栏 5：频率限制
        if (!acquireKickQuota(groupId)) {
            WeLogger.w(TAG, "kick rate limit reached for $groupId, skip $senderWxId")
            return
        }

        if (recallMessage && msgSvrId > 0L) {
            val recalled = runCatching { WeMessageApi.revokeMsgByMsgSvrId(msgSvrId) }
                .onFailure { WeLogger.e(TAG, "recall failed: msgSvrId=$msgSvrId", it) }
                .getOrDefault(false)
            WeLogger.i(TAG, "recall msgSvrId=$msgSvrId result=$recalled")
        }

        kickGate.withPermit {
            runCatching { WeGroupApi.delMember(groupId, senderWxId) }
                .onFailure { WeLogger.e(TAG, "delMember failed: $senderWxId in $groupId", it) }
        }
        WeLogger.i(TAG, "kick requested: $senderWxId in $groupId, keyword=$keyword")

        if (notifyOnKick) {
            val nickname = runCatching { WeDatabaseApi.getGroupMemberDisplayName(groupId, senderWxId) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: senderWxId
            val text = noticeTemplate
                .replace("{nickname}", nickname)
                .replace("{keyword}", keyword)
                .replace("{group}", runCatching { WeDatabaseApi.getDisplayName(groupId) }.getOrDefault(groupId))
            if (text.isNotBlank()) {
                val ok = runCatching { WeMessageApi.sendText(groupId, text) }.getOrDefault(false)
                if (!ok) WeLogger.e(TAG, "kick notice send failed: group=$groupId")
            }
        }
    }

    /**
     * 读取成员身份：1=群主，2=管理员，3=普通成员。
     *
     * 群主来自 chatroom 行的 `field_roomowner`；管理员来自群成员数据的身份位（`and 2048`）。
     * 任一环节失败都按「普通成员」处理——对「自己」意味着不执行（安全），对「对方」意味着
     * 可能被多踢一人，因此仅在 [methodGetChatroomMemberData] 已解析时才走管理员判定。
     */
    private fun resolveRole(groupId: String, wxId: String): Int {
        if (wxId.isBlank()) return ROLE_MEMBER
        return runCatching {
            val group = WeConversationApi.getGroup(groupId)
            val owner = group.reflekt()
                .firstFieldOrNull {
                    name = "field_roomowner"
                    superclass()
                }
                ?.get() as? String
            if (!owner.isNullOrEmpty() && owner == wxId) return@runCatching ROLE_OWNER

            val delegate = methodGetChatroomMemberData
            if (delegate.isPlaceholder) return@runCatching ROLE_MEMBER

            val memberData = delegate.method.invoke(group, wxId) ?: return@runCatching ROLE_MEMBER
            val flags = memberData.reflekt()
                .firstFieldOrNull { type = Int::class }
                ?.get() as? Int ?: return@runCatching ROLE_MEMBER
            if (flags and ADMIN_FLAG != 0) ROLE_ADMIN else ROLE_MEMBER
        }.getOrElse { e ->
            WeLogger.e(TAG, "resolveRole failed: group=$groupId wxId=$wxId", e)
            ROLE_MEMBER
        }
    }

    private fun acquireKickQuota(groupId: String): Boolean = synchronized(handledLock) {
        val now = System.currentTimeMillis()
        val limit = rateLimitPerMinute.coerceIn(1, 60)
        val stamps = kickTimestamps.getOrPut(groupId) { ArrayDeque() }
        while (stamps.isNotEmpty() && now - stamps.first() > 60_000L) stamps.removeFirst()
        if (stamps.size >= limit) {
            false
        } else {
            stamps.addLast(now)
            true
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

    /** 逗号分隔（中英文逗号、分号、空格都支持） */
    private fun parseKeywords(): List<String> =
        keywordsRaw.split(',', '，', ';', '；', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 64 }
            .distinct()

    // ==================== 设置界面 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var keywordsState by remember { mutableStateOf(keywordsRaw) }
            var recallState by remember { mutableStateOf(recallMessage) }
            var notifyState by remember { mutableStateOf(notifyOnKick) }
            var skipAdminState by remember { mutableStateOf(skipAdminSender) }
            var rateState by remember { mutableStateOf(rateLimitPerMinute.toString()) }
            var noticeState by remember { mutableStateOf(noticeTemplate) }
            var whitelistState by remember { mutableStateOf(whitelistGroups) }
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
                title = { Text("关键词踢人") },
                text = {
                    DefaultColumn(scrollable = true) {
                        Text("仅在白名单群生效；只有你是群主或管理员时才会真正执行。")
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
                            headlineContent = { Text("生效群白名单（必填）") },
                            supportingContent = {
                                Text(
                                    if (whitelistState.isEmpty()) {
                                        "尚未选择任何群，功能不会生效（共 ${groups.size} 个群）"
                                    } else {
                                        "已选 ${whitelistState.size} 个群"
                                    }
                                )
                            }
                        )
                        OutlinedTextField(
                            value = keywordsState,
                            onValueChange = { keywordsState = it },
                            label = { Text("关键词（逗号分隔）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = rateState,
                            onValueChange = { input -> rateState = input.filter { it.isDigit() }.take(2) },
                            label = { Text("每个群每分钟最多踢几人（1~60）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        ListItem(
                            modifier = Modifier.clickable { recallState = !recallState },
                            trailingContent = { Switch(checked = recallState, onCheckedChange = null) },
                            headlineContent = { Text("踢人前撤回该消息") },
                            supportingContent = { Text("失败不影响踢人（例如消息已过期）") }
                        )
                        ListItem(
                            modifier = Modifier.clickable { skipAdminState = !skipAdminState },
                            trailingContent = { Switch(checked = skipAdminState, onCheckedChange = null) },
                            headlineContent = { Text("发送者是管理员时跳过") },
                            supportingContent = { Text("建议保持开启，避免与群内其他管理者冲突") }
                        )
                        ListItem(
                            modifier = Modifier.clickable { notifyState = !notifyState },
                            trailingContent = { Switch(checked = notifyState, onCheckedChange = null) },
                            headlineContent = { Text("踢人后在群内提示") }
                        )
                        if (notifyState) {
                            OutlinedTextField(
                                value = noticeState,
                                onValueChange = { noticeState = it },
                                label = { Text("提示文案（{nickname} / {keyword} / {group}）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        keywordsRaw = keywordsState
                        whitelistGroups = whitelistState
                        recallMessage = recallState
                        skipAdminSender = skipAdminState
                        notifyOnKick = notifyState
                        rateLimitPerMinute = (rateState.toIntOrNull() ?: 5).coerceIn(1, 60)
                        noticeTemplate = noticeState.ifBlank {
                            "〔自动管理〕成员 {nickname} 触发关键词「{keyword}」，已被移出本群"
                        }
                        if (whitelistGroups.isEmpty()) {
                            showToast(context, "未选择生效群，关键词踢人不会执行")
                        }
                        WeLogger.i(TAG, "settings saved")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
