package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用 WebView 安全警告", categories = ["系统与隐私"], description = "禁用 WebView 相关的安全警告提示")
object DisableWebViewSafetyWarnings : SwitchFeature(), IResolveDex {
    private val methodGetIsInterceptEnabled by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.WebViewHighRiskAdH5Interceptor",
                "isInterceptEnabled, expt="
            )
        }
    }
    private val methodGetIsUrlSafe by dexMethod {
        matcher {
            declaredClass(methodGetIsInterceptEnabled.method.declaringClass)
            usingEqStrings("http", "https")
        }
    }

    override fun onEnable() {
        methodGetIsInterceptEnabled.hookBefore {
            try {
                // 仅当原方法返回 boolean 时才设置 result = false，避免对非 boolean 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                        result = false
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }

        methodGetIsUrlSafe.hookBefore {
            try {
                // 仅当原方法返回 boolean 时才设置 result = true，避免对非 boolean 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                        result = true
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
            }
        }
    }
}
