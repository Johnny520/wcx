package com.Johnny.wcx.features.items.scripting_python

import androidx.activity.ComponentActivity
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.scripting_python.plugin.PythonPluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Feature(
    name = "脚本引擎 (Python)",
    categories = ["脚本 (Python)"],
    description = "Python 脚本引擎"
)
object PythonScriptingFeature : ClickableFeature() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onEnable() {
        scope.launch {
            PythonPluginManager.discover()
            PythonPluginManager.activateDesired()
        }
    }

    override fun onDisable() {
        scope.launch { PythonPluginManager.deactivateAll() }
    }

    override fun onClick(context: ComponentActivity) {
        // WCX 没有独立的 Python 设置 Activity：打开脚本浮层，Python 入口在脚本页内。
        runCatching { h.Hchat.ui.miuix.MiuixSettingsPage.showScriptPlugin(context) }
            .onFailure {
                android.widget.Toast.makeText(context, "无法打开脚本页面", android.widget.Toast.LENGTH_SHORT).show()
            }
    }
}
