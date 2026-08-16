package com.Johnny.wcx.features.items.moments

import kotlinx.serialization.Serializable

@Serializable
data class MomentIntervalRule(
    val enabled: Boolean = false,
    val milliseconds: String = "0"
) {
    fun value(): Long {
        if (!enabled) return 0L
        val ms = milliseconds.toLongOrNull() ?: 0L
        return ms.coerceIn(0L, 300_000L)
    }
}