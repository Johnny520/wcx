package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsBottomDetailSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsBottomDetailFeature.ID,
    "朋友圈底部详情",
    "自定义朋友圈底部时间和详情格式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
