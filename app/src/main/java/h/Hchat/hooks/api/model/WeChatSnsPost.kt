package h.Hchat.hooks.api.model

class WeChatSnsMedia internal constructor(
    private val id: String,
    private val type: Int,
    private val url: String,
    private val thumbUrl: String,
    private val liveVideo: WeChatSnsMedia?
) {
    fun getId(): String = id

    fun getType(): Int = type

    fun getUrl(): String = url

    fun getThumbUrl(): String = thumbUrl

    fun isLivePhoto(): Boolean = liveVideo != null

    fun getLiveVideo(): WeChatSnsMedia? = liveVideo
}

class WeChatSnsPost internal constructor(
    private val snsId: String,
    private val localId: Long,
    private val userName: String,
    private val displayName: String,
    private val createTimeSeconds: Long,
    private val storageType: Int,
    private val contentType: Int,
    private val type: String,
    private val content: String,
    private val mediaList: List<WeChatSnsMedia>,
    private val self: Boolean
) {
    fun getSnsId(): String = snsId

    fun getLocalId(): Long = localId

    fun getUserName(): String = userName

    fun getDisplayName(): String = displayName

    fun getCreateTimeSeconds(): Long = createTimeSeconds

    fun getCreateTimeMillis(): Long = createTimeSeconds * 1000L

    fun getStorageType(): Int = storageType

    fun getContentType(): Int = contentType

    fun getType(): String = type

    fun getText(): String = content

    fun getContent(): String = content

    fun getMediaList(): List<WeChatSnsMedia> = mediaList

    fun isText(): Boolean = type == TYPE_TEXT

    fun isImage(): Boolean = type == TYPE_IMAGE

    fun isVideo(): Boolean = type == TYPE_VIDEO

    fun isLivePhoto(): Boolean = type == TYPE_LIVE_PHOTO

    fun isCard(): Boolean = type == TYPE_CARD

    fun isSelf(): Boolean = self

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_LIVE_PHOTO = "live_photo"
        const val TYPE_CARD = "card"
        const val TYPE_UNKNOWN = "unknown"
    }
}

class WeChatSnsLivePhoto internal constructor(
    private val imagePath: String,
    private val videoPath: String,
    private val videoDurationMillis: Int,
    private val coverTimeMillis: Long
) {
    fun getImagePath(): String = imagePath

    fun getVideoPath(): String = videoPath

    fun getVideoDurationMillis(): Int = videoDurationMillis

    fun getCoverTimeMillis(): Long = coverTimeMillis
}

class WeChatSnsPrepareResult internal constructor(
    private val success: Boolean,
    private val message: String,
    private val snsId: String,
    private val content: String,
    private val type: String,
    private val imagePathList: List<String>,
    private val videoPath: String,
    private val videoThumbPath: String,
    private val livePhotoList: List<WeChatSnsLivePhoto>,
    private val preparedImageList: List<WeChatSnsLivePhoto?>
) {
    fun isSuccess(): Boolean = success

    fun getMessage(): String = message

    fun getSnsId(): String = snsId

    fun getContent(): String = content

    fun getText(): String = content

    fun getType(): String = type

    fun getImagePathList(): List<String> = imagePathList

    fun getVideoPath(): String = videoPath

    fun getVideoThumbPath(): String = videoThumbPath

    fun getLivePhotoList(): List<WeChatSnsLivePhoto> = livePhotoList

    internal fun getPreparedImageList(): List<WeChatSnsLivePhoto?> = preparedImageList

    fun isText(): Boolean = type == WeChatSnsPost.TYPE_TEXT

    fun isImage(): Boolean = type == WeChatSnsPost.TYPE_IMAGE

    fun isVideo(): Boolean = type == WeChatSnsPost.TYPE_VIDEO

    fun isLivePhoto(): Boolean = type == WeChatSnsPost.TYPE_LIVE_PHOTO

    companion object {
        @JvmStatic
        internal fun failure(snsId: String, message: String): WeChatSnsPrepareResult {
            return WeChatSnsPrepareResult(
                success = false,
                message = message,
                snsId = snsId,
                content = "",
                type = WeChatSnsPost.TYPE_UNKNOWN,
                imagePathList = emptyList(),
                videoPath = "",
                videoThumbPath = "",
                livePhotoList = emptyList(),
                preparedImageList = emptyList()
            )
        }
    }
}
