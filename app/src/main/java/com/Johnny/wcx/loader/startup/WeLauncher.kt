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
import com.Johnny.wcx.utils.crash.NativeCrashHandler
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

        // 原生崩溃（SIGSEGV/SIGABRT/SIGBUS…）Java 层完全抓不到，而它正是「微信莫名其妙
        // 被杀、日志里什么都没有」最常见的成因。Rust 侧 crash_handler 早已实现（且会链式
        // 调用微信自己注册的旧 handler，不会吞掉宿主处理），只是一直没被安装 —— 这里补上。
        runCatching { NativeCrashHandler.install() }
            .onFailure { WeLogger.w(TAG, "native crash handler install failed: ${it.message}") }

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

        // 缓存里存的是 R8 混淆后的类/方法描述符，每次构建混淆结果都不同。
        // key 必须带上「模块自身构建指纹」，否则升级模块后会复用上一版的名字，
        // 导致所有微信内部定位失败、功能与设置入口全部消失（表现为「微信像没装模块」）。
        // 注意：本文件 import 的 BuildConfig 是 com.tencent.mm.boot.BuildConfig（微信的），
        // 模块指纹必须显式取 com.Johnny.wcx.BuildConfig。
        val moduleBuildStamp = "${com.Johnny.wcx.BuildConfig.COMMIT_HASH}|${com.Johnny.wcx.BuildConfig.BUILD_TIMESTAMP}"
        DexCacheManager.init(
            buildString {
                append(HostInfo.versionName).append(HostInfo.versionCode)
                if (Preferences.resetDexCacheOnHotUpdate) {
                    append('|').append(BuildConfig.CLIENT_VERSION_ARM64)
                }
                append('|').append(moduleBuildStamp)
            }
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

        // Hchat 脚本子系统（可插拔）：独立后台引导，失败不影响 WCX 既有功能。
        // 移除本调用即完全下线脚本子系统。
        runCatching {
            com.Johnny.wcx.features.hchat.HchatBootstrap.install(context)
        }.onFailure { WeLogger.e(TAG, "hchat bootstrap failed", it) }

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
