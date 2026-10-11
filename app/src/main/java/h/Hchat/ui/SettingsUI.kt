package h.Hchat.ui

import android.content.Context

// TODO(migration stub): WCX 切桥桩。
// 原实现依赖 h.Hchat.ui.miuix.MiuixSettingsPage（重量级 Miuix 设置页面），无法整包导入。
// 这里仅保留调用方用到的成员签名，保证调用方可编译；方法体为空实现。
// 调用点：h/Hchat/hooks/api/ui/SettingsInjector.java
//   - SettingsUI.show(context)                (context: android.content.Context)
//   - SettingsUI.showScriptPluginAgent(context)
object SettingsUI {
    @JvmStatic
    fun show(context: Context) {
        // TODO(migration stub): 打开模块设置页
    }

    @JvmStatic
    fun showScriptPluginAgent(context: Context) {
        // TODO(migration stub): 打开脚本插件 Agent 设置页
    }

    // 与原始 h.Hchat.ui.SettingsUI 保持签名一致（原文件成员）。
    @JvmStatic
    fun showFeature(context: Context, featureId: String): Boolean {
        // TODO(migration stub): 打开指定功能的设置页
        return false
    }
}
