package h.Hchat.hooks.items.linkparse

/** Keep gallery sending limited to platforms whose real WeChat image sends were accepted and completed. */
internal object LinkParseGallerySupport {
    fun isVerified(platform: WorkLinkMatcher.Platform): Boolean =
        platform == WorkLinkMatcher.Platform.XIAOHONGSHU || platform == WorkLinkMatcher.Platform.DOUYIN
}
