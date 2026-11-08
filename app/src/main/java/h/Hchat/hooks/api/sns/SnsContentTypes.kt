package h.Hchat.hooks.api.sns

internal enum class SnsContentKind(val label: String) {
    TEXT("文字"),
    IMAGE("图片/图文"),
    LIVE_PHOTO("实况照片"),
    VIDEO("视频/视文"),
    LINK("网页/链接"),
    MUSIC("音乐"),
    VIDEO_LINK("视频链接"),
    OTHER("其他类型"),
    UNKNOWN("未知")
}

internal object SnsContentTypes {
    const val IMAGE = 0x1
    const val TEXT = 0x2
    const val LINK = 0x3
    const val MUSIC = 0x4
    const val VIDEO_LINK = 0x5
    const val VIDEO = 0xF
    const val LIVE_PHOTO = 0x36

    fun classify(rawType: Int): SnsContentKind = when (rawType) {
        IMAGE -> SnsContentKind.IMAGE
        TEXT -> SnsContentKind.TEXT
        LINK -> SnsContentKind.LINK
        MUSIC -> SnsContentKind.MUSIC
        VIDEO_LINK -> SnsContentKind.VIDEO_LINK
        VIDEO -> SnsContentKind.VIDEO
        LIVE_PHOTO -> SnsContentKind.LIVE_PHOTO
        in CONFIRMED_EXTENDED_TYPES -> SnsContentKind.OTHER
        else -> SnsContentKind.UNKNOWN
    }

    fun isImageMedia(rawType: Int): Boolean {
        val kind = classify(rawType)
        return kind == SnsContentKind.IMAGE || kind == SnsContentKind.LIVE_PHOTO
    }

    fun isVideoMedia(rawType: Int): Boolean {
        val kind = classify(rawType)
        return kind == SnsContentKind.VIDEO || kind == SnsContentKind.VIDEO_LINK
    }

    fun usesMedia(rawType: Int): Boolean = isImageMedia(rawType) || isVideoMedia(rawType)

    private val CONFIRMED_EXTENDED_TYPES = setOf(
        7, 8, 10, 14, 17, 18, 21, 23, 26, 28, 29, 30, 33, 34,
        36, 37, 38, 39, 41, 42, 43, 44, 45, 46, 47, 48, 50, 51,
        52, 53, 55, 56, 58, 59, 101
    )
}
