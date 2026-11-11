package h.Hchat.hooks.items.conversationgroup

enum class ConversationGroupUnreadMode(val value: String, val displayName: String) {
    ALL("all", "全部未读"),
    EXCLUDE_MUTED("exclude_muted", "免打扰不显示未读"),
    HIDDEN("hidden", "不显示未读");

    companion object {
        fun fromValue(value: String?): ConversationGroupUnreadMode? {
            return values().firstOrNull { it.value == value }
        }

        fun fromLegacy(showUnreadCount: Boolean): ConversationGroupUnreadMode {
            return if (showUnreadCount) EXCLUDE_MUTED else HIDDEN
        }
    }
}

data class ConversationGroup(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val order: Int = 0,
    val conversationIds: List<String> = emptyList(),
    val conversationOrderIds: List<String> = emptyList(),
    val pinnedConversationIds: List<String> = emptyList(),
    val bottomConversationIds: List<String> = emptyList(),
    val pinned: Boolean = false,
    val avatarPath: String = "",
    val unreadCountMode: ConversationGroupUnreadMode = ConversationGroupUnreadMode.ALL,
    val previewLatestMessage: Boolean = true,
    val roundAvatar: Boolean = true,
    val showEmpty: Boolean = false,
    val automaticGroupingEnabled: Boolean = false,
    val automaticAllGroups: Boolean = false,
    val automaticNewGroups: Boolean = false,
    val automaticMutedGroups: Boolean = false,
    val automaticOwnedGroups: Boolean = false,
    val automaticEnterpriseGroups: Boolean = false,
    val automaticOfficialAccounts: Boolean = false,
    val automaticGroupIds: List<String> = emptyList(),
    val automaticGroupLabelIds: List<String> = emptyList(),
    val automaticOfficialIncludeIds: List<String> = emptyList(),
    val automaticOfficialExcludeIds: List<String> = emptyList()
)
