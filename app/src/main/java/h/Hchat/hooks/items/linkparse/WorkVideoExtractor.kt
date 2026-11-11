package h.Hchat.hooks.items.linkparse

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Extracts only the media shapes verified against public samples. */
internal object WorkVideoExtractor {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_PAGE_CHARS = 3_000_000
    private const val MAX_CANDIDATES = 4

    fun douyin(body: String, expectedId: String): String? = douyinCandidates(body, expectedId).firstOrNull()

    fun douyinCandidates(body: String, expectedId: String): List<String> = safely {
        if (!expectedId.matches(Regex("[1-9][0-9]{15,19}"))) return@safely null
        val root = json.parseToJsonElement(body).obj() ?: return@safely null
        if (root.str("status_code") != "0") return@safely null
        val detail = root.obj("aweme_detail") ?: return@safely null
        if (detail.str("aweme_id") != expectedId || detail.str("aweme_type") != "0") return@safely null
        if (!detail.array("images").isNullOrEmpty()) return@safely null
        val urls = detail.obj("video")?.obj("play_addr")?.array("url_list") ?: return@safely null
        urls.mapNotNull { it.prim()?.contentOrNull?.takeIf { u -> allowedMedia(u, WorkLinkMatcher.Platform.DOUYIN) } }.distinct().take(4)
    } ?: emptyList()

    fun xiaohongshu(html: String, expectedId: String): String? = xiaohongshuCandidates(html, expectedId).firstOrNull()

    /** H.264 share streams carry the Xiaohongshu logo and author watermark; only use H.265 streams. */
    fun xiaohongshuCandidates(html: String, expectedId: String): List<String> = safely {
        if (!expectedId.matches(Regex("[a-fA-F0-9]{24}"))) return@safely null
        val root = parseState(html, "__INITIAL_STATE__") ?: return@safely null
        val note = root.obj("noteData")?.obj("data")?.obj("noteData") ?: return@safely null
        if (!note.str("noteId").equals(expectedId, true) || note.str("type") != "video") return@safely null
        val streams = note.obj("video")?.obj("media")?.obj("stream")?.array("h265") ?: return@safely null
        streams.mapNotNull { it.obj()?.str("masterUrl")?.let(::secureXhsMedia) }.distinct().take(MAX_CANDIDATES)
    } ?: emptyList()

    private fun secureXhsMedia(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.userInfo != null || (host != "xhscdn.com" && !host.endsWith(".xhscdn.com"))) return null
        val secure = when {
            uri.scheme.equals("https", true) && (uri.port == -1 || uri.port == 443) -> raw
            uri.scheme.equals("http", true) && uri.port == -1 -> "https://" + raw.substring(raw.indexOf("://") + 3)
            else -> return null
        }
        return secure.takeIf { allowedMedia(it, WorkLinkMatcher.Platform.XIAOHONGSHU) }
    }

    fun allowedMedia(raw: String, platform: WorkLinkMatcher.Platform): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        fun domain(d: String) = host == d || host.endsWith(".$d")
        return when (platform) {
            WorkLinkMatcher.Platform.DOUYIN -> domain("douyinvod.com") || host == "www.douyin.com"
            WorkLinkMatcher.Platform.XIAOHONGSHU -> domain("xhscdn.com")
        }
    }

    private fun parseState(html: String, name: String): JsonObject? {
        if (html.length > MAX_PAGE_CHARS) return null
        val prefix = Regex("window\\." + Regex.escape(name) + "\\s*=\\s*").find(html) ?: return null
        val end = html.indexOf("</script>", prefix.range.last + 1, ignoreCase = true)
        if (end < 0) return null
        val raw = html.substring(prefix.range.last + 1, end).trim().removeSuffix(";").trim()
        return json.parseToJsonElement(replaceUndefinedOutsideStrings(raw)).obj()
    }

    private fun replaceUndefinedOutsideStrings(input: String): String {
        val out = StringBuilder(input.length)
        var quoted = false
        var escaped = false
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                out.append(c)
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                i++
            } else if (c == '"') {
                quoted = true
                out.append(c)
                i++
            } else if (input.startsWith("undefined", i) && (i == 0 || input[i - 1] in "[,: \t\r\n") &&
                (i + 9 == input.length || input[i + 9] in "]}, \t\r\n")) {
                out.append("null")
                i += 9
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private inline fun <T> safely(block: () -> T?): T? = runCatching(block).getOrNull()
    private fun kotlinx.serialization.json.JsonElement.obj() = this as? JsonObject
    private fun kotlinx.serialization.json.JsonElement.prim() = this as? JsonPrimitive
    private fun JsonObject.obj(key: String) = this[key]?.obj()
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.str(key: String) = this[key]?.prim()?.contentOrNull
}
