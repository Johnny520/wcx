package h.Hchat.hooks.api.media

import android.content.Context
import android.os.Build
import android.text.Html
import android.text.TextUtils
import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.ui.WeChatCurrentActivityApi
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.reflect.Method
import java.util.LinkedList
import java.util.Collections
import java.util.Locale
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

class WeChatFavoriteApi(
    private val hostContext: Context,
    private val dexFinder: DexFinder,
    private val hostClassLoader: ClassLoader,
    private val dexKitBridge: DexKitBridge?,
    private val currentActivityApi: WeChatCurrentActivityApi,
    private val logger: Logger?
) {
    interface Logger {
        fun log(message: String)
    }

    data class FavoritePage(
        val items: List<WeChatFavoriteItem>,
        val hasMore: Boolean,
        val changed: Boolean = false
    )

    private val nativeFavorites = object : LinkedHashMap<Long, Any>(NATIVE_FAVORITE_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Any>?): Boolean {
            return size > NATIVE_FAVORITE_CACHE_SIZE
        }
    }
    private val favoriteItems = LinkedHashMap<Long, WeChatFavoriteItem>()
    private val favoriteCacheLock = Any()
    @Volatile private var favoriteCursor = 0L
    @Volatile private var favoriteHasMore = true
    @Volatile private var favoriteHeadMarker = ""
    private val pendingVoiceSends = ConcurrentHashMap.newKeySet<String>()

    fun isAvailable(): Boolean {
        return canList() || canSendSilently()
    }

    fun canList(): Boolean {
        dexFinder.resolveFavoriteApi()
        return canListNative()
    }

    fun canSendSilently(): Boolean {
        return ensureFavoriteSendReady()
    }

    fun listRecent(limit: Int): List<WeChatFavoriteItem> {
        val safeLimit = limit.coerceIn(1, 200)
        return listRecentNative(safeLimit).orEmpty()
    }

    fun listAll(): List<WeChatFavoriteItem> {
        return openCachedList().items
    }

    fun cachedList(): FavoritePage {
        synchronized(favoriteCacheLock) {
            return FavoritePage(favoriteItems.values.toList(), favoriteHasMore)
        }
    }

    private fun canListNative(): Boolean {
        return dexFinder.favoriteServiceClass != null &&
            dexFinder.favoriteServiceResolverMethod != null &&
            dexFinder.favoriteStorageGetterMethod != null &&
            dexFinder.favoriteListMethod != null
    }

    fun openCachedList(): FavoritePage {
        val headItems = listRecentNative(1) ?: return cachedList()
        val marker = favoriteMarker(headItems.firstOrNull())
        var changed = false
        synchronized(favoriteCacheLock) {
            val hasCachedItems = favoriteItems.isNotEmpty()
            if (hasCachedItems && marker == favoriteHeadMarker) {
                return FavoritePage(favoriteItems.values.toList(), favoriteHasMore)
            }
            changed = hasCachedItems
            favoriteItems.clear()
            synchronized(nativeFavorites) { nativeFavorites.clear() }
            favoriteCursor = 0L
            favoriteHasMore = true
            favoriteHeadMarker = marker
        }
        val firstPage = loadNextPage()
        return firstPage.copy(changed = changed)
    }

    fun loadNextPage(): FavoritePage {
        synchronized(favoriteCacheLock) {
            if (!favoriteHasMore) return FavoritePage(favoriteItems.values.toList(), false)
            val nativeItems = queryFavoritePage(favoriteCursor) ?: run {
                favoriteHasMore = false
                return FavoritePage(favoriteItems.values.toList(), false)
            }
            if (nativeItems.isEmpty()) {
                favoriteHasMore = false
                return FavoritePage(favoriteItems.values.toList(), false)
            }
            val favorites = nativeItems.filterNotNull()
            val tagNames = favoriteTagNames(favorites)
            favorites.forEach { nativeItem ->
                val item = favoriteFromNative(nativeItem, tagNames) ?: return@forEach
                cacheNativeFavorite(item.localId, nativeItem)
                favoriteItems[item.localId] = item
            }
            favoriteHasMore = nativeItems.size >= FAVORITE_PAGE_SIZE
            if (favoriteHasMore) {
                favoriteCursor = nextFavoriteCursor(nativeItems, favoriteCursor)
                if (favoriteCursor <= 0L) favoriteHasMore = false
            }
            if (favoriteHeadMarker.isBlank()) {
                favoriteHeadMarker = favoriteMarker(favoriteItems.values.firstOrNull())
            }
            return FavoritePage(favoriteItems.values.toList(), favoriteHasMore)
        }
    }

    private fun queryFavoritePage(cursor: Long): List<*>? {
        dexFinder.resolveFavoriteApi()
        if (!canListNative()) return null
        val storage = favoriteStorage() ?: return null
        return runCatching {
            if (cursor == 0L) {
                KavaReflector.invoke(
                    dexFinder.favoriteListMethod, storage, FAVORITE_TYPE_ALL, FAVORITE_PAGE_SIZE,
                    Collections.emptyList<Any>(), Collections.emptySet<Any>(), null
                ) as? List<*>
            } else {
                val nextMethod = dexFinder.favoriteListNextMethod ?: return@runCatching null
                KavaReflector.invoke(
                    nextMethod, storage, cursor, FAVORITE_TYPE_ALL,
                    Collections.emptyList<Any>(), Collections.emptySet<Any>(), null
                ) as? List<*>
            }
        }.getOrElse {
            log("调用微信原生收藏分页失败: ${it.message}")
            null
        }
    }

    private fun nextFavoriteCursor(nativeItems: List<*>, currentCursor: Long): Long {
        val cursorMethod = dexFinder.favoriteListCursorMethod ?: return 0L
        val base = if (currentCursor == 0L) {
            val last = nativeItems.lastOrNull() ?: return 0L
            firstNumberField(last, "field_updateTime", "updateTime")?.toLong() ?: return 0L
        } else {
            currentCursor
        }
        val next = (KavaReflector.invoke(
            cursorMethod, null, base, FAVORITE_TYPE_ALL, FAVORITE_PAGE_SIZE
        ) as? Number)?.toLong() ?: return 0L
        return next.takeIf { it > 0L && it != base } ?: 0L
    }

    private fun favoriteMarker(item: WeChatFavoriteItem?): String {
        item ?: return "empty"
        return "${item.localId}:${item.updateTimeMillis}"
    }

    private fun listRecentNative(limit: Int): List<WeChatFavoriteItem>? {
        dexFinder.resolveFavoriteApi()
        if (!canListNative()) return null
        return runCatching {
            val service = KavaReflector.invoke(
                dexFinder.favoriteServiceResolverMethod,
                null,
                dexFinder.favoriteServiceClass
            ) ?: return@runCatching null
            val storage = KavaReflector.invoke(
                dexFinder.favoriteStorageGetterMethod,
                service
            ) ?: return@runCatching null
            val nativeItems = KavaReflector.invoke(
                dexFinder.favoriteListMethod,
                storage,
                FAVORITE_TYPE_ALL,
                limit,
                Collections.emptyList<Any>(),
                Collections.emptySet<Any>(),
                null
            ) as? List<*> ?: return@runCatching null
            val favorites = nativeItems.filterNotNull()
            favorites.forEach { nativeItem ->
                favoriteLocalId(nativeItem)?.let { cacheNativeFavorite(it, nativeItem) }
            }
            val tagNames = favoriteTagNames(favorites)
            favorites.mapNotNull { nativeItem ->
                val item = favoriteFromNative(nativeItem, tagNames) ?: return@mapNotNull null
                cacheNativeFavorite(item.localId, nativeItem)
                item
            }
        }.getOrElse {
            log("调用微信原生收藏列表失败: ${it.message}")
            null
        }
    }

    fun get(localId: Long): WeChatFavoriteItem? {
        val favorite = loadFavoriteNative(localId) ?: return null
        return favoriteFromNative(favorite, favoriteTagNames(listOf(favorite)))
    }

    fun textContent(localId: Long): String? {
        val favorite = loadFavoriteNative(localId) ?: return null
        if (favoriteType(favorite) != 1) return null
        return xmlValue(favoriteXml(favorite), "desc", "content", "title")
            .takeIf { it.isNotBlank() }
            ?: favoriteFromNative(favorite, favoriteTagNames(listOf(favorite)))?.title?.takeIf { it.isNotBlank() }
    }

    fun previewPath(localId: Long): String? {
        val favorite = loadFavoriteNative(localId) ?: return null
        return FavoriteVoiceResolver.resolvePreviewPath(
            hostContext = hostContext,
            classLoader = hostClassLoader,
            dexKitBridge = dexKitBridge,
            favorite = favorite,
            logger = ::logFavoriteVoice
        )
    }

    fun send(talker: String, localId: String): Boolean {
        val value = localId.trim().toLongOrNull()
        if (value == null || value <= 0L) {
            log("发送收藏失败: localId 非法 $localId")
            return false
        }
        return send(talker, value)
    }

    fun send(talker: String, localId: Long): Boolean {
        if (talker.isBlank() || localId <= 0L) {
            log("发送收藏失败: talker/localId 为空")
            return false
        }
        val favorite = loadFavoriteNative(localId) ?: run {
            log("发送收藏失败: 未找到收藏 localId=$localId")
            return false
        }
        when (favoriteType(favorite)) {
            3 -> return sendFavoriteVoice(talker, localId, favorite)
            19 -> sendFavoriteMiniProgram(talker, favorite)?.let { return it }
        }
        return sendNativeFavorite(talker, favorite)
    }

    private fun sendFavoriteVoice(talker: String, localId: Long, favorite: Any): Boolean {
        resolveFavoriteVoice(favorite)?.let { source ->
            return sendResolvedFavoriteVoice(talker, source)
        }
        val downloadStarted = FavoriteVoiceResolver.requestDownload(
            hostContext = hostContext,
            classLoader = hostClassLoader,
            dexKitBridge = dexKitBridge,
            favorite = favorite,
            logger = ::logFavoriteVoice
        )
        if (!downloadStarted) {
            log("发送收藏语音失败: 文件不存在且无法启动下载")
            return false
        }
        val tasks = WeChatApis.tasks() ?: return false
        val pendingKey = "$talker:$localId"
        if (!pendingVoiceSends.add(pendingKey)) return true
        tasks.runAsync {
            try {
                repeat(FAVORITE_VOICE_DOWNLOAD_POLLS) {
                    Thread.sleep(FAVORITE_VOICE_DOWNLOAD_POLL_MS)
                    val source = resolveFavoriteVoice(favorite) ?: return@repeat
                    if (!sendResolvedFavoriteVoice(talker, source)) {
                        log("发送收藏语音失败: 下载完成后语音发送未成功")
                    }
                    return@runAsync
                }
                log("发送收藏语音失败: 等待收藏语音下载超时")
            } finally {
                pendingVoiceSends.remove(pendingKey)
            }
        }
        return true
    }

    private fun resolveFavoriteVoice(favorite: Any): FavoriteVoiceResolver.VoiceSource? {
        return FavoriteVoiceResolver.resolve(
            hostContext = hostContext,
            classLoader = hostClassLoader,
            dexKitBridge = dexKitBridge,
            favorite = favorite,
            logger = ::logFavoriteVoice
        )
    }

    private fun sendResolvedFavoriteVoice(
        talker: String,
        source: FavoriteVoiceResolver.VoiceSource
    ): Boolean {
        if (!File(source.path).isFile) return false
        return runCatching {
            WeChatApis.media()?.voices()?.send(talker, source.path, source.durationMillis) == true
        }.getOrElse {
            log("发送收藏语音失败: ${it.message}")
            false
        }
    }

    private fun logFavoriteVoice(message: String, throwable: Throwable?) {
        log(if (throwable == null) message else "$message: ${throwable.message}")
    }

    private fun sendFavoriteMiniProgram(talker: String, favorite: Any): Boolean? {
        val xml = favoriteXml(favorite)
        val appBrand = xmlSection(xml, "appbranditem").ifBlank { return null }
        val userName = xmlValue(appBrand, "username").ifBlank { return null }
        val path = xmlValue(appBrand, "pagepath")
        val appId = xmlValue(appBrand, "appid")
        val title = xmlValue(xml, "desc", "datatitle", "title").ifBlank { "小程序" }
        val description = xmlValue(xml, "datadesc", "sourcedisplayname")
        val media = WeChatApis.media() ?: return null
        val sent = media.shareMiniProgram(
            talker,
            title.take(128),
            description.take(256),
            userName,
            path,
            ByteArray(0),
            appId
        )
        if (!sent) log("发送收藏小程序失败: 公共小程序 API 未成功")
        return sent
    }

    private fun sendNativeFavorite(talker: String, favorite: Any): Boolean {
        if (!ensureFavoriteSendReady()) {
            log("发送收藏失败: API 未就绪")
            return false
        }
        val method = dexFinder.favoriteSendMethod ?: return false
        val context = currentActivityApi.currentActivity() ?: hostContext
        return runCatching {
            invokeFavoriteSendMethod(method, context, talker, favorite)
            true
        }.getOrElse {
            log("发送收藏异常: ${it.message}")
            false
        }
    }

    private fun invokeFavoriteSendMethod(
        method: Method,
        context: Context,
        talker: String,
        favorite: Any
    ) {
        val params = method.parameterTypes
        if (params.size == 6) {
            KavaReflector.invokeOrThrow(method, null, context, talker, "", false, favorite, null)
            return
        }
        if (params.size == 5) {
            val favorites = LinkedList<Any>().apply { add(favorite) }
            KavaReflector.invokeOrThrow(method, null, context, talker, "", favorites, null)
            return
        }
        throw IllegalStateException("favorite send method unsupported: ${method.toGenericString()}")
    }

    private fun loadFavoriteNative(localId: Long): Any? {
        if (localId <= 0L) return null
        synchronized(nativeFavorites) { nativeFavorites[localId] }?.let { return it }
        val storage = favoriteStorage() ?: return null
        val itemClass = dexFinder.favoriteItemClass ?: return null
        val preferred = dexFinder.favoriteGetMethod
        val candidates = buildList {
            if (preferred != null) add(preferred)
            KavaReflector.declaredMethods(storage.javaClass)
                .filter { method ->
                    !KavaReflector.isStatic(method) &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0] == Long::class.javaPrimitiveType &&
                        itemClass.isAssignableFrom(method.returnType)
                }
                .filterNot { it == preferred }
                .forEach(::add)
        }
        for (method in candidates) {
            val favorite = runCatching { KavaReflector.invoke(method, storage, localId) }.getOrNull()
                ?: continue
            val returnedLocalId = firstNumberField(favorite, "field_localId", "localId")?.toLong()
            if (returnedLocalId != localId) continue
            cacheNativeFavorite(localId, favorite)
            return favorite
        }
        log("调用微信原生收藏读取失败: localId 未匹配")
        return null
    }

    private fun favoriteFromNative(
        nativeItem: Any?,
        tagNames: Map<Int, String> = emptyMap()
    ): WeChatFavoriteItem? {
        nativeItem ?: return null
        val localId = favoriteLocalId(nativeItem) ?: return null
        val type = favoriteType(nativeItem)
        val totalSize = firstNumberField(nativeItem, "field_datatotalsize", "datatotalsize", "totalSize")?.toLong() ?: 0L
        val updateTime = firstNumberField(nativeItem, "field_updateTime", "updateTime")?.toLong()
            ?.let { if (it in 1 until 10_000_000_000L) it * 1000L else it }
            ?: 0L
        val snippet = favoriteSnippet(nativeItem, type)
        val typeLabel = favoriteTypeLabel(type)
        return WeChatFavoriteItem(
            localId = localId,
            type = type,
            title = snippet.ifBlank { "${typeLabel}收藏" },
            summary = favoriteSourceSummary(nativeItem, typeLabel),
            totalSizeBytes = totalSize,
            updateTimeMillis = updateTime,
            tags = favoriteTags(nativeItem, tagNames)
        )
    }

    private fun favoriteLocalId(nativeItem: Any): Long? =
        firstNumberField(nativeItem, "field_localId", "localId", "id")?.toLong()

    private fun favoriteStorage(): Any? {
        dexFinder.resolveFavoriteApi()
        val resolver = dexFinder.favoriteServiceResolverMethod ?: return null
        val serviceClass = dexFinder.favoriteServiceClass ?: return null
        val getter = dexFinder.favoriteStorageGetterMethod ?: return null
        val service = KavaReflector.invoke(resolver, null, serviceClass) ?: return null
        return KavaReflector.invoke(getter, service)
    }

    private fun cacheNativeFavorite(localId: Long, favorite: Any) {
        synchronized(nativeFavorites) { nativeFavorites[localId] = favorite }
    }

    private fun ensureFavoriteItemReady(): Boolean {
        dexFinder.resolveFavoriteApi()
        return dexFinder.favoriteItemClass != null && dexFinder.favoriteGetMethod != null
    }

    private fun ensureFavoriteSendReady(): Boolean {
        dexFinder.resolveFavoriteApi()
        return ensureFavoriteItemReady() && dexFinder.favoriteSendMethod != null
    }

    private fun favoriteSnippet(nativeItem: Any, type: Int): String {
        val xml = favoriteXml(nativeItem)
        val proto = KavaReflector.readField(nativeItem, "field_favProto")
        if (type == 5 || type == 8 || type == 19) {
            xmlValue(xml, *favoriteTitleTags(type))
                .takeIf { it.isNotBlank() }
                ?.let { return it.take(MAX_FAVORITE_TITLE_LENGTH) }
        }
        favoriteProtocolTitle(nativeItem, type)
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        xmlValue(xml, *favoriteTitleTags(type))
            .takeIf { it.isNotBlank() }
            ?.let { return it.take(MAX_FAVORITE_TITLE_LENGTH) }
        if (type == 1) {
            readableStrings(proto)
                .filter(::isUserContent)
                .maxByOrNull { it.length }
                ?.let { return it.take(MAX_FAVORITE_TITLE_LENGTH) }
        }
        semanticXmlValues(xml).firstOrNull(::isUserContent)
            ?.let { return it.take(MAX_FAVORITE_TITLE_LENGTH) }
        val data = proto?.let(::firstCollectionItem)
        (readableStrings(data) + readableStrings(proto))
            .firstOrNull(::isUserContent)
            ?.let { return it.take(MAX_FAVORITE_TITLE_LENGTH) }
        if (type == 3) return "语音收藏"
        return ""
    }

    /** Mirrors the title fields used by WeChat's per-type favorite list adapters. */
    private fun favoriteProtocolTitle(nativeItem: Any, type: Int): String {
        val proto = KavaReflector.readField(nativeItem, "field_favProto") ?: return ""
        val data = favoriteDataItem(proto)
        val title = when (type) {
            1 -> nativeText(proto, "s")
            5 -> nativeText(KavaReflector.readField(proto, "j"), "d")
            6 -> nativeText(proto, "o").ifBlank {
                nativeText(KavaReflector.readField(proto, "h"), "o", "j")
            }
            7, 16, 21, 32, 33 -> nativeText(data, "d", "f")
            8, 19 -> nativeText(proto, "q").ifBlank { nativeText(data, "d", "f") }
            10, 11 -> nativeText(KavaReflector.readField(proto, "y"), "d", "f")
            12, 15 -> nativeText(KavaReflector.readField(proto, "C"), "d")
            14 -> nativeText(nativeItem, "field_fromUser").ifBlank {
                nativeText(proto, "q").ifBlank { nativeText(data, "d", "f") }
            }
            17, 18 -> nativeText(data, "d", "f")
            20 -> nativeText(KavaReflector.readField(proto, "M"), "v", "f")
            24 -> nativeText(KavaReflector.readField(proto, "P"), "j", "i", "g")
            else -> nativeText(data, "d", "f")
        }
        return sanitizeSnippet(title)
    }

    private fun favoriteDataItem(proto: Any): Any? {
        return (KavaReflector.readField(proto, "f") as? Collection<*>)
            ?.firstOrNull { it != null }
    }

    private fun nativeText(source: Any?, vararg names: String): String {
        source ?: return ""
        names.forEach { name ->
            val value = KavaReflector.readField(source, name) as? String
            if (!value.isNullOrBlank()) return value
        }
        return ""
    }

    private fun favoriteTitleTags(type: Int): Array<String> = when (type) {
        1 -> arrayOf("desc", "content", "title")
        5 -> arrayOf("pagetitle", "datatitle", "title", "pagedesc", "datadesc")
        6 -> arrayOf("label", "poiname", "locationname", "title", "desc")
        7 -> arrayOf("datatitle", "songname", "title", "datadesc", "desc")
        8 -> arrayOf("datatitle", "filename", "fileName", "title", "datadesc", "desc")
        19 -> arrayOf("datatitle", "title", "sourcedisplayname", "appname", "desc")
        else -> arrayOf("title", "desc", "datatitle", "description", "content", "filename", "fileName")
    }

    private fun firstCollectionItem(source: Any): Any? {
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!Collection::class.java.isAssignableFrom(field.type)) continue
                val item = (KavaReflector.readField(field, source) as? Collection<*>)
                    ?.firstOrNull { it != null }
                if (item != null) return item
            }
            current = current.superclass
        }
        return null
    }

    private fun favoriteXml(nativeItem: Any): String =
        firstStringField(nativeItem, "field_xml", "xml", "field_content", "content")

    private fun xmlSection(xml: String, tag: String): String {
        if (xml.isBlank()) return ""
        return Regex(
            "<$tag(?:\\s[^>]*)?>(.*?)</$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(xml)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun xmlValue(xml: String, vararg tags: String): String {
        for (tag in tags) {
            val raw = xmlSection(xml, tag).trim()
                .removePrefix("<![CDATA[")
                .removeSuffix("]]>")
            val decoded = decodeDisplayText(raw)
            if (decoded.isNotBlank()) return decoded
        }
        return ""
    }

    private fun favoriteTagNames(nativeItems: Collection<Any>): Map<Int, String> {
        val ids = LinkedHashSet<Int>()
        nativeItems.forEach { ids += favoriteTagIds(it) }
        if (ids.isEmpty()) return emptyMap()
        val database = WeChatApis.database() ?: return emptyMap()
        val names = LinkedHashMap<Int, String>()
        ids.chunked(MAX_FAVORITE_TAG_QUERY_SIZE).forEach { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val rows = runCatching {
                database.query(
                    "SELECT id, name FROM FavTagInfo WHERE id IN ($placeholders)",
                    batch.map(Int::toString).toTypedArray()
                )
            }.getOrDefault(emptyList())
            rows.forEach rowLoop@ { row ->
                val id = (row["id"] as? Number)?.toInt()
                    ?: row["id"]?.toString()?.toIntOrNull()
                    ?: return@rowLoop
                val name = row["name"]?.toString()?.let(::decodeDisplayText).orEmpty()
                if (name.isNotBlank() && isReadableLabel(name)) names[id] = name.take(MAX_FAVORITE_TAG_LENGTH)
            }
        }
        return names
    }

    private fun favoriteTags(nativeItem: Any, tagNames: Map<Int, String>): List<String> {
        val ids = favoriteTagIds(nativeItem)
        val resolved = ids.mapNotNull(tagNames::get)
        // The native adapter retains user-defined tag names in tagProto.e even when tag IDs exist.
        val userDefined = favoriteTagFallbackNames(nativeItem)
        val xmlTags = if (resolved.isEmpty() && userDefined.isEmpty()) {
            favoriteXmlTagNames(favoriteXml(nativeItem))
        } else {
            emptyList()
        }
        return (resolved + userDefined + xmlTags)
            .asSequence()
            .map(::decodeDisplayText)
            .filter(::isReadableLabel)
            .distinct()
            .take(MAX_FAVORITE_TAGS)
            .map { it.take(MAX_FAVORITE_TAG_LENGTH) }
            .toList()
    }

    private fun favoriteTagIds(nativeItem: Any): List<Int> {
        val tagProto = KavaReflector.readField(nativeItem, "field_tagProto") ?: return emptyList()
        val values = KavaReflector.readField(tagProto, "f") as? Collection<*> ?: return emptyList()
        return values.mapNotNull { (it as? Number)?.toInt() }.filter { it > 0 }
    }

    private fun favoriteTagFallbackNames(nativeItem: Any): List<String> {
        val tagProto = KavaReflector.readField(nativeItem, "field_tagProto") ?: return emptyList()
        return (KavaReflector.readField(tagProto, "e") as? Collection<*>)
            ?.filterIsInstance<String>()
            .orEmpty()
    }

    private fun favoriteXmlTagNames(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        return Regex(
            "<taglist(?:\\s[^>]*)?>(.*?)</taglist>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(xml).flatMap { section ->
            Regex(
                "<tag(?:\\s[^>]*)?>(.*?)</tag>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            ).findAll(section.groupValues[1]).map { it.groupValues[1] }
        }.map(::decodeDisplayText).toList()
    }

    private fun favoriteSourceSummary(nativeItem: Any, typeLabel: String): String {
        val raw = rawStrings(nativeItem)
        val groupId = raw.firstOrNull { it.endsWith("@chatroom") || it.endsWith("@im.chatroom") }
        val wxId = raw.firstOrNull { it.startsWith("wxid_") || it.startsWith("gh_") }
        val users = WeChatApis.contact().users()
        val parts = ArrayList<String>()
        parts += typeLabel
        if (typeLabel == "语音") {
            FavoriteVoiceResolver.durationMillis(nativeItem)?.let { parts += formatDuration(it) }
        }
        if (!groupId.isNullOrBlank()) {
            val groupName = cleanDisplayName(users?.displayName(groupId).orEmpty())
                .takeUnless { it == groupId }.orEmpty()
            parts += if (groupName.isBlank()) groupId else "$groupName（$groupId）"
        }
        if (!wxId.isNullOrBlank()) {
            val contactName = cleanDisplayName(users?.displayName(wxId).orEmpty())
                .takeUnless { it == wxId }.orEmpty()
            val roomName = if (groupId.isNullOrBlank()) "" else {
                cleanDisplayName(users?.displayNameInGroup(groupId, wxId).orEmpty())
                    .takeUnless { it == wxId }.orEmpty()
            }
            val names = listOf(contactName, roomName).filter { it.isNotBlank() }.distinct()
            parts += if (names.isEmpty()) wxId else "${names.joinToString(" / ")}（$wxId）"
        }
        return parts.distinct().joinToString(" · ")
    }

    private fun formatDuration(durationMillis: Int): String {
        val totalSeconds = ((durationMillis.coerceAtLeast(0) + 999) / 1000).coerceAtLeast(1)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            if (seconds == 0) "${minutes}分钟" else "${minutes}分${seconds}秒"
        } else {
            "${seconds}秒"
        }
    }

    private fun semanticXmlValues(xml: String): List<String> {
        if (xml.isBlank()) return emptyList()
        val tags = "title|desc|description|content|filename|fileName|appname|sourcename|locationname"
        return Regex("<($tags)(?:\\s[^>]*)?>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .map { sanitizeSnippet(it.groupValues[2]) }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun readableStrings(source: Any?): List<String> {
        if (source == null) return emptyList()
        val values = ArrayList<String>()
        collectReadableStrings(source, values, HashSet(), 0)
        return values.distinct()
    }

    private fun collectReadableStrings(
        source: Any?,
        values: MutableList<String>,
        visited: MutableSet<Any>,
        depth: Int
    ) {
        if (source == null || depth > 2 || !visited.add(source)) return
        when (source) {
            is String -> {
                sanitizeSnippet(source).takeIf { it.isNotEmpty() }?.let { values += it }
                return
            }
            is Collection<*> -> {
                source.forEach { collectReadableStrings(it, values, visited, depth + 1) }
                return
            }
            is Array<*> -> {
                source.forEach { collectReadableStrings(it, values, visited, depth + 1) }
                return
            }
        }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive) continue
                val value = KavaReflector.readField(field, source) ?: continue
                collectReadableStrings(value, values, visited, depth + 1)
            }
            current = current.superclass
        }
    }

    private fun rawStrings(source: Any?): List<String> {
        val values = ArrayList<String>()
        collectRawStrings(source, values, HashSet(), 0)
        return values.distinct()
    }

    private fun collectRawStrings(
        source: Any?,
        values: MutableList<String>,
        visited: MutableSet<Any>,
        depth: Int
    ) {
        if (source == null || depth > 3 || !visited.add(source)) return
        when (source) {
            is String -> {
                Regex("(?:wxid_[A-Za-z0-9_-]+|gh_[A-Za-z0-9_-]+|[A-Za-z0-9_-]+@(?:im\\.)?chatroom)")
                    .findAll(source)
                    .forEach { values += it.value }
                return
            }
            is Collection<*> -> source.forEach { collectRawStrings(it, values, visited, depth + 1) }
            is Array<*> -> source.forEach { collectRawStrings(it, values, visited, depth + 1) }
            else -> {
                val className = source.javaClass.name
                if (className.startsWith("java.") || className.startsWith("android.")) return
                var current: Class<*>? = source.javaClass
                while (current != null && current != Any::class.java) {
                    KavaReflector.declaredFields(current).forEach { field ->
                        if (!field.type.isPrimitive) collectRawStrings(KavaReflector.readField(field, source), values, visited, depth + 1)
                    }
                    current = current.superclass
                }
            }
        }
    }

    private fun sanitizeSnippet(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val value = decodeDisplayText(raw)
        if (value.isBlank()) return ""
        return value.take(MAX_FAVORITE_TITLE_LENGTH)
    }

    private fun isUserContent(value: String): Boolean {
        val text = value.trim()
        val lower = text.lowercase(Locale.US)
        if (text.isBlank() || text.length < 2) return false
        if (lower in setOf("silk", "amr", "mp3", "mp4", "jpg", "jpeg", "png", "gif", "htm", "html")) return false
        if (lower.startsWith("wxid_") || lower.endsWith("@chatroom")) return false
        if (lower.startsWith("content://") || text.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(text)) return false
        if (Regex("^[0-9a-f]{16,}$", RegexOption.IGNORE_CASE).matches(text.replace(" ", ""))) return false
        if (Regex("^[A-Za-z0-9_-]{24,}$").matches(text)) return false
        if (text.startsWith(".")) return false
        return true
    }

    private fun decodeDisplayText(raw: String): String {
        if (raw.isBlank()) return ""
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(raw).toString()
        }
        return decoded
            .replace('\uFFFC', ' ')
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanDisplayName(value: String): String {
        val clean = decodeDisplayText(value)
        return clean.takeIf(::isReadableLabel).orEmpty()
    }

    private fun isReadableLabel(value: String): Boolean {
        val clean = value.trim()
        return clean.isNotEmpty() && clean.any { it.isLetterOrDigit() }
    }

    private fun favoriteType(nativeItem: Any): Int {
        return (firstNumberField(nativeItem, "field_type", "type") ?: return 0).toInt()
    }

    private fun favoriteTypeLabel(type: Int): String = when (type) {
        1 -> "文字"
        2 -> "图片"
        3 -> "语音"
        4 -> "视频"
        5 -> "链接"
        6 -> "位置"
        7 -> "音乐"
        8 -> "文件"
        10 -> "笔记"
        14 -> "聊天记录"
        18 -> "笔记"
        19 -> "小程序"
        else -> "类型$type"
    }

    private fun firstNumberField(source: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(source, name)
            if (value is Number) return value
        }
        return null
    }

    private fun firstStringField(source: Any, vararg names: String): String {
        for (name in names) {
            val value = KavaReflector.readField(source, name) as? String
            if (!TextUtils.isEmpty(value)) return value.orEmpty()
        }
        return ""
    }

    private fun log(message: String) {
        logger?.log(message)
    }

    companion object {
        private const val FAVORITE_TYPE_ALL = -1
        private const val FAVORITE_PAGE_SIZE = 20
        private const val NATIVE_FAVORITE_CACHE_SIZE = 80
        private const val MAX_FAVORITE_TITLE_LENGTH = 160
        private const val MAX_FAVORITE_TAG_QUERY_SIZE = 500
        private const val MAX_FAVORITE_TAGS = 8
        private const val MAX_FAVORITE_TAG_LENGTH = 24
        private const val FAVORITE_VOICE_DOWNLOAD_POLLS = 120
        private const val FAVORITE_VOICE_DOWNLOAD_POLL_MS = 500L
    }
}
