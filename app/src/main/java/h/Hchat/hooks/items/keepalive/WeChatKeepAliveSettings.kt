package h.Hchat.hooks.items.keepalive

object WeChatKeepAliveSettings {
    const val PREFS_NAME = "Hchat_wechat_keep_alive_config"
    const val KEY_ENABLE = "wechat_keep_alive_enable"
    const val KEY_FOREGROUND_SERVICE = "wechat_keep_alive_foreground_service"
    const val KEY_WAKE_LOCK = "wechat_keep_alive_wake_lock"
    const val KEY_ROOT_DOZE_WHITELIST = "wechat_keep_alive_root_doze_whitelist"
    const val KEY_WATCHDOG = "wechat_keep_alive_watchdog"
    const val KEY_ROOT_APP_OPS = "wechat_keep_alive_root_app_ops"
    const val KEY_NETWORK_HEARTBEAT = "wechat_keep_alive_network_heartbeat"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_FOREGROUND_SERVICE = true
    const val DEFAULT_WAKE_LOCK = true
    const val DEFAULT_ROOT_DOZE_WHITELIST = false
    const val DEFAULT_WATCHDOG = false
    const val DEFAULT_ROOT_APP_OPS = false
    const val DEFAULT_NETWORK_HEARTBEAT = false
}
