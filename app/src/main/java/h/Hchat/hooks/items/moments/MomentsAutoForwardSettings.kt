package h.Hchat.hooks.items.moments

import android.content.SharedPreferences
import h.Hchat.utils.KeywordReplacementRule
import h.Hchat.utils.KeywordReplacementRules

typealias MomentsKeywordReplacement = KeywordReplacementRule

object MomentsAutoForwardSettings {
    const val PREFS_NAME = "Hchat_moments_auto_forward_config"

    const val KEY_ENABLE = "enable"
    const val KEY_ENABLED_AT_SECONDS = "enabled_at_seconds"
    const val KEY_TARGETS = "targets"
    const val KEY_ALLOW_TEXT = "allow_text"
    const val KEY_ALLOW_IMAGE = "allow_image"
    const val KEY_ALLOW_VIDEO = "allow_video"
    const val KEY_ALLOW_LIVE_PHOTO = "allow_live_photo"
    const val KEY_ALLOW_LINK = "allow_link"
    const val KEY_ALLOW_MUSIC = "allow_music"
    const val KEY_ALLOW_OTHER = "allow_other"
    const val KEY_ALLOW_UNKNOWN = "allow_unknown"
    const val KEY_DELAY_MODE = "delay_mode"
    const val KEY_FIXED_DELAY_SECONDS = "fixed_delay_seconds"
    const val KEY_RANDOM_MIN_SECONDS = "random_min_seconds"
    const val KEY_RANDOM_MAX_SECONDS = "random_max_seconds"
    const val KEY_DAILY_LIMIT = "daily_limit"
    const val KEY_INCLUDE_KEYWORDS_ENABLE = "include_keywords_enable"
    const val KEY_INCLUDE_KEYWORDS = "include_keywords"
    const val KEY_EXCLUDE_KEYWORDS_ENABLE = "exclude_keywords_enable"
    const val KEY_EXCLUDE_KEYWORDS = "exclude_keywords"
    const val KEY_REPLACE_KEYWORDS_ENABLE = "replace_keywords_enable"
    const val KEY_KEYWORD_REPLACEMENTS = "keyword_replacements"
    const val KEY_CONTENT_TEMPLATE = "content_template"
    const val KEY_LOG_ENABLE = "log_enable"
    const val KEY_LOGS = "logs"
    const val KEY_HANDLED_IDS = "handled_ids"
    const val KEY_DAILY_DATE = "daily_date"
    const val KEY_DAILY_COUNT = "daily_count"

    const val DELAY_FIXED = 0
    const val DELAY_RANDOM = 1

    const val VARIABLE_SENDER = "%sender%"
    const val VARIABLE_WXID = "%wxid%"
    const val VARIABLE_TYPE = "%type%"
    const val VARIABLE_CONTENT = "%content%"
    const val VARIABLE_SNS_ID = "%snsid%"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_ENABLED_AT_SECONDS = 0L
    const val DEFAULT_TARGETS = ""
    const val DEFAULT_ALLOW_TEXT = true
    const val DEFAULT_ALLOW_IMAGE = true
    const val DEFAULT_ALLOW_VIDEO = true
    const val DEFAULT_ALLOW_LIVE_PHOTO = true
    const val DEFAULT_ALLOW_LINK = false
    const val DEFAULT_ALLOW_MUSIC = false
    const val DEFAULT_ALLOW_OTHER = false
    const val DEFAULT_ALLOW_UNKNOWN = false
    const val DEFAULT_DELAY_MODE = DELAY_FIXED
    const val DEFAULT_FIXED_DELAY_SECONDS = 0
    const val DEFAULT_RANDOM_MIN_SECONDS = 60
    const val DEFAULT_RANDOM_MAX_SECONDS = 300
    const val DEFAULT_DAILY_LIMIT = 20
    const val DEFAULT_INCLUDE_KEYWORDS_ENABLE = false
    const val DEFAULT_INCLUDE_KEYWORDS = ""
    const val DEFAULT_EXCLUDE_KEYWORDS_ENABLE = false
    const val DEFAULT_EXCLUDE_KEYWORDS = ""
    const val DEFAULT_REPLACE_KEYWORDS_ENABLE = false
    const val DEFAULT_KEYWORD_REPLACEMENTS = "[]"
    const val DEFAULT_CONTENT_TEMPLATE = VARIABLE_CONTENT
    const val DEFAULT_LOG_ENABLE = false
    const val DEFAULT_LOGS = ""
    const val DEFAULT_HANDLED_IDS = "[]"
    const val DEFAULT_DAILY_DATE = ""
    const val DEFAULT_DAILY_COUNT = 0

    fun includeKeywordsEnabled(prefs: SharedPreferences): Boolean {
        return keywordFilterEnabled(
            prefs,
            KEY_INCLUDE_KEYWORDS_ENABLE,
            DEFAULT_INCLUDE_KEYWORDS_ENABLE,
            KEY_INCLUDE_KEYWORDS
        )
    }

    fun excludeKeywordsEnabled(prefs: SharedPreferences): Boolean {
        return keywordFilterEnabled(
            prefs,
            KEY_EXCLUDE_KEYWORDS_ENABLE,
            DEFAULT_EXCLUDE_KEYWORDS_ENABLE,
            KEY_EXCLUDE_KEYWORDS
        )
    }

    fun decodeKeywordReplacements(raw: String?): List<MomentsKeywordReplacement> {
        return KeywordReplacementRules.decode(raw)
    }

    fun encodeKeywordReplacements(rules: List<MomentsKeywordReplacement>): String {
        return KeywordReplacementRules.encode(rules)
    }

    fun applyKeywordReplacements(
        content: String,
        rules: List<MomentsKeywordReplacement>
    ): String {
        return KeywordReplacementRules.apply(content, rules)
    }

    private fun keywordFilterEnabled(
        prefs: SharedPreferences,
        enabledKey: String,
        defaultEnabled: Boolean,
        legacyKeywordsKey: String
    ): Boolean {
        if (prefs.contains(enabledKey)) return prefs.getBoolean(enabledKey, defaultEnabled)
        return prefs.getString(legacyKeywordsKey, "").orEmpty().isNotBlank()
    }
}
