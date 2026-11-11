package h.Hchat.hooks.items.autoreply

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AutoReplySettingsProvider : SimpleFeatureSettingsProvider(
    AutoReplyFeature.ID,
    "自动回复",
    "按规则回复消息，处理好友申请和通过后的欢迎语",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
