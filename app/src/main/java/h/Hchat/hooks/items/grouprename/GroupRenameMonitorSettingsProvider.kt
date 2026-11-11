package h.Hchat.hooks.items.grouprename

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GroupRenameMonitorSettingsProvider : SimpleFeatureSettingsProvider(
    GroupRenameMonitorFeature.ID,
    "改名监控",
    "监控群成员修改群内昵称，支持系统消息、文本和卡片提醒",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
