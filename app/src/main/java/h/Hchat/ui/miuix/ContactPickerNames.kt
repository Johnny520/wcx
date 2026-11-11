package h.Hchat.ui.miuix

import h.Hchat.hooks.api.model.WeChatContact

internal fun WeChatContact.pickerDisplayName(group: Boolean): String {
    if (!group) return displayName().ifBlank { wxId }
    val remark = remarkName.trim()
    val name = nickname.trim()
    return when {
        remark.isNotEmpty() && name.isNotEmpty() && remark != name -> "$remark($name)"
        remark.isNotEmpty() -> remark
        name.isNotEmpty() -> name
        else -> wxId
    }
}
