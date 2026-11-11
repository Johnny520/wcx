package h.Hchat.hooks.items.musicorder

/** Selects a QQ Music result while keeping QQ's order for equally ranked matches. */
internal object QQMusicSongMatcher {
    fun selectIndex(query: String, titles: List<String>): Int {
        if (titles.isEmpty()) return -1
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return 0

        return titles.withIndex()
            .mapNotNull { (index, title) -> rank(index, normalizedQuery, title) }
            .minWithOrNull(compareBy<Ranked> { it.tier }.thenByDescending { it.similarity }.thenBy { it.index })
            ?.index ?: 0
    }

    private fun rank(index: Int, query: String, rawTitle: String): Ranked? {
        val title = normalize(rawTitle)
        if (title.isEmpty()) return null
        val baseTitle = normalize(stripVersionSuffix(rawTitle))
        val tier = when {
            title == query -> 0
            baseTitle == query -> 1
            title.startsWith(query) -> 2
            title.contains(query) -> 3
            else -> {
                val similarity = similarity(query, title)
                if (minOf(query.length, title.length) < MIN_FUZZY_LENGTH || similarity < MIN_FUZZY_SIMILARITY) return null
                return Ranked(index, 4, similarity)
            }
        }
        return Ranked(index, tier, 1.0)
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .filterNot(Char::isWhitespace)
        .filterNot { it in NORMALIZATION_PUNCTUATION }

    private fun stripVersionSuffix(value: String): String {
        var result = value.trim()
        while (true) {
            val match = VERSION_SUFFIX.find(result) ?: break
            result = result.substring(0, match.range.first).trimEnd()
        }
        return result
    }

    private fun similarity(left: String, right: String): Double {
        val previous = IntArray(right.length + 1) { it }
        val current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitution = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitution
                )
            }
            current.copyInto(previous)
        }
        return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
    }

    private data class Ranked(val index: Int, val tier: Int, val similarity: Double)

    private const val MIN_FUZZY_LENGTH = 5
    private const val MIN_FUZZY_SIMILARITY = 0.82
    private const val NORMALIZATION_PUNCTUATION = " \t\n\r　·・,，.。!！?？:：;；'\"“”‘’-—_()（）[]【】"
    private val VERSION_SUFFIX = Regex(
        "(?:\\s*[-—_]?\\s*(?:[（(](?:Live(?:\\s*版)?|现场版?|演唱会版|翻唱|Cover|伴奏|Instrumental|Remix(?:\\s*版)?|混音版?|DJ版|纯音乐版|合唱版)[）)]|\\[(?:Live|现场|翻唱|伴奏|Remix|Cover)]|【(?:Live|现场|翻唱|伴奏|Remix|Cover)】))$|(?:\\s+[-—_]?\\s*)(?:Live(?:\\s*版)?|现场版?|演唱会版|翻唱|Cover|伴奏|Instrumental|Remix(?:\\s*版)?|混音版?|DJ版|纯音乐版|合唱版)$",
        RegexOption.IGNORE_CASE
    )
}
