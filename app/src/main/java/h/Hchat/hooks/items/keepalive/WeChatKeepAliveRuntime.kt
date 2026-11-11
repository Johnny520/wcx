package h.Hchat.hooks.items.keepalive

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import h.Hchat.BuildConfig
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog

object WeChatKeepAliveRuntime {
    private const val TAG = "[Hchat:KeepAlive]"
    private const val WAKE_LOCK_TAG = "Hchat:WeChatKeepAlive"
    private const val WECHAT_PACKAGE = "com.tencent.mm"
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var rootWhitelistApplied = false
    @Volatile private var rootAppOpsApplied = false

    fun apply(hostContext: Context?, moduleContext: Context? = null) {
        if (hostContext == null) return
        val appContext = hostContext.applicationContext ?: hostContext
        val serviceContext = (moduleContext ?: resolveModuleContext(appContext))?.let { it.applicationContext ?: it }
        val prefs = HchatStorage.preferences(appContext, WeChatKeepAliveSettings.PREFS_NAME)
        val enabled = prefs.getBoolean(WeChatKeepAliveSettings.KEY_ENABLE, WeChatKeepAliveSettings.DEFAULT_ENABLE)
        if (!enabled) {
            releaseWakeLock()
            if (serviceContext != null) runCatching { WeChatKeepAliveService.stop(serviceContext) }
            return
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_WAKE_LOCK, WeChatKeepAliveSettings.DEFAULT_WAKE_LOCK)) {
            acquireWakeLock(appContext)
        } else {
            releaseWakeLock()
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_FOREGROUND_SERVICE, WeChatKeepAliveSettings.DEFAULT_FOREGROUND_SERVICE)) {
            if (serviceContext != null) {
                val watchdog = prefs.getBoolean(WeChatKeepAliveSettings.KEY_WATCHDOG, WeChatKeepAliveSettings.DEFAULT_WATCHDOG)
                val networkHeartbeat = prefs.getBoolean(
                    WeChatKeepAliveSettings.KEY_NETWORK_HEARTBEAT,
                    WeChatKeepAliveSettings.DEFAULT_NETWORK_HEARTBEAT
                )
                runCatching { WeChatKeepAliveService.start(serviceContext, watchdog, networkHeartbeat) }
                    .onFailure { HLog.e("$TAG 启动前台服务失败: ${it.message}", it) }
            } else {
                HLog.e("$TAG 模块 Context 不可用，跳过前台服务")
            }
        } else {
            if (serviceContext != null) runCatching { WeChatKeepAliveService.stop(serviceContext) }
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_ROOT_DOZE_WHITELIST, WeChatKeepAliveSettings.DEFAULT_ROOT_DOZE_WHITELIST)) {
            applyRootDozeWhitelistOnce()
        }
        if (prefs.getBoolean(WeChatKeepAliveSettings.KEY_ROOT_APP_OPS, WeChatKeepAliveSettings.DEFAULT_ROOT_APP_OPS)) {
            applyRootAppOpsOnce()
        }
    }

    fun stop(moduleContext: Context? = null) {
        releaseWakeLock()
        val serviceContext = moduleContext?.applicationContext ?: moduleContext
        if (serviceContext != null) {
            runCatching { WeChatKeepAliveService.stop(serviceContext) }
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context?): Boolean {
        if (context == null) return false
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(WECHAT_PACKAGE) == true
        }.getOrDefault(false)
    }

    fun watchdogTick(context: Context?, enabled: Boolean) {
        if (context == null) return
        if (!enabled) return
        val appContext = context.applicationContext ?: context
        launchWeChatIfNeeded(appContext)
    }

    fun heartbeatTick(enabled: Boolean) {
        if (!enabled) return
        Thread {
            runCatching {
                val connection = java.net.URL(HEARTBEAT_URL).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = HEARTBEAT_TIMEOUT_MS
                connection.readTimeout = HEARTBEAT_TIMEOUT_MS
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.responseCode
                connection.disconnect()
            }.onFailure {
                HLog.e("$TAG 网络心跳失败: ${it.message}")
            }
        }.apply {
            name = "Hchat-KeepAlive-Heartbeat"
            isDaemon = true
        }.start()
    }

    private fun acquireWakeLock(context: Context) {
        val current = wakeLock
        if (current?.isHeld == true) return
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
            wakeLock = lock
        }.onFailure {
            HLog.e("$TAG 获取 WakeLock 失败: ${it.message}", it)
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure {
            HLog.e("$TAG 释放 WakeLock 失败: ${it.message}", it)
        }
        wakeLock = null
    }

    private fun applyRootDozeWhitelistOnce() {
        if (rootWhitelistApplied) return
        rootWhitelistApplied = true
        Thread {
            val result = runRootCommand("cmd deviceidle whitelist +com.tencent.mm")
            if (!result) {
                HLog.e("$TAG Root Doze 白名单执行失败")
            }
        }.apply {
            name = "Hchat-KeepAlive-RootWhitelist"
            isDaemon = true
        }.start()
    }

    private fun applyRootAppOpsOnce() {
        if (rootAppOpsApplied) return
        rootAppOpsApplied = true
        Thread {
            val commands = listOf(
                "cmd appops set $WECHAT_PACKAGE RUN_ANY_IN_BACKGROUND allow",
                "cmd appops set $WECHAT_PACKAGE RUN_IN_BACKGROUND allow",
                "cmd appops set $WECHAT_PACKAGE WAKE_LOCK allow",
                "cmd appops set $WECHAT_PACKAGE START_FOREGROUND allow"
            )
            val ok = commands.all { runRootCommand(it) }
            if (!ok) {
                HLog.e("$TAG Root AppOps 放行执行不完整")
            }
        }.apply {
            name = "Hchat-KeepAlive-RootAppOps"
            isDaemon = true
        }.start()
    }

    private fun resolveModuleContext(context: Context): Context? {
        return runCatching {
            context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull()
    }

    private fun runRootCommand(command: String): Boolean {
        return runCatching {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private fun launchWeChatIfNeeded(context: Context) {
        runCatching {
            if (isWeChatProcessAlive()) return
            val manager = context.packageManager
            val intent = manager.getLaunchIntentForPackage(WECHAT_PACKAGE) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            HLog.e("$TAG 看门狗拉起微信失败: ${it.message}", it)
        }
    }

    private fun isWeChatProcessAlive(): Boolean {
        return runCatching {
            val process = ProcessBuilder("sh", "-c", "pidof $WECHAT_PACKAGE")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    private const val HEARTBEAT_URL = "https://connectivitycheck.gstatic.com/generate_204"
    private const val HEARTBEAT_TIMEOUT_MS = 3000
}
