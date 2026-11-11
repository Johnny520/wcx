package h.Hchat.hooks.items.realtail

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class RealNameTailSettingsProvider : SimpleFeatureSettingsProvider(
    RealNameTailFeature.ID,
    "实名尾字",
    "群聊里自动补查并显示实名尾字",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
