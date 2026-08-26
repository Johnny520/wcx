package com.Johnny.wcx.features.items.chat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.fragment.app.FragmentActivity
import com.Johnny.wcx.activity.TransparentActivity
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
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CancellationException
import kotlin.io.path.absolutePathString

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
    data class MessageSegment(
        val type: MessageType,
        val content: String = "",
        val filePath: String = "",
        val duration: Int = 0
    ) : java.io.Serializable

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
        var enabled: Boolean = true,
        val oneTimeOnly: Boolean = false,
        var nextSendTime: Long = 0,
        val segments: List<MessageSegment> = emptyList()
    ) : java.io.Serializable

    enum class MessageType(val description: String) {
        TEXT("文本"),
        IMAGE("图片"),
        VOICE("语音"),
        VIDEO("视频"),
        FILE("文件"),
        LINK("链接")
    }

    private var schedules by prefOption("scheduled_messages", emptyList<ScheduleConfig>())
    private val activeAlarms = ConcurrentHashMap<String, PendingIntent>()
    private val timerJobs = ConcurrentHashMap<String, Job>()
    private lateinit var alarmReceiver: BroadcastReceiver

    override fun onEnable() {
        alarmReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val scheduleId = intent?.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
                CoroutineScope(Dispatchers.IO).launch {
                    handleScheduleTrigger(scheduleId)
                }
            }
        }

        runCatching {
            val filter = IntentFilter(ALARM_ACTION)
            // Android 13+ 必须声明接收器导出标志，否则注册抛 SecurityException
            ContextCompat.registerReceiver(
                HostInfo.application,
                alarmReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            WeLogger.e(TAG, "failed to register alarm receiver", it)
        }

        runCatching {
            schedules.filter { it.enabled }.forEach { schedule ->
                scheduleAlarm(schedule)
            }
        }.onFailure {
            WeLogger.e(TAG, "failed to schedule alarms", it)
        }
    }

    override fun onDisable() {
        activeAlarms.values.forEach { it.cancel() }
        activeAlarms.clear()
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
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
        schedule.nextSendTime = triggerTime
        updateSchedule(schedule)

        // 进程内定时器：只要微信进程存活就保证准点触发，不受系统闹钟权限/省电策略影响
        scheduleInProcess(schedule)

        runCatching {
            val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    alarmManager.canScheduleExactAlarms()
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                // 未授予精确闹钟权限：退化为不精确闹钟，系统会尽量在相近时间触发
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            activeAlarms[schedule.id] = pendingIntent
        }.onFailure {
            WeLogger.e(TAG, "failed to set exact alarm for schedule ${schedule.id}", it)
            // 最后兜底：精确闹钟被拒绝时再尝试一次不精确闹钟
            runCatching {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
                activeAlarms[schedule.id] = pendingIntent
            }.onFailure { e2 ->
                WeLogger.e(TAG, "fallback alarm also failed for ${schedule.id}", e2)
            }
        }
    }

    /**
     * 进程内准点定时器：微信进程存活时按 nextSendTime 用 delay 触发，
     * 与 AlarmManager 互为冗余（配合 handleScheduleTrigger 的时间守卫去重，不会重复发送）。
     */
    private fun scheduleInProcess(schedule: ScheduleConfig) {
        timerJobs.remove(schedule.id)?.cancel()
        val next = schedule.nextSendTime
        val delayMs = next - System.currentTimeMillis()
        if (delayMs <= 0) return
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(delayMs)
                handleScheduleTrigger(schedule.id)
            } catch (e: CancellationException) {
                // 任务被取消（停用/删除/重设闹钟）
            } catch (e: Throwable) {
                WeLogger.e(TAG, "in-process timer failed for ${schedule.id}", e)
            }
        }
        timerJobs[schedule.id] = job
    }

    private fun calculateNextTriggerTime(schedule: ScheduleConfig): Long {
        val now = System.currentTimeMillis()
        val targetTime = LocalTime.of(schedule.hour, schedule.minute)
        val todayTarget = java.time.LocalDateTime.now().with(targetTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        return if (todayTarget > now) {
            todayTarget
        } else if (schedule.repeatDaily) {
            // Bug Fix: 如果今天的目标时间刚过不久（15分钟内），立即触发而不等到明天
            if (now - todayTarget < 15 * 60 * 1000L) {
                now + 2000L
            } else {
                todayTarget + 24 * 60 * 60 * 1000L
            }
        } else {
            -1L
        }
    }

    private suspend fun handleScheduleTrigger(scheduleId: String) {
        val schedule = schedules.find { it.id == scheduleId } ?: return

        if (!schedule.enabled) return

        // 时间守卫：进程内定时器与闹钟可能先后触发，只允许到点后执行，避免重复发送
        val nowMs = System.currentTimeMillis()
        if (schedule.nextSendTime > 0 && nowMs < schedule.nextSendTime - 2000) {
            WeLogger.i(TAG, "trigger for ${schedule.id} fired too early, skipping (next=${schedule.nextSendTime}, now=$nowMs)")
            return
        }
        WeLogger.i(TAG, "scheduled trigger fired for ${schedule.id} (next=${schedule.nextSendTime}, now=$nowMs)")

        runCatching {
            if (schedule.segments.isNotEmpty()) {
                schedule.segments.forEachIndexed { index, segment ->
                    sendSegment(schedule.talker, segment)
                    if (index < schedule.segments.size - 1) {
                        delay(500)
                    }
                }
            } else {
                sendLegacySingle(schedule)
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

    private fun sendSegment(talker: String, segment: MessageSegment) {
        when (segment.type) {
            MessageType.TEXT -> {
                if (segment.content.isNotBlank()) {
                    WeMessageApi.sendText(talker, segment.content)
                }
            }
            MessageType.LINK -> {
                if (segment.content.isNotBlank()) {
                    WeMessageApi.sendText(talker, segment.content)
                }
            }
            MessageType.IMAGE -> {
                if (segment.filePath.isNotBlank()) {
                    WeMessageApi.sendImage(talker, segment.filePath)
                }
            }
            MessageType.VOICE -> {
                if (segment.filePath.isNotBlank()) {
                    WeMessageApi.sendVoice(talker, segment.filePath, segment.duration)
                }
            }
            MessageType.VIDEO -> {
                if (segment.filePath.isNotBlank()) {
                    WeMessageApi.sendVideo(talker, segment.filePath)
                }
            }
            MessageType.FILE -> {
                if (segment.filePath.isNotBlank()) {
                    val fileName = segment.filePath.substringAfterLast('/')
                    WeMessageApi.sendFile(talker, segment.filePath, fileName)
                }
            }
        }
    }

    private fun sendLegacySingle(schedule: ScheduleConfig) {
        when (schedule.messageType) {
            MessageType.TEXT -> {
                WeMessageApi.sendText(schedule.talker, schedule.content)
            }
            MessageType.LINK -> {
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
    }

    private fun cancelAlarm(schedule: ScheduleConfig) {
        timerJobs.remove(schedule.id)?.cancel()
        val pi = activeAlarms.remove(schedule.id) ?: return
        pi.cancel()
        runCatching {
            val am = HostInfo.application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }.onFailure { WeLogger.w(TAG, "cancel alarm failed for ${schedule.id}", it) }
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

    private fun segmentsSummary(segments: List<MessageSegment>): String {
        if (segments.isEmpty()) return ""
        val typesStr = segments.joinToString("+") { it.type.description }
        return "${segments.size}段消息: $typesStr"
    }

    private fun MessageSegment.summary(): String {
        return when (type) {
            MessageType.TEXT -> "文本: ${content.take(20)}"
            MessageType.LINK -> "链接: ${content.take(20)}"
            MessageType.IMAGE -> "图片: ${filePath.substringAfterLast('/').take(20)}"
            MessageType.VOICE -> "语音: ${duration}ms"
            MessageType.VIDEO -> "视频: ${filePath.substringAfterLast('/').take(20)}"
            MessageType.FILE -> "文件: ${filePath.substringAfterLast('/').take(20)}"
        }
    }

    /**
     * 通过 TransparentActivity 拉起系统文件选择器，把选中的 Uri 内容拷贝到 moduleCache
     * 并把本地路径回调给调用方。IMAGE/VIDEO 走 PickVisualMedia，VOICE/FILE 走 OpenDocument。
     */
    private fun pickMediaFile(
        type: MessageType,
        onPicked: (String) -> Unit
    ) {
        TransparentActivity.launch(HostInfo.application) {
            if (type == MessageType.IMAGE || type == MessageType.VIDEO) {
                val launcher = registerForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { uri ->
                    finish()
                    if (uri == null) return@registerForActivityResult
                    copyUriToCache(uri, type)?.let(onPicked)
                }
                val visualMediaType = if (type == MessageType.IMAGE) {
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                } else {
                    ActivityResultContracts.PickVisualMedia.VideoOnly
                }
                launcher.launch(PickVisualMediaRequest(visualMediaType))
            } else {
                val mimeTypes = when (type) {
                    MessageType.VOICE -> arrayOf("audio/*")
                    MessageType.FILE -> arrayOf("*/*")
                    else -> arrayOf("*/*")
                }
                val launcher = registerForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri ->
                    finish()
                    if (uri == null) return@registerForActivityResult
                    copyUriToCache(uri, type)?.let(onPicked)
                }
                launcher.launch(mimeTypes)
            }
        }
    }

    private fun FragmentActivity.copyUriToCache(uri: Uri, type: MessageType): String? {
        return try {
            val resolver = contentResolver
            val displayName = resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "picked_${System.currentTimeMillis()}"
            val safeName = displayName.ifBlank { "picked_${System.currentTimeMillis()}" }
            val destFile = KnownPaths.moduleCache.resolve("sched_${System.currentTimeMillis()}_$safeName")
            resolver.openInputStream(uri)?.use { input ->
                Files.copy(input, destFile, StandardCopyOption.REPLACE_EXISTING)
            } ?: run {
                WeLogger.e(TAG, "copyUriToCache: openInputStream returned null for $uri")
                return null
            }
            WeLogger.i(TAG, "picked media file cached: ${destFile.absolutePathString()}")
            destFile.absolutePathString()
        } catch (e: Exception) {
            WeLogger.e(TAG, "copyUriToCache failed for type=$type uri=$uri", e)
            null
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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
                        // 编辑后重新计算并设置闹钟（时间可能已变更）
                        if (schedule.enabled) scheduleAlarm(schedule)
                        showToast("定时任务已更新")
                        showEditDialog = false
                    },
                    existing = editingSchedule!!
                )
            } else {
                AlertDialogContent(
                    title = { Text("定时发送消息") },
                    text = {
                        DefaultColumn(scrollable = true) {
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
                                            val summary = if (schedule.segments.isNotEmpty()) {
                                                segmentsSummary(schedule.segments)
                                            } else {
                                                schedule.messageType.description
                                            }
                                            Text(
                                                "${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')} " +
                                                        "${if (schedule.repeatDaily) "每天" else "单次"} $summary"
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

    @OptIn(ExperimentalMaterial3Api::class)
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
        var segments by remember {
            mutableStateOf(
                existing?.let {
                    if (it.segments.isNotEmpty()) {
                        it.segments
                    } else if (it.content.isNotBlank() || it.filePath.isNotBlank()) {
                        listOf(
                            MessageSegment(
                                type = it.messageType,
                                content = it.content,
                                filePath = it.filePath,
                                duration = it.duration
                            )
                        )
                    } else {
                        emptyList()
                    }
                } ?: emptyList()
            )
        }
        val timePickerState = rememberTimePickerState(
            initialHour = existing?.hour ?: 9,
            initialMinute = existing?.minute ?: 0,
            is24Hour = true
        )
        var repeatDaily by remember { mutableStateOf(existing?.repeatDaily ?: true) }
        var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
        var showTalkerSelector by remember { mutableStateOf(false) }
        var talkerSearchQuery by remember { mutableStateOf("") }
        var showSegmentTypeDialog by remember { mutableStateOf(false) }
        var pendingSegmentType by remember { mutableStateOf<MessageType?>(null) }
        var showSegmentEditDialog by remember { mutableStateOf(false) }

        var tempContent by remember { mutableStateOf("") }
        var tempFilePath by remember { mutableStateOf("") }
        var tempDuration by remember { mutableStateOf("0") }

        AlertDialogContent(
            title = { Text(if (isEditing) "编辑定时任务" else "添加定时任务") },
            text = {
                DefaultColumn(scrollable = true) {
                    ListItem(
                        modifier = Modifier.clickable { showTalkerSelector = true },
                        headlineContent = { Text(if (selectedTalkerName.isNotBlank()) selectedTalkerName else "选择发送对象") },
                        supportingContent = { Text(selectedTalker) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("消息段列表", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    if (segments.isEmpty()) {
                        Text(
                            "还没有消息段，点击下方按钮添加",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        segments.forEachIndexed { index, segment ->
                            ListItem(
                                headlineContent = { Text(segment.summary()) },
                                supportingContent = { Text("第${index + 1}段 - ${segment.type.description}") },
                                trailingContent = {
                                    TextButton(onClick = {
                                        segments = segments.toMutableList().also { it.removeAt(index) }
                                    }) { Text("删除") }
                                }
                            )
                        }
                    }

                    Button(
                        onClick = { showSegmentTypeDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("+ 添加消息段") }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("发送时间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

                    TimePicker(state = timePickerState)

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
                    val h = timePickerState.hour
                    val m = timePickerState.minute

                    if (selectedTalker.isBlank()) {
                        showToast("请选择发送对象")
                        return@Button
                    }

                    if (segments.isEmpty()) {
                        showToast("请至少添加一个消息段")
                        return@Button
                    }

                    segments.forEachIndexed { index, segment ->
                        val valid = when (segment.type) {
                            MessageType.TEXT, MessageType.LINK -> segment.content.isNotBlank()
                            MessageType.IMAGE, MessageType.VOICE,
                            MessageType.VIDEO, MessageType.FILE -> segment.filePath.isNotBlank()
                        }
                        if (!valid) {
                            showToast("第${index + 1}段消息内容无效")
                            return@Button
                        }
                    }

                    val schedule = ScheduleConfig(
                        id = existing?.id ?: "${System.currentTimeMillis()}",
                        talker = selectedTalker,
                        talkerName = selectedTalkerName,
                        messageType = existing?.messageType ?: MessageType.TEXT,
                        content = existing?.content ?: "",
                        filePath = existing?.filePath ?: "",
                        duration = existing?.duration ?: 0,
                        hour = h,
                        minute = m,
                        repeatDaily = repeatDaily,
                        enabled = enabled,
                        segments = segments
                    )

                    onSave(schedule)
                }) { Text(if (isEditing) "保存" else "添加") }
            }
        )

        if (showTalkerSelector) {
            val filteredContacts = remember(talkerSearchQuery, contacts) {
                if (talkerSearchQuery.isBlank()) contacts
                else contacts.filter { (wxId, name) ->
                    name.contains(talkerSearchQuery, ignoreCase = true) ||
                            wxId.contains(talkerSearchQuery, ignoreCase = true)
                }
            }
            AlertDialogContent(
                title = { Text("选择发送对象") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = talkerSearchQuery,
                            onValueChange = { talkerSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索昵称或微信号") },
                            singleLine = true
                        )
                        if (filteredContacts.isEmpty()) {
                            Text(
                                "无匹配的联系人",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp)
                            ) {
                                items(filteredContacts, key = { it.first }) { (wxId, name) ->
                                    ListItem(
                                        modifier = Modifier.clickable {
                                            selectedTalker = wxId
                                            selectedTalkerName = name
                                            showTalkerSelector = false
                                            talkerSearchQuery = ""
                                        },
                                        headlineContent = { Text(name) },
                                        supportingContent = { Text(wxId) },
                                        trailingContent = { Text(if (selectedTalker == wxId) "✓" else "") }
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = {
                    showTalkerSelector = false
                    talkerSearchQuery = ""
                }) { Text("取消") } },
                confirmButton = {}
            )
        }

        if (showSegmentTypeDialog) {
            AlertDialogContent(
                title = { Text("选择消息段类型") },
                text = {
                    DefaultColumn {
                        MessageType.values().forEach { type ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    pendingSegmentType = type
                                    tempContent = ""
                                    tempFilePath = ""
                                    tempDuration = "0"
                                    showSegmentTypeDialog = false
                                    showSegmentEditDialog = true
                                },
                                headlineContent = { Text(type.description) }
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { showSegmentTypeDialog = false }) { Text("取消") } },
                confirmButton = {}
            )
        }

        if (showSegmentEditDialog && pendingSegmentType != null) {
            val type = pendingSegmentType!!
            AlertDialogContent(
                title = { Text("添加${type.description}消息段") },
                text = {
                    DefaultColumn {
                        when (type) {
                            MessageType.TEXT -> {
                                OutlinedTextField(
                                    value = tempContent,
                                    onValueChange = { tempContent = it },
                                    label = { Text("消息内容") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3
                                )
                            }
                            MessageType.LINK -> {
                                OutlinedTextField(
                                    value = tempContent,
                                    onValueChange = { tempContent = it },
                                    label = { Text("链接URL") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2
                                )
                            }
                            MessageType.VOICE -> {
                                Button(
                                    onClick = {
                                        pickMediaFile(type) { path -> tempFilePath = path }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("选择语音文件") }
                                if (tempFilePath.isNotBlank()) {
                                    Text(
                                        "已选: ${tempFilePath.substringAfterLast('/')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedTextField(
                                    value = tempDuration,
                                    onValueChange = { tempDuration = it.filter { c -> c.isDigit() } },
                                    label = { Text("语音时长（毫秒）") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            MessageType.IMAGE, MessageType.VIDEO -> {
                                Button(
                                    onClick = {
                                        pickMediaFile(type) { path -> tempFilePath = path }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("选择${type.description}文件") }
                                if (tempFilePath.isNotBlank()) {
                                    Text(
                                        "已选: ${tempFilePath.substringAfterLast('/')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            MessageType.FILE -> {
                                Button(
                                    onClick = {
                                        pickMediaFile(type) { path -> tempFilePath = path }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("选择文件") }
                                if (tempFilePath.isNotBlank()) {
                                    Text(
                                        "已选: ${tempFilePath.substringAfterLast('/')}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showSegmentEditDialog = false
                        pendingSegmentType = null
                    }) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        val newSegment = MessageSegment(
                            type = type,
                            content = tempContent,
                            filePath = tempFilePath,
                            duration = tempDuration.toIntOrNull() ?: 0
                        )
                        segments = segments + newSegment
                        showSegmentEditDialog = false
                        pendingSegmentType = null
                    }) { Text("添加") }
                }
            )
        }
    }

    @Composable
    private fun Spacer(modifier: Modifier) {
        androidx.compose.foundation.layout.Spacer(modifier)
    }
}
