package h.Hchat.utils

object WeChatIdRules {
    private val HEX_REGEX = Regex("[0-9a-fA-F]{24,64}")
    private val LONG_DIGIT_REGEX = Regex("[0-9]{12,}")
    fun isLikelyContactId(value: String?): Boolean {
        val s = value?.trim().orEmpty()
        if (s.length !in 3..80) return false
        if (s.endsWith("@chatroom") || s.endsWith("@im.chatroom")) return false
        if (s.contains(':') || s.contains('\n') || s.contains('\r') || s.contains(' ')) return false
        if (s.startsWith("http://") || s.startsWith("https://") || s.startsWith("THUMBNAIL_DIRPATH://")) return false
        if (s.endsWith("@openim")) {
            val openImId = s.removeSuffix("@openim")
            return openImId.isNotEmpty() &&
                !openImId.contains('@') &&
                openImId.all { isAllowedContactIdChar(it) }
        }
        if (s.contains('@')) return false
        if (HEX_REGEX.matches(s)) return false
        if (LONG_DIGIT_REGEX.matches(s)) return false
        if (!s.any { it in 'A'..'Z' || it in 'a'..'z' }) return false
        return s.all { isAllowedContactIdChar(it) }
    }

    private fun isAllowedContactIdChar(ch: Char): Boolean {
        return ch in 'A'..'Z' ||
            ch in 'a'..'z' ||
            ch in '0'..'9' ||
            ch == '_' ||
            ch == '-' ||
            ch == '.'
    }
}
