package com.Johnny.wcx.features.items.miniapps

import android.app.Activity
import com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONObject

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
    // 热启动 / 切回前台时是否要出开屏（displayMode=2 才会弹；1 是冷启动路径，别拦）。
    private val methodSendShouldShowAd by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrandSplashAdLogic[AppBrandSplashAd]",
                "sendShouldShowAdWhenLaunchIfNeed, can not show ad, reason: %d, appId:%s",
                "sendShouldShowAdIfNeed, displayMode:%s  appId:%s, may show ad, preloadedService:%s"
            )
        }
    }

    // 广告 worker 最终把开屏广告显示出来的回调。把 show 改成 false 再放行，
    // 微信会走「不展示」分支并 closeSplashAdImmediately() 正常收起加载页；
    // 直接提前 return 反而会让加载页一直转圈。
    private val methodShowSplashAd by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.plugin.appbrand.ad.jsapi")
        matcher {
            usingEqStrings("MicroMsg.AppBrand.JsApiShowSplashAd[AppBrandSplashAd]", "showSplashAd, show splash ad")
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

        // 热启动/切回前台的开屏单独拦：只在 displayMode=2 时跳过原逻辑
        if (methodSendShouldShowAd.isPlaceholder) {
            WeLogger.w(TAG, "sendShouldShowAd 锚点失效，跳过")
        } else {
            runCatching {
                methodSendShouldShowAd.hookBefore {
                    runCatching {
                        if (args.getOrNull(0) as? Int != 2) return@runCatching
                        val m = method as? java.lang.reflect.Method
                        if (m?.returnType == Void.TYPE) result = null
                    }.onFailure { WeLogger.w(TAG, "sendShouldShowAd 处理失败: ${it.message}") }
                }
            }.onFailure { WeLogger.e(TAG, "hook sendShouldShowAd 失败", it) }
        }

        if (methodShowSplashAd.isPlaceholder) {
            WeLogger.w(TAG, "showSplashAd 锚点失效，跳过")
        } else {
            runCatching {
                methodShowSplashAd.hookBefore {
                    runCatching {
                        (args.getOrNull(1) as? JSONObject)?.put("show", false)
                    }.onFailure { WeLogger.w(TAG, "showSplashAd 改写失败: ${it.message}") }
                }
            }.onFailure { WeLogger.e(TAG, "hook showSplashAd 失败", it) }
        }

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
