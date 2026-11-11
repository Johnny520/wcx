package h.Hchat.hooks.items.custombottombar

object FloatingBottomBarSettings {
    const val FEATURE_ID = "floating_bottom_bar"
    const val PREFS_NAME = "Hchat_custom_bottom_bar_config"

    const val KEY_ENABLE = "custom_bottom_bar_enable"
    const val KEY_STYLE = "custom_bottom_bar_style"
    const val KEY_GLASS = "custom_bottom_bar_glass"
    const val KEY_BLUR_RADIUS = "custom_bottom_bar_blur_radius"
    const val KEY_HIDE_LABELS = "custom_bottom_bar_hide_labels"
    const val KEY_SHOW_BADGES = "custom_bottom_bar_show_discovery_badge"
    const val KEY_VIBRATION_ENABLED = "custom_bottom_bar_vibration_enabled"
    const val KEY_VIBRATION_STRENGTH = "custom_bottom_bar_vibration_strength"
    const val LEGACY_STYLE_FLOATING = "floating"

    const val MIN_BLUR_RADIUS = 0
    const val MAX_BLUR_RADIUS = 40
    const val MIN_VIBRATION_STRENGTH = 1
    const val MAX_VIBRATION_STRENGTH = 100

    const val DEFAULT_ENABLE = false
    const val DEFAULT_GLASS = true
    const val DEFAULT_BLUR_RADIUS = 8
    const val DEFAULT_HIDE_LABELS = false
    const val DEFAULT_SHOW_BADGES = true
    const val DEFAULT_VIBRATION_ENABLED = true
    const val DEFAULT_VIBRATION_STRENGTH = 50

    fun normalizeBlurRadius(value: Int): Int = value.coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS)

    fun normalizeVibrationStrength(value: Int): Int =
        value.coerceIn(MIN_VIBRATION_STRENGTH, MAX_VIBRATION_STRENGTH)
}

object CustomBottomBarSettings {
    const val PREFS_NAME = "Hchat_native_bottom_bar_config"

    const val KEY_ENABLE = "enable"
    const val KEY_MODIFY_ICONS = "modify_icons"
    const val KEY_MODIFY_TITLES = "modify_titles"
    const val KEY_HIDE_TITLES = "hide_titles"
    const val KEY_HIDE_BAR = "hide_bar"
    const val KEY_TITLE_WECHAT = "title_wechat"
    const val KEY_TITLE_CONTACTS = "title_contacts"
    const val KEY_TITLE_DISCOVER = "title_discover"
    const val KEY_TITLE_ME = "title_me"
    const val KEY_ICON_WECHAT = "icon_wechat"
    const val KEY_ICON_CONTACTS = "icon_contacts"
    const val KEY_ICON_DISCOVER = "icon_discover"
    const val KEY_ICON_ME = "icon_me"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_MODIFY_ICONS = true
    const val DEFAULT_MODIFY_TITLES = true
    const val DEFAULT_HIDE_TITLES = false
    const val DEFAULT_HIDE_BAR = false

    val DEFAULT_TITLES = listOf("微信", "通讯", "发现", "我的")
    val TITLE_KEYS = listOf(KEY_TITLE_WECHAT, KEY_TITLE_CONTACTS, KEY_TITLE_DISCOVER, KEY_TITLE_ME)
    val ICON_KEYS = listOf(KEY_ICON_WECHAT, KEY_ICON_CONTACTS, KEY_ICON_DISCOVER, KEY_ICON_ME)
    val TAB_KEYS = listOf(
        CustomBottomBarIconStore.TAB_WECHAT,
        CustomBottomBarIconStore.TAB_CONTACTS,
        CustomBottomBarIconStore.TAB_DISCOVER,
        CustomBottomBarIconStore.TAB_ME
    )

    fun normalizeTitle(index: Int, value: String?): String {
        return value?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_TITLES[index]
    }
}
