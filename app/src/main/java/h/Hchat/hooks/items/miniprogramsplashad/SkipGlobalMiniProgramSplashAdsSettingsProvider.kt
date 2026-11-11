package h.Hchat.hooks.items.miniprogramsplashad

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SkipGlobalMiniProgramSplashAdsSettingsProvider : SimpleFeatureSettingsProvider(
    SkipGlobalMiniProgramSplashAdsFeature.ID,
    "跳过全局小程序开屏广告",
    "阻止所有小程序展示启动开屏广告",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
