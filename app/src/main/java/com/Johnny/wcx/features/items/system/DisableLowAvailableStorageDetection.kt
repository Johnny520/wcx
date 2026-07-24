package com.Johnny.wcx.features.items.system

import android.app.Activity
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(
    name = "禁用存储空间不足检测",
    categories = ["系统与隐私"],
    description = "「隐藏应用列表」等隐藏 Root 模块有时会使应用获取到的可用空间不正确, 而微信在可用空间不足时会强制要求清理空间才可继续使用, 本功能移除了该限制"
)
object DisableLowAvailableStorageDetection : SwitchFeature(), IResolveDex {

    private val methodSplashActivitySplashFinished by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.splash.SplashActivity"
            usingEqStrings("WxSplash.SplashActivity", "Call splashFinished.")
        }
    }
    private val classStaticValuesHolder by dexClass {
        matcher {
            usingEqStrings("UIPageFragmentActivity", "LuckyMoneyNewPrepareUI", "RemittanceUI")
        }
    }

    override fun onEnable() {
        methodSplashActivitySplashFinished.hookBefore {
            classStaticValuesHolder.clazz.reflekt()
                .firstField { type = Boolean::class }
                .setStatic(false)
        }

        "com.tencent.mm.plugin.clean.ui.fileindexui.StorageDisableAlertUI"
            .toClass().hookAfterOnCreate {
                val activity = thisObject as Activity
                activity.finish()
            }
    }
}
