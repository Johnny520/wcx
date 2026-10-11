package h.Hchat.hooks.api.model

class WeChatMessage(
    @JvmField val msgId: Long,
    @JvmField val msgSvrId: Long,
    @JvmField val type: Int,
    @JvmField val status: Int,
    @JvmField val isSend: Int,
    @JvmField val createTime: Long,
    talker: String?,
    content: String?,
    imagePath: String?,
    reserved: String?,
    translatedContent: String?,
    @JvmField val flag: Int,
    msgSource: String?,
    selfWxId: String?
) {
    @JvmField val talker: String = talker.orEmpty()
    @JvmField val content: String = content.orEmpty()
    @JvmField val imagePath: String = imagePath.orEmpty()
    @JvmField val reserved: String = reserved.orEmpty()
    @JvmField val translatedContent: String = translatedContent.orEmpty()
    @JvmField val msgSource: String = msgSource.orEmpty()
    @JvmField val selfWxId: String = selfWxId.orEmpty()

    constructor(
        msgId: Long,
        msgSvrId: Long,
        type: Int,
        status: Int,
        isSend: Int,
        createTime: Long,
        talker: String?,
        content: String?,
        imagePath: String?,
        reserved: String?,
        translatedContent: String?,
        flag: Int
    ) : this(
        msgId,
        msgSvrId,
        type,
        status,
        isSend,
        createTime,
        talker,
        content,
        imagePath,
        reserved,
        translatedContent,
        flag,
        "",
        ""
    )

    fun getMsgId(): Long = msgId

    fun getType(): Int = type

    fun getCreateTime(): Long = createTime

    fun getTalker(): String = talker

    fun getSendTalker(): String = sendTalker()

    fun getContent(): String = content

    fun getMsgSource(): String {
        val raw = firstNotBlank(
            msgSource,
            xmlSection(content, "msgsource"),
            xmlSection(bodyContent(), "msgsource"),
            xmlSection(reserved, "msgsource")
        )
        return unescapeXml(raw)
    }

    fun getAtUserList(): List<String> {
        return parseAtUserList(getMsgSource())
    }

    fun getEffectiveAtUserList(): List<String> {
        return when (getAtMentionType()) {
            AtMentionType.AT_ALL -> listOf("notify@all")
            AtMentionType.ANNOUNCEMENT_ALL -> listOf("announcement@all")
            else -> getAtUserList()
        }
    }

    fun getAtMentionType(): AtMentionType {
        return classifyAtMention(getMsgSource(), bodyContent(), selfWxId)
    }

    fun isAnnounceAll(): Boolean {
        return getAtMentionType() == AtMentionType.ANNOUNCEMENT_ALL
    }

    fun isNotifyAll(): Boolean {
        return getAtMentionType() == AtMentionType.AT_ALL
    }

    fun isAtMe(selfWxId: String?): Boolean {
        return classifyAtMention(getMsgSource(), bodyContent(), selfWxId) == AtMentionType.AT_ME
    }

    fun isAtMe(): Boolean = isAtMe(selfWxId)

    fun isOutgoing(): Boolean = isSend == 1

    fun isIncoming(): Boolean = isSend == 0

    fun isSend(): Boolean = isOutgoing()

    fun isFromGroup(): Boolean = isGroupChat()

    fun isPrivateChat(): Boolean = talker.isNotBlank() && !isGroupChat() && !isOfficialAccount()

    fun isOpenIM(): Boolean = talker.endsWith("@openim")

    fun isGroupChat(): Boolean = isGroupTalker(talker)

    fun isChatroom(): Boolean = talker.endsWith("@chatroom")

    fun isImChatroom(): Boolean = talker.endsWith("@im.chatroom")

    fun isOfficialAccount(): Boolean = talker.endsWith("@app") ||
        talker.startsWith("gh_") ||
        talker == "newsapp"

    fun sendTalker(): String {
        if (isOutgoing()) return talker
        val prefixEnd = content.indexOf(":\n")
        if (isGroupChat() && prefixEnd > 0) return normalizeUsername(content.substring(0, prefixEnd))
        if (isGroupChat() && isEmoji()) {
            val emojiSender = cleanXmlText(firstNotBlank(
                xmlAttr(bodyContent(), "fromusername"),
                xmlTag(bodyContent(), "fromusername")
            ))
            if (emojiSender.isNotBlank() && !isGroupTalker(emojiSender)) return emojiSender
        }
        return talker
    }

    fun bodyContent(): String {
        val prefixEnd = content.indexOf(":\n")
        if (isGroupChat() && prefixEnd > 0) {
            return content.substring(prefixEnd + 2)
        }
        return content
    }

    fun xml(): String = extractXml(bodyContent())

    fun isText(): Boolean = WeChatMessageTypes.isText(type)

    fun isImage(): Boolean = WeChatMessageTypes.isImage(type)

    fun isVoice(): Boolean = WeChatMessageTypes.isVoice(type)

    fun isVideo(): Boolean = WeChatMessageTypes.isVideo(type)

    fun isEmoji(): Boolean = WeChatMessageTypes.isEmoji(type)

    fun isLocation(): Boolean = WeChatMessageTypes.isLocation(type)

    fun isApp(): Boolean = WeChatMessageTypes.isApp(type)

    fun isShareCard(): Boolean = type == 42 || type == 66

    fun isVoip(): Boolean = type == 50 || type == 1000052 || type == 1000053

    fun isVoipVoice(): Boolean = type == 50 || type == 1000052

    fun isVoipVideo(): Boolean = type == 1000053

    fun isSystem(): Boolean = WeChatMessageTypes.isSystem(type)

    fun isRecalled(): Boolean = type == WeChatMessageTypes.RECALLED ||
        content.contains("<revokemsg", ignoreCase = true) ||
        content.contains("revokemsg", ignoreCase = true) ||
        content.contains("revoke_msg", ignoreCase = true) ||
        content.contains("撤回")

    fun isRedPacket(): Boolean {
        val raw = bodyContent()
        val nativeUrl = xmlTag(raw, "nativeurl").lowercase()
        return nativeUrl.contains("receivehongbao", ignoreCase = true) ||
            nativeUrl.contains("wxhb", ignoreCase = true) ||
            nativeUrl.contains("hongbao", ignoreCase = true) ||
            raw.contains("receivehongbao", ignoreCase = true) ||
            raw.contains("wxhb_personalreceive", ignoreCase = true) ||
            raw.contains("/hongbao/", ignoreCase = true) ||
            xmlTag(raw, "type") == "2001"
    }

    fun isTransfer(): Boolean {
        val raw = bodyContent()
        val appType = appMsgType()
        return (isApp() && (appType == 2000 || appType == 2011)) ||
            (raw.contains("<wcpayinfo>", ignoreCase = true) &&
                (raw.contains("<transferid>", ignoreCase = true) ||
                raw.contains("<transcationid>", ignoreCase = true) ||
                raw.contains("<transactionid>", ignoreCase = true) ||
                raw.contains("transfer_id=", ignoreCase = true) ||
                raw.contains("trans_id=", ignoreCase = true) ||
                raw.contains("transferoperation", ignoreCase = true)))
    }

    fun isQuote(): Boolean {
        val raw = bodyContent()
        return appMsgType() == 57 ||
            raw.contains("<refermsg>", ignoreCase = true) ||
            raw.contains("<referfromusr>", ignoreCase = true) ||
            raw.contains("<refermsgid>", ignoreCase = true)
    }

    fun isPat(): Boolean {
        val raw = bodyContent()
        return isSystem() && (
            raw.contains("pattedUser", ignoreCase = true) ||
            raw.contains("pattedusername", ignoreCase = true) ||
            raw.contains("拍了拍")
        )
    }

    fun isFile(): Boolean {
        val raw = bodyContent()
        if (!isApp()) return false
        val appType = appMsgType()
        if (appType > 0) return appType == 6
        return raw.contains("<fileext>", ignoreCase = true) ||
            raw.contains("<filename>", ignoreCase = true) ||
            raw.contains("<cdnattachurl>", ignoreCase = true)
    }

    fun isLink(): Boolean = isApp() && (appMsgType() == 4 || appMsgType() == 5)

    fun isMusic(): Boolean = isApp() && (appMsgType() == 3 || appMsgType() == 76)

    fun isMiniProgram(): Boolean = isApp() && (appMsgType() == 33 || appMsgType() == 36)

    fun appMsgType(): Int = xmlTag(bodyContent(), "type").toIntOrNull() ?: 0

    fun getAppMsgType(): Int = appMsgType()

    fun nativeUrl(): String = xmlTag(bodyContent(), "nativeurl")

    fun getImageMsg(): WeChatImageMsg? {
        if (!isImage()) return null
        val raw = bodyContent()
        return WeChatImageMsg(
            md5 = firstNotBlank(xmlAttr(raw, "md5"), xmlTag(raw, "md5")),
            bigImgUrl = firstNotBlank(xmlAttr(raw, "cdnbigimgurl"), xmlTag(raw, "cdnbigimgurl")),
            midImgUrl = firstNotBlank(xmlAttr(raw, "cdnmidimgurl"), xmlTag(raw, "cdnmidimgurl")),
            thumbUrl = firstNotBlank(xmlAttr(raw, "cdnthumburl"), xmlTag(raw, "cdnthumburl")),
            key = firstNotBlank(xmlAttr(raw, "aeskey"), xmlTag(raw, "aeskey")),
            bigLength = firstNotBlank(xmlAttr(raw, "hdlength"), xmlTag(raw, "hdlength")).toIntOrNull() ?: 0,
            midLength = firstNotBlank(xmlAttr(raw, "length"), xmlTag(raw, "length")).toIntOrNull() ?: 0,
            thumbLength = firstNotBlank(xmlAttr(raw, "cdnthumblength"), xmlTag(raw, "cdnthumblength")).toIntOrNull() ?: 0
        )
    }

    fun getVideoMsg(): WeChatVideoMsg? {
        if (!isVideo() && type != 62) return null
        val raw = bodyContent()
        return WeChatVideoMsg(
            md5 = firstNotBlank(xmlAttr(raw, "md5"), xmlTag(raw, "md5")),
            newMd5 = firstNotBlank(xmlAttr(raw, "newmd5"), xmlTag(raw, "newmd5")),
            cdnVideoUrl = firstNotBlank(xmlAttr(raw, "cdnvideourl"), xmlTag(raw, "cdnvideourl")),
            aesKey = firstNotBlank(xmlAttr(raw, "aeskey"), xmlTag(raw, "aeskey")),
            length = firstNotBlank(xmlAttr(raw, "length"), xmlTag(raw, "length")).toLongOrNull() ?: 0L,
            playLength = firstNotBlank(xmlAttr(raw, "playlength"), xmlTag(raw, "playlength")).toIntOrNull() ?: 0
        )
    }

    fun getQuoteMsg(): WeChatQuoteMsg? {
        if (!isQuote()) return null
        val raw = bodyContent()
        val refer = firstNotBlank(xmlSection(raw, "refermsg"), raw)
        val fromUser = cleanXmlText(firstNotBlank(xmlTag(refer, "fromusr"), xmlTag(raw, "referfromusr"), xmlTag(raw, "fromusername")))
        val chatUser = cleanXmlText(firstNotBlank(xmlTag(refer, "chatusr"), xmlTag(raw, "refertowusr")))
        val referContent = cleanXmlText(firstNotBlank(xmlTag(refer, "content"), xmlTag(raw, "refermsgcontent")))
        val emojiSender = cleanXmlText(firstNotBlank(
            xmlAttr(referContent, "fromusername"),
            xmlAttr(refer, "fromusername"),
            xmlAttr(raw, "fromusername")
        ))
        return WeChatQuoteMsg(
            title = cleanXmlText(xmlTag(raw, "title")),
            msgSource = cleanXmlText(xmlTag(refer, "msgsource")),
            sendTalker = quoteSender(fromUser, chatUser, emojiSender),
            displayName = cleanXmlText(xmlTag(refer, "displayname")),
            talker = quoteTalker(fromUser, chatUser),
            type = firstNotBlank(xmlTag(raw, "refermsgtype"), xmlTag(refer, "type")).toIntOrNull() ?: 0,
            content = referContent,
            svrId = firstNotBlank(xmlTag(refer, "svrid"), xmlTag(raw, "refermsgid")).toLongOrNull() ?: 0L,
            strId = cleanXmlText(xmlTag(refer, "strid")),
            createTime = xmlTag(refer, "createtime").toLongOrNull() ?: 0L
        )
    }

    private fun quoteSender(fromUser: String, chatUser: String, explicitSender: String = ""): String {
        return when {
            !isGroupTalker(explicitSender) && explicitSender.isNotBlank() -> explicitSender
            isGroupTalker(fromUser) -> firstNonGroup(chatUser)
            isGroupTalker(chatUser) -> firstNonGroup(fromUser)
            isGroupChat() -> firstNonGroup(chatUser, fromUser)
            else -> firstNotBlank(chatUser, fromUser)
        }
    }

    private fun quoteTalker(fromUser: String, chatUser: String): String {
        return when {
            isGroupTalker(fromUser) -> fromUser
            isGroupTalker(chatUser) -> chatUser
            talker.isNotBlank() -> talker
            else -> firstNotBlank(fromUser, chatUser)
        }
    }

    fun getFileMsg(): WeChatFileMsg? {
        if (!isFile()) return null
        val raw = bodyContent()
        val title = firstNotBlank(xmlTag(raw, "title"), xmlTag(raw, "filename"))
        return WeChatFileMsg(
            title = title,
            size = xmlTag(raw, "totallen").toLongOrNull() ?: xmlTag(raw, "length").toLongOrNull() ?: 0L,
            ext = firstNotBlank(xmlTag(raw, "fileext"), title.substringAfterLast('.', "")),
            md5 = firstNotBlank(xmlTag(raw, "filemd5"), xmlTag(raw, "md5"), xmlTag(raw, "cdnthumbmd5")),
            url = firstNotBlank(xmlTag(raw, "cdnattachurl"), xmlTag(raw, "attachid"), xmlTag(raw, "url")),
            key = firstNotBlank(xmlTag(raw, "aeskey"), xmlTag(raw, "cdnthumbaeskey")),
            attachId = xmlTag(raw, "attachid"),
            fileName = xmlTag(raw, "filename")
        )
    }

    fun getTransferMsg(): WeChatTransferMsg? {
        if (!isTransfer()) return null
        val raw = bodyContent()
        val fee = firstNotBlank(xmlTag(raw, "total_fee"), xmlTag(raw, "feederval"), xmlTag(raw, "fee")).toLongOrNull() ?: 0L
        return WeChatTransferMsg(
            transactionId = firstNotBlank(xmlTag(raw, "transcationid"), xmlTag(raw, "transactionid"), xmlTag(raw, "transaction_id")),
            transId = firstNotBlank(xmlTag(raw, "transferid"), xmlTag(raw, "transfer_id"), xmlTag(raw, "trans_id")),
            payer = firstNotBlank(xmlTag(raw, "payer_username"), xmlTag(raw, "payerusername"), xmlTag(raw, "username"), sendTalker()),
            receiver = firstNotBlank(xmlTag(raw, "receiver_username"), xmlTag(raw, "receiverusername")),
            invalidTime = xmlTag(raw, "invalidtime").toLongOrNull() ?: 0L,
            fee = fee,
            description = firstNotBlank(xmlTag(raw, "pay_memo"), xmlTag(raw, "feedesc"), xmlTag(raw, "desc"), xmlTag(raw, "title")),
            rawXml = extractXml(raw)
        )
    }

    fun getPatMsg(): WeChatPatMsg? {
        if (!isPat()) return null
        val raw = bodyContent()
        return WeChatPatMsg(
            talker = talker,
            fromUser = sendTalker(),
            pattedUser = "",
            template = raw,
            createTime = createTime
        )
    }

    fun isRedBag(): Boolean = isRedPacket()

    fun isVideoNumberVideo(): Boolean {
        return WeChatMessageTypes.isVideoAccount(type) ||
            (isApp() && isVideoNumberContent(bodyContent()))
    }

    fun isNote(): Boolean {
        val raw = bodyContent()
        return isApp() && (appMsgType() == 53 ||
            raw.contains("solitaire", ignoreCase = true) ||
            raw.contains("接龙"))
    }

    enum class AtMentionType {
        NONE,
        AT_ME,
        AT_ALL,
        ANNOUNCEMENT_ALL,
        OTHERS
    }

    companion object {
        private const val WECHAT_AT_SEPARATOR = '\u2005'
        private const val MAX_WECHAT_AT_LENGTH = 40
        private val AT_ALL_LABELS = setOf("所有人", "all", "everyone", "全員", "모두")

        @JvmStatic
        fun parseAtUserList(msgSource: String?): List<String> {
            val source = msgSource.orEmpty()
            val atUsers = firstNotBlank(
                xmlTag(source, "atuserlist"),
                msgSourceValue(source, ".msgsource.atuserlist"),
                msgSourceValue(source, "atuserlist")
            )
            if (atUsers.isBlank()) return emptyList()
            return atUsers.split(',', ';', '|', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        @JvmStatic
        fun classifyAtMention(
            msgSource: String?,
            content: String?,
            selfWxId: String?
        ): AtMentionType {
            val source = unescapeXml(msgSource.orEmpty())
            val atUsers = parseAtUserList(source)
            if (atUsers.any { it.equals("announcement@all", ignoreCase = true) } ||
                source.contains("announcement@all", ignoreCase = true)
            ) {
                return AtMentionType.ANNOUNCEMENT_ALL
            }
            val explicitAtAll = atUsers.any {
                it.equals("notify@all", ignoreCase = true) ||
                    it.equals("all", ignoreCase = true) ||
                    it.equals("@all", ignoreCase = true)
            } || source.contains("notify@all", ignoreCase = true) || hasPositiveAtAllFlag(source)
            val self = selfWxId.orEmpty()
            val expandedAtAll = self.isNotBlank() &&
                atUsers.any { it.equals(self, ignoreCase = true) } &&
                containsWechatAtAllMention(content.orEmpty())
            if (explicitAtAll ||
                expandedAtAll
            ) {
                return AtMentionType.AT_ALL
            }
            if (self.isNotBlank() && atUsers.any { it.equals(self, ignoreCase = true) }) {
                return AtMentionType.AT_ME
            }
            return if (atUsers.isEmpty()) AtMentionType.NONE else AtMentionType.OTHERS
        }

        @JvmStatic
        fun isNotifyAllMessage(
            msgSource: String?,
            content: String?,
            selfWxId: String?
        ): Boolean {
            return classifyAtMention(msgSource, content, selfWxId) == AtMentionType.AT_ALL
        }

        @JvmStatic
        fun isAtMeMessage(
            msgSource: String?,
            content: String?,
            selfWxId: String?
        ): Boolean {
            return classifyAtMention(msgSource, content, selfWxId) == AtMentionType.AT_ME
        }

        @JvmStatic
        fun effectiveAtUserList(
            msgSource: String?,
            content: String?,
            selfWxId: String?
        ): List<String> {
            return when (classifyAtMention(msgSource, content, selfWxId)) {
                AtMentionType.AT_ALL -> listOf("notify@all")
                AtMentionType.ANNOUNCEMENT_ALL -> listOf("announcement@all")
                else -> parseAtUserList(msgSource)
            }
        }

        private fun hasPositiveAtAllFlag(source: String): Boolean {
            val value = firstNotBlank(
                xmlTag(source, "atall"),
                msgSourceValue(source, ".msgsource.atall"),
                msgSourceValue(source, "atall")
            ).trim()
            return value.toIntOrNull()?.let { it > 0 } == true ||
                value.equals("true", ignoreCase = true)
        }

        private fun containsWechatAtAllMention(rawContent: String): Boolean {
            val senderPrefix = rawContent.indexOf(":\n")
            val content = if (senderPrefix > 0) rawContent.substring(senderPrefix + 2) else rawContent
            var cursor = 0
            while (cursor < content.length) {
                val at = content.indexOf('@', cursor)
                if (at < 0) return false
                val end = content.indexOf(WECHAT_AT_SEPARATOR, at + 1)
                if (end < 0) return false
                if (end - at <= MAX_WECHAT_AT_LENGTH) {
                    val label = content.substring(at + 1, end).substringAfterLast('@').trim()
                    if (AT_ALL_LABELS.any { it.equals(label, ignoreCase = true) }) return true
                }
                cursor = end + 1
            }
            return false
        }

        @JvmStatic
        fun isVideoNumberContent(content: String?): Boolean {
            val raw = content.orEmpty()
            val appType = xmlTag(raw, "type").toIntOrNull() ?: 0
            if (appType == 51) return true
            if (appType != 0) return false
            return raw.contains("<finderFeed>", ignoreCase = true) ||
                raw.contains("<finderObject>", ignoreCase = true) ||
                raw.contains("<finderUsername>", ignoreCase = true) ||
                raw.contains("<objectId>", ignoreCase = true) &&
                raw.contains("<objectNonceId>", ignoreCase = true)
        }

        @JvmStatic
        fun inferType(content: String?): Int {
            val raw = content.orEmpty()
            return when {
                raw.contains(":\n<msg>", ignoreCase = true) || raw.contains("<appmsg", ignoreCase = true) -> WeChatMessageTypes.APP
                raw.contains("<img", ignoreCase = true) -> WeChatMessageTypes.IMAGE
                raw.contains("<voicemsg", ignoreCase = true) -> WeChatMessageTypes.VOICE
                raw.contains("<videomsg", ignoreCase = true) -> WeChatMessageTypes.VIDEO
                raw.contains("<emoji", ignoreCase = true) -> WeChatMessageTypes.EMOJI
                raw.contains("<location", ignoreCase = true) -> WeChatMessageTypes.LOCATION
                raw.contains("revoke", ignoreCase = true) || raw.contains("撤回") -> WeChatMessageTypes.RECALLED
                else -> WeChatMessageTypes.TEXT
            }
        }

        @JvmStatic
        fun transient(
            talker: String?,
            sender: String?,
            content: String?,
            createTime: Long,
            outgoing: Boolean
        ): WeChatMessage {
            return transient(talker, sender, content, createTime, outgoing, inferType(content), 0L)
        }

        @JvmStatic
        fun fromTransient(
            talker: String?,
            sender: String?,
            content: String?,
            createTime: Long,
            outgoing: Boolean
        ): WeChatMessage {
            return transient(talker, sender, content, createTime, outgoing)
        }

        @JvmStatic
        fun fromTransient(
            talker: String?,
            sender: String?,
            content: String?,
            createTime: Long,
            outgoing: Boolean,
            type: Int,
            msgSvrId: Long
        ): WeChatMessage {
            return transient(talker, sender, content, createTime, outgoing, type, msgSvrId, "", "")
        }

        @JvmStatic
        fun fromTransient(
            talker: String?,
            sender: String?,
            content: String?,
            createTime: Long,
            outgoing: Boolean,
            type: Int,
            msgSvrId: Long,
            msgSource: String?,
            selfWxId: String?
        ): WeChatMessage {
            return transient(talker, sender, content, createTime, outgoing, type, msgSvrId, msgSource, selfWxId)
        }

        @JvmStatic
        fun transient(
            talker: String?,
            sender: String?,
            content: String?,
            createTime: Long,
            outgoing: Boolean,
            type: Int,
            msgSvrId: Long
        ): WeChatMessage {
            return transient(talker, sender, content, createTime, outgoing, type, msgSvrId, "", "")
        }

        @JvmStatic
        fun transient(
            talker: String?,
            sender: String?,
            content: String?,
            createTime: Long,
            outgoing: Boolean,
            type: Int,
            msgSvrId: Long,
            msgSource: String?,
            selfWxId: String?
        ): WeChatMessage {
            val safeTalker = talker.orEmpty()
            val safeSender = sender.orEmpty()
            val safeContent = content.orEmpty()
            val contentWithSender = if (!outgoing &&
                isGroupTalker(safeTalker) &&
                safeSender.isNotBlank() &&
                !safeContent.startsWith("$safeSender:\n")
            ) {
                "$safeSender:\n$safeContent"
            } else {
                safeContent
            }
            return WeChatMessage(
                0L,
                msgSvrId,
                if (type > 0) type else inferType(contentWithSender),
                0,
                if (outgoing) 1 else 0,
                createTime,
                safeTalker,
                contentWithSender,
                "",
                "",
                "",
                0,
                msgSource,
                selfWxId
            )
        }

        @JvmStatic
        fun isGroupTalker(value: String?): Boolean {
            val talker = value.orEmpty()
            return talker.endsWith("@chatroom") ||
                talker.endsWith("@im.chatroom") ||
                talker.endsWith("@openim")
        }

        @JvmStatic
        fun xmlTag(xml: String?, tag: String): String {
            if (xml.isNullOrBlank() || tag.isBlank()) return ""
            val cdata = Regex("<$tag><!\\[CDATA\\[(.*?)]]></$tag>", RegexOption.IGNORE_CASE)
                .find(xml)
            if (cdata != null) return cdata.groupValues[1]
            val plain = Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(xml)
            return plain?.groupValues?.getOrNull(1)?.trim().orEmpty()
        }

        @JvmStatic
        fun xmlSection(xml: String?, tag: String): String {
            if (xml.isNullOrBlank() || tag.isBlank()) return ""
            val section = Regex("<$tag\\b[^>]*>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(xml)
            return section?.groupValues?.getOrNull(1)?.trim().orEmpty()
        }

        @JvmStatic
        fun xmlAttr(xml: String?, attr: String): String {
            if (xml.isNullOrBlank() || attr.isBlank()) return ""
            val pattern = Regex("\\b$attr\\s*=\\s*(['\"])(.*?)\\1", RegexOption.IGNORE_CASE)
            return pattern.find(xml)?.groupValues?.getOrNull(2).orEmpty()
        }

        @JvmStatic
        fun msgSourceValue(source: String?, key: String): String {
            val raw = unescapeXml(source.orEmpty())
            if (raw.isBlank() || key.isBlank()) return ""
            val escaped = Regex.escape(key)
            val patterns = arrayOf(
                Regex("$escaped\\s*=\\s*(['\"])(.*?)\\1", RegexOption.IGNORE_CASE),
                Regex("$escaped\\s*=\\s*<!\\[CDATA\\[(.*?)]]>", RegexOption.IGNORE_CASE),
                Regex("$escaped\\s*=\\s*([^,;\\s}]+)", RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val match = pattern.find(raw)
                if (match != null) return match.groupValues.last().trim()
            }
            return ""
        }

        @JvmStatic
        fun extractXml(content: String?): String {
            val raw = content.orEmpty()
            val prefixEnd = raw.indexOf(":\n")
            return if (prefixEnd > 0 && raw.indexOf('<') > prefixEnd) {
                raw.substring(prefixEnd + 2)
            } else {
                raw
            }
        }

        private fun normalizeUsername(value: String): String {
            var result = value.trim()
            while (result.endsWith("]") || result.endsWith(")") ||
                result.endsWith(",") || result.endsWith(";")
            ) {
                result = result.dropLast(1).trim()
            }
            val newline = result.indexOf('\n')
            if (newline > 0) result = result.substring(0, newline).trim()
            return result
        }

        private fun firstNotBlank(vararg values: String?): String {
            return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
        }

        private fun firstNonGroup(vararg values: String?): String {
            return values.firstOrNull { !it.isNullOrBlank() && !isGroupTalker(it) }.orEmpty()
        }

        private fun cleanXmlText(value: String): String {
            return unescapeXmlNumeric(unescapeXml(value.trim())).trim()
        }

        private fun unescapeXmlNumeric(value: String): String {
            if (value.isBlank()) return value
            return Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(value) { match ->
                val raw = match.groupValues[1]
                val codePoint = runCatching {
                    if (raw.startsWith("x", ignoreCase = true)) {
                        raw.substring(1).toInt(16)
                    } else {
                        raw.toInt()
                    }
                }.getOrNull()
                if (codePoint == null) {
                    match.value
                } else {
                    runCatching { String(Character.toChars(codePoint)) }.getOrDefault(match.value)
                }
            }
        }

        private fun unescapeXml(value: String): String {
            return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }
}

data class WeChatQuoteMsg(
    @JvmField val title: String,
    @JvmField val msgSource: String,
    @JvmField val sendTalker: String,
    @JvmField val displayName: String,
    @JvmField val talker: String,
    @JvmField val type: Int,
    @JvmField val content: String,
    @JvmField val svrId: Long,
    @JvmField val strId: String,
    @JvmField val createTime: Long
) {
    fun getTitle(): String = title
    fun getMsgSource(): String = msgSource
    fun getSendTalker(): String = sendTalker
    fun getDisplayName(): String = displayName
    fun getTalker(): String = talker
    fun getType(): Int = type
    fun getContent(): String = content
    fun getSvrId(): Long = svrId
    fun getStrId(): String = strId
    fun getCreateTime(): Long = createTime
}

data class WeChatImageMsg(
    @JvmField val md5: String,
    @JvmField val bigImgUrl: String,
    @JvmField val midImgUrl: String,
    @JvmField val thumbUrl: String,
    @JvmField val key: String,
    @JvmField val bigLength: Int = 0,
    @JvmField val midLength: Int = 0,
    @JvmField val thumbLength: Int = 0
) {
    fun getMd5(): String = md5
    fun getBigImgUrl(): String = bigImgUrl
    fun getMidImgUrl(): String = midImgUrl
    fun getThumbUrl(): String = thumbUrl
    fun getBigLength(): Int = bigLength
    fun getMidLength(): Int = midLength
    fun getThumbLength(): Int = thumbLength
    fun getKey(): String = key
}

data class WeChatVideoMsg(
    @JvmField val md5: String,
    @JvmField val newMd5: String,
    @JvmField val cdnVideoUrl: String,
    @JvmField val aesKey: String,
    @JvmField val length: Long,
    @JvmField val playLength: Int
) {
    fun getMd5(): String = md5
    fun getNewMd5(): String = newMd5
    fun getCdnVideoUrl(): String = cdnVideoUrl
    fun getAesKey(): String = aesKey
    fun getLength(): Long = length
    fun getPlayLength(): Int = playLength
}

data class WeChatFileMsg(
    @JvmField val title: String,
    @JvmField val size: Long,
    @JvmField val ext: String,
    @JvmField val md5: String,
    @JvmField val url: String,
    @JvmField val key: String,
    @JvmField val attachId: String,
    @JvmField val fileName: String
) {
    fun getTitle(): String = title
    fun getSize(): Long = size
    fun getExt(): String = ext
    fun getMd5(): String = md5
    fun getUrl(): String = url
    fun getKey(): String = key
    fun getAttachId(): String = attachId
    fun getFileName(): String = fileName
}

data class WeChatTransferMsg(
    @JvmField val transactionId: String,
    @JvmField val transId: String,
    @JvmField val payer: String,
    @JvmField val receiver: String,
    @JvmField val invalidTime: Long,
    @JvmField val fee: Long,
    @JvmField val description: String,
    @JvmField val rawXml: String
) {
    @JvmField val transferId: String = transId
    @JvmField val payerUsername: String = payer

    fun getTransactionId(): String = transactionId
    fun getTransferId(): String = transferId
    fun getTransId(): String = transId
    fun getPayerUsername(): String = payerUsername
    fun getPayer(): String = payer
    fun getReceiver(): String = receiver
    fun getInvalidTime(): Long = invalidTime
    fun getFee(): Long = fee
    fun getDescription(): String = description
    fun getRawXml(): String = rawXml
}

data class WeChatPatMsg(
    @JvmField val talker: String,
    @JvmField val fromUser: String,
    @JvmField val pattedUser: String,
    @JvmField val template: String,
    @JvmField val createTime: Long
) {
    fun getTalker(): String = talker
    fun getFromUser(): String = fromUser
    fun getPattedUser(): String = pattedUser
    fun getTemplate(): String = template
    fun getCreateTime(): Long = createTime
}
