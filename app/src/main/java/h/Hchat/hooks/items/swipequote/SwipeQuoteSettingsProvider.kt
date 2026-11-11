package h.Hchat.hooks.items.swipequote

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class SwipeQuoteSettingsProvider : SimpleFeatureSettingsProvider(
    SwipeQuoteFeature.ID,
    "滑动手势",
    "左滑引用，右滑或长按菜单复读消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
