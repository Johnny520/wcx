package com.Johnny.wcx.features.items.system

import android.provider.Settings
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "环境伪装", categories = ["系统与隐私"], description = "伪装未启用 ADB, 开发者选项或 VPN, 可能有助于通过人脸等场景下的环境安全性检测")
object SpoofEnvironment : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        Settings.Global::class.reflekt()
            .firstMethod {
                name = "getInt"
                parameterCount = 3
            }.hookBefore {
                try {
                    val name = args[1] as? String? ?: return@hookBefore
                    if (name == "adb_enabled") {
                        // 仅当原方法返回 int 时才设置 result = 0，避免对非 int 方法（如 getInstance）造成 ClassCastException
                        if (method is java.lang.reflect.Method) {
                            val returnType = (method as java.lang.reflect.Method).returnType
                            if (returnType == Int::class.javaPrimitiveType || returnType == Integer::class.java) {
                                result = 0
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }

        Settings.Secure::class.reflekt()
            .firstMethod {
                name = "getInt"
                parameterCount = 3
            }.hookBefore {
                try {
                    val name = args[1] as? String? ?: return@hookBefore
                    if (name == "development_settings_enabled") {
                        // 仅当原方法返回 int 时才设置 result = 0，避免对非 int 方法（如 getInstance）造成 ClassCastException
                        if (method is java.lang.reflect.Method) {
                            val returnType = (method as java.lang.reflect.Method).returnType
                            if (returnType == Int::class.javaPrimitiveType || returnType == Integer::class.java) {
                                result = 0
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }

        methodIsVpnEnabled.hookBefore {
            try {
                // 仅当原方法返回 boolean 时才设置 result = false
                if (method is java.lang.reflect.Method) {
                    val returnType = (method as java.lang.reflect.Method).returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                        result = false
                    }
                }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }
    }

    private val methodIsVpnEnabled by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.WalletSecurityUtilService")
            }

            usingEqStrings("connectivity")
            usingNumbers(4)
        }
    }
}
