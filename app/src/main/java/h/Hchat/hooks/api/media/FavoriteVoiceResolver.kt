package h.Hchat.hooks.api.media

import android.content.Context
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

internal object FavoriteVoiceResolver {
    data class VoiceSource(
        val path: String,
        val durationMillis: Int
    )

    private const val DEFAULT_VOICE_DURATION_MS = 1_000
    private const val METHOD_CACHE_PREFS = "Hchat_favorite_voice_method_cache"
    private val favoriteDataPathMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val favoriteDownloadMethodCache = ConcurrentHashMap<Class<*>, Method>()

    fun resolve(
        hostContext: Context,
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        favorite: Any,
        logger: ((String, Throwable?) -> Unit)? = null
    ): VoiceSource? {
        if (favoriteType(favorite) != 3) return null
        val data = firstFavoriteDataItem(favorite) ?: return null
        val rawPaths = linkedSetOf<String>()
        favoriteDataPaths(hostContext, classLoader, dexKitBridge, data, logger).forEach { rawPaths += it }
        scanFavoriteVoicePath(classLoader, data)?.let { rawPaths += it }
        scanFavoriteVoicePath(classLoader, favorite)?.let { rawPaths += it }
        val path = rawPaths.asSequence()
            .mapNotNull { materializePath(hostContext, classLoader, it, "Hchat_fav_voice", "silk") }
            .firstOrNull()
            ?: return null
        val duration = normalizeVoiceDurationMillis(firstNumberField(data, "y", "duration", "length")?.toLong())
            ?: DEFAULT_VOICE_DURATION_MS
        return VoiceSource(path, duration)
    }

    fun durationMillis(favorite: Any): Int? {
        if (favoriteType(favorite) != 3) return null
        val data = firstFavoriteDataItem(favorite) ?: return null
        return normalizeVoiceDurationMillis(firstNumberField(data, "y", "duration", "length")?.toLong())
    }

    fun resolvePreviewPath(
        hostContext: Context,
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        favorite: Any,
        logger: ((String, Throwable?) -> Unit)? = null
    ): String? {
        val type = favoriteType(favorite)
        if (type != 2 && type != 4) return null
        val data = firstFavoriteDataItem(favorite) ?: return null
        val fallbackExtension = if (type == 4) "mp4" else "jpg"
        return favoriteDataPaths(hostContext, classLoader, dexKitBridge, data, logger)
            .asSequence()
            .mapNotNull {
                materializePath(
                    hostContext,
                    classLoader,
                    it,
                    "Hchat_fav_preview",
                    fallbackExtension
                )
            }
            .firstOrNull()
    }

    fun requestDownload(
        hostContext: Context,
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        favorite: Any,
        logger: ((String, Throwable?) -> Unit)? = null
    ): Boolean {
        if (favoriteType(favorite) != 3) return false
        val method = favoriteDownloadMethod(
            hostContext,
            classLoader,
            dexKitBridge,
            favorite.javaClass,
            logger
        ) ?: return false
        val tasks = WeChatApis.tasks() ?: return false
        tasks.runOnMain {
            runCatching {
                KavaReflector.invokeOrThrow(method, null, favorite, true)
            }.onFailure {
                logger?.invoke("收藏语音启动下载失败", it)
            }
        }
        return true
    }

    private fun favoriteDownloadMethod(
        hostContext: Context,
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        favoriteClass: Class<*>,
        logger: ((String, Throwable?) -> Unit)?
    ): Method? {
        favoriteDownloadMethodCache[favoriteClass]?.let { method ->
            if (isFavoriteDownloadMethod(method, favoriteClass)) return method
        }
        val prefs = DexMethodCache.prefs(hostContext, METHOD_CACHE_PREFS)
        val runtimeKey = DexMethodCache.runtimeKey(hostContext, classLoader)
        val cacheName = "fav_voice_download_v1_${favoriteClass.name}"
        val cached = DexMethodCache.load(prefs, runtimeKey, classLoader, cacheName)
        if (cached != null && isFavoriteDownloadMethod(cached, favoriteClass)) {
            favoriteDownloadMethodCache[favoriteClass] = cached
            return cached
        }
        if (dexKitBridge == null) return null
        return runCatching {
            val method = dexKitBridge.findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(
                                listOf(
                                    "key_detail_data_id",
                                    "key_detail_info_id",
                                    "get fav item info error"
                                )
                            )
                        }
                    )
                }
            ).asSequence()
                .flatMap { it.invokes.asSequence() }
                .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .firstOrNull { isFavoriteDownloadMethod(it, favoriteClass) }
                ?: return@runCatching null
            favoriteDownloadMethodCache[favoriteClass] = method
            DexMethodCache.save(prefs, runtimeKey, cacheName, method)
            method
        }.getOrElse {
            logger?.invoke("收藏语音定位下载方法失败", it)
            null
        }
    }

    private fun isFavoriteDownloadMethod(method: Method, favoriteClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType == Void.TYPE &&
            types.size == 2 &&
            types[0].isAssignableFrom(favoriteClass) &&
            (types[1] == Boolean::class.javaPrimitiveType || types[1] == java.lang.Boolean::class.java)
    }

    private fun favoriteType(favorite: Any): Int {
        return (firstNumberField(favorite, "field_type", "type") ?: return 0).toInt()
    }

    private fun firstFavoriteDataItem(favorite: Any): Any? {
        val proto = KavaReflector.readField(favorite, "field_favProto") ?: return null
        var current: Class<*>? = proto.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!Collection::class.java.isAssignableFrom(field.type)) continue
                val item = (KavaReflector.readField(field, proto) as? Collection<*>)
                    ?.firstOrNull { it != null }
                if (item != null) return item
            }
            current = current.superclass
        }
        return null
    }

    private fun favoriteDataPaths(
        hostContext: Context,
        classLoader: ClassLoader,
        dexKitBridge: DexKitBridge?,
        data: Any,
        logger: ((String, Throwable?) -> Unit)? = null
    ): List<String> {
        val dataClass = data.javaClass
        val dataId = favoriteDataId(data)
        val paths = linkedSetOf<String>()
        favoriteDataPathMethodCache[dataClass]?.let { method ->
            (KavaReflector.invoke(method, null, data) as? String)
                ?.takeIf { favoriteDataPathScore(classLoader, it, dataId, method) >= 0 }
                ?.let { paths += it }
        }
        val prefs = DexMethodCache.prefs(hostContext, METHOD_CACHE_PREFS)
        val cacheName = "fav_data_path_v2_${dataClass.name}"
        val runtimeKey = DexMethodCache.runtimeKey(hostContext, classLoader)
        val cached = DexMethodCache.load(prefs, runtimeKey, classLoader, cacheName)
        if (cached != null && isFavoriteDataPathMethod(cached, dataClass)) {
            favoriteDataPathMethodCache[dataClass] = cached
            (KavaReflector.invoke(cached, null, data) as? String)
                ?.takeIf { favoriteDataPathScore(classLoader, it, dataId, cached) >= 0 }
                ?.let { paths += it }
        }
        val candidates = locateFavoriteDataPathMethods(dexKitBridge, classLoader, dataClass, logger)
        val ranked = candidates
            .mapNotNull { method ->
                val path = KavaReflector.invoke(method, null, data) as? String ?: return@mapNotNull null
                val score = favoriteDataPathScore(classLoader, path, dataId, method)
                if (score < 0) return@mapNotNull null
                Triple(method, path, score)
            }
            .sortedByDescending { it.third }
        val selected = ranked.firstOrNull()
        if (selected != null) {
            favoriteDataPathMethodCache[dataClass] = selected.first
            DexMethodCache.save(prefs, runtimeKey, cacheName, selected.first)
        }
        ranked.forEach { paths += it.second }
        return paths.toList()
    }

    private fun locateFavoriteDataPathMethods(
        dexKitBridge: DexKitBridge?,
        classLoader: ClassLoader,
        dataClass: Class<*>,
        logger: ((String, Throwable?) -> Unit)? = null
    ): List<Method> {
        if (dexKitBridge == null) return emptyList()
        return runCatching {
            dexKitBridge.findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            returnType("java.lang.String")
                            paramTypes(dataClass.name)
                        }
                    )
                }
            ).mapNotNull { result ->
                runCatching { result.getMethodInstance(classLoader) }.getOrNull()
            }.filter { isFavoriteDataPathMethod(it, dataClass) }
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger?.invoke("收藏媒体定位文件路径方法失败", it)
            emptyList()
        }
    }

    private fun isFavoriteDataPathMethod(method: Method, dataClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType == String::class.java &&
            types.size == 1 &&
            types[0].isAssignableFrom(dataClass)
    }

    private fun favoriteDataId(source: Any): String {
        for (fieldName in arrayOf("T", "Z")) {
            (KavaReflector.readField(source, fieldName) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    private fun favoriteDataPathScore(
        classLoader: ClassLoader,
        path: String,
        dataId: String,
        method: Method? = null
    ): Int {
        if (path.isBlank() || (!path.contains('/') && !path.contains("://"))) return -1
        val file = File(path)
        val lowerPath = path.lowercase(Locale.US)
        val lowerName = file.name.lowercase(Locale.US)
        var score = 0
        when (method?.name) {
            "x", "w" -> score += 80
            "X" -> score -= 20
        }
        if (dataId.isNotBlank() && file.name == dataId) score += 40
        if (dataId.isNotBlank() && file.name.startsWith(dataId)) score += 30
        if (dataId.isNotBlank() && path.contains(dataId)) score += 20
        if (!lowerName.endsWith("_t")) score += 10 else score -= 30
        if (lowerPath.contains("/favorite") || lowerPath.contains("/fav/")) score += 8
        if (lowerPath.contains("voice")) score += 6
        if (pathExists(classLoader, path)) score += 24
        return score
    }

    private fun materializePath(
        hostContext: Context,
        classLoader: ClassLoader,
        path: String,
        cacheDirectory: String,
        fallbackExtension: String
    ): String? {
        if (File(path).isFile) return path
        val input = openVfsInputStream(classLoader, path) ?: return null
        val ext = File(path).extension
            .takeIf { it.isNotBlank() && it.length <= 8 }
            ?: fallbackExtension
        val dir = File(hostContext.cacheDir, cacheDirectory)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "fav_${Integer.toHexString(path.hashCode())}.$ext")
        return runCatching {
            input.use { stream ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        }.getOrNull()
    }

    private fun pathExists(classLoader: ClassLoader, path: String): Boolean {
        if (path.isBlank()) return false
        if (File(path).isFile) return true
        return vfsPathExists(classLoader, path, "j", "k")
    }

    private fun openVfsInputStream(classLoader: ClassLoader, path: String): InputStream? {
        if (path.isBlank()) return null
        for (className in arrayOf("com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6")) {
            val clazz = KavaReflector.loadClass(className, classLoader) ?: continue
            for (methodName in arrayOf("E", "F")) {
                val method = KavaReflector.findMethod(clazz, methodName, String::class.java) ?: continue
                val stream = KavaReflector.invoke(method, null, path) as? InputStream
                if (stream != null) return stream
            }
            for (method in KavaReflector.declaredMethods(clazz)) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != InputStream::class.java) continue
                val types = method.parameterTypes
                if (types.size != 1 || types[0] != String::class.java) continue
                val stream = KavaReflector.invoke(method, null, path) as? InputStream
                if (stream != null) return stream
            }
        }
        return null
    }

    private fun vfsPathExists(classLoader: ClassLoader, path: String, vararg methodNames: String): Boolean {
        for (className in arrayOf("com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6")) {
            val clazz = KavaReflector.loadClass(className, classLoader) ?: continue
            for (method in KavaReflector.declaredMethods(clazz)) {
                if (method.name !in methodNames) continue
                if (!Modifier.isStatic(method.modifiers)) continue
                val types = method.parameterTypes
                if (types.size != 1 || types[0] != String::class.java) continue
                val value = KavaReflector.invoke(method, null, path)
                when (value) {
                    true -> return true
                    is Number -> if (value.toLong() > 0L) return true
                }
            }
        }
        return false
    }

    private fun scanFavoriteVoicePath(classLoader: ClassLoader, source: Any): String? {
        val dataId = favoriteDataId(source)
        val candidates = mutableListOf<Pair<String, Int>>()
        collectExistingPaths(
            classLoader = classLoader,
            source = source,
            dataId = dataId,
            candidates = candidates,
            visited = Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
            depth = 0
        )
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun collectExistingPaths(
        classLoader: ClassLoader,
        source: Any?,
        dataId: String,
        candidates: MutableList<Pair<String, Int>>,
        visited: MutableSet<Any>,
        depth: Int
    ) {
        if (source == null || depth > 4 || !visited.add(source)) return
        when (source) {
            is String -> {
                favoriteVoicePathScore(classLoader, source, dataId).takeIf { it >= 0 }?.let { score ->
                    candidates += source to score
                }
                return
            }
            is Array<*> -> {
                source.forEach { collectExistingPaths(classLoader, it, dataId, candidates, visited, depth + 1) }
                return
            }
            is Collection<*> -> {
                source.forEach { collectExistingPaths(classLoader, it, dataId, candidates, visited, depth + 1) }
                return
            }
        }
        val className = source.javaClass.name
        if (className.startsWith("android.") || className.startsWith("java.lang.") || className.startsWith("java.io.")) {
            return
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive) continue
                val value = KavaReflector.readField(field, source) ?: continue
                collectExistingPaths(classLoader, value, dataId, candidates, visited, depth + 1)
            }
            current = current.superclass
        }
    }

    private fun favoriteVoicePathScore(classLoader: ClassLoader, path: String, dataId: String): Int {
        if (path.isBlank()) return -1
        if (!path.contains('/') && !path.contains("://")) return -1
        val file = File(path)
        if (!pathExists(classLoader, path)) return -1
        val lowerPath = path.lowercase(Locale.US)
        val lowerName = file.name.lowercase(Locale.US)
        var score = 0
        if (dataId.isNotBlank() && file.name == dataId) score += 30
        if (dataId.isNotBlank() && file.name.startsWith(dataId)) score += 24
        if (dataId.isNotBlank() && path.contains(dataId)) score += 18
        if (!lowerName.endsWith("_t")) score += 12 else score -= 30
        if (
            lowerName.endsWith(".silk") ||
            lowerName.endsWith(".slk") ||
            lowerName.endsWith(".amr") ||
            lowerName.endsWith(".spx") ||
            lowerName.endsWith(".speex") ||
            lowerName.endsWith(".mp3")
        ) {
            score += 16
        }
        if (lowerPath.contains("/favorite") || lowerPath.contains("/fav/")) score += 4
        if (lowerPath.contains("voice")) score += 4
        if (file.isFile && file.length() > 0L) score += 2
        return score
    }

    private fun normalizeVoiceDurationMillis(raw: Long?): Int? {
        val value = raw ?: return null
        if (value <= 0L) return null
        val millis = if (value in 1L..600L) value * 1000L else value
        return millis.coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun firstNumberField(source: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(source, name)
            if (value is Number) return value
        }
        return null
    }
}
