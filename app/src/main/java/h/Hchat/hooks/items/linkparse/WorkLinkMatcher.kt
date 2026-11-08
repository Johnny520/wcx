package h.Hchat.hooks.items.linkparse

import java.net.URI
import java.util.Locale

/** Recognizes possible work links; short links still require a verified work redirect. */
internal object WorkLinkMatcher {
    enum class Platform { DOUYIN, XIAOHONGSHU }
    data class Match(val platform: Platform, val url: String, val requiresRedirect: Boolean)

    private val urls = Regex("https?://[^\\s\\u0000-\\u001f<>\\\"'（）【】《》，。；：！？、]+", RegexOption.IGNORE_CASE)
    private val escapedScheme = Regex("(?i)(https?)\\\\://")
    private val trailingPunctuation = ".,;:!?，。；：！？、)]}）】》"
    private val douyinWork = Regex("^/(?:video|note)/[1-9][0-9]{15,19}/?$")
    private val douyinShare = Regex("^/share/(?:video|note)/[1-9][0-9]{15,19}/?$")
    private val shortCode = Regex("^/[A-Za-z0-9_-]{3,80}/?$")
    private val xhsShort = Regex("^/(?:a|o)/[A-Za-z0-9_-]{3,80}/?$")
    private val xhsWork = Regex("^/(?:explore|discovery/item)/[a-fA-F0-9]{24}/?$")

    fun find(text: String): Match? = urls.findAll(normalizeEscapedScheme(text)).firstNotNullOfOrNull { result ->
        parse(result.value.trimEnd(*trailingPunctuation.toCharArray()))
    }

    fun parse(raw: String): Match? {
        val normalized = normalizeEscapedScheme(raw)
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return null
        if (uri.userInfo != null || (uri.port != -1 && uri.port != 443 && uri.port != 80)) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.rawPath ?: return null
        if (path.contains('%') || path.contains("..") || uri.fragment != null) return null
        return when {
            host == "v.douyin.com" && shortCode.matches(path) -> Match(Platform.DOUYIN, normalized, true)
            host in setOf("www.douyin.com", "douyin.com") && douyinWork.matches(path) -> Match(Platform.DOUYIN, normalized, false)
            host in setOf("www.iesdouyin.com", "iesdouyin.com", "m.douyin.com") && douyinShare.matches(path) -> Match(Platform.DOUYIN, normalized, false)
            host in setOf("xhslink.com", "www.xhslink.com", "xhslink.cn", "www.xhslink.cn") && (xhsShort.matches(path) || shortCode.matches(path)) -> Match(Platform.XIAOHONGSHU, normalized, true)
            host in setOf("www.xiaohongshu.com", "xiaohongshu.com") && xhsWork.matches(path) -> Match(Platform.XIAOHONGSHU, normalized, false)
            else -> null
        }
    }

    private fun normalizeEscapedScheme(value: String): String = escapedScheme.replace(value) { match ->
        "${match.groupValues[1]}://"
    }
}
