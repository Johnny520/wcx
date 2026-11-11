package h.Hchat.hooks.items.musicorder

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray

class QQMusicOrderSettings(context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun isEnabled(): Boolean = boolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun interceptOwnCommand(): Boolean = boolean(KEY_INTERCEPT_OWN_COMMAND, DEFAULT_INTERCEPT_OWN_COMMAND)

    fun sendAsCard(): Boolean = boolean(KEY_SEND_AS_CARD, DEFAULT_SEND_AS_CARD)

    fun sendAsVoice(): Boolean = boolean(KEY_SEND_AS_VOICE, DEFAULT_SEND_AS_VOICE)

    fun customSingerEnabled(): Boolean = boolean(KEY_CUSTOM_SINGER, DEFAULT_CUSTOM_SINGER)

    fun replaceSingerWithNickname(): Boolean = boolean(KEY_REPLACE_SINGER_WITH_NICKNAME, DEFAULT_REPLACE_SINGER_WITH_NICKNAME)

    fun replaceCoverWithAvatar(): Boolean = boolean(KEY_REPLACE_COVER_WITH_AVATAR, DEFAULT_REPLACE_COVER_WITH_AVATAR)

    fun defaultSinger(): String = string(KEY_DEFAULT_SINGER, DEFAULT_SINGER).trim()

    fun appId(): String = string(KEY_APP_ID, DEFAULT_APP_ID).trim().ifBlank { DEFAULT_APP_ID }

    fun triggers(): List<String> {
        val value = string(KEY_TRIGGERS, DEFAULT_TRIGGERS)
        return value.split(',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(DEFAULT_TRIGGERS) }
    }

    fun allowedTalkers(): Set<String> = parseStringSet(string(KEY_ALLOWED_TALKERS, ""))

    fun saveAllowedTalkers(values: Set<String>) {
        prefs?.edit()?.putString(KEY_ALLOWED_TALKERS, encodeStringSet(values))?.commit()
    }

    private fun boolean(key: String, defaultValue: Boolean): Boolean = runCatching {
        prefs?.getBoolean(key, defaultValue) ?: defaultValue
    }.getOrDefault(defaultValue)

    private fun string(key: String, defaultValue: String): String = runCatching {
        prefs?.getString(key, defaultValue) ?: defaultValue
    }.getOrDefault(defaultValue)

    companion object {
        const val PREFS_NAME = "Hchat_qq_music_order_config"

        const val KEY_ENABLE = "qq_music_order_enable"
        const val KEY_INTERCEPT_OWN_COMMAND = "qq_music_order_intercept_own_command"
        const val KEY_SEND_AS_CARD = "qq_music_order_send_as_card"
        const val KEY_SEND_AS_VOICE = "qq_music_order_send_as_voice"
        const val KEY_CUSTOM_SINGER = "qq_music_order_custom_singer"
        const val KEY_DEFAULT_SINGER = "qq_music_order_default_singer"
        const val KEY_APP_ID = "qq_music_order_app_id"
        const val KEY_TRIGGERS = "qq_music_order_triggers"
        const val KEY_REPLACE_COVER_WITH_AVATAR = "qq_music_order_replace_cover_with_avatar"
        const val KEY_REPLACE_SINGER_WITH_NICKNAME = "qq_music_order_replace_singer_with_nickname"
        const val KEY_ALLOWED_TALKERS = "qq_music_order_allowed_talkers"

        const val DEFAULT_ENABLE = false
        const val DEFAULT_INTERCEPT_OWN_COMMAND = false
        const val DEFAULT_SEND_AS_CARD = true
        const val DEFAULT_SEND_AS_VOICE = false
        const val DEFAULT_CUSTOM_SINGER = false
        const val DEFAULT_SINGER = ""
        const val DEFAULT_APP_ID = "wx485a97c844086dc9"
        const val DEFAULT_TRIGGERS = "点歌"
        const val DEFAULT_REPLACE_COVER_WITH_AVATAR = false
        const val DEFAULT_REPLACE_SINGER_WITH_NICKNAME = false

        @JvmStatic
        fun parseStringSet(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return runCatching {
                val array = JSONArray(value)
                buildSet {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }.getOrDefault(emptySet())
        }

        @JvmStatic
        fun encodeStringSet(values: Set<String>): String {
            return JSONArray().apply {
                values.map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach(::put)
            }.toString()
        }
    }
}
