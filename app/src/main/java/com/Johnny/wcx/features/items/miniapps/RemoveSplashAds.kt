package com.Johnny.wcx.features.items.miniapps

import android.app.Activity
import com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "移除开屏广告", categories = ["小程序"], description = "跳过小程序开屏广告")
object RemoveSplashAds : SwitchFeature(), IResolveDex {

    private const val TAG = "RemoveSplashAds"

    private val methodIsAdContact by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.AppBrandAdUtils[AppBrandSplashAd]", "isAdContact, appId:%s, canShowAd:%s")
        }
    }
    private val methodAdDataCallback by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand.jsapi.auth")
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrand.JsApiAdOperateWXData[AppBrandSplashAd]", "cgi callback, callbackId:%s, service not running or preloaded"
            )
        }
    }
    private val methodCheckCanShowAd by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand")
        matcher {
            usingEqStrings("MicroMsg.AppBrandAdUtils[AppBrandSplashAd]", "checkCanShowAd, show ad (splash ad debug mode open)")
        }
    }

    override fun onEnable() {
        // 三个锚点各自独立：某一个失效不影响其它，也不会因为占位委托抛异常导致整个功能装不上。
        forceFalse(methodIsAdContact, "isAdContact")
        forceSkip(methodAdDataCallback, "adDataCallback")
        forceFalse(methodCheckCanShowAd, "checkCanShowAd")

        runCatching {
            AppBrandAdUI::class.java.hookBeforeOnCreate {
                runCatching {
                    (thisObject as? Activity)?.finish()
                    result = null
                }.onFailure { WeLogger.w(TAG, "AppBrandAdUI.onCreate 处理失败: ${it.message}") }
            }
        }.onFailure { WeLogger.e(TAG, "hook AppBrandAdUI.onCreate 失败", it) }
    }

    /** 让目标方法直接返回 false（仅对 boolean 返回类型生效）。 */
    private fun forceFalse(d: DexMethodDelegate, label: String) {
        if (d.isPlaceholder) {
            WeLogger.w(TAG, "$label 锚点失效，跳过")
            return
        }
        runCatching {
            d.hookBefore {
                runCatching {
                    val m = method as? java.lang.reflect.Method ?: return@runCatching
                    if (m.returnType == Boolean::class.javaPrimitiveType || m.returnType == java.lang.Boolean::class.java) {
                        result = false
                    }
                }.onFailure { WeLogger.w(TAG, "$label 处理失败: ${it.message}") }
            }
        }.onFailure { WeLogger.e(TAG, "hook $label 失败", it) }
    }

    /** 让目标 void 方法直接跳过原逻辑。 */
    private fun forceSkip(d: DexMethodDelegate, label: String) {
        if (d.isPlaceholder) {
            WeLogger.w(TAG, "$label 锚点失效，跳过")
            return
        }
        runCatching {
            d.hookBefore {
                runCatching {
                    val m = method as? java.lang.reflect.Method ?: return@runCatching
                    if (m.returnType == Void.TYPE) result = null
                }.onFailure { WeLogger.w(TAG, "$label 处理失败: ${it.message}") }
            }
        }.onFailure { WeLogger.e(TAG, "hook $label 失败", it) }
    }
}
