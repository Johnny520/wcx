package h.Hchat.hooks.items.payment.fakebalance

import android.content.SharedPreferences
import java.math.BigDecimal
import java.math.RoundingMode

object FakeWalletBalanceSettings {
    const val PREFS_NAME = "Hchat_fake_wallet_balance_config"
    const val KEY_ENABLE = "fake_wallet_balance_enable"
    const val KEY_BALANCE_ENABLE = "fake_wallet_balance_enable_balance"
    const val KEY_LQT_ENABLE = "fake_wallet_balance_enable_lqt"
    const val KEY_BUSINESS_ENABLE = "fake_wallet_balance_enable_business"
    const val KEY_BALANCE_AMOUNT = "fake_wallet_balance_amount"
    const val KEY_LQT_AMOUNT = "fake_wallet_lqt_amount"
    const val KEY_BUSINESS_AMOUNT = "fake_wallet_business_amount"
    const val KEY_BALANCE_MODE = "fake_wallet_balance_mode_balance"
    const val KEY_LQT_MODE = "fake_wallet_balance_mode_lqt"
    const val KEY_BUSINESS_MODE = "fake_wallet_balance_mode_business"
    const val MODE_FIXED = "fixed"
    const val MODE_INCREASE = "increase"
    const val MODE_DECREASE = "decrease"
    const val DEFAULT_ENABLE = false
    const val DEFAULT_AMOUNT = "0.00"
    const val DEFAULT_MODE = MODE_FIXED

    private val amountRegex = Regex("""[+-]?\d+(?:\.\d+)?""")

    fun normalizeAmount(value: String?): String {
        return amountDecimal(value).abs().setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    fun amountDecimal(value: String?): BigDecimal {
        val source = value.orEmpty().replace(",", "")
        val raw = amountRegex.find(source)?.value ?: return BigDecimal.ZERO.setScale(2)
        return runCatching {
            BigDecimal(raw).setScale(2, RoundingMode.HALF_UP)
        }.getOrDefault(BigDecimal.ZERO.setScale(2))
    }

    private fun compactAmount(value: String?): String {
        return value.orEmpty()
            .replace(",", "")
            .replace("¥", "")
            .replace("￥", "")
            .trim()
    }

    fun amountMode(
        prefs: SharedPreferences,
        modeKey: String,
        configured: String?,
        fallback: String = DEFAULT_MODE
    ): String {
        if (prefs.contains(modeKey)) {
            return normalizeMode(prefs.getString(modeKey, fallback), fallback)
        }
        val legacy = compactAmount(configured)
        return when {
            legacy.startsWith('+') -> MODE_INCREASE
            legacy.startsWith('-') -> MODE_DECREASE
            else -> normalizeMode(fallback, DEFAULT_MODE)
        }
    }

    fun resolveAmount(configured: String?, actual: String, mode: String): String {
        val configuredAmount = amountDecimal(configured).abs()
        val actualAmount = amountDecimal(actual)
        val resolved = when (normalizeMode(mode, DEFAULT_MODE)) {
            MODE_INCREASE -> actualAmount.add(configuredAmount)
            MODE_DECREASE -> actualAmount.subtract(configuredAmount)
            else -> configuredAmount
        }.max(BigDecimal.ZERO)
        return resolved.setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    private fun normalizeMode(value: String?, fallback: String): String {
        return value?.takeIf { it == MODE_FIXED || it == MODE_INCREASE || it == MODE_DECREASE }
            ?: fallback
    }

    fun isAccountEnabled(prefs: SharedPreferences, key: String): Boolean {
        return if (prefs.contains(key)) {
            prefs.getBoolean(key, DEFAULT_ENABLE)
        } else {
            prefs.getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
        }
    }
}
