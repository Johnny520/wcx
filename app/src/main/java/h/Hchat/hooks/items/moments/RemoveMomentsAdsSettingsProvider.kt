package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class RemoveMomentsAdsSettingsProvider : SimpleFeatureSettingsProvider(
    RemoveMomentsAdsFeature.ID,
    "去除朋友圈广告",
    "阻止朋友圈广告信息解析和展示",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
