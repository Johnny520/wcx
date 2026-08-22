package com.Johnny.wcx.features.core

/**
 * New-feature window metadata. Upstream generates this at compile time via
 * `GenerateNewFeaturesTask` (collecting feature files whose source entered the repo within
 * [WINDOW_DAYS] of the build HEAD commit); that generator is not part of the public v244 tree,
 * so this fork ships a static empty snapshot. With an empty [ADDED_AT_BY_NAME] the
 * "recently added" category simply stays hidden.
 */
object NewFeatures {
    const val WINDOW_DAYS = 30
    val ADDED_AT_BY_NAME: Map<String, Long> = emptyMap()
}
