package h.Hchat.hooks.items.inputhint

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InputHintStats(
    val totalMessages: Long = 0L,
    val textMessages: Long = 0L,
    val textCharacters: Long = 0L,
    val emojiMessages: Long = 0L,
    val transferMessages: Long = 0L,
    val redPacketMessages: Long = 0L,
    val fileMessages: Long = 0L
)

object InputHintSettings {
    const val PREFS_NAME = "Hchat_input_hint_config"

    const val KEY_ENABLE = "input_hint_enable"
    const val KEY_TEMPLATE = "input_hint_template"
    const val KEY_STATISTICS_ENABLE = "input_hint_statistics_enable"
    const val KEY_STATISTICS_DAY = "input_hint_statistics_day"
    const val KEY_TOTAL_MESSAGES = "input_hint_total_messages"
    const val KEY_TEXT_MESSAGES = "input_hint_text_messages"
    const val KEY_TEXT_CHARACTERS = "input_hint_text_characters"
    const val KEY_EMOJI_MESSAGES = "input_hint_emoji_messages"
    const val KEY_TRANSFER_MESSAGES = "input_hint_transfer_messages"
    const val KEY_RED_PACKET_MESSAGES = "input_hint_red_packet_messages"
    const val KEY_FILE_MESSAGES = "input_hint_file_messages"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_STATISTICS_ENABLE = true

    const val VAR_TOTAL_MESSAGES = "\${totalMsg}"
    const val VAR_TEXT_MESSAGES = "\${textMsg}"
    const val VAR_TEXT_CHARACTERS = "\${textWord}"
    const val VAR_EMOJI_MESSAGES = "\${emojiMsg}"
    const val VAR_TRANSFER_MESSAGES = "\${transferMsg}"
    const val VAR_RED_PACKET_MESSAGES = "\${redBagMsg}"
    const val VAR_FILE_MESSAGES = "\${fileMsg}"

    const val DEFAULT_TEMPLATE = "今日已发\${totalMsg}条"

    fun preferences(context: Context): SharedPreferences =
        HchatStorage.preferences(context, PREFS_NAME)

    fun template(preferences: SharedPreferences): String = normalizeTemplate(
        preferences.getString(KEY_TEMPLATE, DEFAULT_TEMPLATE)
    )

    fun normalizeTemplate(value: String?): String = value.orEmpty().ifBlank { DEFAULT_TEMPLATE }

    @Suppress("UNUSED_PARAMETER")
    fun readTodayStats(
        preferences: SharedPreferences,
        now: Long = System.currentTimeMillis()
    ): InputHintStats = OutgoingMessageStatsRepository.current()

    fun renderTemplate(template: String, stats: InputHintStats): String {
        return normalizeTemplate(template)
            .replace(VAR_TOTAL_MESSAGES, stats.totalMessages.toString())
            .replace(VAR_TEXT_MESSAGES, stats.textMessages.toString())
            .replace(VAR_TEXT_CHARACTERS, stats.textCharacters.toString())
            .replace(VAR_EMOJI_MESSAGES, stats.emojiMessages.toString())
            .replace(VAR_TRANSFER_MESSAGES, stats.transferMessages.toString())
            .replace(VAR_RED_PACKET_MESSAGES, stats.redPacketMessages.toString())
            .replace(VAR_FILE_MESSAGES, stats.fileMessages.toString())
    }

    fun dayKey(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
}
