package h.Hchat.hooks.items.roundavatar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class RoundAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    RoundAvatarFeature.ID,
    "圆角头像",
    "统一设置微信头像的圆角弧度",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
