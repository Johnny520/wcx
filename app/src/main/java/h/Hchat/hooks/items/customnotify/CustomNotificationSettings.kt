package h.Hchat.hooks.items.customnotify

import android.content.Context
import android.text.TextUtils
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class CustomNotificationSettings(context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun rules(): List<CustomNotificationRule> {
        return parseRules(getString(KEY_RULES, ""), legacyIgnoreWechatDoNotDisturb())
    }

    fun saveRules(rules: List<CustomNotificationRule>) {
        prefs?.edit()?.putString(KEY_RULES, encodeRules(rules))?.commit()
    }

    fun findRule(talker: String?): CustomNotificationRule? {
        if (talker.isNullOrBlank()) return null
        return rules().firstOrNull { it.enabled && it.talker == talker }
    }

    fun exactRule(talker: String?): CustomNotificationRule? {
        if (talker.isNullOrBlank()) return null
        return rules().firstOrNull { it.talker == talker }
    }

    fun defaultPrivateRule(): CustomNotificationRule {
        return parseDefaultRule(
            getString(KEY_DEFAULT_PRIVATE, ""),
            group = false,
            legacyIgnoreWechatDnd = legacyIgnoreWechatDoNotDisturb()
        )
    }

    fun defaultGroupRule(): CustomNotificationRule {
        return parseDefaultRule(
            getString(KEY_DEFAULT_GROUP, ""),
            group = true,
            legacyIgnoreWechatDnd = legacyIgnoreWechatDoNotDisturb()
        )
    }

    fun defaultOfficialRule(): CustomNotificationRule {
        return parseDefaultRule(
            getString(KEY_DEFAULT_OFFICIAL, ""),
            group = false,
            official = true,
            legacyIgnoreWechatDnd = legacyIgnoreWechatDoNotDisturb()
        )
    }

    fun saveDefaultPrivateRule(rule: CustomNotificationRule) {
        prefs?.edit()?.putString(KEY_DEFAULT_PRIVATE, encodeDefaultRule(rule, group = false))?.commit()
    }

    fun saveDefaultGroupRule(rule: CustomNotificationRule) {
        prefs?.edit()?.putString(KEY_DEFAULT_GROUP, encodeDefaultRule(rule, group = true))?.commit()
    }

    fun saveDefaultOfficialRule(rule: CustomNotificationRule) {
        prefs?.edit()?.putString(KEY_DEFAULT_OFFICIAL, encodeDefaultRule(rule, group = false, official = true))?.commit()
    }

    fun effectiveRule(talker: String?): CustomNotificationRule? {
        val normalized = talker?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (isSystemTalker(normalized)) return null
        exactRule(normalized)?.let { return it }
        val group = isGroupTalker(normalized)
        val official = isOfficialTalker(normalized)
        val template = when {
            group -> defaultGroupRule()
            official -> defaultOfficialRule()
            else -> defaultPrivateRule()
        }
        return template.copy(
            id = normalized,
            talker = normalized,
            label = normalized,
            group = group,
            official = official,
            onlyMembers = "",
            blockMembers = ""
        )
    }

    fun getBoolean(key: String, def: Boolean): Boolean {
        return runCatching { prefs?.getBoolean(key, def) ?: def }.getOrDefault(def)
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.commit()
    }

    private fun getString(key: String, def: String): String {
        return runCatching { prefs?.getString(key, def) ?: def }.getOrDefault(def)
    }

    private fun legacyIgnoreWechatDoNotDisturb(): Boolean {
        return getBoolean(LEGACY_KEY_IGNORE_WECHAT_DND, false)
    }

    companion object {
        const val PREFS_NAME = "Hchat_custom_notification"
        const val KEY_ENABLE = "custom_notification_enable"
        const val KEY_RULES = "custom_notification_rules"
        const val KEY_DEFAULT_PRIVATE = "custom_notification_default_private"
        const val KEY_DEFAULT_GROUP = "custom_notification_default_group"
        const val KEY_DEFAULT_OFFICIAL = "custom_notification_default_official"

        const val DEFAULT_ENABLE = false
        const val MODE_DND = 0
        const val MODE_NOTIFY = 1
        private const val DEFAULT_PRIVATE_TALKER = "__hchat_custom_notification_default_private__"
        private const val DEFAULT_GROUP_TALKER = "__hchat_custom_notification_default_group__"
        private const val DEFAULT_OFFICIAL_TALKER = "__hchat_custom_notification_default_official__"
        const val DEFAULT_MUTE_START = "23:00:00"
        const val DEFAULT_MUTE_END = "07:00:00"
        private const val LEGACY_KEY_IGNORE_WECHAT_DND = "custom_notification_ignore_wechat_dnd"

        @JvmStatic
        fun parseRules(value: String?): List<CustomNotificationRule> {
            return parseRules(value, legacyIgnoreWechatDnd = false)
        }

        private fun parseRules(value: String?, legacyIgnoreWechatDnd: Boolean): List<CustomNotificationRule> {
            if (value.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(value)
                val result = ArrayList<CustomNotificationRule>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val talker = obj.optString("talker").trim()
                    if (talker.isBlank()) continue
                    result += CustomNotificationRule(
                        id = obj.optString("id").ifBlank { talker },
                        talker = talker,
                        label = obj.optString("label").ifBlank { talker },
                        group = obj.optBoolean("group", talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")),
                        official = obj.optBoolean("official", false),
                        enabled = obj.optBoolean("enabled", true),
                        mode = obj.optInt("mode", MODE_NOTIFY),
                        vibrate = obj.optBoolean("vibrate", true),
                        sound = obj.optBoolean("sound", true),
                        markRead = obj.optBoolean("markRead", true),
                        quickReply = obj.optBoolean("quickReply", false),
                        quoteQuickReply = obj.optBoolean("quoteQuickReply", false),
                        mergeByTalker = obj.optBoolean("mergeByTalker", false),
                        showDetail = obj.optBoolean("showDetail", true),
                        ignoreWechatDnd = if (obj.has("ignoreWechatDnd")) {
                            obj.optBoolean("ignoreWechatDnd", false)
                        } else {
                            legacyIgnoreWechatDnd
                        },
                        muteEnable = obj.optBoolean("muteEnable", false),
                        muteStart = normalizeTime(obj.optString("muteStart"), DEFAULT_MUTE_START),
                        muteEnd = normalizeTime(obj.optString("muteEnd"), DEFAULT_MUTE_END),
                        ringtone = obj.optString("ringtone"),
                        blockAtAll = obj.optBoolean("blockAtAll", false),
                        blockAtMe = obj.optBoolean("blockAtMe", false),
                        onlyMembers = normalizeMemberRules(obj.optString("onlyMembers")),
                        blockMembers = normalizeMemberRules(obj.optString("blockMembers"))
                    )
                }
                result.distinctBy { it.talker }
            }.getOrDefault(emptyList())
        }

        @JvmStatic
        fun encodeRules(rules: List<CustomNotificationRule>): String {
            val array = JSONArray()
            rules.distinctBy { it.talker }.forEach { rule ->
                val obj = JSONObject()
                obj.put("id", rule.id.ifBlank { rule.talker })
                obj.put("talker", rule.talker)
                obj.put("label", rule.label)
                obj.put("group", rule.group)
                obj.put("official", rule.official)
                obj.put("enabled", rule.enabled)
                obj.put("mode", rule.mode)
                obj.put("vibrate", rule.vibrate)
                obj.put("sound", rule.sound)
                obj.put("markRead", rule.markRead)
                obj.put("quickReply", rule.quickReply)
                obj.put("quoteQuickReply", rule.quoteQuickReply)
                obj.put("mergeByTalker", rule.mergeByTalker)
                obj.put("showDetail", rule.showDetail)
                obj.put("ignoreWechatDnd", rule.ignoreWechatDnd)
                obj.put("muteEnable", rule.muteEnable)
                obj.put("muteStart", normalizeTime(rule.muteStart, DEFAULT_MUTE_START))
                obj.put("muteEnd", normalizeTime(rule.muteEnd, DEFAULT_MUTE_END))
                obj.put("ringtone", rule.ringtone)
                obj.put("blockAtAll", rule.blockAtAll)
                obj.put("blockAtMe", rule.blockAtMe)
                obj.put("onlyMembers", normalizeMemberRules(rule.onlyMembers))
                obj.put("blockMembers", normalizeMemberRules(rule.blockMembers))
                array.put(obj)
            }
            return array.toString()
        }

        @JvmStatic
        fun defaultRule(group: Boolean, official: Boolean = false): CustomNotificationRule {
            val talker = when {
                official -> DEFAULT_OFFICIAL_TALKER
                group -> DEFAULT_GROUP_TALKER
                else -> DEFAULT_PRIVATE_TALKER
            }
            val label = when {
                official -> "默认公众号通知"
                group -> "默认群聊通知"
                else -> "默认私聊通知"
            }
            return CustomNotificationRule(
                id = talker,
                talker = talker,
                label = label,
                group = group,
                official = official,
                enabled = false
            )
        }

        @JvmStatic
        fun isGroupTalker(talker: String?): Boolean {
            val value = talker.orEmpty()
            return value.endsWith("@chatroom") || value.endsWith("@im.chatroom")
        }

        @JvmStatic
        fun isOfficialTalker(talker: String?): Boolean {
            val value = talker.orEmpty()
            return value.startsWith("gh_") || value.endsWith("@app") || value == "newsapp"
        }

        @JvmStatic
        fun isSystemTalker(talker: String?): Boolean {
            return SYSTEM_TALKERS.contains(talker.orEmpty().trim())
        }

        private fun parseDefaultRule(
            value: String?,
            group: Boolean,
            official: Boolean = false,
            legacyIgnoreWechatDnd: Boolean = false
        ): CustomNotificationRule {
            if (value.isNullOrBlank()) {
                return defaultRule(group, official).copy(ignoreWechatDnd = legacyIgnoreWechatDnd)
            }
            return runCatching {
                val obj = JSONObject(value)
                val fallback = defaultRule(group, official)
                fallback.copy(
                    enabled = obj.optBoolean("enabled", fallback.enabled),
                    mode = obj.optInt("mode", MODE_NOTIFY),
                    vibrate = obj.optBoolean("vibrate", true),
                    sound = obj.optBoolean("sound", true),
                    markRead = obj.optBoolean("markRead", true),
                    quickReply = obj.optBoolean("quickReply", false),
                    quoteQuickReply = obj.optBoolean("quoteQuickReply", false),
                    mergeByTalker = obj.optBoolean("mergeByTalker", false),
                    showDetail = obj.optBoolean("showDetail", true),
                    ignoreWechatDnd = if (obj.has("ignoreWechatDnd")) {
                        obj.optBoolean("ignoreWechatDnd", false)
                    } else {
                        legacyIgnoreWechatDnd
                    },
                    muteEnable = obj.optBoolean("muteEnable", false),
                    muteStart = normalizeTime(obj.optString("muteStart"), DEFAULT_MUTE_START),
                    muteEnd = normalizeTime(obj.optString("muteEnd"), DEFAULT_MUTE_END),
                    ringtone = obj.optString("ringtone"),
                    blockAtAll = if (group) obj.optBoolean("blockAtAll", false) else false,
                    blockAtMe = if (group) obj.optBoolean("blockAtMe", false) else false,
                    onlyMembers = "",
                    blockMembers = ""
                )
            }.getOrDefault(defaultRule(group, official).copy(ignoreWechatDnd = legacyIgnoreWechatDnd))
        }

        private fun encodeDefaultRule(rule: CustomNotificationRule, group: Boolean, official: Boolean = false): String {
            val obj = JSONObject()
            obj.put("enabled", rule.enabled)
            obj.put("mode", rule.mode)
            obj.put("vibrate", rule.vibrate)
            obj.put("sound", rule.sound)
            obj.put("markRead", rule.markRead)
            obj.put("quickReply", rule.quickReply)
            obj.put("quoteQuickReply", rule.quoteQuickReply)
            obj.put("mergeByTalker", rule.mergeByTalker)
            obj.put("showDetail", rule.showDetail)
            obj.put("ignoreWechatDnd", rule.ignoreWechatDnd)
            obj.put("muteEnable", rule.muteEnable)
            obj.put("muteStart", normalizeTime(rule.muteStart, DEFAULT_MUTE_START))
            obj.put("muteEnd", normalizeTime(rule.muteEnd, DEFAULT_MUTE_END))
            obj.put("ringtone", rule.ringtone)
            obj.put("blockAtAll", group && !official && rule.blockAtAll)
            obj.put("blockAtMe", group && !official && rule.blockAtMe)
            return obj.toString()
        }

        @JvmStatic
        fun normalizeTime(value: String?, def: String): String {
            val seconds = parseTimeToSecond(value)
            if (seconds < 0) return def
            return "%02d:%02d:%02d".format(
                Locale.US,
                seconds / 3600,
                seconds / 60 % 60,
                seconds % 60
            )
        }

        @JvmStatic
        fun parseTimeToSecond(value: String?): Int {
            if (value.isNullOrBlank()) return -1
            val parts = value.trim().split(":")
            if (parts.size !in 2..3) return -1
            val hour = parts[0].toIntOrNull() ?: return -1
            val minute = parts[1].toIntOrNull() ?: return -1
            val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
            if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return -1
            return hour * 3600 + minute * 60 + second
        }

        private val SYSTEM_TALKERS = setOf(
            "filehelper",
            "fmessage",
            "tmessage",
            "qqmail",
            "weixin",
            "floatbottle",
            "medianote",
            "medianote@chatroom",
            "masssend",
            "feedsapp",
            "blogapp"
        )

        @JvmStatic
        fun splitMemberRules(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return value.replace('，', ',')
                .replace('；', ',')
                .replace(';', ',')
                .replace('\n', ',')
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        }

        @JvmStatic
        fun normalizeMemberRules(value: String?): String {
            return splitMemberRules(value).joinToString(",")
        }

        @JvmStatic
        fun memberRuleMatches(rawRule: String?, senderId: String?, senderName: String?, pureContent: String?): Boolean {
            val rules = splitMemberRules(rawRule)
            if (rules.isEmpty()) return false
            val sid = senderId.orEmpty().lowercase(Locale.US)
            val name = senderName.orEmpty().lowercase(Locale.US)
            val content = pureContent.orEmpty().lowercase(Locale.US)
            return rules.any { rule ->
                val key = rule.lowercase(Locale.US)
                (!TextUtils.isEmpty(sid) && (sid == key || sid.contains(key) || key.contains(sid))) ||
                    (!TextUtils.isEmpty(name) && (name == key || name.contains(key) || key.contains(name))) ||
                    (!TextUtils.isEmpty(content) && (content.startsWith("$key:") || content.startsWith("$key：")))
            }
        }
    }
}

data class CustomNotificationRule(
    val id: String,
    val talker: String,
    val label: String,
    val group: Boolean,
    val official: Boolean = false,
    val enabled: Boolean = true,
    val mode: Int = CustomNotificationSettings.MODE_NOTIFY,
    val vibrate: Boolean = true,
    val sound: Boolean = true,
    val markRead: Boolean = true,
    val quickReply: Boolean = false,
    val quoteQuickReply: Boolean = false,
    val mergeByTalker: Boolean = false,
    val showDetail: Boolean = true,
    val ignoreWechatDnd: Boolean = false,
    val muteEnable: Boolean = false,
    val muteStart: String = CustomNotificationSettings.DEFAULT_MUTE_START,
    val muteEnd: String = CustomNotificationSettings.DEFAULT_MUTE_END,
    val ringtone: String = "",
    val blockAtAll: Boolean = false,
    val blockAtMe: Boolean = false,
    val onlyMembers: String = "",
    val blockMembers: String = ""
)
