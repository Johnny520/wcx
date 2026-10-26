package com.Johnny.wcx.loader.startup

import android.app.ActivityManager
import android.content.Context
import android.content.res.Resources
import android.os.Process
import android.os.SystemClock
import com.tencent.mm.boot.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.core.FeaturesLoader
import com.Johnny.wcx.features.hchat.HchatScriptBootstrap
import com.Johnny.wcx.dynamic.LocalAdaptationEngine
import com.Johnny.wcx.dynamic.SelfHealingMonitor
import com.Johnny.wcx.loader.utils.ActivityProxy
import com.Johnny.wcx.loader.utils.ParcelableFixer
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.MainThreadWatchdog
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.crash.ExitReasonRecorder
import com.Johnny.wcx.utils.crash.JavaCrashHandler
import com.Johnny.wcx.utils.crash.ProcessTimeline
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.invokeOriginal
import com.Johnny.wcx.utils.reflection.int

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        // 尽早接管未捕获异常：模块后台线程/协程抛异常时只记录、不结束宿主进程，
        // 否则 Android 会直接杀掉整个微信进程（用户感知就是「微信被强行停止」）。
        runCatching { JavaCrashHandler.install() }

        // 记录上次进程退出原因（内存回收 / 崩溃 / ANR / 用户强行停止），供诊断页区分责任方。
        runCatching {
            val appCtx = context.applicationContext ?: context
            Thread({
                // 启动时间线 + 上次退出原因：用于判断「后台被杀」是偶发还是周期性。
                runCatching { ProcessTimeline.recordStartup(appCtx) }
                runCatching { ExitReasonRecorder.recordOnStartup(appCtx) }
            }, "WCX-ExitReason").apply { isDaemon = true }.start()
        }

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityProxy.init(appContext)
            LocalAdaptationEngine.init(appContext)
            SelfHealingMonitor.init()

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs

            // fix up Jetpack Compose
            // fuck you google
            Resources::class.java.getDeclaredMethod("getString", int).hookBeforeDirectly {
                result = runCatching { invokeOriginal() }.getOrNull() ?: "null"
            }
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load hooks", it) }

        // 接入 Hchat 脚本子系统（脚本插件运行时 + 公共微信 API）。仅在主进程安装。
        // 必须放到后台线程：安装过程会创建 DexKitBridge 并解析整包 dex（重 IO/CPU），
        // 在主线程同步执行会拖慢微信冷启动，表现为「先白屏一会、然后明显卡顿」。
        if (TargetProcesses.isInMain) {
            Thread({
                // 微信冷启动时创建 DexKitBridge + 全量解析 dex 会占满 CPU/IO，
                // 主线程抢不到时间片 → 输入事件分发超过 5s → 系统判 ANR 并结束整个微信进程。
                // 用户感知就是「突然像被强行停止，然后模块重新加载」。
                // 对策：把这条线程降到后台优先级，并等微信真正进入可见前台再开始。
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                runCatching { MainThreadWatchdog.install() }
                awaitVisibleOrTimeout(context.applicationContext ?: context, 20_000L)
                // 再等主线程消息队列空闲（首屏渲染/启动动画结束），避免与 UI 抢 CPU。
                awaitMainLooperIdle(15_000L)
                runCatching { Thread.sleep(3_000L) }
                runCatching {
                    HchatScriptBootstrap.install()
                }.onFailure { WeLogger.e(TAG, "failed to install Hchat script subsystem", it) }
            }, "Hchat-Bootstrap").apply { isDaemon = true }.start()
        }
    }

    /**
     * 等宿主进程进入可见状态（importance <= IMPORTANCE_VISIBLE），最多 [timeoutMs]。
     * 后台冷启动（被 push 拉起）时不会干等：超时后照常继续，只是晚一点解析。
     */
    private fun awaitVisibleOrTimeout(context: Context, timeoutMs: Long) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val selfPid = Process.myPid()
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                val importance = am.runningAppProcesses
                    ?.firstOrNull { it.pid == selfPid }?.importance ?: return
                if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return
                Thread.sleep(500L)
            }
        }
    }

    /** 等主线程消息队列出现一次空闲（说明首屏已渲染完），最多 [timeoutMs]。 */
    private fun awaitMainLooperIdle(timeoutMs: Long) {
        runCatching {
            val latch = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.os.Looper.myQueue().addIdleHandler {
                        latch.countDown()
                        false
                    }
                }.onFailure { latch.countDown() }
            }
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private const val TAG = "WeLauncher"
}
