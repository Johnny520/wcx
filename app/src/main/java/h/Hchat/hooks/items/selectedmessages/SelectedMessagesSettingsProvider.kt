package h.Hchat.hooks.items.selectedmessages

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SelectedMessagesSettingsProvider : SimpleFeatureSettingsProvider(
    SelectedMessagesFeature.ID,
    "群发助手",
    "从多选消息菜单群发或定时转发聊天记录",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
