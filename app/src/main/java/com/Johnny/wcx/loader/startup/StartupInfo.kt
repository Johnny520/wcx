package com.Johnny.wcx.loader.startup

import com.Johnny.wcx.loader.abc.IHookBridge
import com.Johnny.wcx.loader.abc.ILoaderService

object StartupInfo {

    lateinit var loaderService: ILoaderService
    var hookBridge: IHookBridge? = null
}
