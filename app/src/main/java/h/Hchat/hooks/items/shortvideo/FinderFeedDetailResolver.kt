package h.Hchat.hooks.items.shortvideo

import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class FinderFeedDetailResolver private constructor(
    val runtimeKey: String,
    private val requestConstructor: Constructor<*>
) {
    private val cache = ConcurrentHashMap<String, CachedFeed>()

    fun resolve(objectId: String, objectNonceId: String, commentScene: Int): Any? {
        if (objectId.isBlank() || objectNonceId.isBlank()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HLog.e("$TAG 不能在主线程等待视频号详情: objectId=$objectId")
            return null
        }
        val cacheKey = "$objectId|$objectNonceId"
        cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.savedAt < CACHE_TTL_MS }?.let {
            return it.finderObject
        }
        cache.remove(cacheKey)

        val feedId = parseObjectId(objectId) ?: run {
            HLog.e("$TAG 视频号 objectId 无效: $objectId")
            return null
        }
        val scene = KavaReflector.newInstance(
            requestConstructor,
            feedId,
            objectNonceId,
            commentScene.takeIf { it > 0 } ?: DEFAULT_COMMENT_SCENE,
            System.identityHashCode(this)
        ) ?: run {
            HLog.e("$TAG 创建视频号详情请求失败: objectId=$objectId")
            return null
        }
        val request = PendingRequest(feedId)
        pending[scene] = request
        val sent = runCatching { WeChatApis.network()?.sendRequest(scene) == true }
            .onFailure { request.failure = it }
            .getOrDefault(false)
        if (!sent) {
            pending.remove(scene)
            HLog.e("$TAG 发送视频号详情请求失败: objectId=$objectId")
            return null
        }
        val finished = runCatching {
            request.completed.await(DETAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrElse {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            pending.remove(scene)
            HLog.e("$TAG 等待视频号详情超时: objectId=$objectId")
            return null
        }
        request.failure?.let {
            HLog.e("$TAG 获取视频号详情失败: objectId=$objectId ${it.message}", it)
            return null
        }
        return request.finderObject?.also {
            cache[cacheKey] = CachedFeed(it, System.currentTimeMillis())
        }
    }

    private fun parseObjectId(value: String): Long? {
        return runCatching {
            if (value.startsWith('-')) value.toLong() else java.lang.Long.parseUnsignedLong(value)
        }.getOrNull()
    }

    private data class CachedFeed(val finderObject: Any, val savedAt: Long)

    companion object {
        private const val TAG = "[Hchat:FinderDetail]"
        private const val PREFS_NAME = "Hchat_finder_feed_detail_cache"
        private const val CACHE_CONSTRUCTOR = "request_constructor_v2"
        private const val CACHE_CALLBACK = "request_callback_v2"
        private const val CGI_URI = "/cgi-bin/micromsg-bin/findergetcommentdetail"
        private const val FINDER_OBJECT_CLASS = "com.tencent.mm.protocal.protobuf.FinderObject"
        private const val FEED_OBJECT_FIELD_INDEX = 2
        private const val REF_OBJECT_LIST_FIELD_INDEX = 10
        private const val DEFAULT_COMMENT_SCENE = 20
        private const val DETAIL_TIMEOUT_SECONDS = 20L
        private const val CACHE_TTL_MS = 10 * 60_000L
        private val pending = ConcurrentHashMap<Any, PendingRequest>()
        private val hookedCallbacks = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

        fun locate(context: FeatureContext): FinderFeedDetailResolver? {
            val hostContext = context.hostContext()
            val classLoader = context.hostClassLoader()
            val runtimeKey = DexMethodCache.runtimeKey(hostContext, classLoader)
            val prefs = DexMethodCache.prefs(hostContext, PREFS_NAME)
            var constructor = DexMethodCache.loadConstructor(
                prefs,
                runtimeKey,
                classLoader,
                CACHE_CONSTRUCTOR
            )?.takeIf(::isRequestConstructor)
            var callback = constructor?.let { owner ->
                DexMethodCache.load(prefs, runtimeKey, classLoader, CACHE_CALLBACK)
                    ?.takeIf { isCallbackMethod(it, owner.declaringClass) }
                    ?: locateCallbackMethod(owner.declaringClass)
            }
            if (constructor == null || callback == null) {
                DexMethodCache.clear(prefs, runtimeKey, CACHE_CONSTRUCTOR)
                DexMethodCache.clear(prefs, runtimeKey, CACHE_CALLBACK)
                constructor = locateRequestConstructor(context) ?: return null
                callback = locateCallbackMethod(constructor.declaringClass) ?: return null
            }
            val resolvedConstructor = constructor ?: return null
            val resolvedCallback = callback ?: return null
            if (!installCallbackHook(resolvedCallback)) return null
            DexMethodCache.saveConstructor(prefs, runtimeKey, CACHE_CONSTRUCTOR, resolvedConstructor)
            DexMethodCache.save(prefs, runtimeKey, CACHE_CALLBACK, resolvedCallback)
            return FinderFeedDetailResolver(runtimeKey, resolvedConstructor)
        }

        private fun locateCallbackMethod(owner: Class<*>): Method? {
            return KavaReflector.declaredMethods(owner)
                .singleOrNull { isCallbackMethod(it, owner) }
        }

        private fun locateRequestConstructor(context: FeatureContext): Constructor<*>? {
            val candidates = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply { usingEqStrings(CGI_URI) })
                    }
                ).mapNotNull { data ->
                    if (!data.isConstructor) return@mapNotNull null
                    runCatching { data.getConstructorInstance(context.hostClassLoader()) }.getOrNull()
                }.filter(::isRequestConstructor)
                    .distinctBy { it.toGenericString() }
            }.onFailure {
                HLog.e("$TAG DexKit定位视频号详情请求失败: ${it.message}", it)
            }.getOrDefault(emptyList())
            if (candidates.size != 1) {
                HLog.e("$TAG 视频号详情请求候选数量异常: count=${candidates.size}")
            }
            return candidates.singleOrNull()
        }

        private fun isRequestConstructor(constructor: Constructor<*>): Boolean {
            return constructor.parameterTypes.contentEquals(
                arrayOf(
                    java.lang.Long.TYPE,
                    String::class.java,
                    java.lang.Integer.TYPE,
                    java.lang.Integer.TYPE
                )
            )
        }

        private fun isCallbackMethod(method: Method, owner: Class<*>): Boolean {
            val types = method.parameterTypes
            return method.declaringClass == owner &&
                method.returnType == Void.TYPE &&
                types.size == 6 &&
                types[0] == java.lang.Integer.TYPE &&
                types[1] == java.lang.Integer.TYPE &&
                types[2] == java.lang.Integer.TYPE &&
                types[3] == String::class.java &&
                !types[4].isPrimitive &&
                types[5] == ByteArray::class.java
        }

        private fun installCallbackHook(method: Method): Boolean {
            if (!hookedCallbacks.add(method)) return true
            return runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val scene = param.thisObject ?: return
                        val request = pending.remove(scene) ?: return
                        val errType = (param.args.getOrNull(1) as? Number)?.toInt() ?: -1
                        val errCode = (param.args.getOrNull(2) as? Number)?.toInt() ?: -1
                        if (errType == 0 && errCode == 0) {
                            request.finderObject = extractFinderObject(
                                scene,
                                param.args.getOrNull(4),
                                param.args.getOrNull(5) as? ByteArray,
                                request.feedId
                            )
                            if (request.finderObject == null) {
                                request.failure = IllegalStateException("Finder detail response has no FinderObject")
                            }
                        } else {
                            request.failure = IllegalStateException(
                                "Finder detail request failed: errType=$errType errCode=$errCode"
                            )
                        }
                        request.completed.countDown()
                    }
                })
                true
            }.getOrElse {
                hookedCallbacks.remove(method)
                HLog.e("$TAG Hook视频号详情响应失败: ${it.message}", it)
                false
            }
        }

        private fun extractFinderObject(
            scene: Any,
            callbackResponse: Any?,
            rawResponse: ByteArray?,
            feedId: Long
        ): Any? {
            val responseObjects = sequenceOf(
                scene,
                callbackResponse
            ).filterNotNull()
                .flatMap { root ->
                    sequenceOf(root) + hierarchyFields(root.javaClass)
                        .filter { !KavaReflector.isStatic(it) }
                        .mapNotNull { field -> KavaReflector.readField(field, root) }
                }
                .distinctBy { it.javaClass.name + "@" + System.identityHashCode(it) }
                .toList()
            responseObjects.firstNotNullOfOrNull { findFinderObject(it, feedId) }?.let { return it }

            val responseBytes = rawResponse ?: return null
            if (responseBytes.isEmpty()) return null
            responseObjects.asSequence()
                .filter { hasProtobufParser(it.javaClass) }
                .mapNotNull { response -> parseResponse(response.javaClass, responseBytes) }
                .firstNotNullOfOrNull { findFinderObject(it, feedId) }
                ?.let { return it }
            return null
        }

        private fun parseResponse(responseClass: Class<*>, rawResponse: ByteArray): Any? {
            val constructor = KavaReflector.declaredConstructors(responseClass)
                .firstOrNull { it.parameterTypes.isEmpty() }
                ?: return null
            val parsed = KavaReflector.newInstance(constructor) ?: return null
            return KavaReflector.invokeMethod(parsed, "parseFrom", rawResponse) ?: parsed
        }

        private fun hasProtobufParser(clazz: Class<*>): Boolean {
            return KavaReflector.findMethodRecursive(clazz, "parseFrom", ByteArray::class.java) != null
        }

        private fun findFinderObject(response: Any, feedId: Long): Any? {
            return findFinderObject(
                response,
                feedId,
                depth = 4,
                visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            )
        }

        private fun findFinderObject(
            response: Any,
            feedId: Long,
            depth: Int,
            visited: MutableSet<Any>
        ): Any? {
            if (depth < 0 || !visited.add(response)) return null
            if (isTargetFinderObject(response, feedId, allowUnknownId = false)) return response

            val feedObject = sequenceOf(
                KavaReflector.invokeMethod(response, "getCustom", FEED_OBJECT_FIELD_INDEX),
                KavaReflector.invokeMethod(response, "getOrDefault", FEED_OBJECT_FIELD_INDEX),
                KavaReflector.invokeMethod(response, "getFeedObject"),
                KavaReflector.readField(response, "feedObject")
            ).firstOrNull { value ->
                isTargetFinderObject(value, feedId, allowUnknownId = true)
            }
            if (feedObject != null) return feedObject

            val referenceObjects = sequenceOf(
                KavaReflector.invokeMethod(response, "getList", REF_OBJECT_LIST_FIELD_INDEX),
                KavaReflector.invokeMethod(response, "getCommonList", REF_OBJECT_LIST_FIELD_INDEX),
                KavaReflector.invokeMethod(response, "getLinkedList", REF_OBJECT_LIST_FIELD_INDEX),
                KavaReflector.invokeMethod(response, "getOrDefault", REF_OBJECT_LIST_FIELD_INDEX)
            )
            referenceObjects.forEach { value ->
                findFinderObjectInValue(value, feedId, depth - 1, visited)?.let { return it }
            }
            if (depth == 0) return null

            for (field in hierarchyFields(response.javaClass)) {
                if (KavaReflector.isStatic(field)) continue
                val value = KavaReflector.readField(field, response) ?: continue
                findFinderObjectInValue(value, feedId, depth - 1, visited)?.let { return it }
            }
            return null
        }

        private fun findFinderObjectInValue(
            value: Any?,
            feedId: Long,
            depth: Int,
            visited: MutableSet<Any>
        ): Any? {
            if (value == null) return null
            if (isFinderObject(value)) {
                return value.takeIf { isTargetFinderObject(it, feedId, allowUnknownId = false) }
            }
            if (depth < 0 || isTerminalValue(value)) return null
            when (value) {
                is Iterable<*> -> value.forEach { item ->
                    findFinderObjectInValue(item, feedId, depth, visited)?.let { return it }
                }
                is Map<*, *> -> value.values.forEach { item ->
                    findFinderObjectInValue(item, feedId, depth, visited)?.let { return it }
                }
                else -> return findFinderObject(value, feedId, depth, visited)
            }
            return null
        }

        private fun isTargetFinderObject(value: Any?, feedId: Long, allowUnknownId: Boolean): Boolean {
            if (!isFinderObject(value)) return false
            val rawId = KavaReflector.invokeMethod(value, "getId")
                ?: KavaReflector.readField(value, "id")
            val objectId = (rawId as? Number)?.toLong()
            return objectId?.let { it == feedId } ?: allowUnknownId
        }

        private fun isTerminalValue(value: Any): Boolean {
            return value is String ||
                value is Number ||
                value is Boolean ||
                value is Char ||
                value is ByteArray ||
                value.javaClass.isEnum ||
                value.javaClass.isArray
        }

        private fun isFinderObject(value: Any?): Boolean {
            val name = value?.javaClass?.name ?: return false
            return name == FINDER_OBJECT_CLASS || name.endsWith(".FinderObject")
        }

        private fun hierarchyFields(start: Class<*>?): Sequence<java.lang.reflect.Field> = sequence {
            var current = start
            while (current != null && current != Any::class.java) {
                yieldAll(KavaReflector.declaredFields(current))
                current = current.superclass
            }
        }

        private class PendingRequest(val feedId: Long) {
            val completed = CountDownLatch(1)
            @Volatile var finderObject: Any? = null
            @Volatile var failure: Throwable? = null
        }
    }
}
