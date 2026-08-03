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
                                    result = null
                                } catch (_: Throwable) {}
                            }
                    } catch (_: Throwable) {}
                }

                hookAfter {
                    try {
                        val activity = thisObject as EditSignatureUI
                        activity.enableOptionMenu(true)
                        (activity.reflekt()
                            .firstField { type = TextView::class }
                            .get()!! as TextView).visibility = View.GONE
                    } catch (_: Throwable) {}
                }
            }

        methodTextWatcherAfterTextChanged.hookBefore {
            try {
                result = null
            } catch (_: Throwable) {}
        }

        methodConfirmButtonOnClickListenerOnClick.apply {
            hookBefore {
                try {
                    stringMatchesMethodUnhook = String::class.java.reflekt()
                        .firstMethod { name = "matches" }
                        .hookBeforeDirectly {
                            try {
                                result = false
                            } catch (_: Throwable) {}
                        }
                } catch (_: Throwable) {}
            }
            hookAfter {
                try {
                    stringMatchesMethodUnhook.unhook()
                    setFiltersUnhook.unhook()
                } catch (_: Throwable) {}
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
