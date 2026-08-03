package com.Johnny.wcx.features.items.moments

import com.tencent.mm.plugin.sns.storage.ADInfo
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "拦截朋友圈广告", categories = ["朋友圈"], description = "拦截朋友圈广告")
object RemoveMomentsAds : SwitchFeature() {

    private const val TAG = "RemoveMomentsAds"

    override fun onEnable() {
        ADInfo::class.reflekt()
            .firstConstructor {
                parameters(String::class)
            }
            .hookBefore {
                try {
                    WeLogger.i(TAG, "blocked ad")
                    // 构造函数返回 void，设置 result = null 阻断构造
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType == Void.TYPE) {
                            result = null
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }
    }
}
