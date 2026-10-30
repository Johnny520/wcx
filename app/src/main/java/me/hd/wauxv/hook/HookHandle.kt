package me.hd.wauxv.hook

import androidx.annotation.Keep
import de.robv.android.xposed.XC_MethodHook

@Keep
class HookHandle(val unhook: XC_MethodHook.Unhook) {
    override fun toString(): String {
        return "HookHandle(delegate=${unhook.hookedMethod})"
    }
}
