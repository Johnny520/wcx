package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用「转发截图」提示", categories = ["系统与隐私"], description = "你在教我做事?")
object DisableShareScreenshotToast : SwitchFeature(), IResolveDex {

    // 8.0.80 锚点重描：旧串 "showShareTongue, shareTongue already showing..." 已被删除（0 引用）。
    // 新版截图分享 UI 改为 BottomSheet 体系，显示入口为 initImageBottomSheet
    // （0x28005032 实测：TAG + 该串同方法 AND 唯一命中 screenshot 包内的实现方法，返回 boolean）。
    // 双串回落：新串优先，旧串保留给低版本。
    private val methodDisplayToast by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.feature.api.screenshot")
        matcher {
            usingEqStrings("MicroMsg.ScreenShotShareService", "initImageBottomSheet, getTopActivity failed")
        }
    }

    private val methodDisplayToastLegacy by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.feature.api.screenshot")
        matcher {
            usingEqStrings("MicroMsg.ScreenShotShareService", "showShareTongue, shareTongue already showing, reset onClick & countDown")
        }
    }

    override fun onEnable() {
        // 8.0.80 新锚点（initImageBottomSheet，返回 boolean → 返回 false 阻断弹层）
        if (!methodDisplayToast.isPlaceholder) {
            methodDisplayToast.hookBefore {
                try {
                    val m = method
                    if (m is java.lang.reflect.Method) {
                        when (m.returnType) {
                            java.lang.Void.TYPE -> result = null
                            java.lang.Boolean.TYPE -> result = false
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }
        }
        // 低版本旧锚点（showShareTongue，void → 置空）
        if (!methodDisplayToastLegacy.isPlaceholder) {
            methodDisplayToastLegacy.hookBefore {
                try {
                    val m = method
                    if (m is java.lang.reflect.Method && m.returnType == java.lang.Void.TYPE) {
                        result = null
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获
                }
            }
        }
    }
}
