package com.Johnny.wcx.loader.entry.common

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.startup.UnifiedEntryPoint
import com.Johnny.wcx.utils.WeLogger

object ModuleLoader {

    private const val TAG = "ModuleLoader"
    private val initLock = Any()

    @Volatile
    private var isInitialized = false

    private class InitParams(
        val hostDataDir: String,
        val initialClassLoader: ClassLoader,
        val loaderService: ILoaderService,
        val hookBridge: IHookBridge?,
        val modulePath: String,
    )

    @Volatile
    private var cachedParams: InitParams? = null

    @Suppress("unused")
    @JvmStatic
    fun init(
        hostDataDir: String,
        initialClassLoader: ClassLoader,
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        allowDynamicLoad: Boolean
    ): Boolean = synchronized(initLock) {
        cachedParams = InitParams(
            hostDataDir = hostDataDir,
            initialClassLoader = initialClassLoader,
            loaderService = loaderService,
            hookBridge = hookBridge,
            modulePath = modulePath,
        )
        if (isInitialized) return@synchronized true

        try {
            WeLogger.i(TAG, "loading in entry point ${loaderService.entryPointName}")
            UnifiedEntryPoint.entry(loaderService, hookBridge, initialClassLoader, modulePath)
            isInitialized = true
            true
        } catch (t: Throwable) {
            // Do not poison this process's loader state: a later lifecycle
            // callback may have a usable host class loader.
            WeLogger.e(TAG, "UnifiedEntryPoint failed", t)
            false
        }
    }

    /**
     * Re-runs [UnifiedEntryPoint.entry] with the parameters cached by the last [init] call.
     * Used by LSPosed hot-reload: the framework has already unhooked the old hooks, so we only
     * need to rebuild them.
     */
    @JvmStatic
    fun hotReload(): Boolean = synchronized(initLock) {
        val params = cachedParams
        if (params == null) {
            WeLogger.w(TAG, "hot-reload requested but init params not cached yet")
            return@synchronized false
        }
        WeLogger.i(TAG, "hot-reload: re-running UnifiedEntryPoint")
        try {
            UnifiedEntryPoint.entry(
                params.loaderService,
                params.hookBridge,
                params.initialClassLoader,
                params.modulePath,
            )
            true
        } catch (t: Throwable) {
            WeLogger.e(TAG, "hot-reload failed", t)
            false
        }
    }
}
