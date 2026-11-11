package h.Hchat.hooks.items.conversationtabs

import android.content.Context
import h.Hchat.hooks.items.conversationgroup.ConversationGroupStore
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject

object ConversationTabsStore {
    const val PREFS_NAME = "Hchat_conversation_tabs"
    fun accountKey(): String = ConversationGroupStore.accountKey()

    fun load(context: Context): ConversationTabsConfig {
        val account = accountKey()
        if (account.isBlank()) return ConversationTabsConfig()
        return runCatching {
            val raw = HchatStorage.preferences(context, PREFS_NAME).getString(account, null)
                ?: return ConversationTabsConfig()
            val json = JSONObject(raw)
            val array = json.optJSONArray("tabs") ?: JSONArray()
            val tabs = (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val kind = ConversationTabKind.values().firstOrNull { it.name == item.optString("kind") }
                    ?: return@mapNotNull null
                val ids = item.optJSONArray("conversations") ?: JSONArray()
                ConversationTab(
                    id = item.optString("id"), name = item.optString("name"), kind = kind,
                    icon = item.optString("icon", kind.defaultIcon), iconPath = item.optString("iconPath"),
                    display = ConversationTabDisplay.values().firstOrNull { it.name == item.optString("display") }
                        ?: ConversationTabDisplay.BOTH,
                    conversationIds = (0 until ids.length()).map { ids.optString(it) }.toSet()
                )
            }
            ConversationTabsConfig.normalize(ConversationTabsConfig(json.optBoolean("enabled"), tabs))
        }.getOrElse { HLog.e("[Hchat:ConversationTabs] 读取标签配置失败", it); ConversationTabsConfig() }
    }

    @Synchronized
    fun save(context: Context, config: ConversationTabsConfig): Boolean {
        val account = accountKey()
        if (account.isBlank()) return false
        return runCatching {
            val normalized = ConversationTabsConfig.normalize(config)
            val array = JSONArray()
            normalized.tabs.forEach { tab ->
                array.put(JSONObject().put("id", tab.id).put("name", tab.name).put("kind", tab.kind.name)
                    .put("icon", tab.icon).put("iconPath", tab.iconPath).put("display", tab.display.name)
                    .put("conversations", JSONArray(tab.conversationIds.toList())))
            }
            HchatStorage.preferences(context, PREFS_NAME).edit().putString(account,
                JSONObject().put("version", 1).put("enabled", normalized.enabled).put("tabs", array).toString()).apply()
            true
        }.getOrElse { HLog.e("[Hchat:ConversationTabs] 保存标签配置失败", it); false }
    }
}
