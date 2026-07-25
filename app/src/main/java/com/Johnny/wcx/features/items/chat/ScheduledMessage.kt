package com.Johnny.wcx.features.items.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

@Feature(
    name = "定时发送消息",
    categories = ["聊天"],
    description = "在指定时间发送消息到群聊或私聊，支持每天重复或单次发送，支持多种消息类型"
)
object ScheduledMessage : ClickableFeature() {

    private const val TAG = "ScheduledMessage"
    private const val ALARM_ACTION = "com.Johnny.wcx.SCHEDULED_MESSAGE"
    private const val EXTRA_SCHEDULE_ID = "schedule_id"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class ScheduleConfig(
        val id: String,
        val talker: String,
        val talkerName: String,
        val messageType: MessageType = MessageType.TEXT,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0,
        val hour: Int = 9,
        val minute: Int = 0,
        val repeatDaily: Boolean = true,
        val enabled: Boolean = true,
        val oneTimeOnly: Boolean = false,
        val nextSendTime: Long = 0
    )

    enum class MessageType(val description: String) {
        TEXT("文本"),
        IMAGE("图片"),
        VOICE("语音"),
        VIDEO("视频"),
        FILE("文件")
    }

    private var schedules by prefOption("scheduled_messages", emptyList<ScheduleConfig>())
    private val activeAlarms = ConcurrentHashMap<String, PendingIntent>()
    private lateinit var alarmReceiver: BroadcastReceiver

    override fun onEnable() {
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                handleScheduleTrigger(scheduleId)
            }
        }

        runCatching {
            val filter = IntentFilter(ALARM_ACTION)
            HostInfo.application.registerReceiver(alarmReceiver, filter)
        }.onFailure {
            WeLogger.e(TAG, "failed to register alarm receiver", it)
        }

        schedules.filter { it.enabled }.forEach { schedule ->
            scheduleAlarm(schedule)
        }
    }

    override fun onDisable() {
        activeAlarms.values.forEach { it.cancel() }
        activeAlarms.clear()
        runCatching {
            HostInfo.application.unregisterReceiver(alarmReceiver)
        }.onFailure {
            WeLogger.e(TAG, "failed to unregister alarm receiver", it)
        }
    }

    private fun scheduleAlarm(schedule: ScheduleConfig) {
        val context = HostInfo.application
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ALARM_ACTION).apply {
            putExtra(EXTRA_SCHEDULE_ID, schedule.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = calculateNextTriggerTime(schedule)
        if (triggerTime <= 0) return

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )

        activeAlarms[schedule.id] = pendingIntent

        schedule.nextSendTime = triggerTime
        updateSchedule(schedule)
    }

    private fun calculateNextTriggerTime(schedule: ScheduleConfig): Long {
        val now = System.currentTimeMillis()
        val targetTime = LocalTime.of(schedule.hour, schedule.minute)
        val todayTarget = java.time.LocalDateTime.now().with(targetTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        return if (todayTarget > now) {
            todayTarget
        } else if (schedule.repeatDaily) {
            todayTarget + 24 * 60 * 60 * 1000L
        } else {
            -1L
        }
    }

    private fun handleScheduleTrigger(scheduleId: String) {
        val schedule = schedules.find { it.id == scheduleId } ?: return

        if (!schedule.enabled) return

        runCatching {
            when (schedule.messageType) {
                MessageType.TEXT -> {
                    WeMessageApi.sendText(schedule.talker, schedule.content)
                }
                MessageType.IMAGE -> {
                    if (schedule.filePath.isNotBlank()) {
                        WeMessageApi.sendImage(schedule.talker, schedule.filePath)
                    }
                }
                MessageType.VOICE -> {
                    if (schedule.filePath.isNotBlank()) {
                        WeMessageApi.sendVoice(schedule.talker, schedule.filePath, schedule.duration)
                    }
                }
                MessageType.VIDEO -> {
                    if (schedule.filePath.isNotBlank()) {
                        WeMessageApi.sendVideo(schedule.talker, schedule.filePath)
                    }
                }
                MessageType.FILE -> {
                    if (schedule.filePath.isNotBlank()) {
                        val fileName = schedule.filePath.substringAfterLast('/')
                        WeMessageApi.sendFile(schedule.talker, schedule.filePath, fileName)
                    }
                }
            }

            WeLogger.i(TAG, "scheduled message sent to ${schedule.talker}")
        }.onFailure {
            WeLogger.e(TAG, "failed to send scheduled message", it)
        }

        if (schedule.oneTimeOnly) {
            schedule.enabled = false
            updateSchedule(schedule)
            cancelAlarm(schedule)
        } else if (schedule.repeatDaily) {
            scheduleAlarm(schedule)
        }
    }

    private fun cancelAlarm(schedule: ScheduleConfig) {
        activeAlarms.remove(schedule.id)?.cancel()
    }

    private fun addSchedule(schedule: ScheduleConfig) {
        schedules = schedules + schedule
        if (schedule.enabled) {
            scheduleAlarm(schedule)
        }
    }

    private fun updateSchedule(schedule: ScheduleConfig) {
        schedules = schedules.map { if (it.id == schedule.id) schedule else it }
    }

    private fun deleteSchedule(schedule: ScheduleConfig) {
        cancelAlarm(schedule)
        schedules = schedules.filter { it.id != schedule.id }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var showAddDialog by remember { mutableStateOf(false) }
            var showEditDialog by remember { mutableStateOf(false) }
            var editingSchedule by remember { mutableStateOf<ScheduleConfig?>(null) }

            if (showAddDialog) {
                ScheduleEditorDialog(
                    onDismiss = { showAddDialog = false },
                    onSave = { schedule ->
                        addSchedule(schedule)
                        showToast("定时任务已添加")
                        showAddDialog = false
                    }
                )
            } else if (showEditDialog && editingSchedule != null) {
                ScheduleEditorDialog(
                    onDismiss = { showEditDialog = false },
                    onSave = { schedule ->
                        updateSchedule(schedule)
                        showToast("定时任务已更新")
                        showEditDialog = false
                    },
                    existing = editingSchedule!!
                )
            } else {
                AlertDialogContent(
                    title = { Text("定时发送消息") },
                    text = {
                        DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                            if (schedules.isEmpty()) {
                                Text(
                                    "还没有定时任务，点击下方按钮添加",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 16.dp)
                                )
                            } else {
                                schedules.forEach { schedule ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            editingSchedule = schedule
                                            showEditDialog = true
                                        },
                                        headlineContent = { Text(schedule.talkerName) },
                                        supportingContent = {
                                            Text(
                                                "${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')} " +
                                                        "${if (schedule.repeatDaily) "每天" else "单次"} ${schedule.messageType.description}"
                                            )
                                        },
                                        trailingContent = {
                                            Switch(
                                                checked = schedule.enabled,
                                                onCheckedChange = { enabled ->
                                                    schedule.enabled = enabled
                                                    if (enabled) {
                                                        scheduleAlarm(schedule)
                                                    } else {
                                                        cancelAlarm(schedule)
                                                    }
                                                    updateSchedule(schedule)
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    },
                    dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
                    confirmButton = {
                        Button(onClick = { showAddDialog = true }) { Text("添加任务") }
                    }
                )
            }
        }
    }

    @Composable
    private fun ScheduleEditorDialog(
        onDismiss: () -> Unit,
        onSave: (ScheduleConfig) -> Unit,
        existing: ScheduleConfig? = null
    ) {
        val isEditing = existing != null
        val contacts = remember {
            WeDatabaseApi.getFriends().map { it.wxId to it.nickname } +
                    WeDatabaseApi.getGroups().map { it.wxId to it.displayName }
        }

        var selectedTalker by remember { mutableStateOf(existing?.talker ?: "") }
        var selectedTalkerName by remember { mutableStateOf(existing?.talkerName ?: "") }
        var messageType by remember { mutableStateOf(existing?.messageType ?: MessageType.TEXT) }
        var content by remember { mutableStateOf(existing?.content ?: "") }
        var filePath by remember { mutableStateOf(existing?.filePath ?: "") }
        var duration by remember { mutableStateOf(existing?.duration?.toString() ?: "0") }
        var hour by remember { mutableStateOf(existing?.hour?.toString() ?: "9") }
        var minute by remember { mutableStateOf(existing?.minute?.toString() ?: "0") }
        var repeatDaily by remember { mutableStateOf(existing?.repeatDaily ?: true) }
        var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
        var showTalkerSelector by remember { mutableStateOf(false) }

        AlertDialogContent(
            title = { Text(if (isEditing) "编辑定时任务" else "添加定时任务") },
            text = {
                DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        modifier = Modifier.clickable { showTalkerSelector = true },
                        headlineContent = { Text(if (selectedTalkerName.isNotBlank()) selectedTalkerName else "选择发送对象") },
                        supportingContent = { Text(selectedTalker) }
                    )

                    Text("消息类型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    MessageType.values().forEach { type ->
                        ListItem(
                            modifier = Modifier.clickable { messageType = type },
                            trailingContent = { Text(if (messageType == type) "✓" else "") },
                            headlineContent = { Text(type.description) }
                        )
                    }

                    if (messageType == MessageType.TEXT) {
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("消息内容") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    } else if (messageType == MessageType.VOICE) {
                        OutlinedTextField(
                            value = filePath,
                            onValueChange = { filePath = it },
                            label = { Text("语音文件路径") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = duration,
                            onValueChange = { duration = it.filter { c -> c.isDigit() } },
                            label = { Text("语音时长（毫秒）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OutlinedTextField(
                            value = filePath,
                            onValueChange = { filePath = it },
                            label = { Text("文件路径") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("发送时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = hour,
                            onValueChange = { hour = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("小时") },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minute,
                            onValueChange = { minute = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("分钟") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    ListItem(
                        modifier = Modifier.clickable { repeatDaily = !repeatDaily },
                        trailingContent = { Switch(checked = repeatDaily, onCheckedChange = null) },
                        headlineContent = { Text("每天重复") },
                        supportingContent = { Text(if (repeatDaily) "每天同一时间发送" else "仅发送一次") }
                    )

                    ListItem(
                        modifier = Modifier.clickable { enabled = !enabled },
                        trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
                        headlineContent = { Text("启用") },
                        supportingContent = { Text(if (enabled) "任务将在指定时间执行" else "任务不会执行") }
                    )
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: 9
                    val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: 0

                    if (selectedTalker.isBlank()) {
                        showToast("请选择发送对象")
                        return@Button
                    }

                    if (messageType == MessageType.TEXT && content.isBlank()) {
                        showToast("请输入消息内容")
                        return@Button
                    }

                    if ((messageType == MessageType.IMAGE || messageType == MessageType.VOICE ||
                                messageType == MessageType.VIDEO || messageType == MessageType.FILE) && filePath.isBlank()) {
                        showToast("请输入文件路径")
                        return@Button
                    }

                    val schedule = ScheduleConfig(
                        id = existing?.id ?: "${System.currentTimeMillis()}",
                        talker = selectedTalker,
                        talkerName = selectedTalkerName,
                        messageType = messageType,
                        content = content,
                        filePath = filePath,
                        duration = duration.toIntOrNull() ?: 0,
                        hour = h,
                        minute = m,
                        repeatDaily = repeatDaily,
                        enabled = enabled
                    )

                    onSave(schedule)
                }) { Text(if (isEditing) "保存" else "添加") }
            }
        )

        if (showTalkerSelector) {
            AlertDialogContent(
                title = { Text("选择发送对象") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        contacts.forEach { (wxId, name) ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    selectedTalker = wxId
                                    selectedTalkerName = name
                                    showTalkerSelector = false
                                },
                                headlineContent = { Text(name) },
                                supportingContent = { Text(wxId) },
                                trailingContent = { Text(if (selectedTalker == wxId) "✓" else "") }
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { showTalkerSelector = false }) { Text("取消") } },
                confirmButton = {}
            )
        }
    }

    @Composable
    private fun Spacer(modifier: Modifier) {
        androidx.compose.foundation.layout.Spacer(modifier)
    }
}