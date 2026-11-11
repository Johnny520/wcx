package h.Hchat.hooks.items.moments

import android.content.ContentValues
import h.Hchat.hooks.api.sns.SnsContentKind
import h.Hchat.hooks.api.sns.SnsContentTypes
import h.Hchat.hooks.api.sns.WeChatSnsApi
import h.Hchat.utils.KavaReflector
import java.util.Locale

internal data class MomentsPostRecord(
    val key: String,
    val snsId: Long,
    val userName: String,
    val createTimeSeconds: Long,
    val type: SnsContentKind,
    val text: String,
    val nativeInfo: Any
) {
    companion object {
        fun from(values: ContentValues, snsApi: WeChatSnsApi): MomentsPostRecord? {
            val nativeInfo = snsApi.snsInfoFrom(values) ?: return null
            return fromValues(values, nativeInfo)
        }

        fun from(nativeInfo: Any, snsApi: WeChatSnsApi): MomentsPostRecord? {
            val values = snsApi.contentValuesFrom(nativeInfo) ?: return null
            return fromValues(values, nativeInfo)
        }

        private fun fromValues(values: ContentValues, nativeInfo: Any): MomentsPostRecord? {
            val snsId = firstLong(values, "snsId", "field_snsId", "svrId") ?: return null
            if (snsId == 0L) return null
            val userName = firstString(values, "userName", "field_userName").trim()
            if (userName.isBlank()) return null
            val createTime = firstLong(
                values,
                "createTime",
                "field_createTime",
                "create_time",
                "timestamp",
                "field_timestamp"
            ) ?: 0L
            val rawType = firstLong(values, "type", "field_type")?.toInt() ?: -1
            val text = momentsTimelineText(nativeInfo)
            return MomentsPostRecord(
                key = java.lang.Long.toUnsignedString(snsId),
                snsId = snsId,
                userName = userName,
                createTimeSeconds = createTime,
                type = SnsContentTypes.classify(rawType),
                text = text,
                nativeInfo = nativeInfo
            )
        }

        fun keyOf(raw: String?): String? {
            val value = raw?.trim()?.trim('\'', '"')?.takeIf { it.isNotEmpty() } ?: return null
            return value.toLongOrNull()?.let(java.lang.Long::toUnsignedString)
                ?: runCatching { java.lang.Long.parseUnsignedLong(value) }
                    .getOrNull()
                    ?.let(java.lang.Long::toUnsignedString)
        }

        private fun firstLong(values: ContentValues, vararg keys: String): Long? {
            for (key in keys) {
                val raw = values.get(key) ?: continue
                if (raw is Number) return raw.toLong()
                keyOf(raw.toString())?.let { normalized ->
                    return runCatching { java.lang.Long.parseUnsignedLong(normalized) }.getOrNull()
                }
            }
            return null
        }

        private fun firstString(values: ContentValues, vararg keys: String): String {
            for (key in keys) {
                values.getAsString(key)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return ""
        }
    }
}

internal fun momentsTimelineText(nativeInfo: Any): String {
    val timeline = KavaReflector.invokeMethod(nativeInfo, "getTimeLine") ?: return ""
    for (name in arrayOf("ContentDesc", "contentDesc", "desc", "description")) {
        val value = KavaReflector.readField(timeline, name)?.toString().orEmpty()
        if (value.isNotBlank()) return value
    }
    return ""
}

internal fun momentsTimelineUserName(nativeInfo: Any): String {
    return (KavaReflector.invokeMethod(nativeInfo, "getUserName")
        ?: KavaReflector.readField(nativeInfo, "field_userName"))
        ?.toString()
        ?.trim()
        .orEmpty()
}

internal fun parseMomentsIds(raw: String?): Set<String> {
    return raw.orEmpty()
        .split(',', '|', ';', '\n', '，', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()
}

internal fun parseMomentsKeywords(raw: String?): Set<String> {
    return raw.orEmpty()
        .split(',', '|', ';', '\n', '，', '；')
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .toSet()
}

internal fun isInMomentsTimeWindow(start: String, end: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
    val startSecond = parseMomentsTime(start) ?: return true
    val endSecond = parseMomentsTime(end) ?: return true
    if (startSecond == endSecond) return true
    val calendar = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
    val nowSecond = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
        calendar.get(java.util.Calendar.MINUTE) * 60 +
        calendar.get(java.util.Calendar.SECOND)
    return if (startSecond < endSecond) {
        nowSecond in startSecond until endSecond
    } else {
        nowSecond >= startSecond || nowSecond < endSecond
    }
}

private fun parseMomentsTime(value: String): Int? {
    val parts = value.trim().split(':')
    if (parts.size !in 2..3) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    return hour * 3600 + minute * 60 + second
}
