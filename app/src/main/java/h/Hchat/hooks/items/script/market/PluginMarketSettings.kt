package h.Hchat.hooks.items.script.market

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONObject
import java.util.UUID

object PluginMarketSettings {
    const val DEFAULT_SERVICE_URL = "https://hchat.103.97.179.142.sslip.io"
    private const val LEGACY_SERVICE_URL = "https://hchat.103.189.141.120.sslip.io"
    const val PREFS_NAME = "Hchat_script_plugin_market"

    private const val KEY_SERVICE_URL = "service_url"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_OWNERSHIPS = "ownerships"

    private val lock = Any()

    fun serviceUrl(context: Context): String {
        val configured = preferences(context).getString(KEY_SERVICE_URL, DEFAULT_SERVICE_URL)
            ?.trim()
            ?.trimEnd('/')
            .orEmpty()
        return when {
            configured.isBlank() -> DEFAULT_SERVICE_URL
            configured == LEGACY_SERVICE_URL -> DEFAULT_SERVICE_URL
            else -> configured
        }
    }

    fun saveServiceUrl(context: Context, value: String) {
        preferences(context).edit()
            .putString(KEY_SERVICE_URL, value.trim().trimEnd('/'))
            .apply()
    }

    fun installId(context: Context): String {
        synchronized(lock) {
            val prefs = preferences(context)
            prefs.getString(KEY_INSTALL_ID, null)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
            val generated = UUID.randomUUID().toString().replace("-", "")
            check(prefs.edit().putString(KEY_INSTALL_ID, generated).commit()) {
                "保存插件仓库 installId 失败"
            }
            return generated
        }
    }

    fun ownership(context: Context, localPluginId: String): PluginMarketOwnership? {
        return ownershipFromJson(ownerships(context).optJSONObject(localPluginId))
    }

    fun ownershipForRemote(context: Context, remotePluginId: String): PluginMarketOwnership? {
        val targetId = remotePluginId.trim()
        if (targetId.isBlank()) return null
        synchronized(lock) {
            val root = ownerships(context)
            val keys = root.keys()
            while (keys.hasNext()) {
                val ownership = ownershipFromJson(root.optJSONObject(keys.next())) ?: continue
                if (ownership.remotePluginId == targetId) return ownership
            }
            return null
        }
    }

    fun saveOwnership(context: Context, localPluginId: String, ownership: PluginMarketOwnership) {
        require(localPluginId.isNotBlank()) { "本地插件 ID 不能为空" }
        require(ownership.remotePluginId.isNotBlank()) { "远程插件 ID 不能为空" }
        require(ownership.ownerToken.isNotBlank()) { "插件 ownerToken 不能为空" }
        synchronized(lock) {
            val root = ownerships(context)
            root.put(localPluginId, JSONObject().apply {
                put("remotePluginId", ownership.remotePluginId)
                put("ownerToken", ownership.ownerToken)
            })
            check(preferences(context).edit().putString(KEY_OWNERSHIPS, root.toString()).commit()) {
                "保存插件仓库归属信息失败"
            }
        }
    }

    fun removeOwnership(context: Context, localPluginId: String) {
        synchronized(lock) {
            val root = ownerships(context)
            root.remove(localPluginId)
            check(preferences(context).edit().putString(KEY_OWNERSHIPS, root.toString()).commit()) {
                "删除插件仓库归属信息失败"
            }
        }
    }

    fun removeOwnershipForRemote(context: Context, remotePluginId: String) {
        val targetId = remotePluginId.trim()
        if (targetId.isBlank()) return
        synchronized(lock) {
            val root = ownerships(context)
            val keys = buildList {
                val iterator = root.keys()
                while (iterator.hasNext()) add(iterator.next())
            }
            keys.filter { key -> ownershipFromJson(root.optJSONObject(key))?.remotePluginId == targetId }
                .forEach(root::remove)
            check(preferences(context).edit().putString(KEY_OWNERSHIPS, root.toString()).commit()) {
                "删除插件仓库归属信息失败"
            }
        }
    }

    private fun preferences(context: Context) = HchatStorage.preferences(
        context.applicationContext ?: context,
        PREFS_NAME
    )

    private fun ownerships(context: Context): JSONObject {
        val raw = preferences(context).getString(KEY_OWNERSHIPS, "{}") ?: "{}"
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun ownershipFromJson(item: JSONObject?): PluginMarketOwnership? {
        val remoteId = item?.optString("remotePluginId")?.trim().orEmpty()
        val ownerToken = item?.optString("ownerToken")?.trim().orEmpty()
        if (remoteId.isBlank() || ownerToken.isBlank()) return null
        return PluginMarketOwnership(remoteId, ownerToken)
    }
}
