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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
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
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf

@Feature(
    name = "进退群提示增强",
    categories = ["联系人与群组"],
    description = "监控群成员进退群，支持自定义提示模板、仅本地可见、自动获取昵称和ID"
)
object MonitorGroupMemberOperations : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IUpdateListener, WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "MonitorGroupMemberOperations"

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

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

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

        val group = values.getAsString("chatroomname") ?: return
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
                    group, origMembers, origDisplayNames, newRawMembers, newMemberCount,
                    newDisplayNames
                )

                if (enableNameChangeNotify) {
                    handleDisplayNameChange(group, origDisplayNames, newRoomData)
                }
            }
        }.onFailure { WeLogger.e(TAG, "failed to handle group member operations", it) }
    }

    override fun onInsert(table: String, values: ContentValues) {
        // chatroom 表一般是更新，插入场景较少
    }

    private fun handleMemberChange(
        group: String,
        origMembers: List<String>,
        origDisplayNames: Map<String, String>,
        newRawMembers: String?,
        newMemberCount: Int?,
        newDisplayNames: Map<String, String>
    ) {
        if (newRawMembers == null || newMemberCount == null) return

        val origSet = origMembers.toSet()
        val newSet = newRawMembers.split(';').toSet()

        if (enableLeaveNotify) {
            val leavers = origSet - newSet
            leavers.forEach { wxId ->
                val displayName = getDisplayName(wxId, origDisplayNames)
                sendLeaveNotification(group, wxId, displayName)
            }
        }

        if (enableJoinNotify) {
            val joiners = newSet - origSet
            joiners.forEach { wxId ->
                val displayName = getDisplayName(wxId, newDisplayNames)
                sendJoinNotification(group, wxId, displayName)
            }
        }
    }

    private fun getDisplayName(wxId: String, roomDisplayNames: Map<String, String>): String {
        val roomName = roomDisplayNames[wxId] ?: ""
        if (roomName.isNotEmpty()) return roomName
        val dbName = WeDatabaseApi.getDisplayName(wxId)
        return dbName.ifEmpty { wxId }
    }

    private fun sendLeaveNotification(group: String, wxId: String, displayName: String) {
        val displayString = if (displayName.isNotEmpty() && displayName != wxId) {
            "$displayName ($wxId)"
        } else wxId

        val content = leaveTemplate
            .replace("{displayName}", displayName)
            .replace("{wxId}", wxId)
            .replace("{group}", group)

        val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
        val linkedContent = """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_> ${content.substringAfter(displayString).trimStart()}"""

        val finalContent = if (content.contains(displayString)) {
            content.replaceFirst(displayString, """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_>""")
        } else content

        WeMessageApi.createSimpleMsgInfoAndInsert(
            type = MessageType.SYSTEM.code,
            talker = group,
            content = finalContent,
            currentTime = System.currentTimeMillis()
        )
    }

    private fun sendJoinNotification(group: String, wxId: String, displayName: String) {
        val displayString = if (displayName.isNotEmpty() && displayName != wxId) {
            "$displayName ($wxId)"
        } else wxId

        val href = "weixin://weixinhongbao/wekit/chatroom_userinfo/$wxId"
        val content = joinTemplate
            .replace("{displayName}", displayName)
            .replace("{wxId}", wxId)
            .replace("{group}", group)

        val finalContent = if (content.contains(displayString)) {
            content.replaceFirst(displayString, """<_wc_custom_link_ color="#28C445" href="$href">$displayString</_wc_custom_link_>""")
        } else content

        WeMessageApi.createSimpleMsgInfoAndInsert(
            type = MessageType.SYSTEM.code,
            talker = group,
            content = finalContent,
            currentTime = System.currentTimeMillis()
        )
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

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var leaveNotify by remember { mutableStateOf(enableLeaveNotify) }
            var joinNotify by remember { mutableStateOf(enableJoinNotify) }
            var nameNotify by remember { mutableStateOf(enableNameChangeNotify) }
            var showTemplateEdit by remember { mutableStateOf(false) }
            var editWhich by remember { mutableStateOf(0) }

            val leaveTemplateState = rememberTextFieldState(leaveTemplate)
            val joinTemplateState = rememberTextFieldState(joinTemplate)

            if (showTemplateEdit) {
                TemplateEditor(
                    title = if (editWhich == 0) "退群提示模板" else "进群提示模板",
                    template = if (editWhich == 0) leaveTemplateState else joinTemplateState,
                    onDismiss = { showTemplateEdit = false },
                    onSave = { newTemplate ->
                        if (editWhich == 0) {
                            leaveTemplate = newTemplate
                            leaveTemplateState.setTextAndPlaceCursorAtEnd(newTemplate)
                        } else {
                            joinTemplate = newTemplate
                            joinTemplateState.setTextAndPlaceCursorAtEnd(newTemplate)
                        }
                        showTemplateEdit = false
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("进退群提示增强") },
                    text = {
                        DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                            ListItem(
                                modifier = Modifier.clickable { leaveNotify = !leaveNotify },
                                trailingContent = {
                                    Switch(checked = leaveNotify, onCheckedChange = null)
                                },
                                headlineContent = { Text("退群提示") },
                                supportingContent = { Text("有人退群时发送系统消息提示（仅本地可见）") }
                            )

                            ListItem(
                                modifier = Modifier.clickable { joinNotify = !joinNotify },
                                trailingContent = {
                                    Switch(checked = joinNotify, onCheckedChange = null)
                                },
                                headlineContent = { Text("进群提示") },
                                supportingContent = { Text("有人进群时发送系统消息提示（仅本地可见）") }
                            )

                            ListItem(
                                modifier = Modifier.clickable { nameNotify = !nameNotify },
                                trailingContent = {
                                    Switch(checked = nameNotify, onCheckedChange = null)
                                },
                                headlineContent = { Text("群昵称修改提示") },
                                supportingContent = { Text("成员修改群昵称时发送提示") }
                            )

                            Text(
                                "提示模板",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                            )

                            ListItem(
                                modifier = Modifier.clickable {
                                    editWhich = 0
                                    leaveTemplateState.setTextAndPlaceCursorAtEnd(leaveTemplate)
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
                                    joinTemplateState.setTextAndPlaceCursorAtEnd(joinTemplate)
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

                            Text(
                                "可用变量: {displayName} = 群昵称/备注, {wxId} = 微信号, {group} = 群ID",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp)
                            )

                            Text(
                                "提示: 所有消息仅在本地显示，不会真正发送到服务器",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(onClick = {
                            enableLeaveNotify = leaveNotify
                            enableJoinNotify = joinNotify
                            enableNameChangeNotify = nameNotify
                            showToast("设置已保存，重启微信生效")
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    @Composable
    private fun TemplateEditor(
        title: String,
        template: androidx.compose.foundation.text.input.TextFieldState,
        onDismiss: () -> Unit,
        onSave: (String) -> Unit
    ) {
        AlertDialogContent(
            title = { Text(title) },
            text = {
                DefaultColumn {
                    OutlinedTextField(
                        state = template,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
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
                Button(onClick = { onSave(template.text.toString()) }) { Text("保存") }
            }
        )
    }

    private fun showToast(msg: String) {
        com.Johnny.wcx.utils.android.showToast(msg)
    }
}
