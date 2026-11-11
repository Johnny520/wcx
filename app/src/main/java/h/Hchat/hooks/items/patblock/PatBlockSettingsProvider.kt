package h.Hchat.hooks.items.patblock

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class PatBlockSettingsProvider : SimpleFeatureSettingsProvider(
    PatBlockFeature.ID,
    "禁止拍一拍",
    "双击聊天头像时不发送拍一拍",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
