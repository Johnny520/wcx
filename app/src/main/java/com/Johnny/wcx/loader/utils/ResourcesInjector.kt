package com.Johnny.wcx.loader.utils

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import androidx.annotation.RequiresApi
import com.Johnny.wcx.R
import com.Johnny.wcx.loader.startup.StartupInfo
import com.Johnny.wcx.utils.WeLogger
import java.io.File
import java.io.IOException

object ResourcesInjector {

    private const val TAG = "ResourcesInjector"
    private const val UNREGISTERED_RESOURCES_ERROR =
        "Cannot modify resource loaders of ResourcesImpl not registered with ResourcesManager"

    fun injectModuleRes(resources: Resources?) {
        resources ?: return
        if (hasModuleRes(resources)) return

        val modulePath = StartupInfo.modulePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            injectResGte30(resources, modulePath)
        } else {
            injectResLt30(resources, modulePath)
        }

        if (hasModuleRes(resources)) {
            WeLogger.d(TAG, "successfully injected module resources")
        } else {
            WeLogger.e(TAG, "failed to inject module resources")
        }
    }

    private fun hasModuleRes(resources: Resources): Boolean = try {
        resources.getValue(R.string.res_inject_success, TypedValue(), true)
        true
    } catch (_: Resources.NotFoundException) {
        false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private object ResourcesLoaderHolderApi30 {
        var loader: ResourcesLoader? = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun injectResGte30(resources: Resources, path: String) {
        if (ResourcesLoaderHolderApi30.loader == null) {
            try {
                ParcelFileDescriptor.open(
                    File(path),
                    ParcelFileDescriptor.MODE_READ_ONLY
                ).use { descriptor ->
                    val provider = ResourcesProvider.loadFromApk(descriptor)
                    ResourcesLoaderHolderApi30.loader = ResourcesLoader().apply {
                        addProvider(provider)
                    }
                }
            } catch (e: IOException) {
                logInjectionFailure(path, e, 0)
                return
            }
        }

        try {
            resources.addLoaders(ResourcesLoaderHolderApi30.loader!!)
        } catch (e: IllegalArgumentException) {
            if (e.message == UNREGISTERED_RESOURCES_ERROR) {
                WeLogger.e(TAG, "Resources.addLoaders rejected this Resources instance; falling back to addAssetPath", e)
                injectResLt30(resources, path)
                return
            }
            throw e
        }

        if (!hasModuleRes(resources)) {
            logInjectionFailure(path, Resources.NotFoundException(R.string.res_inject_success.toString()), 0)
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    @Suppress("JavaReflectionMemberAccess")
    private fun injectResLt30(resources: Resources, path: String) {
        var cookie = 0
        try {
            val addAssetPath = AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
                .apply { isAccessible = true }
            cookie = addAssetPath.invoke(resources.assets, path) as Int
            if (!hasModuleRes(resources)) {
                logInjectionFailure(path, Resources.NotFoundException(R.string.res_inject_success.toString()), cookie)
            }
        } catch (e: Exception) {
            logInjectionFailure(path, e, cookie)
        }
    }

    private fun logInjectionFailure(path: String, error: Throwable, cookie: Int) {
        val moduleFile = File(path)
        WeLogger.e(
            TAG,
            "module resource injection failed: path=$path, cookie=$cookie, " +
                "loader=${ResourcesInjector::class.java.classLoader}, " +
                "exists=${moduleFile.exists()}, directory=${moduleFile.isDirectory}, " +
                "readable=${moduleFile.canRead()}, length=${moduleFile.length()}",
            error
        )
    }
}
