@file:Suppress("unused")

package com.Johnny.wcx.loader.entry.lsp10x

import androidx.annotation.Keep
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.loader.entry.common.ModuleLoader
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Unified LibXposed entry point compatible with both API101 and API102.
 *
 * Strategy:
 * - API102 (standard new environment): enables hot-reload — settings changes take effect
 *   without restarting WeChat
 * - API101 (legacy / LSPatch): degrades gracefully — hot-reload is disabled, WeChat
 *   must be restarted for settings changes to take effect
 *
 * The API version is detected at runtime via [ModuleLoadedParam.apiVersion] and cached
 * to [WePrefs] so the UI can display which mode is active.
 */
@Keep
class Lsp10xUnifiedHookEntry : XposedModule() {

    companion object {
        private const val TAG = "Lsp10xUnifiedHookEntry"
        private const val API_102 = 102
        private const val API_101 = 101

        @Volatile
        var detectedApiVersion: Int = 0
            private set

        @Volatile
        var isHotReloadSupported: Boolean = false
            private set
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        detectedApiVersion = runCatching {
            param.javaClass.getDeclaredField("apiVersion").apply { isAccessible = true }.getInt(param)
        }.getOrDefault(API_102)
        isHotReloadSupported = detectedApiVersion >= API_102

        WeLogger.i(TAG, "module loaded, detected API version: $detectedApiVersion, hot-reload: $isHotReloadSupported")

        // Cache the API version for cross-process reading (UI reads from WeChat process)
        runCatching {
            WePrefs.putInt(Preferences.CACHED_LSP_API_VERSION, detectedApiVersion)
        }.onFailure {
            WeLogger.e(TAG, "failed to cache LSP API version", it)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage) {
                val ai = param.applicationInfo
                WeLogger.i(TAG, "first package ready, initializing module (API=$detectedApiVersion)")

                ModuleLoader.init(
                    ai.dataDir,
                    param.classLoader,
                    Lsp10xHookImpl,
                    Lsp10xHookImpl,
                    this.moduleApplicationInfo.sourceDir,
                    true
                )
            }
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        if (!isHotReloadSupported) {
            WeLogger.w(TAG, "hot-reload requested but API version $detectedApiVersion does not support it")
            return false
        }
        WeLogger.i(TAG, "hot-reload requested, allowing...")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        if (!isHotReloadSupported) {
            WeLogger.w(TAG, "hot-reload completed but API version $detectedApiVersion does not support it — ignoring")
            return
        }
        WeLogger.i(TAG, "hot-reload completed, re-initializing module...")

        // Unhook all old hooks
        param.oldHookHandles.forEach { it.unhook() }

        // Re-initialize the module
        ModuleLoader.hotReload()
    }
}