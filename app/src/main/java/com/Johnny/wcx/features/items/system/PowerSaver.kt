package com.Johnny.wcx.features.items.system

import android.os.PowerManager
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "省电模式", categories = ["系统与隐私"], description = "通过一些措施, 减少微信耗电量")
object PowerSaver : SwitchFeature() {

    override fun onEnable() {
        PowerManager.WakeLock::class.reflekt().apply {
            methods {
                name = "acquire"
            }.forEach {
                it.hookBefore {
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

            firstMethod {
                name = "release"
                parameterCount = 1
            }.hookBefore {
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
}
