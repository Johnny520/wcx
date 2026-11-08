package h.Hchat.hooks.items.messagebubble

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MessageBubbleSettingsProvider : SimpleFeatureSettingsProvider(
    MessageBubbleFeature.ID,
    "消息气泡",
    "分别替换聊天左右侧气泡并适配深色模式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
