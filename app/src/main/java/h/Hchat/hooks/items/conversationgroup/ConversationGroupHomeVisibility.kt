package h.Hchat.hooks.items.conversationgroup

internal object ConversationGroupHomeVisibility {
    val nativeParents = setOf("officialaccounts", "service_officialaccounts", "photoaccounts")

    fun hidden(projected: Set<String>, nativeMembers: Map<String, Set<String>>): Set<String> = buildSet {
        addAll(projected)
        nativeMembers.forEach { (parent, members) ->
            if (parent in nativeParents && members.isNotEmpty() && members.all { it in projected }) add(parent)
        }
    }

    fun rootWhere(original: String, hidden: Set<String>): String {
        if (hidden.isEmpty()) return original
        // Native root query exposes no bind arguments. Escape each literal and retain the entire original predicate.
        return "($original) AND rconversation.username NOT IN (" +
            hidden.sorted().joinToString(",") { "'${it.replace("'", "''")}'" } + ")"
    }
}
