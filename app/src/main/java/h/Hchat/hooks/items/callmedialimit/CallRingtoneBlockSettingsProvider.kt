package h.Hchat.hooks.items.callmedialimit

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CallRingtoneBlockSettingsProvider : SimpleFeatureSettingsProvider(
    CallRingtoneBlockFeature.ID,
    "屏蔽通话铃声",
    "分别屏蔽微信通话的呼入铃声和呼出等待铃声",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
