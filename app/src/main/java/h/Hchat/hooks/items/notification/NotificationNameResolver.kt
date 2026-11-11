package h.Hchat.hooks.items.notification

import h.Hchat.hooks.api.core.WeChatApis

internal object NotificationNameResolver {
    fun displayName(wxId: String): String {
        if (wxId.isBlank()) return ""
        val contacts = WeChatApis.contact().contacts() ?: return wxId
        val contact = runCatching { contacts.getContact(wxId) }.getOrNull()
        if (contact != null && !contact.isOfficialAccount()) {
            contact.remarkName.takeIf { it.isNotBlank() }?.let { return it }
            contact.nickname.takeIf { it.isNotBlank() }?.let { return it }
        }
        return contacts.getDisplayName(wxId).ifBlank { wxId }
    }
}
