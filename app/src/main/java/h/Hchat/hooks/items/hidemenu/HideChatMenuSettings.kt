package h.Hchat.hooks.items.hidemenu

import android.content.Context
import h.Hchat.preferences.HchatStorage

object HideChatMenuSettings {
    const val PREFS_NAME = "Hchat_hide_chat_menu_config"
    const val KEY_ENABLE = "hide_chat_menu_enable"
    const val KEY_TITLES = "hide_chat_menu_titles"
    const val DEFAULT_ENABLE = false
    const val DEFAULT_TITLES = "提醒,搜一搜,收藏"

    fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)

    fun parseTitles(value: String?): Set<String> {
        return value.orEmpty()
            .split(',', '，', ';', '；', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
