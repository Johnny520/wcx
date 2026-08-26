package com.Johnny.wcx.features.items.profile

import android.view.View
import android.widget.TextView
import com.tencent.mm.plugin.setting.ui.setting.EditSignatureUI
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.hookBeforeDirectly

@Feature(name = "移除个性签名限制", categories = ["个人资料"], description = "允许大于 30 字与包含特殊字符的个性签名")
object RemoveSignatureLimits : SwitchFeature(), IResolveDex {

    private lateinit var stringMatchesMethodUnhook: XC_MethodHook.Unhook

    private lateinit var setFiltersUnhook: XC_MethodHook.Unhook

    override fun onEnable() {
        EditSignatureUI::class.reflekt()
            .firstMethod { name = "initView" }.apply {
                hookBefore {
                    try {
                        setFiltersUnhook = "${PackageNames.WECHAT}.ui.widget.MMEditText".toClass().reflekt()
                            .firstMethod {
                                name = "setFilters"
                            }.hookBeforeDirectly {
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
                    } catch (e: Throwable) {}
                }

                hookAfter {
                    try {
                        val activity = thisObject as EditSignatureUI
                        activity.enableOptionMenu(true)
                        (activity.reflekt()
                            .firstField { type = TextView::class }
                            .get()!! as TextView).visibility = View.GONE
                    } catch (e: Throwable) {}
                }
            }

        methodTextWatcherAfterTextChanged.hookBefore {
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

        methodConfirmButtonOnClickListenerOnClick.apply {
            hookBefore {
                try {
                    stringMatchesMethodUnhook = String::class.java.reflekt()
                        .firstMethod { name = "matches" }
                        .hookBeforeDirectly {
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
                } catch (e: Throwable) {}
            }
            hookAfter {
                try {
                    stringMatchesMethodUnhook.unhook()
                    setFiltersUnhook.unhook()
                } catch (e: Throwable) {}
            }
        }
    }

    private val methodTextWatcherAfterTextChanged by dexMethod {
        searchPackages("${PackageNames.WECHAT}.plugin.setting.ui.setting")
        matcher {
            declaredClass {
                addMethod {
                    name = "<init>"
                    paramTypes("${PackageNames.WECHAT}.plugin.setting.ui.setting.EditSignatureUI", "java.lang.String")
                }
                addInterface { className = "android.text.TextWatcher" }
            }

            name = "afterTextChanged"
        }
    }

    private val methodConfirmButtonOnClickListenerOnClick by dexMethod {
        searchPackages("${PackageNames.WECHAT}.plugin.setting.ui.setting")
        matcher {
            declaredClass {
                addMethod {
                    name = "<init>"
                    paramTypes("${PackageNames.WECHAT}.plugin.setting.ui.setting.EditSignatureUI")
                }
                addInterface { className = $$"android.view.MenuItem$OnMenuItemClickListener" }
            }

            name = "onMenuItemClick"
            usingEqStrings(".*[", "].*")
        }
    }
}
