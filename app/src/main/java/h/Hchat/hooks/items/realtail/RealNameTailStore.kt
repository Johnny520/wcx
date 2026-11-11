package h.Hchat.hooks.items.realtail

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.WeChatIdRules
import java.util.concurrent.ConcurrentHashMap

class RealNameTailStore(context: Context) {
    private val prefs: SharedPreferences =
        HchatStorage.preferences(context, RealNameTailSettings.PREFS_NAME)
    private val memory = ConcurrentHashMap<String, String>()

    fun isEnabled(): Boolean =
        prefs.getBoolean(RealNameTailSettings.KEY_ENABLE, RealNameTailSettings.DEFAULT_ENABLE)

    fun messageQueryEnabled(): Boolean =
        prefs.getBoolean(RealNameTailSettings.KEY_MESSAGE_QUERY, RealNameTailSettings.DEFAULT_MESSAGE_QUERY)

    fun visibleQueryEnabled(): Boolean =
        prefs.getBoolean(RealNameTailSettings.KEY_VISIBLE_QUERY, RealNameTailSettings.DEFAULT_VISIBLE_QUERY)

    fun globalPrefixEnabled(): Boolean =
        prefs.getBoolean(RealNameTailSettings.KEY_GLOBAL_PREFIX_ENABLE, false)

    fun globalPrefix(): String = cleanPrefix(prefs.getString(RealNameTailSettings.KEY_GLOBAL_PREFIX, "") ?: "")

    fun showGenderEnabled(): Boolean =
        prefs.getBoolean(RealNameTailSettings.KEY_SHOW_GENDER, RealNameTailSettings.DEFAULT_SHOW_GENDER)

    fun showRegionEnabled(): Boolean =
        prefs.getBoolean(RealNameTailSettings.KEY_SHOW_REGION, RealNameTailSettings.DEFAULT_SHOW_REGION)

    fun genderText(gender: Int): String {
        val key = when (gender) {
            1 -> RealNameTailSettings.KEY_GENDER_MALE_TEXT
            2 -> RealNameTailSettings.KEY_GENDER_FEMALE_TEXT
            else -> RealNameTailSettings.KEY_GENDER_UNKNOWN_TEXT
        }
        val def = when (gender) {
            1 -> RealNameTailSettings.DEFAULT_GENDER_MALE_TEXT
            2 -> RealNameTailSettings.DEFAULT_GENDER_FEMALE_TEXT
            else -> RealNameTailSettings.DEFAULT_GENDER_UNKNOWN_TEXT
        }
        return cleanLabel(prefs.getString(key, def) ?: def)
    }

    fun tailColor(): MemberTitleStore.ColorSpec? = colorValue(RealNameTailSettings.KEY_TAIL_COLOR, RealNameTailSettings.DEFAULT_TAIL_COLOR)

    fun bracketColor(): MemberTitleStore.ColorSpec? = colorValue(RealNameTailSettings.KEY_BRACKET_COLOR, RealNameTailSettings.DEFAULT_BRACKET_COLOR)

    fun genderColor(): MemberTitleStore.ColorSpec? = colorValue(RealNameTailSettings.KEY_GENDER_COLOR, RealNameTailSettings.DEFAULT_GENDER_COLOR)

    fun regionColor(): MemberTitleStore.ColorSpec? = colorValue(RealNameTailSettings.KEY_REGION_COLOR, RealNameTailSettings.DEFAULT_REGION_COLOR)

    fun tailWeight(): Int = weightValue(RealNameTailSettings.KEY_TAIL_WEIGHT)

    fun bracketWeight(): Int = weightValue(RealNameTailSettings.KEY_BRACKET_WEIGHT)

    fun genderWeight(): Int = weightValue(RealNameTailSettings.KEY_GENDER_WEIGHT)

    fun regionWeight(): Int = weightValue(RealNameTailSettings.KEY_REGION_WEIGHT)

    fun cachedTail(wxid: String?): String {
        val id = wxid?.trim().orEmpty()
        if (!isValidWxid(id)) return ""
        val key = RealNameTailSettings.CACHE_PREFIX + id
        if (!isSupportedQueryId(id)) {
            removeCachedTail(id, key)
            return ""
        }
        val value = memory[id] ?: prefs.getString(key, "").orEmpty()
        val normalized = normalizeMaskedName(value)
        if (normalized.isEmpty() || isLegacySingleTail(normalized)) {
            removeCachedTail(id, key)
            return ""
        }
        memory[id] = normalized
        if (normalized != value) prefs.edit().putString(key, normalized).apply()
        return normalized
    }

    fun hasTail(wxid: String?): Boolean = cachedTail(wxid).isNotEmpty()

    fun saveTail(wxid: String?, rawName: String?) {
        val id = wxid?.trim().orEmpty()
        if (!isSupportedQueryId(id)) return
        val tail = normalizeMaskedName(rawName)
        if (tail.isEmpty()) return
        memory[id] = tail
        prefs.edit().putString(RealNameTailSettings.CACHE_PREFIX + id, tail).apply()
    }

    fun displayTail(wxid: String?): String {
        val tail = cachedTail(wxid)
        if (tail.isEmpty()) return ""
        if (!globalPrefixEnabled()) return tail
        val prefix = globalPrefix()
        return if (prefix.isEmpty()) tail else prefix + tail.takeLast(1)
    }

    private fun removeCachedTail(id: String, key: String) {
        memory.remove(id)
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
    }

    private fun isLegacySingleTail(value: String): Boolean {
        return value.length == 1 && !value.contains('*')
    }

    private fun cleanPrefix(prefix: String): String {
        return prefix.trim().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(8)
    }

    private fun cleanLabel(label: String): String {
        return label.trim().replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').take(12)
    }

    private fun colorValue(key: String, defaultValue: String): MemberTitleStore.ColorSpec? {
        val raw = prefs.getString(key, defaultValue) ?: defaultValue
        return MemberTitleStore.parseColorSpec(raw)
    }

    private fun weightValue(key: String): Int {
        return cleanWeight(prefs.getInt(key, RealNameTailSettings.DEFAULT_TEXT_WEIGHT))
    }

    companion object {
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

        fun cleanColorSpec(value: String?): String = MemberTitleStore.cleanColorSpec(value)

        fun cleanWeight(value: Int): Int {
            return ((value.coerceIn(100, 900) + 50) / 100) * 100
        }

        fun isValidWxid(value: String?): Boolean {
            return WeChatIdRules.isLikelyContactId(value)
        }

        fun isSupportedQueryId(value: String?): Boolean {
            val id = value?.trim().orEmpty()
            return isValidWxid(id) && !id.endsWith("@openim", ignoreCase = true)
        }

        fun normalizeMaskedName(rawName: String?): String {
            val value = rawName?.trim().orEmpty()
            if (value.isEmpty() || value.length > 64) return ""
            if (value.all { it in '0'..'9' }) return ""
            if (value.contains('@') || value.contains('\n') || value.contains('\r')) return ""
            if (value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) ||
                value.startsWith("wxid_", ignoreCase = true) ||
                HEX_IDENTIFIER.matches(value)
            ) {
                return ""
            }
            return value
        }

        private val HEX_IDENTIFIER = Regex("[0-9a-fA-F]{24,64}")
    }
}
