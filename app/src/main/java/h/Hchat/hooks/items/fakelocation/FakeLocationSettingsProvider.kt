package h.Hchat.hooks.items.fakelocation

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class FakeLocationSettingsProvider : SimpleFeatureSettingsProvider(
    FakeLocationFeature.ID,
    "虚拟定位",
    "将微信获取到的位置固定为预设经纬度",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
