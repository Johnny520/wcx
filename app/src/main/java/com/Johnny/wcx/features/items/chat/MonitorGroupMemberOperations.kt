package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.net.models.protobuf.ChatRoomDataProto
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.activity.TransparentActivity
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.reflection.BString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf

@Feature(
    name = "群成员行为监控",
    categories = ["联系人与群组"],
    description = "体系A：自动发送进退群消息到群内 + 体系B：仅本人可见进退群提醒，两套功能独立开关、存储隔离"
)
object MonitorGroupMemberOperations : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "GroupMemberBehaviorMonitor"

    // =========================================================================
    // 体系A: 对外群内自动发送消息
    // =========================================================================
    private var systemAEnabled by prefOption("gbm_system_a_enabled", false)

    @Serializable
    data class GroupConfig(
        val welcomeText: String = "欢迎 {nickname} 加入群聊！",
        val atNewMember: Boolean = true,
        val extraEnabled: Boolean = false,
        val extraSource: String = "local",
        val extraPath: String = "",
        val extraType: String = "text",
        val leaveText: String = "{nickname} 退出了群聊，祝他/她前程似锦！",
        val delaySeconds: Int = 0
    )

    private var groupConfigsJson by prefOption("gbm_group_configs", "{}")

    private fun getGroupConfigs(): Map<String, GroupConfig> {
        return runCatching {
            json.decodeFromString<Map<String, GroupConfig>>(groupConfigsJson)
        }.getOrElse { emptyMap() }
    }

    private fun getGroupConfig(group: String): GroupConfig {
        return getGroupConfigs()[group] ?: GroupConfig()
    }

    private fun hasGroupConfig(group: String): Boolean {
        return group in getGroupConfigs()
    }

    private fun saveGroupConfig(group: String, config: GroupConfig) {
        val configs = getGroupConfigs().toMutableMap()
        configs[group] = config
        groupConfigsJson = json.encodeToString(configs)
    }

    // =========================================================================
    // 体系B: 仅本人可见进退群提醒
    // =========================================================================
    private var systemBEnabled by prefOption("gbm_system_b_enabled", false)

    // =========================================================================
    // 通用: 全局进/退群事件开关（保留兼容旧逻辑）
    // =========================================================================
    private var enableLeaveNotify by prefOption("group_member_leave_notify", true)
    private var enableJoinNotify by prefOption("group_member_join_notify", true)

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // 生命周期
    // =========================================================================
    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        runCatching {
            methodHandleSpanClick.hookBefore {
                val url = args[1].reflekt().firstField {
                    type = BString
                    modifiers(Modifiers.FINAL)
                }.get()!! as String
                if (!url.startsWith("weixin://weixinhongbao/wekit/chatroom_userinfo/")) return@hookBefore

                val wxId = url.substringAfterLast('/')
                val context = (args[0] as View).context
                WeApi.openContact(context, wxId, WeApi.OpenContactDestination.HOMEPAGE)
            }
        }.onFailure { WeLogger.e(TAG, "failed to hook methodHandleSpanClick", it) }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    private val methodHandleSpanClick by dexMethod {
        matcher {
            declaredClass = $$"com.tencent.mm.app.plugin.URISpanHandlerSet\$LuckyMoneyUriSpanHandler"
            usingEqStrings("MicroMsg.URISpanHandlerSet", "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null")
        }
    }

    // =========================================================================
    // 数据库监听 — 检测成员进退群
    // =========================================================================
    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return
        if (!systemAEnabled && !systemBEnabled) return

        val group = values.getAsString("chatroomname") ?: return
        val newRawMembers = values.getAsString("memberlist")
        val newRoomData = values.getAsByteArray("roomdata")

        val cursor = WeDatabaseApi.rawQuery(
            "SELECT memberlist,memberCount,roomdata FROM chatroom WHERE chatroomname = ?",
            arrayOf(group)
        )

        runCatching {
            cursor.use { cursor ->
                if (!cursor.moveToFirst()) return
                val origRawMembers = cursor.getString(cursor.getColumnIndex("memberlist"))
                if (origRawMembers.isNullOrEmpty()) return
                val origMembers = origRawMembers.split(';')

                val origRoomData = cursor.getBlob(cursor.getColumnIndex("roomdata"))
                val origDisplayNames = parseRoomData(origRoomData)
                val newDisplayNames = parseRoomData(newRoomData)

                handleMemberChange(group, origMembers, origDisplayNames, newRawMembers, newDisplayNames)
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    override fun onInsert(table: String, values: ContentValues) {}

    private fun handleMemberChange(
        group: String,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newDisplayNames: Map<String, String>
    ) {
        if (newRawMembers == null) return

        val origSet = origMembers.toSet()
        val newSet = newRawMembers.split(';').toSet()

        val leavers = origSet - newSet
        val joiners = newSet - origSet

        // 体系A: 对外发送消息（仅勾选的群生效）
        if (systemAEnabled) {
            if (hasGroupConfig(group)) {
                val groupConfig = getGroupConfig(group)
                handleSystemAEvents(group, groupConfig, leavers, joiners, origDisplayNames, newDisplayNames)
            }
        }

        // 体系B: 本地提醒（全局生效，无需勾选群）
        if (systemBEnabled) {
            handleSystemBEvents(group, leavers, joiners, origDisplayNames, newDisplayNames)
        }
    }

    // =========================================================================
    // 体系A: 对外发送消息
    // =========================================================================
    private fun handleSystemAEvents(
        group: String,
        config: GroupConfig,
        leavers: Set<String>,
        joiners: Set<String>,
        origDisplayNames: Map<String, String>,
        newDisplayNames: Map<String, String>
    ) {
        if (enableLeaveNotify) {
            leavers.forEach { wxId ->
                val displayName = getDisplayName(wxId, origDisplayNames)
                sendSystemALeaveMessage(group, config, wxId, displayName)
            }
        }

        if (enableJoinNotify) {
            joiners.forEach { wxId ->
                val displayName = getDisplayName(wxId, newDisplayNames)
                sendSystemAJoinMessage(group, config, wxId, displayName)
            }
        }
    }

    private fun sendSystemAJoinMessage(group: String, config: GroupConfig, wxId: String, displayName: String) {
        val delayMs = config.delaySeconds * 1000L
        if (delayMs > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                delay(delayMs)
                doSendSystemAJoinMessage(group, config, wxId, displayName)
            }
        } else {
            doSendSystemAJoinMessage(group, config, wxId, displayName)
        }
    }

    private fun doSendSystemAJoinMessage(group: String, config: GroupConfig, wxId: String, displayName: String) {
        runCatching {
            // 1. 发送第一条欢迎文案（可选@新人）
            val welcomeText = config.welcomeText
                .replace("{nickname}", displayName)
                .replace("{displayName}", displayName)
                .replace("{wxId}", wxId)
                .replace("{group}", group)
            val textToSend = if (config.atNewMember) "@$displayName $welcomeText" else welcomeText
            WeMessageApi.sendText(group, textToSend)

            // 2. 发送附加内容（如果开启且素材非空）
            if (config.extraEnabled && config.extraPath.isNotBlank()) {
                sendExtraContent(group, config)
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to send system A join message", it)
        }
    }

    private fun sendSystemALeaveMessage(group: String, config: GroupConfig, wxId: String, displayName: String) {
        val delayMs = config.delaySeconds * 1000L
        if (delayMs > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                delay(delayMs)
                doSendSystemALeaveMessage(group, config, wxId, displayName)
            }
        } else {
            doSendSystemALeaveMessage(group, config, wxId, displayName)
        }
    }

    private fun doSendSystemALeaveMessage(group: String, config: GroupConfig, wxId: String, displayName: String) {
        runCatching {
            val leaveText = config.leaveText
                .replace("{nickname}", displayName)
                .replace("{displayName}", displayName)
                .replace("{wxId}", wxId)
                .replace("{group}", group)
            WeMessageApi.sendText(group, leaveText)
        }.onFailure {
            WeLogger.e(TAG, "failed to send system A leave message", it)
        }
    }

    private fun sendExtraContent(group: String, config: GroupConfig) {
        runCatching {
            when (config.extraType) {
                "text" -> {
                    if (config.extraPath.isNotBlank()) {
                        WeMessageApi.sendText(group, config.extraPath)
                    }
                }
                "image" -> WeMessageApi.sendImage(group, config.extraPath)
                "video" -> WeMessageApi.sendVideo(group, config.extraPath)
                "voice" -> WeMessageApi.sendVoice(group, config.extraPath, 0)
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to send extra content", it)
        }
    }

    // =========================================================================
    // 体系B: 本地提醒（仅本人可见）
    // =========================================================================
    private fun handleSystemBEvents(
        group: String,
        leavers: Set<String>,
        joiners: Set<String>,
        origDisplayNames: Map<String, String>,
        newDisplayNames: Map<String, String>
    ) {
        if (enableLeaveNotify) {
            leavers.forEach { wxId ->
                val displayName = getDisplayName(wxId, origDisplayNames)
                val groupName = WeDatabaseApi.getDisplayName(group)
                val content = "[本地提醒] $displayName 退出了群聊「$groupName」"
                WeMessageApi.createSimpleMsgInfoAndInsert(
                    type = MessageType.SYSTEM.code,
                    talker = group,
                    content = content,
                    currentTime = System.currentTimeMillis()
                )
            }
        }

        if (enableJoinNotify) {
            joiners.forEach { wxId ->
                val displayName = getDisplayName(wxId, newDisplayNames)
                val groupName = WeDatabaseApi.getDisplayName(group)
                val content = "[本地提醒] $displayName 加入了群聊「$groupName」"
                WeMessageApi.createSimpleMsgInfoAndInsert(
                    type = MessageType.SYSTEM.code,
                    talker = group,
                    content = content,
                    currentTime = System.currentTimeMillis()
                )
            }
        }
    }

    // =========================================================================
    // 通用工具方法
    // =========================================================================
    private fun getDisplayName(wxId: String, roomDisplayNames: Map<String, String>): String {
        val roomName = roomDisplayNames[wxId] ?: ""
        if (roomName.isNotEmpty()) return roomName
        val dbName = WeDatabaseApi.getDisplayName(wxId)
        return dbName.ifEmpty { wxId }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseRoomData(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        return runCatching {
            ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                .members.associate { it.wxId to it.displayName }
        }.getOrElse { emptyMap() }
    }

    // =========================================================================
    // UI — 设置页面
    // =========================================================================
    private fun pickExtraFile(
        activity: ComponentActivity,
        extraType: String,
        onResult: (String) -> Unit
    ) {
        when (extraType) {
            "image" -> {
                TransparentActivity.launch(activity) {
                    val launcher = registerForActivityResult(
                        ActivityResultContracts.PickVisualMedia()
                    ) { uri ->
                        finish()
                        if (uri != null) {
                            activity.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            onResult(uri.toString())
                        }
                    }
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            }
            "video" -> {
                TransparentActivity.launch(activity) {
                    val launcher = registerForActivityResult(
                        ActivityResultContracts.PickVisualMedia()
                    ) { uri ->
                        finish()
                        if (uri != null) {
                            activity.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            onResult(uri.toString())
                        }
                    }
                    launcher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }
            }
            "voice" -> {
                TransparentActivity.launch(activity) {
                    val launcher = registerForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri ->
                        finish()
                        if (uri != null) {
                            activity.contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            onResult(uri.toString())
                        }
                    }
                    launcher.launch(arrayOf("audio/*"))
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var showSystemAConfig by remember { mutableStateOf(false) }
            var showGroupDetail by remember { mutableStateOf<String?>(null) }

            var systemAEnabledState by remember { mutableStateOf(systemAEnabled) }
            var systemBEnabledState by remember { mutableStateOf(systemBEnabled) }

            when {
                showGroupDetail != null -> {
                    val groupWxId = showGroupDetail!!
                    val currentConfig = remember(groupWxId) { getGroupConfig(groupWxId) }
                    SystemAGroupConfigScreen(
                        context = context,
                        groupWxId = groupWxId,
                        config = currentConfig,
                        onBack = { showGroupDetail = null },
                        onSave = { newConfig ->
                            saveGroupConfig(groupWxId, newConfig)
                            showGroupDetail = null
                            showToast("群聊配置已保存")
                        }
                    )
                }
                showSystemAConfig -> {
                    SystemAGroupListScreen(
                        onBack = { showSystemAConfig = false },
                        onGroupClick = { showGroupDetail = it },
                        onSave = { showToast("群聊配置已保存") }
                    )
                }
                else -> {
                    MainSettingsScreen(
                        systemAEnabled = systemAEnabledState,
                        systemBEnabled = systemBEnabledState,
                        onSystemAChange = { systemAEnabledState = it },
                        onSystemBChange = { systemBEnabledState = it },
                        onOpenSystemAConfig = { showSystemAConfig = true },
                        onDismiss = onDismiss,
                        onSave = {
                            systemAEnabled = systemAEnabledState
                            systemBEnabled = systemBEnabledState
                            showToast("设置已保存")
                            onDismiss()
                        }
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MainSettingsScreen(
        systemAEnabled: Boolean,
        systemBEnabled: Boolean,
        onSystemAChange: (Boolean) -> Unit,
        onSystemBChange: (Boolean) -> Unit,
        onOpenSystemAConfig: () -> Unit,
        onDismiss: () -> Unit,
        onSave: () -> Unit
    ) {
        AlertDialogContent(
            title = { Text("群成员行为监控") },
            text = {
                DefaultColumn(scrollable = true) {
                    // 体系A全局开关
                    Text(
                        "体系A：对外自动通知",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    ListItem(
                        modifier = Modifier.clickable { onSystemAChange(!systemAEnabled) },
                        trailingContent = {
                            Switch(checked = systemAEnabled, onCheckedChange = null)
                        },
                        headlineContent = { Text("A组全局总开关") },
                        supportingContent = { Text("开启后，勾选的群聊将自动发送进退群消息") }
                    )

                    if (systemAEnabled) {
                        Spacer(Modifier.height(4.dp))
                        ListItem(
                            modifier = Modifier.clickable { onOpenSystemAConfig() },
                            headlineContent = { Text("群聊设置") },
                            supportingContent = { Text("选择目标群聊并配置欢迎/退群文案") }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // 体系B全局开关
                    Text(
                        "体系B：仅本人可见提醒",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ListItem(
                        modifier = Modifier.clickable { onSystemBChange(!systemBEnabled) },
                        trailingContent = {
                            Switch(checked = systemBEnabled, onCheckedChange = null)
                        },
                        headlineContent = { Text("B组全局总开关") },
                        supportingContent = { Text("开启后，所有群的进退群事件都将本地提醒，仅本人可见，不会在群内发送消息") }
                    )

                    if (systemBEnabled) {
                        Text(
                            "功能说明：开启后全局监控所有微信群，任意成员进群、退群触发本地提醒，仅本机可见，不会往微信群发送任何消息。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // 提示
                    Text(
                        "提示：体系A和体系B的开关独立运行，互不影响。A组发送消息到群内，B组仅本地提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = onSave) { Text("保存") }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SystemAGroupListScreen(
        onBack: () -> Unit,
        onGroupClick: (String) -> Unit,
        onSave: () -> Unit
    ) {
        val groups = remember {
            WeDatabaseApi.getGroups().filter { it.wxId.isNotBlank() }
        }

        AlertDialogContent(
            title = { Text("对外群通知设置") },
            text = {
                DefaultColumn {
                    Text(
                        "勾选需要自动发送通知的群聊，点击进入配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))

                    if (groups.isEmpty()) {
                        Text("暂无群聊", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(groups, key = { it.wxId }) { group ->
                                val isSelected = remember(group.wxId) { hasGroupConfig(group.wxId) }

                                ListItem(
                                    modifier = Modifier.clickable {
                                        onGroupClick(group.wxId)
                                    },
                                    headlineContent = { Text(group.displayName) },
                                    supportingContent = {
                                        Text(
                                            if (isSelected) "已配置" else "点击配置",
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    trailingContent = {
                                        if (isSelected) {
                                            Text(
                                                "✓",
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("返回") } },
            confirmButton = {
                Button(onClick = {
                    onSave()
                    onBack()
                }) { Text("保存") }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SystemAGroupConfigScreen(
        context: ComponentActivity,
        groupWxId: String,
        config: GroupConfig,
        onBack: () -> Unit,
        onSave: (GroupConfig) -> Unit
    ) {
        var welcomeText by remember { mutableStateOf(config.welcomeText) }
        var atNewMember by remember { mutableStateOf(config.atNewMember) }
        var extraEnabled by remember { mutableStateOf(config.extraEnabled) }
        var extraSource by remember { mutableStateOf(config.extraSource) }
        var extraPath by remember { mutableStateOf(config.extraPath) }
        var extraType by remember { mutableStateOf(config.extraType) }
        var leaveText by remember { mutableStateOf(config.leaveText) }
        var delaySeconds by remember { mutableStateOf(config.delaySeconds) }

        val groupName = remember { WeDatabaseApi.getDisplayName(groupWxId) }

        AlertDialogContent(
            title = { Text("配置: $groupName") },
            text = {
                DefaultColumn(scrollable = true) {
                    // 进群欢迎文案
                    Text(
                        "进群主欢迎文案",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = welcomeText,
                        onValueChange = { welcomeText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("支持变量 {nickname}") },
                        minLines = 2
                    )

                    Spacer(Modifier.height(8.dp))

                    // 自动@新人
                    ListItem(
                        modifier = Modifier.clickable { atNewMember = !atNewMember },
                        trailingContent = {
                            Switch(checked = atNewMember, onCheckedChange = null)
                        },
                        headlineContent = { Text("自动@新进群成员") },
                        supportingContent = { Text("进群第一条消息自动@新人") }
                    )

                    // 附加内容
                    ListItem(
                        modifier = Modifier.clickable { extraEnabled = !extraEnabled },
                        trailingContent = {
                            Switch(checked = extraEnabled, onCheckedChange = null)
                        },
                        headlineContent = { Text("附加自动回复内容") },
                        supportingContent = { Text("发送欢迎文案后，追加发送附加素材") }
                    )

                    if (extraEnabled) {
                        // 素材来源
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { extraSource = "local" },
                                modifier = Modifier.weight(1f),
                                enabled = extraSource != "local"
                            ) { Text("本地文件") }
                            Button(
                                onClick = { extraSource = "favorite" },
                                modifier = Modifier.weight(1f),
                                enabled = extraSource != "favorite"
                            ) { Text("微信收藏") }
                        }

                        // 素材类型
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("text" to "文本", "image" to "图片", "video" to "视频", "voice" to "语音").forEach { (type, label) ->
                                Button(
                                    onClick = { extraType = type; extraPath = "" },
                                    modifier = Modifier.weight(1f),
                                    enabled = extraType != type
                                ) { Text(label, fontSize = 11.sp) }
                            }
                        }

                        if (extraType == "text") {
                            OutlinedTextField(
                                value = extraPath,
                                onValueChange = { extraPath = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                label = { Text("附加文本内容") },
                                minLines = 2
                            )
                        } else {
                            // 素材路径显示 + 选择按钮
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = extraPath.ifEmpty { "未选择文件" },
                                    onValueChange = {},
                                    modifier = Modifier.weight(1f),
                                    enabled = false,
                                    label = { Text("素材路径") },
                                    singleLine = true
                                )
                                Button(
                                    onClick = {
                                        pickExtraFile(context, extraType) { path ->
                                            extraPath = path
                                        }
                                    }
                                ) { Text("选择") }
                            }
                            if (extraSource == "favorite") {
                                Text(
                                    "微信收藏素材选择功能开发中，请先使用本地文件",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 退群文案
                    Text(
                        "退群通知文案",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = leaveText,
                        onValueChange = { leaveText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("支持变量 {nickname}") },
                        minLines = 2
                    )

                    Spacer(Modifier.height(8.dp))

                    // 发送延迟
                    Text(
                        "消息发送延迟: ${delaySeconds}秒",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "0秒 = 检测到事件后立即发送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (s in 0..6) {
                            Button(
                                onClick = { delaySeconds = s },
                                modifier = Modifier.weight(1f),
                                enabled = delaySeconds != s
                            ) { Text("${s}s", fontSize = 11.sp) }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onBack) { Text("返回") } },
            confirmButton = {
                Button(onClick = {
                    onSave(
                        GroupConfig(
                            welcomeText = welcomeText,
                            atNewMember = atNewMember,
                            extraEnabled = extraEnabled,
                            extraSource = extraSource,
                            extraPath = extraPath,
                            extraType = extraType,
                            leaveText = leaveText,
                            delaySeconds = delaySeconds
                        )
                    )
                }) { Text("保存") }
            }
        )
    }
}