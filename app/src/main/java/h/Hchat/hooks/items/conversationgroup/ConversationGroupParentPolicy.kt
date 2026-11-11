package h.Hchat.hooks.items.conversationgroup

/** Plans real-conversation parent changes without changing storage or caller-owned state. */
internal object ConversationGroupParentPolicy {
    data class Plan(
        val updates: Map<String, String>,
        val originalParents: Map<String, String>,
        val restoring: Set<String>,
        val unresolved: Set<String>
    )

    fun isModuleParent(value: String): Boolean =
        value.startsWith("wxid_hchat_group_") || value.startsWith("hchat_conv_group:")

    fun isOfficial(talker: String, verifyFlag: Int, originalParent: String?): Boolean =
        talker.startsWith("gh_") || verifyFlag != 0 ||
            originalParent == "officialaccounts" ||
            originalParent == "service_officialaccounts" ||
            originalParent == "photoaccounts"

    /**
     * Persist [Plan.originalParents] successfully before applying [Plan.updates].
     * Restoration backups remain until the caller confirms the corresponding write.
     * [current] must contain every assigned row and every row with a module parent;
     * a missing assigned row is unknown, not an empty/root parent.
     */
    fun plan(
        assigned: Map<String, String>,
        current: Map<String, String>,
        protected: Set<String>,
        originals: Map<String, String>
    ): Plan {
        val updates = linkedMapOf<String, String>()
        val originalParents = originals.toMutableMap()
        val restoring = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        (current.keys + assigned.keys).forEach { talker ->
            val parent = current[talker]
            if (parent == null) {
                unresolved.add(talker)
                return@forEach
            }
            val desired = assigned[talker]
            if (!isModuleParent(parent)) {
                if (talker in protected || desired == null) {
                    // A confirmed native parent supersedes any stale restoration backup.
                    originalParents.remove(talker)
                } else {
                    originalParents[talker] = parent
                    if (parent != desired) updates[talker] = desired
                }
                return@forEach
            }
            val original = originals[talker]?.takeUnless(::isModuleParent)
            if (original == null) {
                // Retain even an unchanged group as unresolved so cleanup keeps its parent.
                unresolved.add(talker)
                return@forEach
            }
            if (talker in protected || desired == null) {
                updates[talker] = original
                restoring.add(talker)
            } else if (parent != desired) {
                updates[talker] = desired
            }
        }
        return Plan(updates, originalParents, restoring, unresolved)
    }
}
