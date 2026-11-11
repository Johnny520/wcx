package h.Hchat.hooks.items.linkparse

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray

internal class LinkParseSettings(context: Context) {
    private val prefs = HchatStorage.preferences(context, PREFS_NAME)

    fun enabled() = prefs.getBoolean(KEY_ENABLE, false)

    fun enabled(platform: WorkLinkMatcher.Platform) = prefs.getBoolean(
        when (platform) {
            WorkLinkMatcher.Platform.DOUYIN -> KEY_DOUYIN
            WorkLinkMatcher.Platform.XIAOHONGSHU -> KEY_XHS
        },
        false
    )

    fun allowedTalkers(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ALLOWED_TALKERS, "[]").orEmpty())
        buildSet {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }.getOrDefault(emptySet())

    fun saveAllowedTalkers(values: Set<String>) {
        val array = JSONArray()
        values.map { it.trim() }.filter(String::isNotEmpty).distinct().forEach { array.put(it) }
        prefs.edit().putString(KEY_ALLOWED_TALKERS, array.toString()).commit()
    }

    fun groupAtMeOnly() = prefs.getBoolean(KEY_GROUP_AT_ME_ONLY, false)

    fun allows(
        platform: WorkLinkMatcher.Platform,
        talker: String,
        group: Boolean,
        outgoing: Boolean,
        atMe: Boolean
    ): Boolean {
        if (!enabled() || !enabled(platform)) return false
        if (outgoing) return true
        if (talker.isBlank() || talker !in allowedTalkers()) return false
        return !group || !groupAtMeOnly() || atMe
    }

    companion object {
        const val PREFS_NAME = "Hchat_link_parse_config"
        const val KEY_ENABLE = "enable"
        const val KEY_DOUYIN = "douyin"
        const val KEY_XHS = "xiaohongshu"
        const val KEY_ALLOWED_TALKERS = "allowed_talkers"
        const val KEY_GROUP_AT_ME_ONLY = "group_at_me_only"
    }
}
