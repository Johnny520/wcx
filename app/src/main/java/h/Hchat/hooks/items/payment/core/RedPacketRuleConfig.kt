package h.Hchat.hooks.items.payment.core

import h.Hchat.hooks.items.payment.detect.RedPacketParser
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

data class RedPacketReplyStep(
    val id: String,
    val mode: Int,
    val content: String,
    val delayMs: Long,
    val random: Boolean
) {
    fun nextDelayMillis(): Long {
        val fixed = delayMs.coerceAtLeast(0L)
        return if (random) fixed + Random.nextLong(0L, 2001L) else fixed
    }
}

data class RedPacketRuleTemplate(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val grabMode: Int,
    val delayMode: Int,
    val delayMs: Long,
    val randomMinMs: Long,
    val randomMaxMs: Long,
    val skipSelf: Boolean,
    val listMode: Int,
    val whitelist: String,
    val blacklist: String,
    val keywordMode: Int,
    val keywords: String,
    val quietEnabled: Boolean,
    val quietStartSecond: Int,
    val quietEndSecond: Int,
    val replyMode: Int,
    val replyText: String,
    val replyDelayMs: Long,
    val replyRandom: Boolean,
    val replySteps: List<RedPacketReplyStep>,
    val groupReplySteps: List<RedPacketReplyStep>?,
    val notificationConfigured: Boolean,
    val notifySystemEnabled: Boolean,
    val notifyToastEnabled: Boolean,
    val notifySoundEnabled: Boolean,
    val notifySoundMode: Int,
    val notifyVibrateEnabled: Boolean,
    val notifySoundUri: String,
    val notifyText: String,
    val notifyToastText: String,
    val notifyFailedSystemEnabled: Boolean,
    val notifyFailedToastEnabled: Boolean,
    val notifyFailedText: String,
    val notifyFailedToastText: String,
    val announceEnabled: Boolean,
    val announceText: String
)

data class RedPacketRuleBinding(
    val id: String,
    val targetId: String,
    val label: String,
    val enabled: Boolean,
    val templateId: String,
    val customRules: Boolean,
    val overrideRule: RedPacketRuleTemplate?
)

data class RedPacketEffectiveRule(
    val sourceName: String,
    val enabled: Boolean,
    val grabMode: Int,
    val delayMode: Int,
    val delayMs: Long,
    val randomMinMs: Long,
    val randomMaxMs: Long,
    val skipSelf: Boolean,
    val listMode: Int,
    val whitelist: String,
    val blacklist: String,
    val keywordMode: Int,
    val keywords: String,
    val quietEnabled: Boolean,
    val quietStartSecond: Int,
    val quietEndSecond: Int,
    val replyMode: Int,
    val replyText: String,
    val replyDelayMs: Long,
    val replyRandom: Boolean,
    val replySteps: List<RedPacketReplyStep>,
    val groupReplySteps: List<RedPacketReplyStep>,
    val notifySystemEnabled: Boolean,
    val notifyToastEnabled: Boolean,
    val notifySoundEnabled: Boolean,
    val notifySoundMode: Int,
    val notifyVibrateEnabled: Boolean,
    val notifySoundUri: String,
    val notifyText: String,
    val notifyToastText: String,
    val notifyFailedSystemEnabled: Boolean,
    val notifyFailedToastEnabled: Boolean,
    val notifyFailedText: String,
    val notifyFailedToastText: String,
    val announceEnabled: Boolean,
    val announceText: String
) {
    fun nextDelayMillis(): Long {
        if (delayMode != RedPacketRuleConfig.DELAY_RANDOM) return delayMs.coerceAtLeast(0L)
        val min = randomMinMs.coerceAtLeast(0L)
        val max = randomMaxMs.coerceAtLeast(min)
        if (max <= min) return min
        return Random.nextLong(min, max + 1L)
    }

    fun nextReplyDelayMillis(): Long {
        val fixed = replyDelayMs.coerceAtLeast(0L)
        return if (replyRandom) fixed + Random.nextLong(0L, 2001L) else fixed
    }

    fun isInQuietTime(): Boolean {
        if (!quietEnabled) return false
        val now = Calendar.getInstance()
        val second = now.get(Calendar.HOUR_OF_DAY) * 3600 +
            now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)
        val start = quietStartSecond.coerceIn(0, 86399)
        val end = quietEndSecond.coerceIn(0, 86399)
        return if (start <= end) {
            second in start..end
        } else {
            second >= start || second <= end
        }
    }
}

class RedPacketRuleResolver(private val settings: RedPacketSettings) {
    fun resolve(talker: String?, sender: String?): RedPacketEffectiveRule {
        val legacy = legacyRule(settings)
        val templates = settings.ruleTemplates()
        val bindings = settings.ruleBindings()
        if (templates.isEmpty() && bindings.isEmpty()) return legacy

        val binding = bindings.firstOrNull {
            RedPacketRuleConfig.targetMatches(it.targetId, talker, sender)
        }
        if (binding != null) {
            if (!binding.enabled) {
                return legacy.copy(
                    sourceName = binding.label.ifBlank { "适用聊天已关闭" },
                    enabled = false
                )
            }
            val rule = if (binding.customRules) {
                binding.overrideRule
            } else {
                templates.firstOrNull { it.id == binding.templateId }
            }
            if (rule != null) return rule.toEffective(binding.label.ifBlank { rule.name }, legacy)
            if (binding.templateId.isBlank()) {
                return defaultRule(settings, templates, legacy)
            }
            return legacy.copy(
                sourceName = binding.label.ifBlank { "未绑定模板" },
                enabled = false
            )
        }

        return defaultRule(settings, templates, legacy)
    }

    companion object {
        @JvmStatic
        fun defaultRule(
            settings: RedPacketSettings,
            templates: List<RedPacketRuleTemplate>,
            legacy: RedPacketEffectiveRule = legacyRule(settings)
        ): RedPacketEffectiveRule {
            val defaultId = settings.getString(RedPacketRuleConfig.KEY_DEFAULT_TEMPLATE_ID, "")
            val defaultTemplate = templates.firstOrNull { it.id == defaultId } ?: return legacy
            return defaultTemplate.toEffective(defaultTemplate.name.ifBlank { "默认规则" }, legacy)
        }

        @JvmStatic
        fun legacyRule(settings: RedPacketSettings): RedPacketEffectiveRule {
            val replyEnabled = settings.getBoolean(RedPacketSettings.KEY_REPLY_ENABLE, false)
            val replyMode = if (replyEnabled) {
                settings.getInt(RedPacketSettings.KEY_REPLY_TYPE, RedPacketRuleConfig.REPLY_TEXT)
            } else {
                RedPacketRuleConfig.REPLY_OFF
            }
            val replyDelay = if (settings.getBoolean(RedPacketSettings.KEY_REPLY_CUSTOM_ENABLE, false)) {
                val value = settings.getInt(RedPacketSettings.KEY_REPLY_DELAY_VALUE, 1).coerceAtLeast(0)
                val unit = settings.getInt(RedPacketSettings.KEY_REPLY_DELAY_UNIT, 1)
                if (unit == 1) value * 1000L else value.toLong()
            } else {
                0L
            }
            val replyText = if (RedPacketRuleConfig.replyUsesTemplateText(replyMode)) {
                settings.getString(
                    RedPacketSettings.KEY_REPLY_TEMPLATES,
                    settings.getString(RedPacketSettings.KEY_REPLY_TEXT, "谢谢老板")
                )
            } else {
                settings.getString(RedPacketSettings.KEY_REPLY_MEDIA_PATHS, "")
            }
            val storedSteps = settings.getString(RedPacketSettings.KEY_REPLY_ITEMS, "")
            val replySteps = if (replyEnabled && storedSteps.isNotBlank()) {
                RedPacketRuleConfig.parseReplySteps(storedSteps)
            } else if (replyEnabled) {
                RedPacketRuleConfig.legacyReplySteps(replyMode, replyText, replyDelay, settings.getBoolean(RedPacketSettings.KEY_REPLY_RANDOM, false))
            } else {
                emptyList()
            }
            val groupReplySteps = if (settings.contains(RedPacketSettings.KEY_REPLY_GROUP_ITEMS)) {
                RedPacketRuleConfig.parseReplySteps(
                    settings.getString(RedPacketSettings.KEY_REPLY_GROUP_ITEMS, "")
                )
            } else {
                replySteps
            }
            return RedPacketEffectiveRule(
                sourceName = "旧版全局设置",
                enabled = settings.isEnabled(),
                grabMode = settings.getInt(RedPacketSettings.KEY_GRAB_MODE, RedPacketSettings.DEFAULT_GRAB_MODE),
                delayMode = settings.getInt(
                    RedPacketSettings.KEY_DELAY_MODE,
                    if (settings.getDelayMillis() > 0L) RedPacketRuleConfig.DELAY_CUSTOM else RedPacketRuleConfig.DELAY_FIXED
                ),
                delayMs = settings.getDelayMillis(),
                randomMinMs = settings.getInt(RedPacketSettings.KEY_DELAY_RANDOM_MIN, 0).coerceAtLeast(0).toLong(),
                randomMaxMs = settings.getInt(RedPacketSettings.KEY_DELAY_RANDOM_MAX, 0).coerceAtLeast(0).toLong(),
                skipSelf = settings.getBoolean(RedPacketSettings.KEY_SKIP_SELF, false),
                listMode = settings.getInt(RedPacketSettings.KEY_MODE, 0),
                whitelist = settings.getString(RedPacketSettings.KEY_WHITELIST, ""),
                blacklist = settings.getString(RedPacketSettings.KEY_BLACKLIST, ""),
                keywordMode = settings.getInt(RedPacketSettings.KEY_KW_MODE, 0),
                keywords = settings.getString(RedPacketSettings.KEY_KEYWORDS, ""),
                quietEnabled = false,
                quietStartSecond = 0,
                quietEndSecond = 0,
                replyMode = replyMode,
                replyText = replyText,
                replyDelayMs = replyDelay,
                replyRandom = settings.getBoolean(RedPacketSettings.KEY_REPLY_RANDOM, false),
                replySteps = replySteps,
                groupReplySteps = groupReplySteps,
                notifySystemEnabled = settings.getBoolean(RedPacketSettings.KEY_NOTIFY_SYSTEM_ENABLE, false),
                notifyToastEnabled = settings.getBoolean(RedPacketSettings.KEY_NOTIFY_TOAST_ENABLE, false),
                notifySoundEnabled = settings.getBoolean(RedPacketSettings.KEY_NOTIFY_SOUND_ENABLE, false),
                notifySoundMode = settings.getInt(
                    RedPacketSettings.KEY_NOTIFY_SOUND_MODE,
                    RedPacketSettings.NOTIFY_SOUND_MODE_SYSTEM
                ),
                notifyVibrateEnabled = settings.getBoolean(RedPacketSettings.KEY_NOTIFY_VIBRATE_ENABLE, false),
                notifySoundUri = settings.getString(RedPacketSettings.KEY_NOTIFY_SOUND_URI, ""),
                notifyText = settings.getString(RedPacketSettings.KEY_NOTIFY_TEXT, "抢到红包 {amount} 元"),
                notifyToastText = settings.getString(
                    RedPacketSettings.KEY_NOTIFY_TOAST_TEXT,
                    settings.getString(RedPacketSettings.KEY_NOTIFY_TEXT, "抢到红包 {amount} 元")
                ),
                notifyFailedSystemEnabled = settings.getBoolean(RedPacketSettings.KEY_NOTIFY_FAILED_SYSTEM_ENABLE, false),
                notifyFailedToastEnabled = settings.getBoolean(RedPacketSettings.KEY_NOTIFY_FAILED_TOAST_ENABLE, false),
                notifyFailedText = settings.getString(RedPacketSettings.KEY_NOTIFY_FAILED_TEXT, "未抢到红包"),
                notifyFailedToastText = settings.getString(
                    RedPacketSettings.KEY_NOTIFY_FAILED_TOAST_TEXT,
                    settings.getString(RedPacketSettings.KEY_NOTIFY_FAILED_TEXT, "未抢到红包")
                ),
                announceEnabled = settings.getBoolean(RedPacketSettings.KEY_ANNOUNCE_ENABLE, false),
                announceText = settings.getString(RedPacketSettings.KEY_ANNOUNCE_TEXT, "抢到红包 {amount} 元")
            )
        }
    }
}

object RedPacketRuleConfig {
    const val KEY_TEMPLATES = "hb_rule_templates_v1"
    const val KEY_BINDINGS = "hb_rule_bindings_v1"
    const val KEY_DEFAULT_TEMPLATE_ID = "hb_rule_default_template_id"

    const val DELAY_FIXED = 0
    const val DELAY_RANDOM = 1
    const val DELAY_CUSTOM = 2

    const val REPLY_OFF = 0
    const val REPLY_TEXT = 1
    const val REPLY_AT_SENDER = 2
    const val REPLY_IMAGE = 3
    const val REPLY_VOICE = 4
    const val REPLY_VIDEO = 5
    const val REPLY_EMOJI = 6
    const val REPLY_FILE = 7
    const val REPLY_XML = 8
    const val REPLY_FAVORITE = 9

    @JvmStatic
    fun replyUsesTemplateText(replyMode: Int): Boolean {
        return replyMode == REPLY_TEXT || replyMode == REPLY_AT_SENDER
    }

    @JvmStatic
    fun parseReplySteps(value: String?): List<RedPacketReplyStep> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            parseReplyStepsArray(array)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun encodeReplySteps(steps: List<RedPacketReplyStep>): String {
        val array = JSONArray()
        steps.forEach { step ->
            if (step.mode == REPLY_OFF) return@forEach
            val obj = JSONObject()
            obj.put("id", step.id.ifBlank { System.currentTimeMillis().toString() })
            obj.put("mode", step.mode)
            obj.put("content", step.content)
            obj.put("delayMs", step.delayMs.coerceAtLeast(0L))
            obj.put("random", step.random)
            array.put(obj)
        }
        return array.toString()
    }

    @JvmStatic
    fun legacyReplySteps(
        replyMode: Int,
        replyText: String,
        replyDelayMs: Long,
        replyRandom: Boolean
    ): List<RedPacketReplyStep> {
        if (replyMode == REPLY_OFF) return emptyList()
        if (replyText.isBlank()) return emptyList()
        return listOf(
            RedPacketReplyStep(
                id = System.currentTimeMillis().toString(),
                mode = replyMode,
                content = replyText,
                delayMs = replyDelayMs.coerceAtLeast(0L),
                random = replyRandom
            )
        )
    }

    @JvmStatic
    fun parseTemplates(value: String?): List<RedPacketRuleTemplate> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    add(parseTemplate(obj, "模板 ${i + 1}"))
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun encodeTemplates(templates: List<RedPacketRuleTemplate>): String {
        val array = JSONArray()
        templates.forEach { array.put(templateJson(it)) }
        return array.toString()
    }

    @JvmStatic
    fun parseBindings(value: String?): List<RedPacketRuleBinding> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            val merged = linkedMapOf<String, RedPacketRuleBinding>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val targetId = obj.optString("targetId").trim()
                if (targetId.isEmpty()) continue
                val key = bindingKey(targetId)
                merged[key] = RedPacketRuleBinding(
                    id = key,
                    targetId = targetId,
                    label = obj.optString("label").ifBlank { targetId },
                    enabled = obj.optBoolean("enabled", false),
                    templateId = obj.optString("templateId"),
                    customRules = obj.optBoolean("customRules", false),
                    overrideRule = obj.optJSONObject("overrideRule")?.let {
                        parseTemplate(it, obj.optString("label").ifBlank { targetId })
                    }
                )
            }
            merged.values.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @JvmStatic
    fun encodeBindings(bindings: List<RedPacketRuleBinding>): String {
        val array = JSONArray()
        val merged = linkedMapOf<String, RedPacketRuleBinding>()
        bindings.forEach { binding ->
            val target = binding.targetId.trim()
            if (target.isNotEmpty()) merged[bindingKey(target)] = binding.copy(id = bindingKey(target), targetId = target)
        }
        merged.values.forEach { binding ->
            val obj = JSONObject()
            obj.put("id", bindingKey(binding.targetId))
            obj.put("targetId", binding.targetId)
            obj.put("label", binding.label)
            obj.put("enabled", binding.enabled)
            obj.put("templateId", binding.templateId)
            obj.put("customRules", binding.customRules)
            binding.overrideRule?.let { obj.put("overrideRule", templateJson(it)) }
            array.put(obj)
        }
        return array.toString()
    }

    @JvmStatic
    fun targetMatches(target: String?, talker: String?, sender: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val cleanTarget = target.trim()
        val cleanTalker = RedPacketParser.normalizeUsername(talker)
        if (cleanTalker.isNotBlank()) return cleanTarget == cleanTalker
        return cleanTarget == RedPacketParser.normalizeUsername(sender)
    }

    @JvmStatic
    fun bindingKey(targetId: String): String = targetId.trim()

    @JvmStatic
    fun splitTokens(value: String?): List<String> {
        return value.orEmpty()
            .split("|", ",", "，", "\n", "\r")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseTemplate(obj: JSONObject, fallbackName: String): RedPacketRuleTemplate {
        val replyMode = obj.optInt("replyMode", REPLY_OFF)
        val replyText = obj.optString("replyText", "谢谢老板")
        val replyDelayMs = obj.optLong("replyDelayMs", 0L)
        val replyRandom = obj.optBoolean("replyRandom", false)
        val replySteps = if (obj.has("replySteps")) {
            parseReplyStepsArray(obj.optJSONArray("replySteps"))
        } else {
            legacyReplySteps(replyMode, replyText, replyDelayMs, replyRandom)
        }
        val groupReplySteps = if (obj.has("groupReplySteps")) {
            parseReplyStepsArray(obj.optJSONArray("groupReplySteps"))
        } else {
            null
        }
        val hasNotificationConfig = obj.has("notificationConfigured")
            || obj.has("notifySystemEnabled")
            || obj.has("notifyToastEnabled")
            || obj.has("notifySoundEnabled")
            || obj.has("notifyVibrateEnabled")
            || obj.has("notifySoundUri")
            || obj.has("notifyText")
            || obj.has("notifyToastText")
            || obj.has("notifyFailedSystemEnabled")
            || obj.has("notifyFailedToastEnabled")
            || obj.has("notifyFailedText")
            || obj.has("notifyFailedToastText")
            || obj.has("announceEnabled")
            || obj.has("announceText")
        val notifyText = obj.optString("notifyText", "抢到红包 {amount} 元")
        val notifyFailedText = obj.optString("notifyFailedText", "未抢到红包")
        return RedPacketRuleTemplate(
            id = obj.optString("id").ifBlank { System.currentTimeMillis().toString() },
            name = obj.optString("name").ifBlank { fallbackName },
            enabled = obj.optBoolean("enabled", false),
            grabMode = obj.optInt("grabMode", RedPacketSettings.DEFAULT_GRAB_MODE),
            delayMode = obj.optInt("delayMode", DELAY_FIXED),
            delayMs = obj.optLong("delayMs", 0L),
            randomMinMs = obj.optLong("randomMinMs", 0L),
            randomMaxMs = obj.optLong("randomMaxMs", 0L),
            skipSelf = obj.optBoolean("skipSelf", false),
            listMode = obj.optInt("listMode", 0),
            whitelist = obj.optString("whitelist"),
            blacklist = obj.optString("blacklist"),
            keywordMode = obj.optInt("keywordMode", 0),
            keywords = obj.optString("keywords"),
            quietEnabled = obj.optBoolean("quietEnabled", false),
            quietStartSecond = if (obj.has("quietStartSecond")) {
                obj.optInt("quietStartSecond", 0)
            } else {
                obj.optInt("quietStartMinute", 0) * 60
            },
            quietEndSecond = if (obj.has("quietEndSecond")) {
                obj.optInt("quietEndSecond", 0)
            } else {
                obj.optInt("quietEndMinute", 0) * 60
            },
            replyMode = replyMode,
            replyText = replyText,
            replyDelayMs = replyDelayMs,
            replyRandom = replyRandom,
            replySteps = replySteps,
            groupReplySteps = groupReplySteps,
            notificationConfigured = obj.optBoolean("notificationConfigured", hasNotificationConfig),
            notifySystemEnabled = obj.optBoolean("notifySystemEnabled", false),
            notifyToastEnabled = obj.optBoolean("notifyToastEnabled", false),
            notifySoundEnabled = obj.optBoolean("notifySoundEnabled", false),
            notifySoundMode = obj.optInt("notifySoundMode", RedPacketSettings.NOTIFY_SOUND_MODE_SYSTEM),
            notifyVibrateEnabled = obj.optBoolean("notifyVibrateEnabled", false),
            notifySoundUri = obj.optString("notifySoundUri"),
            notifyText = notifyText,
            notifyToastText = obj.optString("notifyToastText", notifyText),
            notifyFailedSystemEnabled = obj.optBoolean("notifyFailedSystemEnabled", false),
            notifyFailedToastEnabled = obj.optBoolean("notifyFailedToastEnabled", false),
            notifyFailedText = notifyFailedText,
            notifyFailedToastText = obj.optString("notifyFailedToastText", notifyFailedText),
            announceEnabled = obj.optBoolean("announceEnabled", false),
            announceText = obj.optString("announceText", "抢到红包 {amount} 元")
        )
    }

    private fun parseReplyStepsArray(array: JSONArray?): List<RedPacketReplyStep> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val mode = obj.optInt("mode", REPLY_OFF)
                if (mode == REPLY_OFF) continue
                val content = obj.optString("content")
                if (content.isBlank()) continue
                add(
                    RedPacketReplyStep(
                        id = obj.optString("id").ifBlank { System.currentTimeMillis().toString() + "_$i" },
                        mode = mode,
                        content = content,
                        delayMs = obj.optLong("delayMs", 0L),
                        random = obj.optBoolean("random", false)
                    )
                )
            }
        }
    }

    private fun templateJson(template: RedPacketRuleTemplate): JSONObject {
        val obj = JSONObject()
        obj.put("id", template.id)
        obj.put("name", template.name)
        obj.put("enabled", template.enabled)
        obj.put("grabMode", template.grabMode)
        obj.put("delayMode", template.delayMode)
        obj.put("delayMs", template.delayMs)
        obj.put("randomMinMs", template.randomMinMs)
        obj.put("randomMaxMs", template.randomMaxMs)
        obj.put("skipSelf", template.skipSelf)
        obj.put("listMode", template.listMode)
        obj.put("whitelist", template.whitelist)
        obj.put("blacklist", template.blacklist)
        obj.put("keywordMode", template.keywordMode)
        obj.put("keywords", template.keywords)
        obj.put("quietEnabled", template.quietEnabled)
        obj.put("quietStartSecond", template.quietStartSecond)
        obj.put("quietEndSecond", template.quietEndSecond)
        obj.put("replyMode", template.replyMode)
        obj.put("replyText", template.replyText)
        obj.put("replyDelayMs", template.replyDelayMs)
        obj.put("replyRandom", template.replyRandom)
        obj.put("replySteps", JSONArray(encodeReplySteps(template.replySteps)))
        template.groupReplySteps?.let {
            obj.put("groupReplySteps", JSONArray(encodeReplySteps(it)))
        }
        obj.put("notificationConfigured", template.notificationConfigured)
        obj.put("notifySystemEnabled", template.notifySystemEnabled)
        obj.put("notifyToastEnabled", template.notifyToastEnabled)
        obj.put("notifySoundEnabled", template.notifySoundEnabled)
        obj.put("notifySoundMode", template.notifySoundMode)
        obj.put("notifyVibrateEnabled", template.notifyVibrateEnabled)
        obj.put("notifySoundUri", template.notifySoundUri)
        obj.put("notifyText", template.notifyText)
        obj.put("notifyToastText", template.notifyToastText)
        obj.put("notifyFailedSystemEnabled", template.notifyFailedSystemEnabled)
        obj.put("notifyFailedToastEnabled", template.notifyFailedToastEnabled)
        obj.put("notifyFailedText", template.notifyFailedText)
        obj.put("notifyFailedToastText", template.notifyFailedToastText)
        obj.put("announceEnabled", template.announceEnabled)
        obj.put("announceText", template.announceText)
        return obj
    }
}

private fun RedPacketRuleTemplate.toEffective(name: String, legacy: RedPacketEffectiveRule): RedPacketEffectiveRule {
    return RedPacketEffectiveRule(
        sourceName = name.ifBlank { this.name },
        enabled = enabled,
        grabMode = grabMode,
        delayMode = delayMode,
        delayMs = delayMs,
        randomMinMs = randomMinMs,
        randomMaxMs = randomMaxMs,
        skipSelf = skipSelf,
        listMode = 0,
        whitelist = "",
        blacklist = "",
        keywordMode = keywordMode,
        keywords = keywords,
        quietEnabled = quietEnabled,
        quietStartSecond = quietStartSecond,
        quietEndSecond = quietEndSecond,
        replyMode = replyMode,
        replyText = replyText,
        replyDelayMs = replyDelayMs,
        replyRandom = replyRandom,
        replySteps = replySteps,
        groupReplySteps = groupReplySteps ?: replySteps,
        notifySystemEnabled = if (notificationConfigured) notifySystemEnabled else legacy.notifySystemEnabled,
        notifyToastEnabled = if (notificationConfigured) notifyToastEnabled else legacy.notifyToastEnabled,
        notifySoundEnabled = if (notificationConfigured) notifySoundEnabled else legacy.notifySoundEnabled,
        notifySoundMode = if (notificationConfigured) notifySoundMode else legacy.notifySoundMode,
        notifyVibrateEnabled = if (notificationConfigured) notifyVibrateEnabled else legacy.notifyVibrateEnabled,
        notifySoundUri = if (notificationConfigured) notifySoundUri else legacy.notifySoundUri,
        notifyText = if (notificationConfigured) notifyText else legacy.notifyText,
        notifyToastText = if (notificationConfigured) notifyToastText else legacy.notifyToastText,
        notifyFailedSystemEnabled = if (notificationConfigured) notifyFailedSystemEnabled else legacy.notifyFailedSystemEnabled,
        notifyFailedToastEnabled = if (notificationConfigured) notifyFailedToastEnabled else legacy.notifyFailedToastEnabled,
        notifyFailedText = if (notificationConfigured) notifyFailedText else legacy.notifyFailedText,
        notifyFailedToastText = if (notificationConfigured) notifyFailedToastText else legacy.notifyFailedToastText,
        announceEnabled = if (notificationConfigured) announceEnabled else legacy.announceEnabled,
        announceText = if (notificationConfigured) announceText else legacy.announceText
    )
}
