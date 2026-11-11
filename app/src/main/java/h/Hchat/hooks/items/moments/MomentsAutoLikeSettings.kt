package h.Hchat.hooks.items.moments

object MomentsAutoLikeSettings {
    const val PREFS_NAME = "Hchat_moments_auto_like_config"

    const val KEY_ENABLE = "enable"
    const val KEY_LIKE_SELF = "like_self"
    const val KEY_ENABLED_AT_SECONDS = "enabled_at_seconds"
    const val KEY_LIST_MODE = "list_mode"
    const val KEY_WHITELIST = "whitelist"
    const val KEY_BLACKLIST = "blacklist"
    const val KEY_DAILY_LIKE_LIMIT = "daily_like_limit"
    const val KEY_DAILY_LIKE_DATE = "daily_like_date"
    const val KEY_DAILY_LIKE_COUNTS = "daily_like_counts"
    const val KEY_DELAY_MODE = "delay_mode"
    const val KEY_FIXED_DELAY_SECONDS = "fixed_delay_seconds"
    const val KEY_RANDOM_MIN_SECONDS = "random_min_seconds"
    const val KEY_RANDOM_MAX_SECONDS = "random_max_seconds"
    const val KEY_TIME_WINDOW_ENABLE = "time_window_enable"
    const val KEY_START_TIME = "start_time"
    const val KEY_END_TIME = "end_time"
    const val KEY_MAX_AGE_HOURS = "max_age_hours"
    const val KEY_ALLOW_TEXT = "allow_text"
    const val KEY_ALLOW_IMAGE = "allow_image"
    const val KEY_ALLOW_VIDEO = "allow_video"
    const val KEY_ALLOW_LINK = "allow_link"
    const val KEY_ALLOW_MUSIC = "allow_music"
    const val KEY_ALLOW_OTHER = "allow_other"
    const val KEY_ALLOW_UNKNOWN = "allow_unknown"
    const val KEY_KEYWORDS_TEXT = "exclude_keywords_text"
    const val KEY_KEYWORDS_IMAGE_TEXT = "exclude_keywords_image_text"
    const val KEY_KEYWORDS_VIDEO_TEXT = "exclude_keywords_video_text"
    const val KEY_KEYWORDS_CARD_TEXT = "exclude_keywords_card_text"
    const val KEY_KEYWORD_TEXT = "keyword_text"
    const val KEY_KEYWORD_IMAGE = "keyword_image"
    const val KEY_KEYWORD_VIDEO = "keyword_video"
    const val KEY_KEYWORD_CARD = "keyword_card"
    const val KEY_LOG_ENABLE = "log_enable"
    const val KEY_LOGS = "logs"
    const val KEY_SUCCESS_RECORDS = "success_records"

    const val LIST_WHITELIST = 0
    const val LIST_BLACKLIST = 1
    const val DELAY_FIXED = 0
    const val DELAY_RANDOM = 1

    const val DEFAULT_ENABLE = false
    const val DEFAULT_LIKE_SELF = false
    const val DEFAULT_LIST_MODE = LIST_WHITELIST
    const val DEFAULT_DAILY_LIKE_LIMIT = 0
    const val DEFAULT_DELAY_MODE = DELAY_RANDOM
    const val DEFAULT_FIXED_DELAY_SECONDS = 300
    const val DEFAULT_RANDOM_MIN_SECONDS = 60
    const val DEFAULT_RANDOM_MAX_SECONDS = 3600
    const val DEFAULT_TIME_WINDOW_ENABLE = false
    const val DEFAULT_START_TIME = "08:00:00"
    const val DEFAULT_END_TIME = "23:30:00"
    const val DEFAULT_MAX_AGE_HOURS = 24
}
