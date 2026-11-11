package h.Hchat.hooks.items.profileid

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ProfileIdSettingsProvider : SimpleFeatureSettingsProvider(
    ProfileIdFeature.ID,
    "资料页显示ID",
    "在好友和群聊资料页显示可复制的微信 ID",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
