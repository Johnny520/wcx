package h.Hchat.hooks.items.hideavatar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class HideChatAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    HideChatAvatarFeature.ID,
    "隐藏头像",
    "分别隐藏聊天中自己或对方的头像",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
