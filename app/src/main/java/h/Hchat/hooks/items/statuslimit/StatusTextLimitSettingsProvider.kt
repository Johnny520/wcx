package h.Hchat.hooks.items.statuslimit

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class StatusTextLimitSettingsProvider : SimpleFeatureSettingsProvider(
    StatusTextLimitFeature.ID,
    "解除状态词长度限制",
    "允许个人状态词超过 10 个字",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
