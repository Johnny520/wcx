package h.Hchat.hooks.items.settings

import android.content.Context
import h.Hchat.preferences.HchatStorage

class SettingsEntrySettings(context: Context) {
    private val prefs = HchatStorage.preferences(context, PREFS_NAME)

    fun plusMenuEnabled(): Boolean {
        return prefs.getBoolean(KEY_PLUS_MENU_ENABLE, DEFAULT_PLUS_MENU_ENABLE)
    }

    fun plusLongPressEnabled(): Boolean {
        return prefs.getBoolean(KEY_PLUS_LONG_PRESS_ENABLE, DEFAULT_PLUS_LONG_PRESS_ENABLE)
    }

    fun pluginAgentPlusMenuEnabled(): Boolean {
        return prefs.getBoolean(KEY_PLUGIN_AGENT_PLUS_MENU_ENABLE, DEFAULT_PLUGIN_AGENT_PLUS_MENU_ENABLE)
    }

    companion object {
        const val PREFS_NAME = "settings_entry"
        const val KEY_PLUS_MENU_ENABLE = "plus_menu_enable"
        const val KEY_PLUS_LONG_PRESS_ENABLE = "plus_long_press_enable"
        const val KEY_PLUGIN_AGENT_PLUS_MENU_ENABLE = "plugin_agent_plus_menu_enable"
        const val DEFAULT_PLUS_MENU_ENABLE = false
        const val DEFAULT_PLUS_LONG_PRESS_ENABLE = false
        const val DEFAULT_PLUGIN_AGENT_PLUS_MENU_ENABLE = false
    }
}
