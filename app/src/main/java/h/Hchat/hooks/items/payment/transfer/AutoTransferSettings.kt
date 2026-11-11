package h.Hchat.hooks.items.payment.transfer

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import h.Hchat.hooks.items.payment.core.PaymentTemplateTimeFormatter
import h.Hchat.preferences.HchatStorage

class AutoTransferSettings(private val context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun getBoolean(key: String, def: Boolean): Boolean = try {
        prefs?.getBoolean(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getInt(key: String, def: Int): Int = try {
        prefs?.getInt(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getLong(key: String, def: Long): Long = try {
        prefs?.getLong(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getString(key: String, def: String): String = try {
        prefs?.getString(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun contains(key: String): Boolean = try {
        prefs?.contains(key) == true
    } catch (_: Throwable) {
        false
    }

    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, false)

    internal fun hostContext(): Context? = context

    fun getHostPreferences(): SharedPreferences? = prefs

    fun ruleTemplates(): List<TransferRuleTemplate> = TransferRuleConfig.parseTemplates(
        getString(TransferRuleConfig.KEY_TEMPLATES, "")
    )

    fun ruleBindings(): List<TransferRuleBinding> = TransferRuleConfig.parseBindings(
        getString(TransferRuleConfig.KEY_BINDINGS, "")
    )

    fun isUserInList(id: String?, key: String): Boolean {
        if (TextUtils.isEmpty(id)) return false
        return getString(key, "")
            .split("|", ",", "，")
            .map { it.trim() }
            .any { it.isNotEmpty() && it == id }
    }

    companion object {
        const val PREFS_NAME = "Hchat_transfer_config"

        const val KEY_ENABLE = "transfer_auto_enable"
        const val KEY_MODE = "transfer_mode"
        const val KEY_WHITELIST = "transfer_whitelist"
        const val KEY_BLACKLIST = "transfer_blacklist"
        const val KEY_REFUND_REJECTED = "transfer_refund_rejected"
        const val KEY_DELAY_MS = "transfer_delay_ms"
        const val KEY_DELAY_MODE = "transfer_delay_mode"
        const val KEY_DELAY_RANDOM_MIN = "transfer_delay_random_min"
        const val KEY_DELAY_RANDOM_MAX = "transfer_delay_random_max"
        const val KEY_RECEIVE_ACCOUNT = "transfer_receive_account"

        const val KEY_AMOUNT_ENABLE = "transfer_amount_enable"
        const val KEY_AMOUNT_COND = "transfer_amount_cond"
        const val KEY_AMOUNT_VALUE = "transfer_amount_value"
        const val KEY_AMOUNT_ACTION = "transfer_amount_action"

        const val KEY_KEYWORD_MODE = "transfer_keyword_mode"
        const val KEY_KEYWORDS = "transfer_keywords"

        const val KEY_REPLY_ENABLE = "transfer_reply_enable"
        const val KEY_REPLY_TEXT = "transfer_reply_text"
        const val KEY_REPLY_ITEMS = "transfer_reply_items_v1"
        const val KEY_REPLY_GROUP_ITEMS = "transfer_reply_group_items_v1"

        const val KEY_QUIET_ENABLE = "transfer_quiet_enable"
        const val KEY_QUIET_START_SECOND = "transfer_quiet_start_second"
        const val KEY_QUIET_END_SECOND = "transfer_quiet_end_second"
        const val KEY_NOTIFY_SYSTEM_ENABLE = "transfer_notify_system_enable"
        const val KEY_NOTIFY_TOAST_ENABLE = "transfer_notify_toast_enable"
        const val KEY_NOTIFY_SOUND_ENABLE = "transfer_notify_sound_enable"
        const val KEY_NOTIFY_SOUND_MODE = "transfer_notify_sound_mode"
        const val KEY_NOTIFY_VIBRATE_ENABLE = "transfer_notify_vibrate_enable"
        const val KEY_NOTIFY_SOUND_URI = "transfer_notify_sound_uri"
        const val KEY_NOTIFY_TEXT = "transfer_notify_text"
        const val KEY_NOTIFY_TOAST_TEXT = "transfer_notify_toast_text"
        const val KEY_ANNOUNCE_ENABLE = "transfer_announce_enable"
        const val KEY_ANNOUNCE_TEXT = "transfer_announce_text"
        const val KEY_TIME_FORMAT = "transfer_time_format"
        const val DEFAULT_TIME_FORMAT = PaymentTemplateTimeFormatter.DEFAULT_PATTERN

        const val NOTIFY_SOUND_MODE_SYSTEM = 0
        const val NOTIFY_SOUND_MODE_CUSTOM = 1
    }
}
