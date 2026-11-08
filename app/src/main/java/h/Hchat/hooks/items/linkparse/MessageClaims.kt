package h.Hchat.hooks.items.linkparse

import android.content.SharedPreferences
import org.json.JSONObject

/** Claims are persisted before work starts so a restart cannot replay a processed source message. */
internal class MessageClaims(private val prefs: SharedPreferences, private val maxEntries: Int = 512) {
    fun claim(key: String, expiry: Long, now: Long): Boolean {
        val raw = prefs.getString(KEY, "{}") ?: return false
        val values = runCatching { JSONObject(raw) }.getOrElse { return false }
        val keys = values.keys().asSequence().toList()
        for (old in keys) if (values.optLong(old, 0L) <= now) values.remove(old)
        if (values.has(key) || values.length() >= maxEntries) return false
        values.put(key, expiry)
        return prefs.edit().putString(KEY, values.toString()).commit()
    }
    companion object { private const val KEY = "recent" }
}
