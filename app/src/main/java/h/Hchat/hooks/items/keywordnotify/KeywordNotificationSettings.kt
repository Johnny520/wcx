package h.Hchat.hooks.items.keywordnotify

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject

class KeywordNotificationSettings(context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun getBoolean(key: String, def: Boolean): Boolean = runCatching {
        prefs?.getBoolean(key, def) ?: def
    }.getOrDefault(def)

    fun getString(key: String, def: String): String = runCatching {
        prefs?.getString(key, def) ?: def
    }.getOrDefault(def)

    fun putString(key: String, value: String) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun keywords(): List<KeywordRule> = parseKeywords(getString(KEY_KEYWORDS, ""))

    fun saveKeywords(values: List<KeywordRule>) {
        putString(KEY_KEYWORDS, encodeKeywords(values))
    }

    fun excludeContacts(): Set<String> = parseStringSet(getString(KEY_EXCLUDE_CONTACTS, ""))

    fun includeContacts(): Set<String> = parseStringSet(getString(KEY_INCLUDE_CONTACTS, ""))

    fun saveExcludeContacts(values: Set<String>) {
        putString(KEY_EXCLUDE_CONTACTS, encodeStringSet(values))
    }

    fun saveIncludeContacts(values: Set<String>) {
        putString(KEY_INCLUDE_CONTACTS, encodeStringSet(values))
    }

    fun shouldHandleTalker(talker: String): Boolean {
        if (talker.isBlank()) return false
        return if (getBoolean(KEY_FILTER_MODE, DEFAULT_FILTER_MODE)) {
            includeContacts().contains(talker)
        } else {
            !excludeContacts().contains(talker)
        }
    }

    companion object {
        const val PREFS_NAME = "Hchat_keyword_notification_config"

        const val KEY_ENABLE = "keyword_notify_enable"
        const val KEY_NOTIFY = "keyword_notify_system"
        const val KEY_NOTIFY_SOUND = "keyword_notify_sound"
        const val KEY_NOTIFY_VIBRATE = "keyword_notify_vibrate"
        const val KEY_NOTIFY_RINGTONE = "keyword_notify_ringtone"
        const val KEY_KEYWORD_NOTIFY_SOUND = "keyword_notify_keyword_sound"
        const val KEY_KEYWORD_NOTIFY_VIBRATE = "keyword_notify_keyword_vibrate"
        const val KEY_KEYWORD_NOTIFY_RINGTONE = "keyword_notify_keyword_ringtone"
        const val KEY_AT_ME_NOTIFY_SOUND = "keyword_notify_at_me_sound"
        const val KEY_AT_ME_NOTIFY_VIBRATE = "keyword_notify_at_me_vibrate"
        const val KEY_AT_ME_NOTIFY_RINGTONE = "keyword_notify_at_me_ringtone"
        const val KEY_AT_ALL_NOTIFY_SOUND = "keyword_notify_at_all_sound"
        const val KEY_AT_ALL_NOTIFY_VIBRATE = "keyword_notify_at_all_vibrate"
        const val KEY_AT_ALL_NOTIFY_RINGTONE = "keyword_notify_at_all_ringtone"
        const val KEY_TOAST = "keyword_notify_toast"
        const val KEY_ANY_GROUP = "keyword_notify_any_group"
        const val KEY_ANY_PRIVATE = "keyword_notify_any_private"
        const val KEY_AT_ME = "keyword_notify_at_me"
        const val KEY_AT_ALL = "keyword_notify_at_all"
        const val KEY_QUIET = "keyword_notify_quiet"
        const val KEY_QUIET_START = "keyword_notify_quiet_start"
        const val KEY_QUIET_END = "keyword_notify_quiet_end"
        const val KEY_FILTER_MODE = "keyword_notify_filter_mode"
        const val KEY_KEYWORDS = "keyword_notify_keywords"
        const val KEY_EXCLUDE_CONTACTS = "keyword_notify_exclude_contacts"
        const val KEY_INCLUDE_CONTACTS = "keyword_notify_include_contacts"
        const val KEY_LAST_TIME = "keyword_notify_last_time"
        const val KEY_LAST_KEYWORD = "keyword_notify_last_keyword"
        const val KEY_KEYWORD_TITLE = "keyword_notify_keyword_title"
        const val KEY_KEYWORD_CONTENT = "keyword_notify_keyword_content"
        const val KEY_KEYWORD_TOAST = "keyword_notify_keyword_toast"
        const val KEY_AT_ME_TITLE = "keyword_notify_at_me_title"
        const val KEY_AT_ME_CONTENT = "keyword_notify_at_me_content"
        const val KEY_AT_ME_TOAST = "keyword_notify_at_me_toast"
        const val KEY_AT_ALL_TITLE = "keyword_notify_at_all_title"
        const val KEY_AT_ALL_CONTENT = "keyword_notify_at_all_content"
        const val KEY_AT_ALL_TOAST = "keyword_notify_at_all_toast"

        const val DEFAULT_ENABLE = false
        const val DEFAULT_NOTIFY = true
        const val DEFAULT_NOTIFY_SOUND = true
        const val DEFAULT_NOTIFY_VIBRATE = true
        const val DEFAULT_TOAST = true
        const val DEFAULT_ANY_GROUP = false
        const val DEFAULT_ANY_PRIVATE = false
        const val DEFAULT_AT_ME = true
        const val DEFAULT_AT_ALL = true
        const val DEFAULT_QUIET = false
        const val DEFAULT_FILTER_MODE = false
        const val DEFAULT_QUIET_START = "22:00:00"
        const val DEFAULT_QUIET_END = "08:00:00"
        const val DEFAULT_KEYWORD_TITLE = "关键词通知 %sender%"
        const val DEFAULT_KEYWORD_CONTENT = "%content%"
        const val DEFAULT_KEYWORD_TOAST = "收到关注消息"
        const val DEFAULT_AT_ME_TITLE = "有人@我 %sender%"
        const val DEFAULT_AT_ME_CONTENT = "%content%"
        const val DEFAULT_AT_ME_TOAST = "有人 @ 你"
        const val DEFAULT_AT_ALL_TITLE = "%keyword% %sender%"
        const val DEFAULT_AT_ALL_CONTENT = "%content%"
        const val DEFAULT_AT_ALL_TOAST = "%keyword%"

        @JvmStatic
        fun parseKeywords(value: String?): List<KeywordRule> {
            if (value.isNullOrBlank()) return emptyList()
            return runCatching {
                val result = ArrayList<KeywordRule>()
                if (value.trim().startsWith("[")) {
                    val array = JSONArray(value)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i)
                        if (obj != null) {
                            val keyword = obj.optString("keyword").trim()
                            if (keyword.isNotEmpty()) result += KeywordRule(keyword, obj.optBoolean("wholeWord", false))
                        } else {
                            val keyword = array.optString(i).trim()
                            if (keyword.isNotEmpty()) result += KeywordRule(keyword, false)
                        }
                    }
                } else {
                    val obj = JSONObject(value)
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val keyword = keys.next().trim()
                        if (keyword.isNotEmpty()) result += KeywordRule(keyword, obj.optBoolean(keyword, false))
                    }
                }
                result.distinctBy { it.keyword }
            }.getOrDefault(emptyList())
        }

        @JvmStatic
        fun encodeKeywords(values: List<KeywordRule>): String {
            val array = JSONArray()
            values.distinctBy { it.keyword }.forEach { rule ->
                array.put(JSONObject().apply {
                    put("keyword", rule.keyword)
                    put("wholeWord", rule.wholeWord)
                })
            }
            return array.toString()
        }

        @JvmStatic
        fun parseStringSet(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return runCatching {
                val array = JSONArray(value)
                buildSet {
                    for (i in 0 until array.length()) {
                        val id = array.optString(i).trim()
                        if (id.isNotEmpty()) add(id)
                    }
                }
            }.getOrDefault(emptySet())
        }

        @JvmStatic
        fun encodeStringSet(values: Set<String>): String {
            val array = JSONArray()
            values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { array.put(it) }
            return array.toString()
        }
    }
}

data class KeywordRule(
    val keyword: String,
    val wholeWord: Boolean = false
)
