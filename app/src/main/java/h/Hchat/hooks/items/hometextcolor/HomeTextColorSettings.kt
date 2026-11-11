package h.Hchat.hooks.items.hometextcolor

import h.Hchat.hooks.items.membertitle.MemberTitleStore

object HomeTextColorSettings {
    const val PREFS_NAME = "Hchat_home_text_color_config"

    const val KEY_ENABLE = "home_text_color_enable"
    const val KEY_TITLE_COLOR = "home_text_color_title"
    const val KEY_SUBTITLE_COLOR = "home_text_color_subtitle"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_TITLE_COLOR = ""
    const val DEFAULT_SUBTITLE_COLOR = ""

    fun cleanColorSpec(value: String?): String = MemberTitleStore.cleanColorSpec(value)
}
