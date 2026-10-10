package h.Hchat.hooks.items.script

object ScriptPluginSettings {
    const val PREFS_NAME = "Hchat_script_plugin_config"

    const val KEY_ENABLE = "script_plugin_enable"
    const val KEY_RUN_ON_START = "script_plugin_run_on_start"
    internal const val KEY_PLUGIN_DISPLAY_STATE = "script_plugin_display_state_v1"
    private const val KEY_PLUGIN_ENABLE_PREFIX = "script_plugin_item_"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_RUN_ON_START = false
    const val DEFAULT_PLUGIN_ENABLE = false

    fun pluginEnableKey(pluginId: String): String = KEY_PLUGIN_ENABLE_PREFIX + pluginId
}
