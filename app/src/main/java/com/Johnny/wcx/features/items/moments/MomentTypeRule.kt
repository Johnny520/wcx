package com.Johnny.wcx.features.items.moments

import kotlinx.serialization.Serializable

@Serializable
data class MomentTypeRule(
    val enabled: Boolean = false,
    val typeIds: Set<Int> = MomentsContentType.allTypeIds
)