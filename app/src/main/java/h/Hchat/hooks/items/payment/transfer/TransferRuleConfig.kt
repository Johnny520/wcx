package h.Hchat.hooks.items.payment.transfer

import h.Hchat.hooks.items.payment.core.RedPacketReplyStep
import h.Hchat.hooks.items.payment.core.RedPacketRuleConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.random.Random

data class TransferRuleTemplate(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val delayMode: Int,
    val delayMs: Long,
    val randomMinMs: Long,
    val randomMaxMs: Long,
    val receiveAccount: String,
    val listMode: Int,
    val whitelist: String,
    val blacklist: String,
    val amountEnabled: Boolean,
    val amountCondition: Int,
    val amountValue: String,
    val amountAction: Int,
    val keywordMode: Int,
    val keywords: String,
    val quietEnabled: Boolean,
    val quietStartSecond: Int,
    val quietEndSecond: Int,
    val refundRejected: Boolean,
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
    val announceEnabled: Boolean,
    val announceText: String
)

data class TransferRuleBinding(
    val id: String,
    val targetId: String,
    val label: String,
    val enabled: Boolean,
    val templateId: String
)

data class TransferEffectiveRule(
    val sourceName: String,
    val enabled: Boolean,
    val delayMode: Int,
    val delayMs: Long,
    val randomMinMs: Long,
    val randomMaxMs: Long,
    val receiveAccount: String,
    val listMode: Int,
    val whitelist: String,
    val blacklist: String,
    val amountEnabled: Boolean,
    val amountCondition: Int,
    val amountValue: String,
    val amountAction: Int,
    val keywordMode: Int,
    val keywords: String,
    val quietEnabled: Boolean,
    val quietStartSecond: Int,
    val quietEndSecond: Int,
    val refundRejected: Boolean,
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
    val announceEnabled: Boolean,
    val announceText: String
) {
    fun nextDelayMillis(): Long {
        if (delayMode != TransferRuleConfig.DELAY_RANDOM) return delayMs.coerceAtLeast(0L)
        val min = randomMinMs.coerceAtLeast(0L)
        val max = randomMaxMs.coerceAtLeast(min)
        return if (max <= min) min else Random.nextLong(min, max + 1L)
    }

    fun isInQuietTime(): Boolean {
        if (!quietEnabled) return false
        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)
        val start = quietStartSecond.coerceIn(0, 86399)
        val end = quietEndSecond.coerceIn(0, 86399)
        return if (start <= end) current in start..end else current >= start || current <= end
    }

    fun replyStepsFor(group: Boolean): List<RedPacketReplyStep> {
        return if (group) groupReplySteps else replySteps
    }
}

class TransferRuleResolver(private val settings: AutoTransferSettings) {
    fun resolve(talker: String?, sender: String?): TransferEffectiveRule {
        val legacy = legacyRule(settings)
        val templates = settings.ruleTemplates()
        val binding = settings.ruleBindings().firstOrNull {
            TransferRuleConfig.targetMatches(it.targetId, talker, sender)
        }
        if (binding != null) {
            if (!binding.enabled) return legacy.copy(sourceName = binding.label, enabled = false)
            if (binding.templateId.isBlank()) return defaultRule(settings, templates, legacy)
            val template = templates.firstOrNull { it.id == binding.templateId }
                ?: return legacy.copy(sourceName = binding.label, enabled = false)
            return template.toEffective(binding.label, legacy)
        }
        return defaultRule(settings, templates, legacy)
    }

    companion object {
        fun defaultRule(
            settings: AutoTransferSettings,
            templates: List<TransferRuleTemplate>,
            legacy: TransferEffectiveRule = legacyRule(settings)
        ): TransferEffectiveRule {
            val id = settings.getString(TransferRuleConfig.KEY_DEFAULT_TEMPLATE_ID, "")
            val template = templates.firstOrNull { it.id == id } ?: return legacy
            return template.toEffective(template.name, legacy)
        }

        fun legacyRule(settings: AutoTransferSettings): TransferEffectiveRule {
            val replySteps = settings.getString(AutoTransferSettings.KEY_REPLY_ITEMS, "")
                .takeIf { it.isNotBlank() }
                ?.let(RedPacketRuleConfig::parseReplySteps)
                ?: if (settings.getBoolean(AutoTransferSettings.KEY_REPLY_ENABLE, false)) {
                    RedPacketRuleConfig.legacyReplySteps(
                        RedPacketRuleConfig.REPLY_TEXT,
                        settings.getString(AutoTransferSettings.KEY_REPLY_TEXT, "谢谢老板"),
                        1000L,
                        false
                    )
                } else emptyList()
            val groupReplySteps = if (settings.contains(AutoTransferSettings.KEY_REPLY_GROUP_ITEMS)) {
                RedPacketRuleConfig.parseReplySteps(
                    settings.getString(AutoTransferSettings.KEY_REPLY_GROUP_ITEMS, "")
                )
            } else {
                replySteps
            }
            return TransferEffectiveRule(
                sourceName = "旧版全局设置",
                enabled = settings.isEnabled(),
                delayMode = settings.getInt(AutoTransferSettings.KEY_DELAY_MODE, TransferRuleConfig.DELAY_CUSTOM),
                delayMs = settings.getLong(AutoTransferSettings.KEY_DELAY_MS, 0L),
                randomMinMs = settings.getLong(AutoTransferSettings.KEY_DELAY_RANDOM_MIN, 0L),
                randomMaxMs = settings.getLong(AutoTransferSettings.KEY_DELAY_RANDOM_MAX, 0L),
                receiveAccount = settings.getString(AutoTransferSettings.KEY_RECEIVE_ACCOUNT, TransferReceiveAccountStore.DEFAULT_KEY),
                listMode = settings.getInt(AutoTransferSettings.KEY_MODE, 0),
                whitelist = settings.getString(AutoTransferSettings.KEY_WHITELIST, ""),
                blacklist = settings.getString(AutoTransferSettings.KEY_BLACKLIST, ""),
                amountEnabled = settings.getBoolean(AutoTransferSettings.KEY_AMOUNT_ENABLE, false),
                amountCondition = settings.getInt(AutoTransferSettings.KEY_AMOUNT_COND, 1),
                amountValue = settings.getString(AutoTransferSettings.KEY_AMOUNT_VALUE, "0"),
                amountAction = settings.getInt(AutoTransferSettings.KEY_AMOUNT_ACTION, 0),
                keywordMode = settings.getInt(AutoTransferSettings.KEY_KEYWORD_MODE, 0),
                keywords = settings.getString(AutoTransferSettings.KEY_KEYWORDS, ""),
                quietEnabled = settings.getBoolean(AutoTransferSettings.KEY_QUIET_ENABLE, false),
                quietStartSecond = settings.getInt(AutoTransferSettings.KEY_QUIET_START_SECOND, 0),
                quietEndSecond = settings.getInt(AutoTransferSettings.KEY_QUIET_END_SECOND, 0),
                refundRejected = settings.getBoolean(AutoTransferSettings.KEY_REFUND_REJECTED, false),
                replySteps = replySteps,
                groupReplySteps = groupReplySteps,
                notifySystemEnabled = settings.getBoolean(AutoTransferSettings.KEY_NOTIFY_SYSTEM_ENABLE, false),
                notifyToastEnabled = settings.getBoolean(AutoTransferSettings.KEY_NOTIFY_TOAST_ENABLE, false),
                notifySoundEnabled = settings.getBoolean(AutoTransferSettings.KEY_NOTIFY_SOUND_ENABLE, false),
                notifySoundMode = settings.getInt(AutoTransferSettings.KEY_NOTIFY_SOUND_MODE, AutoTransferSettings.NOTIFY_SOUND_MODE_SYSTEM),
                notifyVibrateEnabled = settings.getBoolean(AutoTransferSettings.KEY_NOTIFY_VIBRATE_ENABLE, false),
                notifySoundUri = settings.getString(AutoTransferSettings.KEY_NOTIFY_SOUND_URI, ""),
                notifyText = settings.getString(AutoTransferSettings.KEY_NOTIFY_TEXT, "已收款 {amount} 元"),
                notifyToastText = settings.getString(AutoTransferSettings.KEY_NOTIFY_TOAST_TEXT, "已收款 {amount} 元"),
                announceEnabled = settings.getBoolean(AutoTransferSettings.KEY_ANNOUNCE_ENABLE, false),
                announceText = settings.getString(AutoTransferSettings.KEY_ANNOUNCE_TEXT, "收到转账 {amount} 元")
            )
        }
    }
}

object TransferRuleConfig {
    const val KEY_TEMPLATES = "transfer_rule_templates_v1"
    const val KEY_BINDINGS = "transfer_rule_bindings_v1"
    const val KEY_DEFAULT_TEMPLATE_ID = "transfer_rule_default_template_id"
    const val DELAY_FIXED = 0
    const val DELAY_RANDOM = 1
    const val DELAY_CUSTOM = 2

    fun parseTemplates(value: String?): List<TransferRuleTemplate> = runCatching {
        if (value.isNullOrBlank()) return emptyList()
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(parseTemplate(it, "模板 ${index + 1}")) }
            }
        }
    }.getOrDefault(emptyList())

    fun encodeTemplates(values: List<TransferRuleTemplate>): String = JSONArray().apply {
        values.forEach { put(templateJson(it)) }
    }.toString()

    fun parseBindings(value: String?): List<TransferRuleBinding> = runCatching {
        if (value.isNullOrBlank()) return emptyList()
        val array = JSONArray(value)
        val merged = linkedMapOf<String, TransferRuleBinding>()
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val targetId = obj.optString("targetId").trim()
            if (targetId.isBlank()) continue
            merged[targetId] = TransferRuleBinding(
                id = targetId,
                targetId = targetId,
                label = obj.optString("label").ifBlank { targetId },
                enabled = obj.optBoolean("enabled", false),
                templateId = obj.optString("templateId")
            )
        }
        merged.values.toList()
    }.getOrDefault(emptyList())

    fun encodeBindings(values: List<TransferRuleBinding>): String = JSONArray().apply {
        val merged = linkedMapOf<String, TransferRuleBinding>()
        values.forEach { if (it.targetId.isNotBlank()) merged[it.targetId.trim()] = it }
        merged.values.forEach { binding ->
            put(JSONObject().apply {
                put("id", binding.targetId.trim())
                put("targetId", binding.targetId.trim())
                put("label", binding.label)
                put("enabled", binding.enabled)
                put("templateId", binding.templateId)
            })
        }
    }.toString()

    fun targetMatches(target: String?, talker: String?, sender: String?): Boolean {
        if (target.isNullOrBlank()) return false
        val clean = target.trim()
        return clean == talker?.trim() || clean == sender?.trim()
    }

    private fun parseTemplate(obj: JSONObject, fallbackName: String): TransferRuleTemplate {
        val notifyText = obj.optString("notifyText", "已收款 {amount} 元")
        return TransferRuleTemplate(
            id = obj.optString("id").ifBlank { System.currentTimeMillis().toString() },
            name = obj.optString("name").ifBlank { fallbackName },
            enabled = obj.optBoolean("enabled", false),
            delayMode = obj.optInt("delayMode", DELAY_FIXED),
            delayMs = obj.optLong("delayMs", 0L),
            randomMinMs = obj.optLong("randomMinMs", 0L),
            randomMaxMs = obj.optLong("randomMaxMs", 0L),
            receiveAccount = obj.optString("receiveAccount", TransferReceiveAccountStore.DEFAULT_KEY),
            listMode = obj.optInt("listMode", 0),
            whitelist = obj.optString("whitelist"),
            blacklist = obj.optString("blacklist"),
            amountEnabled = obj.optBoolean("amountEnabled", false),
            amountCondition = obj.optInt("amountCondition", 1),
            amountValue = obj.optString("amountValue", "0"),
            amountAction = obj.optInt("amountAction", 0),
            keywordMode = obj.optInt("keywordMode", 0),
            keywords = obj.optString("keywords"),
            quietEnabled = obj.optBoolean("quietEnabled", false),
            quietStartSecond = obj.optInt("quietStartSecond", 0),
            quietEndSecond = obj.optInt("quietEndSecond", 0),
            refundRejected = obj.optBoolean("refundRejected", false),
            replySteps = RedPacketRuleConfig.parseReplySteps(obj.optJSONArray("replySteps")?.toString()),
            groupReplySteps = if (obj.has("groupReplySteps")) {
                RedPacketRuleConfig.parseReplySteps(obj.optJSONArray("groupReplySteps")?.toString())
            } else {
                null
            },
            notificationConfigured = obj.optBoolean("notificationConfigured", obj.has("notifySystemEnabled")),
            notifySystemEnabled = obj.optBoolean("notifySystemEnabled", false),
            notifyToastEnabled = obj.optBoolean("notifyToastEnabled", false),
            notifySoundEnabled = obj.optBoolean("notifySoundEnabled", false),
            notifySoundMode = obj.optInt("notifySoundMode", AutoTransferSettings.NOTIFY_SOUND_MODE_SYSTEM),
            notifyVibrateEnabled = obj.optBoolean("notifyVibrateEnabled", false),
            notifySoundUri = obj.optString("notifySoundUri"),
            notifyText = notifyText,
            notifyToastText = obj.optString("notifyToastText", notifyText),
            announceEnabled = obj.optBoolean("announceEnabled", false),
            announceText = obj.optString("announceText", "收到转账 {amount} 元")
        )
    }

    private fun templateJson(value: TransferRuleTemplate) = JSONObject().apply {
        put("id", value.id); put("name", value.name); put("enabled", value.enabled)
        put("delayMode", value.delayMode); put("delayMs", value.delayMs)
        put("randomMinMs", value.randomMinMs); put("randomMaxMs", value.randomMaxMs)
        put("receiveAccount", value.receiveAccount); put("listMode", value.listMode)
        put("whitelist", value.whitelist); put("blacklist", value.blacklist)
        put("amountEnabled", value.amountEnabled); put("amountCondition", value.amountCondition)
        put("amountValue", value.amountValue); put("amountAction", value.amountAction)
        put("keywordMode", value.keywordMode); put("keywords", value.keywords)
        put("quietEnabled", value.quietEnabled); put("quietStartSecond", value.quietStartSecond)
        put("quietEndSecond", value.quietEndSecond); put("refundRejected", value.refundRejected)
        put("replySteps", JSONArray(RedPacketRuleConfig.encodeReplySteps(value.replySteps)))
        value.groupReplySteps?.let {
            put("groupReplySteps", JSONArray(RedPacketRuleConfig.encodeReplySteps(it)))
        }
        put("notificationConfigured", true); put("notifySystemEnabled", value.notifySystemEnabled)
        put("notifyToastEnabled", value.notifyToastEnabled); put("notifySoundEnabled", value.notifySoundEnabled)
        put("notifySoundMode", value.notifySoundMode); put("notifyVibrateEnabled", value.notifyVibrateEnabled)
        put("notifySoundUri", value.notifySoundUri); put("notifyText", value.notifyText)
        put("notifyToastText", value.notifyToastText); put("announceEnabled", value.announceEnabled)
        put("announceText", value.announceText)
    }
}

private fun TransferRuleTemplate.toEffective(
    sourceName: String,
    legacy: TransferEffectiveRule
): TransferEffectiveRule = TransferEffectiveRule(
    sourceName = sourceName.ifBlank { name }, enabled = enabled,
    delayMode = delayMode, delayMs = delayMs, randomMinMs = randomMinMs, randomMaxMs = randomMaxMs,
    receiveAccount = receiveAccount, listMode = listMode, whitelist = whitelist, blacklist = blacklist,
    amountEnabled = amountEnabled, amountCondition = amountCondition, amountValue = amountValue,
    amountAction = amountAction, keywordMode = keywordMode, keywords = keywords,
    quietEnabled = quietEnabled, quietStartSecond = quietStartSecond, quietEndSecond = quietEndSecond,
    refundRejected = refundRejected, replySteps = replySteps,
    groupReplySteps = groupReplySteps ?: replySteps,
    notifySystemEnabled = if (notificationConfigured) notifySystemEnabled else legacy.notifySystemEnabled,
    notifyToastEnabled = if (notificationConfigured) notifyToastEnabled else legacy.notifyToastEnabled,
    notifySoundEnabled = if (notificationConfigured) notifySoundEnabled else legacy.notifySoundEnabled,
    notifySoundMode = if (notificationConfigured) notifySoundMode else legacy.notifySoundMode,
    notifyVibrateEnabled = if (notificationConfigured) notifyVibrateEnabled else legacy.notifyVibrateEnabled,
    notifySoundUri = if (notificationConfigured) notifySoundUri else legacy.notifySoundUri,
    notifyText = if (notificationConfigured) notifyText else legacy.notifyText,
    notifyToastText = if (notificationConfigured) notifyToastText else legacy.notifyToastText,
    announceEnabled = if (notificationConfigured) announceEnabled else legacy.announceEnabled,
    announceText = if (notificationConfigured) announceText else legacy.announceText
)
