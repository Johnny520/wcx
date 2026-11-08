package h.Hchat.hooks.items.grouplabel

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class QuickGroupChatLabelSettingsProvider : SimpleFeatureSettingsProvider(
    QuickGroupChatLabelFeature.ID,
    "快捷设置群聊标签",
    "长按群聊会话快速设置模块群聊标签",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
