package h.Hchat.hooks.items.conversationtabs

/** Immutable configuration; compiled once when the selected tab or settings change. */
enum class ConversationTabKind(val title: String, val defaultIcon: String) {
    ALL("全部", "💬"), UNREAD("未读", "🔔"), GROUP("群聊", "👥"),
    PRIVATE("私聊", "👤"), OFFICIAL("公众号", "📰"), CUSTOM("自定义", "⭐")
}

enum class ConversationTabDisplay(val title: String) {
    NAME("只显示名称"), ICON("只显示图标"), BOTH("图标和名称")
}

data class ConversationTab(
    val id: String,
    val name: String,
    val kind: ConversationTabKind,
    val icon: String = kind.defaultIcon,
    val iconPath: String = "",
    val display: ConversationTabDisplay = ConversationTabDisplay.BOTH,
    val conversationIds: Set<String> = emptySet()
)

data class ConversationTabsConfig(
    val enabled: Boolean = false,
    val tabs: List<ConversationTab> = defaults()
) {
    companion object {
        const val MAX_TABS = 20
        fun defaults(): List<ConversationTab> = ConversationTabKind.values()
            .filter { it != ConversationTabKind.CUSTOM }
            .map { ConversationTab(it.name.lowercase(), it.title, it) }

        fun normalize(config: ConversationTabsConfig): ConversationTabsConfig {
            val tabs = config.tabs.distinctBy { it.id }.filter { it.id.isNotBlank() }
                .take(MAX_TABS).map { tab ->
                    tab.copy(
                        name = tab.name.trim().take(24).ifBlank { tab.kind.title },
                        icon = tab.icon.trim().take(16).ifBlank { tab.kind.defaultIcon },
                        conversationIds = tab.conversationIds.map(String::trim)
                            .filter(String::isNotEmpty).toSet()
                    )
                }.toMutableList()
            // Always retain a way to return to the complete native conversation list.
            if (tabs.none { it.kind == ConversationTabKind.ALL }) {
                if (tabs.size == MAX_TABS) tabs.removeAt(tabs.lastIndex)
                tabs.add(0, defaults().first())
            }
            return config.copy(tabs = tabs)
        }
    }
}
