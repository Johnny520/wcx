package h.Hchat.hooks.items.keepalive

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class WeChatKeepAliveSettingsProvider : SimpleFeatureSettingsProvider(
    WeChatKeepAliveFeature.ID,
    "微信强保活",
    "前台服务、WakeLock 和 Root 白名单提高息屏存活率",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
