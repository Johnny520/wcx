package h.Hchat.hooks.items.customfriendavatar

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

// 与 Hchat 的 CustomFriendAvatarFeature.ID 保持一致（该 Feature 本体未迁移，仅保留设置入口）
private const val ID = "custom_friend_avatar"

class CustomFriendAvatarSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "自定义头像",
    "为指定好友或群聊设置仅本地显示的头像",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
