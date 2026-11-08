package h.Hchat.hooks.items.payment.core

import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import h.Hchat.hooks.items.payment.detect.RedPacketReflector
import h.Hchat.preferences.HchatStorage

/**
 * 红包统计写入层。
 */
class RedPacketStats(
    private val hostContext: Context,
    private val state: RedPacketState,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    fun incrementSuccess(nativeUrl: String?): Boolean {
        if (!state.markProcessed(nativeUrl)) return false
        try {
            val sp = preferences()
            sp.edit()
                .putInt(
                    RedPacketSettings.KEY_STATS_COUNT,
                    sp.getInt(RedPacketSettings.KEY_STATS_COUNT, 0) + 1
                )
                .putInt(
                    RedPacketSettings.KEY_STATS_TODAY,
                    sp.getInt(RedPacketSettings.KEY_STATS_TODAY, 0) + 1
                )
                .apply()
        } catch (_: Throwable) {
        }
        return true
    }

    fun incrementFailure(nativeUrl: String?, fallbackKey: String?): Boolean {
        val key = if (!TextUtils.isEmpty(nativeUrl)) nativeUrl else fallbackKey
        if (!TextUtils.isEmpty(key) && !state.markFailedNotified("stat:$key")) return false
        try {
            val sp = preferences()
            sp.edit()
                .putInt(
                    RedPacketSettings.KEY_STATS_FAILED,
                    sp.getInt(RedPacketSettings.KEY_STATS_FAILED, 0) + 1
                )
                .apply()
        } catch (_: Throwable) {
        }
        return true
    }

    fun recordAmount(amount: String?, nativeUrl: String?) {
        if (TextUtils.isEmpty(nativeUrl) || TextUtils.isEmpty(amount)) return
        val fen = RedPacketReflector.amountToFen(amount)
        if (fen <= 0) return
        try {
            val sp = preferences()
            val amountKey = amountPrefsKey(nativeUrl)
            if (sp.getBoolean(amountKey, false) || state.hasAmountRecorded(nativeUrl)) return
            if (!state.markAmountRecorded(nativeUrl)) return
            sp.edit()
                .putInt(
                    RedPacketSettings.KEY_STATS_AMOUNT,
                    sp.getInt(RedPacketSettings.KEY_STATS_AMOUNT, 0) + fen
                )
                .putBoolean(amountKey, true)
                .apply()
            log("记录本人实收金额: ${amount}元")
        } catch (_: Throwable) {
        }
    }

    fun hasSuccessRecorded(nativeUrl: String?): Boolean {
        if (TextUtils.isEmpty(nativeUrl)) return false
        if (state.hasProcessed(nativeUrl) || state.hasAmountRecorded(nativeUrl)) return true
        return try {
            preferences().getBoolean(amountPrefsKey(nativeUrl), false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun amountPrefsKey(nativeUrl: String?): String {
        val id = RedPacketState.redPacketId(nativeUrl)
        return "hb_amount_" + if (!TextUtils.isEmpty(id)) "sendid_$id" else nativeUrl
    }

    private fun preferences(): SharedPreferences {
        return HchatStorage.preferences(hostContext, RedPacketSettings.PREFS_NAME)
    }

    private fun log(message: String) {
        logger?.log(message)
    }
}
