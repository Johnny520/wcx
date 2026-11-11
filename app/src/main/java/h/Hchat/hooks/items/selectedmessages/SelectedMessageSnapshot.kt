package h.Hchat.hooks.items.selectedmessages

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.VoiceMessageDurationResolver
import h.Hchat.hooks.api.message.WeChatRetransmitPayload
import h.Hchat.hooks.api.message.WeChatRetransmitPayloadFactory
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import java.io.File

data class SelectedMessageSnapshot(
    val msgId: Long,
    val type: Int,
    val sourceTalker: String,
    val content: String,
    val imagePath: String,
    val createTime: Long,
    val retransmit: WeChatRetransmitPayload?,
    val voicePath: String,
    val voiceDurationMillis: Int,
    val voiceFileName: String = "",
    val videoDurationSeconds: Int = 0,
    /** The live host message is only kept for the current in-memory action. */
    val nativeMessage: Any? = null
) {
    fun encode(): String {
        return JSONObject().apply {
            put("msgId", msgId)
            put("type", type)
            put("sourceTalker", sourceTalker)
            put("content", content)
            put("imagePath", imagePath)
            put("createTime", createTime)
            put("voicePath", voicePath)
            put("voiceDurationMillis", voiceDurationMillis)
            put("voiceFileName", voiceFileName)
            put("videoDurationSeconds", videoDurationSeconds)
            retransmit?.let { payload ->
                put("retransmit", JSONObject().apply {
                    put("msgId", payload.msgId)
                    put("sourceTalker", payload.sourceTalker)
                    put("content", payload.content)
                    put("retrType", payload.retrType)
                    put("msgFromScene", payload.msgFromScene)
                    put("fileName", payload.fileName)
                    put("length", payload.length)
                })
            }
        }.toString()
    }

    fun label(): String = when (type and 0xffff) {
        1 -> "文本"
        3 -> "图片"
        34 -> "语音"
        42 -> "名片"
        43, 62 -> "视频"
        47 -> "表情"
        48 -> "位置"
        49 -> when {
            isFile() -> "文件"
            isVideoNumber() -> "视频号"
            else -> "卡片"
        }
        else -> "消息"
    }

    fun isFile(): Boolean {
        if ((type and 0xffff) != 49) return false
        val raw = retransmit?.content.orEmpty().ifBlank { content }
        return WeChatMessage.xmlTag(raw, "type").toIntOrNull() == 6
    }

    fun isVideoNumber(): Boolean {
        if ((type and 0xffff) != 49) return false
        val raw = retransmit?.content.orEmpty().ifBlank { content }
        return WeChatMessage.isVideoNumberContent(raw)
    }

    companion object {
        fun fromNative(nativeMessage: Any): SelectedMessageSnapshot? {
            return fromNativeInternal(nativeMessage, momentsOnly = false)
        }

        fun fromNativeForMoments(nativeMessage: Any): SelectedMessageSnapshot? {
            return fromNativeInternal(nativeMessage, momentsOnly = true)
        }

        private fun fromNativeInternal(
            nativeMessage: Any,
            momentsOnly: Boolean
        ): SelectedMessageSnapshot? {
            val msgId = messageId(nativeMessage)
            if (msgId <= 0L) return null
            val stored = runCatching { WeChatApis.messageStore()?.getMessageById(msgId) }.getOrNull()
            val message = stored ?: messageFromNative(nativeMessage, msgId) ?: return null
            if (!momentsOnly && (message.isSystem() || message.isRecalled() || message.isVoip() ||
                message.isRedPacket() || message.isTransfer()
            )) return null

            if (message.isVoice()) {
                val fileName = voiceFileName(message)
                val path = WeChatApis.media()?.voices()?.resolvePath(nativeMessage, fileName).orEmpty()
                val available = File(path).isFile
                if (!momentsOnly && !available) return null
                val duration = if (available) {
                    VoiceMessageDurationResolver.resolve(
                        nativeMessage,
                        fileName,
                        message.msgId,
                        listOf(message.content, message.bodyContent()),
                        DEFAULT_VOICE_DURATION_MS
                    )
                } else {
                    0
                }
                return SelectedMessageSnapshot(
                    msgId = message.msgId,
                    type = message.type,
                    sourceTalker = message.talker,
                    content = message.content,
                    imagePath = message.imagePath,
                    createTime = message.createTime,
                    retransmit = null,
                    voicePath = path,
                    voiceDurationMillis = duration,
                    voiceFileName = fileName,
                    nativeMessage = nativeMessage
                )
            }

            val payload = WeChatRetransmitPayloadFactory.build(
                message,
                nativeMessage
            )
            if (payload == null && !momentsOnly) return null
            return SelectedMessageSnapshot(
                msgId = message.msgId,
                type = message.type,
                sourceTalker = message.talker,
                content = message.content,
                imagePath = message.imagePath,
                createTime = message.createTime,
                retransmit = payload,
                voicePath = "",
                voiceDurationMillis = 0,
                videoDurationSeconds = message.getVideoMsg()?.playLength?.coerceAtLeast(0) ?: 0,
                nativeMessage = nativeMessage
            )
        }

        fun decode(value: String): SelectedMessageSnapshot? {
            return runCatching {
                val obj = JSONObject(value)
                val retransmitObj = obj.optJSONObject("retransmit")
                val retransmit = retransmitObj?.let {
                    WeChatRetransmitPayload(
                        msgId = it.optLong("msgId", obj.optLong("msgId")),
                        sourceTalker = it.optString("sourceTalker", obj.optString("sourceTalker")),
                        content = it.optString("content", obj.optString("content")),
                        retrType = it.optInt("retrType", -1),
                        msgFromScene = it.optInt("msgFromScene", 2),
                        fileName = it.optString("fileName", obj.optString("imagePath")),
                        length = it.optInt("length", 0)
                    ).takeIf { payload -> payload.retrType >= 0 }
                }
                SelectedMessageSnapshot(
                    msgId = obj.optLong("msgId"),
                    type = obj.optInt("type"),
                    sourceTalker = obj.optString("sourceTalker"),
                    content = obj.optString("content"),
                    imagePath = obj.optString("imagePath"),
                    createTime = obj.optLong("createTime"),
                    retransmit = retransmit,
                    voicePath = obj.optString("voicePath"),
                    voiceDurationMillis = obj.optInt("voiceDurationMillis", DEFAULT_VOICE_DURATION_MS),
                    voiceFileName = obj.optString("voiceFileName").ifBlank {
                        obj.optString("imagePath").takeIf { (obj.optInt("type") and 0xffff) == 34 }.orEmpty()
                    },
                    videoDurationSeconds = obj.optInt("videoDurationSeconds", 0).coerceAtLeast(0)
                ).takeIf { it.msgId > 0L && (it.retransmit != null || File(it.voicePath).isFile) }
            }.getOrNull()
        }

        private fun messageFromNative(nativeMessage: Any, msgId: Long): WeChatMessage? {
            val content = readString(nativeMessage, "getContent", "field_content", "content")
            val type = readInt(nativeMessage, "getType", "field_type", "type")
                .takeIf { it > 0 } ?: WeChatMessage.inferType(content)
            if (type <= 0) return null
            return WeChatMessage(
                msgId,
                readLong(nativeMessage, "getMsgSvrId", "field_msgSvrId", "msgSvrId"),
                type,
                readInt(nativeMessage, "getStatus", "field_status", "status"),
                readInt(nativeMessage, "getIsSend", "field_isSend", "isSend"),
                readLong(nativeMessage, "getCreateTime", "field_createTime", "createTime"),
                readString(nativeMessage, "getTalker", "field_talker", "talker"),
                content,
                readString(nativeMessage, "getImgPath", "field_imgPath", "imgPath"),
                "",
                "",
                0,
                readString(nativeMessage, "getMsgSource", "field_msgSource", "msgSource"),
                ""
            )
        }

        private fun voiceFileName(message: WeChatMessage): String {
            message.imagePath.takeIf { it.isNotBlank() }?.let { return it }
            val body = message.bodyContent()
            val parts = body.trimEnd('\n', '\r').split(':')
            if (parts.size >= 3 && '<' !in body) {
                return (if (parts.size == 4) parts[1] else parts[0]).trim()
            }
            return WeChatMessage.xmlAttr(body, "filename")
                .ifBlank { WeChatMessage.xmlAttr(body, "voiceurl") }
                .ifBlank { WeChatMessage.xmlTag(body, "filename") }
        }

        private fun messageId(message: Any): Long {
            return readLong(message, "getMsgId", "field_msgId", "msgId")
                .takeIf { it > 0L }
                ?: readLong(message, "getMsgID", "msgID", "id")
        }

        private fun readString(source: Any, getter: String, field: String, fallback: String): String {
            return readValue(source, getter, field, fallback)?.toString().orEmpty()
        }

        private fun readInt(source: Any, getter: String, field: String, fallback: String): Int {
            return (readValue(source, getter, field, fallback) as? Number)?.toInt() ?: 0
        }

        private fun readLong(source: Any, getter: String, field: String, fallback: String): Long {
            return (readValue(source, getter, field, fallback) as? Number)?.toLong() ?: 0L
        }

        private fun readValue(source: Any, getter: String, field: String, fallback: String): Any? {
            KavaReflector.invokeMethod(source, getter)?.let { return it }
            KavaReflector.readField(source, field)?.let { return it }
            return KavaReflector.readField(source, fallback)
        }

        private const val DEFAULT_VOICE_DURATION_MS = 1000
    }
}
