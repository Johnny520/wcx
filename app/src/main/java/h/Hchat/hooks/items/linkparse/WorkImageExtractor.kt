package h.Hchat.hooks.items.linkparse

import java.net.URI
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class WorkImageSource(val candidates: List<String>)

/** Extracts ordered image candidates only from gallery shapes verified on public work pages. */
internal object WorkImageExtractor {
    const val MAX_IMAGES = 20
    private val json = Json { ignoreUnknownKeys = true }

    fun douyin(body: String, expectedId: String): List<WorkImageSource>? = safely {
        if (!expectedId.matches(Regex("[1-9][0-9]{15,19}"))) return@safely null
        val root = json.parseToJsonElement(body).obj() ?: return@safely null
        if (root.str("status_code") != "0") return@safely null
        val detail = root.obj("aweme_detail") ?: return@safely null
        if (detail.str("aweme_id") != expectedId || detail.str("aweme_type") != "68") return@safely null
        val images = detail.array("images") ?: return@safely null
        if (images.isEmpty() || images.size > MAX_IMAGES) return@safely null
        images.map { element ->
            val image = element.obj() ?: return@safely null
            // Douyin's download_url_list is a rendered/share copy with the account watermark.
            // url_list contains the original image variants; never fall back to the marked copy.
            val candidates = image.array("url_list")
                ?.asSequence()
                ?: return@safely null
            val safeCandidates = candidates
                .mapNotNull { it.prim()?.contentOrNull }
                .filter(::allowedDouyinImage)
                .distinct()
                .take(MAX_CANDIDATES_PER_IMAGE)
                .toList()
            if (safeCandidates.isEmpty()) return@safely null
            WorkImageSource(safeCandidates)
        }
    }

    fun xiaohongshu(html: String, expectedId: String): List<WorkImageSource>? = safely {
        if (!expectedId.matches(Regex("[a-fA-F0-9]{24}"))) return@safely null
        val root = parseState(html) ?: return@safely null
        val note = root.obj("noteData")?.obj("data")?.obj("noteData") ?: return@safely null
        if (!note.str("noteId").equals(expectedId, true) || note.str("type") != "normal") return@safely null
        val images = note.array("imageList") ?: return@safely null
        if (images.isEmpty() || images.size > MAX_IMAGES) return@safely null
        images.map { element ->
            val image = element.obj() ?: return@safely null
            val fileId = image.str("fileId") ?: return@safely null
            if (fileId.length > MAX_FILE_ID_CHARS || fileId.contains("..") ||
                !fileId.matches(Regex("[A-Za-z0-9/_-]+"))) return@safely null
            val url = "https://sns-img-qc.xhscdn.com/${fileId.trimStart('/')}?imageView2/2/w/1920/format/jpg"
            if (!allowedXhsImage(url)) return@safely null
            WorkImageSource(listOf(url))
        }
    }

    fun allowedDouyinImage(raw: String): Boolean = allowedImage(raw, "douyinpic.com")

    fun allowedXhsImage(raw: String): Boolean = allowedImage(raw, "xhscdn.com")

    private fun allowedImage(raw: String, domain: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        return host == domain || host.endsWith(".$domain")
    }

    private fun parseState(html: String): JsonObject? {
        if (html.length > MAX_PAGE_CHARS) return null
        val prefix = Regex("window\\.__INITIAL_STATE__\\s*=\\s*").find(html) ?: return null
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

    private const val MAX_PAGE_CHARS = 3_000_000
    private const val MAX_FILE_ID_CHARS = 512
    private const val MAX_CANDIDATES_PER_IMAGE = 6
}
