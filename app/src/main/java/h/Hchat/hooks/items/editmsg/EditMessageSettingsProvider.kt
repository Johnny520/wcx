package h.Hchat.hooks.items.editmsg

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class EditMessageSettingsProvider : SimpleFeatureSettingsProvider(
    EditMessageFeature.ID,
    "修改聊天记录",
    "长按文字、引用或转账消息后修改本地聊天记录",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
