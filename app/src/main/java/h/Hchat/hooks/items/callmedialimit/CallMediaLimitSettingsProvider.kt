package h.Hchat.hooks.items.callmedialimit

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CallMediaLimitSettingsProvider : SimpleFeatureSettingsProvider(
    CallMediaLimitFeature.ID,
    "移除通话媒体限制",
    "通话时播放语音和视频，并允许打开拍摄",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
