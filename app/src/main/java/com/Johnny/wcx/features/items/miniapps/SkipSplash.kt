package com.Johnny.wcx.features.items.miniapps

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "跳过启动页面", categories = ["小程序"], description = "跳过小程序启动页面, 变相去广告 (实验性)")
object SkipSplash : SwitchFeature(), IResolveDex {

    private const val TAG = "SkipSplash"

    private val methodShowSplash by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand")
        matcher {
            declaredClass = "com.tencent.mm.plugin.appbrand.AppBrandRuntime"
            returnType = "void"
            paramCount = 0
            usingEqStrings("Loading页展示")
        }
    }

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

    override fun onEnable() {
        if (methodShowSplash.isPlaceholder) {
            WeLogger.w(TAG, "未定位到 AppBrandRuntime 启动页方法（锚点失效，微信版本变化）")
            return
        }
        runCatching {
            methodShowSplash.hookBefore {
                runCatching {
                    val m = method as? java.lang.reflect.Method ?: return@runCatching
                    if (m.returnType == Void.TYPE) result = null
                }.onFailure { WeLogger.w(TAG, "启动页拦截处理失败: ${it.message}") }
            }
            WeLogger.i(TAG, "已安装启动页拦截")
        }.onFailure { WeLogger.e(TAG, "hook 启动页方法失败", it) }
    }
}
