package h.Hchat.hooks.items.payment.core

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import h.Hchat.preferences.HchatStorage

class RedPacketSettings @JvmOverloads constructor(
    private val classLoader: ClassLoader,
    private val hostContext: Context? = null
) {
    fun getBoolean(key: String, def: Boolean): Boolean {
        return try {
            getHostPreferences()?.getBoolean(key, def) ?: def
        } catch (_: Throwable) {
            def
        }
    }

    fun getInt(key: String, def: Int): Int {
        return try {
            getHostPreferences()?.getInt(key, def) ?: def
        } catch (_: Throwable) {
            def
        }
    }

    fun getString(key: String, def: String): String {
        return try {
            getHostPreferences()?.getString(key, def) ?: def
        } catch (_: Throwable) {
            def
        }
    }

    fun contains(key: String): Boolean {
        return try {
            getHostPreferences()?.contains(key) == true
        } catch (_: Throwable) {
            false
        }
    }

    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, false)

    fun isSilentGrabEnabled(): Boolean = isEnabled() && getInt(KEY_GRAB_MODE, DEFAULT_GRAB_MODE) == 1

    fun getDelayMillis(): Long {
        val value = getInt(KEY_DELAY_VALUE, 0)
        val unit = getInt(KEY_DELAY_UNIT, 0)
        return if (unit == 1) value * 1000L else value.toLong()
    }

    fun ruleTemplates(): List<RedPacketRuleTemplate> {
        return RedPacketRuleConfig.parseTemplates(getString(RedPacketRuleConfig.KEY_TEMPLATES, ""))
    }

    fun ruleBindings(): List<RedPacketRuleBinding> {
        return RedPacketRuleConfig.parseBindings(getString(RedPacketRuleConfig.KEY_BINDINGS, ""))
    }

    fun isUserInList(id: String?, listKey: String): Boolean {
        if (TextUtils.isEmpty(id)) return false
        val list = getString(listKey, "")
        if (TextUtils.isEmpty(list)) return false
        return list.split("|").any { item -> id == item.trim() }
    }

    fun getHostPreferences(): SharedPreferences? {
        return hostContext?.let { HchatStorage.preferences(it, PREFS_NAME) }
    }

    companion object {
        const val PREFS_NAME = "Hchat_redpacket_config"

        const val KEY_ENABLE = "hb_auto_enable"
        const val KEY_SKIP_SELF = "hb_skip_self"
        const val KEY_AUTO_CLOSE = "hb_auto_close"
        const val KEY_GRAB_MODE = "hb_grab_mode"
        const val DEFAULT_GRAB_MODE = 1
        const val KEY_MODE = "hb_auto_mode"
        const val KEY_WHITELIST = "hb_auto_whitelist"
        const val KEY_BLACKLIST = "hb_auto_blacklist"
        const val KEY_DELAY_MODE = "hb_auto_delay_mode"
        const val KEY_DELAY_VALUE = "hb_auto_delay_value"
        const val KEY_DELAY_UNIT = "hb_auto_delay_unit"
        const val KEY_DELAY_RANDOM_MIN = "hb_auto_delay_random_min"
        const val KEY_DELAY_RANDOM_MAX = "hb_auto_delay_random_max"
        const val KEY_KW_MODE = "hb_kw_mode"
        const val KEY_KEYWORDS = "hb_keywords"
        const val KEY_BLOCK_NEW_GROUP_ENABLE = "hb_block_new_group_enable"
        const val KEY_BLOCK_NEW_GROUP_KNOWN = "hb_block_new_group_known"
        const val KEY_CHECK_TIMES = "hb_check_times"
        const val KEY_WISH_ENABLE = "hb_wish_enable"
        const val KEY_WISH_TEXT = "hb_wish_text"
        const val KEY_WISH_RANDOM = "hb_wish_random"
        const val KEY_WISH_TEMPLATES = "hb_wish_templates"
        const val KEY_FAKE_PACKET_ENABLE = "hb_fake_packet_enable"
        const val KEY_STATS_COUNT = "hb_stats_count"
        const val KEY_STATS_AMOUNT = "hb_stats_amount"
        const val KEY_STATS_FAILED = "hb_stats_failed"
        const val KEY_STATS_TODAY = "hb_stats_today"
        const val KEY_NOTIFY_SYSTEM_ENABLE = "hb_notify_system_enable"
        const val KEY_NOTIFY_TOAST_ENABLE = "hb_notify_toast_enable"
        const val KEY_NOTIFY_TEXT = "hb_notify_text"
        const val KEY_NOTIFY_TITLE = "hb_notify_title"
        const val KEY_NOTIFY_TOAST_TEXT = "hb_notify_toast_text"
        const val KEY_NOTIFY_SOUND_ENABLE = "hb_notify_sound_enable"
        const val KEY_NOTIFY_SOUND_MODE = "hb_notify_sound_mode"
        const val KEY_NOTIFY_VIBRATE_ENABLE = "hb_notify_vibrate_enable"
        const val KEY_NOTIFY_SOUND_URI = "hb_notify_sound_uri"
        const val KEY_ANNOUNCE_ENABLE = "hb_announce_enable"
        const val KEY_ANNOUNCE_TEXT = "hb_announce_text"
        const val KEY_TIME_FORMAT = "hb_time_format"
        const val DEFAULT_TIME_FORMAT = PaymentTemplateTimeFormatter.DEFAULT_PATTERN
        const val NOTIFY_SOUND_MODE_SYSTEM = 0
        const val NOTIFY_SOUND_MODE_CUSTOM = 1
        const val KEY_NOTIFY_FAILED_SYSTEM_ENABLE = "hb_notify_failed_system_enable"
        const val KEY_NOTIFY_FAILED_TOAST_ENABLE = "hb_notify_failed_toast_enable"
        const val KEY_NOTIFY_FAILED_TEXT = "hb_notify_failed_text"
        const val KEY_NOTIFY_FAILED_TITLE = "hb_notify_failed_title"
        const val KEY_NOTIFY_FAILED_TOAST_TEXT = "hb_notify_failed_toast_text"
        const val KEY_REPLY_ENABLE = "hb_reply_enable"
        const val KEY_REPLY_TEXT = "hb_reply_text"
        const val KEY_REPLY_RANDOM = "hb_reply_random"
        const val KEY_REPLY_TEMPLATES = "hb_reply_templates"
        const val KEY_REPLY_CUSTOM_ENABLE = "hb_reply_custom_enable"
        const val KEY_REPLY_DELAY_VALUE = "hb_reply_delay_value"
        const val KEY_REPLY_DELAY_UNIT = "hb_reply_delay_unit"
        const val KEY_REPLY_TYPE = "hb_reply_type"
        const val KEY_REPLY_MEDIA_PATHS = "hb_reply_media_paths"
        const val KEY_REPLY_MEDIA_DELAY = "hb_reply_media_delay"
        const val KEY_REPLY_ITEMS = "hb_reply_items_v1"
        const val KEY_REPLY_GROUP_ITEMS = "hb_reply_group_items_v1"
        const val KEY_FAKE_PACKET_RECEIVE_ENABLE = "hb_fake_packet_receive_enable"
    }
}
