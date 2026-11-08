package h.Hchat.hooks.items.messagetextcolor

object MessageTextColorSettings {
    const val PREFS_NAME = "Hchat_message_text_color_config"

    const val KEY_ENABLE = "message_text_color_enable"
    const val KEY_LEFT_LIGHT_COLOR = "message_text_color_left_light"
    const val KEY_RIGHT_LIGHT_COLOR = "message_text_color_right_light"
    const val KEY_LEFT_DARK_COLOR = "message_text_color_left_dark"
    const val KEY_RIGHT_DARK_COLOR = "message_text_color_right_dark"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_LEFT_LIGHT_COLOR = "#FF000000"
    const val DEFAULT_RIGHT_LIGHT_COLOR = "#FF000000"
    const val DEFAULT_LEFT_DARK_COLOR = "#FFFFFFFF"
    const val DEFAULT_RIGHT_DARK_COLOR = "#FF000000"

    fun cleanColorSpec(value: String?): String {
        val parts = value.orEmpty()
            .split(',')
            .mapNotNull { cleanColor(it).takeIf { color -> color.isNotEmpty() } }
            .take(2)
        if (parts.isEmpty()) return ""
        return if (parts.size == 1 || parts[0] == parts[1]) parts[0] else "${parts[0]},${parts[1]}"
    }

    fun cleanColor(value: String?): String {
        val raw = value.orEmpty().trim()
        if (raw.isEmpty()) return ""
        val normalized = if (raw.startsWith("#")) raw else "#$raw"
        val hex = normalized.substring(1)
        if (hex.length != 6 && hex.length != 8) return ""
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return ""
        return "#${hex.uppercase()}"
    }
}
