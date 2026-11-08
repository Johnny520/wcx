package h.Hchat.hooks.items.selectedmessages

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatContact
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.ui.miuix.pickerDisplayName
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object SelectedMessageContactRepository {
    @Volatile
    private var allContacts: CachedContacts? = null

    @Volatile
    private var friendContacts: CachedContacts? = null

    private val warming = AtomicBoolean(false)

    fun cached(friendsOnly: Boolean): List<VoiceForwardMiuixDialog.ContactItem>? {
        val all = allContacts?.takeIf { it.valid() }
        if (!friendsOnly) return all?.items
        if (all != null) return all.items.filter(::isFriend)
        return friendContacts?.takeIf { it.valid() }?.items
    }

    fun load(friendsOnly: Boolean): List<VoiceForwardMiuixDialog.ContactItem> {
        cached(friendsOnly)?.let { return it }
        val loaded = loadFresh(friendsOnly)
        cache(loaded, friendsOnly)
        return loaded
    }

    fun warm() {
        if (allContacts?.valid() == true || !warming.compareAndSet(false, true)) return
        Thread({
            runCatching { load(friendsOnly = false) }
            warming.set(false)
        }, "Hchat-SelectedMessageContactsWarmup").start()
    }

    private fun loadFresh(friendsOnly: Boolean): List<VoiceForwardMiuixDialog.ContactItem> {
        val api = WeChatApis.contact().contacts() ?: return emptyList()
        if (!api.isAvailable) return emptyList()
        val labelsByUser = linkedMapOf<String, MutableList<String>>()
        runCatching { api.getContactLabelList() }.getOrDefault(emptyList()).forEach { label ->
            val labelName = label.labelName.ifBlank { label.labelId }
            if (labelName.isBlank()) return@forEach
            label.userNameList.forEach { wxId ->
                if (wxId.isNotBlank()) labelsByUser.getOrPut(wxId) { arrayListOf() }.add(labelName)
            }
        }
        if (friendsOnly) {
            return api.getPickerContacts()
                .mapNotNull { contact -> contact.toPickerItem(false, labelsByUser[contact.wxId].orEmpty()) }
                .sortedWith(conversationComparator())
        }
        val result = ArrayList<VoiceForwardMiuixDialog.ContactItem>()
        result += api.getPickerContacts().mapNotNull { contact ->
            contact.toPickerItem(false, labelsByUser[contact.wxId].orEmpty())
        }
        result += api.getPickerOfficialAccounts().mapNotNull { contact ->
            contact.toPickerItem(group = false, labels = emptyList(), official = true)
        }
        result += api.getPickerGroups().mapNotNull { contact ->
            contact.toPickerItem(group = true, labels = emptyList())
        }
        return result.distinctBy { it.id }.sortedWith(conversationComparator())
    }

    private fun cache(
        contacts: List<VoiceForwardMiuixDialog.ContactItem>,
        friendsOnly: Boolean
    ) {
        val cached = CachedContacts(contacts, System.currentTimeMillis())
        if (friendsOnly) {
            friendContacts = cached
        } else {
            allContacts = cached
            friendContacts = CachedContacts(contacts.filter(::isFriend), cached.savedAt)
        }
    }

    private fun isFriend(item: VoiceForwardMiuixDialog.ContactItem): Boolean {
        return !item.group && !item.official
    }

    private fun conversationComparator(): Comparator<VoiceForwardMiuixDialog.ContactItem> {
        val conversationOrder = WeChatApis.conversations()
            ?.getRecentConversationUsernames(10000)
            .orEmpty()
            .mapIndexed { index, username -> username to index }
            .toMap()
        return compareBy<VoiceForwardMiuixDialog.ContactItem> {
            conversationOrder[it.id] ?: Int.MAX_VALUE
        }.thenBy { it.group }.thenBy { it.official }.thenBy { it.label.lowercase(Locale.US) }
    }

    private fun WeChatContact?.toPickerItem(
        group: Boolean,
        labels: List<String>,
        official: Boolean = false
    ): VoiceForwardMiuixDialog.ContactItem? {
        if (this == null || wxId.isBlank()) return null
        return VoiceForwardMiuixDialog.ContactItem(
            id = wxId,
            label = pickerDisplayName(group),
            group = group,
            official = official,
            avatarUrl = avatarUrl,
            avatarBackupUrl = avatarBackupUrl,
            labels = labels.distinct(),
            searchAliases = listOf(remarkName, nickname, customWxId)
                .filter { it.isNotBlank() }
                .distinct()
        )
    }

    private data class CachedContacts(
        val items: List<VoiceForwardMiuixDialog.ContactItem>,
        val savedAt: Long
    ) {
        fun valid(): Boolean = items.isNotEmpty() && System.currentTimeMillis() - savedAt <= CONTACT_CACHE_MS
    }

    private const val CONTACT_CACHE_MS = 5 * 60_000L
}
