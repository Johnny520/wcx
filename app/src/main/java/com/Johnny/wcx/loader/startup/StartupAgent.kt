package com.Johnny.wcx.loader.startup

import android.annotation.SuppressLint
import android.app.Application
import android.os.Build
import dev.ujhhgtg.reflekt.utils.ReflectionClassLoader
import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService
import com.Johnny.wcx.loader.entry.zygisk.ArtHookBridge
import com.Johnny.wcx.loader.entry.zygisk.ZygiskLoaderService
import com.Johnny.wcx.loader.utils.HybridClassLoader
import com.Johnny.wcx.loader.utils.NativeLoader
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.lang.reflect.Field
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

object StartupAgent {

    private const val TAG = "StartupAgent"

    private val startupLock = Any()

    @Volatile
    private var initialized = false

    @OptIn(ExperimentalPathApi::class)
    fun startup(
        loaderService: ILoaderService,
        hookBridge: IHookBridge?,
        modulePath: String,
        application: Application
    ) = synchronized(startupLock) {
        if (initialized) {
            return@synchronized
        }

        val realClassLoader = application.baseContext.classLoader
        HybridClassLoader.hostClassLoader = realClassLoader
        ReflectionClassLoader.value = realClassLoader

        StartupInfo.modulePath = modulePath
        StartupInfo.loaderService = loaderService
        StartupInfo.hookBridge = hookBridge

        ensureHiddenApiAccess()
        if (loaderService !is ZygiskLoaderService) {
            checkWxForModulePath(modulePath)
        }

        HostInfo.init(application)
        NativeLoader.init(application)
        if (hookBridge is ArtHookBridge) {
            hideModuleLibraries(hookBridge)
        }
        WeLauncher.init(application)

        runCatching {
            application.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure { WeLogger.e(TAG, "failed to delete app_qqprotect", it) }

        // Only commit after every required startup phase completes. The caller
        // already logs a thrown failure, and a later lifecycle callback can retry.
        initialized = true
    }

    private fun hideModuleLibraries(hookBridge: ArtHookBridge) {
        runCatching { hookBridge.hideLoadedModuleLibraries() }
            .onSuccess { hidden ->
                WeLogger.i(
                    TAG,
                    "hid loaded module libraries"
                )
                if (!hidden) WeLogger.w(TAG, "module native-library hiding was incomplete")
            }
            .onFailure {
                WeLogger.e(TAG, "failed to hide module libraries", it)
            }
    }

    private fun checkWxForModulePath(modulePath: String) {
        val moduleFile = File(modulePath)
        if (moduleFile.canWrite()) {
            WeLogger.w(TAG, "module path is writable: $modulePath\nthis may cause issues on Android 15+, please check your Xposed framework")
        }
    }

    private fun ensureHiddenApiAccess() {
        if (!isHiddenApiAccessible()) {
            WeLogger.w(
                TAG,
                "hidden api is not accessible, SDK_INT is ${Build.VERSION.SDK_INT}"
            )
            HiddenApiBypass.setHiddenApiExemptions("L")
        }
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    fun isHiddenApiAccessible(): Boolean {
        val kContextImpl = runCatching {
            Class.forName("android.app.ContextImpl")
        }.getOrElse { return false }

        var mActivityToken: Field? = null
        var mToken: Field? = null

        try {
            mActivityToken = kContextImpl.getDeclaredField("mActivityToken")
        } catch (_: NoSuchFieldException) {
        }
        try {
            mToken = kContextImpl.getDeclaredField("mToken")
        } catch (_: NoSuchFieldException) {
        }

        return mActivityToken != null || mToken != null
    }
}
