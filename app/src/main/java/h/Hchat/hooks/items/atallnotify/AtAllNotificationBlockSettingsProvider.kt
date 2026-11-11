package h.Hchat.hooks.items.atallnotify

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AtAllNotificationBlockSettingsProvider : SimpleFeatureSettingsProvider(
    AtAllNotificationBlockFeature.ID,
    "屏蔽艾特所有人",
    "按选择的群聊拦截艾特所有人通知",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
