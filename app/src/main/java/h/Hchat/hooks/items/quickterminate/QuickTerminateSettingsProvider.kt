package h.Hchat.hooks.items.quickterminate

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class QuickTerminateSettingsProvider : SimpleFeatureSettingsProvider(
    QuickTerminateFeature.ID,
    "快捷终止",
    "从微信右上角加号菜单快速结束微信进程",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
