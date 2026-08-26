package com.Johnny.wcx.features.items.scripting_js

import android.content.ContentValues
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.net.WeProtoData
import com.Johnny.wcx.features.api.net.abc.IWePacketInterceptor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
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
        scripts.clear()
    }

    // --- onRequest ---
    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnRequest.isEnabled) return null

        try {
            val data = WeProtoData.fromBytes(reqBytes)
            val json = data.toJsonObject()
            val modifiedJson = JsEngine.executeAllOnRequest(uri, cgiId, json)
            data.applyViewJson(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, "onRequest failed", e)
        }

        return null
    }

    // --- onResponse ---
    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (!isEnabled) return null
        if (!OnResponse.isEnabled) return null

        try {
            val data = WeProtoData.fromBytes(respBytes)
            val json = data.toJsonObject()
            val modifiedJson = JsEngine.executeAllOnResponse(uri, cgiId, json)
            data.applyViewJson(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, "onResponse failed", e)
        }
        return null
    }
}
