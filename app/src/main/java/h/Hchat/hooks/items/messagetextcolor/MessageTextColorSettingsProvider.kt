package h.Hchat.hooks.items.messagetextcolor

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MessageTextColorSettingsProvider : SimpleFeatureSettingsProvider(
    MessageTextColorFeature.ID,
    "消息文本颜色",
    "自定义聊天文本消息左右侧颜色",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
