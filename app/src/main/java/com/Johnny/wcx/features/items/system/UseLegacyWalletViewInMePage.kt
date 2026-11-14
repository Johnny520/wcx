package com.Johnny.wcx.features.items.system

import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "恢复旧版「我」界面卡包", categories = ["系统与隐私"], description = "在「我」界面使用旧版「卡包」而非「小店与卡包」")
object UseLegacyWalletViewInMePage : SwitchFeature(), IResolveDex {
    private const val TAG = "UseLegacyWalletViewInMePage"

    override fun onEnable() {
        // 8.0.80：MicroMsg.EcsOrderService / MoreTabUI 相关日志串均已成孤儿串，
        // 两个锚点都解析不到。对 placeholder 注册 hook 会抛异常，这里整体跳过并留痕。
        if (methodGetOrderAndCardEntranceInfo.isPlaceholder || methodMoreTabUIHandlePrefOnClick.isPlaceholder) {
            WeLogger.w(TAG, "锚点在当前微信版本已失效（钱包卡包入口实现已改版），跳过")
            return
        }
        methodGetOrderAndCardEntranceInfo.hookAfter {
            result.reflekt()
                .firstField {
                    type = Int::class.java
                }.set(1)
        }

        methodMoreTabUIHandlePrefOnClick.hookBefore {
            val field = Preference::class.reflekt()
                .firstField { type = String::class }

            val pref = args[1] as Preference
            if (field.get(pref) as? String? == "settings_mm_cardpackage_new") {
                field.set(pref, "settings_mm_cardpackage")
            }
        }
    }

    private val methodGetOrderAndCardEntranceInfo by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.EcsOrderService", "getOrderAndCardEntranceInfo use finder logic")
        }
    }

    private val methodMoreTabUIHandlePrefOnClick by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.MoreTabUI", "account has not already!", "onPreferenceTreeClick")
        }
    }
}
