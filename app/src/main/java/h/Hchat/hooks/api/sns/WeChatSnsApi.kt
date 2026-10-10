package h.Hchat.hooks.api.sns

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.api.contact.WeChatAccountApi
import h.Hchat.hooks.api.contact.WeChatContactApi
import h.Hchat.hooks.api.model.WeChatSnsLivePhoto
import h.Hchat.hooks.api.model.WeChatSnsMedia
import h.Hchat.hooks.api.model.WeChatSnsPost
import h.Hchat.hooks.api.model.WeChatSnsPrepareResult
import h.Hchat.hooks.api.net.WeChatNetworkApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_SNS_IMAGES = 9

/**
 * 微信朋友圈发布、互动和时间线 API。
 *
 * 发布复用 UploadPackHelper，点赞和评论复用微信原生 SnsServer 包装方法。
 */
class WeChatSnsApi(
    private val hostContext: Context?,
    private val dexFinder: DexFinder?,
    private val classLoader: ClassLoader?,
    private val dexKitBridge: DexKitBridge?,
    private val networkApi: WeChatNetworkApi?,
    private val accountApi: WeChatAccountApi?,
    private val contactApi: WeChatContactApi?,
    private val featureContext: FeatureContext?,
    private val logger: Logger?
) {
    fun interface Logger {
        fun log(message: String)
    }

    val isAvailable: Boolean
        get() = hostContext != null && dexFinder?.hasSnsUploadApi() == true

    private val interactionLocator: SnsInteractionLocator? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context = hostContext ?: return@lazy null
        val loader = classLoader ?: return@lazy null
        SnsInteractionLocator(context, loader, dexKitBridge, ::log)
    }
    private val postObserver: WeChatSnsPostObserver? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context = hostContext ?: return@lazy null
        val loader = classLoader ?: return@lazy null
        WeChatSnsPostObserver(context, loader, dexKitBridge, ::log)
    }
    private val cachedPostStorage: SnsCachedPostStorage? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context = hostContext ?: return@lazy null
        val loader = classLoader ?: return@lazy null
        val bridge = dexKitBridge ?: return@lazy null
        SnsCachedPostStorage(context, loader, bridge, ::log)
    }
    private val contentResolver: SnsForwardContentResolver? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val context = featureContext ?: return@lazy null
        SnsForwardContentResolver(context) { message, throwable ->
            log(if (throwable == null) message else "$message: ${throwable.message}")
        }
    }
    @Volatile
    private var livePhotoUploadRuntime: SnsLivePhotoUploadRuntime? = null
    private val mediaPrepareLocks = ConcurrentHashMap<String, MediaPrepareLock>()

    fun installPostObserver(): Boolean = postObserver?.install() == true

    fun warmupCachedPosts(): Boolean = cachedPostStorage?.warmup() == true

    internal fun warmupCachedPostReadWrite(): Boolean {
        return cachedPostStorage?.warmupReadWrite() == true
    }

    internal fun warmupCachedPostLocalWrite(): Boolean = cachedPostStorage?.warmupLocalWrite() == true

    internal fun insertCachedNativeSnsInfo(nativeInfo: Any?): Boolean = cachedPostStorage?.insert(nativeInfo) == true

    internal fun deleteCachedNativeSnsInfo(snsId: Long): Boolean = cachedPostStorage?.delete(snsId) == true

    fun warmupInteraction(): Boolean {
        val locator = interactionLocator ?: return false
        val likeReady = locator.nativeLikeMethod() != null
        val commentReady = locator.nativeCommentMethod() != null
        val refreshReady = locator.timelineRefreshConstructor() != null
        return likeReady && commentReady && refreshReady
    }

    fun observePosts(listener: WeChatSnsPostObserver.Listener): WeChatSnsPostObserver.Subscription? {
        return postObserver?.subscribe(listener)
    }

    fun getSnsPostList(limit: Int): List<WeChatSnsPost> {
        return cachedPostStorage?.query(null, limit, false)
            .orEmpty()
            .mapNotNull(::postFromRecord)
            .sortedByDescending { it.getCreateTimeSeconds() }
            .take(limit.coerceAtLeast(0))
    }

    fun getSnsPostList(userName: String?, limit: Int): List<WeChatSnsPost> {
        val normalizedUser = userName.orEmpty().trim()
        if (normalizedUser.isEmpty() || limit <= 0) return emptyList()
        return cachedPostStorage?.query(
            normalizedUser,
            limit,
            normalizedUser == accountApi?.selfWxId()
        )
            .orEmpty()
            .mapNotNull(::postFromRecord)
            .sortedByDescending { it.getCreateTimeSeconds() }
            .take(limit)
    }

    fun getSnsPost(snsId: String?): WeChatSnsPost? {
        val normalizedId = snsId.orEmpty().trim()
        if (normalizedId.isEmpty()) return null
        return cachedPostStorage?.queryBySnsId(normalizedId)?.let(::postFromRecord)
    }

    internal fun cachedNativeSnsInfo(snsId: String?): Any? {
        return cachedNativeSnsInfoLookup(snsId).nativeInfo
    }

    internal fun cachedNativeSnsInfoLookup(snsId: String?): SnsCachedNativeLookup {
        val normalizedId = snsId.orEmpty().trim()
        if (normalizedId.isEmpty()) return SnsCachedNativeLookup(false, null)
        return cachedPostStorage?.lookupNativeBySnsId(normalizedId)
            ?: SnsCachedNativeLookup(false, null)
    }

    internal fun localInteractionNodeClass(): Class<*>? {
        return interactionLocator?.interactionNodeClass()
    }

    internal fun localCommentGuardMethods(): List<Method> {
        return interactionLocator?.nativeCommentGuardMethods().orEmpty()
    }

    internal fun updateCachedNativeSnsInfo(
        nativeInfo: Any?,
        notifyObservers: Boolean = true
    ): Boolean {
        val update = { cachedPostStorage?.update(nativeInfo) == true }
        return if (notifyObservers) update() else postObserver?.withoutDispatch(update) ?: update()
    }

    fun prepareSnsPostMedia(snsId: String?): WeChatSnsPrepareResult {
        return prepareSnsPostMedia(snsId, AtomicBoolean(false))
    }

    fun prepareSnsPostMedia(
        snsId: String?,
        canceled: AtomicBoolean
    ): WeChatSnsPrepareResult {
        val normalizedId = snsId.orEmpty().trim()
        if (normalizedId.isEmpty()) return WeChatSnsPrepareResult.failure("", "朋友圈 ID 为空")
        val lock = mediaPrepareLocks.compute(normalizedId) { _, existing ->
            (existing ?: MediaPrepareLock()).also { it.references++ }
        } ?: return WeChatSnsPrepareResult.failure(normalizedId, "朋友圈媒体准备锁创建失败")
        return try {
            synchronized(lock.monitor) {
                if (canceled.get()) {
                    WeChatSnsPrepareResult.failure(normalizedId, "已取消准备朋友圈媒体")
                } else {
                    prepareSnsPostMediaLocked(normalizedId, canceled)
                }
            }
        } finally {
            mediaPrepareLocks.computeIfPresent(normalizedId) { _, current ->
                if (current !== lock) {
                    current
                } else {
                    current.references--
                    current.takeIf { it.references > 0 }
                }
            }
        }
    }

    private fun prepareSnsPostMediaLocked(
        normalizedId: String,
        canceled: AtomicBoolean
    ): WeChatSnsPrepareResult {
        val record = cachedPostStorage?.queryBySnsId(normalizedId)
            ?: return WeChatSnsPrepareResult.failure(normalizedId, "未找到本机缓存的朋友圈")
        val nativeInfo = record.nativeInfo
        if (KavaReflector.invokeMethod(nativeInfo, "isAd") == true) {
            return WeChatSnsPrepareResult.failure(normalizedId, "不支持准备广告朋友圈")
        }
        val resolver = contentResolver
            ?: return WeChatSnsPrepareResult.failure(normalizedId, "朋友圈媒体解析器未就绪")
        val snapshot = resolver.snapshotFromSnsInfo(nativeInfo)
            ?: return WeChatSnsPrepareResult.failure(normalizedId, "朋友圈内容解析失败")
        val storageType = record.values.getAsInteger("type")
            ?: record.values.getAsInteger("field_type")
            ?: -1
        val type = classifyPostType(snapshot, storageType)
        if (type == WeChatSnsPost.TYPE_CARD || type == WeChatSnsPost.TYPE_UNKNOWN) {
            return WeChatSnsPrepareResult.failure(normalizedId, "暂不支持转发该朋友圈类型")
        }
        return runCatching {
            val prepared = resolver.prepare(snapshot, canceled)
            val preparedImages = prepared.imageItems.map {
                if (it.isLivePhoto) {
                    WeChatSnsLivePhoto(
                        imagePath = it.imagePath,
                        videoPath = it.liveVideoPath,
                        videoDurationMillis = it.liveVideoDurationMillis,
                        coverTimeMillis = it.liveVideoCoverTimeMillis
                    )
                } else {
                    null
                }
            }
            val livePhotos = preparedImages.filterNotNull()
            when {
                type == WeChatSnsPost.TYPE_IMAGE && prepared.images.isEmpty() ->
                    error("未找到可用的朋友圈图片")
                type == WeChatSnsPost.TYPE_VIDEO && prepared.video.isBlank() ->
                    error("未找到可用的朋友圈视频")
                type == WeChatSnsPost.TYPE_LIVE_PHOTO && prepared.imageItems.isEmpty() ->
                    error("未找到可用的实况照片")
            }
            WeChatSnsPrepareResult(
                success = true,
                message = "准备完成",
                snsId = normalizedId,
                content = snapshot.text,
                type = type,
                imagePathList = prepared.images,
                videoPath = prepared.video,
                videoThumbPath = prepared.videoThumb,
                livePhotoList = livePhotos,
                preparedImageList = preparedImages
            )
        }.onFailure {
            log("准备朋友圈媒体失败: snsId=$normalizedId ${it.message}")
        }.getOrElse {
            WeChatSnsPrepareResult.failure(normalizedId, it.message ?: "准备朋友圈媒体失败")
        }
    }

    fun publishSnsPost(result: WeChatSnsPrepareResult?): Boolean {
        if (result?.isSuccess() != true) return false
        return when (result.getType()) {
            WeChatSnsPost.TYPE_TEXT -> uploadText(result.getContent())
            WeChatSnsPost.TYPE_IMAGE -> uploadTextAndPicList(
                result.getContent(),
                result.getImagePathList()
            )
            WeChatSnsPost.TYPE_VIDEO -> uploadTextAndVideo(
                result.getContent(),
                result.getVideoPath()
            )
            WeChatSnsPost.TYPE_LIVE_PHOTO -> {
                val imagePaths = result.getImagePathList()
                if (imagePaths.isEmpty()) return false
                val preparedImages = result.getPreparedImageList()
                if (preparedImages.size != imagePaths.size) return false
                publishLivePhotos(
                    content = result.getContent(),
                    items = imagePaths.mapIndexed { index, imagePath ->
                        preparedImages[index]?.let {
                            LivePhotoUploadItem(
                                imagePath = imagePath,
                                videoPath = it.getVideoPath(),
                                coverTimeMs = it.getCoverTimeMillis()
                            )
                        } ?: LivePhotoUploadItem(imagePath, "", 0L)
                    },
                    sdkId = "",
                    sdkAppName = "",
                    fallbackToStatic = true
                )
            }
            else -> false
        }
    }

    private fun postFromRecord(record: SnsCachedPostRecord): WeChatSnsPost? {
        val nativeInfo = record.nativeInfo
        val values = record.values
        if (KavaReflector.invokeMethod(nativeInfo, "isAd") == true) return null
        val snapshot = contentResolver?.snapshotFromSnsInfo(nativeInfo) ?: return null
        val rawId = values.get("snsId") ?: values.get("field_snsId") ?: return null
        val signedId = when (rawId) {
            is Number -> rawId.toLong()
            else -> rawId.toString().toLongOrNull() ?: return null
        }
        if (signedId == 0L) return null
        val userName = values.getAsString("userName")
            ?: values.getAsString("field_userName")
            ?: return null
        val localId = values.getAsLong(SnsCachedPostStorage.LOCAL_ID_ALIAS)
            ?: (KavaReflector.invokeMethod(nativeInfo, "getLocalid") as? Number)?.toLong()
            ?: (KavaReflector.readField(nativeInfo, "localid") as? Number)?.toLong()
            ?: 0L
        val createTime = values.getAsLong("createTime")
            ?: values.getAsLong("field_createTime")
            ?: 0L
        val storageType = values.getAsInteger("type")
            ?: values.getAsInteger("field_type")
            ?: -1
        val selfWxId = accountApi?.selfWxId().orEmpty()
        return WeChatSnsPost(
            snsId = java.lang.Long.toUnsignedString(signedId),
            localId = localId,
            userName = userName,
            displayName = contactApi?.getDisplayName(userName).orEmpty().ifBlank { userName },
            createTimeSeconds = createTime,
            storageType = storageType,
            contentType = snapshot.type,
            type = classifyPostType(snapshot, storageType),
            content = snapshot.text,
            mediaList = snapshot.media.map(::mediaBean),
            self = selfWxId.isNotBlank() && selfWxId == userName
        )
    }

    private fun mediaBean(media: SnsForwardMedia): WeChatSnsMedia {
        return WeChatSnsMedia(
            id = media.id,
            type = media.type,
            url = media.url,
            thumbUrl = media.thumbUrl,
            liveVideo = media.livePhotoVideo?.let(::mediaBean)
        )
    }

    private fun classifyPostType(snapshot: SnsForwardSnapshot, storageType: Int): String {
        return when (SnsContentTypes.classify(snapshot.type)) {
            SnsContentKind.LIVE_PHOTO -> WeChatSnsPost.TYPE_LIVE_PHOTO
            SnsContentKind.IMAGE -> WeChatSnsPost.TYPE_IMAGE
            SnsContentKind.VIDEO,
            SnsContentKind.VIDEO_LINK -> WeChatSnsPost.TYPE_VIDEO
            SnsContentKind.TEXT -> WeChatSnsPost.TYPE_TEXT
            SnsContentKind.LINK,
            SnsContentKind.MUSIC,
            SnsContentKind.OTHER -> WeChatSnsPost.TYPE_CARD
            SnsContentKind.UNKNOWN -> if (storageType == SnsContentTypes.TEXT && snapshot.media.isEmpty()) {
                WeChatSnsPost.TYPE_TEXT
            } else if (snapshot.media.isEmpty() && snapshot.text.isNotBlank()) {
                WeChatSnsPost.TYPE_CARD
            } else {
                WeChatSnsPost.TYPE_UNKNOWN
            }
        }
    }

    private class MediaPrepareLock {
        val monitor = Any()
        var references = 0
    }

    fun contentValuesFrom(snsInfo: Any?): ContentValues? {
        if (snsInfo == null || snsInfo.javaClass.name != SnsInteractionLocator.SNS_INFO_CLASS) return null
        return runCatching {
            (KavaReflector.invokeMethod(snsInfo, "convertTo") as? ContentValues)
                ?.let(::ContentValues)
        }.onFailure {
            log("朋友圈记录导出失败: ${it.message}")
        }.getOrNull()
    }

    fun snsInfoFrom(values: ContentValues?): Any? {
        val loader = classLoader ?: return null
        val source = values ?: return null
        return runCatching {
            val clazz = KavaReflector.loadClass(SnsInteractionLocator.SNS_INFO_CLASS, loader)
                ?: return@runCatching null
            val info = KavaReflector.newInstance(KavaReflector.findConstructor(clazz))
                ?: return@runCatching null
            val copy = ContentValues(source)
            val guardedConvert = KavaReflector.findMethodRecursive(
                clazz,
                "convertFrom",
                ContentValues::class.java,
                java.lang.Boolean.TYPE
            )
            if (guardedConvert != null) {
                KavaReflector.invokeOrThrow(guardedConvert, info, copy, false)
            } else {
                val convert = KavaReflector.findMethodRecursive(clazz, "convertFrom", ContentValues::class.java)
                    ?: return@runCatching null
                KavaReflector.invokeOrThrow(convert, info, copy)
            }
            info
        }.onFailure {
            log("朋友圈记录解析失败: ${it.message}")
        }.getOrNull()
    }

    fun like(snsInfo: Any?, sourceScene: Int = 0): Boolean {
        if (snsInfo == null || snsInfo.javaClass.name != SnsInteractionLocator.SNS_INFO_CLASS) return false
        val method = interactionLocator?.nativeLikeMethod() ?: run {
            log("朋友圈点赞失败: 原生方法未就绪")
            return false
        }
        return runCatching {
            val result = KavaReflector.invokeOrThrow(method, null, snsInfo, 1, null, sourceScene)
            (result as? Boolean) ?: true
        }.onFailure {
            log("朋友圈点赞异常: ${it.message}")
        }.getOrDefault(false)
    }

    fun comment(snsInfo: Any?, content: String?, sourceScene: Int = 0): Boolean {
        if (snsInfo == null || snsInfo.javaClass.name != SnsInteractionLocator.SNS_INFO_CLASS) return false
        val text = content.orEmpty().trim()
        if (text.isEmpty()) return false
        return runCatching {
            val extFlag = KavaReflector.invokeMethod(snsInfo, "isExtFlag") as? Boolean
            if (extFlag != false) {
                val method = interactionLocator?.nativeCommentMethod()
                    ?: error("原生评论方法未就绪")
                KavaReflector.invokeOrThrow(method, null, snsInfo, 2, text, 0L, "", false, sourceScene)
            } else {
                val method = interactionLocator?.nativeLikeMethod()
                    ?: error("陌生人评论方法未就绪")
                val payload = buildCommentPayload(method, text)
                    ?: error("评论正文参数创建失败")
                KavaReflector.invokeOrThrow(method, null, snsInfo, 2, payload, sourceScene)
            }
            true
        }.onFailure {
            log("朋友圈评论异常: ${it.message}")
        }.getOrDefault(false)
    }

    private fun buildCommentPayload(method: Method, content: String): Any? {
        val type = method.parameterTypes.getOrNull(2) ?: return null
        if (type == String::class.java) return content
        val payload = KavaReflector.newInstance(KavaReflector.findConstructor(type)) ?: return null
        val methods = KavaReflector.declaredMethods(type)
        val textSetter = methods.singleOrNull { candidate ->
            !Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(String::class.java))
        } ?: return null
        KavaReflector.invokeOrThrow(textSetter, payload, content)
        methods.singleOrNull { candidate ->
            !Modifier.isStatic(candidate.modifiers) &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.contentEquals(arrayOf(Integer.TYPE))
        }?.let { KavaReflector.invokeOrThrow(it, payload, 0) }
        return payload
    }

    fun refreshTimeline(): Boolean {
        val constructor = interactionLocator?.timelineRefreshConstructor() ?: run {
            log("朋友圈刷新失败: 原生请求未就绪")
            return false
        }
        return runCatching {
            val request = KavaReflector.newInstance(constructor, 0L, 0L, 0)
                ?: return@runCatching false
            networkApi?.sendRequest(request) == true
        }.onFailure {
            log("朋友圈刷新异常: ${it.message}")
        }.getOrDefault(false)
    }

    fun uploadText(content: String?): Boolean = uploadText(content, "", "")

    fun uploadText(content: String?, sdkId: String?, sdkAppName: String?): Boolean =
        publish(content, emptyList(), null, sdkId, sdkAppName)

    fun uploadText(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) {
            return uploadText("")
        }
        return uploadText(
            jsonObj.optString("content", ""),
            jsonObj.optString("sdkId", ""),
            jsonObj.optString("sdkAppName", "")
        )
    }

    fun uploadTextAndPicList(content: String?, picPath: String?): Boolean =
        uploadTextAndPicList(content, picPath, "", "")

    fun uploadTextAndPicList(content: String?, picPath: String?, sdkId: String?, sdkAppName: String?): Boolean {
        val list = if (picPath.isNullOrBlank()) emptyList() else listOf(picPath)
        return publish(content, list, null, sdkId, sdkAppName)
    }

    fun uploadTextAndPicList(content: String?, picPathList: List<*>?): Boolean =
        uploadTextAndPicList(content, picPathList, "", "")

    fun uploadTextAndPicList(content: String?, picPathList: List<*>?, sdkId: String?, sdkAppName: String?): Boolean {
        val list = picPathList.orEmpty()
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotBlank() }
        return publish(content, list, null, sdkId, sdkAppName)
    }

    fun uploadTextAndPicList(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) {
            return uploadTextAndPicList("", emptyList<String>())
        }
        val list = ArrayList<String>()
        val array = jsonObj.optJSONArray("picPathList")
        if (array != null) {
            for (i in 0 until array.length()) {
                val path = array.optString(i, "").trim()
                if (path.isNotBlank()) list.add(path)
            }
        }
        val singlePath = jsonObj.optString("picPath", "").trim()
        if (singlePath.isNotBlank()) list.add(singlePath)
        return uploadTextAndPicList(
            jsonObj.optString("content", ""),
            list,
            jsonObj.optString("sdkId", ""),
            jsonObj.optString("sdkAppName", "")
        )
    }

    fun uploadLivePhoto(livePhotoPath: String?): Boolean =
        uploadTextAndLivePhoto("", livePhotoPath)

    fun uploadLivePhoto(imagePath: String?, videoPath: String?): Boolean =
        uploadTextAndLivePhoto("", imagePath, videoPath)

    fun uploadLivePhoto(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) return uploadLivePhoto("", "")
        return uploadTextAndLivePhoto(jsonObj)
    }

    fun uploadLivePhotoList(livePhotoList: List<*>?): Boolean =
        uploadTextAndLivePhotoList("", livePhotoList)

    fun uploadLivePhotoList(jsonObj: JSONObject?): Boolean =
        uploadTextAndLivePhotoList(jsonObj)

    fun uploadTextAndLivePhoto(
        content: String?,
        livePhotoPath: String?
    ): Boolean = uploadTextAndLivePhoto(content, livePhotoPath, "", "", "", 0L)

    fun uploadTextAndLivePhoto(
        content: String?,
        livePhotoPath: String?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean = uploadTextAndLivePhoto(content, livePhotoPath, "", sdkId, sdkAppName, 0L)

    fun uploadTextAndLivePhoto(
        content: String?,
        imagePath: String?,
        videoPath: String?
    ): Boolean = uploadTextAndLivePhoto(content, imagePath, videoPath, "", "", 0L)

    fun uploadTextAndLivePhoto(
        content: String?,
        imagePath: String?,
        videoPath: String?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean = uploadTextAndLivePhoto(
        content,
        imagePath,
        videoPath,
        sdkId,
        sdkAppName,
        0L
    )

    private fun uploadTextAndLivePhoto(
        content: String?,
        imagePath: String?,
        videoPath: String?,
        sdkId: String?,
        sdkAppName: String?,
        coverTimeMs: Long
    ): Boolean {
        val normalizedImagePath = imagePath.orEmpty().trim()
        val normalizedVideoPath = videoPath.orEmpty().trim()
        val resolved = resolveLivePhotoFiles(normalizedImagePath, normalizedVideoPath)
            ?: return false
        return publishLivePhotos(
            content = content.orEmpty(),
            items = listOf(
                LivePhotoUploadItem(
                    imagePath = resolved.imagePath,
                    videoPath = resolved.videoPath,
                    coverTimeMs = coverTimeMs.coerceAtLeast(0L)
                )
            ),
            sdkId = sdkId,
            sdkAppName = sdkAppName,
            fallbackToStatic = false
        )
    }

    fun uploadTextAndLivePhoto(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) return uploadTextAndLivePhoto("", "", "")
        val livePhotoPath = jsonObj.optString(
            "livePhotoPath",
            jsonObj.optString("path", "")
        )
        return uploadTextAndLivePhoto(
            content = jsonObj.optString("content", ""),
            imagePath = jsonObj.optString(
                "imagePath",
                jsonObj.optString("picPath", livePhotoPath)
            ),
            videoPath = jsonObj.optString(
                "videoPath",
                jsonObj.optString("liveVideoPath", "")
            ),
            sdkId = jsonObj.optString("sdkId", ""),
            sdkAppName = jsonObj.optString("sdkAppName", ""),
            coverTimeMs = jsonObj.optLong(
                "coverTimeMs",
                jsonObj.optLong("coverTime", 0L)
            ).coerceAtLeast(0L)
        )
    }

    fun uploadTextAndLivePhotoList(
        content: String?,
        livePhotoList: List<*>?
    ): Boolean = uploadTextAndLivePhotoList(content, livePhotoList, "", "")

    fun uploadTextAndLivePhotoList(
        content: String?,
        livePhotoList: List<*>?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean {
        val items = resolveLivePhotoItems(livePhotoList.orEmpty()) ?: return false
        return publishLivePhotos(
            content = content.orEmpty(),
            items = items,
            sdkId = sdkId,
            sdkAppName = sdkAppName,
            fallbackToStatic = true
        )
    }

    fun uploadTextAndLivePhotoList(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) return false
        val array = jsonObj.optJSONArray("livePhotoList")
            ?: jsonObj.optJSONArray("livePhotoPathList")
            ?: return false
        val values = ArrayList<Any?>(array.length())
        for (index in 0 until array.length()) values.add(array.opt(index))
        return uploadTextAndLivePhotoList(
            content = jsonObj.optString("content", ""),
            livePhotoList = values,
            sdkId = jsonObj.optString("sdkId", ""),
            sdkAppName = jsonObj.optString("sdkAppName", "")
        )
    }

    private fun resolveLivePhotoItems(values: List<*>): List<LivePhotoUploadItem>? {
        if (values.isEmpty()) {
            log("朋友圈多实况发布失败: 实况列表为空")
            return null
        }
        if (values.size > MAX_SNS_IMAGES) {
            log("朋友圈多实况发布失败: 最多支持 $MAX_SNS_IMAGES 张")
            return null
        }
        val items = ArrayList<LivePhotoUploadItem>(values.size)
        values.forEachIndexed { index, value ->
            val item = when (value) {
                is WeChatSnsLivePhoto -> LivePhotoUploadItem(
                    imagePath = value.getImagePath(),
                    videoPath = value.getVideoPath(),
                    coverTimeMs = value.getCoverTimeMillis().coerceAtLeast(0L)
                )
                is JSONObject -> livePhotoItemFromJson(value)
                else -> value?.takeUnless { it === JSONObject.NULL }
                    ?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
                        resolveLivePhotoFiles(path, "")?.let {
                            LivePhotoUploadItem(it.imagePath, it.videoPath, 0L)
                        } ?: path.takeIf { File(it).isFile }?.let {
                            LivePhotoUploadItem(it, "", 0L)
                        }
                }
            }
            if (item == null) {
                log("朋友圈多实况发布失败: 第${index + 1}项无法解析")
                return null
            }
            items.add(item)
        }
        return items
    }

    private fun livePhotoItemFromJson(value: JSONObject): LivePhotoUploadItem? {
        val livePhotoPath = value.optString(
            "livePhotoPath",
            value.optString("path", "")
        )
        val imagePath = value.optString(
            "imagePath",
            value.optString("picPath", livePhotoPath)
        ).trim()
        val videoPath = value.optString(
            "videoPath",
            value.optString("liveVideoPath", "")
        ).trim()
        val hasExplicitVideoPath = value.has("videoPath") || value.has("liveVideoPath")
        val resolved = if (videoPath.isBlank() && livePhotoPath.isBlank() && hasExplicitVideoPath) {
            imagePath.takeIf { File(it).isFile }?.let { ResolvedMotionPhoto(it, "") }
        } else {
            resolveLivePhotoFiles(imagePath, videoPath)
        }
            ?: imagePath.takeIf { File(it).isFile }?.let { ResolvedMotionPhoto(it, "") }
            ?: return null
        return LivePhotoUploadItem(
            imagePath = resolved.imagePath,
            videoPath = resolved.videoPath,
            coverTimeMs = value.optLong(
                "coverTimeMs",
                value.optLong("coverTime", 0L)
            ).coerceAtLeast(0L)
        )
    }

    private fun resolveLivePhotoFiles(
        imagePath: String,
        videoPath: String
    ): ResolvedMotionPhoto? {
        if (videoPath.isNotBlank()) return ResolvedMotionPhoto(imagePath, videoPath)
        val context = hostContext ?: run {
            log("朋友圈实况发布失败: api未就绪")
            return null
        }
        val source = File(imagePath)
        if (!source.isFile) {
            log("朋友圈实况发布失败: 实况图片不存在 $imagePath")
            return null
        }
        return EmbeddedMotionPhotoResolver.resolve(source, context.cacheDir) ?: run {
            log("朋友圈实况发布失败: 图片内未找到有效实况视频 $imagePath")
            null
        }
    }

    fun uploadVideo(videoPath: String?): Boolean = uploadTextAndVideo("", videoPath)

    fun uploadVideo(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) {
            return uploadVideo("")
        }
        return uploadTextAndVideo(jsonObj)
    }

    fun uploadTextAndVideo(content: String?, videoPath: String?): Boolean =
        uploadTextAndVideo(content, videoPath, "", "")

    fun uploadTextAndVideo(content: String?, videoPath: String?, sdkId: String?, sdkAppName: String?): Boolean {
        val video = videoPath?.trim().orEmpty()
        return publish(content, emptyList(), video, sdkId, sdkAppName)
    }

    fun uploadTextAndVideo(jsonObj: JSONObject?): Boolean {
        if (jsonObj == null) {
            return uploadTextAndVideo("", "")
        }
        val videoPath = jsonObj.optString("videoPath", jsonObj.optString("path", ""))
        return uploadTextAndVideo(
            jsonObj.optString("content", ""),
            videoPath,
            jsonObj.optString("sdkId", ""),
            jsonObj.optString("sdkAppName", "")
        )
    }

    private fun publish(
        content: String?,
        picPaths: List<String>,
        videoPath: String?,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean {
        val context = hostContext
        if (context == null || dexFinder == null) {
            log("朋友圈发布失败: api未就绪")
            return false
        }
        val validVideo = videoPath?.trim().orEmpty().takeIf { it.isNotBlank() }
        if (validVideo != null && !File(validVideo).isFile) {
            log("朋友圈视频不存在: $validVideo")
            return false
        }
        val validPics = picPaths.filter { path ->
            val ok = path.isNotBlank() && File(path).isFile
            if (!ok && path.isNotBlank()) log("朋友圈图片不存在: $path")
            ok
        }
        if (validVideo != null && validPics.isNotEmpty()) {
            log("朋友圈发布失败: 视频和图片不能同时上传")
            return false
        }
        if (validVideo == null && validPics.isEmpty()) {
            return publishTextWithNativeAppMsg(content.orEmpty(), sdkId, sdkAppName)
        }
        val helperClass = dexFinder.snsUploadPackHelperClass
        if (helperClass == null) {
            log("朋友圈发布失败: helper未就绪")
            return false
        }
        val helper = KavaReflector.newInstanceByArgs(helperClass, arrayOf(1, context))
        if (helper == null) {
            log("朋友圈发布失败: 创建UploadPackHelper失败")
            return false
        }
        try {
            invokeChain(helper, dexFinder.snsSetContentMethod, content.orEmpty())
            if (!sdkId.isNullOrBlank()) invokeChain(helper, dexFinder.snsSetSdkIdMethod, sdkId)
            if (!sdkAppName.isNullOrBlank()) invokeChain(helper, dexFinder.snsSetSdkAppNameMethod, sdkAppName)
            for (path in validPics) {
                val ok = KavaReflector.invoke(dexFinder.snsAddImageMethod, helper, path, "") as? Boolean
                if (ok != true) {
                    log("朋友圈图片添加失败: $path")
                    return false
                }
            }
            if (validVideo != null) {
                val addVideo = dexFinder.snsAddVideoMethod
                if (addVideo == null) {
                    log("朋友圈视频发布失败: 视频方法未就绪")
                    return false
                }
                val thumbPath = buildVideoThumb(validVideo)
                if (thumbPath.isNullOrBlank()) {
                    log("朋友圈视频发布失败: 缩略图生成失败")
                    return false
                }
                val ok = KavaReflector.invoke(
                    addVideo,
                    helper,
                    validVideo,
                    thumbPath,
                    content.orEmpty(),
                    fileMd5(validVideo)
                ) as? Boolean
                if (ok != true) {
                    log("朋友圈视频添加失败: $validVideo")
                    return false
                }
            }
            val localId = KavaReflector.invoke(dexFinder.snsCommitMethod, helper) as? Number
            if ((localId?.toInt() ?: 0) <= 0) {
                log("朋友圈发布失败: commit返回=${localId ?: "null"}")
                return false
            }
            val triggered = triggerUpload()
            log("朋友圈发布提交完成: localId=$localId triggerUpload=$triggered")
            return true
        } catch (e: Throwable) {
            log("朋友圈发布异常: ${e.message}")
            return false
        }
    }

    private fun publishLivePhotos(
        content: String,
        items: List<LivePhotoUploadItem>,
        sdkId: String?,
        sdkAppName: String?,
        fallbackToStatic: Boolean
    ): Boolean {
        val context = hostContext
        val finder = dexFinder
        if (context == null || finder == null) {
            log("朋友圈实况发布失败: api未就绪")
            return false
        }
        if (items.isEmpty() || items.size > MAX_SNS_IMAGES) {
            log("朋友圈实况发布失败: 图片数量=${items.size}")
            return false
        }
        val preparedItems = ArrayList<PreparedLivePhotoUploadItem>(items.size)
        items.forEachIndexed { index, item ->
            val image = File(item.imagePath)
            if (!image.isFile) {
                log("朋友圈实况发布失败: 第${index + 1}张封面不存在 ${item.imagePath}")
                return false
            }
            val metadata = item.videoPath.takeIf { it.isNotBlank() }?.let { videoPath ->
                val video = File(videoPath)
                if (!video.isFile) null else livePhotoVideoMetadata(videoPath)
            }
            if (metadata == null && item.videoPath.isNotBlank()) {
                if (!fallbackToStatic) {
                    log("朋友圈实况发布失败: 第${index + 1}张实况视频不存在或无效")
                    return false
                }
                log("朋友圈第${index + 1}张实况视频不可用，按静态封面发布")
            }
            preparedItems.add(PreparedLivePhotoUploadItem(item, metadata))
        }
        val candidateLiveCount = preparedItems.count { it.metadata != null }
        if (candidateLiveCount == 0) {
            if (!fallbackToStatic) return false
            log("朋友圈实况视频均不可用，按 ${preparedItems.size} 张静态封面发布")
            return publish(content, preparedItems.map { it.item.imagePath }, null, sdkId, sdkAppName)
        }
        val runtime = livePhotoUploadRuntime() ?: run {
            if (!fallbackToStatic) {
                log("朋友圈实况发布失败: 当前微信没有实况上传入口")
                return false
            }
            log("当前微信没有实况上传入口，按 ${preparedItems.size} 张静态封面发布")
            return publish(content, preparedItems.map { it.item.imagePath }, null, sdkId, sdkAppName)
        }
        val helperClass = finder.snsUploadPackHelperClass ?: return false
        log(
            "朋友圈实况发布入队: helper=${helperClass.name} " +
                "count=${preparedItems.size} liveCount=$candidateLiveCount " +
                "element=${runtime.elementConstructor.declaringClass.name}"
        )
        val helper = KavaReflector.newInstanceByArgs(helperClass, arrayOf(54, context))
            ?: run {
                log("朋友圈实况发布失败: 创建UploadPackHelper失败")
                return false
            }
        return try {
            invokeChain(helper, finder.snsSetContentMethod, content)
            if (!sdkId.isNullOrBlank()) invokeChain(helper, finder.snsSetSdkIdMethod, sdkId)
            if (!sdkAppName.isNullOrBlank()) {
                invokeChain(helper, finder.snsSetSdkAppNameMethod, sdkAppName)
            }
            val uploadList = ArrayList<Any>(preparedItems.size)
            var attachedLiveCount = 0
            preparedItems.forEachIndexed { index, prepared ->
                val item = prepared.item
                val imageElement = KavaReflector.newInstance(
                    runtime.elementConstructor,
                    item.imagePath,
                    2
                ) ?: return false
                if (prepared.metadata != null) {
                    val videoElement = KavaReflector.newInstance(
                        runtime.elementConstructor,
                        item.videoPath,
                        6
                    )
                    val elementReady = videoElement != null &&
                        KavaReflector.writeField(runtime.thumbPathField, videoElement, item.imagePath) &&
                            KavaReflector.writeField(runtime.liveTypeField, videoElement, 54) &&
                            KavaReflector.writeField(runtime.coverTimeField, videoElement, item.coverTimeMs) &&
                            KavaReflector.writeField(runtime.liveElementField, imageElement, videoElement)
                    if (!elementReady) {
                        if (!fallbackToStatic) {
                            log("朋友圈实况发布失败: 第${index + 1}张实况媒体字段写入失败")
                            return false
                        }
                        log("朋友圈第${index + 1}张实况媒体构造失败，按静态封面发布")
                    } else {
                        attachedLiveCount++
                    }
                }
                uploadList.add(imageElement)
            }
            if (attachedLiveCount == 0) {
                if (!fallbackToStatic) return false
                return publish(content, preparedItems.map { it.item.imagePath }, null, sdkId, sdkAppName)
            }
            KavaReflector.invokeOrThrow(runtime.setUploadListMethod, helper, uploadList)
            log(
                "朋友圈实况发布媒体入库完成: count=${uploadList.size} " +
                    "liveCount=$attachedLiveCount setUploadList=${runtime.setUploadListMethod}"
            )
            val localId = KavaReflector.invoke(finder.snsCommitMethod, helper) as? Number
            if ((localId?.toInt() ?: 0) <= 0) {
                log("朋友圈实况发布失败: commit返回=${localId ?: "null"}")
                return false
            }
            val triggered = triggerUpload()
            log("朋友圈实况发布提交完成: localId=$localId triggerUpload=$triggered")
            true
        } catch (e: Throwable) {
            log("朋友圈实况发布异常: ${e.message}")
            false
        }
    }

    private fun livePhotoUploadRuntime(): SnsLivePhotoUploadRuntime? {
        livePhotoUploadRuntime?.let { return it }
        val context = hostContext ?: return null
        val finder = dexFinder ?: return null
        val bridge = dexKitBridge ?: return null
        runCatching { finder.resolveSnsUploadApi() }
        val located = SnsLivePhotoUploadLocator.locate(
            context = context,
            classLoader = classLoader ?: return null,
            dexKitBridge = bridge,
            helperClass = finder.snsUploadPackHelperClass,
            logger = ::log
        ) ?: return null
        livePhotoUploadRuntime = located
        return located
    }

    private data class LivePhotoVideoMetadata(
        val durationMs: Long
    )

    private data class LivePhotoUploadItem(
        val imagePath: String,
        val videoPath: String,
        val coverTimeMs: Long
    )

    private data class PreparedLivePhotoUploadItem(
        val item: LivePhotoUploadItem,
        val metadata: LivePhotoVideoMetadata?
    )

    private fun livePhotoVideoMetadata(path: String): LivePhotoVideoMetadata? {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (duration <= 0L) return@runCatching null
            LivePhotoVideoMetadata(durationMs = duration)
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }

    private fun publishTextWithNativeAppMsg(
        content: String,
        sdkId: String?,
        sdkAppName: String?
    ): Boolean {
        val dexFinder = dexFinder ?: return false
        val manager = KavaReflector.invoke(dexFinder.snsUploadManagerGetterMethod, null) ?: run {
            log("朋友圈纯文字发布失败: manager为空")
            return false
        }
        val shareMethod = dexFinder.snsShareAppMsgMethod
        if (shareMethod == null) {
            log("朋友圈纯文字发布失败: shareAppMsg方法为空")
            return false
        }
        val mediaMessage = newTextMediaMessage(shareMethod, content) ?: run {
            log("朋友圈纯文字发布失败: WXTextObject创建失败")
            return false
        }
        return try {
            val helper = KavaReflector.invoke(
                shareMethod,
                manager,
                mediaMessage,
                content,
                sdkId.orEmpty(),
                sdkAppName.orEmpty()
            ) ?: run {
                log("朋友圈纯文字发布失败: native helper为空")
                return false
            }
            val localId = KavaReflector.invoke(dexFinder.snsCommitMethod, helper) as? Number
            if ((localId?.toInt() ?: 0) <= 0) {
                log("朋友圈纯文字发布失败: commit返回=${localId ?: "null"}")
                return false
            }
            triggerUpload()
        } catch (e: Throwable) {
            log("朋友圈纯文字发布异常: ${e.message}")
            false
        }
    }

    private fun newTextMediaMessage(shareMethod: Method, content: String): Any? {
        return runCatching {
            val messageClass = shareMethod.parameterTypes.firstOrNull() ?: return null
            val loader = messageClass.classLoader
            val textObjectClass = KavaReflector.loadClass(
                "com.tencent.mm.opensdk.modelmsg.WXTextObject",
                loader
            ) ?: return null
            val textObject = KavaReflector.newInstance(
                KavaReflector.findConstructor(textObjectClass)
            ) ?: KavaReflector.newInstanceByArgs(textObjectClass, emptyArray())
            if (textObject == null) return null
            writeField(textObject, "text", content)

            val message = KavaReflector.newInstance(
                KavaReflector.findConstructor(messageClass, textObjectClass),
                textObject
            ) ?: KavaReflector.newInstance(
                KavaReflector.findConstructor(messageClass)
            ) ?: KavaReflector.newInstanceByArgs(messageClass, emptyArray())
            if (message == null) return null
            writeField(message, "mediaObject", textObject)
            writeField(message, "description", content)
            message
        }.getOrNull()
    }

    private fun buildVideoThumb(videoPath: String): String? {
        val context = hostContext ?: return null
        val thumbDir = File(context.cacheDir, "Hchat_sns_thumb")
        if (!thumbDir.exists() && !thumbDir.mkdirs()) return null
        val thumb = File(thumbDir, md5(videoPath) + ".jpg")
        if (thumb.isFile && thumb.length() > 0L) return thumb.absolutePath
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val bitmap = retriever.frameAtTime ?: return null
            FileOutputStream(thumb).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
            }
            bitmap.recycle()
            if (thumb.isFile && thumb.length() > 0L) thumb.absolutePath else null
        } catch (e: Throwable) {
            log("朋友圈视频缩略图生成异常: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun md5(value: String): String {
        return runCatching {
            val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        }.getOrDefault(value.hashCode().toString())
    }

    private fun fileMd5(path: String): String {
        return runCatching {
            val digest = MessageDigest.getInstance("MD5")
            File(path).inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
    }

    private fun triggerUpload(): Boolean {
        val getter = dexFinder?.snsUploadManagerGetterMethod
        val manager = KavaReflector.invoke(getter, null) ?: run {
            log("朋友圈上传触发失败: manager为空")
            return false
        }
        val method = dexFinder?.snsUploadCheckMethod
        if (method == null) {
            log("朋友圈上传触发失败: checkPost方法为空")
            return false
        }
        KavaReflector.invoke(method, manager)
        return true
    }

    private fun invokeChain(receiver: Any, method: Method?, value: String) {
        if (method == null) return
        KavaReflector.invoke(method, receiver, value)
    }

    private fun writeField(target: Any, fieldName: String, value: Any?) {
        val field = KavaReflector.findFieldRecursive(target.javaClass, fieldName) ?: return
        KavaReflector.writeField(field, target, value)
    }

    private fun log(message: String) {
        logger?.log("[WeChatSnsApi] $message")
    }
}
