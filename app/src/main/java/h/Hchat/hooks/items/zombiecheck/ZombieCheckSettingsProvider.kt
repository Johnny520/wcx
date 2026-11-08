package h.Hchat.hooks.items.zombiecheck

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ZombieCheckSettingsProvider : SimpleFeatureSettingsProvider(
    ZombieCheckFeature.ID,
    "僵尸粉检测",
    "批量核验好友关系并记录异常联系人",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
