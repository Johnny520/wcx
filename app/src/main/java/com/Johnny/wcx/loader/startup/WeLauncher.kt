package com.Johnny.wcx.loader.startup

import android.content.Context
import android.content.res.Resources
import com.tencent.mm.boot.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.features.core.FeaturesLoader
import com.Johnny.wcx.dynamic.LocalAdaptationEngine
import com.Johnny.wcx.dynamic.SelfHealingMonitor
import com.Johnny.wcx.loader.utils.ActivityLauncher
import com.Johnny.wcx.loader.utils.ParcelableFixer
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.invokeOriginal
import com.Johnny.wcx.utils.reflection.int

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            if (!Preferences.resetDexCacheOnHotUpdate) "${HostInfo.versionName}${HostInfo.versionCode}"
            else "${BuildConfig.VERSION_NAME}${BuildConfig.VERSION_CODE}${BuildConfig.CLIENT_VERSION_ARM64}"
        )

        if (TargetProcesses.isInMain) {
            val appContext = context.applicationContext ?: context
            ActivityLauncher.init()
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
    }

    private const val TAG = "WeLauncher"
}
