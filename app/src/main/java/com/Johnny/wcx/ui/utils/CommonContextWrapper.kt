package com.Johnny.wcx.ui.utils

import android.content.Context
import android.view.ContextThemeWrapper
import com.Johnny.wcx.loader.utils.ResourcesInjector
import com.Johnny.wcx.utils.reflection.ClassLoaders

class CommonContextWrapper(val base: Context) : ContextThemeWrapper(base, base.theme) {

    init {
        ResourcesInjector.injectModuleRes(resources)
    }

    override fun getClassLoader(): ClassLoader = ClassLoaders.MODULE
}
