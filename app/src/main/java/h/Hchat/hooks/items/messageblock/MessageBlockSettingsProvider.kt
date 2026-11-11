package h.Hchat.hooks.items.messageblock

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MessageBlockSettingsProvider : SimpleFeatureSettingsProvider(
    MessageBlockFeature.ID,
    "屏蔽消息",
    "拦截指定成员或会话的消息，不显示也不弹通知",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
