package h.Hchat.hooks.items.script

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.message.WeChatMessageStoreApi
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.model.WeChatQuoteMsg
import java.util.Collections
import kotlin.math.abs

private const val QUOTE_TIME_WINDOW_MS = 120_000L
private const val QUOTE_TIME_WINDOW_SECONDS = 120L

class ScriptMessageBean private constructor(
    private val event: Events.MessageReceived?,
    private val observed: WeChatMessageObserveApi.ObservedMessage?,
    private val stored: WeChatMessage?
) {
    // ---------------------------------------------------------------------
    // 迁移说明（路线 A：改 Hchat 侧适配 WCX）
    //
    // 原实现为 `ScriptMessageBean : me.hd.wauxv.data.bean.MsgInfoBean`，依赖 Hchat 侧的“扁平字段” MsgInfoBean。
    // 在 WCX 仓库中，同一 FQN 下已是“包装型” `class MsgInfoBean(@JvmField val origin: Any)`（final、字段全为 val、
    // 无扁平字段），既不能被继承，语义也完全不同，因此这里不再继承该类型，改为把脚本需要访问的扁平字段
    // 内联进本类（ScriptMessageBean 自身）。
    //
    // 对外语义与 Hchat 版保持一致：脚本按“公有字段名”访问（如 msg.content / msg.talker / msg.msgType），
    // 本类原有的 getXxx() 方法全部保留。字段使用 @JvmField 是为了暴露公有字段、并避免与已存在的
    // getXxx() 方法产生 JVM 访问器命名冲突（否则 `var content` 会生成 getContent() 与 `fun getContent()` 冲突）。
    // ---------------------------------------------------------------------
    @JvmField
    var xml: String = ""

    @JvmField
    var sender: String = ""

    @JvmField
    var senderId: String = ""

    @JvmField
    var sendTalker: String = ""

    @JvmField
    var talker: String = ""

    @JvmField
    var talkerId: String = ""

    @JvmField
    var content: String = ""

    @JvmField
    var text: String = ""

    @JvmField
    var msgId: Long = 0L

    @JvmField
    var msgType: String = ""

    @JvmField
    var type: String = ""

    @JvmField
    var createTime: Long = 0L

    @JvmField
    var msgSvrId: Long = 0L

    @JvmField
    var msgSource: String = ""

    @JvmField
    var selfWxId: String = ""

    @JvmField
    var source: String = ""

    @JvmField
    var kind: String = ""

    @JvmField
    var nativeUrl: String = ""

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
        if (imageMsg is ImageMsg) return imageMsg
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
        return ImageMsg(md5, bigUrl, midUrl, thumbUrl, key, bigLength, midLength, thumbLength)
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

    /**
     * 迁移自 Hchat `me.hd.wauxv.data.bean.MsgInfoBean.ImageMsg`（路线 A：内联到脚本子系统自有数据类）。
     *
     * WCX 仓库中 `me.hd.wauxv.data.bean.MsgInfoBean` 已被占用（包装型），无法再承载 Hchat 的扁平 ImageMsg，
     * 故把它内联为本类的嵌套类型。为保持脚本可见的“公有字段 + getter”语义，字段用 @JvmField 暴露为公有字段，
     * 并保留原 Java 版全部构造签名与 getter（供 BeanShell 反射或脚本直接调用）。
     */
    class ImageMsg {
        @JvmField
        var md5: String = ""

        @JvmField
        var bigImgUrl: String = ""

        @JvmField
        var midImgUrl: String = ""

        @JvmField
        var thumbUrl: String = ""

        @JvmField
        var key: String = ""

        @JvmField
        var bigLength: Int = 0

        @JvmField
        var midLength: Int = 0

        @JvmField
        var thumbLength: Int = 0

        constructor()

        constructor(md5: String?, bigImgUrl: String?, midImgUrl: String?, thumbUrl: String?, key: String?) : this(
            md5,
            bigImgUrl,
            midImgUrl,
            thumbUrl,
            key,
            0,
            0,
            0
        )

        constructor(
            md5: String?,
            bigImgUrl: String?,
            midImgUrl: String?,
            thumbUrl: String?,
            key: String?,
            bigLength: Int,
            midLength: Int,
            thumbLength: Int
        ) {
            this.md5 = md5 ?: ""
            this.bigImgUrl = bigImgUrl ?: ""
            this.midImgUrl = midImgUrl ?: ""
            this.thumbUrl = thumbUrl ?: ""
            this.key = key ?: ""
            this.bigLength = Math.max(0, bigLength)
            this.midLength = Math.max(0, midLength)
            this.thumbLength = Math.max(0, thumbLength)
        }

        fun getMd5(): String = md5

        fun getBigImgUrl(): String = bigImgUrl

        fun getMidImgUrl(): String = midImgUrl

        fun getThumbUrl(): String = thumbUrl

        fun getCdnUrl(): String {
            if (thumbUrl.isNotEmpty()) return thumbUrl
            if (midImgUrl.isNotEmpty()) return midImgUrl
            return bigImgUrl
        }

        fun getKey(): String = key

        fun getAesKey(): String = key

        fun getBigLength(): Int = bigLength

        fun getMidLength(): Int = midLength

        fun getThumbLength(): Int = thumbLength
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
