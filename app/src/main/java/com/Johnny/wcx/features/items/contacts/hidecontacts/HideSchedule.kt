package com.Johnny.wcx.features.items.contacts.hidecontacts

import kotlinx.serialization.Serializable

@Serializable
data class HideSchedule(
    val id: String,
    val enabled: Boolean = true,
    val action: HideScheduleAction,
    val kind: HideScheduleKind,
    val minuteOfDay: Int = 0,
    val daysOfWeek: Set<Int> = ALL_DAYS_OF_WEEK,
    val atEpochMillis: Long = 0L
)

val ALL_DAYS_OF_WEEK: Set<Int> = (1..7).toSet()