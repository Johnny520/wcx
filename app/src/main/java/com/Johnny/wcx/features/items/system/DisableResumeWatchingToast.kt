package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.core.SwitchFeature

@Feature(
    name = "禁用「刚刚在看」提醒",
    categories = ["系统与隐私"],
    description = "禁用微信启动时弹出的「刚刚在看的「视频号」」继续浏览提示条"
)
object DisableResumeWatchingToast : SwitchFeature(), IResolveDex {

    private val methodShowRecoveryToast by dexMethod(allowFailure = true) {
        // 注意：只保留仍被引用的两串会多命中到无关 lambda（实测 8.0.79 落到 f94/a.invoke()），
        // 因此保留原始 5 串组合。其中 "recoveryObj == null " / "toast_button" /
        // "topActivity == null or isFinishing or isDestroyed" 在 8.0.76 起已被微信裁成孤儿串
        // （string_ids 残留、无 const-string 引用）→ 该锚点在 8.0.76+ 恒不命中，
        // 用 allowFailure 干净降级：功能不生效，但绝不 hook 到错误方法。
        matcher {
            paramCount = 0
            usingEqStrings(
                "MicroMsg.RecoveryHelper",
                "view_exp",
                "recoveryObj == null ",
                "toast_button",
                "topActivity == null or isFinishing or isDestroyed",
            )
        }
    }

    override fun onEnable() {
        methodShowRecoveryToast.hookBefore {
            result = null
        }
    }
}
