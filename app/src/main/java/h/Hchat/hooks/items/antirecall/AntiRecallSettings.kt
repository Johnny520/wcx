package h.Hchat.hooks.items.antirecall

object AntiRecallSettings {
    const val PREFS_NAME = "Hchat_anti_recall_config"
    const val KEY_ENABLE = "anti_recall_enable"
    const val KEY_KEEP_SELF_RECALL = "anti_recall_keep_self"
    const val KEY_SHOW_NOTICE = "anti_recall_show_notice"
    const val KEY_NOTICE_TEXT = "anti_recall_notice_text"
    const val KEY_NOTICE_TIME_FORMAT = "anti_recall_notice_time_format"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_KEEP_SELF_RECALL = false
    const val DEFAULT_SHOW_NOTICE = true
    const val LEGACY_NOTICE_TEXT = "已阻止一条撤回消息"
    const val DEFAULT_NOTICE_TEXT = "{name}撤回了上一条消息 {content}"
    const val DEFAULT_NOTICE_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"
    const val SELF_NOTICE_TEXT = "你撤回了上一条消息"
    const val VAR_RECALLER_NAME = "{name}"
    const val VAR_RECALL_TEXT = "{content}"
    const val VAR_SEND_TIME = "{sendTime}"
    const val VAR_RECALL_TIME = "{recallTime}"
}
