package h.Hchat.hooks.items.fakelocation

import android.content.Context
import h.Hchat.preferences.HchatStorage

object FakeLocationSettings {
    const val PREFS_NAME = "Hchat_fake_location_config"
    const val KEY_ENABLE = "fake_location_enable"
    const val KEY_LATITUDE = "fake_location_latitude"
    const val KEY_LONGITUDE = "fake_location_longitude"
    const val DEFAULT_ENABLE = false
    const val DEFAULT_LATITUDE = "31.224361"
    const val DEFAULT_LONGITUDE = "121.469170"

    fun enabled(context: Context): Boolean {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }

    fun latitude(context: Context): Double {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getString(KEY_LATITUDE, DEFAULT_LATITUDE)
            ?.toDoubleOrNull()
            ?.takeIf { it in -90.0..90.0 }
            ?: DEFAULT_LATITUDE.toDouble()
    }

    fun longitude(context: Context): Double {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getString(KEY_LONGITUDE, DEFAULT_LONGITUDE)
            ?.toDoubleOrNull()
            ?.takeIf { it in -180.0..180.0 }
            ?: DEFAULT_LONGITUDE.toDouble()
    }
}
