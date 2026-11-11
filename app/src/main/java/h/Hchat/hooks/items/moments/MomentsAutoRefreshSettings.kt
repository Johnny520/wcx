package h.Hchat.hooks.items.moments

object MomentsAutoRefreshSettings {
    const val PREFS_NAME = "Hchat_moments_auto_refresh_config"
    const val KEY_ENABLE = "enable"
    const val KEY_INTERVAL_SECONDS = "interval_seconds"
    const val KEY_TIME_WINDOW_ENABLE = "time_window_enable"
    const val KEY_START_TIME = "start_time"
    const val KEY_END_TIME = "end_time"
    const val KEY_LAST_TIME = "last_time"
    const val KEY_LAST_RESULT = "last_result"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_INTERVAL_SECONDS = 300
    const val DEFAULT_TIME_WINDOW_ENABLE = false
    const val DEFAULT_START_TIME = "08:00:00"
    const val DEFAULT_END_TIME = "23:00:00"
}
