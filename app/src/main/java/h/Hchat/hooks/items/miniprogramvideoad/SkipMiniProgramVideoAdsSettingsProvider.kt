package h.Hchat.hooks.items.miniprogramvideoad

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SkipMiniProgramVideoAdsSettingsProvider : SimpleFeatureSettingsProvider(
    SkipMiniProgramVideoAdsFeature.ID,
    "跳过小程序视频广告",
    "自动跳过小程序视频广告",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
