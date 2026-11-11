package h.Hchat.crash

import android.content.Context
import h.Hchat.preferences.HchatStorage

object CrashReportSettings {
    const val PREFS_NAME = "Hchat_crash_report_config"
    const val KEY_ENABLE = "crash_report_enable"
    const val DEFAULT_ENABLE = false

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        return HchatStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }
}
