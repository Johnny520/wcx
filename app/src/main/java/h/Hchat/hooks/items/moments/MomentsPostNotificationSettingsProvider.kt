package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsPostNotificationSettingsProvider : SimpleFeatureSettingsProvider(
    MomentsPostNotificationFeature.ID,
    "朋友圈发布通知",
    "指定好友发布朋友圈时提醒",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
