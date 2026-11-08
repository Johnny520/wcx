package h.Hchat.hooks.items.conversationgroup

import android.content.Context

data class ConversationGroupPickerFilter(
    val id: String,
    val name: String,
    val conversationIds: Set<String>
)

object ConversationGroupPickerSupport {
    @JvmStatic
    fun filters(context: Context, availableIds: Collection<String>): List<ConversationGroupPickerFilter> {
        if (!ConversationGroupStore.isEnabled(context)) return emptyList()
        val groups = ConversationGroupStore.load(context)
        val effective = ConversationGroupRuntime.effectiveConversationIdsForPicker(groups)
        return filters(groups, availableIds, effective)
    }

    @JvmStatic
    fun filters(
        groups: List<ConversationGroup>,
        availableIds: Collection<String>
    ): List<ConversationGroupPickerFilter> {
        return filters(groups, availableIds, emptyMap())
    }

    private fun filters(
        groups: List<ConversationGroup>,
        availableIds: Collection<String>,
        effectiveConversationIds: Map<String, List<String>>
    ): List<ConversationGroupPickerFilter> {
        val candidates = availableIds.asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot(ConversationGroupRuntime::isVirtualTalker)
            .toSet()
        if (candidates.isEmpty()) return emptyList()

        if (groups.isEmpty()) return emptyList()
        val byId = groups.associateBy { it.id }
        return groups.mapNotNull { group ->
            val ids = ConversationGroupRuntime.descendantConversationIds(
                groups,
                group.id,
                effectiveConversationIds
            )
                .intersect(candidates)
            if (ids.isEmpty()) return@mapNotNull null
            ConversationGroupPickerFilter(
                id = group.id,
                name = groupPath(group.id, byId),
                conversationIds = ids
            )
        }
    }

    private fun groupPath(groupId: String, byId: Map<String, ConversationGroup>): String {
        val names = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        var current = byId[groupId]
        while (current != null && visited.add(current.id)) {
            names.addFirst(current.name)
            current = current.parentId?.let(byId::get)
        }
        return names.joinToString(" / ").ifBlank { byId[groupId]?.name.orEmpty() }
    }
}
