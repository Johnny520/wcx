package h.Hchat.hooks.items.groupnicknamecolor

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GroupNicknameColorSettingsProvider : SimpleFeatureSettingsProvider(
    GroupNicknameColorSettings.FEATURE_ID,
    "群昵称自定义颜色",
    "自定义群聊成员昵称的颜色和粗细",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
