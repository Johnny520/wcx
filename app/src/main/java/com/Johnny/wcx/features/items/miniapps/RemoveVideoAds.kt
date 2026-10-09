package com.Johnny.wcx.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONObject

@Feature(name = "移除视频广告", categories = ["小程序"], description = "跳过小程序视频广告")
object RemoveVideoAds : SwitchFeature() {

    override fun onEnable() {
        // 精确匹配 4 参重载 subscribeHandler(String, String, int, String)。
        // 该类存在多个同名重载，仅按方法名取第一个可能在部分微信版本上命中错误重载，
        // 导致 args[0]/args[1] 语义错位、功能静默失效。
        runCatching {
            "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding".toClass().reflekt()
                .firstMethod {
                    name = "subscribeHandler"
                    parameters(String::class, String::class, Int::class, String::class)
                }
                .hookBefore {
                    if (args.getOrNull(0) as? String != "onVideoTimeUpdate") return@hookBefore
                    val payload = args.getOrNull(1) as? String ?: return@hookBefore
                    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return@hookBefore
                    json.put("position", 60)
                    json.put("duration", 1)
                    args[1] = json.toString()
                }
        }.onFailure {
            WeLogger.e("RemoveVideoAds", "安装 subscribeHandler Hook 失败", it)
        }
    }
}
