package h.Hchat.hooks.items.moments

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MomentsAutoCommentSettings {
    const val PREFS_NAME = "Hchat_moments_auto_comment_config"

    const val KEY_ENABLE = "comment_enable"
    const val KEY_COMMENT_CONTENT = "comment_content"
    const val KEY_TIME_FORMAT = "comment_time_format"
    const val KEY_COMMENT_SELF = "comment_self"
    const val KEY_ENABLED_AT_SECONDS = "comment_enabled_at_seconds"
    const val KEY_LIST_MODE = "comment_list_mode"
    const val KEY_WHITELIST = "comment_whitelist"
    const val KEY_BLACKLIST = "comment_blacklist"
    const val KEY_DAILY_COMMENT_LIMIT = "daily_comment_limit"
    const val KEY_DAILY_COMMENT_DATE = "daily_comment_date"
    const val KEY_DAILY_COMMENT_COUNTS = "daily_comment_counts"
    const val KEY_DELAY_MODE = "comment_delay_mode"
    const val KEY_FIXED_DELAY_SECONDS = "comment_fixed_delay_seconds"
    const val KEY_RANDOM_MIN_SECONDS = "comment_random_min_seconds"
    const val KEY_RANDOM_MAX_SECONDS = "comment_random_max_seconds"
    const val KEY_TIME_WINDOW_ENABLE = "comment_time_window_enable"
    const val KEY_START_TIME = "comment_start_time"
    const val KEY_END_TIME = "comment_end_time"
    const val KEY_MAX_AGE_HOURS = "comment_max_age_hours"
    const val KEY_ALLOW_TEXT = "comment_allow_text"
    const val KEY_ALLOW_IMAGE = "comment_allow_image"
    const val KEY_ALLOW_VIDEO = "comment_allow_video"
    const val KEY_ALLOW_LINK = "comment_allow_link"
    const val KEY_ALLOW_MUSIC = "comment_allow_music"
    const val KEY_ALLOW_OTHER = "comment_allow_other"
    const val KEY_ALLOW_UNKNOWN = "comment_allow_unknown"
    const val KEY_KEYWORDS_TEXT = "comment_exclude_keywords_text"
    const val KEY_KEYWORDS_IMAGE_TEXT = "comment_exclude_keywords_image_text"
    const val KEY_KEYWORDS_VIDEO_TEXT = "comment_exclude_keywords_video_text"
    const val KEY_KEYWORDS_CARD_TEXT = "comment_exclude_keywords_card_text"
    const val KEY_KEYWORD_TEXT = "comment_keyword_text"
    const val KEY_KEYWORD_IMAGE = "comment_keyword_image"
    const val KEY_KEYWORD_VIDEO = "comment_keyword_video"
    const val KEY_KEYWORD_CARD = "comment_keyword_card"
    const val KEY_LOG_ENABLE = "comment_log_enable"
    const val KEY_LOGS = "comment_logs"
    const val KEY_SUCCESS_RECORDS = "comment_success_records"

    const val LIST_WHITELIST = 0
    const val LIST_BLACKLIST = 1
    const val DELAY_FIXED = 0
    const val DELAY_RANDOM = 1

    const val DEFAULT_ENABLE = false
    const val DEFAULT_COMMENT_CONTENT = ""
    const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"
    const val DEFAULT_COMMENT_SELF = false
    const val DEFAULT_LIST_MODE = LIST_WHITELIST
    const val DEFAULT_DAILY_COMMENT_LIMIT = 0
    const val DEFAULT_DELAY_MODE = DELAY_RANDOM
    const val DEFAULT_FIXED_DELAY_SECONDS = 300
    const val DEFAULT_RANDOM_MIN_SECONDS = 60
    const val DEFAULT_RANDOM_MAX_SECONDS = 3600
    const val DEFAULT_TIME_WINDOW_ENABLE = false
    const val DEFAULT_START_TIME = "08:00:00"
    const val DEFAULT_END_TIME = "23:30:00"
    const val DEFAULT_MAX_AGE_HOURS = 24

    const val VAR_TIME = "\${time}"

    fun normalizeTimeFormat(value: String?): String {
        return value.orEmpty().trim().ifBlank { DEFAULT_TIME_FORMAT }
    }

    fun isValidTimeFormat(value: String?): Boolean {
        return runCatching {
            SimpleDateFormat(normalizeTimeFormat(value), Locale.getDefault())
        }.isSuccess
    }

    fun renderCommentContent(template: String?, timeFormat: String?, timestamp: Long): String {
        val content = template.orEmpty().trim()
        if (!content.contains(VAR_TIME)) return content
        val date = Date(timestamp)
        val time = runCatching {
            SimpleDateFormat(normalizeTimeFormat(timeFormat), Locale.getDefault()).format(date)
        }.getOrElse {
            SimpleDateFormat(DEFAULT_TIME_FORMAT, Locale.getDefault()).format(date)
        }
        return content.replace(VAR_TIME, time)
    }
}
