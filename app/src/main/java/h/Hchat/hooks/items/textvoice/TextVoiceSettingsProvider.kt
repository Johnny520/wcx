package h.Hchat.hooks.items.textvoice

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class TextVoiceSettingsProvider : SimpleFeatureSettingsProvider(
    TextVoiceFeature.ID,
    "文本转语音",
    "把输入文字合成为微信语音，或长按文字消息在线合成播放",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
