package com.Johnny.wcx.features.items.miniapps

import android.app.Activity
import com.tencent.mm.plugin.appbrand.ad.ui.AppBrandAdUI
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "移除开屏广告", categories = ["小程序"], description = "跳过小程序开屏广告")
object RemoveSplashAds : SwitchFeature(), IResolveDex {

    private val methodIsAdContact by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.AppBrandAdUtils[AppBrandSplashAd]", "isAdContact, appId:%s, canShowAd:%s")
        }
    }
    private val methodAdDataCallback by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.jsapi.auth")
        matcher {
            usingEqStrings(
                "MicroMsg.AppBrand.JsApiAdOperateWXData[AppBrandSplashAd]", "cgi callback, callbackId:%s, service not running or preloaded"
            )
        }
    }
    private val methodCheckCanShowAd by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand")
        matcher {
            usingEqStrings("MicroMsg.AppBrandAdUtils[AppBrandSplashAd]", "checkCanShowAd, show ad (splash ad debug mode open)")
        }
    }

    override fun onEnable() {
        methodIsAdContact.hookBefore {
            try {
                result = false
            } catch (_: Throwable) {}
        }

        methodAdDataCallback.hookBefore {
            try {
                result = null
            } catch (_: Throwable) {}
        }

        methodCheckCanShowAd.hookBefore {
            try {
                result = false
            } catch (_: Throwable) {}
        }

        AppBrandAdUI::class.java.hookBeforeOnCreate {
            try {
                val activity = thisObject as Activity
                activity.finish()
                result = null
            } catch (_: Throwable) {}
        }
    }
}
