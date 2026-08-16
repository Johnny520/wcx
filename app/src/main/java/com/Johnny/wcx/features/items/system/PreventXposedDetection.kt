package com.Johnny.wcx.features.items.system

import android.content.Context
import androidx.compose.material3.Text
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo

@Feature(name = "禁止微信检测 Xposed", categories = ["系统与隐私"], description = "防止微信检测 Xposed 框架是否存在")
object PreventXposedDetection : SwitchFeature(), IResolveDex {

    private val methodCheckStackTraceElements by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.app")
        matcher {
            usingEqStrings(
                "de.robv.android.xposed.XposedBridge",
                "com.zte.heartyservice.SCC.FrameworkBridge"
            )
        }
    }

    override fun onEnable() {
        if (methodCheckStackTraceElements.isPlaceholder || HostInfo.isHostGooglePlay) return

        methodCheckStackTraceElements.hookBefore {
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
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && HostInfo.isHostGooglePlay) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text("禁止微信检测 Xposed") },
                    text = {
                        Text("Google Play 版微信无此检测, 开启可能导致闪退, 已关闭功能!")
                    },
                    confirmButton = { TextButton(onDismiss) { Text("取消") } })
            }
            return false
        }

        return true
    }
}
