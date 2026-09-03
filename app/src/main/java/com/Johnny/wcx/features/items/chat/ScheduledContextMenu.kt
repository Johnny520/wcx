package com.Johnny.wcx.features.items.chat

import android.graphics.drawable.Drawable
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.fragment.app.FragmentActivity
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi.ChattingContext
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi.MultiSelectSupport
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.ExtensionIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.utils.AudioUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalTime

@Feature(name = "定时发送菜单", categories = ["聊天"], description = "消息长按菜单添加定时发送/取消定时发送入口")
object ScheduledContextMenu : SwitchFeature() {

    private const val TAG = "ScheduledContextMenu"

    // Menu IDs - must be globally unique (no collision with 7770xx range)
    private const val MENU_SCHEDULE_SEND = 777026
    private const val MENU_CANCEL_SCHEDULE = 777027

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(object : WeChatMessageContextMenuApi.IMenuItemsProvider {
            override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
                // 定时发送
                WeChatMessageContextMenuApi.MenuItem(
                    id = MENU_SCHEDULE_SEND,
                    text = "定时发送",
                    drawable = ExtensionIcon,
                    imageVector = ExtensionIcon,
                    isSupported = { msgInfo -> isSupportedType(msgInfo) },
                    multiSelect = MultiSelectSupport.Adapted(
                        isSupported = { infos -> infos.all { isSupportedType(it) } },
                        onClick = { view, context, infos -> handleScheduleSend(view, context, infos) }
                    ),
                    onClick = { view, context, msgInfo -> handleScheduleSend(view, context, listOf(msgInfo)) }
                ),
                // 取消定时发送
                WeChatMessageContextMenuApi.MenuItem(
                    id = MENU_CANCEL_SCHEDULE,
                    text = "取消定时发送",
                    drawable = ExtensionIcon,
                    imageVector = ExtensionIcon,
                    isSupported = { true }, // shown for any single message
                    multiSelect = MultiSelectSupport.Unsupported,
                    onClick = { view, context, msgInfo -> handleCancelSchedule(view, context, msgInfo) }
                )
            )
        })
    }

    private fun isSupportedType(msgInfo: MessageInfo): Boolean {
        return msgInfo.messageType in listOf(MessageType.TEXT, MessageType.IMAGE, MessageType.VOICE)
    }

    /**
     * 处理"定时发送"操作
     */
    private fun handleScheduleSend(view: View, context: ChattingContext, infos: List<MessageInfo>) {
        val activity = context.activity as FragmentActivity
        val talker = infos.first().talker
        val talkerName = WeDatabaseApi.getDisplayName(talker)

        showComposeDialog(activity) {
            var subject by remember { mutableStateOf("") }
            var showConfirm by remember { mutableStateOf(false) }
            var selectedHour by remember { mutableStateOf(10) }
            var selectedMinute by remember { mutableStateOf(0) }
            var repeatDaily by remember { mutableStateOf(true) }

            if (showConfirm) {
                AlertDialogContent(
                    title = { Text("确认添加定时任务") },
                    text = {
                        DefaultColumn {
                            OutlinedTextField(
                                value = subject,
                                onValueChange = { subject = it },
                                label = { Text("主题") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("请输入任务主题") }
                            )

                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            Text("发送时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            TimePicker(
                                state = rememberTimePickerState(
                                    initialHour = selectedHour,
                                    initialMinute = selectedMinute,
                                    is24Hour = true
                                )
                            )

                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { repeatDaily = !repeatDaily },
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                            ) {
                                Text("每天重复", style = MaterialTheme.typography.bodyLarge)
                                Switch(checked = repeatDaily, onCheckedChange = null)
                            }

                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            ListItem(
                                headlineContent = { Text("发送目标") },
                                supportingContent = { Text("$talkerName ($talker)") }
                            )

                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            val summary = scheduledSegmentSummary(infos)
                            ListItem(
                                headlineContent = { Text("消息摘要") },
                                supportingContent = { Text(summary) }
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirm = false }) { Text("返回") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (subject.isBlank()) {
                                showToast("请输入主题")
                                return@Button
                            }
                            val hour = selectedHour
                            val minute = selectedMinute
                            showConfirm = false
                            registerSchedule(activity, talker, talkerName, subject, infos, hour, minute, repeatDaily)
                        }) { Text("确认添加") }
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("定时发送") },
                    text = {
                        DefaultColumn {
                            val summary = scheduledSegmentSummary(infos)
                            val timeStr = "$selectedHour:$selectedMinute${if (repeatDaily) " 每天重复" else ""}"
                            
                            ListItem(
                                headlineContent = { Text("消息摘要") },
                                supportingContent = { Text(summary) }
                            )

                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            ListItem(
                                headlineContent = { Text("发送时间") },
                                supportingContent = { Text(timeStr) }
                            )

                            Spacer(modifier = Modifier.padding(top = 8.dp))
                            ListItem(
                                headlineContent = { Text("发送目标") },
                                supportingContent = { Text("$talkerName ($talker)") }
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = { showConfirm = true }) { Text("下一步") }
                    }
                )
            }
        }
    }

    /**
     * 注册定时任务
     */
    private fun registerSchedule(
        activity: FragmentActivity,
        talker: String,
        talkerName: String,
        subject: String,
        infos: List<MessageInfo>,
        hour: Int,
        minute: Int,
        repeatDaily: Boolean
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val segments = infos.map { msgInfo ->
                    when (msgInfo.messageType) {
                        MessageType.TEXT -> {
                            ScheduledMessage.MessageSegment(
                                type = MessageType.TEXT,
                                content = msgInfo.content.orEmpty()
                            )
                        }
                        MessageType.IMAGE -> {
                            val path = ScheduledMessage.copyUriToCache(activity, msgInfo.uri, MessageType.IMAGE)
                            ScheduledMessage.MessageSegment(
                                type = MessageType.IMAGE,
                                filePath = path.orEmpty()
                            )
                        }
                        MessageType.VOICE -> {
                            val path = ScheduledMessage.copyUriToCache(activity, msgInfo.uri, MessageType.VOICE)
                            val duration = AudioUtils.getDurationMs(path ?: "")
                            ScheduledMessage.MessageSegment(
                                type = MessageType.VOICE,
                                filePath = path.orEmpty(),
                                duration = duration
                            )
                        }
                        else -> {
                            ScheduledMessage.MessageSegment(
                                type = msgInfo.messageType,
                                content = msgInfo.content.orEmpty()
                            )
                        }
                    }
                }

                val schedule = ScheduledMessage.ScheduleConfig(
                    id = "${System.currentTimeMillis()}",
                    talker = talker,
                    talkerName = talkerName,
                    hour = hour,
                    minute = minute,
                    repeatDaily = repeatDaily,
                    enabled = true,
                    segments = segments
                )

                ScheduledMessage.addSchedule(schedule)
                showToast("定时任务已添加")
            } catch (e: Exception) {
                WeLogger.e(TAG, "registerSchedule failed", e)
                showToast("添加定时任务失败")
            }
        }
    }

    /**
     * 处理"取消定时发送"操作
     */
    private fun handleCancelSchedule(view: View, context: ChattingContext, msgInfo: MessageInfo) {
        val activity = context.activity as FragmentActivity
        val talker = msgInfo.talker
        val talkerName = WeDatabaseApi.getDisplayName(talker)

        showComposeDialog(activity) {
            var schedules by remember {
                mutableStateOf(ScheduledMessage.getSchedulesForTalker(talker))
            }
            var selectedIds by remember {
                mutableStateOf(HashSet<String>())
            }
            var showConfirm by remember { mutableStateOf(false) }

            if (showConfirm) {
                AlertDialogContent(
                    title = { Text("确认取消") },
                    text = {
                        DefaultColumn {
                            Text("确定要取消选中的 ${selectedIds.size} 个定时任务吗？")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfirm = false }) { Text("返回") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val cancelled = mutableListOf<ScheduledMessage.ScheduleConfig>()
                            schedules = schedules.filter {
                                if (it.id in selectedIds) {
                                    cancelled.add(it)
                                    false
                                } else {
                                    true
                                }
                            }
                            cancelled.forEach { ScheduledMessage.deleteSchedule(it) }
                            showToast("已取消 ${cancelled.size} 个定时任务")
                            showConfirm = false
                        }) { Text("取消所选") }
                    }
                )
            } else {
                AlertDialogContent(
                    title = { Text("取消定时发送") },
                    text = {
                        DefaultColumn {
                            if (schedules.isEmpty()) {
                                Text(
                                    "当前会话暂无定时发送任务",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                // 按 talkerName 分组
                                val grouped = schedules.groupBy { it.talkerName }
                                grouped.forEach { (groupName, groupSchedules) ->
                                    Text(
                                        groupName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                                    )
                                    groupSchedules.forEach { schedule ->
                                        val timeStr = String.format(
                                            "%02d:%02d %s",
                                            schedule.hour,
                                            schedule.minute,
                                            if (schedule.repeatDaily) "每天" else "单次"
                                        )
                                        val summary = if (schedule.segments.isNotEmpty()) {
                                            schedule.segmentsSummary(schedule.segments)
                                        } else {
                                            schedule.messageType.description
                                        }
                                        ListItem(
                                            headlineContent = { Text(timeStr) },
                                            supportingContent = { Text(summary) },
                                            trailingContent = {
                                                Checkbox(
                                                    checked = schedule.id in selectedIds,
                                                    onCheckedChange = {
                                                        if (it) {
                                                            selectedIds.add(schedule.id)
                                                        } else {
                                                            selectedIds.remove(schedule.id)
                                                        }
                                                    }
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    },
                    confirmButton = {
                        if (schedules.isNotEmpty()) {
                            Button(onClick = {
                                if (selectedIds.isEmpty()) {
                                    showToast("请选择要取消的任务")
                                    return@Button
                                }
                                showConfirm = true
                            }) { Text("取消所选") }
                        }
                    }
                )
            }
        }
    }

    /**
     * 定时发送消息摘要
     */
    private fun scheduledSegmentSummary(infos: List<MessageInfo>): String {
        if (infos.isEmpty()) return ""
        if (infos.size == 1) {
            return when (val type = infos.first().messageType) {
                MessageType.TEXT -> "1 段消息: 文本"
                MessageType.IMAGE -> "1 段消息: 图片"
                MessageType.VOICE -> "1 段消息: 语音"
                else -> "1 段消息: ${type.description}"
            }
        }
        val typesStr = infos.joinToString("+") { it.messageType.description }
        return "${infos.size} 段消息: $typesStr"
    }

    @Composable
    private fun Spacer(modifier: Modifier) {
        androidx.compose.foundation.layout.Spacer(modifier)
    }
}
