package h.Hchat.hooks.items.voicepreview

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class VoicePreviewSettingsProvider : SimpleFeatureSettingsProvider(
    VoicePreviewFeature.ID,
    "语音消息预览",
    "长按聊天语音查看时长并播放",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
