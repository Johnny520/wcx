package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsAutoCommentSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsAutoCommentFeature.ID,
    "朋友圈自动评论",
    "按好友、内容和时间规则自动评论",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
