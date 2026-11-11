package h.Hchat.hooks.items.textspeech

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class TextSpeechSettingsProvider : SimpleFeatureSettingsProvider(
    TextSpeechFeature.ID,
    "文字转语音播报",
    "自动播报允许名单内收到的文字或语音消息",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
