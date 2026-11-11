package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsKeywordBlockSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsKeywordBlockFeature.ID,
    "朋友圈关键词屏蔽",
    "隐藏正文命中关键词的朋友圈",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
