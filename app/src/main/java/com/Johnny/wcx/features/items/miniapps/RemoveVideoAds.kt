package com.Johnny.wcx.features.items.miniapps

import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import org.json.JSONObject

@Feature(name = "移除视频广告", categories = ["小程序"], description = "跳过小程序视频广告")
object RemoveVideoAds : SwitchFeature() {

    override fun onEnable() {
        "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass().reflekt()
            .firstMethod { name = "subscribeHandler" }
            .hookBefore {
                val type = args[0] as String? ?: ""
                val json = args[1] as String? ?: ""

                if (type == "onVideoTimeUpdate") {
                    val json = JSONObject(json)
                    json.put("position", 60)
                    json.put("duration", 1)
                    args[1] = json.toString()
                }
            }
    }
}
