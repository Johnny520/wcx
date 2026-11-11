package h.Hchat.hooks.items.quickread

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class QuickMarkReadSettingsProvider : SimpleFeatureSettingsProvider(
    QuickMarkReadFeature.ID,
    "快捷已读",
    "拖拽未读角标或加号菜单一键已读",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
