package h.Hchat.hooks.api.model

class WeChatConversation(
    username: String?,
    @JvmField val unreadCount: Int,
    @JvmField val status: Int,
    @JvmField val isSend: Int,
    @JvmField val conversationTime: Long,
    content: String?,
    messageType: String?,
    @JvmField val flag: Long,
    digest: String?,
    digestUser: String?,
    @JvmField val atCount: Int,
    @JvmField val unreadMuteCount: Int,
    @JvmField val hasTodo: Int
) {
    @JvmField val username: String = username.orEmpty()
    @JvmField val content: String = content.orEmpty()
    @JvmField val messageType: String = messageType.orEmpty()
    @JvmField val digest: String = digest.orEmpty()
    @JvmField val digestUser: String = digestUser.orEmpty()
}
