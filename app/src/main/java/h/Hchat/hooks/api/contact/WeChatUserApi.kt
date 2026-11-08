package h.Hchat.hooks.api.contact

import android.text.TextUtils

/**
 * 微信用户身份判断 API。
 *
 * 面向功能层提供“是否自己、是否群聊、是否好友、显示名”等常用判断。
 */
class WeChatUserApi(
    private val accountApi: WeChatAccountApi?,
    private val contactApi: WeChatContactApi?,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    val isAvailable: Boolean
        get() = (accountApi != null && accountApi.isAvailable) ||
            (contactApi != null && contactApi.isAvailable)

    fun selfWxId(): String = accountApi?.selfWxId() ?: ""

    fun isSelf(wxId: String?): Boolean {
        val self = selfWxId()
        return !TextUtils.isEmpty(wxId) && !TextUtils.isEmpty(self) && wxId == self
    }

    fun isGroup(wxId: String?): Boolean {
        return contactApi != null && contactApi.isGroup(wxId)
    }

    fun isOfficialAccount(wxId: String?): Boolean {
        return !TextUtils.isEmpty(wxId) && wxId!!.startsWith("gh_")
    }

    fun isFriend(wxId: String?): Boolean {
        if (TextUtils.isEmpty(wxId) || isGroup(wxId) || isOfficialAccount(wxId)) return false
        val contact = contactApi?.getContact(wxId) ?: return false
        return contact.type and 1 != 0
    }

    fun displayName(wxId: String?): String {
        if (TextUtils.isEmpty(wxId)) return ""
        val name = contactApi?.getDisplayName(wxId)
        return if (!TextUtils.isEmpty(name)) name ?: "" else wxId ?: ""
    }

    fun displayNameInGroup(groupId: String?, memberWxId: String?): String {
        if (TextUtils.isEmpty(memberWxId)) return ""
        val name = contactApi?.getGroupMemberDisplayName(groupId, memberWxId)
        return if (!TextUtils.isEmpty(name)) name ?: "" else memberWxId ?: ""
    }

    fun customWxId(): String = accountApi?.customWxId() ?: ""

    private fun log(message: String) {
        logger?.log("[WeChatUserApi] $message")
    }
}
