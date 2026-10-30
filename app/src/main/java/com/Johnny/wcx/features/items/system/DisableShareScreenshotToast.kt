package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用「转发截图」提示", categories = ["系统与隐私"], description = "你在教我做事?")
object DisableShareScreenshotToast : SwitchFeature(), IResolveDex {

    // 允许失败：8.0.80 起 "showShareTongue, shareTongue already showing..." 日志串已被删除
    // （0 引用），只剩类 TAG "MicroMsg.ScreenShotShareService"（同包内 10 个类都有），
    // 无法唯一定位。这里保留原锚点 + allowFailure，配合下方 unavailableReason 明确告知用户，
    // 而不是抛异常或让用户开了开关没反应。
    private val methodDisplayToast by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.feature.api.screenshot")
        matcher {
            usingEqStrings("MicroMsg.ScreenShotShareService", "showShareTongue, shareTongue already showing, reset onClick & countDown")
        }
    }

    override val unavailableReason: String?
        get() = if (methodDisplayToast.isPlaceholder) {
            "当前微信版本已移除「转发截图」提示的相关实现，该功能不可用"
        } else null

    override fun onEnable() {
        // 占位委托直接跳过，避免对未解析的委托注册 Hook
        if (methodDisplayToast.isPlaceholder) return
        methodDisplayToast.hookBefore {
            try {
                // 仅当原方法返回 void 时才设置 result = null，避免对非 void 方法（如 getInstance）造成 ClassCastException
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
