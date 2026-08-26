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

    private val methodShowRecoveryToast by dexMethod {
        matcher {
            paramCount = 0
            usingEqStrings(
                "MicroMsg.RecoveryHelper",
                "topActivity == null or isFinishing or isDestroyed",
                "recoveryObj == null ",
                "toast_button",
                "view_exp",
            )
        }
    }

    override fun onEnable() {
        methodShowRecoveryToast.hookBefore {
            result = null
        }
    }
}
