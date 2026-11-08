package h.Hchat.hooks.items.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import h.Hchat.R
import h.Hchat.utils.HLog

class WeChatKeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var watchdogEnabled = false
    private var networkHeartbeatEnabled = false
    private val keepAliveTask = object : Runnable {
        override fun run() {
            WeChatKeepAliveRuntime.watchdogTick(this@WeChatKeepAliveService, watchdogEnabled)
            WeChatKeepAliveRuntime.heartbeatTick(networkHeartbeatEnabled)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return runCatching {
            watchdogEnabled = intent?.getBooleanExtra(EXTRA_WATCHDOG, false) == true
            networkHeartbeatEnabled = intent?.getBooleanExtra(EXTRA_NETWORK_HEARTBEAT, false) == true
            startForeground(NOTIFICATION_ID, buildNotification())
            handler.removeCallbacks(keepAliveTask)
            handler.postDelayed(keepAliveTask, WATCHDOG_INTERVAL_MS)
            START_STICKY
        }.onFailure {
            HLog.e("$TAG 启动前台保活服务失败: ${it.message}", it)
        }.getOrDefault(START_NOT_STICKY)
    }

    override fun onDestroy() {
        handler.removeCallbacks(keepAliveTask)
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hchat 微信保活", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager?.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hchat 微信强保活")
            .setContentText("正在尝试保持微信息屏运行")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "[Hchat:KeepAliveService]"
        private const val CHANNEL_ID = "hchat_wechat_keep_alive"
        private const val NOTIFICATION_ID = 520134
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private const val EXTRA_WATCHDOG = "h.Hchat.extra.WATCHDOG"
        private const val EXTRA_NETWORK_HEARTBEAT = "h.Hchat.extra.NETWORK_HEARTBEAT"

        fun start(context: Context, watchdog: Boolean, networkHeartbeat: Boolean) {
            val appContext = context.applicationContext ?: context
            val intent = Intent(appContext, WeChatKeepAliveService::class.java).apply {
                putExtra(EXTRA_WATCHDOG, watchdog)
                putExtra(EXTRA_NETWORK_HEARTBEAT, networkHeartbeat)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext ?: context
            appContext.stopService(Intent(appContext, WeChatKeepAliveService::class.java))
        }
    }
}
