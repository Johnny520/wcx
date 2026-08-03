package com.Johnny.wcx.features.items.moments

import kotlinx.serialization.Serializable

@Serializable
data class MomentAgeRule(
    val enabled: Boolean = false,
    val maximumHours: String = "24"
) {
    fun matches(createTimeSeconds: Int, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!enabled) return true
        val hours = maximumHours.toLongOrNull() ?: return false
        if (hours < 0) return false
        if (createTimeSeconds <= 0) return false
        val ageSeconds = ((nowMillis / 1000) - createTimeSeconds).coerceAtLeast(0)
        return ageSeconds <= (hours * 60 * 60)
    }
}