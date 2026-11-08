package h.Hchat.hooks.items.hchatextra

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SkipWebRiskSettingsProvider : SimpleFeatureSettingsProvider(
    FEATURE_ID,
    "跳过网页风险",
    "跳过微信 WebView 高风险网页拦截提示",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val FEATURE_ID = "hchat_skip_web_risk"
    }
}
