package com.Johnny.wcx.features.items.official_accounts

import android.content.ComponentName
import android.content.Intent
import de.robv.android.xposed.XC_MethodHook
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.features.api.ui.WeStartActivityApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger

@Feature(name = "恢复旧版公众号列表", categories = ["公众号"], description = "!!! 仅适用于旧版本微信 !!!\n新版本已在代码中移除旧 UI, 无法继续使用本功能")
object UseLegacyOfficialAccountsView : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: XC_MethodHook.MethodHookParam, intent: Intent) {
        val className = intent.component?.className
        if (className == "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
            className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI"
        ) {
            WeLogger.d("UseLegacyOfficialAccountsView", "redirected $className")
            intent.component = ComponentName(
                HostInfo.packageName,
                "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"
            )
        }
    }
}
