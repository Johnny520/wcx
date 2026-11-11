package h.Hchat.hooks.items.selectedmessages

import android.content.Context
import h.Hchat.preferences.HchatStorage

object SelectedMessagesSettings {
    const val PREFS_NAME = "Hchat_selected_messages_config"
    const val KEY_ENABLE = "selected_messages_enable"
    const val KEY_BACKGROUND_SILENT_SEND = "selected_messages_background_silent_send"
    const val KEY_OFFICIAL_INTERVAL_MINUTES = "selected_messages_official_interval_minutes"
    const val KEY_SEND_INTERVAL_SECONDS = "selected_messages_send_interval_seconds"
    const val DEFAULT_ENABLE = true
    const val DEFAULT_BACKGROUND_SILENT_SEND = false
    const val DEFAULT_OFFICIAL_INTERVAL_MINUTES = 0
    const val DEFAULT_SEND_INTERVAL_SECONDS = 0
    const val MAX_OFFICIAL_INTERVAL_MINUTES = 1440
    const val MAX_SEND_INTERVAL_SECONDS = 3600

    fun isBackgroundSilentSendEnabled(context: Context): Boolean {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_BACKGROUND_SILENT_SEND, DEFAULT_BACKGROUND_SILENT_SEND)
    }

    fun officialIntervalMinutes(context: Context): Int {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getInt(KEY_OFFICIAL_INTERVAL_MINUTES, DEFAULT_OFFICIAL_INTERVAL_MINUTES)
            .coerceIn(0, MAX_OFFICIAL_INTERVAL_MINUTES)
    }

    fun sendIntervalSeconds(context: Context): Int {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getInt(KEY_SEND_INTERVAL_SECONDS, DEFAULT_SEND_INTERVAL_SECONDS)
            .coerceIn(0, MAX_SEND_INTERVAL_SECONDS)
    }
}
