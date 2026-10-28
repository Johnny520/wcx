package com.Johnny.wcx.features.items.system

import android.content.Context
import android.widget.Button
import androidx.compose.material3.Text
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog

@Feature(name = "强制平板模式", categories = ["系统与隐私"], description = "让微信将当前设备识别为平板")
object ForceTabletMode : SwitchFeature(), IResolveDex {

    // 设备型号白名单串 "Lenovo TB-9707F" 在两版都唯一命中「是否平板」判断方法：
    // 8.0.76 → com.tencent.mm.ui.bk.B(in5.x0)Z，8.0.79 → com.tencent.mm.ui.j9.a()Z。
    // 原组合里的 "eebbk" 与 "isRoyoleFoldableDevice!!!" 现已是孤儿串（无任何方法引用），
    // 拼在一起会让 matcher 恒不命中 —— 故只保留单串锚点，并补 allowFailure 防解析失败抛异常。
    private val methodIsTablet by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("Lenovo TB-9707F")
        }
    }
    private val methodOtherDeviceLoginButtonIsVisible by dexMethod {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }

    override fun onEnable() {
        if (methodIsTablet.isPlaceholder) {
            com.Johnny.wcx.utils.WeLogger.w(
                "ForceTabletMode",
                "methodIsTablet 未解析（当前微信版本无设备型号判断锚点），跳过 hook"
            )
        }
        if (!methodIsTablet.isPlaceholder) methodIsTablet.hookBefore {
            try {
                // 仅当原方法返回 boolean 时才设置 result = true，避免对非 boolean 方法（如 getInstance）造成 ClassCastException
                if (method is java.lang.reflect.Method) {
                val returnType = (method as java.lang.reflect.Method).returnType
                if (returnType == Boolean::class.javaPrimitiveType || returnType == java.lang.Boolean::class.java) {
                    result = true
                }
            }
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }

        methodOtherDeviceLoginButtonIsVisible.hookBefore {
            try {
                val view = args[0] as? Button? ?: return@hookBefore
                if (view.isGone) view.isVisible = true
            } catch (e: Throwable) {
                // 兜底异常捕获
            }
        }

        "com.tencent.mm.plugin.account.ui.LoginHistoryUI".toClass().reflekt().firstMethod("initView").hookAfter {
            // 不同微信版本的字段布局可能不同，firstField 可能匹配不到或返回 null。
            // 原实现对 get() 直接 !! 强转，一旦为空即抛 NPE 并可能拖垮宿主主进程。
            val btn = runCatching {
                thisObject.reflekt().firstField { type = Button::class }.get() as? Button
            }.getOrNull() ?: return@hookAfter
            btn.isVisible = true
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
