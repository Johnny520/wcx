package h.Hchat.hooks.items.moments

object MomentsPostNotificationSettings {
    const val PREFS_NAME = "Hchat_moments_post_notification_config"
    const val KEY_ENABLE = "enable"
    const val KEY_ENABLED_AT_SECONDS = "enabled_at_seconds"
    const val KEY_NOTIFIED_IDS = "notified_ids"
    const val KEY_TARGETS = "targets"
    const val KEY_SYSTEM_NOTIFICATION = "system_notification"
    const val KEY_TOAST = "toast"
    const val KEY_TITLE_TEMPLATE = "title_template"
    const val KEY_BODY_TEMPLATE = "body_template"
    const val KEY_TOAST_TEMPLATE = "toast_template"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SYSTEM_NOTIFICATION = true
    const val DEFAULT_TOAST = true
    const val DEFAULT_TITLE_TEMPLATE = ""
    const val DEFAULT_BODY_TEMPLATE = ""
    const val DEFAULT_TOAST_TEMPLATE = ""

    const val FALLBACK_TITLE = "📣 指定好友发布朋友圈"
}
