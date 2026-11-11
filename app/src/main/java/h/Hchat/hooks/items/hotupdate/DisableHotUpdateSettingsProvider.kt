package h.Hchat.hooks.items.hotupdate

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class DisableHotUpdateSettingsProvider : SimpleFeatureSettingsProvider(
    DisableHotUpdateFeature.ID,
    "屏蔽热更新",
    "阻止微信加载和应用热更新补丁",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
