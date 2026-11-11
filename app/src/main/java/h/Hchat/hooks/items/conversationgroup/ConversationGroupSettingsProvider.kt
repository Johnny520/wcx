package h.Hchat.hooks.items.conversationgroup

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ConversationGroupSettingsProvider : SimpleFeatureSettingsProvider(
    ConversationGroupFeature.ID,
    "聊天分组",
    "按自定义分组整理聊天，支持多级分类",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
