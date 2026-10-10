package h.Hchat.hooks.items.script

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.message.WeChatMessageStoreApi
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.model.WeChatQuoteMsg
import me.hd.wauxv.data.bean.MsgInfoBean
import java.util.Collections
import kotlin.math.abs

private const val QUOTE_TIME_WINDOW_MS = 120_000L
private const val QUOTE_TIME_WINDOW_SECONDS = 120L

class ScriptMessageBean private constructor(
    private val event: Events.MessageReceived?,
    private val observed: WeChatMessageObserveApi.ObservedMessage?,
    private val stored: WeChatMessage?
) : MsgInfoBean() {
    init {
        xml = getXml()
        sender = getSender()
        senderId = getSenderId()
        sendTalker = getSendTalker()
        talker = getTalker()
        talkerId = getTalkerId()
        content = getContent()
        text = getText()
        msgId = getMsgId()
        msgType = getMsgType()
        type = getType()
        createTime = getCreateTime()
        msgSvrId = getMsgSvrId()
        msgSource = getMsgSource()
        selfWxId = getSelfWxId()
        source = getSource()
        kind = getKind()
        nativeUrl = getNativeUrl()
    }

    internal constructor(event: Events.MessageReceived) : this(event, null, null)

    internal constructor(observed: WeChatMessageObserveApi.ObservedMessage) : this(null, observed, null)

    internal constructor(message: WeChatMessage) : this(null, null, message)

    fun getXml(): String = stored?.xml() ?: observed?.xml ?: event?.xml.orEmpty()

    fun getSender(): String = stored?.let { storedSender(it) } ?: observed?.sender ?: event?.sender.orEmpty()

    fun getTalker(): String = stored?.talker ?: observed?.talker ?: event?.talker.orEmpty()

    fun getTalkerId(): String = getTalker()

    fun getContent(): String = stored?.bodyContent() ?: observed?.content ?: event?.content.orEmpty()

    fun getText(): String = getContent()

    fun getMsgId(): Long = stored?.msgId ?: observed?.getMsgId() ?: 0L

    fun getMsgType(): String = stored?.type?.takeIf { it > 0 }?.toString()
        ?: observed?.getType()?.takeIf { it > 0 }?.toString()
        ?: event?.msgType.orEmpty()

    fun getType(): String = getMsgType()

    fun getSendTalker(): String = stored?.let { storedSender(it) } ?: observed?.getSendTalker() ?: getSender()

    fun getSenderId(): String = getSendTalker()

    fun getCreateTime(): Long = stored?.createTime?.takeIf { it > 0L }
        ?: observed?.getCreateTime()?.takeIf { it > 0L }
        ?: event?.createTimeSeconds
        ?: 0L

    fun getCreateTimeSeconds(): Long {
        val time = getCreateTime()
        return if (time > 100000000000L) time / 1000L else time
    }

    fun getMsgSvrId(): Long = stored?.msgSvrId ?: observed?.message?.msgSvrId ?: event?.msgSvrId ?: 0L

    fun getMsgSource(): String = stored?.getMsgSource() ?: observed?.msgSource ?: event?.msgSource.orEmpty()

    fun getAtUserList(): List<String> = stored?.getAtUserList() ?: observed?.getAtUserList() ?: Collections.emptyList()

    fun getSelfWxId(): String = stored?.selfWxId ?: observed?.message?.selfWxId ?: event?.selfWxId.orEmpty()

    fun getSource(): String = if (stored != null) "message_db" else observed?.source ?: event?.source.orEmpty()

    fun getKind(): String = stored?.let { kindOf(it) } ?: observed?.kind.orEmpty()

    fun getNativeUrl(): String = stored?.nativeUrl() ?: observed?.nativeUrl.orEmpty()

    fun getMessage(): Any? = stored ?: observed?.message

    fun getStoredMessage(): Any? = stored ?: observed?.storedMessage

    fun getImageMsg(): Any? = toWaImageMsg(stored?.getImageMsg() ?: observed?.imageMsg)

    fun getVideoMsg(): Any? = stored?.getVideoMsg() ?: observed?.message?.getVideoMsg()

    fun getQuoteMsg(): Any? {
        stored?.getQuoteMsg()?.let { return quoteBean(it) }
        observed?.quoteMsg?.let { return quoteBean(it) }
        fallbackQuoteMsg()?.let { return quoteBean(it) }
        return null
    }

    fun getFileMsg(): Any? = stored?.getFileMsg() ?: observed?.fileMsg

    fun getTransferMsg(): Any? = stored?.getTransferMsg() ?: observed?.transferMsg

    fun getPatMsg(): Any? = stored?.getPatMsg() ?: observed?.patMsg

    fun isSend(): Boolean {
        stored?.let { return it.isSend() }
        observed?.let { return it.isSend() }
        val rawEvent = event ?: return false
        if (rawEvent.outgoing) return true
        val self = getSelfWxId()
        val sender = getSender()
        return self.isNotBlank() && sender == self
    }

    fun isSelf(): Boolean = isSend()

    fun isGroupChat(): Boolean = stored?.isGroupChat() ?: observed?.isGroupChat() ?: getTalker().endsWith("@chatroom")

    fun isChatroom(): Boolean = stored?.isChatroom() ?: observed?.isChatroom() ?: isGroupChat()

    fun isImChatroom(): Boolean = stored?.isImChatroom() ?: observed?.isImChatroom() ?: getTalker().endsWith("@im.chatroom")

    fun isPrivateChat(): Boolean = stored?.isPrivateChat() ?: observed?.isPrivateChat() ?: !isGroupChat()

    fun isOpenIM(): Boolean = stored?.isOpenIM() ?: observed?.isOpenIM() ?: getTalker().endsWith("@openim")

    fun isOfficialAccount(): Boolean = stored?.isOfficialAccount() ?: observed?.isOfficialAccount() ?: false

    fun isText(): Boolean = stored?.isText() ?: observed?.isText() ?: (getMsgType() == "1")

    fun isImage(): Boolean = stored?.isImage() ?: observed?.isImage() ?: (getMsgType() == "3")

    fun isVoice(): Boolean = stored?.isVoice() ?: observed?.isVoice() ?: (getMsgType() == "34")

    fun isVideo(): Boolean = stored?.let { it.isVideo() || it.type == 62 } ?: observed?.isVideo() ?: (getMsgType() == "43" || getMsgType() == "62")

    fun isAppMsg(): Boolean = stored?.isApp() ?: observed?.isApp() ?: WeChatMessageTypes.isApp(getMsgType().toIntOrNull() ?: 0)

    fun isApp(): Boolean = isAppMsg()

    fun isEmoji(): Boolean = stored?.isEmoji() ?: observed?.isEmoji() ?: (getMsgType() == "47")

    fun isLocation(): Boolean = stored?.isLocation() ?: observed?.isLocation() ?: (getMsgType() == "48")

    fun isSystem(): Boolean = stored?.isSystem() ?: observed?.isSystem() ?: WeChatMessageTypes.isSystem(getMsgType().toIntOrNull() ?: 0)

    fun isRedPacket(): Boolean = stored?.isRedPacket() ?: observed?.isRedPacket() ?: false

    fun isRedBag(): Boolean = isRedPacket()

    fun isTransfer(): Boolean = stored?.isTransfer() ?: observed?.isTransfer() ?: false

    fun isQuote(): Boolean = stored?.isQuote() ?: observed?.isQuote() ?: false

    fun isFile(): Boolean = stored?.isFile() ?: observed?.isFile() ?: false

    fun isLink(): Boolean = stored?.isLink() ?: observed?.isLink() ?: false

    fun isMusic(): Boolean = stored?.isMusic() ?: observed?.isMusic() ?: false

    fun isNote(): Boolean = stored?.isNote() ?: observed?.isNote() ?: false

    fun isShareCard(): Boolean = stored?.isShareCard() ?: observed?.isShareCard() ?: false

    fun isVoip(): Boolean = stored?.isVoip() ?: observed?.isVoip() ?: false

    fun isVoipVoice(): Boolean = stored?.isVoipVoice() ?: observed?.isVoipVoice() ?: false

    fun isVoipVideo(): Boolean = stored?.isVoipVideo() ?: observed?.isVoipVideo() ?: false

    fun isVideoNumberVideo(): Boolean = stored?.isVideoNumberVideo() ?: observed?.isVideoNumberVideo() ?: false

    fun isPat(): Boolean = stored?.isPat() ?: observed?.isPat() ?: false

    fun isRecalled(): Boolean = stored?.isRecalled() ?: observed?.isRecalled() ?: false

    fun isAnnounceAll(): Boolean = stored?.isAnnounceAll() ?: observed?.isAnnounceAll() ?: false

    fun isNotifyAll(): Boolean = stored?.isNotifyAll() ?: observed?.isNotifyAll() ?: false

    fun isAtMe(): Boolean {
        stored?.let { return it.isAtMe() }
        observed?.let { return it.isAtMe() }
        val self = getSelfWxId()
        return WeChatMessage.isAtMeMessage(getMsgSource(), getContent(), self)
    }

    private fun storedSender(message: WeChatMessage): String {
        val self = message.selfWxId
        if (message.isOutgoing() && self.isNotBlank()) return self
        return message.sendTalker()
    }

    private fun quoteBean(value: WeChatQuoteMsg): ScriptQuoteMsgBean {
        return ScriptQuoteMsgBean.from(value, resolveQuotedSender(value))
    }

    private fun resolveQuotedSender(value: WeChatQuoteMsg): String {
        val store = WeChatApis.messageStore()
        val quoted = if (value.svrId > 0L && store != null) {
            runCatching { store.getMessageBySvrId(value.talker, value.svrId) }.getOrNull()
                ?: runCatching { store.getMessageBySvrId(value.svrId) }.getOrNull()
        } else {
            null
        }
        quoted?.let(::storedSender)
            ?.takeIf(::isRealQuotedSender)
            ?.let { return it }

        if (store != null && (value.createTime > 0L || value.content.isNotBlank())) {
            resolveQuotedMessageByMetadata(value, store)
                ?.let(::storedSender)
                ?.takeIf(::isRealQuotedSender)
                ?.let { return it }
        }

        return value.sendTalker.takeIf(::isRealQuotedSender).orEmpty()
    }

    private fun resolveQuotedMessageByMetadata(
        value: WeChatQuoteMsg,
        store: WeChatMessageStoreApi
    ): WeChatMessage? {
        val talker = value.talker.ifBlank { getTalker() }
        if (talker.isBlank()) return null

        val candidates = ArrayList<WeChatMessage>()
        val seen = HashSet<String>()
        fun append(rows: List<WeChatMessage>?) {
            rows.orEmpty().forEach { message ->
                val key = "${message.msgId}:${message.msgSvrId}:${message.createTime}"
                if (seen.add(key)) candidates += message
            }
        }

        val rawTime = value.createTime
        if (rawTime > 0L) {
            val millis = if (rawTime < 100_000_000_000L) rawTime * 1000L else rawTime
            append(runCatching {
                store.getMessagesBetween(talker, millis - QUOTE_TIME_WINDOW_MS, millis + QUOTE_TIME_WINDOW_MS, Int.MAX_VALUE)
            }.getOrNull())
            val seconds = if (rawTime >= 100_000_000_000L) rawTime / 1000L else rawTime
            if (seconds != millis) {
                append(runCatching {
                    store.getMessagesBetween(talker, seconds - QUOTE_TIME_WINDOW_SECONDS, seconds + QUOTE_TIME_WINDOW_SECONDS, Int.MAX_VALUE)
                }.getOrNull())
            }
        } else {
            append(runCatching { store.getMessages(talker, 0, Int.MAX_VALUE) }.getOrNull())
        }
        if (candidates.isEmpty()) return null

        val quoteType = WeChatMessageTypes.normalize(value.type)
        val pool = if (quoteType > 0) {
            candidates.filter { WeChatMessageTypes.normalize(it.type) == quoteType }
        } else {
            candidates
        }
        if (pool.isEmpty()) return null
        val content = value.content.trim()
        val contentMatches = if (content.isBlank()) emptyList() else pool.filter {
            val candidateContent = it.bodyContent().trim()
            candidateContent.isNotBlank() && (
                candidateContent == content ||
                    candidateContent.contains(content) ||
                    content.contains(candidateContent)
                )
        }
        val ranked = if (contentMatches.isNotEmpty()) contentMatches else pool
        val targetTime = normalizeQuoteTime(rawTime)
        val valid = ranked.filter { isRealQuotedSender(storedSender(it)) }
        if (valid.isEmpty()) return null
        if (targetTime <= 0L) return valid.singleOrNull()
        val minimumDelta = valid.minOf { abs(normalizeQuoteTime(it.createTime) - targetTime) }
        return valid.filter {
            abs(normalizeQuoteTime(it.createTime) - targetTime) == minimumDelta
        }.singleOrNull()
    }

    private fun normalizeQuoteTime(value: Long): Long {
        return if (value in 1L until 100_000_000_000L) value * 1000L else value
    }

    private fun isRealQuotedSender(value: String): Boolean {
        return value.isNotBlank() && !WeChatMessage.isGroupTalker(value)
    }

    private fun kindOf(message: WeChatMessage): String {
        return when {
            message.isRedPacket() -> "red_packet"
            message.isTransfer() -> "transfer"
            message.isQuote() -> "quote"
            message.isFile() -> "file"
            message.isPat() -> "pat"
            message.isLink() -> "link"
            message.isMusic() -> "music"
            message.isNote() -> "note"
            message.isVideoNumberVideo() -> "video_number_video"
            else -> WeChatMessageTypes.nameOf(message.type)
        }
    }

    private fun fallbackQuoteMsg(): WeChatQuoteMsg? {
        val content = getContent()
        if (content.isBlank()) return null
        val transient = WeChatMessage.fromTransient(
            getTalker(),
            getSender(),
            content,
            getCreateTime(),
            isSend(),
            0,
            getMsgSvrId(),
            getMsgSource(),
            getSelfWxId()
        )
        return transient.getQuoteMsg()
    }

    private fun toWaImageMsg(imageMsg: Any?): Any? {
        if (imageMsg == null) return null
        if (imageMsg is MsgInfoBean.ImageMsg) return imageMsg
        val md5 = callString(imageMsg, "getMd5", "md5")
        val bigUrl = callString(imageMsg, "getBigImgUrl", "bigImgUrl")
        val midUrl = callString(imageMsg, "getMidImgUrl", "midImgUrl")
        val thumbUrl = callString(imageMsg, "getThumbUrl", "thumbUrl")
        val key = firstNotBlank(
            callString(imageMsg, "getKey", "key"),
            callString(imageMsg, "getAesKey", "aesKey")
        )
        val bigLength = callInt(imageMsg, "getBigLength", "bigLength")
        val midLength = callInt(imageMsg, "getMidLength", "midLength")
        val thumbLength = callInt(imageMsg, "getThumbLength", "thumbLength")
        return MsgInfoBean.ImageMsg(md5, bigUrl, midUrl, thumbUrl, key, bigLength, midLength, thumbLength)
    }

    private fun firstNotBlank(vararg values: String?): String {
        for (value in values) {
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    private fun callString(instance: Any, methodName: String, fieldName: String): String {
        return runCatching {
            instance.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(instance)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: fieldValue(instance, fieldName)?.toString().orEmpty()
        }.getOrDefault("")
    }

    private fun callInt(instance: Any, methodName: String, fieldName: String): Int {
        val value = runCatching {
            instance.javaClass.methods.firstOrNull { it.name == methodName && it.parameterTypes.isEmpty() }
                ?.invoke(instance)
                ?: fieldValue(instance, fieldName)
        }.getOrNull()
        return when (value) {
            is Number -> value.toInt().coerceAtLeast(0)
            is String -> (value.toIntOrNull() ?: 0).coerceAtLeast(0)
            else -> 0
        }
    }

    private fun fieldValue(instance: Any, fieldName: String): Any? {
        var current: Class<*>? = instance.javaClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == fieldName }?.let { field ->
                field.isAccessible = true
                return field.get(instance)
            }
            current = current.superclass
        }
        return null
    }

    override fun toString(): String {
        return "ScriptMessageBean(talker=${getTalker()}, sender=${getSender()}, type=${getMsgType()}, send=${isSend()}, content=${getContent()})"
    }
}

class ScriptQuoteMsgBean private constructor(
    private val title: String,
    private val msgSource: String,
    private val sendTalker: String,
    private val displayName: String,
    private val talker: String,
    private val type: Int,
    private val content: String,
    private val svrId: Long,
    private val strId: String,
    private val createTime: Long
) {
    fun getTitle(): String = title
    fun getMsgSource(): String = msgSource
    fun getSendTalker(): String = sendTalker
    fun getSenderId(): String = sendTalker
    fun getDisplayName(): String = displayName
    fun getTalker(): String = talker
    fun getTalkerId(): String = talker
    fun getType(): Int = type
    fun getContent(): String = content
    fun getSvrId(): Long = svrId
    fun getStrId(): String = strId
    fun getCreateTime(): Long = createTime

    companion object {
        fun from(value: WeChatQuoteMsg, resolvedSendTalker: String = value.sendTalker): ScriptQuoteMsgBean {
            return ScriptQuoteMsgBean(
                value.title,
                value.msgSource,
                resolvedSendTalker,
                value.displayName,
                value.talker,
                value.type,
                value.content,
                value.svrId,
                value.strId,
                value.createTime
            )
        }
    }
}
