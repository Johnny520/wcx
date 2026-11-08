package h.Hchat.hooks.items.floatingshortcut

import android.content.Context
import android.content.SharedPreferences
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject

data class FloatingShortcutItem(
    val id: String,
    val title: String,
    val actionType: String,
    val target: String,
    val iconPath: String = "",
    val darkIconPath: String = "",
    val enabled: Boolean = true
)

object FloatingShortcutSettings {
    const val PREFS_NAME = "floating_shortcut_menu"
    const val KEY_ENABLE = "enable"
    const val KEY_SCOPE = "scope"
    const val KEY_DISPLAY_MODE = "display_mode"
    const val KEY_EXPAND_DIRECTION = "expand_direction"
    const val KEY_BUBBLE_ICON = "bubble_icon"
    const val KEY_BUBBLE_DARK_ICON = "bubble_dark_icon"
    const val KEY_BUBBLE_SIZE = "bubble_size"
    const val KEY_BUBBLE_COLOR = "bubble_color"
    const val KEY_ACTION_SIZE = "action_size"
    const val KEY_ACTION_COLOR = "action_color"
    const val KEY_LABEL_TEXT_SIZE = "label_text_size"
    const val KEY_LABEL_COLOR = "label_color"
    const val KEY_ITEMS = "items"
    const val KEY_POSITION_X = "position_x"
    const val KEY_POSITION_Y = "position_y"
    private const val KEY_AGENT_ITEM_MIGRATED = "agent_item_migrated_v1"
    private const val KEY_LEGACY_AGENT_SETTINGS_MIGRATED = "legacy_agent_settings_migrated_v1"
    private const val LEGACY_AGENT_PREFS_NAME = "agent_floating_window"
    private const val LEGACY_KEY_ENABLE = "enable"
    private const val LEGACY_KEY_POSITION_X = "position_x"
    private const val LEGACY_KEY_POSITION_Y = "position_y"

    const val SCOPE_HOME = "home"
    const val SCOPE_ALL = "all"
    const val DISPLAY_ICON = "icon"
    const val DISPLAY_TEXT = "text"
    const val DISPLAY_BOTH = "both"
    const val EXPAND_UP = "up"
    const val EXPAND_DOWN = "down"
    const val EXPAND_LEFT = "left"
    const val EXPAND_RIGHT = "right"
    const val ACTION_ACTIVITY = "activity"
    const val ACTION_MODULE_SETTINGS = "module_settings"
    const val ACTION_PLUGIN_AGENT = "plugin_agent"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SCOPE = SCOPE_HOME
    const val DEFAULT_DISPLAY_MODE = DISPLAY_ICON
    const val DEFAULT_EXPAND_DIRECTION = EXPAND_UP
    const val DEFAULT_POSITION_X = 1f
    const val DEFAULT_POSITION_Y = 0.46f
    const val MIN_BUTTON_SIZE = 36
    const val MAX_BUTTON_SIZE = 64
    const val DEFAULT_BUBBLE_SIZE = 44
    const val DEFAULT_BUBBLE_COLOR = "#FFFFFF"
    const val DEFAULT_ACTION_SIZE = 44
    const val DEFAULT_ACTION_COLOR = ""
    const val MIN_LABEL_TEXT_SIZE = 10
    const val MAX_LABEL_TEXT_SIZE = 24
    const val DEFAULT_LABEL_TEXT_SIZE = 14
    const val DEFAULT_LABEL_COLOR = ""

    fun loadItems(context: Context): List<FloatingShortcutItem> {
        val preferences = HchatStorage.preferences(context, PREFS_NAME)
        migrateLegacyAgentSettings(context, preferences)
        val value = preferences.getString(KEY_ITEMS, null)
        if (value.isNullOrBlank()) {
            preferences.edit().putBoolean(KEY_AGENT_ITEM_MIGRATED, true).apply()
            return defaultItems()
        }
        val items = runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id").trim()
                    val title = obj.optString("title").trim()
                    val type = obj.optString("actionType", ACTION_ACTIVITY).trim()
                    val target = if (type == ACTION_ACTIVITY) obj.optString("target").trim() else ""
                    if (id.isEmpty() || title.isEmpty()) continue
                    if (type != ACTION_ACTIVITY &&
                        type != ACTION_MODULE_SETTINGS &&
                        type != ACTION_PLUGIN_AGENT
                    ) continue
                    if (type == ACTION_ACTIVITY && target.isEmpty()) continue
                    add(
                        FloatingShortcutItem(
                            id = id,
                            title = title,
                            actionType = type,
                            target = target,
                            iconPath = obj.optString("iconPath").trim(),
                            darkIconPath = obj.optString("darkIconPath").trim(),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }.getOrDefault(defaultItems())
        if (preferences.getBoolean(KEY_AGENT_ITEM_MIGRATED, false)) return items

        val migrated = if (items.any { it.actionType == ACTION_PLUGIN_AGENT }) {
            items
        } else {
            listOf(defaultAgentItem()) + items
        }
        preferences.edit()
            .putString(KEY_ITEMS, encodeItems(migrated).toString())
            .putBoolean(KEY_AGENT_ITEM_MIGRATED, true)
            .apply()
        return migrated
    }

    private fun migrateLegacyAgentSettings(context: Context, preferences: SharedPreferences) {
        if (preferences.getBoolean(KEY_LEGACY_AGENT_SETTINGS_MIGRATED, false)) return
        val legacy = HchatStorage.preferences(context, LEGACY_AGENT_PREFS_NAME)
        val editor = preferences.edit()
        val legacyEnabled = runCatching {
            legacy.getBoolean(LEGACY_KEY_ENABLE, false)
        }.getOrDefault(false)
        if (legacyEnabled && !preferences.contains(KEY_ENABLE)) {
            editor.putBoolean(KEY_ENABLE, true)
            if (!preferences.contains(KEY_SCOPE)) editor.putString(KEY_SCOPE, SCOPE_ALL)
        }
        if (legacy.contains(LEGACY_KEY_POSITION_X) && !preferences.contains(KEY_POSITION_X)) {
            editor.putFloat(
                KEY_POSITION_X,
                runCatching { legacy.getFloat(LEGACY_KEY_POSITION_X, DEFAULT_POSITION_X) }
                    .getOrDefault(DEFAULT_POSITION_X)
                    .coerceIn(0f, 1f)
            )
        }
        if (legacy.contains(LEGACY_KEY_POSITION_Y) && !preferences.contains(KEY_POSITION_Y)) {
            editor.putFloat(
                KEY_POSITION_Y,
                runCatching { legacy.getFloat(LEGACY_KEY_POSITION_Y, DEFAULT_POSITION_Y) }
                    .getOrDefault(DEFAULT_POSITION_Y)
                    .coerceIn(0f, 1f)
            )
        }
        editor.putBoolean(KEY_LEGACY_AGENT_SETTINGS_MIGRATED, true).apply()
    }

    fun saveItems(context: Context, items: List<FloatingShortcutItem>) {
        HchatStorage.preferences(context, PREFS_NAME).edit()
            .putString(KEY_ITEMS, encodeItems(items).toString())
            .putBoolean(KEY_AGENT_ITEM_MIGRATED, true)
            .apply()
    }

    private fun encodeItems(items: List<FloatingShortcutItem>): JSONArray {
        val array = JSONArray()
        items.distinctBy { it.id }.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title.trim())
                put("actionType", item.actionType)
                put("target", if (item.actionType == ACTION_ACTIVITY) item.target.trim() else "")
                put("iconPath", item.iconPath.trim())
                put("darkIconPath", item.darkIconPath.trim())
                put("enabled", item.enabled)
            })
        }
        return array
    }

    fun defaultItems(): List<FloatingShortcutItem> = listOf(
        defaultAgentItem(),
        FloatingShortcutItem("module_settings", "设置", ACTION_MODULE_SETTINGS, ""),
        FloatingShortcutItem("scan", "扫一扫", ACTION_ACTIVITY, "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        FloatingShortcutItem("moments", "朋友圈", ACTION_ACTIVITY, "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"),
        FloatingShortcutItem("finder", "视频号", ACTION_ACTIVITY, "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"),
        FloatingShortcutItem("favorite", "收藏", ACTION_ACTIVITY, "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"),
        FloatingShortcutItem("wallet", "钱包", ACTION_ACTIVITY, "com.tencent.mm.plugin.mall.ui.MallIndexUIv2")
    )

    private fun defaultAgentItem(): FloatingShortcutItem =
        FloatingShortcutItem("plugin_agent", "插件 Agent", ACTION_PLUGIN_AGENT, "")
}
