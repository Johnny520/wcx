package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf

@Feature(
    name = "进退群提示增强",
    categories = ["联系人与群组"],
    description = "监控群成员进退群，自动发送提示消息到群里，支持总开关、每群独立开关、自定义多消息类型"
)
object MonitorGroupMemberOperations : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "MonitorGroupMemberOperations"

    private var globalEnabled by prefOption("group_member_notify_global_enabled", false)
    private var enableLeaveNotify by prefOption("group_member_leave_notify", true)
    private var enableJoinNotify by prefOption("group_member_join_notify", true)
    private var enableNameChangeNotify by prefOption("group_member_name_change_notify", false)

    private var leaveTemplate by prefOption(
        "group_member_leave_template",
        "{displayName} ({wxId}) 退出了群聊，祝他/她前程似锦，后会有期！"
    )
    private var joinTemplate by prefOption(
        "group_member_join_template",
        "欢迎 {displayName} ({wxId}) 加入群聊！希望大家相处愉快~"
    )

    private val json = Json { ignoreUnknownKeys = true }

    data class GroupNotifyConfig(
        val enabled: Boolean = false,
        val leaveEnabled: Boolean = true,
        val joinEnabled: Boolean = true,
        val leaveTemplate: String = "",
        val joinTemplate: String = "",
        val leaveMessages: List<NotifyMessage> = emptyList(),
        val joinMessages: List<NotifyMessage> = emptyList()
    ) : java.io.Serializable

    data class NotifyMessage(
        val type: MessageTypeEnum = MessageTypeEnum.TEXT,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0
    ) : java.io.Serializable

    enum class MessageTypeEnum {
        TEXT, IMAGE, VOICE, VIDEO, FILE
    }

    private var groupConfigs by prefOption(
        "group_member_notify_group_configs",
        emptyMap<String, GroupNotifyConfig>()
    )

    private fun getGroupConfig(group: String): GroupNotifyConfig {
        return groupConfigs[group] ?: GroupNotifyConfig()
    }

    private fun saveGroupConfig(group: String, config: GroupNotifyConfig) {
        groupConfigs = groupConfigs + (group to config)
    }

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

    @SuppressLint("Range")
    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        if (table != "chatroom") return
        if (!globalEnabled) return

        val group = values.getAsString("chatroomname") ?: return
        val groupConfig = getGroupConfig(group)
        if (!groupConfig.enabled) return

        val newMemberCount = values.getAsInteger("memberCount")
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

                handleMemberChange(
                    group, groupConfig, origMembers, origDisplayNames, newRawMembers, newMemberCount,
                    newDisplayNames
                )

                if (enableNameChangeNotify && groupConfig.joinEnabled) {
                    handleDisplayNameChange(group, origDisplayNames, newRoomData)
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    override fun onInsert(table: String, values: ContentValues) {
    }

    private fun handleMemberChange(
        group: String,
        groupConfig: GroupNotifyConfig,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newMemberCount: Int?,
        newDisplayNames: Map<String, String>
    ) {
        if (newRawMembers == null || newMemberCount == null) return

        val origSet = origMembers.toSet()
        val newSet = newRawMembers.split(';').toSet()

        if (enableLeaveNotify && groupConfig.leaveEnabled) {
            val leavers = origSet - newSet
            leavers.forEach { wxId ->
                val displayName = getDisplayName(wxId, origDisplayNames)
                sendLeaveNotifications(group, groupConfig, wxId, displayName)
            }
        }

        if (enableJoinNotify && groupConfig.joinEnabled) {
            val joiners = newSet - origSet
            joiners.forEach { wxId ->
                val displayName = getDisplayName(wxId, newDisplayNames)
                sendJoinNotifications(group, groupConfig, wxId, displayName)
            }
        }
    }

    private fun getDisplayName(wxId: String, roomDisplayNames: Map<String, String>): String {
        val roomName = roomDisplayNames[wxId] ?: ""
        if (roomName.isNotEmpty()) return roomName
        val dbName = WeDatabaseApi.getDisplayName(wxId)
        return dbName.ifEmpty { wxId }
    }

    private fun sendLeaveNotifications(group: String, config: GroupNotifyConfig, wxId: String, displayName: String) {
        if (config.leaveMessages.isNotEmpty()) {
            config.leaveMessages.forEach { msg ->
                sendNotifyMessage(group, msg, wxId, displayName)
            }
        } else {
            val content = if (config.leaveTemplate.isNotBlank()) config.leaveTemplate else leaveTemplate
            val text = content
                .replace("{displayName}", displayName)
                .replace("{wxId}", wxId)
                .replace("{group}", group)
            WeMessageApi.sendText(group, text)
        }
    }

    private fun sendJoinNotifications(group: String, config: GroupNotifyConfig, wxId: String, displayName: String) {
        if (config.joinMessages.isNotEmpty()) {
            config.joinMessages.forEach { msg ->
                sendNotifyMessage(group, msg, wxId, displayName)
            }
        } else {
            val content = if (config.joinTemplate.isNotBlank()) config.joinTemplate else joinTemplate
            val text = content
                .replace("{displayName}", displayName)
                .replace("{wxId}", wxId)
                .replace("{group}", group)
            WeMessageApi.sendText(group, text)
        }
    }

    private fun sendNotifyMessage(group: String, msg: NotifyMessage, wxId: String, displayName: String) {
        val processedContent = msg.content
            .replace("{displayName}", displayName)
            .replace("{wxId}", wxId)
            .replace("{group}", group)

        when (msg.type) {
            MessageTypeEnum.TEXT -> {
                WeMessageApi.sendText(group, processedContent)
            }
            MessageTypeEnum.IMAGE -> {
                if (msg.filePath.isNotBlank()) {
                    WeMessageApi.sendImage(group, msg.filePath)
                }
            }
            MessageTypeEnum.VOICE -> {
                if (msg.filePath.isNotBlank()) {
                    WeMessageApi.sendVoice(group, msg.filePath, msg.duration)
                }
            }
            MessageTypeEnum.VIDEO -> {
                if (msg.filePath.isNotBlank()) {
                    WeMessageApi.sendVideo(group, msg.filePath)
                }
            }
            MessageTypeEnum.FILE -> {
                if (msg.filePath.isNotBlank()) {
                    val fileName = msg.filePath.substringAfterLast('/')
                    WeMessageApi.sendFile(group, msg.filePath, fileName)
                }
            }
        }
    }

    private fun handleDisplayNameChange(group: String, origDisplayNames: Map<String, String>, newRoomData: ByteArray?) {
        if (newRoomData == null) return

        val newDisplayNames = parseRoomData(newRoomData)
        if (origDisplayNames.isEmpty() || newDisplayNames.isEmpty()) return

        newDisplayNames.forEach { (wxId, newName) ->
            val oldName = origDisplayNames[wxId] ?: return@forEach
            if (oldName == newName) return@forEach

            val displayName = WeDatabaseApi.getDisplayName(wxId)
            val displayString = if (displayName.isNotEmpty()) "$displayName ($wxId)" else wxId

            val oldShow = oldName.ifEmpty { "(无)" }
            val newShow = newName.ifEmpty { "(无)" }

            val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
            val content = """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> 修改群昵称：$oldShow → $newShow"""

            WeMessageApi.createSimpleMsgInfoAndInsert(
                type = MessageType.SYSTEM.code,
                talker = group,
                content = content,
                currentTime = System.currentTimeMillis()
            )
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun parseRoomData(blob: ByteArray?): Map<String, String> {
        if (blob == null || blob.isEmpty()) return emptyMap()
        return runCatching {
            ProtoBuf.decodeFromByteArray<ChatRoomDataProto>(blob)
                .members.associate { it.wxId to it.displayName }
        }.getOrElse { emptyMap() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var globalEnabledState by remember { mutableStateOf(globalEnabled) }
            var leaveNotify by remember { mutableStateOf(enableLeaveNotify) }
            var joinNotify by remember { mutableStateOf(enableJoinNotify) }
            var nameNotify by remember { mutableStateOf(enableNameChangeNotify) }
            var showGroupList by remember { mutableStateOf(false) }
            var showTemplateEdit by remember { mutableStateOf(false) }
            var editWhich by remember { mutableStateOf(0) }

            var leaveTemplateText by remember { mutableStateOf(leaveTemplate) }
            var joinTemplateText by remember { mutableStateOf(joinTemplate) }

            if (showGroupList) {
                GroupListScreen(
                    onDismiss = { showGroupList = false },
                    onSave = { showToast("群聊配置已保存") }
                )
            } else if (showTemplateEdit) {
                TemplateEditor(
                    title = if (editWhich == 0) "退群提示模板" else "进群提示模板",
                    template = if (editWhich == 0) leaveTemplateText else joinTemplateText,
                    onTemplateChange = { newText ->
                        if (editWhich == 0) {
                            leaveTemplateText = newText
                        } else {
                            joinTemplateText = newText
                        }
                    },
                    onDismiss = { showTemplateEdit = false },
                    onSave = { newTemplate ->
                        if (editWhich == 0) {
                            leaveTemplate = newTemplate
                            leaveTemplateText = newTemplate
                        } else {
                            joinTemplate = newTemplate
                            joinTemplateText = newTemplate
                        }
                        showTemplateEdit = false
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("进退群提示增强") },
                    text = {
                        DefaultColumn(scrollable = true) {
                            ListItem(
                                modifier = Modifier.clickable { globalEnabledState = !globalEnabledState },
                                trailingContent = {
                                    Switch(checked = globalEnabledState, onCheckedChange = null)
                                },
                                headlineContent = { Text("总开关") },
                                supportingContent = { Text("关闭后所有群聊均不会发送进退群提示") }
                            )

                            ListItem(
                                modifier = Modifier.clickable { leaveNotify = !leaveNotify },
                                trailingContent = {
                                    Switch(checked = leaveNotify, onCheckedChange = null)
                                },
                                headlineContent = { Text("退群提示") },
                                supportingContent = { Text("有人退群时发送提示消息") }
                            )

                            ListItem(
                                modifier = Modifier.clickable { joinNotify = !joinNotify },
                                trailingContent = {
                                    Switch(checked = joinNotify, onCheckedChange = null)
                                },
                                headlineContent = { Text("进群提示") },
                                supportingContent = { Text("有人进群时发送提示消息") }
                            )

                            ListItem(
                                modifier = Modifier.clickable { nameNotify = !nameNotify },
                                trailingContent = {
                                    Switch(checked = nameNotify, onCheckedChange = null)
                                },
                                headlineContent = { Text("群昵称修改提示") },
                                supportingContent = { Text("成员修改群昵称时发送提示（仅本地可见）") }
                            )

                            Text(
                                "默认提示模板",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )

                            ListItem(
                                modifier = Modifier.clickable {
                                    editWhich = 0
                                    leaveTemplateText = leaveTemplate
                                    showTemplateEdit = true
                                },
                                headlineContent = { Text("退群提示模板") },
                                supportingContent = {
                                    Text(
                                        leaveTemplate,
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )

                            ListItem(
                                modifier = Modifier.clickable {
                                    editWhich = 1
                                    joinTemplateText = joinTemplate
                                    showTemplateEdit = true
                                },
                                headlineContent = { Text("进群提示模板") },
                                supportingContent = {
                                    Text(
                                        joinTemplate,
                                        maxLines = 2,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            )

                            ListItem(
                                modifier = Modifier.clickable { showGroupList = true },
                                headlineContent = { Text("群聊单独设置") },
                                supportingContent = { Text("为每个群聊单独配置进退群提示") }
                            )

                            Text(
                                "可用变量: {displayName} = 群昵称/备注, {wxId} = 微信号, {group} = 群ID",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp)
                            )

                            Text(
                                "提示: 消息会真实发送到群聊中，所有成员都能看到",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(onClick = {
                            globalEnabled = globalEnabledState
                            enableLeaveNotify = leaveNotify
                            enableJoinNotify = joinNotify
                            enableNameChangeNotify = nameNotify
                            showToast("设置已保存")
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GroupListScreen(
        onDismiss: () -> Unit,
        onSave: () -> Unit
    ) {
        val groups = remember {
            WeDatabaseApi.getGroups().filter { it.wxId.isNotBlank() }
        }

        AlertDialogContent(
            title = { Text("群聊单独设置") },
            text = {
                DefaultColumn {
                    if (groups.isEmpty()) {
                        Text("暂无群聊", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        groups.forEach { group ->
                            val config = remember { getGroupConfig(group.wxId) }
                            var enabled by remember { mutableStateOf(config.enabled) }

                            ListItem(
                                modifier = Modifier.clickable {
                                    enabled = !enabled
                                    saveGroupConfig(group.wxId, config.copy(enabled = enabled))
                                },
                                headlineContent = { Text(group.displayName) },
                                supportingContent = { Text(group.wxId) },
                                trailingContent = {
                                    Switch(checked = enabled, onCheckedChange = null)
                                }
                            )
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("返回") } },
            confirmButton = {
                Button(onClick = {
                    onSave()
                    onDismiss()
                }) { Text("保存") }
            }
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun TemplateEditor(
        title: String,
        template: String,
        onTemplateChange: (String) -> Unit,
        onDismiss: () -> Unit,
        onSave: (String) -> Unit
    ) {
        AlertDialogContent(
            title = { Text(title) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        value = template,
                        onValueChange = onTemplateChange,
                        modifier = Modifier
                            .fillMaxWidth(),
                        label = { Text("模板内容") },
                        minLines = 4
                    )
                    Text(
                        "变量: {displayName} 显示昵称, {wxId} 微信号, {group} 群ID",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = { onSave(template) }) { Text("保存") }
            }
        )
    }
}