package h.Hchat.hooks.items.groupleave

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GroupLeaveMonitorSettingsProvider : SimpleFeatureSettingsProvider(
    GroupLeaveMonitorFeature.ID,
    "进退群监控",
    "监听成员进群和退群，支持系统消息和自动回复",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
