package h.Hchat.hooks.items.payment.gift

import h.Hchat.ui.FeatureSettingsProvider

class GiftSettingsProvider : FeatureSettingsProvider {
    override fun featureId(): String = AutoGiftFeature.ID

    override fun title(): String = "自动抢礼物"

    override fun subtitle(): String = "自动抢礼物"

    override fun category(): String = FeatureSettingsProvider.CATEGORY_PRACTICAL
}
