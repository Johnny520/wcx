package h.Hchat.hooks.items.autovieworiginal

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AutoViewOriginalSettingsProvider : SimpleFeatureSettingsProvider(
    AutoViewOriginalFeature.ID,
    "自动查看原图",
    "打开聊天图片或视频时自动查看原图或原视频",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
