package h.Hchat.hooks.items.hchatextra

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class GroupMemberHistorySettingsProvider : SimpleFeatureSettingsProvider(
    FEATURE_ID,
    "历史发言记录",
    "在群成员资料页查看历史发言记录",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val FEATURE_ID = "hchat_group_member_history"
    }
}
