package h.Hchat.hooks.items.moments

object MomentsContactFilterSettings {
    const val PREFS_NAME = "Hchat_moments_contact_filter_config"
    const val KEY_ENABLE = "moments_contact_filter_enable"
    const val KEY_MODE = "moments_contact_filter_mode"
    const val KEY_TARGETS = "moments_contact_filter_targets"

    const val MODE_EXCLUDE = 0
    const val MODE_INCLUDE_ONLY = 1

    const val DEFAULT_ENABLE = false
    const val DEFAULT_MODE = MODE_EXCLUDE
    const val DEFAULT_TARGETS = ""
}
