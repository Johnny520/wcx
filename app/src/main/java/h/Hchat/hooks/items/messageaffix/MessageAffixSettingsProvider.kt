package h.Hchat.hooks.items.messageaffix

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MessageAffixSettingsProvider : SimpleFeatureSettingsProvider(
    MessageAffixFeature.ID,
    "发送文本格式",
    "自定义聊天文字消息的发送格式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
