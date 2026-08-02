package com.Johnny.wcx.loader.entry.common

import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.startup.UnifiedEntryPoint
import com.Johnny.wcx.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private var isInitialized = false

    private lateinit var savedHostClassLoader: ClassLoader
    private lateinit var savedModulePath: String
    private lateinit var savedLoaderService: ILoaderService
    private var savedHookBridge: IHookBridge? = null
    private lateinit var savedHostDataDir: String

    fun saveInitParams(
        hostClassLoader: ClassLoader,
        modulePath: String
    ) {
        savedHostClassLoader = hostClassLoader
        savedModulePath = modulePath
    }

    @Suppress("unused")
    @JvmStatic
    fun init(
        hostDataDir: String,
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        allowDynamicLoad: Boolean
    ) {
        if (isInitialized) return
        isInitialized = true

        // Save parameters for potential hot-reload
        savedHostClassLoader = initialClassLoader
        savedModulePath = modulePath
        savedLoaderService = loaderService
        savedHookBridge = hookBridge
        savedHostDataDir = hostDataDir

        WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
        runCatching {
            UnifiedEntryPoint.entry(loaderService, hookBridge, initialClassLoader, modulePath)
        }.onFailure { WeLogger.e(TAG, "UnifiedEntryPoint failed", it) }
    }

    /**
     * Hot-reload: re-apply all features with current settings.
     * Only available when the framework supports API102+ hot-reload.
     *
     * Disables all currently active features, then re-enables those
     * that should be active based on current preferences.
     */
    fun hotReload() {
        WeLogger.i(TAG, "hot-reloading in entry point ${savedLoaderService.entryPointName}")

        // Disable all currently active features
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        WeLogger.i(TAG, "disabling ${allFeatures.count { it.isActive }} active features for hot-reload")

        allFeatures.forEach { feature ->
            if (feature.isActive) {
                runCatching {
                    feature.disable()
                }.onFailure { e ->
                    WeLogger.e(TAG, "failed to disable feature ${feature.displayName} during hot-reload", e)
                }
            }
        }

        // Re-enable features based on current settings
        WeLogger.i(TAG, "re-enabling features with current settings")
        allFeatures.forEach { feature ->
            runCatching {
                feature.startup()
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to startup feature ${feature.displayName} during hot-reload", e)
            }
        }

        val enabledCount = allFeatures.count { it.isActive }
        WeLogger.i(TAG, "hot-reload complete: $enabledCount features active")
    }
}