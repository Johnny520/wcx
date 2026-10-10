package h.Hchat.hooks.api.model

class WeChatChatroom(
    chatroomId: String?,
    name: String?,
    owner: String?,
    memberIds: List<String>?,
    rawDisplayNames: String?
) {
    @JvmField val chatroomId: String = chatroomId.orEmpty()
    @JvmField val name: String = name.orEmpty()
    @JvmField val owner: String = owner.orEmpty()
    @JvmField val memberIds: List<String> = memberIds?.toList().orEmpty()
    @JvmField val rawDisplayNames: String = rawDisplayNames.orEmpty()

    fun getRoomId(): String = chatroomId

    fun getChatroomId(): String = chatroomId

    fun getName(): String = name

    fun getOwner(): String = owner

    fun getMemberList(): List<String> = memberIds

    fun getRawDisplayNames(): String = rawDisplayNames

    fun memberCount(): Int = memberIds.size

    fun getMemberCount(): Int = memberCount()
}
