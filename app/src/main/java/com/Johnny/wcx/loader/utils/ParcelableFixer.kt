package com.Johnny.wcx.loader.utils

import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.hookDirectly
import com.Johnny.wcx.utils.reflection.BString
import com.Johnny.wcx.utils.reflection.ClassLoaders

object ParcelableFixer {

    private const val TAG = "ParcelableFixer"

    lateinit var hybridClassLoader: ClassLoader
        private set

    private var initialized = false

    @Suppress("unused")
    fun init() {
        if (initialized) return
        initialized = true

        hybridClassLoader = object : ClassLoader(ClassLoaders.HOST) {
            override fun findClass(name: String): Class<*> = ClassLoaders.MODULE.loadClass(name)
        }

        hookIntentMethods()
    }

    private fun fixIntentExtrasClassLoader(intent: Intent?) {
        if (!isTargetIntent(intent)) return
        val cl = hybridClassLoader
        runCatching { intent?.setExtrasClassLoader(cl) }
    }

    private fun isTargetIntent(intent: Intent?): Boolean {
        intent ?: return false
        val className = intent.component?.className ?: return false
        return ActivityLauncher.isModuleActivity(className)
    }

    private fun hookIntentMethods() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                (param.thisObject as? Intent)?.let { fixIntentExtrasClassLoader(it) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val intent = param.thisObject as? Intent ?: return
                if (!isTargetIntent(intent)) return
                val cl = hybridClassLoader
                (param.result as? Bundle)?.classLoader = cl
            }
        }

        runCatching {
            Intent::class.reflekt().apply {
                firstMethod {
                    name = "getExtras"
                    parameters()
                }.hookDirectly(hook)

                firstMethod {
                    name = "getBundleExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getParcelableExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getParcelableArrayListExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getSerializableExtra"
                    parameters(BString)
                }.hookDirectly(hook)

                // Android 13+

                firstMethod {
                    name = "getParcelableExtra"
                    parameters(BString, Class::class)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getParcelableArrayListExtra"
                    parameters(BString, Class::class)
                }.hookDirectly(hook)

                firstMethod {
                    name = "getSerializableExtra"
                    parameters(BString, Class::class)
                }.hookDirectly(hook)
            }
        }.onFailure { WeLogger.w(TAG, "failed to hook some Intent methods") }
    }
}
