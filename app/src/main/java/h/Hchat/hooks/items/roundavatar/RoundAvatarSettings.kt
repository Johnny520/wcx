package h.Hchat.hooks.items.roundavatar

import android.content.Context
import h.Hchat.preferences.HchatStorage
import kotlin.math.roundToInt

object RoundAvatarSettings {
    const val PREFS_NAME = "Hchat_round_avatar_config"

    const val KEY_ENABLE = "round_avatar_enable"
    const val KEY_RADIUS_FACTOR = "round_avatar_radius_factor"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_RADIUS_FACTOR = 0.5f
    const val MIN_RADIUS_FACTOR = 0.1f
    const val MAX_RADIUS_FACTOR = 0.5f

    fun enabled(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }

    fun radiusFactor(context: Context): Float {
        return normalizeRadiusFactor(
            preferences(context).getFloat(KEY_RADIUS_FACTOR, DEFAULT_RADIUS_FACTOR)
        )
    }

    fun normalizeRadiusFactor(value: Float): Float {
        return (value.coerceIn(MIN_RADIUS_FACTOR, MAX_RADIUS_FACTOR) * 100f).roundToInt() / 100f
    }

    fun displayPercent(value: Float): Int {
        return (normalizeRadiusFactor(value) * 100f).roundToInt()
    }

    private fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
