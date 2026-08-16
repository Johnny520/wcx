package com.Johnny.wcx.application

import android.app.Application
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.crash.JavaCrashHandler

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        // 安装全局 Java 崩溃捕获，防止未捕获异常导致闪退
        runCatching { JavaCrashHandler.install() }
            .onFailure { WeLogger.e("ModuleApplication", "failed to install crash handler", it) }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_MODERATE -> {
                WeLogger.i("ModuleApplication", "onTrimMemory: level=$level, cleaning caches")
                // 提示系统回收弱/软引用缓存
                System.gc()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        WeLogger.w("ModuleApplication", "onLowMemory: cleaning caches")
        System.gc()
    }
}
