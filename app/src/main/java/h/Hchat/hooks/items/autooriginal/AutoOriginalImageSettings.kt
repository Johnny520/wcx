package h.Hchat.hooks.items.autooriginal

import android.content.Context
import h.Hchat.preferences.HchatStorage

object AutoOriginalImageSettings {
    const val PREFS_NAME = "Hchat_auto_original_image_config"
    const val KEY_ENABLE = "auto_original_image_enable"
    const val DEFAULT_ENABLE = false

    fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
