package h.Hchat.ui.miuix

import android.app.Activity
import android.view.View

// TODO(migration stub): WCX 切桥桩。
// 原实现依赖 miuix / compose / lifecycle / navigationevent 等重量级 UI，无法整包导入。
// 这里仅保留调用方用到的成员签名，保证调用方可编译；方法体返回空值。
//
// 调用点：h/Hchat/hooks/items/script/ScriptPluginBridge.kt
//   - ScriptFloatingGlassBarHost.apply(activity, target, options, restored@{...})
//   - apply 的返回值被传入 ScriptFloatingGlassBarHandle 的 internal 构造函数，
//     其参数类型为 h.Hchat.ui.miuix.FloatingGlassBarHostHandle（与该桩类型对齐）。
//   - ScriptFloatingGlassBarHandle.isApplied() 会调用 delegate.isApplied()，
//     故 FloatingGlassBarHostHandle 必须同时提供 restore() 与 isApplied(): Boolean。
internal interface FloatingGlassBarHostHandle {
    fun restore()
    fun isApplied(): Boolean
}

internal object ScriptFloatingGlassBarHost {
    fun apply(
        activity: Activity,
        bottomBar: View,
        rawOptions: Map<*, *>?,
        restoredCallback: () -> Unit = {}
    ): FloatingGlassBarHostHandle? {
        // TODO(migration stub): 挂载悬浮玻璃底栏
        return null
    }
}
