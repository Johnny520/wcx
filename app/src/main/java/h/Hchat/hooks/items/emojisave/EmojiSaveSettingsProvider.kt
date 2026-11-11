package h.Hchat.hooks.items.emojisave

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class EmojiSaveSettingsProvider : SimpleFeatureSettingsProvider(
    EmojiSaveFeature.ID,
    "保存表情",
    "长按聊天表情后保存原始文件到本地",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
