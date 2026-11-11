package h.Hchat.hooks.items.membertitle

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import h.Hchat.hooks.api.contact.WeChatChatroomApi
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.WeChatIdRules

class MemberTitleStore(context: Context) {
    private val prefs: SharedPreferences =
        HchatStorage.preferences(context, MemberTitleSettings.PREFS_NAME)

    fun isEnabled(): Boolean =
        prefs.getBoolean(MemberTitleSettings.KEY_ENABLE, MemberTitleSettings.DEFAULT_ENABLE)

    fun showMemberEnabled(): Boolean =
        prefs.getBoolean(MemberTitleSettings.KEY_SHOW_MEMBER, MemberTitleSettings.DEFAULT_SHOW_MEMBER)

    fun customTitle(roomId: String?, wxid: String?): String =
        cleanTitle(prefs.getString(MemberTitleSettings.CUSTOM_TITLE_PREFIX + key(roomId, wxid), "") ?: "")

    fun customColorSpec(roomId: String?, wxid: String?): ColorSpec? =
        parseColorSpec(prefs.getString(MemberTitleSettings.CUSTOM_COLOR_PREFIX + key(roomId, wxid), "") ?: "")

    fun customTextColorSpec(roomId: String?, wxid: String?): ColorSpec? =
        parseColorSpec(prefs.getString(MemberTitleSettings.CUSTOM_TEXT_COLOR_PREFIX + key(roomId, wxid), "") ?: "")

    fun saveCustom(roomId: String?, wxid: String?, title: String?, color: String?, textColor: String?) {
        val k = key(roomId, wxid)
        if (k.isEmpty()) return
        prefs.edit()
            .putString(MemberTitleSettings.CUSTOM_TITLE_PREFIX + k, cleanTitle(title))
            .putString(MemberTitleSettings.CUSTOM_COLOR_PREFIX + k, cleanColorSpec(color))
            .putString(MemberTitleSettings.CUSTOM_TEXT_COLOR_PREFIX + k, cleanColorSpec(textColor))
            .apply()
    }

    fun clearCustom(roomId: String?, wxid: String?) {
        val k = key(roomId, wxid)
        if (k.isEmpty()) return
        prefs.edit()
            .remove(MemberTitleSettings.CUSTOM_TITLE_PREFIX + k)
            .remove(MemberTitleSettings.CUSTOM_COLOR_PREFIX + k)
            .remove(MemberTitleSettings.CUSTOM_TEXT_COLOR_PREFIX + k)
            .apply()
    }

    fun roleColorSpec(role: Int): ColorSpec {
        val key = when (role) {
            WeChatChatroomApi.ROLE_OWNER -> MemberTitleSettings.KEY_OWNER_COLOR
            WeChatChatroomApi.ROLE_ADMIN -> MemberTitleSettings.KEY_ADMIN_COLOR
            else -> MemberTitleSettings.KEY_MEMBER_COLOR
        }
        val def = when (role) {
            WeChatChatroomApi.ROLE_OWNER -> MemberTitleSettings.DEFAULT_OWNER_COLOR
            WeChatChatroomApi.ROLE_ADMIN -> MemberTitleSettings.DEFAULT_ADMIN_COLOR
            else -> MemberTitleSettings.DEFAULT_MEMBER_COLOR
        }
        return parseColorSpec(prefs.getString(key, def) ?: def) ?: ColorSpec.solid(Color.parseColor(def))
    }

    fun roleTitle(role: Int): String {
        val key = when (role) {
            WeChatChatroomApi.ROLE_OWNER -> MemberTitleSettings.KEY_OWNER_TITLE
            WeChatChatroomApi.ROLE_ADMIN -> MemberTitleSettings.KEY_ADMIN_TITLE
            else -> MemberTitleSettings.KEY_MEMBER_TITLE
        }
        val def = when (role) {
            WeChatChatroomApi.ROLE_OWNER -> MemberTitleSettings.DEFAULT_OWNER_TITLE
            WeChatChatroomApi.ROLE_ADMIN -> MemberTitleSettings.DEFAULT_ADMIN_TITLE
            else -> MemberTitleSettings.DEFAULT_MEMBER_TITLE
        }
        return cleanTitle(prefs.getString(key, def) ?: def).ifEmpty { def }
    }

    fun roleTextColorSpec(role: Int): ColorSpec {
        val key = when (role) {
            WeChatChatroomApi.ROLE_OWNER -> MemberTitleSettings.KEY_OWNER_TEXT_COLOR
            WeChatChatroomApi.ROLE_ADMIN -> MemberTitleSettings.KEY_ADMIN_TEXT_COLOR
            else -> MemberTitleSettings.KEY_MEMBER_TEXT_COLOR
        }
        return parseColorSpec(prefs.getString(key, MemberTitleSettings.DEFAULT_TEXT_COLOR) ?: MemberTitleSettings.DEFAULT_TEXT_COLOR)
            ?: ColorSpec.solid(Color.WHITE)
    }

    fun customDefaultColorSpec(): ColorSpec =
        parseColorSpec(prefs.getString(MemberTitleSettings.KEY_CUSTOM_COLOR, MemberTitleSettings.DEFAULT_CUSTOM_COLOR))
            ?: ColorSpec.solid(Color.parseColor(MemberTitleSettings.DEFAULT_CUSTOM_COLOR))

    fun customDefaultTextColorSpec(): ColorSpec =
        parseColorSpec(prefs.getString(MemberTitleSettings.KEY_CUSTOM_TEXT_COLOR, MemberTitleSettings.DEFAULT_TEXT_COLOR))
            ?: ColorSpec.solid(Color.WHITE)

    private fun key(roomId: String?, wxid: String?): String {
        val room = roomId?.trim().orEmpty()
        val member = wxid?.trim().orEmpty()
        if (room.isEmpty() || member.isEmpty()) return ""
        return room + "_" + member
    }

    companion object {
        fun cleanTitle(value: String?): String =
            value.orEmpty()
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .trim()
                .take(8)

        fun cleanColor(value: String?): String {
            val s = value?.trim().orEmpty()
            if (s.isEmpty()) return ""
            val normalized = if (s.startsWith("#")) s else "#$s"
            val hex = normalized.substring(1)
            return if ((hex.length == 6 || hex.length == 8) && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "#${hex.uppercase()}"
            } else {
                ""
            }
        }

        fun cleanColorSpec(value: String?): String {
            val s = value?.trim().orEmpty()
            if (s.isEmpty()) return ""
            val parts = s.split(',', '-', '，')
                .map { cleanColor(it) }
                .filter { it.isNotEmpty() }
                .take(2)
            if (parts.isEmpty()) return ""
            return parts.joinToString(",")
        }

        fun parseColor(value: String?): Int? {
            val color = cleanColor(value)
            if (color.isEmpty()) return null
            return runCatching { Color.parseColor(color) }.getOrNull()
        }

        fun parseColorSpec(value: String?): ColorSpec? {
            val cleaned = cleanColorSpec(value)
            if (cleaned.isEmpty()) return null
            val colors = cleaned.split(',')
                .mapNotNull { parseColor(it) }
                .take(2)
            if (colors.isEmpty()) return null
            return if (colors.size == 1) ColorSpec.solid(colors[0]) else ColorSpec.gradient(colors[0], colors[1])
        }

        fun isValidMemberId(value: String?): Boolean {
            return WeChatIdRules.isLikelyContactId(value)
        }
    }

    data class ColorSpec(val startColor: Int, val endColor: Int = startColor) {
        val isGradient: Boolean
            get() = startColor != endColor

        fun toConfigString(): String {
            val start = String.format("#%06X", 0xFFFFFF and startColor)
            if (!isGradient) return start
            val end = String.format("#%06X", 0xFFFFFF and endColor)
            return "$start,$end"
        }

        companion object {
            fun solid(color: Int): ColorSpec = ColorSpec(color, color)

            fun gradient(startColor: Int, endColor: Int): ColorSpec = ColorSpec(startColor, endColor)
        }
    }
}
