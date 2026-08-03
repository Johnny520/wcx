package com.Johnny.wcx.features.items.moments

import kotlinx.serialization.Serializable

@Serializable
data class MomentActionRule(
    val enabled: Boolean = true,
    val action: MomentAutomationAction = MomentAutomationAction.LIKE
)