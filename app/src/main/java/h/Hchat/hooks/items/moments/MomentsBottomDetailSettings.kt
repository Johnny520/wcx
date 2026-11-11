package h.Hchat.hooks.items.moments

import java.text.SimpleDateFormat
import java.util.Locale

object MomentsBottomDetailSettings {
    const val PREFS_NAME = "Hchat_moments_bottom_detail_config"
    const val KEY_ENABLE = "moments_bottom_detail_enable"
    const val KEY_TEXT_FORMAT = "moments_bottom_detail_text_format"
    const val KEY_TIME_FORMAT = "moments_bottom_detail_time_format"
    const val KEY_HIDE_GROUP_ICON = "moments_bottom_detail_hide_group_icon"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_TEXT_FORMAT = "\${originalText} | \${time}"
    const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"
    const val DEFAULT_HIDE_GROUP_ICON = false

    const val VAR_ORIGINAL_TEXT = "\${originalText}"
    const val VAR_TIME = "\${time}"
    const val VAR_TYPE = "\${type}"
    const val VAR_SNS_ID = "\${snsId}"
    const val VAR_USER_NAME = "\${userName}"

    fun normalizeTextFormat(value: String?): String {
        return value.orEmpty().trim().ifBlank { DEFAULT_TEXT_FORMAT }
    }

    fun normalizeTimeFormat(value: String?): String {
        return value.orEmpty().trim().ifBlank { DEFAULT_TIME_FORMAT }
    }

    fun isValidTimeFormat(value: String?): Boolean {
        return runCatching {
            SimpleDateFormat(normalizeTimeFormat(value), Locale.CHINA)
        }.isSuccess
    }
}
