package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsAutoLikeSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsAutoLikeFeature.ID,
    "朋友圈自动点赞",
    "按好友、内容和时间规则自动点赞",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
