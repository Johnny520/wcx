package h.Hchat.event

object Events {
    class MessageReceived(
        @JvmField val xml: String?,
        @JvmField val sender: String?,
        @JvmField val talker: String?,
        @JvmField val content: String?,
        @JvmField val msgType: String?,
        @JvmField val createTimeSeconds: Long,
        @JvmField val msgSvrId: Long,
        @JvmField val msgSource: String?,
        @JvmField val selfWxId: String?,
        @JvmField val source: String?,
        @JvmField val outgoing: Boolean
    ) {
        constructor(
            xml: String?,
            sender: String?,
            talker: String?,
            content: String?,
            msgType: String?,
            createTimeSeconds: Long,
            msgSvrId: Long,
            msgSource: String?,
            selfWxId: String?
        ) : this(xml, sender, talker, content, msgType, createTimeSeconds, msgSvrId, msgSource, selfWxId, null, false)

        constructor(
            xml: String?,
            sender: String?,
            talker: String?,
            content: String?,
            msgType: String?,
            createTimeSeconds: Long,
            msgSvrId: Long
        ) : this(xml, sender, talker, content, msgType, createTimeSeconds, msgSvrId, null, null, null, false)
    }

    class MessageBlocked(
        @JvmField val xml: String?,
        @JvmField val sender: String?,
        @JvmField val talker: String?,
        @JvmField val content: String?,
        @JvmField val msgType: String?,
        @JvmField val createTimeSeconds: Long,
        @JvmField val msgSvrId: Long,
        @JvmField val msgSource: String?,
        @JvmField val selfWxId: String?,
        @JvmField val nativeUrl: String?
    )

    class MessageRecalled(
        @JvmField val talker: String,
        @JvmField val sourceMsgId: Long,
        @JvmField val sourceMsgSvrId: Long,
        @JvmField val lookupSvrIds: Set<Long>
    )

    class RedPacketDetected(
        @JvmField val source: String?,
        @JvmField val xml: String?,
        @JvmField val sender: String?,
        @JvmField val talker: String?,
        @JvmField val nativeUrl: String?,
        @JvmField val exclusiveRecvUser: String?
    )

    class PatDetected(
        @JvmField val fromUser: String?,
        @JvmField val pattedUser: String?,
        @JvmField val template: String?,
        @JvmField val talker: String?,
        @JvmField val createTime: Long,
        @JvmField val svrId: Long
    )

    class RedPacketGrabbed(
        @JvmField val sendId: String?,
        @JvmField val amount: String?,
        @JvmField val talker: String?
    )

    class RedPacketFailed(
        @JvmField val sendId: String?,
        @JvmField val talker: String?,
        @JvmField val reason: String?
    )

    class NetworkDispatcherReady(
        @JvmField val dispatcherInstance: Any?
    )

    class DexReady

    class ConfigChanged(
        @JvmField val featureId: String?,
        @JvmField val key: String?
    )

    class FeatureInstalled(
        @JvmField val featureName: String?
    )

    class FeatureUninstalled(
        @JvmField val featureName: String?
    )
}
