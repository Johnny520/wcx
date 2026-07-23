package com.Johnny.wcx.loader.entry.common

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.startup.UnifiedEntryPoint
import com.Johnny.wcx.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private var isInitialized = false

//    private lateinit var savedHostClassLoader: ClassLoader
//    private lateinit var savedModulePath: String
//
//    fun saveInitParams(
//        hostClassLoader: ClassLoader,
//        modulePath: String
//    ) {
//        savedHostClassLoader = hostClassLoader
//        savedModulePath = modulePath
//    }

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

        WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
        runCatching {
            UnifiedEntryPoint.entry(loaderService, hookBridge, initialClassLoader, modulePath)
        }.onFailure { WeLogger.e(TAG, "UnifiedEntryPoint failed", it) }
    }

//    fun hotReload(loaderService: ILoaderService, hookBridge: IHookBridge?) {
//        WeLogger.i(TAG, "hot-reloading in entry point ${loaderService.entryPointName}")
//        runCatching {
//            UnifiedEntryPoint.entry(loaderService, hookBridge, savedHostClassLoader, savedModulePath)
//        }.onFailure { WeLogger.e(TAG, "UnifiedEntryPoint failed", it) }
//    }
}
