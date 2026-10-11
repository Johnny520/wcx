package h.Hchat.hooks.items.script

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.widget.Toast
import bsh.Interpreter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import dalvik.system.DexClassLoader
import com.Johnny.wcx.BuildConfig
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.runtime.WeChatVersionApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.protobuf.ProtobufPacketRuntime
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadSupport
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import me.yun.silk.AacCodec
import me.yun.silk.SilkCodec
import me.yun.silk.utils.Conversion
import org.luckypray.dexkit.DexKitBridge
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

object ScriptPluginRuntime {
    private const val TAG = "[Hchat:Script]"
    private const val MAIN_FILE = "main.java"
    private const val INFO_FILE = "info.prop"
    private const val README_FILE = "README.md"
    private const val SNAPSHOT_SUFFIX = ".bshs"
    private const val RELOAD_DEBOUNCE_MS = 500L
    private const val INITIAL_LOAD_READY_TIMEOUT_MS = 30_000L
    private const val INITIAL_LOAD_POLL_MIN_MS = 250L
    private const val INITIAL_LOAD_POLL_MAX_MS = 2_000L
    private const val SEND_BUTTON_SLOW_CALLBACK_MS = 50L
    private const val SEND_BUTTON_DIAGNOSTIC_LOG_COOLDOWN_MS = 10_000L
    private const val PROTOBUF_CALLBACK_QUEUE_CAPACITY = 128
    private const val PROTOBUF_DROP_LOG_COOLDOWN_MS = 10_000L
    private const val IMAGE_DOWNLOAD_CALLBACK_QUEUE_CAPACITY = 32
    private const val IMAGE_DOWNLOAD_DROP_LOG_COOLDOWN_MS = 10_000L
    private const val IMAGE_DOWNLOAD_TASK_DEDUP_WINDOW_MS = 10 * 60_000L
    private const val IMAGE_DOWNLOAD_TASK_RUNNING = Long.MIN_VALUE
    private const val VIDEO_DOWNLOAD_CALLBACK_QUEUE_CAPACITY = 32
    private const val VIDEO_DOWNLOAD_DROP_LOG_COOLDOWN_MS = 10_000L
    private const val VIDEO_DOWNLOAD_TASK_DEDUP_WINDOW_MS = 10 * 60_000L
    private const val FINDER_MEDIA_DOWNLOAD_CALLBACK_QUEUE_CAPACITY = 32
    private const val FINDER_MEDIA_DOWNLOAD_DROP_LOG_COOLDOWN_MS = 10_000L
    private const val FINDER_MEDIA_DOWNLOAD_TASK_DEDUP_WINDOW_MS = 10 * 60_000L
    private const val FINDER_MEDIA_DOWNLOAD_TASK_RUNNING = Long.MIN_VALUE
    private const val SNS_PREPARE_QUEUE_CAPACITY = 32
    private const val PROCESS_MAIN = "main"
    private const val PROCESS_APPBRAND = "appbrand"
    private val AGENT_TRANSACTION_DIRECTORY = Regex(
        "^\\..+\\.agent-(?:new|old|copy)-[A-Za-z0-9]+$"
    )
    private val nativeLoadLock = Any()
    private val loadedNativeLibraries = ArrayList<LoadedNativeLibrary>()
    private val nativeLoadSequence = AtomicLong(0L)
    private val initialLoadStarted = AtomicBoolean(false)
    private val invalidProcessWarnings = ConcurrentHashMap.newKeySet<String>()
    private val SCRIPT_FUNCTION_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    @Volatile
    private var bridge: ScriptPluginBridge? = null
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var runtimeProcess = PROCESS_MAIN
    @Volatile
    private var runtimeProcessName = ""
    @Volatile
    private var scriptRootObserver: FileObserver? = null
    private val loadedPlugins = ConcurrentHashMap<String, LoadedPlugin>()
    private val pluginDirObservers = ConcurrentHashMap<String, FileObserver>()
    private val pluginCatalogListeners = CopyOnWriteArrayList<() -> Unit>()
    private val reloadTasks = ConcurrentHashMap<String, Runnable>()
    private val interpreterLocks = WeakHashMap<Interpreter, ReentrantLock>()
    private val sendButtonDiagnosticLogAt = ConcurrentHashMap<String, Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val protobufListenerLock = Any()
    private val protobufListenerRegistered = AtomicBoolean(false)
    private val protobufDroppedPacketCount = AtomicLong(0L)
    private val protobufDropLogAt = AtomicLong(0L)
    private val imageDownloadDroppedCount = AtomicLong(0L)
    private val imageDownloadDropLogAt = AtomicLong(0L)
    private val imageDownloadTasks = ConcurrentHashMap<String, Long>()
    private val videoDownloadDroppedCount = AtomicLong(0L)
    private val videoDownloadDropLogAt = AtomicLong(0L)
    private val videoDownloadTasks = ConcurrentHashMap<String, Long>()
    private val finderMediaDownloadDroppedCount = AtomicLong(0L)
    private val finderMediaDownloadDropLogAt = AtomicLong(0L)
    private val finderMediaDownloadTasks = ConcurrentHashMap<String, Long>()
    private val snsPrepareSequence = AtomicLong(0L)
    private val snsPrepareCancellations = ConcurrentHashMap<String, AtomicBoolean>()
    private val snsPrepareExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(SNS_PREPARE_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "Hchat-Script-SnsPrepare").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val protobufCallbackExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(PROTOBUF_CALLBACK_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "Hchat-Script-Protobuf").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val imageDownloadCallbackExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(IMAGE_DOWNLOAD_CALLBACK_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "Hchat-Script-ImageDownload").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val videoDownloadCallbackExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(VIDEO_DOWNLOAD_CALLBACK_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "Hchat-Script-VideoDownload").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val finderMediaDownloadCallbackExecutor = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(FINDER_MEDIA_DOWNLOAD_CALLBACK_QUEUE_CAPACITY),
        { runnable ->
            Thread(runnable, "Hchat-Script-FinderDownload").apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val protobufPacketListener = ProtobufPacketRuntime.Listener { packet ->
        dispatchOnProtobufPacket(packet)
    }

    data class ScriptPlugin(
        val id: String,
        val name: String,
        val dir: File,
        val mainFile: File,
        val author: String,
        val version: String,
        val updateTime: String,
        val displayName: String? = null,
        val processScope: Set<String> = setOf(PROCESS_MAIN)
    )

    data class PluginRuntimeSnapshot(
        val initialized: Boolean,
        val loaded: Boolean,
        val processKind: String,
        val processName: String
    )

    /** 只查看当前进程，不触发初始化、加载或插件回调。 */
    fun pluginRuntimeSnapshot(pluginId: String): PluginRuntimeSnapshot = PluginRuntimeSnapshot(
        initialized = bridge != null,
        loaded = loadedPlugins.containsKey(pluginId),
        processKind = runtimeProcess,
        processName = runtimeProcessName
    )

    private data class LoadedPlugin(
        val plugin: ScriptPlugin,
        val interpreter: Interpreter,
        val waBridge: ScriptWaBridge,
        @Volatile var hasSendButtonCallback: Boolean,
        @Volatile var hasLongSendButtonCallback: Boolean,
        @Volatile var hasHandleMsgCallback: Boolean,
        @Volatile var hasOpenSettingsCallback: Boolean,
        @Volatile var hasMemberChangeCallback: Boolean,
        @Volatile var hasNewFriendCallback: Boolean,
        @Volatile var hasProtobufPacketCallback: Boolean,
        @Volatile var hasImageDownloadCallback: Boolean,
        @Volatile var hasVideoDownloadCallback: Boolean,
        @Volatile var hasFinderMediaDownloadCallback: Boolean
    )

    data class SendButtonEventResult(
        val intercepted: Boolean,
        val handledBy: List<String>
    )

    class SendResult internal constructor(
        private val success: Boolean,
        private val message: String
    ) {
        fun isSuccess(): Boolean = success

        fun getMessage(): String = message

        override fun toString(): String {
            return "SendResult(success=$success, message=$message)"
        }
    }

    class PluginCatalogSubscription internal constructor(
        private val listener: () -> Unit
    ) {
        fun unsubscribe() {
            pluginCatalogListeners.remove(listener)
        }
    }

    fun install(context: FeatureContext) {
        val hostAppContext = context.hostContext().applicationContext ?: context.hostContext()
        appContext = hostAppContext
        runtimeProcess = PROCESS_MAIN
        runtimeProcessName = hostAppContext.packageName
        val currentBridge = ScriptPluginBridge.from(context)
        bridge = currentBridge
        ensureDirs(hostAppContext)
        ScriptPluginManager.recoverInterruptedOperations(
            hostAppContext,
            cleanupOrphanImportStages = true
        ).onFailure { error ->
            h.Hchat.utils.HLog.e("$TAG 恢复插件文件事务失败: ${error.message}", error)
        }
        startPluginObservers(hostAppContext)
    }

    @JvmStatic
    fun installAppBrandProcess(context: Context, classLoader: ClassLoader, processName: String?) {
        val hostAppContext = context.applicationContext ?: context
        if (!isPluginRuntimeEnabled(hostAppContext)) return
        // 没有“已启用且声明 appbrand”的插件时，小程序进程不分配脚本桥、不启动加载线程。
        if (!hasEnabledAppBrandPlugin(hostAppContext)) return
        if (!initialLoadStarted.compareAndSet(false, true)) return
        appContext = hostAppContext
        runtimeProcess = PROCESS_APPBRAND
        runtimeProcessName = processName.orEmpty()
        val currentBridge = ScriptPluginBridge(
            hostAppContext,
            classLoader,
            scriptDir(hostAppContext)
        )
        bridge = currentBridge
        Thread({
            try {
                val result = loadEnabledAppBrandPlugins(hostAppContext, currentBridge)
                if (result.isFailure) {
                    h.Hchat.utils.HLog.e(
                        "$TAG 小程序进程插件加载失败: ${runtimeProcessName} " +
                            "${result.exceptionOrNull()?.message}",
                        result.exceptionOrNull()
                    )
                }
            } catch (error: Throwable) {
                h.Hchat.utils.HLog.e(
                    "$TAG 小程序进程插件加载线程异常: ${runtimeProcessName} ${error.message}",
                    error
                )
            }
        }, "Hchat-Script-AppBrand").apply {
            isDaemon = true
            start()
        }
    }

    fun loadEnabledPluginsWhenReady(context: Context) {
        val hostAppContext = context.applicationContext ?: context
        val prefs = HchatStorage.preferences(hostAppContext, ScriptPluginSettings.PREFS_NAME)
        if (!prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)) return
        if (!initialLoadStarted.compareAndSet(false, true)) return
        Thread({
            try {
                if (!awaitScriptApiReady(hostAppContext)) {
                    if (isPluginRuntimeEnabled(hostAppContext)) {
                        h.Hchat.utils.HLog.e("$TAG 等待联系人数据库就绪超时，跳过本次自动加载")
                    }
                    return@Thread
                }
                val result = loadEnabledPlugins(hostAppContext, currentBridge(hostAppContext))
                if (result.isFailure) {
                    h.Hchat.utils.HLog.e(
                        "$TAG 自动加载已启用插件失败: ${result.exceptionOrNull()?.message}",
                        result.exceptionOrNull()
                    )
                }
            } catch (error: Throwable) {
                h.Hchat.utils.HLog.e("$TAG 自动加载线程异常: ${error.message}", error)
            }
        }, "Hchat-Script-InitialLoad").apply {
            isDaemon = true
            start()
        }
    }

    private fun awaitScriptApiReady(context: Context): Boolean {
        val deadline = SystemClock.elapsedRealtime() + INITIAL_LOAD_READY_TIMEOUT_MS
        var pollDelay = INITIAL_LOAD_POLL_MIN_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isPluginRuntimeEnabled(context)) return false
            if (isScriptApiReady()) return true
            SystemClock.sleep(pollDelay)
            pollDelay = (pollDelay * 2L).coerceAtMost(INITIAL_LOAD_POLL_MAX_MS)
        }
        return false
    }

    private fun isScriptApiReady(): Boolean {
        val rows = WeChatApis.database()?.query(
            "SELECT name FROM sqlite_master " +
                "WHERE type='table' AND name IN ('rcontact','chatroom')",
            null
        ).orEmpty()
        val tables = rows.mapNotNull { it["name"]?.toString() }.toSet()
        return tables.contains("rcontact") && tables.contains("chatroom")
    }

    private fun isPluginRuntimeEnabled(context: Context): Boolean {
        return HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            .getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)
    }

    /** 至少存在一个已启用且声明 appbrand 的插件时，小程序进程才需要脚本运行时。 */
    private fun hasEnabledAppBrandPlugin(context: Context): Boolean {
        return listPlugins(context).any { plugin ->
            PROCESS_APPBRAND in plugin.processScope && isPluginEnabled(context, plugin.id)
        }
    }

    @Synchronized
    fun setGlobalEnabled(context: Context, enabled: Boolean): Result<Unit> {
        val appContext = context.applicationContext ?: context
        this.appContext = appContext
        ensureDirs(appContext)
        startPluginObservers(appContext)
        if (enabled && !isScriptApiReady()) {
            return Result.failure(IllegalStateException("微信联系人数据库尚未就绪，请稍后重试"))
        }
        val prefs = HchatStorage.preferences(appContext, ScriptPluginSettings.PREFS_NAME)
        val result = if (enabled) {
            prefs.edit().putBoolean(ScriptPluginSettings.KEY_ENABLE, true).apply()
            loadEnabledPlugins(appContext, currentBridge(appContext))
        } else {
            unloadAllPlugins()
        }
        if (result.isSuccess) {
            prefs.edit().putBoolean(ScriptPluginSettings.KEY_ENABLE, enabled).apply()
        } else if (enabled) {
            unloadAllPlugins()
            prefs.edit().putBoolean(ScriptPluginSettings.KEY_ENABLE, false).apply()
        }
        return result
    }

    fun setPluginEnabled(context: Context, pluginId: String, enabled: Boolean): Result<Unit> {
        return runCatching {
            ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(pluginId)) {
                synchronized(this) {
                    setPluginEnabledLocked(context, pluginId, enabled).getOrThrow()
                }
            }
        }
    }

    private fun setPluginEnabledLocked(context: Context, pluginId: String, enabled: Boolean): Result<Unit> {
        val appContext = context.applicationContext ?: context
        this.appContext = appContext
        ensureDirs(appContext)
        startPluginObservers(appContext)
        val prefs = HchatStorage.preferences(appContext, ScriptPluginSettings.PREFS_NAME)
        val plugin = if (enabled) {
            listPlugins(appContext).firstOrNull { it.id == pluginId }
                ?: return Result.failure(IllegalArgumentException("未找到插件: $pluginId"))
        } else {
            null
        }
        if (enabled &&
            prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE) &&
            plugin?.let { supportsProcess(it, PROCESS_MAIN) } == true &&
            !isScriptApiReady()
        ) {
            return Result.failure(IllegalStateException("微信联系人数据库尚未就绪，请稍后重试"))
        }
        val result = if (enabled) {
            val targetPlugin = requireNotNull(plugin)
            if (targetPlugin.processScope.isEmpty()) {
                Result.failure(IllegalArgumentException("插件 process 配置无效"))
            } else if (!prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)) {
                Result.success(Unit)
            } else if (!supportsProcess(targetPlugin, PROCESS_MAIN)) {
                Result.success(Unit)
            } else {
                loadPlugin(appContext, currentBridge(appContext), targetPlugin, forceReload = true)
            }
        } else {
            reloadTasks.remove(pluginId)?.let { mainHandler.removeCallbacks(it) }
            unloadPlugin(pluginId)
        }
        if (result.isSuccess) {
            prefs.edit()
                .putBoolean(ScriptPluginSettings.pluginEnableKey(pluginId), enabled)
                .apply()
        }
        notifyPluginCatalogChanged()
        return result
    }

    @Synchronized
    fun reloadPlugin(context: Context, pluginId: String): Result<Unit> {
        val appContext = context.applicationContext ?: context
        this.appContext = appContext
        val prefs = HchatStorage.preferences(appContext, ScriptPluginSettings.PREFS_NAME)
        if (!prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE) ||
            !prefs.getBoolean(
                ScriptPluginSettings.pluginEnableKey(pluginId),
                ScriptPluginSettings.DEFAULT_PLUGIN_ENABLE
            )
        ) {
            return Result.success(Unit)
        }
        val plugin = listPlugins(appContext).firstOrNull { it.id == pluginId }
            ?: return Result.failure(IllegalArgumentException("未找到插件: $pluginId"))
        if (plugin.processScope.isEmpty()) {
            return Result.failure(IllegalArgumentException("插件 process 配置无效"))
        }
        if (!supportsProcess(plugin, runtimeProcess)) return Result.success(Unit)
        return loadPlugin(
            appContext,
            currentBridge(appContext),
            plugin,
            forceReload = true,
            requireScriptApiReady = runtimeProcess == PROCESS_MAIN
        )
    }

    fun reloadPluginAsync(context: Context, pluginId: String) {
        Thread({
            val result = reloadPlugin(context, pluginId)
            if (result.isFailure) {
                h.Hchat.utils.HLog.e(
                    "$TAG 插件重载失败: $pluginId ${result.exceptionOrNull()?.message}",
                    result.exceptionOrNull()
                )
            }
        }, "Hchat-Script-Reload-$pluginId").start()
    }

    @Synchronized
    fun refreshPluginObserver(context: Context, pluginId: String) {
        pluginDirObservers.remove(pluginId)?.stopWatching()
        reloadTasks.remove(pluginId)?.let { mainHandler.removeCallbacks(it) }
        startPluginObservers(context.applicationContext ?: context)
    }

    fun compileSnapshot(pluginId: String, path: String): String {
        val loaded = loadedPlugins[pluginId] ?: error("插件未开启")
        return compileSnapshot(pluginId, loaded.plugin.dir, loaded.interpreter, path)
    }

    fun compileSnapshot(pluginId: String, pluginDir: File, interpreter: Interpreter, path: String): String {
        val sourceFile = resolvePluginFile(pluginDir, path)
        require(sourceFile.isFile) { "源脚本不存在: ${sourceFile.absolutePath}" }
        val outputFile = File(sourceFile.absolutePath + SNAPSHOT_SUFFIX)
        withInterpreterLock(interpreter) {
            interpreter.compileSnapshot(
                sourceFile.absolutePath,
                outputFile.absolutePath,
                snapshotKey(pluginId)
            )
        }
        return outputFile.absolutePath
    }

    fun evalSnapshot(pluginId: String, path: String): Any? {
        val loaded = loadedPlugins[pluginId] ?: error("插件未开启")
        return evalSnapshot(pluginId, loaded.plugin.dir, loaded.interpreter, path)
    }

    fun evalSnapshot(pluginId: String, pluginDir: File, interpreter: Interpreter, path: String): Any? {
        val snapshotFile = resolvePluginFile(pluginDir, path)
        require(snapshotFile.isFile) { "快照文件不存在: ${snapshotFile.absolutePath}" }
        val result = withInterpreterLock(interpreter) {
            interpreter.evalSnapshot(
                snapshotFile.absolutePath,
                snapshotKey(pluginId)
            )
        }
        refreshCallbacks(pluginId, interpreter)
        return result
    }

    fun evalSnapshot(pluginId: String, interpreter: Interpreter, inputStream: InputStream): Any? {
        val result = withInterpreterLock(interpreter) {
            interpreter.evalSnapshot(inputStream, snapshotKey(pluginId))
        }
        refreshCallbacks(pluginId, interpreter)
        return result
    }

    fun evalSnapshot(pluginId: String, interpreter: Interpreter, data: ByteArray): Any? {
        return evalSnapshot(pluginId, interpreter, ByteArrayInputStream(data))
    }

    fun hasOpenSettings(pluginId: String): Boolean {
        return loadedPlugins[pluginId]?.hasOpenSettingsCallback == true
    }

    fun refreshCallbacks(pluginId: String, interpreter: Interpreter) {
        val loaded = loadedPlugins[pluginId] ?: return
        val callbackFlags = withInterpreterLock(interpreter) { detectCallbacks(interpreter) }
        loaded.hasSendButtonCallback = loaded.hasSendButtonCallback || callbackFlags.hasSendButton
        loaded.hasLongSendButtonCallback =
            loaded.hasLongSendButtonCallback || callbackFlags.hasLongSendButton
        loaded.hasHandleMsgCallback = loaded.hasHandleMsgCallback || callbackFlags.hasHandleMsg
        loaded.hasOpenSettingsCallback = loaded.hasOpenSettingsCallback || callbackFlags.hasOpenSettings
        loaded.hasMemberChangeCallback = loaded.hasMemberChangeCallback || callbackFlags.hasMemberChange
        loaded.hasNewFriendCallback = loaded.hasNewFriendCallback || callbackFlags.hasNewFriend
        loaded.hasProtobufPacketCallback =
            loaded.hasProtobufPacketCallback || callbackFlags.hasProtobufPacket
        loaded.hasImageDownloadCallback =
            loaded.hasImageDownloadCallback || callbackFlags.hasImageDownload
        loaded.hasVideoDownloadCallback =
            loaded.hasVideoDownloadCallback || callbackFlags.hasVideoDownload
        loaded.hasFinderMediaDownloadCallback =
            loaded.hasFinderMediaDownloadCallback || callbackFlags.hasFinderMediaDownload
        updateProtobufPacketListener()
    }

    fun useCallback(pluginId: String, interpreter: Interpreter, callbackName: String?, methodName: String?) {
        val wrapper = callbackWrapper(callbackName?.trim().orEmpty(), methodName?.trim().orEmpty())
        withInterpreterLock(interpreter) {
            interpreter.eval(wrapper)
            refreshCallbacks(pluginId, interpreter)
        }
    }

    fun sendProtobufPacket(
        uri: String,
        cgiId: Int,
        json: String,
        callback: Consumer<SendResult>?
    ): Boolean {
        return ProtobufPacketRuntime.send(uri, cgiId, json) { success, message ->
            deliverProtobufSendResult(callback, success, message)
        }
    }

    fun sendProtobufPacket(
        uri: String,
        cgiId: Int,
        funcId: Int,
        routeId: Int,
        json: String,
        callback: Consumer<SendResult>?
    ): Boolean {
        return ProtobufPacketRuntime.send(uri, cgiId, funcId, routeId, json) { success, message ->
            deliverProtobufSendResult(callback, success, message)
        }
    }

    fun prepareSnsPostMedia(
        pluginId: String,
        interpreter: Interpreter,
        snsId: String?,
        callback: Consumer<Any?>?
    ): Boolean {
        val normalizedId = snsId.orEmpty().trim()
        if (normalizedId.isEmpty() || callback == null) return false
        val api = WeChatApis.interaction().sns() ?: return false
        val taskId = "$pluginId:$normalizedId:${snsPrepareSequence.incrementAndGet()}"
        val canceled = AtomicBoolean(false)
        snsPrepareCancellations[taskId] = canceled
        return try {
            snsPrepareExecutor.execute {
                try {
                    val result = api.prepareSnsPostMedia(normalizedId, canceled)
                    if (!canceled.get()) {
                        val loaded = loadedPlugins[pluginId]
                        if (loaded?.interpreter === interpreter) {
                            runCatching {
                                withInterpreterLock(interpreter) {
                                    if (!canceled.get() && loadedPlugins[pluginId] === loaded) {
                                        callback.accept(result)
                                    }
                                }
                            }.onFailure {
                                h.Hchat.utils.HLog.e(
                                    "$TAG 朋友圈媒体准备回调失败: $pluginId ${it.message}",
                                    it
                                )
                            }
                        }
                    }
                } finally {
                    snsPrepareCancellations.remove(taskId, canceled)
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            snsPrepareCancellations.remove(taskId, canceled)
            false
        }
    }

    fun evalCode(pluginId: String, interpreter: Interpreter, code: String): Any? {
        return withInterpreterLock(interpreter) {
            val result = interpreter.eval(code)
            refreshCallbacks(pluginId, interpreter)
            result
        }
    }

    fun loadJava(pluginId: String, pluginDir: File, interpreter: Interpreter, path: String) {
        val file = resolvePluginFile(pluginDir, path)
        withInterpreterLock(interpreter) {
            interpreter.source(file.absolutePath)
            refreshCallbacks(pluginId, interpreter)
        }
    }

    fun loadDex(
        pluginId: String,
        pluginDir: File,
        interpreter: Interpreter,
        parentClassLoader: ClassLoader,
        path: String
    ): ClassLoader {
        val sourceFile = resolvePluginFile(pluginDir, path)
        require(sourceFile.isFile) { "Dex文件不存在: ${sourceFile.absolutePath}" }
        val context = appContext ?: error("宿主Context不可用")
        val dexRoot = processScopedCacheRoot(context, "hchat_plugin_dex")
        val pluginDexDir = File(dexRoot, safeFileName(pluginId)).apply { mkdirs() }
        val optimizedDir = File(pluginDexDir, "opt").apply { mkdirs() }
        val digest = sha256(sourceFile).take(16)
        val copiedDex = File(pluginDexDir, "${sourceFile.nameWithoutExtension}_$digest.${sourceFile.extension.ifBlank { "dex" }}")
        if (!copiedDex.isFile || copiedDex.length() != sourceFile.length()) {
            sourceFile.copyTo(copiedDex, overwrite = true)
        }
        copiedDex.setWritable(false, false)
        val classLoader = DexClassLoader(
            copiedDex.absolutePath,
            optimizedDir.absolutePath,
            null,
            parentClassLoader
        )
        withInterpreterLock(interpreter) {
            interpreter.addClassLoader(classLoader)
        }
        return classLoader
    }

    fun loadSo(pluginId: String, pluginDir: File, classLoader: ClassLoader, path: String) {
        require(Process.is64Bit()) { "当前版本仅支持 ARM64 微信进程" }
        val sourceFile = resolvePluginFile(pluginDir, path).canonicalFile
        require(sourceFile.isFile) { "SO文件不存在: ${sourceFile.absolutePath}" }
        require(sourceFile.extension.equals("so", ignoreCase = true)) {
            "SO文件扩展名必须是 .so: ${sourceFile.name}"
        }
        validateNativeLibrary(sourceFile)
        val context = appContext ?: error("宿主Context不可用")
        val sourceDigest = sha256(sourceFile)
        val nativeRoot = processScopedCacheRoot(context, "hchat_plugin_native")
        val pluginNativeDir = File(nativeRoot, safeFileName(pluginId))
        require(pluginNativeDir.isDirectory || pluginNativeDir.mkdirs()) {
            "无法创建Native缓存目录: ${pluginNativeDir.absolutePath}"
        }
        synchronized(nativeLoadLock) {
            val sourceKey = sourceFile.absolutePath
            val loaded = loadedNativeLibraries.firstOrNull {
                it.sourcePath == sourceKey && it.classLoader === classLoader
            }
            loaded?.let {
                require(it.digest == sourceDigest) {
                    "SO内容已更新，但JNI ClassLoader未变化；请重新加载插件并传入新JNI类的ClassLoader，" +
                        "宿主ClassLoader无法热更新: ${sourceFile.absolutePath}"
                }
                return
            }

            // ART cannot safely unload a native library. A unique path makes an updated
            // library a new load while the previous mapping remains valid until process exit.
            val loadId = nativeLoadSequence.incrementAndGet().toString(36)
            val loaderId = Integer.toHexString(System.identityHashCode(classLoader))
            val copiedSo = File(
                pluginNativeDir,
                "${safeFileName(sourceFile.nameWithoutExtension)}_${sourceDigest}_${loaderId}_$loadId.so"
            )
            copyNativeLibrary(sourceFile, copiedSo, sourceDigest)
            Os.chmod(copiedSo.absolutePath, 0b100100100)
            require(!copiedSo.canWrite()) { "Native缓存无法设为只读: ${copiedSo.absolutePath}" }
            loadNativeLibrary(copiedSo, classLoader)
            loadedNativeLibraries += LoadedNativeLibrary(sourceKey, sourceDigest, classLoader)
        }
    }

    fun canOpenSettings(plugin: ScriptPlugin): Boolean {
        if (!supportsProcess(plugin, PROCESS_MAIN)) return false
        if (hasOpenSettings(plugin.id)) return true
        return runCatching {
            detectCallbacks(plugin.mainFile.readText(Charsets.UTF_8)).hasOpenSettings
        }.getOrDefault(false)
    }

    fun callOpenSettings(pluginId: String): Result<Unit> {
        val loaded = loadedPlugins[pluginId]
            ?: return Result.failure(IllegalStateException("插件未开启"))
        if (!loaded.hasOpenSettingsCallback) {
            return Result.failure(IllegalStateException("插件没有设置入口"))
        }
        return runCatching {
            withInterpreterLock(loaded.interpreter) {
                loaded.interpreter.eval("openSettings();")
            }
        }.map { Unit }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 插件设置入口失败: ${loaded.plugin.name} ${it.message}", it)
            bridge?.log(loaded.plugin.name, loaded.plugin.dir, "设置入口失败: ${it.message}")
        }
    }

    fun callPluginFunction(pluginId: String, functionName: String, vararg args: Any?): Result<Any?> {
        val loaded = loadedPlugins[pluginId]
            ?: return Result.failure(IllegalStateException("插件未开启: $pluginId"))
        if (!functionName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            return Result.failure(IllegalArgumentException("非法函数名: $functionName"))
        }
        return runCatching {
            withInterpreterLock(loaded.interpreter) {
                val names = args.indices.map { "__hchat_call_arg_$it" }
                args.forEachIndexed { index, value ->
                    loaded.interpreter.set(names[index], value)
                }
                loaded.interpreter.eval("$functionName(${names.joinToString(",")});")
            }
        }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 调用插件函数失败: ${loaded.plugin.name}#$functionName ${it.message}", it)
            bridge?.log(loaded.plugin.name, loaded.plugin.dir, "调用插件函数失败: $functionName ${it.message}")
        }
    }

    fun dispatchOnClickSendBtn(text: String): SendButtonEventResult {
        return dispatchSendButton(text, longClick = false)
    }

    fun dispatchOnLongClickSendBtn(text: String): SendButtonEventResult {
        return dispatchSendButton(text, longClick = true)
    }

    fun hasLongSendButtonCallbacks(): Boolean {
        return loadedPlugins.values.any { it.hasLongSendButtonCallback }
    }

    private fun dispatchSendButton(text: String, longClick: Boolean): SendButtonEventResult {
        if (loadedPlugins.isEmpty()) return SendButtonEventResult(false, emptyList())
        val handledBy = ArrayList<String>()
        var intercepted = false
        val targets = loadedPlugins.values
            .asSequence()
            .filter {
                if (longClick) it.hasLongSendButtonCallback else it.hasSendButtonCallback
            }
            .sortedBy { it.plugin.id.lowercase(Locale.US) }
            .toList()
        if (targets.isEmpty()) return SendButtonEventResult(false, emptyList())
        for (loaded in targets) {
            if (loadedPlugins[loaded.plugin.id] !== loaded) continue
            val lock = interpreterLock(loaded.interpreter)
            if (!lock.tryLock()) {
                logBusySendButtonPlugin(loaded, longClick)
                continue
            }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                if (loadedPlugins[loaded.plugin.id] !== loaded) continue
                loaded.interpreter.set("__hchat_send_text", text)
                val callbackName = if (longClick) "onLongClickSendBtn" else "onClickSendBtn"
                val result = loaded.interpreter.eval("$callbackName(__hchat_send_text);")
                if (result == true) {
                    intercepted = true
                    handledBy += loaded.plugin.name
                }
            } catch (t: Throwable) {
                val message = t.message.orEmpty()
                if (!message.contains("Command not found", ignoreCase = true)
                    && !message.contains("undefined", ignoreCase = true)
                    && !message.contains("not found", ignoreCase = true)
                ) {
                    val label = if (longClick) "长按发送按钮" else "发送按钮"
                    h.Hchat.utils.HLog.e("$TAG ${label}回调失败: ${loaded.plugin.name} ${t.message}", t)
                    bridge?.log(loaded.plugin.name, loaded.plugin.dir, "${label}回调失败: ${t}")
                }
            } finally {
                lock.unlock()
                val duration = SystemClock.elapsedRealtime() - startedAt
                if (duration >= SEND_BUTTON_SLOW_CALLBACK_MS) {
                    val diagnosticKey = if (longClick) "slow_long" else "slow"
                    val label = if (longClick) "长按发送按钮" else "发送按钮"
                    logSendButtonDiagnostic(
                        "$diagnosticKey:${loaded.plugin.id}",
                        "${label}回调耗时: ${loaded.plugin.name} ${duration}ms"
                    )
                }
            }
        }
        return SendButtonEventResult(intercepted, handledBy)
    }

    fun hasHandleMsgCallbacks(): Boolean = loadedPlugins.values.any { it.hasHandleMsgCallback }

    fun dispatchOnHandleMsg(msgInfoBean: ScriptMessageBean) {
        captureHandleMsgDispatch(msgInfoBean)?.run()
    }

    // 入队时固定插件实例，排队期间重载不会把旧消息交给新解释器。
    fun captureHandleMsgDispatch(msgInfoBean: ScriptMessageBean): Runnable? {
        val targets = loadedPlugins.values
            .asSequence()
            .filter { it.hasHandleMsgCallback }
            .sortedBy { it.plugin.id.lowercase(Locale.US) }
            .toList()
        if (targets.isEmpty()) return null
        return Runnable {
            for (loaded in targets) {
                if (loadedPlugins[loaded.plugin.id] !== loaded) continue
                try {
                    withInterpreterLock(loaded.interpreter) {
                        if (loadedPlugins[loaded.plugin.id] !== loaded) return@withInterpreterLock
                        loaded.interpreter.set("__hchat_msg_info", msgInfoBean)
                        try {
                            loaded.interpreter.eval("onHandleMsg(__hchat_msg_info);")
                        } finally {
                            loaded.interpreter.unset("__hchat_msg_info")
                        }
                    }
                } catch (t: Throwable) {
                    if (!isMissingCallbackError(t, "onHandleMsg")) {
                        h.Hchat.utils.HLog.e(TAG + " 消息监听回调失败: " + loaded.plugin.name + " " + t.message, t)
                        bridge?.log(loaded.plugin.name, loaded.plugin.dir, "消息监听回调失败: " + t)
                    }
                }
            }
        }
    }

    fun hasImageDownloadCallback(): Boolean {
        return loadedPlugins.values.any { it.hasImageDownloadCallback }
    }

    fun dispatchOnImageDownload(msgInfoBean: ScriptMessageBean) {
        if (loadedPlugins.values.none { it.hasImageDownloadCallback }) return
        if (!msgInfoBean.isImage()) return
        val imageMsg = runCatching { msgInfoBean.getImageMsg() }.getOrNull() ?: return
        val currentBridge = bridge ?: return
        val taskKey = imageDownloadTaskKey(msgInfoBean)
        if (!acquireImageDownloadTask(taskKey)) return
        try {
            imageDownloadCallbackExecutor.execute {
                try {
                    val path = downloadCallbackImage(currentBridge, msgInfoBean, imageMsg)
                    if (path == null) {
                        imageDownloadTasks.remove(taskKey)
                        return@execute
                    }
                    val targets = loadedPlugins.values
                        .asSequence()
                        .filter { it.hasImageDownloadCallback }
                        .sortedBy { it.plugin.id.lowercase(Locale.US) }
                        .toList()
                    val talker = msgInfoBean.getTalker()
                    val senderWxid = msgInfoBean.getSendTalker()
                    for (loaded in targets) {
                        if (loadedPlugins[loaded.plugin.id] !== loaded) continue
                        try {
                            withInterpreterLock(loaded.interpreter) {
                                if (loadedPlugins[loaded.plugin.id] !== loaded) return@withInterpreterLock
                                loaded.interpreter.set("__hchat_image_msg_info", msgInfoBean)
                                loaded.interpreter.set("__hchat_image_path", path)
                                loaded.interpreter.set("__hchat_image_talker", talker)
                                loaded.interpreter.set("__hchat_image_sender", senderWxid)
                                loaded.interpreter.eval(
                                    "onImageDownload(__hchat_image_msg_info, " +
                                        "__hchat_image_path, " +
                                        "__hchat_image_talker, " +
                                        "__hchat_image_sender);"
                                )
                            }
                        } catch (t: Throwable) {
                            if (!isMissingCallbackError(t, "onImageDownload")) {
                                h.Hchat.utils.HLog.e(
                                    "$TAG 图片下载回调失败: ${loaded.plugin.name} ${t.message}",
                                    t
                                )
                                bridge?.log(
                                    loaded.plugin.name,
                                    loaded.plugin.dir,
                                    "图片下载回调失败: ${t.message}"
                                )
                            }
                        }
                    }
                    imageDownloadTasks[taskKey] = System.currentTimeMillis()
                } catch (t: Throwable) {
                    imageDownloadTasks.remove(taskKey)
                    h.Hchat.utils.HLog.e("$TAG 图片下载监听任务失败: ${t.message}", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            imageDownloadTasks.remove(taskKey)
            logDroppedImageDownload()
        }
    }

    private fun imageDownloadTaskKey(msgInfoBean: ScriptMessageBean): String {
        val talker = msgInfoBean.getTalker()
        val stableId = msgInfoBean.getMsgId().takeIf { it > 0L }
            ?.let { "msg:$it" }
            ?: msgInfoBean.getMsgSvrId().takeIf { it > 0L }?.let { "svr:$it" }
            ?: "time:${msgInfoBean.getCreateTime()}:${msgInfoBean.getContent().hashCode()}"
        return "$talker|$stableId"
    }

    private fun acquireImageDownloadTask(taskKey: String): Boolean {
        val now = System.currentTimeMillis()
        imageDownloadTasks.entries.forEach { (key, timestamp) ->
            if (timestamp != IMAGE_DOWNLOAD_TASK_RUNNING &&
                now - timestamp >= IMAGE_DOWNLOAD_TASK_DEDUP_WINDOW_MS
            ) {
                imageDownloadTasks.remove(key, timestamp)
            }
        }
        while (true) {
            val previous = imageDownloadTasks.putIfAbsent(
                taskKey,
                IMAGE_DOWNLOAD_TASK_RUNNING
            ) ?: return true
            if (previous == IMAGE_DOWNLOAD_TASK_RUNNING) return false
            if (now - previous < IMAGE_DOWNLOAD_TASK_DEDUP_WINDOW_MS) return false
            if (imageDownloadTasks.replace(taskKey, previous, IMAGE_DOWNLOAD_TASK_RUNNING)) {
                return true
            }
        }
    }

    fun hasVideoDownloadCallback(): Boolean {
        return loadedPlugins.values.any { it.hasVideoDownloadCallback }
    }

    fun dispatchOnVideoDownload(msgInfoBean: ScriptMessageBean) {
        if (!msgInfoBean.isVideo()) return
        val targets = loadedPlugins.values
            .asSequence()
            .filter { it.hasVideoDownloadCallback }
            .sortedBy { it.plugin.id.lowercase(Locale.US) }
            .toList()
        if (targets.isEmpty()) return
        val currentBridge = bridge ?: return
        val taskKey = videoDownloadTaskKey(msgInfoBean)
        if (!acquireVideoDownloadTask(taskKey)) return
        try {
            videoDownloadCallbackExecutor.execute {
                try {
                    if (targets.none { loadedPlugins[it.plugin.id] === it }) {
                        videoDownloadTasks.remove(taskKey)
                        return@execute
                    }
                    val path = downloadCallbackVideo(currentBridge, msgInfoBean)
                    if (path == null) {
                        videoDownloadTasks.remove(taskKey)
                        return@execute
                    }
                    try {
                        val talker = msgInfoBean.getTalker()
                        val senderWxid = msgInfoBean.getSendTalker()
                        for (loaded in targets) {
                            if (loadedPlugins[loaded.plugin.id] !== loaded) continue
                            try {
                                withInterpreterLock(loaded.interpreter) {
                                    if (loadedPlugins[loaded.plugin.id] !== loaded) return@withInterpreterLock
                                    loaded.interpreter.set("__hchat_video_msg_info", msgInfoBean)
                                    loaded.interpreter.set("__hchat_video_path", path)
                                    loaded.interpreter.set("__hchat_video_talker", talker)
                                    loaded.interpreter.set("__hchat_video_sender", senderWxid)
                                    loaded.interpreter.eval(
                                        "onVideoDownload(__hchat_video_msg_info, " +
                                            "__hchat_video_path, " +
                                            "__hchat_video_talker, " +
                                            "__hchat_video_sender);"
                                    )
                                }
                            } catch (t: Throwable) {
                                if (!isMissingCallbackError(t, "onVideoDownload")) {
                                    h.Hchat.utils.HLog.e(
                                        "$TAG 视频下载回调失败: ${loaded.plugin.name} ${t.message}",
                                        t
                                    )
                                    bridge?.log(
                                        loaded.plugin.name,
                                        loaded.plugin.dir,
                                        "视频下载回调失败: ${t.message}"
                                    )
                                }
                            }
                        }
                    } finally {
                        runCatching { File(path).delete() }
                    }
                } catch (t: Throwable) {
                    videoDownloadTasks.remove(taskKey)
                    h.Hchat.utils.HLog.e("$TAG 视频下载监听任务失败: ${t.message}", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            videoDownloadTasks.remove(taskKey)
            logDroppedVideoDownload()
        }
    }

    private fun videoDownloadTaskKey(msgInfoBean: ScriptMessageBean): String {
        val talker = msgInfoBean.getTalker()
        val stableId = msgInfoBean.getMsgId().takeIf { it > 0L }
            ?.let { "msg:$it" }
            ?: msgInfoBean.getMsgSvrId().takeIf { it > 0L }?.let { "svr:$it" }
            ?: "time:${msgInfoBean.getCreateTime()}:${msgInfoBean.getContent().hashCode()}"
        return "$talker|$stableId"
    }

    private fun acquireVideoDownloadTask(taskKey: String): Boolean {
        val now = System.currentTimeMillis()
        videoDownloadTasks.entries.forEach { (key, timestamp) ->
            if (now - timestamp >= VIDEO_DOWNLOAD_TASK_DEDUP_WINDOW_MS) {
                videoDownloadTasks.remove(key, timestamp)
            }
        }
        while (true) {
            val previous = videoDownloadTasks.putIfAbsent(taskKey, now) ?: return true
            if (now - previous < VIDEO_DOWNLOAD_TASK_DEDUP_WINDOW_MS) return false
            if (videoDownloadTasks.replace(taskKey, previous, now)) return true
        }
    }

    fun hasFinderMediaDownloadCallback(): Boolean {
        return loadedPlugins.values.any { it.hasFinderMediaDownloadCallback }
    }

    fun dispatchOnFinderMediaDownload(msgInfoBean: ScriptMessageBean) {
        val targets = loadedPlugins.values
            .asSequence()
            .filter { it.hasFinderMediaDownloadCallback }
            .sortedBy { it.plugin.id.lowercase(Locale.US) }
            .toList()
        if (targets.isEmpty()) return
        if (!msgInfoBean.isAppMsg() && !msgInfoBean.isVideoNumberVideo()) return
        val media = runCatching {
            FinderMediaDownloadSupport.extractMedia(msgInfoBean.getContent())
                ?: FinderMediaDownloadSupport.extractMedia(msgInfoBean)
        }.getOrNull() ?: return
        if (media.type != FinderMediaDownloadSupport.MEDIA_TYPE_IMAGE &&
            media.type != FinderMediaDownloadSupport.MEDIA_TYPE_VIDEO
        ) {
            return
        }
        val currentBridge = bridge ?: return
        val taskKey = finderMediaDownloadTaskKey(msgInfoBean)
        if (!acquireFinderMediaDownloadTask(taskKey)) return
        try {
            finderMediaDownloadCallbackExecutor.execute {
                try {
                    if (targets.none { loadedPlugins[it.plugin.id] === it }) {
                        finderMediaDownloadTasks.remove(taskKey)
                        return@execute
                    }
                    val paths = downloadCallbackFinderMedia(currentBridge, msgInfoBean, media)
                    if (paths.isEmpty()) {
                        finderMediaDownloadTasks.remove(taskKey)
                        return@execute
                    }
                    val talker = msgInfoBean.getTalker()
                    val senderWxid = msgInfoBean.getSendTalker()
                    for (path in paths) {
                        try {
                            for (loaded in targets) {
                                if (loadedPlugins[loaded.plugin.id] !== loaded) continue
                                try {
                                    withInterpreterLock(loaded.interpreter) {
                                        if (loadedPlugins[loaded.plugin.id] !== loaded) return@withInterpreterLock
                                        loaded.interpreter.set("__hchat_finder_msg_info", msgInfoBean)
                                        loaded.interpreter.set("__hchat_finder_media_path", path)
                                        loaded.interpreter.set("__hchat_finder_talker", talker)
                                        loaded.interpreter.set("__hchat_finder_sender", senderWxid)
                                        loaded.interpreter.eval(
                                            "onFinderMediaDownload(__hchat_finder_msg_info, " +
                                                "__hchat_finder_media_path, " +
                                                "__hchat_finder_talker, " +
                                                "__hchat_finder_sender);"
                                        )
                                    }
                                } catch (t: Throwable) {
                                    if (!isMissingCallbackError(t, "onFinderMediaDownload")) {
                                        h.Hchat.utils.HLog.e(
                                            "$TAG 视频号媒体下载回调失败: ${loaded.plugin.name} ${t.message}",
                                            t
                                        )
                                        bridge?.log(
                                            loaded.plugin.name,
                                            loaded.plugin.dir,
                                            "视频号媒体下载回调失败: ${t.message}"
                                        )
                                    }
                                }
                            }
                        } finally {
                            runCatching { File(path).delete() }
                        }
                    }
                    finderMediaDownloadTasks[taskKey] = System.currentTimeMillis()
                } catch (t: Throwable) {
                    finderMediaDownloadTasks.remove(taskKey)
                    h.Hchat.utils.HLog.e("$TAG 视频号媒体下载监听任务失败: ${t.message}", t)
                }
            }
        } catch (_: RejectedExecutionException) {
            finderMediaDownloadTasks.remove(taskKey)
            logDroppedFinderMediaDownload()
        }
    }

    private fun finderMediaDownloadTaskKey(msgInfoBean: ScriptMessageBean): String {
        val talker = msgInfoBean.getTalker()
        val stableId = msgInfoBean.getMsgId().takeIf { it > 0L }
            ?.let { "msg:$it" }
            ?: msgInfoBean.getMsgSvrId().takeIf { it > 0L }?.let { "svr:$it" }
            ?: "time:${msgInfoBean.getCreateTime()}:${msgInfoBean.getContent().hashCode()}"
        return "$talker|$stableId"
    }

    private fun acquireFinderMediaDownloadTask(taskKey: String): Boolean {
        val now = System.currentTimeMillis()
        finderMediaDownloadTasks.entries.forEach { (key, timestamp) ->
            if (timestamp != FINDER_MEDIA_DOWNLOAD_TASK_RUNNING &&
                now - timestamp >= FINDER_MEDIA_DOWNLOAD_TASK_DEDUP_WINDOW_MS
            ) {
                finderMediaDownloadTasks.remove(key, timestamp)
            }
        }
        while (true) {
            val previous = finderMediaDownloadTasks.putIfAbsent(
                taskKey,
                FINDER_MEDIA_DOWNLOAD_TASK_RUNNING
            ) ?: return true
            if (previous == FINDER_MEDIA_DOWNLOAD_TASK_RUNNING) return false
            if (now - previous < FINDER_MEDIA_DOWNLOAD_TASK_DEDUP_WINDOW_MS) return false
            if (finderMediaDownloadTasks.replace(
                    taskKey,
                    previous,
                    FINDER_MEDIA_DOWNLOAD_TASK_RUNNING
                )
            ) {
                return true
            }
        }
    }

    private fun downloadCallbackImage(
        currentBridge: ScriptPluginBridge,
        msgInfoBean: ScriptMessageBean,
        imageMsg: Any
    ): String? {
        val cacheDir = File(currentBridge.scriptDir.parentFile ?: currentBridge.scriptDir, "Cache")
            .apply { if (!isDirectory) mkdirs() }
        val messageId = msgInfoBean.getMsgId().takeIf { it > 0L }
            ?: msgInfoBean.getCreateTime().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val sender = safeCallbackFilePart(
            msgInfoBean.getSendTalker().ifBlank { msgInfoBean.getSender() }
        )
        val file = File(
            cacheDir,
            "Hchat_ImageCallback_${sender}_${messageId}_${System.currentTimeMillis()}.jpg"
        )
        ScriptWaBridge(currentBridge).downloadImg(imageMsg, file.absolutePath)
        return file.takeIf { it.isFile && it.length() > 0L }?.absolutePath
    }

    private fun downloadCallbackVideo(
        currentBridge: ScriptPluginBridge,
        msgInfoBean: ScriptMessageBean
    ): String? {
        val cacheDir = File(currentBridge.scriptDir.parentFile ?: currentBridge.scriptDir, "Cache")
            .apply { if (!isDirectory) mkdirs() }
        val messageId = msgInfoBean.getMsgId().takeIf { it > 0L }
            ?: msgInfoBean.getMsgSvrId().takeIf { it > 0L }
            ?: msgInfoBean.getCreateTime().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val sender = safeCallbackFilePart(
            msgInfoBean.getSendTalker().ifBlank { msgInfoBean.getSender() }
        )
        val file = File(
            cacheDir,
            "Hchat_VideoCallback_${sender}_${messageId}_${System.currentTimeMillis()}.mp4"
        )
        return ScriptWaBridge(currentBridge)
            .downloadVideoForListener(msgInfoBean, file.absolutePath)
            ?.absolutePath
    }

    private fun downloadCallbackFinderMedia(
        currentBridge: ScriptPluginBridge,
        msgInfoBean: ScriptMessageBean,
        media: FinderMediaDownloadSupport.FinderMedia
    ): List<String> {
        val cacheDir = File(currentBridge.scriptDir.parentFile ?: currentBridge.scriptDir, "Cache")
            .apply { if (!isDirectory) mkdirs() }
        val messageId = msgInfoBean.getMsgId().takeIf { it > 0L }
            ?: msgInfoBean.getMsgSvrId().takeIf { it > 0L }
            ?: msgInfoBean.getCreateTime().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val sender = safeCallbackFilePart(
            msgInfoBean.getSendTalker().ifBlank { msgInfoBean.getSender() }
        )
        val extension = if (media.type == FinderMediaDownloadSupport.MEDIA_TYPE_VIDEO) {
            "mp4"
        } else {
            "jpg"
        }
        return media.items.indices.mapNotNull { index ->
            val file = File(
                cacheDir,
                "Hchat_FinderCallback_${sender}_${messageId}_${index + 1}_" +
                    "${System.currentTimeMillis()}.$extension"
            )
            FinderMediaDownloadSupport.downloadItem(
                currentBridge.hostContext,
                media,
                index,
                file.absolutePath
            )?.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        }
    }

    fun dispatchOnProtobufPacket(packet: ProtobufPacketRuntime.Packet) {
        if (loadedPlugins.values.none { it.hasProtobufPacketCallback }) return
        try {
            protobufCallbackExecutor.execute {
                val targets = loadedPlugins.values
                    .asSequence()
                    .filter { it.hasProtobufPacketCallback }
                    .sortedBy { it.plugin.id.lowercase(Locale.US) }
                    .toList()
                for (loaded in targets) {
                    if (loadedPlugins[loaded.plugin.id] !== loaded) continue
                    try {
                        withInterpreterLock(loaded.interpreter) {
                            loaded.interpreter.set("__hchat_protobuf_packet", packet)
                            loaded.interpreter.eval("onProtobufPacket(__hchat_protobuf_packet);")
                        }
                    } catch (t: Throwable) {
                        if (!isMissingCallbackError(t, "onProtobufPacket")) {
                            h.Hchat.utils.HLog.e(
                                "$TAG 数据包监听回调失败: ${loaded.plugin.name} ${t.message}",
                                t
                            )
                            bridge?.log(
                                loaded.plugin.name,
                                loaded.plugin.dir,
                                "数据包监听回调失败: $t"
                            )
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            logDroppedProtobufPacket()
        }
    }

    fun dispatchOnMemberChange(type: String, groupWxid: String, userWxid: String, userName: String) {
        if (loadedPlugins.isEmpty()) return
        val targets = loadedPlugins.values
            .asSequence()
            .filter { it.hasMemberChangeCallback }
            .sortedBy { it.plugin.id.lowercase(Locale.US) }
            .toList()
        if (targets.isEmpty()) return
        for (loaded in targets) {
            if (loadedPlugins[loaded.plugin.id] !== loaded) continue
            try {
                withInterpreterLock(loaded.interpreter) {
                    if (loadedPlugins[loaded.plugin.id] !== loaded) return@withInterpreterLock
                    loaded.interpreter.set("__hchat_member_change_type", type)
                    loaded.interpreter.set("__hchat_member_change_group", groupWxid)
                    loaded.interpreter.set("__hchat_member_change_user", userWxid)
                    loaded.interpreter.set("__hchat_member_change_name", userName)
                    loaded.interpreter.eval(
                        "onMemberChange(__hchat_member_change_type, " +
                            "__hchat_member_change_group, " +
                            "__hchat_member_change_user, " +
                            "__hchat_member_change_name);"
                    )
                }
            } catch (t: Throwable) {
                val message = t.message.orEmpty()
                if (!message.contains("Command not found", ignoreCase = true)
                    && !message.contains("undefined", ignoreCase = true)
                    && !message.contains("not found", ignoreCase = true)
                ) {
                    h.Hchat.utils.HLog.e("$TAG 成员变动回调失败: ${loaded.plugin.name} ${t.message}", t)
                    bridge?.log(loaded.plugin.name, loaded.plugin.dir, "成员变动回调失败: ${t.message}")
                }
            }
        }
    }

    fun dispatchOnNewFriend(wxid: String, ticket: String, scene: Int) {
        if (loadedPlugins.isEmpty()) return
        val cleanWxid = wxid.trim()
        val cleanTicket = ticket.trim()
        if (cleanWxid.isEmpty() || cleanTicket.isEmpty()) return
        val targets = loadedPlugins.values
            .asSequence()
            .filter { it.hasNewFriendCallback }
            .sortedBy { it.plugin.id.lowercase(Locale.US) }
            .toList()
        if (targets.isEmpty()) return
        for (loaded in targets) {
            if (loadedPlugins[loaded.plugin.id] !== loaded) continue
            try {
                withInterpreterLock(loaded.interpreter) {
                    if (loadedPlugins[loaded.plugin.id] !== loaded) return@withInterpreterLock
                    loaded.interpreter.set("__hchat_new_friend_wxid", cleanWxid)
                    loaded.interpreter.set("__hchat_new_friend_ticket", cleanTicket)
                    loaded.interpreter.set("__hchat_new_friend_scene", scene)
                    loaded.interpreter.eval(
                        "onNewFriend(__hchat_new_friend_wxid, " +
                            "__hchat_new_friend_ticket, " +
                            "__hchat_new_friend_scene);"
                    )
                }
            } catch (t: Throwable) {
                val message = t.message.orEmpty()
                if (!message.contains("Command not found", ignoreCase = true)
                    && !message.contains("undefined", ignoreCase = true)
                    && !message.contains("not found", ignoreCase = true)
                ) {
                    h.Hchat.utils.HLog.e("$TAG 好友申请回调失败: ${loaded.plugin.name} ${t.message}", t)
                    bridge?.log(loaded.plugin.name, loaded.plugin.dir, "好友申请回调失败: ${t.message}")
                }
            }
        }
    }

    fun scriptDir(context: Context): File {
        val appContext = context.applicationContext ?: context
        val mediaRoot = try {
            appContext.externalMediaDirs?.firstOrNull { it != null }
        } catch (_: Throwable) {
            null
        }
        val root = mediaRoot ?: File("/storage/emulated/0/Android/media/${appContext.packageName}")
        return File(root, "WCX/脚本插件")
    }

    fun ensureDirs(context: Context): File {
        val dir = scriptDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        return dir
    }

    fun subscribePluginCatalog(context: Context, listener: () -> Unit): PluginCatalogSubscription {
        val appContext = context.applicationContext ?: context
        this.appContext = appContext
        ensureDirs(appContext)
        startPluginObservers(appContext)
        pluginCatalogListeners.add(listener)
        return PluginCatalogSubscription(listener)
    }

    fun listPlugins(context: Context): List<ScriptPlugin> {
        val root = scriptDir(context)
        if (!root.isDirectory) return emptyList()
        return root.listFiles()
            ?.asSequence()
            ?.filter(::isPluginDirectory)
            ?.mapNotNull { dir ->
                val mainFile = File(dir, MAIN_FILE)
                if (mainFile.isFile) {
                    val meta = readPluginMeta(dir)
                    ScriptPlugin(
                        id = dir.name,
                        name = meta.getProperty("name")?.takeIf { it.isNotBlank() } ?: dir.name,
                        dir = dir,
                        mainFile = mainFile,
                        author = meta.getProperty("author").orEmpty(),
                        version = meta.getProperty("version").orEmpty(),
                        updateTime = meta.getProperty("updateTime").orEmpty(),
                        displayName = meta.getProperty("name")?.takeIf { it.isNotBlank() },
                        processScope = parseProcessScope(dir.name, meta.getProperty("process"))
                    )
                } else {
                    null
                }
            }
            ?.sortedBy { it.id.lowercase(Locale.US) }
            ?.toList()
            ?: emptyList()
    }

    fun isPluginEnabled(context: Context, pluginId: String): Boolean {
        val prefs = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
        return prefs.getBoolean(
            ScriptPluginSettings.pluginEnableKey(pluginId),
            ScriptPluginSettings.DEFAULT_PLUGIN_ENABLE
        )
    }

    @Synchronized
    private fun loadEnabledPlugins(context: Context, currentBridge: ScriptPluginBridge): Result<Unit> {
        return runCatching {
            ensureDirs(context)
            startPluginObservers(context)
            val prefs = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            if (!prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)) {
                return@runCatching
            }
            var firstFailure: Throwable? = null
            for (plugin in listPlugins(context)) {
                if (!supportsProcess(plugin, PROCESS_MAIN)) continue
                if (!prefs.getBoolean(
                        ScriptPluginSettings.pluginEnableKey(plugin.id),
                        ScriptPluginSettings.DEFAULT_PLUGIN_ENABLE
                    )
                ) {
                    continue
                }
                val result = loadPlugin(context, currentBridge, plugin, forceReload = false)
                if (result.isFailure) {
                    prefs.edit()
                        .putBoolean(ScriptPluginSettings.pluginEnableKey(plugin.id), false)
                        .apply()
                    if (firstFailure == null) {
                        firstFailure = result.exceptionOrNull()
                            ?: IllegalStateException("插件加载失败: ${plugin.name}")
                    }
                }
            }
            firstFailure?.let { throw it }
        }
    }

    @Synchronized
    private fun loadEnabledAppBrandPlugins(
        context: Context,
        currentBridge: ScriptPluginBridge
    ): Result<Unit> {
        return runCatching {
            val prefs = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            if (!prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)) {
                return@runCatching
            }
            for (plugin in listPlugins(context)) {
                if (!supportsProcess(plugin, PROCESS_APPBRAND)) continue
                if (!prefs.getBoolean(
                        ScriptPluginSettings.pluginEnableKey(plugin.id),
                        ScriptPluginSettings.DEFAULT_PLUGIN_ENABLE
                    )
                ) {
                    continue
                }
                val result = loadPlugin(
                    context,
                    currentBridge,
                    plugin,
                    forceReload = false,
                    requireScriptApiReady = false
                )
                if (result.isFailure) {
                    h.Hchat.utils.HLog.e(
                        "$TAG 小程序进程插件加载失败: ${plugin.name} process=$runtimeProcessName " +
                            "${result.exceptionOrNull()?.message}",
                        result.exceptionOrNull()
                    )
                }
            }
        }
    }

    @Synchronized
    private fun loadPlugin(
        context: Context,
        currentBridge: ScriptPluginBridge,
        plugin: ScriptPlugin,
        forceReload: Boolean,
        requireScriptApiReady: Boolean = true
    ): Result<Unit> {
        if (requireScriptApiReady && !isScriptApiReady()) {
            return Result.failure(IllegalStateException("微信联系人数据库尚未就绪，请稍后重试"))
        }
        if (forceReload) unloadPlugin(plugin.id)
        if (loadedPlugins.containsKey(plugin.id)) return Result.success(Unit)
        var pendingWaBridge: ScriptWaBridge? = null
        return runCatching {
            val prefs = HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
            if (!prefs.getBoolean(ScriptPluginSettings.KEY_ENABLE, ScriptPluginSettings.DEFAULT_ENABLE)) {
                throw IllegalStateException("脚本插件总开关未开启")
            }
            val scriptText = runCatching { plugin.mainFile.readText(Charsets.UTF_8) }.getOrElse {
                throw IllegalStateException("读取脚本失败: ${it.message}", it)
            }
            val waBridge = ScriptWaBridge(currentBridge, delayOnMainThread = runtimeProcess == PROCESS_MAIN)
            pendingWaBridge = waBridge
            waBridge.bindPluginLog(plugin.name, plugin.dir)
            val interpreter = newInterpreter(currentBridge, plugin, waBridge)
            withInterpreterLock(interpreter) {
                interpreter.source(plugin.mainFile.absolutePath)
            }
            callLifecycle(interpreter, "onLoad")
            val interpreterFlags = detectCallbacks(interpreter)
            val sourceFlags = detectCallbacks(scriptText)
            val callbackFlags = sourceFlags
                .merge(interpreterFlags)
                .copy(
                    hasVideoDownload = interpreterFlags.hasVideoDownload ||
                        sourceFlags.hasVideoDownload,
                    hasFinderMediaDownload = interpreterFlags.hasFinderMediaDownload ||
                        sourceFlags.hasFinderMediaDownload
                )
            loadedPlugins[plugin.id] = LoadedPlugin(
                plugin,
                interpreter,
                waBridge,
                callbackFlags.hasSendButton,
                callbackFlags.hasLongSendButton,
                callbackFlags.hasHandleMsg,
                callbackFlags.hasOpenSettings,
                callbackFlags.hasMemberChange,
                callbackFlags.hasNewFriend,
                callbackFlags.hasProtobufPacket,
                callbackFlags.hasImageDownload,
                callbackFlags.hasVideoDownload,
                callbackFlags.hasFinderMediaDownload
            )
            updateProtobufPacketListener()
            // Async entry scripts can finish between initial detection and plugin registration.
            refreshCallbacks(plugin.id, interpreter)
            notifyPluginCatalogChanged()
        }.onFailure {
            pendingWaBridge?.dispose()
            loadedPlugins.remove(plugin.id)
            if (!hasHandleMsgCallbacks()) ScriptMessageHook.clearPendingMessages()
            updateProtobufPacketListener()
            currentBridge.unhookPlugin(plugin.id)
            h.Hchat.utils.HLog.e("$TAG 插件加载失败: ${plugin.name} ${it.message}", it)
            writePluginLoadError(plugin, it)
            notifyPluginCatalogChanged()
        }
    }

    @Synchronized
    private fun unloadPlugin(pluginId: String): Result<Unit> {
        cancelSnsPrepareTasks(pluginId)
        val loaded = loadedPlugins.remove(pluginId) ?: return Result.success(Unit)
        loaded.waBridge.dispose()
        if (!hasHandleMsgCallbacks()) ScriptMessageHook.clearPendingMessages()
        updateProtobufPacketListener()
        runCatching {
            callLifecycle(loaded.interpreter, "onUnload")
        }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 插件卸载回调失败: ${loaded.plugin.name} ${it.message}", it)
        }
        bridge?.unhookPlugin(pluginId)
        notifyPluginCatalogChanged()
        return Result.success(Unit)
    }

    private fun cancelSnsPrepareTasks(pluginId: String) {
        val prefix = "$pluginId:"
        snsPrepareCancellations.forEach { (taskId, canceled) ->
            if (taskId.startsWith(prefix)) canceled.set(true)
        }
    }

    @Synchronized
    private fun unloadAllPlugins(): Result<Unit> {
        return runCatching {
            val ids = loadedPlugins.keys().toList()
            for (id in ids) unloadPlugin(id).getOrThrow()
        }
    }

    private fun currentBridge(context: Context): ScriptPluginBridge {
        return bridge ?: ScriptPluginBridge(
            context.applicationContext ?: context,
            context.classLoader,
            scriptDir(context)
        ).also { bridge = it }
    }

    private fun newInterpreter(
        currentBridge: ScriptPluginBridge,
        plugin: ScriptPlugin,
        waBridge: ScriptWaBridge
    ): Interpreter {
        val pluginDir = plugin.dir
        val cacheDir = File(currentBridge.scriptDir.parentFile ?: currentBridge.scriptDir, "Cache")
        val audioBridge = ScriptAudioBridge(currentBridge)
        val version = runCatching { WeChatApis.version()?.current() }.getOrNull()
            ?: runCatching {
                WeChatVersionApi.build(currentBridge.hostContext, currentBridge.classLoader)
            }.getOrNull()
        val hostVerName = version?.versionName.orEmpty()
        val hostVerCode = version?.versionCode ?: 0L
        val hostVerClient = version?.clientVersion.orEmpty()
        return Interpreter().apply {
            addClassLoader(currentBridge.classLoader)
            set("context", currentBridge.hostContext)
            set("hostContext", currentBridge.hostContext)
            set("classLoader", currentBridge.classLoader)
            set("scriptDir", currentBridge.scriptDir.absolutePath)
            set("scriptDirFile", currentBridge.scriptDir)
            set("pluginDir", pluginDir.absolutePath)
            set("pluginDirFile", pluginDir)
            set("cacheDir", cacheDir.absolutePath)
            set("cacheDirFile", cacheDir)
            set("pluginId", plugin.id)
            set("pluginName", plugin.name)
            set("pluginAuthor", plugin.author)
            set("pluginVersion", plugin.version)
            set("pluginUpdateTime", plugin.updateTime)
            set("processName", runtimeProcessName)
            set("pluginProcess", runtimeProcess)
            set("isMainProcess", runtimeProcess == PROCESS_MAIN)
            set("isAppBrandProcess", runtimeProcess == PROCESS_APPBRAND)
            set("hostVerName", hostVerName)
            set("hostVerCode", hostVerCode)
            set("hostVerClient", hostVerClient)
            set("moduleVer", BuildConfig.VERSION_NAME)
            set("bridge", currentBridge)
            set("wa", waBridge)
            set("waBridge", waBridge)
            set("audio", audioBridge)
            set("audioBridge", audioBridge)
            set("http", waBridge)
            set("httpClient", waBridge)
            set("__hchat_runtime", ScriptPluginRuntime)
            set("__hchat_interpreter", this)
            set("apis", currentBridge.apis)
            set("dexKit", currentBridge.dexKit)
            set("dexKitBridge", currentBridge.dexKit?.bridge())
            set("dexFinder", currentBridge.dexKit?.holder()?.dexFinder)
            set("dexBridgeHolder", currentBridge.dexKit?.holder())
            set("WeChatApisClass", WeChatApis::class.java)
            set("XposedBridgeClass", XposedBridge::class.java)
            set("XposedHelpersClass", XposedHelpers::class.java)
            set("XC_MethodHookClass", XC_MethodHook::class.java)
            set("DexKitBridgeClass", DexKitBridge::class.java)
            set("DexFinderClass", DexFinder::class.java)
            set("DexBridgeHolderClass", DexBridgeHolder::class.java)
            set("KavaReflectorClass", KavaReflector::class.java)
            set("ScriptAudioBridgeClass", ScriptAudioBridge::class.java)
            set("SilkCodecClass", SilkCodec::class.java)
            set("AacCodecClass", AacCodec::class.java)
            set("ConversionClass", Conversion::class.java)
            set("FieldClass", Field::class.java)
            set("MethodClass", java.lang.reflect.Method::class.java)
            set("ConstructorClass", Constructor::class.java)
            set("startedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            eval(
                """
                import de.robv.android.xposed.XC_MethodHook;
                import de.robv.android.xposed.XposedBridge;
                import de.robv.android.xposed.XposedHelpers;
                import h.Hchat.dexkit.DexBridgeHolder;
                import h.Hchat.dexkit.DexFinder;
                import h.Hchat.hooks.api.core.WeChatApis;
                import h.Hchat.hooks.items.script.ScriptDexKitBridge;
                import h.Hchat.hooks.items.script.ScriptPluginBridge;
                import h.Hchat.hooks.items.script.ScriptAudioBridge;
                import h.Hchat.hooks.items.script.ScriptWaBridge;
                import h.Hchat.hooks.api.model.ContactLabelBean;
                import h.Hchat.utils.KavaReflector;
                import java.io.File;
                import java.io.InputStream;
                import java.lang.reflect.Constructor;
                import java.lang.reflect.Field;
                import org.luckypray.dexkit.DexKitBridge;
                import java.lang.reflect.Member;
                import java.lang.reflect.Method;
                import java.util.Map;
                import java.util.List;
                import java.util.Set;
                import java.util.function.Consumer;
                import java.util.function.Function;
                import android.content.ContentValues;
                import android.database.Cursor;
                import android.view.View;
                import me.hd.wauxv.data.bean.MsgInfoBean;
                import me.hd.wauxv.plugin.api.callback.PluginCallBack;
                import me.yun.silk.AacCodec;
                import me.yun.silk.SilkCodec;
                import me.yun.silk.utils.Conversion;
                void log(Object msg) { bridge.log(pluginName, pluginDirFile, msg); }
                void toast(Object msg) { bridge.toast(pluginName, msg); }
                boolean showModuleDialog(String title, String message) { return bridge.showModuleDialog(title, message); }
                boolean showModuleDialog(String title, String message, String position) { return bridge.showModuleDialog(title, message, position); }
                boolean showModuleConfirmDialog(String title, String message, Consumer callback) { return bridge.showModuleConfirmDialogForPlugin(pluginId, title, message, callback); }
                boolean showModuleConfirmDialog(String title, String message, String position, Consumer callback) { return bridge.showModuleConfirmDialogForPlugin(pluginId, title, message, position, callback); }
                boolean showModuleInputDialog(String title, String summary, String initialValue, String placeholder, Consumer callback) { return bridge.showModuleInputDialogForPlugin(pluginId, title, summary, initialValue, placeholder, callback); }
                boolean showModuleInputDialog(String title, String summary, String initialValue, String placeholder, String position, Consumer callback) { return bridge.showModuleInputDialogForPlugin(pluginId, title, summary, initialValue, placeholder, position, callback); }
                boolean showModuleChoiceDialog(String title, String summary, List choices, Consumer callback) { return bridge.showModuleChoiceDialogForPlugin(pluginId, title, summary, choices, callback); }
                boolean showModuleChoiceDialog(String title, String summary, List choices, String position, Consumer callback) { return bridge.showModuleChoiceDialogForPlugin(pluginId, title, summary, choices, position, callback); }
                boolean showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, Consumer callback) { return bridge.showModuleMultiChoiceDialogForPlugin(pluginId, title, summary, choices, initialSelected, callback); }
                boolean showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, String position, Consumer callback) { return bridge.showModuleMultiChoiceDialogForPlugin(pluginId, title, summary, choices, initialSelected, position, callback); }
                Object applyModuleFloatingGlassBar(View bottomBar) { return bridge.applyModuleFloatingGlassBar(pluginId, bottomBar); }
                Object applyModuleFloatingGlassBar(View bottomBar, Map options) { return bridge.applyModuleFloatingGlassBar(pluginId, bottomBar, options); }
                Object registerPlusMenu(String title, String iconPath, Consumer callback) { return bridge.registerPlusMenu(pluginId, pluginDirFile, title, iconPath, false, callback); }
                Object registerPlusMenu(String title, String iconPath, boolean front, Consumer callback) { return bridge.registerPlusMenu(pluginId, pluginDirFile, title, iconPath, front, callback); }
                Object registerPlusMenu(String title, Consumer callback) { return bridge.registerPlusMenu(pluginId, pluginDirFile, title, null, false, callback); }
                Object registerPlusMenu(String title, boolean front, Consumer callback) { return bridge.registerPlusMenu(pluginId, pluginDirFile, title, null, front, callback); }
                Object registerMessageMenu(String title, String iconPath, Consumer callback) { return bridge.registerMessageMenu(pluginId, pluginDirFile, title, iconPath, false, callback); }
                Object registerMessageMenu(String title, String iconPath, boolean front, Consumer callback) { return bridge.registerMessageMenu(pluginId, pluginDirFile, title, iconPath, front, callback); }
                Object registerMessageMenu(String title, Consumer callback) { return bridge.registerMessageMenu(pluginId, pluginDirFile, title, null, false, callback); }
                Object registerMessageMenu(String title, boolean front, Consumer callback) { return bridge.registerMessageMenu(pluginId, pluginDirFile, title, null, front, callback); }
                void removeMenu(Object handle) { bridge.removeMenu(handle); }
                String getString(String key, String __hchat_default_string) { return bridge.getString(pluginDirFile, key, __hchat_default_string); }
                Set getStringSet(String key, Set __hchat_default_set) { return bridge.getStringSet(pluginDirFile, key, __hchat_default_set); }
                boolean getBoolean(String key, boolean __hchat_default_boolean) { return bridge.getBoolean(pluginDirFile, key, __hchat_default_boolean); }
                int getInt(String key, int __hchat_default_int) { return bridge.getInt(pluginDirFile, key, __hchat_default_int); }
                float getFloat(String key, float __hchat_default_float) { return bridge.getFloat(pluginDirFile, key, __hchat_default_float); }
                long getLong(String key, long __hchat_default_long) { return bridge.getLong(pluginDirFile, key, __hchat_default_long); }
                void putString(String key, String value) { bridge.putString(pluginDirFile, key, value); }
                void putStringSet(String key, Set value) { bridge.putStringSet(pluginDirFile, key, value); }
                void putBoolean(String key, boolean value) { bridge.putBoolean(pluginDirFile, key, value); }
                void putInt(String key, int value) { bridge.putInt(pluginDirFile, key, value); }
                void putFloat(String key, float value) { bridge.putFloat(pluginDirFile, key, value); }
                void putLong(String key, long value) { bridge.putLong(pluginDirFile, key, value); }
                Class findClass(String className) { return bridge.findClass(className); }
                Object findClassList(String usingString) { return dexKit == null ? new java.util.ArrayList() : dexKit.findClassList(usingString); }
                Object findClassList(String[] usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findClassList(usingStrings); }
                Object findClassList(Object[] usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findClassList(usingStrings); }
                Object findClassList(List usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findClassList(usingStrings); }
                Object findClassList(Object usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findClassList(usingStrings); }
                Object findMemberList(String usingString) { return dexKit == null ? new java.util.ArrayList() : dexKit.findMemberList(usingString); }
                Object findMemberList(String[] usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findMemberList(usingStrings); }
                Object findMemberList(Object[] usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findMemberList(usingStrings); }
                Object findMemberList(List usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findMemberList(usingStrings); }
                Object findMemberList(Object usingStrings) { return dexKit == null ? new java.util.ArrayList() : dexKit.findMemberList(usingStrings); }
                Method firstMethod(Object instance, String methodName) { return bridge.firstMethod(instance, methodName); }
                Method firstMethod(Object instance, String methodName, int paramCount) { return bridge.firstMethod(instance, methodName, paramCount); }
                Constructor firstConstructor(Object instance, int paramCount) { return bridge.firstConstructor(instance, paramCount); }
                Field firstField(Object instance, String fieldName) { return bridge.firstField(instance, fieldName); }
                Object invokeMethod(Object instance, String methodName) { return bridge.invokeMethod(instance, methodName); }
                Object invokeMethod(Object instance, String methodName, Object[] params) { return bridge.invokeMethod(instance, methodName, params); }
                Object invokeMethod(Object instance, String methodName, int paramCount) { return bridge.invokeMethod(instance, methodName, paramCount); }
                Object invokeMethod(Object instance, String methodName, int paramCount, Object[] params) { return bridge.invokeMethod(instance, methodName, paramCount, params); }
                Object createInstance(Object instance, int paramCount) { return bridge.createInstance(instance, paramCount); }
                Object createInstance(Object instance, int paramCount, Object[] params) { return bridge.createInstance(instance, paramCount, params); }
                Object getField(Object instance, String fieldName) { return bridge.getField(instance, fieldName); }
                void setField(Object instance, String fieldName, Object value) { bridge.setField(instance, fieldName, value); }
                Object hookBefore(Member member, Consumer callback) { return bridge.hookBefore(pluginId, member, callback); }
                Object hookAfter(Member member, Consumer callback) { return bridge.hookAfter(pluginId, member, callback); }
                Object hookReplace(Member member, Function callback) { return bridge.hookReplace(pluginId, member, callback); }
                void unhook(Object handle) { bridge.unhook(pluginId, handle); }
                void reloadPlugin() { __hchat_runtime.reloadPluginAsync(hostContext, pluginId); }
                String compileSnapshot(String path) { return __hchat_runtime.compileSnapshot(pluginId, pluginDirFile, __hchat_interpreter, path); }
                Object evalSnapshot(String path) { return __hchat_runtime.evalSnapshot(pluginId, pluginDirFile, __hchat_interpreter, path); }
                Object evalSnapshot(InputStream inputStream) { return __hchat_runtime.evalSnapshot(pluginId, __hchat_interpreter, inputStream); }
                Object evalSnapshot(byte[] data) { return __hchat_runtime.evalSnapshot(pluginId, __hchat_interpreter, data); }
                void eval(String code) { __hchat_runtime.evalCode(pluginId, __hchat_interpreter, code); }
                void loadJava(String path) { __hchat_runtime.loadJava(pluginId, pluginDirFile, __hchat_interpreter, path); }
                void useCallback(String callbackName, String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, callbackName, methodName); }
                void useOnLoad(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onLoad", methodName); }
                void useOnUnload(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onUnload", methodName); }
                void useOpenSettings(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "openSettings", methodName); }
                void useOnClickSendBtn(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onClickSendBtn", methodName); }
                void useOnLongClickSendBtn(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onLongClickSendBtn", methodName); }
                void useOnHandleMsg(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onHandleMsg", methodName); }
                void useOnImageDownload(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onImageDownload", methodName); }
                void useOnVideoDownload(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onVideoDownload", methodName); }
                void useOnFinderMediaDownload(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onFinderMediaDownload", methodName); }
                void useOnMemberChange(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onMemberChange", methodName); }
                void useOnNewFriend(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onNewFriend", methodName); }
                void useOnProtobufPacket(String methodName) { __hchat_runtime.useCallback(pluginId, __hchat_interpreter, "onProtobufPacket", methodName); }
                ClassLoader loadDex(String path) { return __hchat_runtime.loadDex(pluginId, pluginDirFile, __hchat_interpreter, classLoader, path); }
                void loadSo(String path) { __hchat_runtime.loadSo(pluginId, pluginDirFile, classLoader, path); }
                void loadSo(String path, ClassLoader loader) { __hchat_runtime.loadSo(pluginId, pluginDirFile, loader, path); }
                String getLoginWxid() { return wa.getLoginWxid(); }
                String getLoginAlias() { return wa.getLoginAlias(); }
                String getTargetTalker() { return wa.getTargetTalker(); }
                android.app.Activity getTopActivity() { return wa.getTopActivity(); }
                Object getDatabaseApi() { return wa.getDatabaseApi(); }
                Object getOfficialList() { return wa.getOfficialList(); }
                Object getFriendList() { return wa.getFriendList(); }
                Object getFriendListInfo() { return wa.getFriendListInfo(); }
                Object getGroupList() { return wa.getGroupList(); }
                Object getGroupListInfo() { return wa.getGroupListInfo(); }
                Object getGroupMemberListInfo(String groupWxid) { return wa.getGroupMemberListInfo(groupWxid); }
                List getContactLabelList() { return wa.getContactLabelList(); }
                List getContactLabelListInfo() { return wa.getContactLabelListInfo(); }
                List getContactByLabelId(String labelId) { return wa.getContactByLabelId(labelId); }
                List getContactByLabelName(String labelName) { return wa.getContactByLabelName(labelName); }
                String addContactLabel(String labelName) { return wa.addContactLabel(labelName); }
                void modifyContactLabelList(String username, String labelName) { wa.modifyContactLabelList(username, labelName); }
                void modifyContactLabelList(String username, List labelNames) { wa.modifyContactLabelList(username, labelNames); }
                void verifyUser(String wxid, String ticket, int scene) { wa.verifyUser(wxid, ticket, scene); }
                void verifyUser(String wxid, String ticket, int scene, int privacy) { wa.verifyUser(wxid, ticket, scene, privacy); }
                Object getGroupMemberList(String groupWxid) { return wa.getGroupMemberList(groupWxid); }
                int getGroupMemberCount(String groupWxid) { return wa.getGroupMemberCount(groupWxid); }
                String getGroupName(String groupWxid) { return wa.getGroupName(groupWxid); }
                String getChatroomName(String chatroomId) { return wa.getChatroomName(chatroomId); }
                String getGroupRemarkName(String groupWxid) { return wa.getGroupRemarkName(groupWxid); }
                String getGroupMemberName(String groupWxid, String memberWxid) { return wa.getGroupMemberName(groupWxid, memberWxid); }
                String getGroupNickName(String groupWxid, String memberWxid) { return wa.getGroupNickName(groupWxid, memberWxid); }
                String getFriendNickName(String friendWxid) { return wa.getFriendNickName(friendWxid); }
                String getFriendRemarkName(String friendWxid) { return wa.getFriendRemarkName(friendWxid); }
                int getFriendGender(String friendWxid) { return wa.getFriendGender(friendWxid); }
                String getFriendProvince(String friendWxid) { return wa.getFriendProvince(friendWxid); }
                String getFriendCity(String friendWxid) { return wa.getFriendCity(friendWxid); }
                String getFriendRegion(String friendWxid) { return wa.getFriendRegion(friendWxid); }
                String getFriendDisplayName(String friendWxid, String roomId) { return wa.getFriendDisplayName(friendWxid, roomId); }
                String getFriendName(String friendWxid) { return wa.getFriendName(friendWxid); }
                String getFriendName(String friendWxid, String roomId) { return wa.getFriendName(friendWxid, roomId); }
                int getGroupMemberGender(String groupWxid, String memberWxid) { return wa.getGroupMemberGender(groupWxid, memberWxid); }
                String getGroupMemberProvince(String groupWxid, String memberWxid) { return wa.getGroupMemberProvince(groupWxid, memberWxid); }
                String getGroupMemberCity(String groupWxid, String memberWxid) { return wa.getGroupMemberCity(groupWxid, memberWxid); }
                String getGroupMemberRegion(String groupWxid, String memberWxid) { return wa.getGroupMemberRegion(groupWxid, memberWxid); }
                void addChatroomMember(String chatroomId, String addMember) { wa.addChatroomMember(chatroomId, addMember); }
                void addChatroomMember(String chatroomId, List addMemberList) { wa.addChatroomMember(chatroomId, addMemberList); }
                void inviteChatroomMember(String chatroomId, String inviteMember) { wa.inviteChatroomMember(chatroomId, inviteMember); }
                void inviteChatroomMember(String chatroomId, List inviteMemberList) { wa.inviteChatroomMember(chatroomId, inviteMemberList); }
                void delChatroomMember(String chatroomId, String delMember) { wa.delChatroomMember(chatroomId, delMember); }
                void delChatroomMember(String chatroomId, List delMemberList) { wa.delChatroomMember(chatroomId, delMemberList); }
                String getAvatarUrl(String username) { return wa.getAvatarUrl(username); }
                String getAvatarUrl(String username, boolean isBigHeadImg) { return wa.getAvatarUrl(username, isBigHeadImg); }
                void sendText(String talker, String content) { wa.sendText(talker, content); }
                void sendText(String talker, String content, Consumer callback) { wa.sendText(talker, content, callback); }
                boolean sendProtobufPacket(String uri, int cgiId, String json) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, json, null); }
                boolean sendProtobufPacket(String uri, int cgiId, String json, Consumer callback) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, json, callback); }
                boolean sendProtobufPacket(String uri, int cgiId, JSONObject json) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, json == null ? "{}" : json.toString(), null); }
                boolean sendProtobufPacket(String uri, int cgiId, JSONObject json, Consumer callback) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, json == null ? "{}" : json.toString(), callback); }
                boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, String json) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, funcId, routeId, json, null); }
                boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, String json, Consumer callback) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, funcId, routeId, json, callback); }
                boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, JSONObject json) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, funcId, routeId, json == null ? "{}" : json.toString(), null); }
                boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, JSONObject json, Consumer callback) { return __hchat_runtime.sendProtobufPacket(uri, cgiId, funcId, routeId, json == null ? "{}" : json.toString(), callback); }
                void sendQuoteMsg(String talker, long msgId, String content) { wa.sendQuoteMsg(talker, msgId, content); }
                void sendQuoteMsg(String talker, String content, long msgId) { wa.sendQuoteMsg(talker, content, msgId); }
                void revokeMsg(long msgId) { wa.revokeMsg(msgId); }
                void uploadDeviceStep(long step) { wa.uploadDeviceStep(step); }
                Object getSnsPostList() { return wa.getSnsPostList(); }
                Object getSnsPostList(int limit) { return wa.getSnsPostList(limit); }
                Object getSnsPostList(String userName, int limit) { return wa.getSnsPostList(userName, limit); }
                Object getSnsPost(String snsId) { return wa.getSnsPost(snsId); }
                boolean prepareSnsPostMedia(String snsId, Consumer callback) { return __hchat_runtime.prepareSnsPostMedia(pluginId, __hchat_interpreter, snsId, callback); }
                boolean publishSnsPost(Object prepared) { return wa.publishSnsPost(prepared); }
                boolean refreshSnsTimeline() { return wa.refreshSnsTimeline(); }
                void uploadText(String content) { wa.uploadText(content); }
                void uploadText(String content, String sdkId, String sdkAppName) { wa.uploadText(content, sdkId, sdkAppName); }
                void uploadText(JSONObject jsonObj) { wa.uploadText(jsonObj); }
                void uploadTextAndPicList(String content, String picPath) { wa.uploadTextAndPicList(content, picPath); }
                void uploadTextAndPicList(String content, String picPath, String sdkId, String sdkAppName) { wa.uploadTextAndPicList(content, picPath, sdkId, sdkAppName); }
                void uploadTextAndPicList(String content, List picPathList) { wa.uploadTextAndPicList(content, picPathList); }
                void uploadTextAndPicList(String content, List picPathList, String sdkId, String sdkAppName) { wa.uploadTextAndPicList(content, picPathList, sdkId, sdkAppName); }
                void uploadTextAndPicList(JSONObject jsonObj) { wa.uploadTextAndPicList(jsonObj); }
                void uploadLivePhoto(String livePhotoPath) { wa.uploadLivePhoto(livePhotoPath); }
                void uploadLivePhoto(String imagePath, String videoPath) { wa.uploadLivePhoto(imagePath, videoPath); }
                void uploadLivePhoto(JSONObject jsonObj) { wa.uploadLivePhoto(jsonObj); }
                void uploadLivePhotoList(List livePhotoList) { wa.uploadLivePhotoList(livePhotoList); }
                void uploadLivePhotoList(JSONObject jsonObj) { wa.uploadLivePhotoList(jsonObj); }
                void uploadTextAndLivePhoto(String content, String livePhotoPath) { wa.uploadTextAndLivePhoto(content, livePhotoPath); }
                void uploadTextAndLivePhoto(String content, String livePhotoPath, String sdkId, String sdkAppName) { wa.uploadTextAndLivePhoto(content, livePhotoPath, sdkId, sdkAppName); }
                void uploadTextAndLivePhoto(String content, String imagePath, String videoPath) { wa.uploadTextAndLivePhoto(content, imagePath, videoPath); }
                void uploadTextAndLivePhoto(String content, String imagePath, String videoPath, String sdkId, String sdkAppName) { wa.uploadTextAndLivePhoto(content, imagePath, videoPath, sdkId, sdkAppName); }
                void uploadTextAndLivePhoto(JSONObject jsonObj) { wa.uploadTextAndLivePhoto(jsonObj); }
                void uploadTextAndLivePhotoList(String content, List livePhotoList) { wa.uploadTextAndLivePhotoList(content, livePhotoList); }
                void uploadTextAndLivePhotoList(String content, List livePhotoList, String sdkId, String sdkAppName) { wa.uploadTextAndLivePhotoList(content, livePhotoList, sdkId, sdkAppName); }
                void uploadTextAndLivePhotoList(JSONObject jsonObj) { wa.uploadTextAndLivePhotoList(jsonObj); }
                void uploadVideo(String videoPath) { wa.uploadVideo(videoPath); }
                void uploadVideo(JSONObject jsonObj) { wa.uploadVideo(jsonObj); }
                void uploadTextAndVideo(String content, String videoPath) { wa.uploadTextAndVideo(content, videoPath); }
                void uploadTextAndVideo(String content, String videoPath, String sdkId, String sdkAppName) { wa.uploadTextAndVideo(content, videoPath, sdkId, sdkAppName); }
                void uploadTextAndVideo(JSONObject jsonObj) { wa.uploadTextAndVideo(jsonObj); }
                void sendPat(String talker, String pattedUser) { wa.sendPat(talker, pattedUser); }
                void sendShareCard(String talker, String wxid) { wa.sendShareCard(talker, wxid); }
                boolean sendImage(String talker, String sendPath) { return wa.sendImage(talker, sendPath); }
                boolean sendImage(String talker, String sendPath, String appId) { return wa.sendImage(talker, sendPath, appId); }
                boolean sendOriginalImage(String talker, String sendPath) { return wa.sendOriginalImage(talker, sendPath); }
                boolean sendVoice(String talker, String sendPath) { return wa.sendVoice(talker, sendPath); }
                boolean sendVoice(String talker, String sendPath, int duration) { return wa.sendVoice(talker, sendPath, duration); }
                boolean sendVideo(String talker, String sendPath) { return wa.sendVideo(talker, sendPath); }
                boolean sendEmoji(String talker, String sendPath) { return wa.sendEmoji(talker, sendPath); }
                boolean sendFile(String talker, String sendPath) { return wa.sendFile(talker, sendPath); }
                boolean sendFile(String talker, String sendPath, String title) { return wa.sendFile(talker, sendPath, title); }
                Object getFavoriteList(int limit) { return wa.getFavoriteList(limit); }
                Object getFavorite(long localId) { return wa.getFavorite(localId); }
                boolean sendFavorite(String talker, long localId) { return wa.sendFavorite(talker, localId); }
                boolean sendFavorite(String talker, String localId) { return wa.sendFavorite(talker, localId); }
                void sendMediaMsg(String talker, Object mediaMessage, String appId) { wa.sendMediaMsg(talker, mediaMessage, appId); }
                void shareFile(String talker, String title, String filePath, String appId) { wa.shareFile(talker, title, filePath, appId); }
                void shareMiniProgram(String talker, String title, String description, String userName, String path, byte[] thumbData, String appId) { wa.shareMiniProgram(talker, title, description, userName, path, thumbData, appId); }
                void sendAppBrandMsg(String talker, String title, String pagePath, String ghName) { wa.sendAppBrandMsg(talker, title, pagePath, ghName); }
                void shareMusic(String talker, String title, String description, String musicUrl, String musicDataUrl, byte[] thumbData, String appId) { wa.shareMusic(talker, title, description, musicUrl, musicDataUrl, thumbData, appId); }
                void shareMusicVideo(String talker, String title, String description, String musicUrl, String musicDataUrl, String singerName, int duration, String songLyric, byte[] thumbData, String appId) { wa.shareMusicVideo(talker, title, description, musicUrl, musicDataUrl, singerName, duration, songLyric, thumbData, appId); }
                void shareText(String talker, String text, String appId) { wa.shareText(talker, text, appId); }
                void shareVideo(String talker, String title, String description, String videoUrl, byte[] thumbData, String appId) { wa.shareVideo(talker, title, description, videoUrl, thumbData, appId); }
                void shareWebpage(String talker, String title, String description, String webpageUrl, byte[] thumbData, String appId) { wa.shareWebpage(talker, title, description, webpageUrl, thumbData, appId); }
                void sendXmlMsg(String talker, String content) { wa.sendXmlMsg(talker, content); }
                void sendLocation(String talker, String poiName, String label, String x, String y, String scale) { wa.sendLocation(talker, poiName, label, x, y, scale); }
                void sendLocation(String talker, JSONObject jsonObj) { wa.sendLocation(talker, jsonObj); }
                long insertSystemMsg(String talker, String content, long createTime) { return wa.insertSystemMsg(talker, content, createTime); }
                List queryHistoryMsg(String talker, long startTime, int count) { return wa.queryHistoryMsg(talker, startTime, count); }
                int getUnreadCount(String talker) { return wa.getUnreadCount(talker); }
                boolean deleteConversation(String talker) { return wa.deleteConversation(talker); }
                int getAllUnreadCount() { return wa.getAllUnreadCount(); }
                boolean clearUnread(String talker) { return wa.clearUnread(talker); }
                boolean clearAllUnread() { return wa.clearAllUnread(); }
                void delay(long millis, Runnable action) { wa.delay(millis, action); }
                void notify(String title, String text) { wa.notify(title, text); }
                int getFileType(String filePath) { return audio.getFileType(filePath); }
                int mp3ToSilk(String mp3Path, String silkPath) { return audio.mp3ToSilk(mp3Path, silkPath); }
                int mp3ToSilk(String mp3Path, String silkPath, int hz) { return audio.mp3ToSilk(mp3Path, silkPath, hz); }
                int wavToSilk(String wavPath, String silkPath, int hz) { return audio.wavToSilk(wavPath, silkPath, hz); }
                int flacToSilk(String flacPath, String silkPath, int hz) { return audio.flacToSilk(flacPath, silkPath, hz); }
                int oggToSilk(String oggPath, String silkPath, int hz) { return audio.oggToSilk(oggPath, silkPath, hz); }
                int pcmToSilk(String pcmPath, String silkPath, int hz, int pcmHz, int channels) { return audio.pcmToSilk(pcmPath, silkPath, hz, pcmHz, channels); }
                int autoToSilk(String audioPath, String silkPath, int hz) { return audio.autoToSilk(audioPath, silkPath, hz); }
                int silkToMp3(String silkPath, String mp3Path) { return audio.silkToMp3(silkPath, mp3Path); }
                int silkToMp3(String silkPath, String mp3Path, int hz) { return audio.silkToMp3(silkPath, mp3Path, hz); }
                int silkToPcm(String silkPath, String pcmPath, int hz) { return audio.silkToPcm(silkPath, pcmPath, hz); }
                int mp3ToPcm(String mp3Path, String pcmPath) { return audio.mp3ToPcm(mp3Path, pcmPath); }
                int wavToPcm(String wavPath, String pcmPath) { return audio.wavToPcm(wavPath, pcmPath); }
                int flacToPcm(String flacPath, String pcmPath) { return audio.flacToPcm(flacPath, pcmPath); }
                int oggToPcm(String oggPath, String pcmPath) { return audio.oggToPcm(oggPath, pcmPath); }
                int autoToPcm(String audioPath, String pcmPath) { return audio.autoToPcm(audioPath, pcmPath); }
                Map getAudioInfo(String filePath) { return audio.getAudioInfo(filePath); }
                int decodeAacFile(String aacPath, String pcmPath) { return audio.decodeAacFile(aacPath, pcmPath); }
                int encodePcmToAac(String pcmPath, String aacPath, int sampleRate, int channels) { return audio.encodePcmToAac(pcmPath, aacPath, sampleRate, channels); }
                int encodePcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels) { return audio.encodePcmToM4a(pcmPath, m4aPath, sampleRate, channels); }
                int mp4ToSilk(String mp4Path, String silkPath, int hz) { return audio.mp4ToSilk(mp4Path, silkPath, hz); }
                int silkToM4a(String silkPath, String m4aPath, int hz) { return audio.silkToM4a(silkPath, m4aPath, hz); }
                int mp4ToM4a(String mp4Path, String m4aPath, int hz) { return audio.mp4ToM4a(mp4Path, m4aPath, hz); }
                int mp4ToAac(String mp4Path, String aacPath, int hz) { return audio.mp4ToAac(mp4Path, aacPath, hz); }
                int m4aToSilk(String m4aPath, String silkPath, int hz) { return audio.m4aToSilk(m4aPath, silkPath, hz); }
                int aacToSilk(String aacPath, String silkPath, int hz) { return audio.aacToSilk(aacPath, silkPath, hz); }
                int m4aToAac(String m4aPath, String aacPath, int hz) { return audio.m4aToAac(m4aPath, aacPath, hz); }
                int m4aToM4a(String m4aPath, String m4aPathOut, int hz) { return audio.m4aToM4a(m4aPath, m4aPathOut, hz); }
                int autoToAac(String inputPath, String aacPath, int hz) { return audio.autoToAac(inputPath, aacPath, hz); }
                int autoToM4a(String inputPath, String m4aPath, int hz) { return audio.autoToM4a(inputPath, m4aPath, hz); }
                int autoAacToSilk(String inputPath, String silkPath, int hz) { return audio.autoAacToSilk(inputPath, silkPath, hz); }
                int silkToAac(String silkPath, String aacPath, int hz) { return audio.silkToAac(silkPath, aacPath, hz); }
                int aacToPcm(String aacPath, String pcmPath) { return audio.aacToPcm(aacPath, pcmPath); }
                int pcmToAac(String pcmPath, String aacPath, int sampleRate, int channels) { return audio.pcmToAac(pcmPath, aacPath, sampleRate, channels); }
                int pcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels) { return audio.pcmToM4a(pcmPath, m4aPath, sampleRate, channels); }
                int m4aToPcm(String m4aPath, String pcmPath) { return audio.m4aToPcm(m4aPath, pcmPath); }
                int decodeM4aFile(String m4aPath, String pcmPath) { return audio.decodeM4aFile(m4aPath, pcmPath); }
                long getDuration(String filePath) { return audio.getDuration(filePath); }
                long getDurationLimited(String filePath) { return audio.getDurationLimited(filePath); }
                String getErrorMessage(int code) { return audio.getErrorMessage(code); }
                void startTransform(int type, String inputPath, String outputPath, int sampleRate, Consumer callback) { audio.startTransform(type, inputPath, outputPath, sampleRate, callback); }
                void get(String url, Map headerMap, Consumer callback) { wa.get(url, headerMap, callback); }
                void get(String url, Map headerMap, long timeout, Consumer callback) { wa.get(url, headerMap, timeout, callback); }
                void get(String url, Map headerMap, PluginCallBack.HttpCallback callback) {
                    wa.get(url, headerMap, new Consumer() {
                        public void accept(Object body) {
                            if (body != null) callback.onSuccess(200, String.valueOf(body));
                            else callback.onError(new Exception("GET failed: " + url));
                        }
                    });
                }
                void get(String url, Map headerMap, long timeout, PluginCallBack.HttpCallback callback) {
                    wa.get(url, headerMap, timeout, new Consumer() {
                        public void accept(Object body) {
                            if (body != null) callback.onSuccess(200, String.valueOf(body));
                            else callback.onError(new Exception("GET failed: " + url));
                        }
                    });
                }
                void post(String url, Map paramMap, Map headerMap, Consumer callback) { wa.post(url, paramMap, headerMap, callback); }
                void post(String url, Map paramMap, Map headerMap, long timeout, Consumer callback) { wa.post(url, paramMap, headerMap, timeout, callback); }
                void post(String url, Map paramMap, Map headerMap, PluginCallBack.HttpCallback callback) {
                    wa.post(url, paramMap, headerMap, new Consumer() {
                        public void accept(Object body) {
                            if (body != null) callback.onSuccess(200, String.valueOf(body));
                            else callback.onError(new Exception("POST failed: " + url));
                        }
                    });
                }
                void post(String url, Map paramMap, Map headerMap, long timeout, PluginCallBack.HttpCallback callback) {
                    wa.post(url, paramMap, headerMap, timeout, new Consumer() {
                        public void accept(Object body) {
                            if (body != null) callback.onSuccess(200, String.valueOf(body));
                            else callback.onError(new Exception("POST failed: " + url));
                        }
                    });
                }
                void download(String url, String path, Map headerMap, Consumer callback) { wa.download(url, path, headerMap, callback); }
                void download(String url, String path, Map headerMap, long timeout, Consumer callback) { wa.download(url, path, headerMap, timeout, callback); }
                void download(String url, String path, Map headerMap, PluginCallBack.DownloadCallback callback) {
                    wa.download(url, path, headerMap, new Consumer() {
                        public void accept(Object file) {
                            if (file instanceof File) callback.onSuccess((File) file);
                            else callback.onError(new Exception("Download failed: " + url));
                        }
                    });
                }
                void download(String url, String path, Map headerMap, long timeout, PluginCallBack.DownloadCallback callback) {
                    wa.download(url, path, headerMap, timeout, new Consumer() {
                        public void accept(Object file) {
                            if (file instanceof File) callback.onSuccess((File) file);
                            else callback.onError(new Exception("Download failed: " + url));
                        }
                    });
                }
                void downloadImage(String url, Consumer callback) { wa.downloadImage(url, callback); }
                void downloadImage(String url, String fileName, Consumer callback) { wa.downloadImage(url, fileName, callback); }
                void downloadImg(String md5, String cdnUrl, String aesKey, String savePath) { wa.downloadImg(md5, cdnUrl, aesKey, savePath); }
                void downloadImg(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback) { wa.downloadImg(md5, cdnUrl, aesKey, savePath, callback); }
                void downloadImg(Object imageMsg, String savePath) { wa.downloadImg(imageMsg, savePath); }
                void downloadImg(Object imageMsg, String savePath, PluginCallBack.DownloadCallback callback) { wa.downloadImg(imageMsg, savePath, callback); }
                void downloadImages(List urlList, Consumer callback) { wa.downloadImages(urlList, callback); }
                void downloadImages(List urlList, String prefix, Consumer callback) { wa.downloadImages(urlList, prefix, callback); }
                void downloadVideo(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback) { wa.downloadVideo(md5, cdnUrl, aesKey, savePath, callback); }
                void downloadVideo(Object videoMessage, String savePath, PluginCallBack.DownloadCallback callback) { wa.downloadVideo(videoMessage, savePath, callback); }
                void downloadFinderMedia(Object finderFeedOrMessage, String savePath, PluginCallBack.DownloadCallback callback) { wa.downloadFinderMedia(finderFeedOrMessage, savePath, callback); }
                void downloadFinderMedia(Object finderFeedOrMessage, int mediaIndex, String savePath, PluginCallBack.DownloadCallback callback) { wa.downloadFinderMedia(finderFeedOrMessage, mediaIndex, savePath, callback); }
            """.trimIndent()
            )
        }
    }

    private fun readPluginMeta(pluginDir: File): Properties {
        val properties = Properties()
        val info = File(pluginDir, "info.prop")
        if (!info.isFile) return properties
        runCatching {
            info.reader(Charsets.UTF_8).use { properties.load(it) }
        }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 读取插件信息失败: ${pluginDir.name} ${it.message}", it)
        }
        return properties
    }

    private fun parseProcessScope(pluginId: String, rawValue: String?): Set<String> {
        if (rawValue.isNullOrBlank()) return setOf(PROCESS_MAIN)
        val result = LinkedHashSet<String>()
        val values = rawValue.lowercase(Locale.US)
            .split(Regex("[,;|\\s]+"))
            .filter { it.isNotBlank() }
        val invalid = values.filterNot {
            it == PROCESS_MAIN || it == PROCESS_APPBRAND || it == "all"
        }
        if (invalid.isNotEmpty()) {
            val warningKey = "$pluginId:${rawValue.trim()}"
            if (invalidProcessWarnings.add(warningKey)) {
                h.Hchat.utils.HLog.e(
                    "$TAG 插件进程配置无效，已拒绝加载: plugin=$pluginId process=${rawValue.trim()}"
                )
            }
            return emptySet()
        }
        values.forEach { value ->
            when (value) {
                PROCESS_MAIN -> result += PROCESS_MAIN
                PROCESS_APPBRAND -> result += PROCESS_APPBRAND
                "all" -> {
                    result += PROCESS_MAIN
                    result += PROCESS_APPBRAND
                }
            }
        }
        if (result.isEmpty()) return setOf(PROCESS_MAIN)
        return result
    }

    private fun supportsProcess(plugin: ScriptPlugin, process: String): Boolean {
        return process in plugin.processScope
    }

    private fun processScopedCacheRoot(context: Context, name: String): File {
        val root = File(context.codeCacheDir, name)
        if (runtimeProcess != PROCESS_APPBRAND) return root
        val processKey = safeFileName(runtimeProcessName.ifBlank { PROCESS_APPBRAND })
        return File(root, processKey)
    }

    private fun callLifecycle(interpreter: Interpreter, name: String) {
        runCatching {
            withInterpreterLock(interpreter) {
                interpreter.eval("$name();")
            }
        }.onFailure {
            val message = it.message.orEmpty()
            if (!message.contains("Command not found", ignoreCase = true)
                && !message.contains("undefined", ignoreCase = true)
                && !message.contains("not found", ignoreCase = true)
            ) {
                throw it
            }
        }
    }

    private fun interpreterLock(interpreter: Interpreter): ReentrantLock {
        return synchronized(interpreterLocks) {
            interpreterLocks.getOrPut(interpreter) { ReentrantLock() }
        }
    }

    private inline fun <T> withInterpreterLock(interpreter: Interpreter, block: () -> T): T {
        val lock = interpreterLock(interpreter)
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private fun updateProtobufPacketListener() {
        synchronized(protobufListenerLock) {
            val required = loadedPlugins.values.any { it.hasProtobufPacketCallback }
            if (required) {
                if (!protobufListenerRegistered.compareAndSet(false, true)) return
                runCatching {
                    ProtobufPacketRuntime.registerListener(protobufPacketListener)
                }.onFailure {
                    protobufListenerRegistered.set(false)
                    h.Hchat.utils.HLog.e("$TAG 注册数据包监听器失败: ${it.message}", it)
                }
                return
            }
            if (!protobufListenerRegistered.compareAndSet(true, false)) return
            runCatching {
                ProtobufPacketRuntime.unregisterListener(protobufPacketListener)
            }.onFailure {
                protobufListenerRegistered.set(true)
                h.Hchat.utils.HLog.e("$TAG 注销数据包监听器失败: ${it.message}", it)
            }
        }
    }

    private fun deliverProtobufSendResult(
        callback: Consumer<SendResult>?,
        success: Boolean,
        message: String?
    ) {
        if (callback == null) return
        runCatching {
            callback.accept(SendResult(success, message.orEmpty()))
        }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 数据包发送结果回调失败: ${it.message}", it)
        }
    }

    private fun logDroppedProtobufPacket() {
        protobufDroppedPacketCount.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val previous = protobufDropLogAt.get()
        if (previous != 0L && now - previous < PROTOBUF_DROP_LOG_COOLDOWN_MS) return
        if (!protobufDropLogAt.compareAndSet(previous, now)) return
        val dropped = protobufDroppedPacketCount.getAndSet(0L)
        h.Hchat.utils.HLog.e("$TAG 数据包回调队列已满，已丢弃 $dropped 个事件")
    }

    private fun logDroppedImageDownload() {
        imageDownloadDroppedCount.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val previous = imageDownloadDropLogAt.get()
        if (previous != 0L && now - previous < IMAGE_DOWNLOAD_DROP_LOG_COOLDOWN_MS) return
        if (!imageDownloadDropLogAt.compareAndSet(previous, now)) return
        val dropped = imageDownloadDroppedCount.getAndSet(0L)
        h.Hchat.utils.HLog.e("$TAG 图片下载回调队列已满，已丢弃 $dropped 个事件")
    }

    private fun logDroppedVideoDownload() {
        videoDownloadDroppedCount.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val previous = videoDownloadDropLogAt.get()
        if (previous != 0L && now - previous < VIDEO_DOWNLOAD_DROP_LOG_COOLDOWN_MS) return
        if (!videoDownloadDropLogAt.compareAndSet(previous, now)) return
        val dropped = videoDownloadDroppedCount.getAndSet(0L)
        h.Hchat.utils.HLog.e("$TAG 视频下载回调队列已满，已丢弃 $dropped 个事件")
    }

    private fun logDroppedFinderMediaDownload() {
        finderMediaDownloadDroppedCount.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val previous = finderMediaDownloadDropLogAt.get()
        if (previous != 0L && now - previous < FINDER_MEDIA_DOWNLOAD_DROP_LOG_COOLDOWN_MS) return
        if (!finderMediaDownloadDropLogAt.compareAndSet(previous, now)) return
        val dropped = finderMediaDownloadDroppedCount.getAndSet(0L)
        h.Hchat.utils.HLog.e("$TAG 视频号媒体下载回调队列已满，已丢弃 $dropped 个事件")
    }

    private fun logBusySendButtonPlugin(loaded: LoadedPlugin, longClick: Boolean) {
        val diagnosticKey = if (longClick) "busy_long" else "busy"
        val label = if (longClick) "长按发送按钮" else "发送按钮"
        logSendButtonDiagnostic(
            "$diagnosticKey:${loaded.plugin.id}",
            "${label}跳过忙碌插件: ${loaded.plugin.name}"
        )
    }

    private fun logSendButtonDiagnostic(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        var shouldLog = false
        sendButtonDiagnosticLogAt.compute(key) { _, previous ->
            if (previous == null || now - previous >= SEND_BUTTON_DIAGNOSTIC_LOG_COOLDOWN_MS) {
                shouldLog = true
                now
            } else {
                previous
            }
        }
        if (shouldLog) h.Hchat.utils.HLog.e("$TAG $message")
    }

    private fun writePluginLoadError(plugin: ScriptPlugin, throwable: Throwable) {
        runCatching {
            if (!plugin.dir.isDirectory) plugin.dir.mkdirs()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(plugin.dir, "log.txt").appendText(
                buildString {
                    append('[').append(time).append("] ERROR 插件加载失败")
                    append('\n').append(throwable::class.java.name).append(": ").append(throwable.message.orEmpty())
                    append('\n')
                }
            )
        }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 写入插件加载错误日志失败: ${plugin.name} ${it.message}", it)
        }
    }

    @Synchronized
    private fun startPluginObservers(context: Context) {
        val root = ensureDirs(context)
        if (scriptRootObserver == null) {
            scriptRootObserver = object : FileObserver(
                root.absolutePath,
                CREATE or DELETE or MOVED_TO or MOVED_FROM or DELETE_SELF or MOVE_SELF
            ) {
                override fun onEvent(event: Int, path: String?) {
                    refreshPluginDirObservers(root)
                    notifyPluginCatalogChanged()
                }
            }.also { it.startWatching() }
        }
        refreshPluginDirObservers(root)
    }

    @Synchronized
    private fun refreshPluginDirObservers(root: File) {
        val currentIds = root.listFiles()
            ?.asSequence()
            ?.filter(::isPluginDirectory)
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        val stale = pluginDirObservers.keys.filter { it !in currentIds }
        for (pluginId in stale) {
            pluginDirObservers.remove(pluginId)?.stopWatching()
            reloadTasks.remove(pluginId)?.let { mainHandler.removeCallbacks(it) }
        }
        root.listFiles()
            ?.filter(::isPluginDirectory)
            ?.forEach { dir ->
                if (pluginDirObservers.containsKey(dir.name)) return@forEach
                val observer = object : FileObserver(
                    dir.absolutePath,
                    CLOSE_WRITE or CREATE or DELETE or MOVED_TO or MOVED_FROM or DELETE_SELF or MOVE_SELF
                ) {
                    override fun onEvent(event: Int, path: String?) {
                        val fileName = path?.substringAfterLast('/') ?: ""
                        if (fileName.isEmpty()) {
                            notifyPluginCatalogChanged()
                            return
                        }
                        if (fileName == MAIN_FILE) {
                            notifyPluginCatalogChanged()
                            schedulePluginReload(dir.name)
                            return
                        }
                        if (fileName == INFO_FILE || fileName == README_FILE) {
                            notifyPluginCatalogChanged()
                        }
                    }
                }
                observer.startWatching()
                pluginDirObservers[dir.name] = observer
            }
    }

    private fun isPluginDirectory(file: File): Boolean {
        return file.isDirectory && !AGENT_TRANSACTION_DIRECTORY.matches(file.name)
    }

    private fun schedulePluginReload(pluginId: String) {
        if (!loadedPlugins.containsKey(pluginId)) return
        val context = appContext ?: return
        val task = Runnable {
            reloadTasks.remove(pluginId)
            reloadPluginFromFileChange(context, pluginId)
        }
        reloadTasks.put(pluginId, task)?.let { mainHandler.removeCallbacks(it) }
        mainHandler.postDelayed(task, RELOAD_DEBOUNCE_MS)
    }

    private fun reloadPluginFromFileChange(context: Context, pluginId: String) {
        Thread({
            val result = reloadPlugin(context, pluginId)
            if (result.isFailure) {
                val plugin = listPlugins(context).firstOrNull { it.id == pluginId }
                HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME)
                    .edit()
                    .putBoolean(ScriptPluginSettings.pluginEnableKey(pluginId), false)
                    .apply()
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(
                        context,
                        "加载[${plugin?.displayName ?: "未知"}]失败，已自动关闭",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            notifyPluginCatalogChanged()
        }, "Hchat-Script-AutoReload-$pluginId").start()
    }

    private fun notifyPluginCatalogChanged() {
        if (pluginCatalogListeners.isEmpty()) return
        pluginCatalogListeners.forEach { listener ->
            runCatching { listener() }
        }
    }

    private data class CallbackFlags(
        val hasSendButton: Boolean,
        val hasLongSendButton: Boolean,
        val hasHandleMsg: Boolean,
        val hasOpenSettings: Boolean,
        val hasMemberChange: Boolean,
        val hasNewFriend: Boolean,
        val hasProtobufPacket: Boolean,
        val hasImageDownload: Boolean,
        val hasVideoDownload: Boolean,
        val hasFinderMediaDownload: Boolean
    ) {
        fun merge(other: CallbackFlags): CallbackFlags {
            return CallbackFlags(
                hasSendButton || other.hasSendButton,
                hasLongSendButton || other.hasLongSendButton,
                hasHandleMsg || other.hasHandleMsg,
                hasOpenSettings || other.hasOpenSettings,
                hasMemberChange || other.hasMemberChange,
                hasNewFriend || other.hasNewFriend,
                hasProtobufPacket || other.hasProtobufPacket,
                hasImageDownload || other.hasImageDownload,
                hasVideoDownload || other.hasVideoDownload,
                hasFinderMediaDownload || other.hasFinderMediaDownload
            )
        }
    }

    private fun detectCallbacks(scriptText: String): CallbackFlags {
        val sendButton = scriptHasCallback(scriptText, "onClickSendBtn", "useOnClickSendBtn")
        val longSendButton = scriptHasCallback(
            scriptText,
            "onLongClickSendBtn",
            "useOnLongClickSendBtn"
        )
        val handleMsg = scriptHasCallback(scriptText, "onHandleMsg", "useOnHandleMsg")
        val openSettings = scriptHasCallback(scriptText, "openSettings", "useOpenSettings")
        val memberChange = scriptHasCallback(scriptText, "onMemberChange", "useOnMemberChange")
        val newFriend = scriptHasCallback(scriptText, "onNewFriend", "useOnNewFriend")
        val protobufPacket = scriptHasCallback(scriptText, "onProtobufPacket", "useOnProtobufPacket")
        val imageDownload = scriptHasCallback(scriptText, "onImageDownload", "useOnImageDownload")
        val videoDownload = scriptDeclaresCallback(scriptText, "onVideoDownload") ||
            scriptUsesCallback(scriptText, "onVideoDownload", "useOnVideoDownload")
        val finderMediaDownload = scriptDeclaresCallback(scriptText, "onFinderMediaDownload") ||
            scriptUsesCallback(scriptText, "onFinderMediaDownload", "useOnFinderMediaDownload")
        return CallbackFlags(
            sendButton,
            longSendButton,
            handleMsg,
            openSettings,
            memberChange,
            newFriend,
            protobufPacket,
            imageDownload,
            videoDownload,
            finderMediaDownload
        )
    }

    private fun scriptHasCallback(scriptText: String, callbackName: String, aliasName: String): Boolean {
        return Regex("""\b${Regex.escape(callbackName)}\s*\(""").containsMatchIn(scriptText)
            || Regex("""\b${Regex.escape(aliasName)}\s*\(""").containsMatchIn(scriptText)
            || Regex("""\buseCallback\s*\(\s*["']${Regex.escape(callbackName)}["']\s*,""").containsMatchIn(scriptText)
    }

    private fun scriptDeclaresCallback(scriptText: String, callbackName: String): Boolean {
        val escapedName = Regex.escape(callbackName)
        return Regex(
            """(?m)^\s*(?:(?:public|protected|private|static|final|synchronized)\s+)*""" +
                """(?:void|boolean|byte|short|int|long|float|double|char|String|Object|""" +
                """[A-Za-z_][A-Za-z0-9_.$]*(?:\s*<[^;{}()\n]+>)?(?:\s*\[\])?)""" +
                """\s+$escapedName\s*\("""
        ).containsMatchIn(scriptText)
    }

    private fun scriptUsesCallback(scriptText: String, callbackName: String, aliasName: String): Boolean {
        return Regex("""\b${Regex.escape(aliasName)}\s*\(""").containsMatchIn(scriptText)
            || Regex("""\buseCallback\s*\(\s*["']${Regex.escape(callbackName)}["']\s*,""")
                .containsMatchIn(scriptText)
    }

    private fun detectCallbacks(interpreter: Interpreter): CallbackFlags {
        val methods = runCatching {
            interpreter.nameSpace.methods.map { it.name }.toSet()
        }.getOrDefault(emptySet())
        return CallbackFlags(
            hasSendButton = "onClickSendBtn" in methods,
            hasLongSendButton = "onLongClickSendBtn" in methods,
            hasHandleMsg = "onHandleMsg" in methods,
            hasOpenSettings = "openSettings" in methods,
            hasMemberChange = "onMemberChange" in methods,
            hasNewFriend = "onNewFriend" in methods,
            hasProtobufPacket = "onProtobufPacket" in methods,
            hasImageDownload = "onImageDownload" in methods,
            hasVideoDownload = "onVideoDownload" in methods,
            hasFinderMediaDownload = "onFinderMediaDownload" in methods
        )
    }

    private fun callbackWrapper(callbackName: String, methodName: String): String {
        require(SCRIPT_FUNCTION_NAME.matches(methodName)) { "非法函数名: $methodName" }
        require(callbackName != methodName) { "回调别名不能指向自身: $callbackName" }
        return when (callbackName) {
            "onLoad" -> "void onLoad() { $methodName(); }"
            "onUnload" -> "void onUnload() { $methodName(); }"
            "openSettings" -> "void openSettings() { $methodName(); }"
            "onClickSendBtn" -> "boolean onClickSendBtn(String text) { return Boolean.TRUE.equals($methodName(text)); }"
            "onLongClickSendBtn" -> "boolean onLongClickSendBtn(String text) { return Boolean.TRUE.equals($methodName(text)); }"
            "onHandleMsg" -> "void onHandleMsg(Object msg) { $methodName(msg); }"
            "onImageDownload" -> "void onImageDownload(Object msg, String imagePath, String talker, String senderWxid) { $methodName(msg, imagePath, talker, senderWxid); }"
            "onVideoDownload" -> "void onVideoDownload(Object msg, String videoPath, String talker, String senderWxid) { $methodName(msg, videoPath, talker, senderWxid); }"
            "onFinderMediaDownload" -> "void onFinderMediaDownload(Object msg, String mediaPath, String talker, String senderWxid) { $methodName(msg, mediaPath, talker, senderWxid); }"
            "onMemberChange" -> "void onMemberChange(String type, String groupWxid, String userWxid, String userName) { $methodName(type, groupWxid, userWxid, userName); }"
            "onNewFriend" -> "void onNewFriend(String wxid, String ticket, int scene) { $methodName(wxid, ticket, scene); }"
            "onProtobufPacket" -> "void onProtobufPacket(Object packet) { $methodName(packet); }"
            else -> throw IllegalArgumentException("不支持的回调名: $callbackName")
        }
    }

    private fun isMissingCallbackError(throwable: Throwable, callbackName: String): Boolean {
        val message = buildString {
            append(throwable.message.orEmpty())
            throwable.cause?.message?.let {
                append('\n')
                append(it)
            }
        }
        if (!message.contains(callbackName, ignoreCase = true)) return false
        return message.contains("Command not found", ignoreCase = true) ||
            message.contains("undefined", ignoreCase = true) ||
            message.contains("not found", ignoreCase = true)
    }

    private fun resolvePluginFile(pluginDir: File, path: String): File {
        require(path.isNotBlank()) { "路径不能为空" }
        val file = File(path)
        return if (file.isAbsolute) file else File(pluginDir, path)
    }

    private fun safeFileName(name: String): String {
        return name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "plugin" }
    }

    private fun safeCallbackFilePart(name: String?): String {
        return name.orEmpty()
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifBlank { "unknown" }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateNativeLibrary(file: File) {
        val header = ByteArray(20)
        val read = file.inputStream().use { it.read(header) }
        require(read == header.size &&
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
            header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()
        ) { "不是有效的ELF文件: ${file.name}" }
        require(header[4].toInt() == 2) {
            "仅支持64位ARM SO: ${file.name}"
        }
        val littleEndian = header[5].toInt() == 1
        require(littleEndian || header[5].toInt() == 2) { "SO字节序无效: ${file.name}" }
        val machine = if (littleEndian) {
            (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
        } else {
            ((header[18].toInt() and 0xff) shl 8) or (header[19].toInt() and 0xff)
        }
        require(machine == 183) {
            "仅支持arm64-v8a SO: ${file.name}"
        }
    }

    private fun copyNativeLibrary(source: File, destination: File, expectedDigest: String) {
        val temp = File(
            destination.parentFile,
            ".${destination.name}.${Process.myPid()}.${Thread.currentThread().id}.tmp"
        )
        try {
            FileOutputStream(temp, false).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
                output.fd.sync()
            }
            require(temp.length() == source.length()) { "Native缓存复制不完整: ${source.name}" }
            require(sha256(temp) == expectedDigest) { "Native缓存复制校验失败: ${source.name}" }
            try {
                Os.rename(temp.absolutePath, destination.absolutePath)
            } catch (error: Throwable) {
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("无法替换Native缓存: ${destination.absolutePath}", error)
                }
                if (!temp.renameTo(destination)) {
                    throw IllegalStateException("无法写入Native缓存: ${destination.absolutePath}", error)
                }
            }
        } finally {
            temp.delete()
        }
    }

    private fun loadNativeLibrary(file: File, classLoader: ClassLoader) {
        val runtimeClass = java.lang.Runtime::class.java
        val result = try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    val method = KavaReflector.findDeclaredMethod(
                        runtimeClass,
                        "nativeLoad",
                        String::class.java,
                        ClassLoader::class.java,
                        Class::class.java
                    ) ?: throw NoSuchMethodException("Runtime.nativeLoad(String, ClassLoader, Class)")
                    KavaReflector.invokeOrThrow(method, null, file.absolutePath, classLoader, null)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                    val method = KavaReflector.findDeclaredMethod(
                        runtimeClass,
                        "nativeLoad",
                        String::class.java,
                        ClassLoader::class.java
                    ) ?: throw NoSuchMethodException("Runtime.nativeLoad(String, ClassLoader)")
                    KavaReflector.invokeOrThrow(method, null, file.absolutePath, classLoader)
                }
                else -> {
                    val method = KavaReflector.findDeclaredMethod(
                        runtimeClass,
                        "nativeLoad",
                        String::class.java,
                        ClassLoader::class.java,
                        String::class.java
                    ) ?: throw NoSuchMethodException("Runtime.nativeLoad(String, ClassLoader, String)")
                    KavaReflector.invokeOrThrow(
                        method,
                        null,
                        file.absolutePath,
                        classLoader,
                        file.parentFile?.absolutePath
                    )
                }
            }
        } catch (error: Throwable) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            throw IllegalStateException("无法调用Android Native加载入口: ${cause.message}", cause)
        }
        val loadError = result as? String
        if (!loadError.isNullOrBlank()) {
            throw UnsatisfiedLinkError("SO加载失败(arm64-v8a): ${file.absolutePath}: $loadError")
        }
    }

    private data class LoadedNativeLibrary(
        val sourcePath: String,
        val digest: String,
        val classLoader: ClassLoader
    )

    private fun snapshotKey(pluginId: String): SecretKey {
        return SecretKeySpec(snapshotKeyBytes(), "AES")
    }

    private fun snapshotKeyBytes(): ByteArray {
        val mask = 0x5a
        val data = byteArrayOf(
            0x6a, 0x6b, 0x68, 0x69,
            0x6e, 0x6f, 0x6c, 0x6d,
            0x62, 0x63, 0x3b, 0x38,
            0x39, 0x3e, 0x3f, 0x3c
        )
        return ByteArray(data.size) { index -> (data[index].toInt() xor mask).toByte() }
    }
}
