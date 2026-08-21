package com.Johnny.wcx.features.items.scripting_js

import android.content.ContentValues
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.net.WePacketManager
import com.Johnny.wcx.features.api.net.WeProtoData
import com.Johnny.wcx.features.api.net.abc.IWePacketInterceptor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Feature(name = "脚本引擎 (JS)", categories = ["脚本 (JS)"], description = "执行 JavaScript 脚本")
object JsScriptingHook : SwitchFeature(),
    WeDatabaseListenerApi.IInsertListener, IWePacketInterceptor {

    private const val TAG = "JsScriptingHook"

    private val SCRIPTS_DIR = (KnownPaths.moduleData / "scripts_js").createDirsSafe()

    val scripts = ConcurrentHashMap<String, String>()

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        // without this the onRequest/onResponse overrides below are never reached
        WePacketManager.addInterceptor(this)

        WeLogger.d(TAG, "loading js scripts...")
        for (path in SCRIPTS_DIR.listDirectoryEntries("*.js")) {
            val name = path.name
            val content = runCatching { path.readText() }.getOrElse { continue }
            WeLogger.d(TAG, "loaded script, name='${name}', length=${content.length}")
            scripts[name] = content
        }

        JsEngine.executeAllOnLoad(scripts)
    }

    // --- onMessage ---
    override fun onInsert(table: String, values: ContentValues) {
        if (!isEnabled) return
        if (!OnMessage.isEnabled) return

        if (table != "message") return

        val isSend = values.getAsInteger("isSend") ?: return

        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return
        val type = values.getAsInteger("type") ?: 0

        JsEngine.executeAllOnMessage(scripts, talker, content, type, isSend)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        WePacketManager.removeInterceptor(this)
        scripts.clear()
        // scripts are re-read from disk on the next onEnable, so their cached scopes must go too
        JsEngine.invalidateCache()
    }

    // --- onRequest ---
    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnRequest.isEnabled) return null
        // runs on WeChat's network thread for *every* packet: get out before parsing anything
        if (!JsEngine.anyScriptDefines("onRequest")) return null

        return tamperPacket("onRequest", reqBytes) { json ->
            JsEngine.executeAllOnRequest(uri, cgiId, json)
        }
    }

    // --- onResponse ---
    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnResponse.isEnabled) return null
        if (!JsEngine.anyScriptDefines("onResponse")) return null

        return tamperPacket("onResponse", respBytes) { json ->
            JsEngine.executeAllOnResponse(uri, cgiId, json)
        }
    }

    /**
     * Runs [execute] over the decoded body of [bytes] and re-serializes it *only* if a script
     * really changed something.
     *
     * `null` means "not tampered" to [WePacketManager], which then forwards the untouched original
     * packet. Returning re-serialized bytes for a packet nobody wanted to modify would put every
     * single CGI call through the proto parse/serialize round-trip, so any imperfection there
     * would corrupt unrelated traffic.
     */
    private fun tamperPacket(
        entry: String,
        bytes: ByteArray,
        execute: (JSONObject) -> JSONObject?,
    ): ByteArray? {
        try {
            val data = WeProtoData.fromBytes(bytes)
            val modifiedJson = execute(data.toJsonObject()) ?: return null
            if (data.applyViewJson(modifiedJson, true) == 0) return null

            // toPacketBytes() throws when serialization fails; dropping the modification beats
            // handing WeChat a truncated packet
            val tampered = data.toPacketBytes()
            return if (tampered.contentEquals(bytes)) null else tampered
        } catch (e: Exception) {
            WeLogger.e(TAG, "$entry failed", e)
            return null
        }
    }
}
