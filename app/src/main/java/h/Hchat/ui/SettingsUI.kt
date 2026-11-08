package h.Hchat.ui

import android.content.Context
import h.Hchat.ui.miuix.MiuixSettingsPage

object SettingsUI {
    @JvmStatic
    fun show(context: Context) {
        MiuixSettingsPage.show(context)
    }

    @JvmStatic
    fun showScriptPluginAgent(context: Context) {
        MiuixSettingsPage.showScriptPluginAgent(context)
    }

    @JvmStatic
    fun showFeature(context: Context, featureId: String): Boolean {
        return MiuixSettingsPage.showFeature(context, featureId)
    }
}
