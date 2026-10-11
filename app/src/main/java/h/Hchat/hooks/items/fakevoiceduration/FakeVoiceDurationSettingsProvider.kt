package h.Hchat.hooks.items.fakevoiceduration

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class FakeVoiceDurationSettingsProvider : SimpleFeatureSettingsProvider(
    FakeVoiceDurationFeature.ID,
    "伪造语音时长",
    "自定义微信显示的语音时长",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
