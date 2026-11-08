package h.Hchat.hooks.items.legacychatinput

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class LegacyChatInputSettingsProvider : SimpleFeatureSettingsProvider(
    LegacyChatInputFeature.ID,
    "恢复旧版聊天输入框",
    "恢复微信原生旧版聊天输入栏；与输入框提示相互独立",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
