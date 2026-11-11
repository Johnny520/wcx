package h.Hchat.hooks.items.grouplabel

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GroupChatLabelSettingsProvider : SimpleFeatureSettingsProvider(
    GroupChatLabelFeature.ID,
    "群聊标签",
    "分类管理群聊，在名单选择器中按标签批量选择",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
