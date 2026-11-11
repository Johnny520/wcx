package h.Hchat.hooks.items.scheduledtask

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ScheduledTaskSettingsProvider : SimpleFeatureSettingsProvider(
    ScheduledTaskFeature.ID,
    "定时任务",
    "按计划时间发送聊天消息或发布朋友圈",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
