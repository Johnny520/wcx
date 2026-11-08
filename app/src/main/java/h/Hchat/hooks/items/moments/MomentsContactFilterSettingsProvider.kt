package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsContactFilterSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsContactFilterFeature.ID,
    "朋友圈过滤",
    "按好友范围过滤朋友圈内容",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
