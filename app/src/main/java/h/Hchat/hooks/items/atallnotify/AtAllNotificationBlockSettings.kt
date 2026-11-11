package h.Hchat.hooks.items.atallnotify

import android.content.Context
import h.Hchat.preferences.HchatStorage

object AtAllNotificationBlockSettings {
    const val PREFS_NAME = "Hchat_block_at_all_notification_config"
    const val KEY_ENABLE = "block_at_all_notification_enable"
    const val KEY_GROUPS = "block_at_all_notification_groups"
    const val DEFAULT_ENABLE = false

    fun isEnabled(context: Context): Boolean {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }

    fun blocksGroup(context: Context, talker: String): Boolean {
        val prefs = HchatStorage.preferences(context, PREFS_NAME)
        if (!prefs.getBoolean(KEY_ENABLE, DEFAULT_ENABLE)) return false
        if (!prefs.contains(KEY_GROUPS)) return true
        return parseGroups(prefs.getString(KEY_GROUPS, "")).contains(talker.trim())
    }

    fun parseGroups(value: String?): Set<String> {
        return value.orEmpty()
            .split(',', '|', ';', '\n', '，', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
}
