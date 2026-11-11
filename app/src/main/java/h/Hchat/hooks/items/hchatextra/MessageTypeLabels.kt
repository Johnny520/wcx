package h.Hchat.hooks.items.hchatextra

import h.Hchat.hooks.api.model.WeChatMessageTypes
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/** 仅用于详情标签；消息 raw type、appmsg type、合并转发 datatype 是三套独立编号。 */
internal object MessageTypeLabels {
    private const val MAX_XML_LENGTH = 512 * 1024

    // 8.0.76 pluginsdk.model.app.k0.p 的明确映射；低位 49 的高位不是 appmsg type。
    // 包含负数及低 16 位不为 49 的类型，不能仅依赖公共 normalize。
    private fun rawAppType(type: Int): Int? = when (type) {
        16777265 -> 1
        268435505 -> 2
        1048625 -> 8
        335544369 -> 10
        369098801 -> 13
        452984881 -> 16
        -1879048186 -> 17
        402653233 -> 20
        553648177, 587202609 -> 33
        520093745 -> 34
        1124073521 -> 41
        687865905 -> 46
        704643121 -> 47
        738197553 -> 48
        771751985 -> 50
        754974769 -> 51
        788529201 -> 52
        805306417 -> 53
        486539313 -> 54
        822083633 -> 57
        838860849 -> 59
        855638065 -> 60
        922746929 -> 62
        973078577 -> 63
        989855793 -> 65
        905969713 -> 66
        939524145 -> 69
        1006633009 -> 72
        956301361 -> 73
        1023475761, 1023541297, 1023606833, 1023672369, 1057030193 -> 75
        1040187441 -> 76
        1074790449 -> 77
        1075839025 -> 80
        1076887601 -> 81
        974127153 -> 82
        1107296305 -> 87
        975175729 -> 88
        1078984753 -> 89
        1409286193 -> 92
        1426063409 -> 93
        976224305 -> 94
        1080033329 -> 95
        977272881 -> 96
        1140850737 -> 101
        1157627953 -> 105
        1174405169 -> 106
        1627390001 -> 109
        978321457 -> 111
        1081081905 -> 112
        979370033 -> 113
        1895825457 -> 114
        -2130706383 -> 115
        1191182385 -> 116
        1442840625 -> 118
        1207959601 -> 119
        1224736817 -> 120
        -2113929167 -> 124
        -2097151951 -> 126
        -2080374735 -> 128
        1241514033 -> 129
        1476395057 -> 133
        419430449 -> 2000
        436207665, 469762097, 503316529 -> 2001
        536870961 -> 2002
        536936497 -> 2003
        671088689 -> 671088689
        else -> null
    }

    /** 只读最外层 appmsg 的直接子节点，不能读入引用/聊天记录中的另一条消息。 */
    fun appType(content: String): Int? = runCatching {
        val parser = xmlParser(content) ?: return null
        var appDepth = -1
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                if (parser.depth > 64) return null
                if (appDepth < 0) {
                    if (parser.depth == 1 && parser.name == "appmsg" ||
                        parser.depth == 2 && parser.name == "appmsg") appDepth = parser.depth
                } else if (parser.depth == appDepth + 1 && parser.name == "type") {
                    return parser.nextText().trim().toIntOrNull()
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.depth == appDepth) {
                return null
            }
        }
        null
    }.getOrNull()

    private fun xmlParser(content: String): XmlPullParser? {
        if (content.length > MAX_XML_LENGTH || content.contains("<!DOCTYPE", true)) return null
        val start = content.indexOf('<')
        if (start < 0) return null
        return XmlPullParserFactory.newInstance().newPullParser().also {
            it.setInput(StringReader(content.substring(start)))
        }
    }

    fun subtype(type: Int, content: String, body: String): Int? {
        val raw = rawAppType(type)
        if (raw == null && type != 1090519089 &&
            WeChatMessageTypes.normalize(type) != WeChatMessageTypes.APP) return null
        return appType(body) ?: (if (body != content) appType(content) else null) ?: raw
    }

    private fun appLabel(sub: Int): String? = when (sub) {
        1 -> "文字卡片"
        2 -> "图片卡片"
        3, 76, 92 -> "音乐"
        4, 5 -> "链接"
        6, 74, 130, 131 -> "文件"
        8 -> "表情卡片"
        17 -> "位置共享"
        19 -> "聊天记录"
        24 -> "收藏笔记"
        33, 36 -> "小程序"
        44 -> "小店店铺卡片"
        46 -> "微视视频"
        50 -> "视频号名片"
        51, 106, 129 -> "视频号视频"
        53 -> "接龙"
        54 -> "视频"
        57 -> "引用回复"
        60 -> "直播分享"
        62 -> "拍一拍"
        63, 88 -> "视频号直播"
        65 -> "直播邀请"
        68 -> "轻应用"
        80 -> "订阅通知"
        82 -> "小店商品"
        87 -> "文字"
        89 -> "直播抽奖"
        96 -> "小店订单"
        101 -> "游戏分享"
        112 -> "视频号直播订阅通知"
        113 -> "直播主题"
        115, 124 -> "送礼物"
        116 -> "小店客服商品卡片"
        118 -> "听一听聊天室"
        126 -> "小店客服动态卡片"
        128 -> "直播抽奖礼物"
        2000, 2011 -> "转账"
        2001 -> "红包"
        else -> null
    }

    fun label(type: Int, content: String, body: String = content): String {
        // 通知/拍一拍同样有低位 49；先匹配已确认的专用 raw type。
        when (type) {
            889192497, 922746929 -> return "拍一拍"
            10000, 10002, 570425393, 64, 603979825, 268445456, 268445458, 285222674 ->
                return systemLabel(content, body)
            318767153 -> return "模板通知"
            872415281 -> return "小程序通知"
            285212721 -> return "公众号图文"
            301989937 -> return "文字"
            1090519089 -> return "文件"
            1057030193 -> return "视频号视频"
            -1879048189 -> return "语音提醒确认"
            -1879048190 -> return "语音提醒"
            -1879048185 -> return "硬件消息"
            -1879048183 -> return "硬件点赞消息"
        }
        val base = WeChatMessageTypes.normalize(type)
        if (rawAppType(type) != null || base == WeChatMessageTypes.APP) {
            val sub = subtype(type, content, body)
            return if (sub == null) {
                if (type == WeChatMessageTypes.APP) "链接/卡片" else "卡片（类型 $type）"
            } else appLabel(sub) ?: "卡片（子类型 $sub，类型 $type）"
        }
        return when (base) {
            1, 11, 21, 31, 36 -> "文字"
            3, 13, 23, 33, 39 -> "图片"
            34 -> "语音"
            37 -> "好友申请"
            40 -> "好友推荐"
            42, 66 -> "名片"
            43 -> "视频"
            47 -> "表情"
            48 -> "位置"
            50, 51, 52, 53 -> "通话消息"
            62 -> "小视频"
            67 -> "微信客服名片"
            82 -> "商品"
            85, 86 -> "来电铃声"
            10000, 10002 -> systemLabel(content, body)
            else -> "消息（类型 $type）"
        }
    }

    private fun systemLabel(content: String, body: String): String {
        // 普通文字提及“拍一拍”不是拍一拍事件；系统 XML 必须验证结构。
        val kind = systemType(body) ?: if (content != body) systemType(content) else null
        return when (kind) {
            "pat", "patmsg" -> "拍一拍"
            "revokemsg" -> "撤回消息"
            null, "sysmsgtemplate" -> "系统消息"
            else -> "系统消息（$kind）"
        }
    }

    private fun systemType(content: String): String? = runCatching {
        val parser = xmlParser(content) ?: return null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (parser.depth > 2) return null
            if (parser.name == "sysmsg") return parser.getAttributeValue(null, "type")
                ?.takeIf { it.length in 1..64 && it.all { ch -> ch.isLetterOrDigit() || ch == '_' } }
        }
        null
    }.getOrNull()

    /** 合并转发详情的数据类型，绝不能用 normalize 或 appmsg type 解释。 */
    fun recordLabel(type: Int): String = when (type) {
        1 -> "文字"
        2 -> "图片"
        3 -> "语音"
        4, 15 -> "视频"
        5, 36 -> "链接"
        6 -> "位置"
        7, 29, 32 -> "音乐"
        8, 10130 -> "文件"
        10 -> "商品"
        11 -> "商城商品"
        14 -> "电视"
        16 -> "名片"
        17 -> "聊天记录/笔记"
        19 -> "小程序"
        21 -> "收藏笔记"
        22 -> "视频号视频"
        23 -> "视频号直播"
        26, 41 -> "视频号名片"
        27, 30 -> "视频号话题"
        28 -> "视频号专栏"
        31 -> "微信客服名片"
        34, 39 -> "小店橱窗"
        40 -> "直播主题"
        10132 -> "图文号名片"
        else -> "记录消息（类型 $type）"
    }
}
