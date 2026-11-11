package h.Hchat.utils

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class KeywordReplacementRule(
    val keyword: String,
    val replacement: String
)

object KeywordReplacementRules {
    fun decode(raw: String?): List<KeywordReplacementRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { decode(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun decode(array: JSONArray?): List<KeywordReplacementRule> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val keyword = item.optString("keyword").trim()
                if (keyword.isEmpty()) continue
                add(KeywordReplacementRule(keyword, item.optString("replacement")))
            }
        }
    }

    fun encode(rules: List<KeywordReplacementRule>): String = encodeArray(rules).toString()

    fun encodeArray(rules: List<KeywordReplacementRule>): JSONArray {
        return JSONArray().apply {
            rules.forEach { rule ->
                val keyword = rule.keyword.trim()
                if (keyword.isNotEmpty()) {
                    put(
                        JSONObject()
                            .put("keyword", keyword)
                            .put("replacement", rule.replacement)
                    )
                }
            }
        }
    }

    fun apply(content: String, rules: List<KeywordReplacementRule>): String {
        if (content.isEmpty() || rules.isEmpty()) return content
        val normalized = LinkedHashMap<String, KeywordReplacementRule>()
        rules.forEach { rule ->
            val keyword = rule.keyword.trim()
            if (keyword.isNotEmpty()) {
                normalized[keyword.lowercase(Locale.ROOT)] = rule.copy(keyword = keyword)
            }
        }
        if (normalized.isEmpty()) return content
        val pattern = normalized.values
            .sortedByDescending { it.keyword.length }
            .joinToString("|") { Regex.escape(it.keyword) }
        return Regex(pattern, RegexOption.IGNORE_CASE).replace(content) { match ->
            normalized[match.value.lowercase(Locale.ROOT)]?.replacement ?: match.value
        }
    }
}
