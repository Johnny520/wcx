package com.Johnny.wcx.features.items.moments

import kotlinx.serialization.Serializable

@Serializable
enum class MomentAutomationMode {
    WHEN_SEEN,
    ALL_LOADED
}