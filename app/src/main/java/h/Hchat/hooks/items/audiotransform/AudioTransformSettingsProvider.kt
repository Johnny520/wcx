package h.Hchat.hooks.items.audiotransform

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AudioTransformSettingsProvider : SimpleFeatureSettingsProvider(
    AudioTransformFeature.ID,
    "音频转换",
    "任意音频转 Silk 保存/发送，或把 Silk 导出为 MP3/M4A 保存",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
