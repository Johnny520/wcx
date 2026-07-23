@file:Suppress("unused")

package com.Johnny.wcx.loader.entry.lxp

import androidx.annotation.Keep
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.loader.entry.common.ModuleLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

@Keep
class LxpHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        LxpHookImpl.init(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage) {
                val ai = param.applicationInfo
//                ModuleLoader.saveInitParams(
//                    param.classLoader,
//                    this.moduleApplicationInfo.sourceDir
//                )
                ModuleLoader.init(
                    ai.dataDir,
                    param.classLoader,
                    LxpHookImpl,
                    LxpHookImpl,
                    this.moduleApplicationInfo.sourceDir,
                    true
                )
            }
        }
    }

//    override fun onHotReloading(param: HotReloadingParam): Boolean {
//        WeLogger.i("LxpHookEntry", "hot reload requested, allowing...")
//        return true
//    }
//
//    override fun onHotReloaded(param: HotReloadedParam) {
//        WeLogger.i("LxpHookEntry", "hot reload completed, re-initializing...")
//
//        LxpHookImpl.init(this)
//        param.oldHookHandles.forEach { it.unhook() }
//        ModuleLoader.hotReload(LxpHookImpl, LxpHookImpl)
//    }
}
