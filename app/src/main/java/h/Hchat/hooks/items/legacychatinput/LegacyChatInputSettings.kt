package h.Hchat.hooks.items.legacychatinput

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage

object LegacyChatInputSettings {
    const val PREFS_NAME = "Hchat_legacy_chat_input_config"
    const val KEY_ENABLE = "legacy_chat_input_enable"
    const val DEFAULT_ENABLE = false

    fun preferences(context: Context): SharedPreferences =
        HchatStorage.preferences(context, PREFS_NAME)
}
