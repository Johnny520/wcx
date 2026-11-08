package h.Hchat.hooks.items.messageforward

import android.media.MediaMetadataRetriever
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSnapshot
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class ChatLivePhotoMedia(
    val videoPath: String,
    val durationMillis: Int,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L
)

private data class ChatLivePhotoVideoMetadata(
    val durationMillis: Int,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

internal class ChatLivePhotoResolver(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val methods by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatLivePhotoLocator.locate(context, logger)
    }

    fun warmup() {
        methods
    }

    fun resolve(snapshot: SelectedMessageSnapshot): ChatLivePhotoMedia? {
        if ((snapshot.type and 0xffff) != 3) return null
        val located = methods ?: return null
        if (located.recordQuery.parameterTypes.getOrNull(0) != String::class.java) {
            logger(
                "聊天实况图片查询方法参数不兼容: msgId=${snapshot.msgId} " +
                    "method=${located.recordQuery.toGenericString()}",
                null
            )
            return null
        }
        val messageClass = located.mediaFactory.parameterTypes[0]
        val nativeMessage = snapshot.nativeMessage
            ?.takeIf(messageClass::isInstance)
            ?: runCatching { WeChatApis.database()?.nativeMessageById(snapshot.msgId) }
                .getOrNull()
                ?.takeIf(messageClass::isInstance)
            ?: return null
        val msgId = nativeMessageId(nativeMessage).takeIf { it > 0L } ?: snapshot.msgId
        val talker = nativeMessageTalker(nativeMessage).ifBlank { snapshot.sourceTalker }

        return runCatching {
            val storage = KavaReflector.invokeOrThrow(located.storageGetter, null)
                ?: return@runCatching null
            val record = KavaReflector.invokeOrThrow(
                located.recordQuery,
                storage,
                talker,
                msgId
            ) ?: return@runCatching null
            if (!located.mediaFactory.parameterTypes[1].isInstance(record)) return@runCatching null
            val factoryTarget = if (Modifier.isStatic(located.mediaFactory.modifiers)) {
                null
            } else {
                KavaReflector.staticInstance(located.mediaFactory.declaringClass)
                    ?: return@runCatching null
            }
            val mediaInfo = KavaReflector.invokeOrThrow(
                located.mediaFactory,
                factoryTarget,
                nativeMessage,
                record
            ) ?: return@runCatching null
            resolveVideo(mediaInfo, msgId)
        }.onFailure {
            logger("解析聊天实况图片失败: msgId=${snapshot.msgId}", it)
        }.getOrNull()
    }

    private fun nativeMessageId(message: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID", "getId")) {
            (KavaReflector.invokeMethod(message, name) as? Number)
                ?.toLong()
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID", "id")) {
            (KavaReflector.readField(message, name) as? Number)
                ?.toLong()
                ?.takeIf { it > 0L }
                ?.let { return it }
        }
        return 0L
    }

    private fun nativeMessageTalker(message: Any): String {
        for (name in arrayOf("getTalker", "talker")) {
            (KavaReflector.invokeMethod(message, name) as? String)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        for (name in arrayOf("field_talker", "talker")) {
            (KavaReflector.readField(message, name) as? String)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    private fun resolveVideo(mediaInfo: Any, msgId: Long): ChatLivePhotoMedia? {
        val candidates = LinkedHashSet<String>()
        var current: Class<*>? = mediaInfo.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).asSequence()
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.isEmpty() &&
                        method.returnType == String::class.java
                }
                .mapNotNull { method -> KavaReflector.invoke(method, mediaInfo) as? String }
                .map { it.trim() }
                .filter { it.isNotBlank() && it != msgId.toString() }
                .forEach(candidates::add)
            current = current.superclass
        }
        for (candidate in candidates) {
            val path = materializeVideo(candidate, msgId) ?: continue
            val metadata = videoMetadata(path) ?: continue
            return ChatLivePhotoMedia(
                path,
                metadata.durationMillis,
                metadata.width,
                metadata.height,
                metadata.sizeBytes
            )
        }
        return null
    }

    private fun materializeVideo(path: String, msgId: Long): String? {
        val direct = File(path)
        if (direct.isFile && direct.length() > 0L) return direct.absolutePath
        val directory = File(context.hostContext().cacheDir, "Hchat_live_photo")
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val target = File(directory, "live_${msgId}_${Integer.toHexString(path.hashCode())}.mp4")
        if (target.isFile && target.length() > 0L) return target.absolutePath
        val input = openVfsInputStream(path) ?: return null
        return runCatching {
            input.use { source ->
                FileOutputStream(target, false).use { output -> source.copyTo(output) }
            }
            target.absolutePath.takeIf { target.isFile && target.length() > 0L }
        }.onFailure {
            target.delete()
        }.getOrNull()
    }

    private fun openVfsInputStream(path: String): InputStream? {
        for (className in arrayOf("com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6")) {
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: continue
            for (methodName in arrayOf("E", "F")) {
                val method = KavaReflector.findMethod(clazz, methodName, String::class.java)
                    ?: continue
                if (!Modifier.isStatic(method.modifiers)) continue
                (KavaReflector.invoke(method, null, path) as? InputStream)?.let { return it }
            }
            val method = KavaReflector.declaredMethods(clazz).firstOrNull { candidate ->
                val types = candidate.parameterTypes
                Modifier.isStatic(candidate.modifiers) &&
                    candidate.returnType == InputStream::class.java &&
                    types.size == 1 &&
                    types[0] == String::class.java
            } ?: continue
            (KavaReflector.invoke(method, null, path) as? InputStream)?.let { return it }
        }
        return null
    }

    private fun videoMetadata(path: String): ChatLivePhotoVideoMetadata? {
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) return null
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (duration <= 0L && width <= 0 && height <= 0) return@runCatching null
            ChatLivePhotoVideoMetadata(
                durationMillis = duration.coerceAtLeast(1L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                width = width,
                height = height,
                sizeBytes = file.length()
            )
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }
}

private data class ChatLivePhotoMethods(
    val storageGetter: Method,
    val recordQuery: Method,
    val mediaFactory: Method
)

private object ChatLivePhotoLocator {
    private const val PREFS_NAME = "Hchat_chat_live_photo_method_cache"
    private const val CACHE_STORAGE_GETTER = "storage_getter_v1"
    private const val CACHE_RECORD_QUERY = "record_query_v1"
    private const val CACHE_MEDIA_FACTORY = "media_factory_v1"

    fun locate(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): ChatLivePhotoMethods? {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val loader = context.hostClassLoader()
        val getter = DexMethodCache.load(prefs, runtimeKey, loader, CACHE_STORAGE_GETTER)
        val query = DexMethodCache.load(prefs, runtimeKey, loader, CACHE_RECORD_QUERY)
        val factory = DexMethodCache.load(prefs, runtimeKey, loader, CACHE_MEDIA_FACTORY)
        if (getter != null && query != null && factory != null) {
            ChatLivePhotoMethods(getter, query, factory)
                .takeIf(::isValid)
                ?.let { return it }
        }
        DexMethodCache.clear(prefs, runtimeKey, CACHE_STORAGE_GETTER)
        DexMethodCache.clear(prefs, runtimeKey, CACHE_RECORD_QUERY)
        DexMethodCache.clear(prefs, runtimeKey, CACHE_MEDIA_FACTORY)
        return locateFresh(context, logger)?.also { save(prefs, runtimeKey, it) }
    }

    private fun locateFresh(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): ChatLivePhotoMethods? {
        return runCatching {
            val callers = context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(
                            listOf(
                                "ChatLiveMediaInfo imgInfo query failed, msgId=",
                                "create ChatLiveMediaInfo, msgId="
                            )
                        )
                    })
                }
            )
            for (callerData in callers) {
                val caller = runCatching {
                    callerData.getMethodInstance(context.hostClassLoader())
                }.getOrNull() ?: continue
                val messageClass = caller.parameterTypes.singleOrNull() ?: continue
                val invokes = callerData.invokes.mapNotNull { invokeData ->
                    runCatching {
                        invokeData.getMethodInstance(context.hostClassLoader())
                    }.getOrNull()
                }
                for (factory in invokes.filter { isFactory(it, messageClass) }) {
                    val recordClass = factory.parameterTypes[1]
                    val query = invokes.firstOrNull { isRecordQuery(it, recordClass) } ?: continue
                    val getter = invokes.firstOrNull { isStorageGetter(it, query.declaringClass) }
                        ?: continue
                    return@runCatching ChatLivePhotoMethods(getter, query, factory)
                }
            }
            null
        }.onFailure {
            logger("定位聊天实况图片方法失败", it)
        }.getOrNull()
    }

    private fun isValid(methods: ChatLivePhotoMethods): Boolean {
        val messageClass = methods.mediaFactory.parameterTypes.getOrNull(0) ?: return false
        return isFactory(methods.mediaFactory, messageClass) &&
            isRecordQuery(methods.recordQuery, methods.mediaFactory.parameterTypes[1]) &&
            isStorageGetter(methods.storageGetter, methods.recordQuery.declaringClass)
    }

    private fun isFactory(method: Method, messageClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return types.size == 2 &&
            types[0] == messageClass &&
            !types[1].isPrimitive &&
            !method.returnType.isPrimitive &&
            method.returnType != Void.TYPE
    }

    private fun isRecordQuery(method: Method, recordClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            types.size == 2 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Long.TYPE &&
            method.returnType == recordClass
    }

    private fun isStorageGetter(method: Method, storageClass: Class<*>): Boolean {
        return Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            method.returnType == storageClass
    }

    private fun save(
        prefs: android.content.SharedPreferences,
        runtimeKey: String,
        methods: ChatLivePhotoMethods
    ) {
        DexMethodCache.save(prefs, runtimeKey, CACHE_STORAGE_GETTER, methods.storageGetter)
        DexMethodCache.save(prefs, runtimeKey, CACHE_RECORD_QUERY, methods.recordQuery)
        DexMethodCache.save(prefs, runtimeKey, CACHE_MEDIA_FACTORY, methods.mediaFactory)
    }
}
