package h.Hchat.hooks.items.messageaffix

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage
import java.text.SimpleDateFormat
import java.util.Locale

object MessageAffixSettings {
    const val PREFS_NAME = "Hchat_message_affix_config"
    const val KEY_ENABLE = "message_affix_enable"
    const val KEY_TEXT_FORMAT = "message_affix_text_format"
    const val KEY_TIME_FORMAT = "message_affix_time_format"
    const val DEFAULT_ENABLE = false
    const val DEFAULT_TEXT_FORMAT = "\${sendText}"
    const val DEFAULT_TIME_FORMAT = "HH:mm:ss"

    const val VAR_SEND_TEXT = "\${sendText}"
    const val VAR_LINE = "\${line}"
    const val VAR_SEND_TIME = "\${sendTime}"
    const val VAR_TOTAL_MESSAGES = "\${totalMsg}"
    const val VAR_TEXT_MESSAGES = "\${textMsg}"
    const val VAR_TEXT_CHARACTERS = "\${textWord}"
    const val VAR_EMOJI_MESSAGES = "\${emojiMsg}"
    const val VAR_TRANSFER_MESSAGES = "\${transferMsg}"
    const val VAR_RED_PACKET_MESSAGES = "\${redBagMsg}"
    const val VAR_FILE_MESSAGES = "\${fileMsg}"
    const val VAR_SEND_DURATION = "\${sendDuration}"

    fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)

    fun textFormat(preferences: SharedPreferences): String {
        return normalizeTextFormat(preferences.getString(KEY_TEXT_FORMAT, DEFAULT_TEXT_FORMAT))
    }

    fun normalizeTextFormat(value: String?): String = value.orEmpty().ifBlank { DEFAULT_TEXT_FORMAT }

    fun normalizeTimeFormat(value: String?): String =
        value.orEmpty().trim().ifBlank { DEFAULT_TIME_FORMAT }

    fun isValidTimeFormat(value: String?): Boolean = runCatching {
        SimpleDateFormat(normalizeTimeFormat(value), Locale.getDefault())
    }.isSuccess

    fun originalTextVariableCount(value: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val index = value.indexOf(VAR_SEND_TEXT, start)
            if (index < 0) return count
            count++
            start = index + VAR_SEND_TEXT.length
        }
    }
}
