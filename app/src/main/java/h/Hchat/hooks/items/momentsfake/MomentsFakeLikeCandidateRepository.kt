package h.Hchat.hooks.items.momentsfake

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatContact
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.ui.miuix.pickerDisplayName
import java.util.Locale

internal object MomentsFakeLikeCandidateRepository {
    fun loadForLikes(includeGroupMembers: Boolean): List<VoiceForwardMiuixDialog.ContactItem> {
        return loadScoped(includeGroupMembers)
    }

    fun loadForComments(includeGroupMembers: Boolean): List<VoiceForwardMiuixDialog.ContactItem> {
        return loadScoped(includeGroupMembers)
    }

    private fun loadScoped(
        includeGroupMembers: Boolean
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        val contacts = WeChatApis.contact().contacts()
            ?.takeIf { it.isAvailable }
            ?: return emptyList()
        val labelsByUser = loadLabelsByUser(contacts)
        val comparator = conversationComparator()
        val pickerContacts = contacts.getPickerContacts()
            .mapNotNull { it.toCandidate(labelsByUser[it.wxId].orEmpty()) }
            .distinctBy { it.id }
            .sortedWith(comparator)
        if (!includeGroupMembers) return pickerContacts

        val pickerContactIds = pickerContacts.mapTo(hashSetOf()) { it.id }
        val groupMembers = loadGroupMembers(pickerContactIds)
        return (pickerContacts + groupMembers)
            .distinctBy { it.id }
            .sortedWith(comparator)
    }

    private fun loadLabelsByUser(
        contacts: h.Hchat.hooks.api.contact.WeChatContactApi
    ): Map<String, List<String>> {
        val labelsByUser = linkedMapOf<String, MutableList<String>>()
        runCatching { contacts.getContactLabelList() }.getOrDefault(emptyList()).forEach { label ->
            val labelName = label.labelName.ifBlank { label.labelId }
            if (labelName.isBlank()) return@forEach
            label.userNameList.forEach { wxId ->
                if (wxId.isNotBlank()) labelsByUser.getOrPut(wxId) { arrayListOf() }.add(labelName)
            }
        }
        return labelsByUser.mapValues { (_, labels) -> labels.distinct() }
    }

    private fun WeChatContact?.toCandidate(
        labels: List<String>
    ): VoiceForwardMiuixDialog.ContactItem? {
        if (this == null || wxId.isBlank()) return null
        return VoiceForwardMiuixDialog.ContactItem(
            id = wxId,
            label = pickerDisplayName(group = false),
            group = false,
            avatarUrl = avatarUrl,
            avatarBackupUrl = avatarBackupUrl,
            labels = labels,
            searchAliases = listOf(remarkName, nickname, customWxId)
                .filter { it.isNotBlank() }
                .distinct()
        )
    }

    private fun conversationComparator(): Comparator<VoiceForwardMiuixDialog.ContactItem> {
        val conversationOrder = WeChatApis.conversations()
            ?.getRecentConversationUsernames(10000)
            .orEmpty()
            .mapIndexed { index, username -> username to index }
            .toMap()
        return compareBy<VoiceForwardMiuixDialog.ContactItem> {
            conversationOrder[it.id] ?: Int.MAX_VALUE
        }.thenBy { it.label.lowercase(Locale.CHINA) }
    }

    private fun loadGroupMembers(
        pickerContactIds: Set<String>
    ): List<VoiceForwardMiuixDialog.ContactItem> {
        val contacts = WeChatApis.contact().contacts() ?: return emptyList()
        val selfWxId = WeChatApis.contact().account()?.selfWxId().orEmpty()
        val roomNames = linkedMapOf<String, String>()
        contacts.getPickerGroups().forEach { group ->
            val names = contacts.getGroupMemberRoomDisplayNames(group.wxId)
            contacts.getGroupMemberIds(group.wxId).forEach memberLoop@ { memberId ->
                if (memberId.isBlank() || memberId == selfWxId || pickerContactIds.contains(memberId)) {
                    return@memberLoop
                }
                val roomName = names[memberId].orEmpty().trim()
                if (roomName.isNotEmpty()) roomNames.putIfAbsent(memberId, roomName)
                else roomNames.putIfAbsent(memberId, "")
            }
        }
        if (roomNames.isEmpty()) return emptyList()

        val records = contacts.getContactsByIds(roomNames.keys.toList()).associateBy { it.wxId }
        return roomNames.keys.map { wxId ->
            val record = records[wxId]
            val resolvedName = listOf(
                record?.remarkName,
                record?.nickname,
                roomNames[wxId]
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty().ifBlank { wxId }
            VoiceForwardMiuixDialog.ContactItem(
                id = wxId,
                label = resolvedName,
                group = false,
                avatarUrl = record?.avatarUrl.orEmpty(),
                avatarBackupUrl = record?.avatarBackupUrl.orEmpty(),
                searchAliases = listOfNotNull(
                    record?.customWxId?.takeIf { it.isNotBlank() },
                    record?.nickname?.takeIf { it.isNotBlank() },
                    roomNames[wxId]?.takeIf { it.isNotBlank() },
                    "群成员"
                ).distinct()
            )
        }.sortedWith(compareBy { it.label.lowercase(Locale.CHINA) })
    }

}
