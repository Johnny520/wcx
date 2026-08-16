@file:Suppress("NOTHING_TO_INLINE", "DEPRECATION", "unused")

package com.Johnny.wcx.features.api.core.models

// type=0 post
// type=1 plain text
// type=3 image
// type=34 voice
// type=37 add friend request verification
// type=40 friends you possible know
// type=42 contact card
// type=43 video
// type=48 static location
// type=49 app message
// type=50 voip
// type=51 app initialization
// type=52 voip notification
// type=53 voip invitation
// type=419430449 cash transfer
// type=436207665 red packet
// type=1040187441 qq music
// type=1090519089 file

enum class MessageType(val code: Int, val displayName: String) {
    MOMENTS(0, "朋友圈"),

    @Deprecated("Use MessageType.isText()")
    TEXT(1, "文本"),
    IMAGE(3, "图片"),
    VOICE(34, "语音"),
    FRIEND_VERIFY(37, "好友验证"),
    CONTACT_RECOMMEND(40, "名片推荐"),
    CARD(42, "名片"),
    VIDEO(43, "视频"),

    @Deprecated("Use MessageType.isSticker()")
    STICKER(47, "表情"),

    @Deprecated("Use MessageType.isLocation()")
    LOCATION(48, "位置"),
    APP(49, "应用消息"),

    @Deprecated("Use MessageType.isVoip()")
    VOIP(50, "音视频通话"),
    STATUS(51, "状态"),

    @Deprecated("Use MessageType.isVoip()")
    VOIP_NOTIFY(52, "通话通知"),

    @Deprecated("Use MessageType.isVoip()")
    VOIP_INVITE(53, "通话邀请"),
    MICRO_VIDEO(62, "小视频"),
    SYSTEM_NOTICE(9999, "系统通知"),

    SYSTEM(10000, "系统消息"),

    @Deprecated("Use MessageType.isLocation()")
    SYSTEM_LOCATION(10002, "系统位置"),

    @Deprecated("Use MessageType.isSticker()")
    SO_GOU_EMOJI(1048625, "搜狗表情"),

    @Deprecated("Use MessageType.isLink()")
    LINK(16777265, "链接"),
    RECALL(268445456, "撤回消息"),
    SERVICE(318767153, "服务消息"),
    TRANSFER(419430449, "转账"),

    @Deprecated("Use MessageType.isRedPacket()")
    RED_PACKET(436207665, "红包"),

    @Deprecated("Use MessageType.isRedPacket()")
    SPECIAL_RED_PACKET(469762097, "裂变红包"),
    ACCOUNT_VIDEO(486539313, "视频号视频"),
    RED_PACKET_COVER(536936497, "红包封面"),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT(754974769, "视频号"),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT_CARD(771751985, "视频号名片"),
    GROUP_NOTE(805306417, "群笔记"),
    QUOTE(822083633, "引用消息"),
    PAT(922746929, "拍一拍"),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT_LIVE(973078577, "视频号直播"),

    @Deprecated("Use MessageType.isLink()")
    PRODUCT(974127153, "商品链接"),
    UNKNOWN(975175729, "未知类型"),

    @Deprecated("Use MessageType.isLink()")
    MUSIC(1040187441, "音乐链接"),
    FILE(1090519089, "文件"),
    ;

    inline val isText get() = code == TEXT.code || code == QUOTE.code
    inline val isLink get() = code == LINK.code || code == MUSIC.code || code == PRODUCT.code
    inline val isRedPacket
        get() =
            code == RED_PACKET.code || code == SPECIAL_RED_PACKET.code
    inline val isSystem get() = code == SYSTEM.code || code == SYSTEM_NOTICE.code
    inline val isSticker get() = code == STICKER.code || code == SO_GOU_EMOJI.code
    inline val isLocation get() = code == LOCATION.code || code == SYSTEM_LOCATION.code
    inline val isVideoAccount
        get() =
            code == VIDEO_ACCOUNT.code || code == VIDEO_ACCOUNT_CARD.code || code == VIDEO_ACCOUNT_LIVE.code
    inline val isVoip
        get() =
            code == VOIP.code || code == VOIP_NOTIFY.code || code == VOIP_INVITE.code

    companion object {

        fun fromCode(code: Int): MessageType? = entries.find { it.code == code }
    }
}
