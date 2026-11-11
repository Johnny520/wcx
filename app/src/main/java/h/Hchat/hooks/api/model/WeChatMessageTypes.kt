package h.Hchat.hooks.api.model

object WeChatMessageTypes {
    const val TEXT = 1
    const val IMAGE = 3
    const val VOICE = 34
    const val VIDEO = 43
    const val EMOJI = 47
    const val LOCATION = 48
    const val APP = 49
    const val SYSTEM = 10000
    const val RECALLED = 10002
    const val VIDEO_ACCOUNT = 0x2D000031
    const val VIDEO_ACCOUNT_CARD = 0x2E000031
    const val VIDEO_ACCOUNT_LIVE = 0x3A000031

    fun normalize(type: Int): Int {
        if (type <= 0) return type
        val low8 = type and 0xFF
        val low16 = type and 0xFFFF
        if ((type ushr 16) == 0) return type
        if (low16 == SYSTEM || low16 == RECALLED) return low16
        return if (low8 != 0 && low16 == low8) low8 else type
    }

    fun isText(type: Int): Boolean = normalize(type) == TEXT

    fun isImage(type: Int): Boolean = normalize(type) == IMAGE

    fun isVoice(type: Int): Boolean = normalize(type) == VOICE

    fun isVideo(type: Int): Boolean = normalize(type) == VIDEO

    fun isEmoji(type: Int): Boolean = normalize(type) == EMOJI

    fun isLocation(type: Int): Boolean = normalize(type) == LOCATION

    fun isApp(type: Int): Boolean = normalize(type) == APP

    fun isVideoAccount(type: Int): Boolean {
        return type == VIDEO_ACCOUNT ||
            type == VIDEO_ACCOUNT_CARD ||
            type == VIDEO_ACCOUNT_LIVE
    }

    fun isSystem(type: Int): Boolean {
        val normalized = normalize(type)
        return normalized == SYSTEM || normalized == RECALLED
    }

    fun nameOf(type: Int): String = when (normalize(type)) {
        TEXT -> "text"
        IMAGE -> "image"
        VOICE -> "voice"
        VIDEO -> "video"
        EMOJI -> "emoji"
        LOCATION -> "location"
        APP -> "app"
        SYSTEM -> "system"
        RECALLED -> "recalled"
        else -> "unknown($type)"
    }
}
