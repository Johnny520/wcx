package h.Hchat.hooks.api.model

class WeChatParsedMessage(
    content: String?,
    xml: String?,
    from: String?,
    to: String?,
    sender: String?,
    talker: String?,
    nativeUrl: String?,
    exclusiveRecvUser: String?,
    @JvmField val groupMessage: Boolean,
    @JvmField val redPacketMessage: Boolean,
    @JvmField val type: Int,
    @JvmField val createTimeSeconds: Long,
    @JvmField val msgSvrId: Long,
    msgSource: String?,
    selfWxId: String?
) {
    @JvmField val content: String = content.orEmpty()
    @JvmField val xml: String = xml.orEmpty()
    @JvmField val from: String = from.orEmpty()
    @JvmField val to: String = to.orEmpty()
    @JvmField val sender: String = sender.orEmpty()
    @JvmField val talker: String = talker.orEmpty()
    @JvmField val nativeUrl: String = nativeUrl.orEmpty()
    @JvmField val exclusiveRecvUser: String = exclusiveRecvUser.orEmpty()
    @JvmField val msgSource: String = msgSource.orEmpty()
    @JvmField val selfWxId: String = selfWxId.orEmpty()

    constructor(
        content: String?,
        xml: String?,
        from: String?,
        to: String?,
        sender: String?,
        talker: String?,
        nativeUrl: String?,
        exclusiveRecvUser: String?,
        groupMessage: Boolean,
        redPacketMessage: Boolean,
        type: Int,
        createTimeSeconds: Long,
        msgSvrId: Long
    ) : this(
        content,
        xml,
        from,
        to,
        sender,
        talker,
        nativeUrl,
        exclusiveRecvUser,
        groupMessage,
        redPacketMessage,
        type,
        createTimeSeconds,
        msgSvrId,
        "",
        ""
    )
}
