package h.Hchat.hooks.items.voiceforward

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class VoiceForwardSettingsProvider : SimpleFeatureSettingsProvider(
    VoiceForwardFeature.ID,
    "语音转发保存",
    "长按聊天语音或收藏语音后转发给好友/群聊，或保存到本地",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
