package h.Hchat.hooks.items.multirecall

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MultiRecallSettingsProvider : SimpleFeatureSettingsProvider(
    MultiRecallFeature.ID,
    "多选撤回",
    "在多选分享菜单中批量撤回自己发送的消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
