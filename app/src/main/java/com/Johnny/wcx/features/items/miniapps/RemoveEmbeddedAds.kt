package com.Johnny.wcx.features.items.miniapps

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import org.json.JSONObject
import java.lang.reflect.Field

@Feature(name = "移除嵌入广告", categories = ["小程序"], description = "移除小程序嵌入广告")
object RemoveEmbeddedAds : SwitchFeature(), IResolveDex {

    private val ctorNetSceneJSOperateWxData by dexConstructor {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
            }
        }
    }
    private val methodBaseTransferRequestOnLoad by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.BaseTransferRequest")
            paramTypes("com.tencent.mm.plugin.brandservice.api.TransferResultInfo")
        }
    }

    /**
     * 奖励/激励视频广告位（用户主动看完拿奖励），不属于被动展示的嵌入广告，不拦。
     * 「跳过」和「删掉」是两回事：把激励视频的请求也清空，用户拿不到奖励。
     */
    private val REWARDED_AD_POS_IDS = setOf(
        "1030436212907001", // rewardedVideoAd
        "5010365819466098", // wxAppVideo
        "7090665964306299", // wxAppPreVideoAd
    )

    private var protoField: Field? = null

    /** 广告请求里表示广告位/插槽的字段，清空后接口拿不到可投广告的位置。 */
    private val AD_KEYS = arrayOf("ad_unit_id", "adunitid", "adUnitId", "pos_id", "ad_slot", "ad_slot_data")

    /**
     * 小程序广告 CGI 的 api_name 判定。
     *
     * 旧版精确串是 `webapi_getadvert`；8.0.80 实测该串已从 18 个 dex 里彻底消失（微信改了广告接口），
     * 所以改为按语义匹配：名字里带 advert / adunit / ad_unit 的都按广告接口处理。
     */
    private fun isAdApiName(name: String): Boolean {
        if (name.isBlank()) return false
        val lower = name.lowercase()
        return lower.contains("advert") || lower.contains("adunit") || lower.contains("ad_unit")
    }

    override fun onEnable() {
        ctorNetSceneJSOperateWxData.hookBefore {
            // 从全部实参中定位承载 JSON 的那个（构造器参数顺序可能随版本变化）。
            // 原实现把 api_name 当字符串字面量比较（恒为 false），导致本功能从未生效。
            val idx = args.indexOfFirst { arg ->
                arg is String && runCatching {
                    isAdApiName(JSONObject(arg).optString("api_name"))
                }.getOrDefault(false)
            }
            if (idx < 0) return@hookBefore
            val json = runCatching { JSONObject(args[idx] as String) }.getOrNull() ?: return@hookBefore
            val data = json.optJSONObject("data") ?: JSONObject().also { json.put("data", it) }
            // 激励视频放行：清空 ad_unit_id 会让「看完拿奖励」直接失效
            val posId = data.optString("pos_id")
            if (posId.isBlank() || posId in REWARDED_AD_POS_IDS) return@hookBefore
            AD_KEYS.forEach { key -> data.remove(key) }
            args[idx] = json.toString()
        }

        methodBaseTransferRequestOnLoad.hookBefore {
            // 参数位可空 / 字段结构变了都要安全退出：宁可不去广告，
            // 也不能让一条广告 hook 把品牌服务和小程序的业务数据一起吞掉（表现是列表全空）。
            val transferResultInfo = args.getOrNull(0) ?: return@hookBefore
            val field = protoField ?: runCatching {
                transferResultInfo.reflekt()
                    .firstField {
                        type { !it.isBuiltin }
                    }.self
            }.getOrNull()?.also { protoField = it } ?: return@hookBefore

            val proto = field.get(transferResultInfo) ?: return@hookBefore
            proto.reflekt()
                .fields {
                    type = String::class
                }.forEach {
                    val jsonStr = it.get() as? String? ?: return@forEach
                    if (jsonStr.isBlank()) return@forEach
                    val json = runCatching { JSONObject(jsonStr) }.getOrElse { return@forEach }
                    if (!json.has("ad_slot_data")) return@forEach
                    it.set("{}")
                }
        }
    }
}
