package h.Hchat.hooks.items.payment.gift

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage

class GiftSettings @JvmOverloads constructor(
    private val classLoader: ClassLoader,
    private val hostContext: Context? = null
) {
    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, false)

    fun getDelayMillis(): Long {
        val value = getInt(KEY_DELAY_VALUE, 0)
        val unit = getInt(KEY_DELAY_UNIT, 0)
        return if (unit == 1) value * 1000L else value.toLong()
    }

    fun getBoolean(key: String, def: Boolean): Boolean = try {
        getHostPreferences()?.getBoolean(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getInt(key: String, def: Int): Int = try {
        getHostPreferences()?.getInt(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getLong(key: String, def: Long): Long = try {
        getHostPreferences()?.getLong(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getString(key: String, def: String): String = try {
        getHostPreferences()?.getString(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun putBoolean(key: String, value: Boolean): Boolean = edit { it.putBoolean(key, value) }

    fun putInt(key: String, value: Int): Boolean = edit { it.putInt(key, value) }

    fun putString(key: String, value: String): Boolean = edit { it.putString(key, value) }

    fun recordResult(orderId: String, success: Boolean) {
        val prefs = getHostPreferences() ?: return
        try {
            val editor = prefs.edit()
            editor.putString(KEY_LAST_ORDER, orderId)
            editor.putLong(KEY_LAST_TIME, System.currentTimeMillis())
            if (success) {
                editor.putInt(KEY_SUCCESS, prefs.getInt(KEY_SUCCESS, 0) + 1)
            } else {
                editor.putInt(KEY_FAILED, prefs.getInt(KEY_FAILED, 0) + 1)
            }
            editor.apply()
        } catch (_: Throwable) {
        }
    }

    fun resetStats() = edit {
        it.putInt(KEY_SUCCESS, 0)
        it.putInt(KEY_FAILED, 0)
        it.putString(KEY_LAST_ORDER, "")
        it.putLong(KEY_LAST_TIME, 0L)
    }

    fun getHostPreferences(): SharedPreferences? =
        hostContext?.let { HchatStorage.preferences(it, PREFS_NAME) }

    private inline fun edit(block: (SharedPreferences.Editor) -> Unit): Boolean = try {
        val prefs = getHostPreferences()
        if (prefs == null) {
            false
        } else {
            val editor = prefs.edit()
            block(editor)
            editor.apply()
            true
        }
    } catch (_: Throwable) {
        false
    }

    companion object {
        const val PREFS_NAME = "Hchat_gift_config"
        const val KEY_ENABLE = "gift_auto_enable"
        const val KEY_DELAY_VALUE = "gift_delay_value"
        const val KEY_DELAY_UNIT = "gift_delay_unit"
        const val KEY_LOG_ENABLE = "gift_log_enable"
        const val KEY_SUCCESS = "gift_success_count"
        const val KEY_FAILED = "gift_failed_count"
        const val KEY_LAST_ORDER = "gift_last_order"
        const val KEY_LAST_TIME = "gift_last_time"
        const val KEY_LAST_RESULT = "gift_last_result"
    }
}
