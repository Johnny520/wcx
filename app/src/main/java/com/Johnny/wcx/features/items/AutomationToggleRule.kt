package com.Johnny.wcx.features.items

import kotlinx.serialization.Serializable

@Serializable
data class AutomationToggleRule(
    val enabled: Boolean = false
)