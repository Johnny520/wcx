package com.Johnny.wcx.features.items.moments

import kotlinx.serialization.Serializable

@Serializable
data class MomentModeRule(
    val enabled: Boolean = true,
    val mode: MomentAutomationMode = MomentAutomationMode.WHEN_SEEN
)