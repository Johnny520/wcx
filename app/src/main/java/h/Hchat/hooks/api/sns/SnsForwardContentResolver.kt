package h.Hchat.hooks.api.sns

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings
import h.Hchat.utils.KavaReflector
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class SnsForwardSnapshot(
    val id: String,
    val text: String,
    val type: Int,
    val media: List<SnsForwardMedia>
) {
    val isImage: Boolean get() = SnsContentTypes.isImageMedia(type)
    val isVideo: Boolean get() = SnsContentTypes.isVideoMedia(type)
}

internal data class SnsForwardMedia(
    val id: String,
    val type: Int,
    val url: String,
    val thumbUrl: String,
    val nativeObject: Any,
    val livePhotoVideo: SnsForwardMedia? = null
)

internal data class PreparedSnsImage(
    val imagePath: String,
    val liveVideoPath: String = "",
    val liveVideoDurationMillis: Int = 0,
    val liveVideoWidth: Int = 0,
    val liveVideoHeight: Int = 0,
    val liveVideoSizeBytes: Long = 0L,
    val liveVideoCoverTimeMillis: Long = 0L
) {
    val isLivePhoto: Boolean
        get() = liveVideoPath.isNotBlank() && liveVideoDurationMillis > 0
}

internal data class PreparedSnsForward(
    val text: String,
    val imageItems: List<PreparedSnsImage> = emptyList(),
    val video: String = "",
    val videoThumb: String = ""
) {
    val images: List<String>
        get() = imageItems.map { it.imagePath }

    fun contentItems(): List<ScheduledTaskContentItem> = buildList {
        text.takeIf { it.isNotBlank() }?.let {
            add(ScheduledTaskContentItem(ScheduledTaskSettings.TYPE_TEXT, it))
        }
        images.forEach {
            add(ScheduledTaskContentItem(ScheduledTaskSettings.TYPE_IMAGE, it))
        }
        video.takeIf { it.isNotBlank() }?.let {
            add(ScheduledTaskContentItem(ScheduledTaskSettings.TYPE_VIDEO, it))
        }
    }
}

internal class SnsForwardContentResolver(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
    private val localMediaMethods by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        lateinit var methods: SnsForwardLocalMediaMethods
        DexInstallScheduler.runDexKitTask {
            methods = SnsForwardLocalMediaLocator.locate(context, logger)
        }
        methods
    }
    private val nativeMediaMethods by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        lateinit var methods: SnsForwardNativeMediaMethods
        DexInstallScheduler.runDexKitTask {
            methods = SnsForwardNativeMediaLocator.locate(context, logger)
        }
        methods
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun resolve(owner: Any?, args: Array<Any?>?): SnsForwardSnapshot? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val view = args?.firstNotNullOfOrNull { it as? View }
        val timeline = findTimeline(view, visited, 0)
            ?: findTimeline(owner, visited, 0)
            ?: findTimeline(args, visited, 0)
            ?: return null
        return snapshotFromTimeline(timeline)
    }

    fun resolveNativeSnsInfo(owner: Any?, args: Array<Any?>?): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        val view = args?.firstNotNullOfOrNull { it as? View }
        return findObjectByClass(view, SNS_INFO_CLASS, visited, 0)
            ?: findObjectByClass(owner, SNS_INFO_CLASS, visited, 0)
            ?: findObjectByClass(args, SNS_INFO_CLASS, visited, 0)
    }

    fun snapshotFromSnsInfo(nativeInfo: Any?): SnsForwardSnapshot? {
        if (nativeInfo == null) return null
        return runCatching {
            val timeline = KavaReflector.invokeMethod(nativeInfo, "getTimeLine") ?: return@runCatching null
            snapshotFromTimeline(timeline)
        }.onFailure {
            logger("解析朋友圈原生内容失败", it)
        }.getOrNull()
    }

    fun prepare(snapshot: SnsForwardSnapshot, canceled: AtomicBoolean): PreparedSnsForward {
        if (canceled.get()) throw InterruptedException("已取消")
        return when {
            snapshot.isImage -> {
                val selected = snapshot.media.take(MAX_IMAGES)
                val images = selected.mapIndexed { index, media ->
                    resolveLocalImage(media, index, canceled)
                }.toMutableList()
                val requested = selected.mapIndexed { index, media ->
                    images[index] == null && triggerImageDownload(media)
                }
                val deadline = SystemClock.elapsedRealtime() + NATIVE_IMAGE_TIMEOUT_MS
                selected.forEachIndexed { index, media ->
                    if (images[index] == null && requested[index]) {
                        images[index] = waitForLocalImage(media, index, canceled, deadline)
                    }
                    if (images[index] == null) {
                        images[index] = downloadMedia(
                            urls = listOf(media.url),
                            cacheKey = "${snapshot.id}_${media.id}_$index",
                            extension = "jpg",
                            maxBytes = MAX_IMAGE_BYTES,
                            canceled = canceled
                        )?.let { path ->
                            path.takeIf(::isUsableImage) ?: run {
                                File(path).delete()
                                null
                            }
                        }
                    }
                }
                val resolved = images.mapIndexed { index, path ->
                    path ?: throw IllegalStateException("第${index + 1}张图片下载失败")
                }
                if (resolved.isEmpty()) throw IllegalStateException("未找到朋友圈图片")
                val imageItems = selected.mapIndexed { index, media ->
                    val imagePath = resolved[index]
                    val liveMedia = media.livePhotoVideo
                        ?: return@mapIndexed PreparedSnsImage(imagePath)
                    val liveVideo = resolveVideoFile(
                        snsId = snapshot.id,
                        media = liveMedia,
                        cacheKey = "${snapshot.id}_${media.id}_${liveMedia.id}_live",
                        canceled = canceled
                    )
                    if (liveVideo == null) {
                        logger("第${index + 1}张实况视频下载失败，保留静态封面", null)
                        return@mapIndexed PreparedSnsImage(imagePath)
                    }
                    val duration = videoDurationMillis(liveVideo)
                        .takeIf { it > 0L }
                        ?.coerceAtMost(Int.MAX_VALUE.toLong())
                        ?.toInt()
                    if (duration == null) {
                        logger("第${index + 1}张实况视频无效，保留静态封面", null)
                        return@mapIndexed PreparedSnsImage(imagePath)
                    }
                    PreparedSnsImage(imagePath, liveVideo, duration)
                }
                PreparedSnsForward(snapshot.text, imageItems = imageItems)
            }
            snapshot.isVideo -> {
                val media = snapshot.media.firstOrNull()
                    ?: throw IllegalStateException("未找到朋友圈视频")
                val video = resolveVideoFile(
                    snsId = snapshot.id,
                    media = media,
                    cacheKey = "${snapshot.id}_${media.id}_video",
                    canceled = canceled
                ) ?: throw IllegalStateException("朋友圈视频下载失败")
                val videoThumb = resolveLocalVideoThumb(media, canceled)
                    ?: generateVideoThumb(video, "${snapshot.id}_${media.id}", canceled)
                    ?: throw IllegalStateException("朋友圈视频封面生成失败")
                PreparedSnsForward(snapshot.text, video = video, videoThumb = videoThumb)
            }
            else -> PreparedSnsForward(snapshot.text)
        }
    }

    private fun snapshotFromTimeline(timeline: Any): SnsForwardSnapshot? {
        val content = (KavaReflector.readField(timeline, "ContentDesc") as? String).orEmpty()
        val contentObj = KavaReflector.readField(timeline, "ContentObj")
        val type = contentObj?.let(::protobufType) ?: 0
        val media = contentObj?.let(::firstMediaCollection).orEmpty().mapNotNull {
            mediaFromNative(it, includeLivePhoto = type == SnsContentTypes.LIVE_PHOTO)
        }
        val id = (KavaReflector.readField(timeline, "Id") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: "sns_${Integer.toHexString(System.identityHashCode(timeline))}"
        val fallbackText = if (content.isBlank() && !typeUsesMedia(type)) {
            contentObj?.let(::contentObjectText).orEmpty()
        } else {
            content
        }
        val text = if (!typeUsesMedia(type)) {
            val link = media.asSequence().map { it.url }.firstOrNull { it.isNotBlank() }.orEmpty()
            listOf(fallbackText, link).filter { it.isNotBlank() }.distinct().joinToString("\n")
        } else {
            fallbackText
        }
        if (text.isBlank() && media.isEmpty()) return null
        return SnsForwardSnapshot(id, text, type, media)
    }

    private fun contentObjectText(contentObj: Any): String {
        return directStringValues(contentObj)
            .filterNot(::isHttpUrl)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    private fun firstMediaCollection(contentObj: Any): List<Any> {
        return instanceFields(contentObj).asSequence()
            .filter { Collection::class.java.isAssignableFrom(it.type) }
            .mapNotNull { field ->
                val values = (KavaReflector.readField(field, contentObj) as? Collection<*>)
                    ?.filterNotNull()
                    .orEmpty()
                values.takeIf { list -> list.isNotEmpty() && list.all(::looksLikeMediaObject) }
            }
            .firstOrNull()
            .orEmpty()
    }

    private fun looksLikeMediaObject(value: Any): Boolean {
        val fields = instanceFields(value)
        return fields.any { it.type == String::class.java } &&
            fields.any { it.type == Integer.TYPE || it.type == Integer::class.java }
    }

    private fun mediaFromNative(media: Any, includeLivePhoto: Boolean): SnsForwardMedia? {
        val strings = directStringValues(media)
        val urls = strings.filter(::isHttpUrl).map(::decodeUrl).distinct()
        val id = (KavaReflector.readField(media, "d") as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: strings.firstOrNull { it.isNotBlank() && !isHttpUrl(it) }
            ?: Integer.toHexString(System.identityHashCode(media))
        val type = protobufType(media)
        val standardThumb = httpField(media, "o")
        val version58Thumb = httpField(media, "q")
        val url = when {
            version58Thumb.isNotBlank() -> httpField(media, "n")
            standardThumb.isNotBlank() -> httpField(media, "i")
            else -> httpField(media, "i").ifBlank { httpField(media, "n") }
        }.ifBlank { urls.firstOrNull().orEmpty() }
        val thumbUrl = version58Thumb.ifBlank { standardThumb }
        val livePhotoVideo = if (includeLivePhoto) {
            livePhotoVideoObject(media)?.let { mediaFromNative(it, includeLivePhoto = false) }
        } else {
            null
        }
        return SnsForwardMedia(id, type, url, thumbUrl, media, livePhotoVideo)
    }

    private fun livePhotoVideoObject(media: Any): Any? {
        return instanceFields(media).asSequence()
            .filter { field -> field.type == media.javaClass }
            .mapNotNull { field -> KavaReflector.readField(field, media) }
            .firstOrNull { value -> value !== media && looksLikeMediaObject(value) }
    }

    private fun resolveLocalImage(
        media: SnsForwardMedia,
        index: Int,
        canceled: AtomicBoolean
    ): String? {
        val methods = localMediaMethods
        val rootMethod = methods.snsRoot ?: return null
        val directoryMethod = methods.mediaDirectory ?: return null
        val bigNameMethod = methods.bigImageName ?: return null
        if (!accepts(bigNameMethod, media.nativeObject)) return null
        return runCatching {
            val root = KavaReflector.invoke(rootMethod, null) as? String ?: return@runCatching null
            val directory = KavaReflector.invoke(directoryMethod, null, root, media.id) as? String
                ?: return@runCatching null
            val fileName = KavaReflector.invoke(bigNameMethod, null, media.nativeObject) as? String
                ?: return@runCatching null
            val sourcePath = directory + fileName
            val localPath = materializeLocalMedia(
                path = sourcePath,
                cacheKey = "${media.id}_$index",
                extension = "jpg",
                maxBytes = MAX_IMAGE_BYTES,
                canceled = canceled
            )
            if (localPath != null && isUsableImage(localPath)) {
                localPath
            } else {
                if (localPath != null && localPath != sourcePath) File(localPath).delete()
                null
            }
        }.onFailure {
            if (it !is InterruptedException) logger("读取朋友圈本地原图失败: ${media.id}", it)
        }.getOrNull()
    }

    private fun resolveLocalVideo(
        snsId: String,
        media: SnsForwardMedia,
        canceled: AtomicBoolean
    ): String? {
        return runCatching {
            val native = nativeMediaMethods
            val paths = ArrayList<String>(3)
            for (method in listOf(native.videoFinishedPath, native.videoFullPath)) {
                if (method == null || !accepts(method, 1, media.nativeObject)) continue
                (KavaReflector.invokeOrThrow(method, null, snsId, media.nativeObject) as? String)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(paths::add)
            }
            if (native.videoFinishedPath == null && native.videoFullPath == null) {
                val method = localMediaMethods.videoPath
                if (method != null && accepts(method, media.nativeObject)) {
                    (KavaReflector.invokeOrThrow(method, null, media.nativeObject) as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?.let(paths::add)
                }
            }
            for (path in paths.distinct()) {
                val localPath = materializeLocalMedia(
                    path = path,
                    cacheKey = "${media.id}_video",
                    extension = "mp4",
                    maxBytes = MAX_VIDEO_BYTES,
                    canceled = canceled
                ) ?: continue
                if (isUsableVideo(localPath)) return@runCatching localPath
                if (localPath != path) File(localPath).delete()
            }
            null
        }.onFailure {
            if (it !is InterruptedException) logger("读取朋友圈本地视频失败: ${media.id}", it)
        }.getOrNull()
    }

    private fun resolveVideoFile(
        snsId: String,
        media: SnsForwardMedia,
        cacheKey: String,
        canceled: AtomicBoolean
    ): String? {
        var video = resolveLocalVideo(snsId, media, canceled)
        if (video == null && triggerVideoDownload(snsId, media)) {
            video = waitForLocalVideo(snsId, media, canceled)
        }
        if (video != null) return video
        return downloadMedia(
            urls = listOf(media.url),
            cacheKey = cacheKey,
            extension = "mp4",
            maxBytes = MAX_VIDEO_BYTES,
            canceled = canceled
        )?.let { path ->
            path.takeIf(::isUsableVideo) ?: run {
                File(path).delete()
                null
            }
        }
    }

    private fun resolveLocalVideoThumb(
        media: SnsForwardMedia,
        canceled: AtomicBoolean
    ): String? {
        val method = nativeMediaMethods.videoThumbPath ?: return null
        if (!accepts(method, media.nativeObject)) return null
        return runCatching {
            val sourcePath = KavaReflector.invokeOrThrow(method, null, media.nativeObject) as? String
                ?: return@runCatching null
            val localPath = materializeLocalMedia(
                path = sourcePath,
                cacheKey = "${media.id}_video_thumb",
                extension = "jpg",
                maxBytes = MAX_IMAGE_BYTES,
                canceled = canceled
            )
            if (localPath != null && isUsableImage(localPath)) {
                localPath
            } else {
                if (localPath != null && localPath != sourcePath) File(localPath).delete()
                null
            }
        }.onFailure {
            if (it !is InterruptedException) logger("读取朋友圈视频封面失败: ${media.id}", it)
        }.getOrNull()
    }

    private fun triggerImageDownload(media: SnsForwardMedia): Boolean {
        val methods = nativeMediaMethods
        val getter = methods.imageManager ?: return false
        val download = methods.imageDownload ?: return false
        if (!download.parameterTypes[0].isInstance(media.nativeObject)) return false
        val scene = findTimelineScene(download.parameterTypes[3]) ?: return false
        return invokeOnMain {
            runCatching {
                val manager = KavaReflector.invokeOrThrow(getter, null) ?: return@runCatching false
                if (!download.declaringClass.isInstance(manager)) return@runCatching false
                KavaReflector.invokeOrThrow(
                    download,
                    manager,
                    media.nativeObject,
                    ORIGINAL_IMAGE_DOWNLOAD_TYPE,
                    null,
                    scene
                )
                true
            }.onFailure {
                logger("调用微信朋友圈原图下载失败: ${media.id}", it)
            }.getOrDefault(false)
        }
    }

    private fun triggerVideoDownload(snsId: String, media: SnsForwardMedia): Boolean {
        val methods = nativeMediaMethods
        val getter = methods.videoService ?: return false
        val download = methods.videoDownload ?: return false
        if (!download.parameterTypes[0].isInstance(media.nativeObject)) return false
        return invokeOnMain {
            runCatching {
                val service = KavaReflector.invokeOrThrow(getter, null) ?: return@runCatching false
                if (!download.declaringClass.isInstance(service)) return@runCatching false
                KavaReflector.invokeOrThrow(
                    download,
                    service,
                    media.nativeObject,
                    SNS_VIDEO_DOWNLOAD_TYPE,
                    snsId.ifBlank { media.id },
                    false,
                    true,
                    SNS_VIDEO_SOURCE,
                    media.id
                ) as? Boolean == true
            }.onFailure {
                logger("调用微信朋友圈视频下载失败: ${media.id}", it)
            }.getOrDefault(false)
        }
    }

    private fun waitForLocalImage(
        media: SnsForwardMedia,
        index: Int,
        canceled: AtomicBoolean,
        deadline: Long
    ): String? {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (canceled.get()) throw InterruptedException("已取消")
            resolveLocalImage(media, index, canceled)?.let { return it }
            SystemClock.sleep(NATIVE_POLL_INTERVAL_MS)
        }
        return resolveLocalImage(media, index, canceled)
    }

    private fun waitForLocalVideo(
        snsId: String,
        media: SnsForwardMedia,
        canceled: AtomicBoolean
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + NATIVE_VIDEO_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (canceled.get()) throw InterruptedException("已取消")
            resolveLocalVideo(snsId, media, canceled)?.let { return it }
            SystemClock.sleep(NATIVE_POLL_INTERVAL_MS)
        }
        return resolveLocalVideo(snsId, media, canceled)
    }

    private fun findTimelineScene(sceneClass: Class<*>): Any? {
        val fields = KavaReflector.declaredFields(sceneClass)
        val nameField = fields.firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && field.type == String::class.java
        } ?: return null
        return fields.asSequence()
            .filter { field ->
                Modifier.isStatic(field.modifiers) && sceneClass.isAssignableFrom(field.type)
            }
            .mapNotNull { field -> KavaReflector.readField(field, null as Any?) }
            .firstOrNull { scene -> KavaReflector.readField(nameField, scene) == "timeline" }
    }

    private fun invokeOnMain(action: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        if (!mainHandler.post {
                try {
                    result.set(action())
                } finally {
                    latch.countDown()
                }
            }
        ) return false
        return latch.await(NATIVE_INVOKE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && result.get()
    }

    private fun accepts(method: java.lang.reflect.Method, value: Any): Boolean {
        return method.parameterTypes.singleOrNull()?.isInstance(value) == true
    }

    private fun accepts(method: java.lang.reflect.Method, index: Int, value: Any): Boolean {
        return method.parameterTypes.getOrNull(index)?.isInstance(value) == true
    }

    private fun materializeLocalMedia(
        path: String,
        cacheKey: String,
        extension: String,
        maxBytes: Long,
        canceled: AtomicBoolean
    ): String? {
        if (path.isBlank()) return null
        val file = File(path)
        if (file.isFile && file.length() in 1..maxBytes) return file.absolutePath
        val dir = File(context.hostContext().cacheDir, "Hchat_sns_forward")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "local_${Integer.toHexString(cacheKey.hashCode())}.$extension")
        if (target.isFile && target.length() in 1..maxBytes) return target.absolutePath
        val input = openVfsInputStream(path) ?: return null
        return runCatching {
            input.use { source ->
                FileOutputStream(target, false).use { output ->
                    copyLimited(source, output, maxBytes, canceled)
                }
            }
            target.takeIf { it.isFile && it.length() in 1..maxBytes }?.absolutePath
        }.onFailure {
            target.delete()
            if (it !is InterruptedException) logger("读取朋友圈VFS缓存失败: $path", it)
        }.getOrNull()
    }

    private fun openVfsInputStream(path: String): InputStream? {
        for (className in arrayOf("com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6")) {
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: continue
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

    private fun copyLimited(
        input: InputStream,
        output: FileOutputStream,
        maxBytes: Long,
        canceled: AtomicBoolean
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            if (canceled.get()) throw InterruptedException("已取消")
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) throw IllegalStateException("媒体文件过大")
            output.write(buffer, 0, read)
        }
    }

    private fun isUsableImage(path: String): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private fun generateVideoThumb(
        videoPath: String,
        cacheKey: String,
        canceled: AtomicBoolean
    ): String? {
        if (canceled.get()) throw InterruptedException("已取消")
        val dir = File(context.hostContext().cacheDir, "Hchat_sns_forward")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "video_thumb_${Integer.toHexString(cacheKey.hashCode())}.jpg")
        if (target.isFile && isUsableImage(target.absolutePath)) return target.absolutePath
        val retriever = MediaMetadataRetriever()
        var bitmap: Bitmap? = null
        return runCatching {
            retriever.setDataSource(videoPath)
            val frame = retriever.frameAtTime ?: return@runCatching null
            bitmap = frame
            FileOutputStream(target, false).use { output ->
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    return@runCatching null
                }
            }
            target.absolutePath.takeIf(::isUsableImage)
        }.onFailure {
            target.delete()
            if (it !is InterruptedException) logger("生成朋友圈视频封面失败: $videoPath", it)
        }.getOrNull().also {
            bitmap?.recycle()
            runCatching { retriever.release() }
            if (it == null) target.delete()
        }
    }

    private fun isUsableVideo(path: String): Boolean {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            duration > 0L && width > 0 && height > 0
        }.getOrDefault(false).also {
            runCatching { retriever.release() }
        }
    }

    private fun videoDurationMillis(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
        }.getOrDefault(0L).also {
            runCatching { retriever.release() }
        }
    }

    private fun protobufType(source: Any): Int {
        return (KavaReflector.readField(source, "e") as? Number)?.toInt() ?: 0
    }

    private fun httpField(source: Any, name: String): String {
        val value = (KavaReflector.readField(source, name) as? String).orEmpty()
        return decodeUrl(value).takeIf(::isHttpUrl).orEmpty()
    }

    private fun directStringValues(source: Any): List<String> {
        return instanceFields(source).mapNotNull { field ->
            if (field.type != String::class.java) null
            else (KavaReflector.readField(field, source) as? String)?.trim()
        }
    }

    private fun instanceFields(source: Any): List<java.lang.reflect.Field> {
        val result = ArrayList<java.lang.reflect.Field>()
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current)
                .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
                .forEach(result::add)
            current = current.superclass
        }
        return result
    }

    private fun findTimeline(
        source: Any?,
        visited: MutableSet<Any>,
        depth: Int
    ): Any? {
        if (source == null || depth > MAX_RESOLVE_DEPTH || !visited.add(source)) return null
        if (source.javaClass.name == TIMELINE_OBJECT_CLASS) return source
        when (source) {
            is Array<*> -> {
                source.forEach { findTimeline(it, visited, depth + 1)?.let { value -> return value } }
                return null
            }
            is Collection<*> -> {
                source.forEach { findTimeline(it, visited, depth + 1)?.let { value -> return value } }
                return null
            }
            is View -> return findTimeline(source.tag, visited, depth + 1)
        }

        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType.name == TIMELINE_OBJECT_CLASS
            }?.let { method ->
                KavaReflector.invoke(method, source)?.let { return it }
            }
            current = current.superclass
        }

        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        current = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val value = KavaReflector.readField(field, source) ?: continue
                findTimeline(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun findObjectByClass(
        source: Any?,
        className: String,
        visited: MutableSet<Any>,
        depth: Int
    ): Any? {
        if (source == null || depth > MAX_RESOLVE_DEPTH || !visited.add(source)) return null
        if (source.javaClass.name == className) return source
        when (source) {
            is Array<*> -> {
                source.forEach {
                    findObjectByClass(it, className, visited, depth + 1)?.let { value -> return value }
                }
                return null
            }
            is Collection<*> -> {
                source.forEach {
                    findObjectByClass(it, className, visited, depth + 1)?.let { value -> return value }
                }
                return null
            }
            is View -> return findObjectByClass(source.tag, className, visited, depth + 1)
        }
        val sourceName = source.javaClass.name
        if (sourceName.startsWith("java.") || sourceName.startsWith("android.")) return null
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val value = KavaReflector.readField(field, source) ?: continue
                findObjectByClass(value, className, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun downloadMedia(
        urls: List<String>,
        cacheKey: String,
        extension: String,
        maxBytes: Long,
        canceled: AtomicBoolean
    ): String? {
        val dir = File(context.hostContext().cacheDir, "Hchat_sns_forward")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val safeKey = Integer.toHexString(cacheKey.hashCode())
        val target = File(dir, "remote_v2_$safeKey.$extension")
        if (target.isFile && target.length() in 1..maxBytes) return target.absolutePath
        for (rawUrl in urls) {
            if (canceled.get()) throw InterruptedException("已取消")
            val url = decodeUrl(rawUrl)
            if (!isHttpUrl(url)) continue
            val part = File(dir, "$safeKey.part")
            val ok = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "MicroMessenger Client")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logger("朋友圈媒体下载响应异常: code=${response.code} url=$url", null)
                        return@use false
                    }
                    val body = response.body ?: run {
                        logger("朋友圈媒体下载响应为空: url=$url", null)
                        return@use false
                    }
                    val declared = body.contentLength()
                    if (declared > maxBytes) return@use false
                    body.byteStream().use { input ->
                        FileOutputStream(part, false).use { output ->
                            copyLimited(input, output, maxBytes, canceled)
                        }
                    }
                    if (!part.isFile || part.length() <= 0L) return@use false
                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    target.isFile && target.length() > 0L
                }
            }.onFailure {
                part.delete()
                if (it !is InterruptedException) {
                    logger("朋友圈媒体下载失败: $url", it)
                }
            }.getOrDefault(false)
            if (ok) return target.absolutePath
        }
        return null
    }

    private fun decodeUrl(value: String): String {
        return value.trim()
            .replace("&amp;", "&")
            .replace("&#38;", "&")
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
    }

    private fun typeUsesMedia(type: Int): Boolean {
        return SnsContentTypes.usesMedia(type)
    }

    companion object {
        private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"
        private const val TIMELINE_OBJECT_CLASS = "com.tencent.mm.protocal.protobuf.TimeLineObject"
        private const val MAX_RESOLVE_DEPTH = 5
        private const val MAX_IMAGES = 9
        private const val MAX_IMAGE_BYTES = 40L * 1024L * 1024L
        private const val MAX_VIDEO_BYTES = 512L * 1024L * 1024L
        private const val ORIGINAL_IMAGE_DOWNLOAD_TYPE = 2
        private const val SNS_VIDEO_DOWNLOAD_TYPE = 1
        private const val SNS_VIDEO_SOURCE = 31
        private const val NATIVE_IMAGE_TIMEOUT_MS = 60_000L
        private const val NATIVE_VIDEO_TIMEOUT_MS = 90_000L
        private const val NATIVE_POLL_INTERVAL_MS = 500L
        private const val NATIVE_INVOKE_TIMEOUT_MS = 5_000L
    }
}
