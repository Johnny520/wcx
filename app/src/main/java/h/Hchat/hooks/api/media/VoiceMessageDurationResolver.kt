package h.Hchat.hooks.api.media

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.utils.KavaReflector
import java.util.concurrent.ConcurrentHashMap

object VoiceMessageDurationResolver {
    private const val MAX_CACHE_SIZE = 2048
    private val durationCache = ConcurrentHashMap<String, Int>()

    fun resolve(
        nativeSource: Any?,
        fileName: String,
        msgId: Long,
        contentCandidates: Iterable<String>,
        defaultDurationMillis: Int
    ): Int {
        cachedDuration(fileName, msgId)?.let { return it }
        val nativeCandidates = nativeMessageCandidates(nativeSource, msgId)
        val duration = nativeCandidates.firstNotNullOfOrNull(::readExplicitVoiceLength)
            ?: contentCandidates.firstNotNullOfOrNull { voiceDurationFromContent(it, fileName) }
            ?: nativeCandidates.asSequence()
                .flatMap { nativeContentCandidates(it).asSequence() }
                .firstNotNullOfOrNull { voiceDurationFromContent(it, fileName) }
            ?: WeChatApis.media()?.voices()?.storedDurationMillis(fileName)?.takeIf { it > 0 }
        return duration?.also { cacheDuration(fileName, msgId, it) }
            ?: defaultDurationMillis
    }

    private fun nativeMessageCandidates(source: Any?, knownMsgId: Long): List<Any> {
        source ?: return emptyList()
        val result = ArrayList<Any>()
        result += source
        val sourceMsgId = knownMsgId.takeIf { it > 0L } ?: messageId(source)
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val type = field.type
                if (type.isPrimitive || type.isArray || type == String::class.java) continue
                if (Number::class.java.isAssignableFrom(type)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                if (value === source || result.any { it === value }) continue
                val className = value.javaClass.name
                if (!className.startsWith("com.tencent.mm.storage.") && sourceMsgId <= 0L) continue
                if (sourceMsgId > 0L && messageId(value) != sourceMsgId) continue
                result += value
            }
            current = current.superclass
        }
        return result
    }

    private fun readExplicitVoiceLength(source: Any): Int? {
        for (name in arrayOf("getVoiceLength", "getVoiceLen", "getDuration")) {
            KavaReflector.findMethod(source.javaClass, name)
                ?.takeIf { it.parameterTypes.isEmpty() }
                ?.let { method ->
                    normalizeVoiceDurationMillis(parseLong(KavaReflector.invoke(method, source)))
                        ?.let { return it }
                }
        }
        for (name in arrayOf("field_voiceLength", "voiceLength", "VoiceLength", "duration", "field_duration")) {
            normalizeVoiceDurationMillis(parseLong(KavaReflector.readField(source, name)))
                ?.let { return it }
        }
        return null
    }

    private fun nativeContentCandidates(source: Any): List<String> {
        val result = LinkedHashSet<String>()
        for (name in arrayOf("getContent", "getMsgContent")) {
            val method = KavaReflector.findMethod(source.javaClass, name)
                ?.takeIf { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                ?: continue
            (KavaReflector.invoke(method, source) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        for (name in arrayOf("field_content", "content", "msgContent")) {
            (KavaReflector.readField(source, name) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        return result.toList()
    }

    private fun voiceDurationFromContent(raw: String, fileName: String): Int? {
        val body = raw.trimEnd('\n', '\r')
        if (body.isBlank() || body.indexOf('<') >= 0) return null
        val parts = body.split(':')
        if (parts.size < 3) return null
        val contentFileName = parts[0].trim()
        val duration = normalizeVoiceDurationMillis(parts.getOrNull(1)?.trim()?.toLongOrNull())
            ?: return null
        if (fileName.isBlank() || contentFileName.isBlank()) return duration
        if (contentFileName == fileName ||
            fileName.endsWith(contentFileName) ||
            contentFileName.endsWith(fileName)
        ) return duration
        return duration.takeIf { !contentFileName.contains('/') && !contentFileName.contains('\\') }
    }

    private fun messageId(source: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID", "getId")) {
            parseLong(KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, name), source))
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID", "id")) {
            parseLong(KavaReflector.readField(source, name))
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return 0L
    }

    private fun cachedDuration(fileName: String, msgId: Long): Int? {
        if (fileName.isNotBlank()) durationCache["file:$fileName"]?.let { return it }
        if (msgId > 0L) durationCache["msg:$msgId"]?.let { return it }
        return null
    }

    private fun cacheDuration(fileName: String, msgId: Long, durationMillis: Int) {
        if (durationMillis <= 0) return
        if (durationCache.size > MAX_CACHE_SIZE) durationCache.clear()
        if (fileName.isNotBlank()) durationCache["file:$fileName"] = durationMillis
        if (msgId > 0L) durationCache["msg:$msgId"] = durationMillis
    }

    private fun normalizeVoiceDurationMillis(raw: Long?): Int? {
        val value = raw ?: return null
        if (value <= 0L) return null
        val millis = if (value in 1L..600L) value * 1000L else value
        return millis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }
}
