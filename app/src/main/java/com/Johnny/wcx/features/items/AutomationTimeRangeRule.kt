package com.Johnny.wcx.features.items

import kotlinx.serialization.Serializable
import java.util.Calendar

@Serializable
data class AutomationTimeRangeRule(
    val enabled: Boolean = false,
    val startMinute: Int = 0,
    val endMinute: Int = 0
) {
    fun matches(now: Calendar = Calendar.getInstance()): Boolean {
        if (!enabled) return true
        val current = (now.get(Calendar.HOUR_OF_DAY) * 60) + now.get(Calendar.MINUTE)
        val start = startMinute.coerceIn(0, 1439)
        val end = endMinute.coerceIn(0, 1439)
        if (start == end) return true
        return if (start >= end) {
            !(end <= current && current < start)
        } else {
            start <= current && current < end
        }
    }
}