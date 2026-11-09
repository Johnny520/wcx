package com.Johnny.wcx.loader.startup

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService

object StartupInfo {

    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null

    /** 模块 APK 路径（资源注入用）。 */
    val modulePath: String
        get() = runCatching { com.Johnny.wcx.utils.HostInfo.appInfo.sourceDir }.getOrDefault("")
}
