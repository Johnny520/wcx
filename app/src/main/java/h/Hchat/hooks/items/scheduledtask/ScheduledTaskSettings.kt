package h.Hchat.hooks.items.scheduledtask

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import kotlin.random.Random

data class ScheduledTaskContentItem(
    val type: Int,
    val value: String
)

data class ScheduledTaskItem(
    val id: String,
    val type: Int,
    val content: String,
    val contentItems: List<String> = emptyList(),
    val mediaPaths: List<String>,
    val targetIds: List<String>,
    val planTime: Long,
    val repeatType: Int,
    val repeatDays: Set<Int>,
    val intervalSeconds: Int,
    val mediaIntervalSeconds: Int,
    val sendOnTimeout: Boolean,
    val status: String = ScheduledTaskSettings.STATUS_PENDING,
    val lastExecutedTime: Long = 0L,
    val lastSuccessCount: Int = 0,
    val lastFailCount: Int = 0,
    val remark: String = "",
    val targetType: Int = ScheduledTaskSettings.TARGET_CHAT,
    val momentsType: Int = ScheduledTaskSettings.MOMENTS_TEXT,
    val items: List<ScheduledTaskContentItem> = emptyList(),
    val sendChannel: Int = ScheduledTaskSettings.SEND_CHANNEL_MODULE,
    val planTimes: List<Long> = emptyList()
)

class ScheduledTaskSettings(context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLE, enabled)?.apply()
    }

    fun tasks(): List<ScheduledTaskItem> = parseTasks(getString(KEY_TASKS, ""))

    fun findTask(taskId: String): ScheduledTaskItem? = tasks().firstOrNull { it.id == taskId }

    fun saveTasks(tasks: List<ScheduledTaskItem>) {
        prefs?.edit()?.putString(KEY_TASKS, encodeTasks(tasks))?.apply()
    }

    fun saveTask(task: ScheduledTaskItem) {
        val next = tasks().filterNot { it.id == task.id } + task
        saveTasks(next)
    }

    fun deleteTask(taskId: String) {
        saveTasks(tasks().filterNot { it.id == taskId })
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean {
        return runCatching { prefs?.getBoolean(key, defValue) ?: defValue }.getOrDefault(defValue)
    }

    private fun getString(key: String, defValue: String): String {
        return runCatching { prefs?.getString(key, defValue) ?: defValue }.getOrDefault(defValue)
    }

    companion object {
        const val PREFS_NAME = "Hchat_scheduled_task_config"
        const val KEY_ENABLE = "scheduled_task_enable"
        const val KEY_TASKS = "scheduled_task_items_v1"

        const val DEFAULT_ENABLE = false
        const val DEFAULT_SEND_ON_TIMEOUT = true

        const val TYPE_TEXT = 0
        const val TYPE_IMAGE = 1
        const val TYPE_VIDEO = 2
        const val TYPE_FILE = 3
        const val TYPE_EMOJI = 4
        const val TYPE_VOICE = 5
        const val TYPE_XML = 6
        const val TYPE_FAVORITE = 7
        const val TYPE_SELECTED_MESSAGE = 8

        const val TARGET_CHAT = 0
        const val TARGET_MOMENTS = 1

        const val SEND_CHANNEL_MODULE = 0
        const val SEND_CHANNEL_OFFICIAL = 1

        const val MOMENTS_TEXT = 0
        const val MOMENTS_TEXT_IMAGE = 1
        const val MOMENTS_TEXT_VIDEO = 2
        const val MOMENTS_IMAGE = 3
        const val MOMENTS_VIDEO = 4

        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_WEEKLY = 2

        const val STATUS_PENDING = "pending"
        const val STATUS_RUNNING = "running"

        const val DEFAULT_INTERVAL_SECONDS = 0
        const val DEFAULT_MEDIA_INTERVAL_SECONDS = 0
        const val DEFAULT_RANDOM_MIN_SECONDS = 0
        const val DEFAULT_RANDOM_MAX_SECONDS = 3

        fun newDraft(now: Long = System.currentTimeMillis()): ScheduledTaskItem {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.MINUTE, 5)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return ScheduledTaskItem(
                id = "task_" + now + "_" + Random.nextInt(1000, 9999),
                type = TYPE_TEXT,
                content = "",
                mediaPaths = emptyList(),
                targetIds = emptyList(),
                planTime = calendar.timeInMillis,
                repeatType = REPEAT_NONE,
                repeatDays = emptySet(),
                intervalSeconds = DEFAULT_INTERVAL_SECONDS,
                mediaIntervalSeconds = DEFAULT_MEDIA_INTERVAL_SECONDS,
                sendOnTimeout = DEFAULT_SEND_ON_TIMEOUT
            )
        }

        fun parseTasks(value: String?): List<ScheduledTaskItem> {
            if (value.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                buildList {
                    for (index in 0 until array.length()) {
                        val obj = array.optJSONObject(index) ?: continue
                        val id = obj.optString("id").trim()
                        if (id.isBlank()) continue
                        val planTimes = parsePlanTimes(obj)
                        add(
                            ScheduledTaskItem(
                                id = id,
                                remark = obj.optString("remark", ""),
                                type = obj.optInt("type", TYPE_TEXT),
                                content = obj.optString("content", ""),
                                contentItems = parseStringArray(obj.optJSONArray("contentItems")),
                                mediaPaths = parseStringArray(obj.optJSONArray("mediaPaths")),
                                targetIds = parseStringArray(obj.optJSONArray("targetIds")),
                                planTime = planTimes.firstOrNull() ?: 0L,
                                repeatType = obj.optInt("repeatType", REPEAT_NONE),
                                repeatDays = parseIntSet(obj.optJSONArray("repeatDays")),
                                intervalSeconds = obj.optInt("intervalSeconds", DEFAULT_INTERVAL_SECONDS).coerceIn(0, 3600),
                                mediaIntervalSeconds = obj.optInt("mediaIntervalSeconds", DEFAULT_MEDIA_INTERVAL_SECONDS).coerceIn(0, 3600),
                                sendOnTimeout = obj.optBoolean("sendOnTimeout", DEFAULT_SEND_ON_TIMEOUT),
                                status = obj.optString("status", STATUS_PENDING).ifBlank { STATUS_PENDING },
                                lastExecutedTime = obj.optLong("lastExecutedTime", 0L),
                                lastSuccessCount = obj.optInt("lastSuccessCount", 0).coerceAtLeast(0),
                                lastFailCount = obj.optInt("lastFailCount", 0).coerceAtLeast(0),
                                targetType = obj.optInt("targetType", TARGET_CHAT)
                                    .takeIf { targetTypeIsValid(it) } ?: TARGET_CHAT,
                                momentsType = obj.optInt("momentsType", MOMENTS_TEXT)
                                    .takeIf { momentsTypeIsValid(it) } ?: MOMENTS_TEXT,
                                items = parseContentItems(obj.optJSONArray("items")),
                                sendChannel = obj.optInt("sendChannel", SEND_CHANNEL_MODULE)
                                    .takeIf { sendChannelIsValid(it) } ?: SEND_CHANNEL_MODULE,
                                planTimes = planTimes
                            )
                        )
                    }
                }.sortedBy { it.planTime }
            }.getOrDefault(emptyList())
        }

        fun encodeTasks(tasks: List<ScheduledTaskItem>): String {
            val array = JSONArray()
            tasks.sortedBy { it.planTime }.forEach { task ->
                val obj = JSONObject()
                val items = normalizedItems(task)
                val firstType = items.firstOrNull()?.type
                    ?: task.type.takeIf { scheduledTypeIsValid(it) }
                    ?: TYPE_TEXT
                val contents = if (scheduledTypeUsesText(firstType)) {
                    items.filter { it.type == firstType }.map { it.value }
                } else {
                    emptyList()
                }
                val mediaPaths = if (scheduledTypeUsesMedia(firstType)) {
                    items.filter { it.type == firstType }.map { it.value }.distinct()
                } else {
                    emptyList()
                }
                obj.put("id", task.id)
                obj.put("remark", task.remark.trim())
                obj.put("type", firstType)
                obj.put("content", contents.firstOrNull().orEmpty())
                obj.put("contentItems", JSONArray().apply { contents.forEach { put(it) } })
                obj.put("mediaPaths", JSONArray().apply { mediaPaths.forEach { put(it) } })
                obj.put("items", JSONArray().apply {
                    items.forEach { item ->
                        put(JSONObject().apply {
                            put("type", item.type)
                            put("value", item.value)
                        })
                    }
                })
                obj.put("targetIds", JSONArray().apply { task.targetIds.distinct().forEach { put(it) } })
                val planTimes = normalizedPlanTimes(task)
                obj.put("planTime", planTimes.firstOrNull() ?: 0L)
                obj.put("planTimes", JSONArray().apply {
                    planTimes.forEach { put(it) }
                })
                obj.put("repeatType", task.repeatType)
                obj.put("repeatDays", JSONArray().apply { task.repeatDays.sorted().forEach { put(it) } })
                obj.put("intervalSeconds", task.intervalSeconds.coerceIn(0, 3600))
                obj.put("mediaIntervalSeconds", task.mediaIntervalSeconds.coerceIn(0, 3600))
                obj.put("sendOnTimeout", task.sendOnTimeout)
                obj.put("status", task.status.ifBlank { STATUS_PENDING })
                obj.put("lastExecutedTime", task.lastExecutedTime.coerceAtLeast(0L))
                obj.put("lastSuccessCount", task.lastSuccessCount.coerceAtLeast(0))
                obj.put("lastFailCount", task.lastFailCount.coerceAtLeast(0))
                obj.put("targetType", task.targetType.takeIf { targetTypeIsValid(it) } ?: TARGET_CHAT)
                obj.put("momentsType", task.momentsType.takeIf { momentsTypeIsValid(it) } ?: MOMENTS_TEXT)
                obj.put(
                    "sendChannel",
                    if (task.targetType == TARGET_CHAT) {
                        task.sendChannel.takeIf { sendChannelIsValid(it) } ?: SEND_CHANNEL_MODULE
                    } else {
                        SEND_CHANNEL_MODULE
                    }
                )
                array.put(obj)
            }
            return array.toString()
        }

        fun normalizeForSave(task: ScheduledTaskItem): ScheduledTaskItem {
            val targetType = task.targetType.takeIf { targetTypeIsValid(it) } ?: TARGET_CHAT
            val momentsType = task.momentsType.takeIf { momentsTypeIsValid(it) } ?: MOMENTS_TEXT
            val sendChannel = if (targetType == TARGET_CHAT) {
                task.sendChannel.takeIf { sendChannelIsValid(it) } ?: SEND_CHANNEL_MODULE
            } else {
                SEND_CHANNEL_MODULE
            }
            val items = if (targetType == TARGET_MOMENTS) {
                normalizedMomentsItems(task.copy(momentsType = momentsType))
            } else {
                normalizedItems(task)
            }
            val firstType = items.firstOrNull()?.type
                ?: task.type.takeIf { scheduledTypeIsValid(it) }
                ?: TYPE_TEXT
            val contents = if (scheduledTypeUsesText(firstType)) {
                items.filter { it.type == firstType }.map { it.value }
            } else {
                emptyList()
            }
            val mediaPaths = if (scheduledTypeUsesMedia(firstType)) {
                items.filter { it.type == firstType }.map { it.value }.distinct()
            } else {
                emptyList()
            }
            val planTimes = normalizedPlanTimes(task)
            return task.copy(
                remark = task.remark.trim(),
                type = firstType,
                content = contents.firstOrNull().orEmpty(),
                contentItems = contents,
                mediaPaths = mediaPaths,
                items = items,
                targetType = targetType,
                momentsType = momentsType,
                sendChannel = sendChannel,
                targetIds = if (targetType == TARGET_CHAT) {
                    task.targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                } else {
                    emptyList()
                },
                repeatDays = task.repeatDays.filter { it in validRepeatDays() }.toSet(),
                intervalSeconds = task.intervalSeconds.coerceIn(0, 3600),
                mediaIntervalSeconds = task.mediaIntervalSeconds.coerceIn(0, 3600),
                planTime = planTimes.firstOrNull() ?: 0L,
                planTimes = planTimes,
                status = STATUS_PENDING
            )
        }

        fun normalizedPlanTimes(task: ScheduledTaskItem): List<Long> {
            val source = task.planTimes.ifEmpty { listOf(task.planTime) }
            return source.asSequence()
                .filter { it > 0L }
                .distinct()
                .sorted()
                .toList()
        }

        fun resolveNextPlanTime(
            basePlanTime: Long,
            repeatType: Int,
            repeatDays: Set<Int>,
            now: Long = System.currentTimeMillis()
        ): Long {
            if (basePlanTime <= 0L) return 0L
            if (repeatType == REPEAT_NONE) return basePlanTime
            var next = basePlanTime
            if (repeatType == REPEAT_WEEKLY) {
                val days = repeatDays.filter { it in validRepeatDays() }.toSet()
                if (days.isNotEmpty()) {
                    val currentDay = Calendar.getInstance().apply { timeInMillis = next }
                        .get(Calendar.DAY_OF_WEEK)
                    if (currentDay !in days) {
                        next = calculateNextPlanTime(next, repeatType, days)
                    }
                }
            }
            var guard = 0
            while (next <= now && guard < 400) {
                next = calculateNextPlanTime(next, repeatType, repeatDays)
                guard++
            }
            return next
        }

        fun calculateNextPlanTime(
            currentPlanTime: Long,
            repeatType: Int,
            repeatDays: Set<Int>
        ): Long {
            if (currentPlanTime <= 0L) return 0L
            val calendar = Calendar.getInstance().apply { timeInMillis = currentPlanTime }
            when (repeatType) {
                REPEAT_DAILY -> calendar.add(Calendar.DAY_OF_MONTH, 1)
                REPEAT_WEEKLY -> {
                    val days = repeatDays.filter { it in validRepeatDays() }.toSet()
                    if (days.isEmpty()) {
                        calendar.add(Calendar.DAY_OF_MONTH, 1)
                    } else {
                        var safety = 14
                        do {
                            calendar.add(Calendar.DAY_OF_MONTH, 1)
                            safety--
                        } while (safety > 0 && calendar.get(Calendar.DAY_OF_WEEK) !in days)
                    }
                }
            }
            return calendar.timeInMillis
        }

        fun validRepeatDays(): Set<Int> = setOf(
            Calendar.MONDAY,
            Calendar.TUESDAY,
            Calendar.WEDNESDAY,
            Calendar.THURSDAY,
            Calendar.FRIDAY,
            Calendar.SATURDAY,
            Calendar.SUNDAY
        )

        private fun parseStringArray(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }.distinct()
        }

        private fun parsePlanTimes(obj: JSONObject): List<Long> {
            val array = obj.optJSONArray("planTimes")
            if (array != null) {
                val values = buildList {
                    for (index in 0 until array.length()) {
                        array.optLong(index, 0L).takeIf { it > 0L }?.let(::add)
                    }
                }
                return values.distinct().sorted()
            }
            return listOfNotNull(obj.optLong("planTime", 0L).takeIf { it > 0L })
        }

        fun normalizedItems(task: ScheduledTaskItem): List<ScheduledTaskContentItem> {
            val rawItems = task.items.ifEmpty {
                if (scheduledTypeUsesText(task.type)) {
                    normalizedContentItems(task).map { ScheduledTaskContentItem(task.type, it) }
                } else {
                    task.mediaPaths.map { ScheduledTaskContentItem(task.type, it) }
                }
            }
            return rawItems.mapNotNull { item ->
                val type = item.type.takeIf { scheduledTypeIsValid(it) } ?: TYPE_TEXT
                val value = item.value.trim()
                if (value.isBlank()) null else ScheduledTaskContentItem(type, value)
            }
        }

        fun normalizedContentItems(task: ScheduledTaskItem): List<String> {
            val rawItems = task.contentItems.ifEmpty {
                task.content.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
            }
            return rawItems.map { it.trim() }.filter { it.isNotBlank() }
        }

        fun scheduledTypeUsesText(type: Int): Boolean {
            return type == TYPE_TEXT || type == TYPE_XML
        }

        fun scheduledTypeUsesMedia(type: Int): Boolean {
            return !scheduledTypeUsesText(type) && type != TYPE_SELECTED_MESSAGE
        }

        fun scheduledTypeIsValid(type: Int): Boolean {
            return type in TYPE_TEXT..TYPE_SELECTED_MESSAGE
        }

        fun targetTypeIsValid(type: Int): Boolean {
            return type == TARGET_CHAT || type == TARGET_MOMENTS
        }

        fun sendChannelIsValid(channel: Int): Boolean {
            return channel == SEND_CHANNEL_MODULE || channel == SEND_CHANNEL_OFFICIAL
        }

        fun momentsTypeIsValid(type: Int): Boolean {
            return type in MOMENTS_TEXT..MOMENTS_VIDEO
        }

        fun normalizedMomentsItems(task: ScheduledTaskItem): List<ScheduledTaskContentItem> {
            val rawItems = task.items.ifEmpty { normalizedItems(task) }
            val text = rawItems.firstOrNull { it.type == TYPE_TEXT }
                ?.value?.trim().orEmpty()
            val images = rawItems.asSequence()
                .filter { it.type == TYPE_IMAGE }
                .map { it.value.trim() }
                .filter { it.isNotBlank() && File(it).isFile }
                .distinct()
                .take(9)
                .map { ScheduledTaskContentItem(TYPE_IMAGE, it) }
                .toList()
            val video = rawItems.firstOrNull { it.type == TYPE_VIDEO }
                ?.value?.trim().orEmpty()
                .takeIf { it.isNotBlank() && File(it).isFile }
                ?.let { ScheduledTaskContentItem(TYPE_VIDEO, it) }
            val textItem = text.takeIf { it.isNotBlank() }
                ?.let { ScheduledTaskContentItem(TYPE_TEXT, it) }
            return when (task.momentsType) {
                MOMENTS_TEXT -> listOfNotNull(textItem)
                MOMENTS_TEXT_IMAGE -> listOfNotNull(textItem) + images
                MOMENTS_TEXT_VIDEO -> listOfNotNull(textItem, video)
                MOMENTS_IMAGE -> images
                MOMENTS_VIDEO -> listOfNotNull(video)
                else -> emptyList()
            }
        }

        fun momentsValidationError(task: ScheduledTaskItem): String? {
            if (task.targetType != TARGET_MOMENTS) return null
            val items = normalizedMomentsItems(task)
            val hasText = items.any { it.type == TYPE_TEXT && it.value.isNotBlank() }
            val imageCount = items.count { it.type == TYPE_IMAGE }
            val videoCount = items.count { it.type == TYPE_VIDEO }
            return when (task.momentsType) {
                MOMENTS_TEXT -> if (hasText) null else "请输入朋友圈文字"
                MOMENTS_TEXT_IMAGE -> when {
                    !hasText -> "请输入朋友圈文字"
                    imageCount !in 1..9 -> "请选择 1-9 张朋友圈图片"
                    else -> null
                }
                MOMENTS_TEXT_VIDEO -> when {
                    !hasText -> "请输入朋友圈文字"
                    videoCount != 1 -> "请选择 1 个朋友圈视频"
                    else -> null
                }
                MOMENTS_IMAGE -> if (imageCount in 1..9) null else "请选择 1-9 张朋友圈图片"
                MOMENTS_VIDEO -> if (videoCount == 1) null else "请选择 1 个朋友圈视频"
                else -> "请选择朋友圈类型"
            }
        }

        private fun parseContentItems(array: JSONArray?): List<ScheduledTaskContentItem> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val type = obj.optInt("type", TYPE_TEXT)
                    val value = obj.optString("value").trim()
                    if (scheduledTypeIsValid(type) && value.isNotBlank()) {
                        add(ScheduledTaskContentItem(type, value))
                    }
                }
            }
        }

        private fun parseIntSet(array: JSONArray?): Set<Int> {
            if (array == null) return emptySet()
            return buildSet {
                for (index in 0 until array.length()) {
                    val value = array.optInt(index, Int.MIN_VALUE)
                    if (value in validRepeatDays()) add(value)
                }
            }
        }
    }
}
