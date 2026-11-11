package com.Johnny.wcx.extensions

import android.app.Activity
import android.content.Intent
import android.widget.Toast

/** Dependency-prompt dialogs and extension screen entry shared by extension-pack consumers. */
object ExtensionPackDialogs {

    /** 扩展包入口：WCX 未内置扩展管理器页面，直接提示到脚本页安装（避免依赖不存在的 Activity）。 */
    fun openExtensions(activity: Activity, pack: Any?, autoDownload: Boolean) {
        Toast.makeText(
            activity,
            "Python 运行时扩展包请在脚本 → Python 脚本页面安装",
            Toast.LENGTH_SHORT
        ).show()
    }
}
