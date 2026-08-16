package com.Johnny.wcx.features.items

import kotlinx.serialization.Serializable

@Serializable
data class AutomationKeywordRule(
    val enabled: Boolean = false,
    val mode: AutomationKeywordMode = AutomationKeywordMode.STRING_LIST,
    val keywords: List<String> = emptyList()
) {
    fun matches(text: String): Boolean {
        if (!enabled) return true
        if (keywords.isEmpty()) return true

        val effectiveKeywords = keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (effectiveKeywords.isEmpty()) return true

        return when (mode) {
            AutomationKeywordMode.STRING_LIST ->
                effectiveKeywords.any { text.contains(it, ignoreCase = true) }

            AutomationKeywordMode.REGEX ->
                effectiveKeywords.any { pattern ->
                    runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text) }
                        .getOrDefault(false)
                }
        }
    }
}