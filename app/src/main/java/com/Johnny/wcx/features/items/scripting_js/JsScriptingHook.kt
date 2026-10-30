package com.Johnny.wcx.features.items.scripting_js

import android.content.ContentValues
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.net.WeProtoData
import com.Johnny.wcx.features.api.net.abc.IWePacketInterceptor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

@Feature(name = "脚本引擎 (JS)", categories = ["脚本 (JS)"], description = "执行 JavaScript 脚本")
object JsScriptingHook : SwitchFeature(),
    WeDatabaseListenerApi.IInsertListener, IWePacketInterceptor {

    private const val TAG = "JsScriptingHook"

    private val SCRIPTS_DIR = (KnownPaths.moduleData / "scripts_js").createDirsSafe()

    /** 脚本名 -> 源码。 */
    val scripts = ConcurrentHashMap<String, String>()

    /** 脚本名 -> 读取失败原因，供诊断页/界面展示（旧实现是静默 continue，用户看不到）。 */
    private val loadErrors = ConcurrentHashMap<String, String>()

    private var observer: FileObserver? = null
    private var watchThread: HandlerThread? = null
    private var watchHandler: Handler? = null

    /** 目录变更去抖：编辑器保存一次 .js 通常会连发多个事件。 */
    private val reloadTask = Runnable {
        runCatching { reloadScripts(runOnLoad = false) }
            .onFailure { WeLogger.e(TAG, "重载 JS 脚本失败", it) }
    }

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)

        reloadScripts(runOnLoad = true)
        startWatching()
    }

    fun loadErrorSnapshot(): Map<String, String> = HashMap(loadErrors)

    /**
     * 重新扫描脚本目录。
     *
     * @param runOnLoad 首次启用时为 true，对所有脚本执行 onLoad；
     *                  目录变更时为 false，只对「本次新出现」的脚本执行 onLoad。
     *                  JS 脚本没有 onUnload 约定，改文件重跑 onLoad 会让脚本里的 hook 重复注册。
     */
    private fun reloadScripts(runOnLoad: Boolean) {
        val loaded = LinkedHashMap<String, String>()
        val errors = LinkedHashMap<String, String>()

        val dirReady = runCatching {
            if (!SCRIPTS_DIR.exists()) SCRIPTS_DIR.createDirectories()
            SCRIPTS_DIR.exists()
        }.getOrDefault(false)
        if (!dirReady) {
            WeLogger.e(TAG, "脚本目录不可用: ${SCRIPTS_DIR.toAbsolutePath()}")
            return
        }

        val entries = runCatching { SCRIPTS_DIR.listDirectoryEntries("*.js") }
            .getOrElse {
                WeLogger.e(TAG, "扫描脚本目录失败: ${SCRIPTS_DIR.toAbsolutePath()}", it)
                return
            }
            .sortedBy { it.name }

        entries.forEach { path ->
            val name = path.name
            runCatching { path.readText() }
                .onSuccess { text ->
                    if (text.isBlank()) errors[name] = "文件内容为空" else loaded[name] = text
                }
                .onFailure { errors[name] = it.message ?: it.javaClass.simpleName }
        }

        val fresh = loaded.keys - scripts.keys
        scripts.clear()
        scripts.putAll(loaded)
        loadErrors.clear()
        loadErrors.putAll(errors)

        errors.forEach { (name, reason) -> WeLogger.e(TAG, "JS 脚本加载失败: $name → $reason") }
        WeLogger.i(TAG, "JS 脚本已加载：成功 ${loaded.size} 个，失败 ${errors.size} 个")

        when {
            runOnLoad -> JsEngine.executeAllOnLoad(scripts)
            fresh.isNotEmpty() -> JsEngine.executeAllOnLoad(scripts.filterKeys { it in fresh })
        }
    }

    private fun startWatching() {
        if (observer != null) return
        val thread = HandlerThread("WCX-JsScriptWatch").apply { start() }
        watchThread = thread
        val handler = Handler(thread.looper)
        watchHandler = handler
        val watcher = object : FileObserver(
            SCRIPTS_DIR.toAbsolutePath().toString(),
            // 0x000000c0 = MOVED_FROM|MOVED_TO（这两个常量是 API 29 才加的，这里直接用字面量，避免低版本 runtime 与 NewApi 告警）
            CREATE or DELETE or CLOSE_WRITE or 0x000000c0
        ) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null || !path.endsWith(".js", ignoreCase = true)) return
                handler.removeCallbacks(reloadTask)
                handler.postDelayed(reloadTask, 500L)
            }
        }
        runCatching { watcher.startWatching() }
            .onSuccess { observer = watcher }
            .onFailure { WeLogger.e(TAG, "监听脚本目录失败: ${SCRIPTS_DIR.toAbsolutePath()}", it) }
    }

    private fun stopWatching() {
        observer?.let { runCatching { it.stopWatching() } }
        observer = null
        watchHandler?.removeCallbacksAndMessages(null)
        watchHandler = null
        watchThread?.quitSafely()
        watchThread = null
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
        stopWatching()
        WeDatabaseListenerApi.removeListener(this)
        scripts.clear()
        loadErrors.clear()
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
