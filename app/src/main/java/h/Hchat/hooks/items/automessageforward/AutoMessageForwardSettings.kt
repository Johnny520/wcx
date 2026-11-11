package h.Hchat.hooks.items.automessageforward

import android.content.Context
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KeywordReplacementRule
import h.Hchat.utils.KeywordReplacementRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object AutoMessageForwardSettings {
    const val PREFS_NAME = "Hchat_auto_message_forward_config"
    const val KEY_ENABLED = "enabled"
    const val KEY_RULES = "rules_v1"

    const val DEFAULT_ENABLED = false
    val supportedKinds: Set<String> = linkedSetOf(
        WeChatMessageObserveApi.Kind.TEXT,
        WeChatMessageObserveApi.Kind.IMAGE,
        WeChatMessageObserveApi.Kind.VOICE,
        WeChatMessageObserveApi.Kind.VIDEO,
        WeChatMessageObserveApi.Kind.EMOJI,
        WeChatMessageObserveApi.Kind.QUOTE,
        WeChatMessageObserveApi.Kind.FILE,
        WeChatMessageObserveApi.Kind.LINK,
        WeChatMessageObserveApi.Kind.MUSIC,
        WeChatMessageObserveApi.Kind.APP,
        WeChatMessageObserveApi.Kind.LOCATION,
        WeChatMessageObserveApi.Kind.SHARE_CARD,
        WeChatMessageObserveApi.Kind.NOTE,
        WeChatMessageObserveApi.Kind.VIDEO_NUMBER_VIDEO
    )

    fun isEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun rules(context: Context): List<AutoMessageForwardRule> = parseRules(
        preferences(context).getString(KEY_RULES, "").orEmpty()
    )

    fun saveRules(context: Context, rules: List<AutoMessageForwardRule>) {
        preferences(context).edit().putString(KEY_RULES, encodeRules(rules)).apply()
    }

    fun newRule(index: Int): AutoMessageForwardRule = AutoMessageForwardRule(
        id = UUID.randomUUID().toString(),
        name = "转发规则 $index"
    )

    fun splitKeywords(value: String): List<String> = value
        .split('|', ',', '，', ';', '；', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)

    private fun parseRules(value: String): List<AutoMessageForwardRule> {
        if (value.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        AutoMessageForwardRule(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            name = item.optString("name", "转发规则 ${index + 1}"),
                            enabled = item.optBoolean("enabled", true),
                            forwardOwnMessages = item.optBoolean("forwardOwnMessages", false),
                            followSourceRecall = item.optBoolean("followSourceRecall", false),
                            sourceIds = stringSet(item.optJSONArray("sourceIds")),
                            sourceMemberIds = stringSet(item.optJSONArray("sourceMemberIds")),
                            targetIds = stringSet(item.optJSONArray("targetIds")),
                            messageKinds = stringSet(item.optJSONArray("messageKinds"))
                                .filterTo(linkedSetOf()) { it in supportedKinds }
                                .let { if (it.isEmpty()) supportedKinds else it },
                            delayEnabled = item.optBoolean(
                                "delayEnabled",
                                item.optLong("delaySeconds", 0L) > 0L
                            ),
                            delaySeconds = item.optLong("delaySeconds", 0L).coerceAtLeast(0L),
                            includeKeywordsEnabled = item.optBoolean(
                                "includeKeywordsEnabled",
                                item.optString("includeKeywords", "").isNotBlank()
                            ),
                            includeKeywords = item.optString("includeKeywords", ""),
                            excludeKeywordsEnabled = item.optBoolean(
                                "excludeKeywordsEnabled",
                                item.optString("excludeKeywords", "").isNotBlank()
                            ),
                            excludeKeywords = item.optString("excludeKeywords", ""),
                            replaceKeywordsEnabled = item.optBoolean("replaceKeywordsEnabled", false),
                            keywordReplacements = KeywordReplacementRules.decode(
                                item.optJSONArray("keywordReplacements")
                            )
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeRules(rules: List<AutoMessageForwardRule>): String {
        return JSONArray().apply {
            rules.forEach { rule ->
                put(JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("enabled", rule.enabled)
                    put("forwardOwnMessages", rule.forwardOwnMessages)
                    put("followSourceRecall", rule.followSourceRecall)
                    put("sourceIds", stringArray(rule.sourceIds))
                    put("sourceMemberIds", stringArray(rule.sourceMemberIds))
                    put("targetIds", stringArray(rule.targetIds))
                    put("messageKinds", stringArray(rule.messageKinds.filterTo(linkedSetOf()) { it in supportedKinds }))
                    put("delayEnabled", rule.delayEnabled)
                    put("delaySeconds", rule.delaySeconds.coerceAtLeast(0L))
                    put("includeKeywordsEnabled", rule.includeKeywordsEnabled)
                    put("includeKeywords", rule.includeKeywords)
                    put("excludeKeywordsEnabled", rule.excludeKeywordsEnabled)
                    put("excludeKeywords", rule.excludeKeywords)
                    put("replaceKeywordsEnabled", rule.replaceKeywordsEnabled)
                    put(
                        "keywordReplacements",
                        KeywordReplacementRules.encodeArray(rule.keywordReplacements)
                    )
                })
            }
        }.toString()
    }

    private fun stringSet(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    private fun stringArray(values: Collection<String>): JSONArray = JSONArray().apply {
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct().forEach { put(it) }
    }
}

data class AutoMessageForwardRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新规则",
    val enabled: Boolean = true,
    val forwardOwnMessages: Boolean = false,
    val followSourceRecall: Boolean = false,
    val sourceIds: Set<String> = emptySet(),
    val sourceMemberIds: Set<String> = emptySet(),
    val targetIds: Set<String> = emptySet(),
    val messageKinds: Set<String> = AutoMessageForwardSettings.supportedKinds,
    val delayEnabled: Boolean = false,
    val delaySeconds: Long = 0L,
    val includeKeywordsEnabled: Boolean = false,
    val includeKeywords: String = "",
    val excludeKeywordsEnabled: Boolean = false,
    val excludeKeywords: String = "",
    val replaceKeywordsEnabled: Boolean = false,
    val keywordReplacements: List<KeywordReplacementRule> = emptyList()
)
