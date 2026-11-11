package h.Hchat.hooks.items.messageforward

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MessageForwardSettingsProvider : SimpleFeatureSettingsProvider(
    MessageForwardFeature.ID,
    "转发",
    "从消息、收藏和朋友圈菜单使用转发功能",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
