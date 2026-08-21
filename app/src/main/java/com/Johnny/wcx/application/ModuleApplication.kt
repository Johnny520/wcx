package com.Johnny.wcx.application

import android.app.Application
import com.Johnny.wcx.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
    }
}
