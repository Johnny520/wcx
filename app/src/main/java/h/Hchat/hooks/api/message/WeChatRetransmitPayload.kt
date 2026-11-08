package h.Hchat.hooks.api.message

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.utils.KavaReflector
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap

data class WeChatRetransmitPayload(
    val msgId: Long,
    val sourceTalker: String,
    val content: String,
    val retrType: Int,
    val msgFromScene: Int,
    val fileName: String,
    val length: Int
)

object WeChatRetransmitPayloadFactory {
    @JvmStatic
    fun build(
        message: WeChatMessage,
        nativeMessage: Any?
    ): WeChatRetransmitPayload? {
        val normalizedType = WeChatMessageTypes.normalize(message.type)
        val appMessage = isRetransmittableAppMessage(message)
        val content = retransmitContent(message, appMessage)
        val fileName = when {
            message.isImage() -> resolveImagePath(message, nativeMessage).orEmpty()
            message.isVideo() || normalizedType == 62 -> resolveVideoPath(message).orEmpty()
            else -> message.imagePath.orEmpty()
        }
        val retrType = when {
            appMessage -> {
                if (content.isBlank()) return null
                retransmitTypeForAppMsg(message)
            }
            message.isText() -> {
                if (content.isBlank()) return null
                4
            }
            message.isShareCard() -> {
                if (content.isBlank()) return null
                8
            }
            message.isImage() -> {
                if (fileName.isBlank()) return null
                0
            }
            message.isEmoji() -> {
                if (content.isBlank() && fileName.isBlank()) return null
                5
            }
            message.isLocation() -> {
                if (content.isBlank()) return null
                9
            }
            message.isVideo() || normalizedType == 62 -> {
                if (fileName.isBlank()) return null
                if (normalizedType == 62) 11 else 1
            }
            else -> return null
        }
        return WeChatRetransmitPayload(
            msgId = message.msgId,
            sourceTalker = message.talker.ifBlank { WeChatApis.chatPage()?.currentTalker().orEmpty() },
            content = content,
            retrType = retrType,
            msgFromScene = if (appMessage) 1 else 2,
            fileName = fileName,
            length = parseVideoLength(content)
        )
    }

    private fun resolveImagePath(
        message: WeChatMessage,
        nativeMessage: Any?
    ): String? {
        val tokens = imagePathTokens(message, nativeMessage)
        val nativeCandidates = imageNativeMessageCandidates(message.msgId, nativeMessage)
        val imageApi = WeChatApis.media()?.images()
        val nativePaths = LinkedHashSet<String>()
        for (candidate in nativeCandidates) {
            imageApi?.resolveBestAvailablePath(candidate)
                ?.takeIf { it.isNotBlank() }
                ?.let(nativePaths::add)
        }
        val directPaths = tokens.mapNotNullTo(LinkedHashSet<String>(), ::existingFilePath)
        val resolvedPaths = tokens.mapNotNullTo(LinkedHashSet<String>()) { token ->
            imageApi?.resolvePathToken(token)?.takeIf { it.isNotBlank() }
        }
        val availablePaths = LinkedHashSet<String>().apply {
            addAll(nativePaths)
            addAll(directPaths)
            addAll(resolvedPaths)
        }
        return availablePaths.maxByOrNull { File(it).length() }
            ?: tokens.firstOrNull()?.takeIf { allowRawImageTokenFallback() }
    }

    private fun resolveVideoPath(message: WeChatMessage): String? {
        val token = message.imagePath.trim()
        if (token.isBlank()) return null
        val direct = File(token)
        if (direct.isFile) return direct.absolutePath
        return WeChatApis.media()?.videos()?.resolvePathToken(token)?.takeIf { it.isNotBlank() }
            ?: token
    }

    private fun imageNativeMessageCandidates(msgId: Long, source: Any?): List<Any> {
        val result = ArrayList<Any>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun add(candidate: Any?) {
            candidate ?: return
            for (value in nativeMessageCandidates(candidate)) {
                if (visited.add(value)) result += value
            }
        }
        add(runCatching { WeChatApis.database()?.nativeMessageById(msgId) }.getOrNull())
        add(source)
        return result
    }

    private fun imagePathTokens(message: WeChatMessage, nativeMessage: Any?): List<String> {
        val result = LinkedHashSet<String>()
        fun add(value: String?) {
            value?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add)
        }
        add(message.imagePath)
        add(WeChatMessage.xmlAttr(message.content, "imgpath"))
        add(WeChatMessage.xmlAttr(message.bodyContent(), "imgpath"))
        add(WeChatMessage.xmlTag(message.content, "imgpath"))
        add(WeChatMessage.xmlTag(message.bodyContent(), "imgpath"))
        nativeMessage?.let { source ->
            nativeMessageCandidates(source).forEach { candidate ->
                add(readMessageValue(candidate, "getImgPath", "field_imgPath", "imgPath") as? String)
            }
        }
        return result.toList()
    }

    private fun existingFilePath(value: String?): String? {
        val file = File(value?.trim().orEmpty())
        return file.absolutePath.takeIf { file.isFile }
    }

    private fun nativeMessageCandidates(source: Any): List<Any> {
        val result = ArrayList<Any>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        result += source
        visited.add(source)
        val sourceMsgId = messageId(source)
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, source) ?: continue
                if (!visited.add(value)) continue
                if (!value.javaClass.name.startsWith("com.tencent.mm.storage.") && sourceMsgId <= 0L) continue
                if (sourceMsgId > 0L && messageId(value) != sourceMsgId) continue
                result += value
            }
            current = current.superclass
        }
        return result
    }

    private fun messageId(message: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID", "getId")) {
            (KavaReflector.invokeMethod(message, name) as? Number)?.toLong()?.takeIf { it > 0L }?.let { return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID", "id")) {
            (KavaReflector.readField(message, name) as? Number)?.toLong()?.takeIf { it > 0L }?.let { return it }
        }
        return 0L
    }

    private fun readMessageValue(source: Any, getter: String, fieldName: String, fallbackField: String): Any? {
        KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, getter), source)?.let { return it }
        KavaReflector.readField(source, fieldName)?.let { return it }
        return KavaReflector.readField(source, fallbackField)
    }

    private fun allowRawImageTokenFallback(): Boolean {
        val version = WeChatApis.version()?.current()
        return !version?.versionName.orEmpty().startsWith("8.0.49") && version?.versionCode != 2600L
    }

    @JvmStatic
    fun isRetransmittableAppMessage(message: WeChatMessage): Boolean {
        if (message.isApp() || message.isQuote()) return true
        val body = message.bodyContent()
        return body.contains("<appmsg", ignoreCase = true) && body.contains("</appmsg>", ignoreCase = true)
    }

    private fun retransmitContent(message: WeChatMessage, appMessage: Boolean): String {
        val raw = message.content.orEmpty()
        val body = message.bodyContent()
        return when {
            appMessage -> body.ifBlank { raw }
            message.isShareCard() -> body.ifBlank { raw }
            message.isText() && message.isGroupChat() -> body.ifBlank { raw }
            else -> raw
        }
    }

    private fun retransmitTypeForAppMsg(message: WeChatMessage): Int {
        return when (message.appMsgType()) {
            57 -> 2
            16 -> 14
            19, 24 -> 10
            51 -> 18
            63 -> 23
            73 -> 22
            75 -> 12
            82 -> 30
            88 -> 31
            94 -> 33
            106 -> 36
            111 -> 37
            113 -> 38
            119, 120 -> 40
            129 -> 42
            else -> 2
        }
    }

    private fun parseVideoLength(content: String): Int {
        return Regex("<(?:length|voicelength)>(\\d+)</(?:length|voicelength)>", RegexOption.IGNORE_CASE)
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0
    }
}
