package h.Hchat.hooks.items.shortvideo

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Xml
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.HchatMediaDownloader
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Shared Finder media path used by the alt-entry menu and script bridge. */
internal object FinderMediaDownloadSupport {
    const val MEDIA_TYPE_IMAGE = 2
    const val MEDIA_TYPE_VIDEO = 4

    private const val FINDER_VIDEO_SPEC_QUERY = "X-snsvideoflag"
    private const val FINDER_FEED_END_TAG = "</finderFeed>"
    private const val MAX_MESSAGE_UNWRAP_DEPTH = 2
    private const val DEFAULT_COMMENT_SCENE = 20
    private const val XML_PARSE_ERROR_LOG_COOLDOWN_MS = 10_000L
    private val xmlParseErrorLogAt = AtomicLong(0L)
    private val MESSAGE_WRAPPER_GETTERS = arrayOf("getMessage", "getStoredMessage")
    private val FINDER_XML_FIELDS = mapOf(
        "mediatype" to "mediaType",
        "url" to "url",
        "urltoken" to "url_token",
        "url_token" to "url_token",
        "decodekey" to "decodeKey",
        "decode_key" to "decodeKey",
        "pcdnurl" to "pcdn_url",
        "pcdn_url" to "pcdn_url",
        "thumburl" to "thumbUrl",
        "thumburltoken" to "thumb_url_token",
        "thumb_url_token" to "thumb_url_token",
        "coverurl" to "coverUrl",
        "coverurltoken" to "cover_url_token",
        "cover_url_token" to "cover_url_token",
        "fullcoverurl" to "fullCoverUrl",
        "width" to "width",
        "height" to "height",
        "videoplayduration" to "videoPlayDuration"
    )
    private val FINDER_XML_META_FIELDS = mapOf(
        "objectid" to "objectId",
        "objectnonceid" to "objectNonceId",
        "sourcecommentscene" to "sourceCommentScene"
    )

    @Volatile
    private var detailResolver: FinderFeedDetailResolver? = null

    data class FinderMedia(
        val type: Int,
        val items: List<JSONObject>,
        val objectId: String = "",
        val objectNonceId: String = "",
        val sourceCommentScene: Int = DEFAULT_COMMENT_SCENE
    )

    @Synchronized
    fun install(context: FeatureContext): Boolean {
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        detailResolver?.takeIf { it.runtimeKey == runtimeKey }?.let { return true }
        val resolver = FinderFeedDetailResolver.locate(context) ?: return false
        detailResolver = resolver
        return true
    }

    fun extractMedia(source: Any?): FinderMedia? = extractMedia(source, 0)

    private fun extractMedia(source: Any?, unwrapDepth: Int): FinderMedia? {
        if (source == null) return null
        extractFinderObject(source)?.let { return it }
        extractFinderItem(source)?.let { return it }
        findBaseFinderFeedObject(source)?.let { feed ->
            val finderItem = KavaReflector.readField(feed, "feedObject")
            if (finderItem != null) {
                extractFinderItem(finderItem)?.let { return it }
            }
        }
        extractMessageXml(source)?.let { xml ->
            extractFinderXml(xml)?.let { return it }
        }
        if (unwrapDepth >= MAX_MESSAGE_UNWRAP_DEPTH) return null
        MESSAGE_WRAPPER_GETTERS.forEach { getter ->
            val nested = KavaReflector.invokeMethod(source, getter)
            if (nested != null && nested !== source) {
                extractMedia(nested, unwrapDepth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun extractMessageXml(source: Any): String? {
        if (source is String) return source.takeIf { it.isNotBlank() }
        arrayOf("getXml", "getContent", "getText").forEach { getter ->
            val value = KavaReflector.invokeMethod(source, getter) as? String
            if (!value.isNullOrBlank()) return value
        }
        return arrayOf("xml", "content")
            .firstNotNullOfOrNull { field ->
                (KavaReflector.readField(source, field) as? String)?.takeIf { it.isNotBlank() }
            }
    }

    private fun extractFinderXml(source: String): FinderMedia? {
        val feedStart = source.indexOf("<finderFeed", ignoreCase = true)
        if (feedStart < 0) return null
        val feedEnd = source.indexOf(FINDER_FEED_END_TAG, feedStart, ignoreCase = true)
        if (feedEnd < feedStart) return null
        return try {
            val parser = Xml.newPullParser().apply {
                setInput(StringReader(source.substring(feedStart, feedEnd + FINDER_FEED_END_TAG.length)))
            }
            val items = ArrayList<JSONObject>()
            var finderDepth = -1
            var mediaListDepth = -1
            var mediaDepth = -1
            var currentItem: JSONObject? = null
            var mediaType = 0
            var objectId = ""
            var objectNonceId = ""
            var sourceCommentScene = DEFAULT_COMMENT_SCENE
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name.orEmpty().lowercase(Locale.US)
                        when {
                            tag == "finderfeed" -> finderDepth = parser.depth
                            finderDepth > 0 && tag == "medialist" -> mediaListDepth = parser.depth
                            mediaListDepth > 0 && tag == "media" -> {
                                mediaDepth = parser.depth
                                currentItem = JSONObject()
                            }
                            finderDepth > 0 -> {
                                val mediaField = FINDER_XML_FIELDS[tag]
                                val metaField = FINDER_XML_META_FIELDS[tag]
                                    ?.takeIf { parser.depth == finderDepth + 1 }
                                val field = mediaField ?: metaField
                                if (field != null &&
                                    (currentItem != null || mediaField == "mediaType" || metaField != null)
                                ) {
                                    val value = parser.nextText().trim()
                                    if (value.isNotEmpty()) {
                                        currentItem?.put(field, value)
                                        if (field == "mediaType" && mediaType == 0) {
                                            mediaType = value.toIntOrNull() ?: 0
                                        }
                                        when (field) {
                                            "objectId" -> objectId = value
                                            "objectNonceId" -> objectNonceId = value
                                            "sourceCommentScene" -> {
                                                sourceCommentScene = value.toIntOrNull()
                                                    ?: DEFAULT_COMMENT_SCENE
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name.orEmpty().lowercase(Locale.US)
                        when {
                            tag == "media" && parser.depth == mediaDepth -> {
                                currentItem?.takeIf { it.length() > 0 }?.let(items::add)
                                currentItem = null
                                mediaDepth = -1
                            }
                            tag == "medialist" && parser.depth == mediaListDepth -> mediaListDepth = -1
                            tag == "finderfeed" && parser.depth == finderDepth -> finderDepth = -1
                        }
                    }
                }
                event = parser.next()
            }
            val media = FinderMedia(
                type = mediaType,
                items = items,
                objectId = objectId,
                objectNonceId = objectNonceId,
                sourceCommentScene = sourceCommentScene
            )
            if (media.type > 0 && media.items.isNotEmpty()) media else null
        } catch (t: Throwable) {
            logXmlParseFailure(source, t)
            null
        }
    }

    private fun logXmlParseFailure(source: String, throwable: Throwable) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = xmlParseErrorLogAt.get()
            if (previous != 0L && now - previous < XML_PARSE_ERROR_LOG_COOLDOWN_MS) return
            if (xmlParseErrorLogAt.compareAndSet(previous, now)) break
        }
        HLog.e(
            "[Hchat:FinderDownload] 解析视频号XML失败: stage=pull-parser " +
                "length=${source.length} hash=${source.hashCode()}",
            throwable
        )
    }

    fun mediaLinks(media: FinderMedia): String {
        return when (media.type) {
            MEDIA_TYPE_IMAGE -> media.items.mapNotNull(::fullUrl).joinToString("\n")
            MEDIA_TYPE_VIDEO -> {
                val first = media.items.firstOrNull()
                val pcdn = pcdnUrl(first)
                if (pcdn.isNotBlank()) {
                    "链接: $pcdn"
                } else {
                    listOf(
                        "密链: ${fullUrl(first)}",
                        "密钥: ${first?.optString("decodeKey").orEmpty()}"
                    ).joinToString("\n")
                }
            }
            else -> ""
        }
    }

    fun downloadItem(
        context: Context,
        media: FinderMedia,
        mediaIndex: Int,
        savePath: String?
    ): File? {
        if (mediaIndex !in media.items.indices) return null
        val prepared = prepareForDownload(media, mediaIndex)
        if (mediaIndex !in prepared.items.indices) return null
        return when (prepared.type) {
            MEDIA_TYPE_IMAGE -> downloadImage(context, prepared.items[mediaIndex], mediaIndex, savePath)
            MEDIA_TYPE_VIDEO -> downloadVideo(context, prepared.items[mediaIndex], mediaIndex, savePath)
            else -> null
        }
    }

    fun downloadAllImages(context: Context, media: FinderMedia, savePath: String?): List<File> {
        if (media.type != MEDIA_TYPE_IMAGE) return emptyList()
        return media.items.mapIndexedNotNull { index, item ->
            downloadImage(context, item, index, savePath)
        }
    }

    private fun extractFinderItem(finderItem: Any): FinderMedia? {
        val mediaType = (KavaReflector.invokeMethod(finderItem, "getMediaType") as? Number)?.toInt()
            ?: return null
        val mediaList = KavaReflector.invokeMethod(finderItem, "getMediaList") as? Iterable<*>
            ?: return null
        val mediaExtList = (KavaReflector.invokeMethod(finderItem, "getMediaExtList") as? Iterable<*>)
            ?.toList()
            .orEmpty()
        val items = mediaList.mapIndexedNotNull { index, item ->
            val json = KavaReflector.invokeMethod(item, "toJSON") as? JSONObject
                ?: return@mapIndexedNotNull null
            mergeMediaCdnInfo(json, mediaExtList.getOrNull(index))
            json
        }
        return FinderMedia(mediaType, items).takeIf { it.items.isNotEmpty() }
    }

    private fun mergeMediaCdnInfo(mediaJson: JSONObject, mediaExt: Any?) {
        if (pcdnUrl(mediaJson).isNotBlank() || mediaExt == null) return
        val extJson = KavaReflector.invokeMethod(mediaExt, "toJSON") as? JSONObject ?: return
        val cdnInfo = extJson.optJSONObject("media_cdn_info") ?: return
        if (cdnInfo.optString("pcdn_url").isBlank()) return
        runCatching { mediaJson.put("media_cdn_info", cdnInfo) }
    }

    private fun extractFinderObject(finderObject: Any): FinderMedia? {
        if (finderObject.javaClass.name != "com.tencent.mm.protocal.protobuf.FinderObject") return null
        val objectDesc = KavaReflector.invokeMethod(finderObject, "getObjectDesc")
            ?: KavaReflector.readField(finderObject, "objectDesc")
            ?: return null
        val mediaType = ((KavaReflector.invokeMethod(objectDesc, "getMediaType")
            ?: KavaReflector.readField(objectDesc, "mediaType")) as? Number)?.toInt()
            ?: return null
        val mediaList = (KavaReflector.invokeMethod(objectDesc, "getMedia")
            ?: KavaReflector.readField(objectDesc, "media")) as? Iterable<*>
            ?: return null
        val items = mediaList.mapNotNull { item ->
            KavaReflector.invokeMethod(item, "toJSON") as? JSONObject
        }
        val rawId = KavaReflector.invokeMethod(finderObject, "getId")
            ?: KavaReflector.readField(finderObject, "id")
        val objectId = (rawId as? Number)?.toLong()
            ?.let { java.lang.Long.toUnsignedString(it) }
            .orEmpty()
        val objectNonceId = (KavaReflector.invokeMethod(finderObject, "getObjectNonceId")
            ?: KavaReflector.readField(finderObject, "objectNonceId")) as? String
        return FinderMedia(mediaType, items, objectId, objectNonceId.orEmpty())
            .takeIf { it.items.isNotEmpty() }
    }

    private fun prepareForDownload(media: FinderMedia, mediaIndex: Int): FinderMedia {
        if (media.type != MEDIA_TYPE_VIDEO || hasUsableVideoSource(media.items[mediaIndex])) return media
        if (media.objectId.isBlank() || media.objectNonceId.isBlank()) return media
        val finderObject = detailResolver?.resolve(
            media.objectId,
            media.objectNonceId,
            media.sourceCommentScene
        ) ?: run {
            HLog.e("[Hchat:FinderDownload] 未能补齐视频号详情: objectId=${media.objectId}")
            return media
        }
        return extractFinderObject(finderObject) ?: run {
            HLog.e("[Hchat:FinderDownload] 视频号详情缺少可下载媒体: objectId=${media.objectId}")
            media
        }
    }

    private fun hasUsableVideoSource(json: JSONObject): Boolean {
        return jsonString(json, "decodeKey", "decode_key").isNotBlank() || pcdnUrl(json).isNotBlank()
    }

    private fun findBaseFinderFeedObject(source: Any): Any? {
        if (isBaseFinderFeed(source)) return source
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).forEach { field ->
                val typeName = field.type.name
                if (typeName == "com.tencent.mm.plugin.finder.model.BaseFinderFeed" ||
                    typeName.contains("BaseFinderFeed")
                ) {
                    KavaReflector.readField(field, source)?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun isBaseFinderFeed(source: Any?): Boolean {
        val className = source?.javaClass?.name ?: return false
        return className == "com.tencent.mm.plugin.finder.model.BaseFinderFeed" ||
            className.contains("BaseFinderFeed")
    }

    private fun downloadImage(
        context: Context,
        json: JSONObject,
        mediaIndex: Int,
        savePath: String?
    ): File? {
        val url = fullUrl(json) ?: return null
        val target = targetFile(
            context,
            savePath,
            "finder_image_${System.currentTimeMillis()}_${mediaIndex + 1}",
            extensionFromUrl(url, "png")
        )
        return HchatMediaDownloader.downloadFinderImage(context, url, target)
            ?.takeIf { it.isFile && it.length() > 0L }
    }

    private fun downloadVideo(
        context: Context,
        json: JSONObject,
        mediaIndex: Int,
        savePath: String?
    ): File? {
        val target = targetFile(
            context,
            savePath,
            "finder_video_${System.currentTimeMillis()}_${mediaIndex + 1}",
            "mp4"
        )
        val decodeKey = jsonString(json, "decodeKey", "decode_key")
        val h265Url = h265VideoUrl(json)
        val pcdn = pcdnUrl(json)
        val file = when {
            !h265Url.isNullOrBlank() && decodeKey.isNotBlank() ->
                HchatMediaDownloader.downloadAndDecryptFinderVideo(context, h265Url, decodeKey, target)
            pcdn.isNotBlank() ->
                HchatMediaDownloader.downloadFinderVideo(context, pcdn, target)
            decodeKey.isNotBlank() ->
                HchatMediaDownloader.downloadAndDecryptFinderVideo(context, fullUrl(json), decodeKey, target)
            else ->
                HchatMediaDownloader.downloadFinderVideo(context, fullUrl(json), target)
        }
        return validateFinderVideo(file)
    }

    private fun validateFinderVideo(file: File?): File? {
        if (file == null) return null
        val valid = file.isFile && file.length() > 8L && runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(8)
                input.read(header) == header.size &&
                    header[4] == 'f'.code.toByte() &&
                    header[5] == 't'.code.toByte() &&
                    header[6] == 'y'.code.toByte() &&
                    header[7] == 'p'.code.toByte()
            }
        }.getOrDefault(false)
        if (valid) return file
        val path = file.absolutePath
        val size = file.length()
        runCatching { file.delete() }
        HLog.e("[Hchat:FinderDownload] 视频号下载结果不是有效MP4，已删除: path=$path size=$size")
        return null
    }

    private fun targetFile(
        context: Context,
        savePath: String?,
        defaultName: String,
        defaultExtension: String
    ): File {
        val path = savePath?.trim().orEmpty()
        if (path.isBlank()) {
            return File(HchatMediaDownloader.hchatDir(context, "Finder"), "$defaultName.$defaultExtension")
        }
        val target = File(path)
        if (path.endsWith("/") || target.isDirectory) {
            target.mkdirs()
            return File(target, "$defaultName.$defaultExtension")
        }
        target.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        return target
    }

    private fun fullUrl(json: JSONObject?): String? {
        if (json == null) return null
        val pcdn = pcdnUrl(json)
        if (pcdn.isNotBlank()) return pcdn
        val url = jsonString(json, "url")
        val token = jsonString(json, "url_token", "urlToken")
        return (url + token).takeIf { it.isNotBlank() }
    }

    private fun h265VideoUrl(json: JSONObject): String? {
        val fileFormat = h265FileFormat(json) ?: return null
        val sourceUrl = (jsonString(json, "url") + jsonString(json, "url_token", "urlToken"))
            .takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            sourceUrl.toHttpUrlOrNull()
                ?.newBuilder()
                ?.setQueryParameter(FINDER_VIDEO_SPEC_QUERY, fileFormat)
                ?.build()
                ?.toString()
        }.getOrNull()
    }

    private fun h265FileFormat(json: JSONObject): String? {
        val specs = json.optJSONArray("spec") ?: return null
        for (index in 0 until specs.length()) {
            val spec = specs.optJSONObject(index) ?: continue
            val format = jsonString(spec, "codingFormat", "coding_format")
            if (format.equals("h265", ignoreCase = true) || format.equals("hevc", ignoreCase = true)) {
                jsonString(spec, "fileFormat", "file_format")
                    .takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun pcdnUrl(json: JSONObject?): String {
        if (json == null) return ""
        return json.optJSONObject("media_cdn_info")?.optString("pcdn_url").orEmpty()
            .ifBlank { json.optString("pcdn_url").orEmpty() }
    }

    private fun jsonString(json: JSONObject, vararg names: String): String {
        return names.firstNotNullOfOrNull { name ->
            json.optString(name).takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun extensionFromUrl(url: String, fallback: String): String {
        val segment = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
        return when (val extension = segment.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4" -> extension
            else -> fallback
        }
    }
}
