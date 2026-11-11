package h.Hchat.hooks.items.linkparse

import okhttp3.HttpUrl

/** Keeps manual redirect following inside the selected platform while retaining only concrete work IDs. */
internal object WorkLinkRedirectPolicy {
    private val douyinId = Regex("^/(?:share/)?(?:video|note|slides)/([1-9][0-9]{15,19})/?$")
    private val xhsId = Regex("^/(?:explore|discovery/item)/([a-fA-F0-9]{24})/?$")

    fun allows(url: HttpUrl, platform: WorkLinkMatcher.Platform): Boolean {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        if (url.scheme != "http" && url.scheme != "https") return false
        if (url.scheme == "https" && url.port != 443 || url.scheme == "http" && url.port != 80) return false
        val host = url.host.lowercase()
        return when (platform) {
            WorkLinkMatcher.Platform.DOUYIN -> host in DOUYIN_HOSTS
            WorkLinkMatcher.Platform.XIAOHONGSHU -> host in XHS_HOSTS
        }
    }

    fun workId(url: HttpUrl, platform: WorkLinkMatcher.Platform): String? {
        val fromPath = when (platform) {
            WorkLinkMatcher.Platform.DOUYIN -> douyinId.matchEntire(url.encodedPath)?.groupValues?.getOrNull(1)
            WorkLinkMatcher.Platform.XIAOHONGSHU -> xhsId.matchEntire(url.encodedPath)?.groupValues?.getOrNull(1)
        }
        val fromQuery = when (platform) {
            WorkLinkMatcher.Platform.DOUYIN -> listOf("modal_id", "aweme_id", "item_id")
                .firstNotNullOfOrNull(url::queryParameter)?.takeIf { it.matches(Regex("[1-9][0-9]{15,19}")) }
            WorkLinkMatcher.Platform.XIAOHONGSHU -> listOf("noteId", "note_id")
                .firstNotNullOfOrNull(url::queryParameter)?.takeIf { it.matches(Regex("[a-fA-F0-9]{24}")) }
        }
        return fromPath ?: fromQuery
    }

    private val DOUYIN_HOSTS = setOf(
        "v.douyin.com", "www.douyin.com", "douyin.com", "m.douyin.com", "iesdouyin.com", "www.iesdouyin.com"
    )
    private val XHS_HOSTS = setOf(
        "xhslink.com", "www.xhslink.com", "xhslink.cn", "www.xhslink.cn", "xiaohongshu.com", "www.xiaohongshu.com"
    )
}
