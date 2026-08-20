@file:Suppress("NOTHING_TO_INLINE")

package com.Johnny.wcx.utils

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import dev.ujhhgtg.reflekt.reflected.BaseReflectedMethod
import dev.ujhhgtg.reflekt.reflected.ReflectedConstructor
import java.lang.reflect.Executable

typealias HookAction = XC_MethodHook.MethodHookParam.() -> Unit

// most extension methods are inside BaseFeature for enabled state checking

inline fun BaseReflectedMethod.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
) = self.hookBeforeDirectly(priority, action)

inline fun Executable.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = XposedBridge.hookMethod(
    this, object : XC_MethodHook(priority) {
        override fun beforeHookedMethod(param: MethodHookParam) {
            action(param)
        }
    }
)

inline fun BaseReflectedMethod.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = self.hookAfterDirectly(priority, action)

inline fun ReflectedConstructor<*>.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = self.hookAfterDirectly(priority, action)

inline fun Executable.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
): XC_MethodHook.Unhook = XposedBridge.hookMethod(
    this, object : XC_MethodHook(priority) {
        override fun afterHookedMethod(param: MethodHookParam) {
            action(param)
        }
    }
)

inline fun BaseReflectedMethod.hookDirectly(
    hook: XC_MethodHook
): XC_MethodHook.Unhook = self.hookDirectly(hook)

inline fun Executable.hookDirectly(
    hook: XC_MethodHook
): XC_MethodHook.Unhook = XposedBridge.hookMethod(this, hook)

@Suppress("NOTHING_TO_INLINE")
fun XC_MethodHook.MethodHookParam.invokeOriginal(thisObject: Any? = null, args: Array<Any?>? = null): Any? =
    XposedBridge.invokeOriginalMethod(method, thisObject ?: this.thisObject, args ?: this.args)

// 上游新 Hook API 兼容别名：映射回 Xposed 旧类型。
// 上游重构把 hook 抽象为 IHookBridge，但这几个新功能文件仍直接引用这些符号。
typealias HookParam = XC_MethodHook.MethodHookParam

typealias HookHandle = XC_MethodHook.Unhook

abstract class HookCallback(priority: Int = 50) : XC_MethodHook(priority) {
    protected abstract override fun beforeHookedMethod(param: HookParam)

    protected abstract override fun afterHookedMethod(param: HookParam)
}

