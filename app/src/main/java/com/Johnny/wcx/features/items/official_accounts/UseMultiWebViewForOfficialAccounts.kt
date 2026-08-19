package com.Johnny.wcx.features.items.official_accounts

import android.content.Intent
import com.Johnny.wcx.features.api.ui.WeStartActivityApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HookParam
import com.Johnny.wcx.utils.WeLogger

@Feature(
    name = "允许公众号网页多开", categories = ["公众号"],
    description = "为公众号时间线预加载网页启用多任务"
)
object UseMultiWebViewForOfficialAccounts : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    private const val tag = "UseMultiWebViewForOfficialAccounts"

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className ?: return
        if (!className.endsWith(".ui.timeline.preload.ui.TmplWebViewMMUI")) return

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        WeLogger.d(tag, "enabled multi webview for $className")
    }
}
