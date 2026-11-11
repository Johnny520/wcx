package h.Hchat.hooks.items.payment.transfer

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class AutoTransferSettingsProvider : SimpleFeatureSettingsProvider(
    AutoTransferFeature.ID,
    "自动收款",
    "自动领取微信转账",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
