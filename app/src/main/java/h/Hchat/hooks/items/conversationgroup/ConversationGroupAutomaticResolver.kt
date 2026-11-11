package h.Hchat.hooks.items.conversationgroup

import android.content.Context
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.items.grouplabel.GroupChatLabelStore

internal data class ConversationGroupAutomaticResolution(
    val conversationIds: Map<String, List<String>>,
    val observedConversationGroupIds: Set<String>?,
    val newConversationGroupIds: Set<String>,
    val automaticNewGroupsEnabled: Boolean,
    val baselineInitialized: Boolean
)

/**
 * Resolves automatic rules to real talkers without writing the result back to
 * the user's manual group configuration. Seen-group state is committed by the
 * runtime only after a baseline or a real parent update has succeeded.
 */
internal object ConversationGroupAutomaticResolver {
    fun resolveForSync(
        context: Context,
        sourceGroups: List<ConversationGroup>
    ): ConversationGroupAutomaticResolution {
        val groups = orderedGroups(sourceGroups)
        val automaticGroups = groups.filter(ConversationGroup::automaticGroupingEnabled)
        val needsChatGroups = automaticGroups.any {
            it.automaticAllGroups || it.automaticNewGroups || it.automaticMutedGroups ||
                it.automaticOwnedGroups || it.automaticEnterpriseGroups ||
                it.automaticGroupIds.isNotEmpty() || it.automaticGroupLabelIds.isNotEmpty()
        }
        val needsOfficialAccounts = automaticGroups.any {
            it.automaticOfficialAccounts || it.automaticOfficialIncludeIds.isNotEmpty()
        }
        if (!needsChatGroups && !needsOfficialAccounts) {
            return ConversationGroupAutomaticResolution(
                conversationIds = groups.associate { it.id to it.conversationIds },
                observedConversationGroupIds = null,
                newConversationGroupIds = emptySet(),
                automaticNewGroupsEnabled = false,
                baselineInitialized = false
            )
        }
        val automaticNewGroupsEnabled = groups.any {
            it.automaticGroupingEnabled && it.automaticNewGroups
        }
        val baselineInitialized = if (automaticNewGroupsEnabled) {
            ConversationGroupStore.automaticSeenBaselineInitialized(context)
        } else {
            false
        }
        val contacts = WeChatApis.contacts()
            ?: return ConversationGroupAutomaticResolution(
                conversationIds = groups.associate { it.id to it.conversationIds },
                observedConversationGroupIds = null,
                newConversationGroupIds = emptySet(),
                automaticNewGroupsEnabled = automaticNewGroupsEnabled,
                baselineInitialized = baselineInitialized
            )

        val chatGroups = if (needsChatGroups) {
            contacts.getPickerGroups()
                .map { it.wxId.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        } else {
            emptyList()
        }
        val chatGroupIds = chatGroups.toSet()
        val observedConversationGroupIds = if (automaticNewGroupsEnabled) {
            loadConversationGroupIds(chatGroupIds)
        } else {
            null
        }
        val seenGroupIds = if (automaticNewGroupsEnabled) {
            ConversationGroupStore.loadAutomaticSeenGroupIds(context)
        } else {
            emptySet()
        }
        val newGroupIds = if (automaticNewGroupsEnabled && baselineInitialized) {
            observedConversationGroupIds.orEmpty() - seenGroupIds
        } else {
            emptySet()
        }
        val officialIds = if (needsOfficialAccounts) {
            contacts.getPickerOfficialAccounts()
                .map { it.wxId.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .toSet()
        } else {
            emptySet()
        }
        val self = if (automaticGroups.any { it.automaticOwnedGroups }) {
            runCatching { WeChatApis.account()?.selfWxId().orEmpty().trim() }.getOrDefault("")
        } else {
            ""
        }
        val labelGroups = if (automaticGroups.any { it.automaticGroupLabelIds.isNotEmpty() }) {
            GroupChatLabelStore.load(context)
                .associate { it.id to it.groupIds.filter(chatGroupIds::contains).toSet() }
        } else {
            emptyMap()
        }

        val manualOwners = buildMap {
            groups.forEach { group ->
                group.conversationIds.forEach { talker ->
                    if (talker.isNotBlank()) put(talker, group.id)
                }
            }
        }
        val automaticOwners = hashSetOf<String>()
        val result = linkedMapOf<String, List<String>>()

        groups.forEach { group ->
            val selected = linkedSetOf<String>()
            val manual = group.conversationIds.filter(String::isNotBlank)
            selected.addAll(manual)
            if (group.automaticGroupingEnabled) {
                val automatic = linkedSetOf<String>()
                if (group.automaticAllGroups) automatic.addAll(chatGroups)
                if (group.automaticNewGroups) automatic.addAll(newGroupIds)
                if (group.automaticEnterpriseGroups) {
                    automatic.addAll(chatGroups.filter { it.endsWith("@im.chatroom") })
                }
                if (group.automaticOwnedGroups && self.isNotBlank()) {
                    val chatrooms = WeChatApis.chatrooms()
                    automatic.addAll(chatGroups.filter { talker ->
                        chatrooms?.getOwner(talker).orEmpty().trim() == self
                    })
                }
                if (group.automaticMutedGroups) {
                    val conversations = WeChatApis.conversations()
                    automatic.addAll(chatGroups.filter { talker ->
                        runCatching { conversations?.isWechatDoNotDisturb(talker) == true }
                            .getOrDefault(false)
                    })
                }
                automatic.addAll(group.automaticGroupIds.filter(chatGroupIds::contains))
                group.automaticGroupLabelIds.forEach { labelId ->
                    automatic.addAll(labelGroups[labelId].orEmpty())
                }
                if (group.automaticOfficialAccounts) automatic.addAll(officialIds)
                automatic.addAll(group.automaticOfficialIncludeIds.filter(officialIds::contains))
                automatic.removeAll(group.automaticOfficialExcludeIds)
                selected.addAll(automatic)
            }

            val effective = selected.filter { talker ->
                val manualOwner = manualOwners[talker]
                manualOwner == null || manualOwner == group.id
            }.filter { talker ->
                automaticOwners.add(talker) || manualOwners[talker] == group.id
            }
            result[group.id] = effective.distinct()
        }
        return ConversationGroupAutomaticResolution(
            conversationIds = result,
            observedConversationGroupIds = observedConversationGroupIds,
            newConversationGroupIds = newGroupIds,
            automaticNewGroupsEnabled = automaticNewGroupsEnabled,
            baselineInitialized = baselineInitialized
        )
    }

    /**
     * A contact can appear before its rconversation row is created. Only rows
     * that can actually be assigned to a homepage group participate in the
     * new-group baseline; otherwise a transient contact update could consume
     * the new-group event before the conversation exists.
     */
    private fun loadConversationGroupIds(candidateIds: Set<String>): Set<String>? {
        if (candidateIds.isEmpty()) return emptySet()
        val database = WeChatApis.database() ?: return null
        val result = linkedSetOf<String>()
        candidateIds.chunked(400).forEach { chunk ->
            val placeholders = List(chunk.size) { "?" }.joinToString(",")
            val cursor = database.rawQuery(
                "SELECT username FROM rconversation WHERE username IN ($placeholders)",
                chunk.toTypedArray()
            ) ?: return null
            try {
                val rows = cursor
                val index = rows.getColumnIndex("username")
                if (index >= 0 && rows.moveToFirst()) {
                    do {
                        rows.getString(index)?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let(result::add)
                    } while (rows.moveToNext())
                }
            } finally {
                runCatching { cursor.close() }
            }
        }
        return result
    }

    private fun orderedGroups(groups: List<ConversationGroup>): List<ConversationGroup> {
        val byParent = groups.groupBy { it.parentId }
        val result = arrayListOf<ConversationGroup>()
        val visited = hashSetOf<String>()
        fun appendChildren(parentId: String?) {
            byParent[parentId].orEmpty()
                .sortedWith(
                    compareByDescending<ConversationGroup> { it.pinned }
                        .thenBy { it.order }
                        .thenBy { it.id }
                )
                .forEach { group ->
                    if (visited.add(group.id)) {
                        result.add(group)
                        appendChildren(group.id)
                    }
                }
        }
        appendChildren(null)
        groups.filterNot { it.id in visited }
            .sortedWith(compareBy<ConversationGroup> { it.order }.thenBy { it.id })
            .forEach(result::add)
        return result
    }
}
