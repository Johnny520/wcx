package h.Hchat.hooks.items.chattime

object ChatTimeStyleSettings {
    const val PREFS_NAME = "Hchat_chat_time_style_config"

    const val KEY_ENABLE = "chat_time_enable"
    const val KEY_MODE = "chat_time_mode"
    const val KEY_TIME_FORMAT = "chat_time_format"

    const val MODE_ORIGINAL = "original"
    const val MODE_EVERY = "every"
    const val MODE_CUSTOM = "custom"
    const val MODE_HIDDEN = "hidden"

    const val DEFAULT_ENABLE = true
    const val DEFAULT_MODE = MODE_ORIGINAL
    const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

    /** 微信原生时间间隔：相邻显示的时间标签至少相隔 5 分钟 */
    const val NATIVE_INTERVAL_MS = 5 * 60 * 1000L

    fun normalizeMode(value: String?): String = when (value) {
        MODE_EVERY -> MODE_EVERY
        MODE_CUSTOM -> MODE_CUSTOM
        MODE_HIDDEN -> MODE_HIDDEN
        else -> MODE_ORIGINAL
    }
}
