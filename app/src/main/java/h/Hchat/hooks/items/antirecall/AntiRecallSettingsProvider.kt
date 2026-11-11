package h.Hchat.hooks.items.antirecall

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AntiRecallSettingsProvider : SimpleFeatureSettingsProvider(
    AntiRecallFeature.ID,
    "防撤回",
    "保留被撤回的消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
