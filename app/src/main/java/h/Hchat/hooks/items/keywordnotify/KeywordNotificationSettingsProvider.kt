package h.Hchat.hooks.items.keywordnotify

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class KeywordNotificationSettingsProvider : SimpleFeatureSettingsProvider(
    KeywordNotificationFeature.ID,
    "关键词通知",
    "按关键词、@我或@所有人提醒指定聊天消息，支持铃声和震动",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
