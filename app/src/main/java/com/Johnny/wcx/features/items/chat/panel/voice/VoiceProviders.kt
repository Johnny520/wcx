package com.Johnny.wcx.features.items.chat.panel.voice

import android.annotation.SuppressLint
import android.util.Xml
import com.Johnny.wcx.features.items.chat.panel.PanelSource
import com.Johnny.wcx.features.items.chat.panel.VoiceItem
import com.Johnny.wcx.features.items.chat.panel.VoiceProviderPage
import com.Johnny.wcx.features.items.chat.panel.service.FunBoxServiceClient
import com.Johnny.wcx.features.items.chat.panel.service.FunBoxVoiceRepository
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface VoiceProvider {
    val id: String
    val name: String

    suspend fun browse(parent: VoiceItem? = null, page: Int = 0): Result<VoiceProviderPage>
    suspend fun search(query: String, page: Int = 0): Result<VoiceProviderPage>
    suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem>
}

/** Fixed built-in provider registry. */
object VoiceProviderRegistry {
    val providers: List<VoiceProvider> = listOf(
        FunBoxShareVoiceProvider,
        RingDuoDuoVoiceProvider,
        UoiceVoiceProvider,
    )

    fun get(id: String): VoiceProvider = providers.firstOrNull { it.id == id } ?: providers.first()

    fun forItem(item: VoiceItem): VoiceProvider? = when {
        item.id.startsWith("funbox:") -> FunBoxShareVoiceProvider
        item.id.startsWith("ring:") -> RingDuoDuoVoiceProvider
        item.id.startsWith("uoice:") -> UoiceVoiceProvider
        else -> null
    }
}

private val providerHttpClient = OkHttpClient.Builder().build()
private const val PROVIDER_TAG = "VoiceProviderNetwork"

private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
    val endpoint = providerEndpoint(url)
    WeLogger.d(PROVIDER_TAG, "GET start endpoint=$endpoint thread=${Thread.currentThread().name}")
    try {
        providerHttpClient.newCall(Request.Builder().url(url).get().build()).awaitResponse().use { response ->
            WeLogger.d(PROVIDER_TAG, "GET HTTP ${response.code} endpoint=$endpoint")
            check(response.isSuccessful) { "HTTP ${response.code}: ${response.message}" }
            response.body.string().also { body ->
                WeLogger.i(PROVIDER_TAG, "GET completed endpoint=$endpoint chars=${body.length}")
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        WeLogger.e(PROVIDER_TAG, "GET failed endpoint=$endpoint", error)
        throw error
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private fun providerEndpoint(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "invalid-url"
    return "${parsed.scheme}://${parsed.host}${parsed.encodedPath}"
}

object UoiceVoiceProvider : VoiceProvider {
    override val id = "uoice"
    override val name = "千变语音2"

    private fun categoryUrl(title: String): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val encoded = URLEncoder.encode(title, StandardCharsets.UTF_8.name())
        return when (title) {
            "最新发布", "最近更新" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=2"
            "热榜总榜" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0"
            "热榜周榜" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0&startTime=" +
                    URLEncoder.encode(formatter.format(Date(System.currentTimeMillis() - 604_800_000L)), StandardCharsets.UTF_8.name())

            "热榜月榜" -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0&startTime=" +
                    URLEncoder.encode(formatter.format(Date(System.currentTimeMillis() - 2_592_000_000L)), StandardCharsets.UTF_8.name())

            else -> "https://uoice.com/v1/voice/list?page={{PAGE}}&take=50&count=0&sort=0&tag=$encoded"
        }
    }

    private val categories = listOf(
        "最新发布", "最近更新", "热榜总榜", "热榜周榜", "热榜月榜",
        "抖音快手B站热门", "搞笑", "DJ蹦迪", "聊天日常", "铃声多多", "鬼畜&卡点", "二次元", "萝莉音", "诱惑软妹音",
    )

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> = runCatching {
        if (parent == null) {
            return@runCatching VoiceProviderPage(
                categories.map { title ->
                    VoiceItem(
                        id = "uoice:category:$title",
                        title = title,
                        source = PanelSource.ONLINE,
                        isContainer = true,
                        metadata = mapOf("category" to title),
                    )
                },
                page = 0,
                hasMore = false,
            )
        }
        val voiceId = parent.metadata["voiceId"]
        if (voiceId != null) {
            return@runCatching parseVoiceItems(
                getText("https://uoice.com/v1/voice?id=$voiceId&keyword=null&fromType=0"),
                parent.title,
            )
        }
        val category = parent.metadata["category"] ?: error("无效分类")
        parseCatalog(getText(categoryUrl(category).replace("{{PAGE}}", (page + 1).toString())), page)
    }.logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> = runCatching {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val url = "https://uoice.com/v1/search/voice?page=${page + 1}&take=50&keyword=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        parseCatalog(getText(url), page)
    }.logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        require(item.id.startsWith("uoice:")) { "无效语音" }
        item.copy(
            remoteUrl = "https://uoice.com/v1/voice/audition?id=${item.id.substringAfterLast(':')}",
            format = item.format.ifBlank { mimeExtension(item.metadata["mimeType"]) },
        )
    }

    private fun parseCatalog(body: String, page: Int): VoiceProviderPage {
        val result = DefaultJson.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["result"]!!.jsonArray
        val items = result.map { element ->
            val obj = element.jsonObject
            val itemId = obj["id"]!!.jsonPrimitive.content
            VoiceItem(
                id = "uoice:$itemId",
                title = obj["name"]?.jsonPrimitive?.content ?: itemId,
                source = PanelSource.ONLINE,
                isContainer = true,
                metadata = mapOf("voiceId" to itemId),
            )
        }
        return VoiceProviderPage(items, page, items.size >= 50)
    }

    private fun parseVoiceItems(body: String, packTitle: String): VoiceProviderPage {
        val result = DefaultJson.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["item"]!!.jsonArray
        val items = result.mapNotNull { element ->
            val obj = element.jsonObject
            val state = obj["state"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (state != 0) return@mapNotNull null
            val itemId = obj["id"]!!.jsonPrimitive.content
            val mimeType = obj["mimeType"]?.jsonPrimitive?.content.orEmpty()
            VoiceItem(
                id = "uoice:$itemId",
                title = obj["name"]?.jsonPrimitive?.content ?: itemId,
                source = PanelSource.ONLINE,
                packId = packTitle,
                durationMs = (obj["duration"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L) * 1_000L,
                format = mimeExtension(mimeType),
                metadata = mapOf(
                    "mimeType" to mimeType,
                    "size" to (obj["size"]?.jsonPrimitive?.content ?: "0"),
                ),
            )
        }
        return VoiceProviderPage(items, 0, false)
    }

    private fun mimeExtension(mimeType: String?): String = when (mimeType?.lowercase()) {
        "audio/aac" -> "aac"
        "audio/vnd.wave", "audio/wav", "audio/x-wav" -> "wav"
        "audio/mp4", "audio/m4a" -> "m4a"
        else -> "mp3"
    }
}

object RingDuoDuoVoiceProvider : VoiceProvider {
    override val id = "ring_duoduo"
    override val name = "铃声多多"

    private val categories = linkedMapOf(
        "彩铃" to 20, "最热" to 1, "短信" to 5, "DJ榜" to 6,
        "情感" to 8, "铃声" to 11, "欧美馆" to 33,
    )

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> = runCatching {
        if (parent == null) {
            return@runCatching VoiceProviderPage(
                categories.map { (title, category) ->
                    VoiceItem(
                        id = "ring:category:$category",
                        title = title,
                        source = PanelSource.ONLINE,
                        isContainer = true,
                        metadata = mapOf("category" to category.toString()),
                    )
                },
                0,
                false,
            )
        }
        val category = parent.metadata["category"]?.toIntOrNull() ?: error("无效分类")
        val plain = CATEGORY_PREFIX + category +
                "&from=&page=${page + 1}&pagesize=25&uid=&ptime=2023-08-24&tstamp=${System.currentTimeMillis()}"
        parseRingXml(getText(wrappedUrl(plain)), page)
    }.logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> = runCatching {
        require(query.isNotBlank()) { "搜索内容不能为空" }
        val plain = SEARCH_PREFIX + query +
                "&src=input&page=${page + 1}&pagesize=15&include=all&ctdb=1&cudb=1" +
                "&ptime=2023-08-24&tstamp=${System.currentTimeMillis()}"
        parseRingXml(getText(wrappedUrl(plain)), page)
    }.logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        require(!item.remoteUrl.isNullOrBlank()) { "没有可用下载地址" }
        item
    }

    @SuppressLint("GetInstance")
    private fun wrappedUrl(plain: String): String {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("hikmpuF9".toByteArray(), "DES"))
        val encoded = Base64.getMimeEncoder().encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)))
        return "http://ring.shoujiduoduo.com/ring_enc.php?q=" +
                URLEncoder.encode(encoded, StandardCharsets.UTF_8.name()) +
                "&os=ar&ver=8.9.36.0&startid=YKkVXsjl1wiS8lwqPrvFcqFHuVzshHz6"
    }

    private fun parseRingXml(body: String, page: Int): VoiceProviderPage {
        val parser = Xml.newPullParser().apply { setInput(StringReader(body)) }
        val items = mutableListOf<VoiceItem>()
        var baseUrl = "http://cdnringbd.shoujiduoduo.com"
        var hasMore = false
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "page" -> {
                        baseUrl = parser.getAttributeValue(null, "baseurl")?.trimEnd('/') ?: baseUrl
                        hasMore = parser.getAttributeValue(null, "hasmore").equals("true", ignoreCase = true)
                    }

                    "ring" -> {
                        val rawUrl = parser.getAttributeValue(null, "mp3url")
                        val title = parser.getAttributeValue(null, "name")
                        if (!rawUrl.isNullOrBlank() && !title.isNullOrBlank()) {
                            val itemId = parser.getAttributeValue(null, "rid")
                                ?: parser.getAttributeValue(null, "uid")
                                ?: parser.getAttributeValue(null, "cid")
                                ?: rawUrl
                            items += VoiceItem(
                                id = "ring:$itemId",
                                title = title,
                                remoteUrl = if (rawUrl.startsWith("http")) rawUrl else "$baseUrl/${rawUrl.trimStart('/')}",
                                source = PanelSource.ONLINE,
                                // The endpoint currently reports the same placeholder duration for
                                // unrelated tracks. FunBox also ignores this field and resolves the
                                // real duration only after the audio is opened.
                                durationMs = 0L,
                                format = "mp3",
                            )
                        }
                    }
                }
            }
            parser.next()
        }
        return VoiceProviderPage(items, page, hasMore)
    }

    private const val CATEGORY_PREFIX =
        "user=12345678&prod=RingDD_ar_8.9.36.0&isrc=RingDD_ar_8.9.36.0_qq.apk" +
                "&dev=UAWEIP90Kelin114514&vc=60089360&loc=CN&sp=cm&type=getlist&listid="

    private const val SEARCH_PREFIX =
        "user=12345678&prod=RingDD_ar_8.9.36.0&isrc=RingDD_ar_8.9.36.0_qq.apk" +
                "&dev=HUAWEIP90Kelin114514&vc=60089360&loc=CN&sp=cm&type=search&keyword="
}

object FunBoxShareVoiceProvider : VoiceProvider {
    override val id = "funbox_share"
    override val name = "FunBox分享"

    override suspend fun browse(parent: VoiceItem?, page: Int): Result<VoiceProviderPage> =
        FunBoxVoiceRepository.browseSharedVoices(parent, page).logProviderResult(name, "browse", page)

    override suspend fun search(query: String, page: Int): Result<VoiceProviderPage> =
        FunBoxVoiceRepository.searchSharedVoices(query, page).logProviderResult(name, "search", page)

    override suspend fun resolveAudio(item: VoiceItem): Result<VoiceItem> = runCatching {
        val objectId = item.remoteObjectId ?: error("没有可用语音对象")
        item.copy(remoteUrl = FunBoxServiceClient.objectUrl("voice", objectId), format = "mp3")
    }.onSuccess {
        WeLogger.i(PROVIDER_TAG, "provider=$name audio URL resolved")
    }.onFailure { error ->
        WeLogger.e(PROVIDER_TAG, "provider=$name audio URL resolution failed", error)
    }
}

private fun Result<VoiceProviderPage>.logProviderResult(
    provider: String,
    action: String,
    requestedPage: Int,
): Result<VoiceProviderPage> = onSuccess { result ->
    WeLogger.i(
        PROVIDER_TAG,
        "provider=$provider action=$action requestedPage=$requestedPage resultPage=${result.page} items=${result.items.size}",
    )
}.onFailure { error ->
    WeLogger.e(PROVIDER_TAG, "provider=$provider action=$action page=$requestedPage failed", error)
}
