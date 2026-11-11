package h.Hchat.hooks.items.tablet

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class WeChatTabletSettingsProvider : SimpleFeatureSettingsProvider(
    WeChatTabletFeature.ID,
    "平板模式",
    "伪装平板登录微信",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
