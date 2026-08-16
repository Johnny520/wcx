package com.Johnny.wcx.features.items.miniapps

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.enumValueOfClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.lang.reflect.Modifier

@Feature(name = "去除菜单限制", categories = ["小程序"], description = "移除小程序右上角菜单的限制")
object RemoveMenuLimits : SwitchFeature(), IResolveDex {

    private lateinit var showAndClickableEnumValue: Any

    override fun onEnable() {
        listOf(
            methodGetMenuItemVisibility1,
            methodGetMenuItemVisibility2
        ).forEach {
            it.hookBefore {
                try {
                    if (!::showAndClickableEnumValue.isInitialized) {
                        val returnType = methodGetMenuItemVisibility1.method.returnType
                        showAndClickableEnumValue = enumValueOfClass(returnType, "SHOW_CLICKABLE")
                    }
                    // 仅当返回值类型匹配时才设置 result，避免类型不匹配造成 ClassCastException
                    if (method is java.lang.reflect.Method) {
                        val returnType = (method as java.lang.reflect.Method).returnType
                        if (returnType.isInstance(showAndClickableEnumValue)) {
                            result = showAndClickableEnumValue
                        }
                    }
                } catch (e: Throwable) {
                    // 兜底异常捕获，防止单条 Hook 异常导致微信主线程崩溃
                }
            }
        }
    }

    private val methodGetMenuItemVisibility1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.menu")

        matcher {
            declaredClass {
                superClass {
                    modifiers(AccessFlagsMatcher(Modifier.ABSTRACT))
                }

                addMethod {
                    usingNumbers(39)
                }
            }

            returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
        }
    }

    private val methodGetMenuItemVisibility2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.menu")
        matcher {
            declaredClass {
                superClass {
                    modifiers(AccessFlagsMatcher(Modifier.ABSTRACT))
                }

                addMethod {
                    usingNumbers(30)
                }
            }

            returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
        }
    }
}
