package h.Hchat.hooks.items.autooriginal

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AutoOriginalImageSettingsProvider : SimpleFeatureSettingsProvider(
    AutoOriginalImageFeature.ID,
    "自动勾选原图",
    "发送聊天图片时自动勾选原图",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
