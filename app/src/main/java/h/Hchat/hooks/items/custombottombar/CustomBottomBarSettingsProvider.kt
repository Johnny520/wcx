package h.Hchat.hooks.items.custombottombar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CustomBottomBarSettingsProvider : SimpleFeatureSettingsProvider(
    CustomBottomBarFeature.ID,
    "自定义底栏",
    "修改微信首页底栏的图标、标题与显示状态",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)

class FloatingBottomBarSettingsProvider : SimpleFeatureSettingsProvider(
    FloatingBottomBarSettings.FEATURE_ID,
    "悬浮底栏",
    "将微信首页底栏显示为悬浮样式",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
