package h.Hchat.hooks.items.gameemoji

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GameEmojiSettingsProvider : SimpleFeatureSettingsProvider(
    GameEmojiFeature.ID,
    "指定骰子猜拳",
    "固定骰子点数和猜拳结果，或在发送时选择",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
