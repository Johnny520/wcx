package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SnsAntiRecallSettingsProvider : SimpleFeatureSettingsProvider(
    SnsAntiRecallFeature.ID,
    "朋友圈防撤回",
    "已缓存的朋友圈在对方删除或限制可见范围后继续显示",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
