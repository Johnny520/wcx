package h.Hchat.hooks.items.messageblock

import android.content.Context
import android.text.TextUtils
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject

class MessageBlockSettings(context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun getBoolean(key: String, def: Boolean): Boolean = try {
        prefs?.getBoolean(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun getString(key: String, def: String): String = try {
        prefs?.getString(key, def) ?: def
    } catch (_: Throwable) {
        def
    }

    fun isEnabled(): Boolean = getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun targetMatches(target: String?, talker: String?, sender: String?): Boolean {
        if (TextUtils.isEmpty(target) || TextUtils.isEmpty(talker) || TextUtils.isEmpty(sender)) return false
        val token = target.orEmpty().trim()
        if (isGroupMemberTarget(token, talker, sender)) return true
        if (parseGroupMemberToken(token) != null) return false
        return token == talker || token == sender
    }

    fun templates(): List<MessageBlockTemplate> {
        return parseTemplates(getString(KEY_TEMPLATES, ""))
    }

    fun bindings(): List<MessageBlockBinding> {
        val raw = getString(KEY_BINDINGS, "")
        val parsed = parseBindings(raw)
        if (raw.isNotBlank()) return parsed
        return legacyBindingsFromTemplates(templates())
    }

    fun quickBlockedTalkers(): Set<String> {
        return bindings().asSequence()
            .filter { it.targetType == TARGET_CONTACT && it.quickBlockAll }
            .map { it.targetId }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun setQuickBlockedTalkers(
        targets: Map<String, String>,
        blocked: Boolean
    ): MessageBlockBatchUpdateResult {
        val normalizedTargets = linkedMapOf<String, String>()
        targets.forEach { (rawId, rawLabel) ->
            val id = rawId.trim()
            if (id.isNotEmpty()) normalizedTargets[id] = rawLabel.trim().ifBlank { id }
        }
        if (normalizedTargets.isEmpty()) {
            return MessageBlockBatchUpdateResult(success = true, changed = 0, total = 0)
        }

        val merged = linkedMapOf<String, MessageBlockBinding>()
        bindings().forEach { binding ->
            merged[bindingKey(binding.targetType, binding.targetId)] = binding
        }
        var changed = 0
        normalizedTargets.forEach { (talker, label) ->
            val key = bindingKey(TARGET_CONTACT, talker)
            val old = merged[key]
            if (blocked) {
                if (old?.quickBlockAll == true) return@forEach
                merged[key] = old?.copy(
                    label = old.label.ifBlank { label },
                    quickBlockAll = true
                ) ?: MessageBlockBinding(
                    id = key,
                    targetType = TARGET_CONTACT,
                    targetId = talker,
                    label = label,
                    enabled = true,
                    action = ACTION_BLOCK,
                    templateIds = emptySet(),
                    quickBlockAll = true
                )
                changed++
            } else {
                if (old?.quickBlockAll != true) return@forEach
                val restored = old.copy(quickBlockAll = false)
                if (hasConfiguredRule(restored)) merged[key] = restored else merged.remove(key)
                changed++
            }
        }

        if (changed == 0) {
            return MessageBlockBatchUpdateResult(
                success = true,
                changed = 0,
                total = normalizedTargets.size
            )
        }
        val editor = prefs?.edit()
            ?: return MessageBlockBatchUpdateResult(false, 0, normalizedTargets.size)
        editor.putString(KEY_BINDINGS, encodeBindings(merged.values.toList()))
        if (blocked) editor.putBoolean(KEY_ENABLE, true)
        val success = runCatching { editor.commit() }.getOrDefault(false)
        return MessageBlockBatchUpdateResult(
            success = success,
            changed = if (success) changed else 0,
            total = normalizedTargets.size
        )
    }

    private fun hasConfiguredRule(binding: MessageBlockBinding): Boolean {
        return binding.templateIds.isNotEmpty() ||
            binding.customRules ||
            binding.typeAll ||
            binding.types.isNotEmpty() ||
            binding.textKeywords.isNotBlank()
    }

    fun defaultPrivateRule(): MessageBlockDefaultRule {
        return parseDefaultRule(getString(KEY_DEFAULT_PRIVATE, ""), group = false)
    }

    fun defaultGroupRule(): MessageBlockDefaultRule {
        return parseDefaultRule(getString(KEY_DEFAULT_GROUP, ""), group = true)
    }

    fun defaultOfficialRule(): MessageBlockDefaultRule {
        return parseDefaultRule(getString(KEY_DEFAULT_OFFICIAL, ""), group = false, official = true)
    }

    fun saveDefaultPrivateRule(rule: MessageBlockDefaultRule) {
        prefs?.edit()?.putString(KEY_DEFAULT_PRIVATE, encodeDefaultRule(rule, group = false))?.commit()
    }

    fun saveDefaultGroupRule(rule: MessageBlockDefaultRule) {
        prefs?.edit()?.putString(KEY_DEFAULT_GROUP, encodeDefaultRule(rule, group = true))?.commit()
    }

    fun saveDefaultOfficialRule(rule: MessageBlockDefaultRule) {
        prefs?.edit()?.putString(KEY_DEFAULT_OFFICIAL, encodeDefaultRule(rule, group = false, official = true))?.commit()
    }

    fun keywordMatches(content: String?, keywords: String): Boolean {
        val values = splitTokens(keywords)
        if (values.isEmpty()) return true
        val text = cleanTextContent(content)
        return values.any { text.contains(it, ignoreCase = true) }
    }

    fun targetListMatches(value: String?, talker: String?, sender: String?): Boolean {
        return splitTokens(value.orEmpty()).any { targetMatches(it, talker, sender) }
    }

    fun groupMemberListMatches(value: String?, talker: String?, sender: String?): Boolean {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(sender)) return false
        return splitTokens(value.orEmpty()).any { token ->
            isGroupMemberTarget(token, talker, sender)
        }
    }

    fun bindingMatches(binding: MessageBlockBinding, talker: String?, sender: String?): Boolean {
        if (TextUtils.isEmpty(talker) || TextUtils.isEmpty(sender)) return false
        return when (binding.targetType) {
            TARGET_GROUP_MEMBER -> isGroupMemberTarget(binding.targetId, talker, sender)
            else -> targetMatches(binding.targetId, talker, sender)
        }
    }

    private fun splitTokens(value: String): List<String> {
        return value.split("|", ",", "，", "\n", "\r")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun isGroupMemberTarget(token: String, talker: String?, sender: String?): Boolean {
        val pair = parseGroupMemberToken(token) ?: return false
        return pair.first == talker && pair.second == sender
    }

    private fun parseGroupMemberToken(token: String): Pair<String, String>? {
        val separators = charArrayOf('/', '#', ':', '：')
        val index = separators.map { token.indexOf(it) }
            .filter { it > 0 }
            .minOrNull()
            ?: return null
        val groupId = token.substring(0, index).trim()
        val memberId = token.substring(index + 1).trim()
        if (groupId.isEmpty() || memberId.isEmpty()) return null
        return groupId to memberId
    }

    private fun cleanTextContent(content: String?): String {
        val value = content.orEmpty()
        val prefixEnd = value.indexOf(":\n")
        return if (prefixEnd > 0) value.substring(prefixEnd + 2) else value
    }

    companion object {
        const val PREFS_NAME = "Hchat_message_block_config"

        const val KEY_ENABLE = "message_block_enable"
        const val KEY_TEMPLATES = "message_block_templates"
        const val KEY_BINDINGS = "message_block_bindings"
        const val KEY_DEFAULT_PRIVATE = "message_block_default_private"
        const val KEY_DEFAULT_GROUP = "message_block_default_group"
        const val KEY_DEFAULT_OFFICIAL = "message_block_default_official"

        const val MODE_TARGETS = 0
        const val MODE_ALL = 1

        const val TARGET_CONTACT = "contact"
        const val TARGET_GROUP_MEMBER = "group_member"
        const val ACTION_BLOCK = "block"
        const val ACTION_EXCLUDE = "exclude"

        const val DEFAULT_ENABLE = false

        const val TYPE_TEXT = "text"
        const val TYPE_QUOTE = "quote"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_VOICE = "voice"
        const val TYPE_LINK = "link"
        const val TYPE_MUSIC = "music"
        const val TYPE_MINI_PROGRAM = "mini_program"
        const val TYPE_CARD = "card"
        const val TYPE_EMOJI = "emoji"
        const val TYPE_RED_PACKET = "red_packet"
        const val TYPE_TRANSFER = "transfer"
        const val TYPE_VOIP = "voip"
        const val TYPE_LOCATION = "location"
        const val TYPE_SYSTEM = "system"
        const val TYPE_PAT = "pat"
        const val TYPE_VIDEO_NUMBER = "video_number"
        const val TYPE_UNKNOWN = "unknown"

        private val LEGACY_EXPLICIT_TYPES = setOf(
            TYPE_TEXT,
            TYPE_QUOTE,
            TYPE_IMAGE,
            TYPE_VIDEO,
            TYPE_VOICE,
            TYPE_LINK,
            TYPE_MUSIC,
            TYPE_MINI_PROGRAM,
            TYPE_CARD,
            TYPE_EMOJI,
            TYPE_RED_PACKET,
            TYPE_TRANSFER,
            TYPE_VOIP,
            TYPE_LOCATION,
            TYPE_SYSTEM,
            TYPE_PAT,
            TYPE_VIDEO_NUMBER
        )

        @JvmStatic
        fun parseTemplates(value: String?): List<MessageBlockTemplate> {
            if (value.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(value)
                val result = ArrayList<MessageBlockTemplate>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    result += MessageBlockTemplate(
                        id = obj.optString("id").ifBlank { System.currentTimeMillis().toString() + "_" + i },
                        name = obj.optString("name").ifBlank { "模板 ${i + 1}" },
                        enabled = obj.optBoolean("enabled", true),
                        mode = obj.optInt("mode", MODE_TARGETS),
                        targets = obj.optString("targets"),
                        targetGroupMembers = obj.optString("targetGroupMembers"),
                        excludes = obj.optString("excludes"),
                        excludeGroupMembers = obj.optString("excludeGroupMembers"),
                        typeAll = obj.optBoolean("typeAll", false),
                        types = parseTypeArray(obj.optJSONArray("types")),
                        textKeywords = obj.optString("textKeywords")
                    )
                }
                result
            } catch (_: Throwable) {
                emptyList()
            }
        }

        @JvmStatic
        fun encodeTemplates(templates: List<MessageBlockTemplate>): String {
            val array = JSONArray()
            templates.forEach { template ->
                val obj = JSONObject()
                obj.put("id", template.id)
                obj.put("name", template.name)
                obj.put("enabled", template.enabled)
                obj.put("mode", template.mode)
                obj.put("targets", template.targets)
                obj.put("targetGroupMembers", template.targetGroupMembers)
                obj.put("excludes", template.excludes)
                obj.put("excludeGroupMembers", template.excludeGroupMembers)
                obj.put("typeAll", template.typeAll)
                obj.put("types", JSONArray().also { typeArray ->
                    template.types.forEach { typeArray.put(it) }
                })
                obj.put("textKeywords", template.textKeywords)
                array.put(obj)
            }
            return array.toString()
        }

        @JvmStatic
        fun parseBindings(value: String?): List<MessageBlockBinding> {
            if (value.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(value)
                val merged = linkedMapOf<String, MessageBlockBinding>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val targetType = normalizeBindingType(obj.optString("targetType"))
                    val rawTargetId = obj.optString("targetId").trim()
                    val targetId = normalizeBindingTarget(targetType, rawTargetId) ?: continue
                    val action = obj.optString("action").ifBlank { ACTION_BLOCK }
                        .takeIf { it == ACTION_EXCLUDE } ?: ACTION_BLOCK
                    val templateIds = parseStringArray(obj.optJSONArray("templateIds")).ifEmpty {
                        obj.optString("templateId").trim().takeIf { it.isNotEmpty() }?.let { setOf(it) }
                            ?: emptySet()
                    }
                    val key = bindingKey(targetType, targetId)
                    val old = merged[key]
                    merged[key] = MessageBlockBinding(
                        id = key,
                        targetType = targetType,
                        targetId = targetId,
                        label = obj.optString("label").ifBlank { old?.label ?: targetId },
                        enabled = obj.optBoolean("enabled", old?.enabled ?: true),
                        action = action,
                        templateIds = (old?.templateIds.orEmpty() + templateIds).filter { it.isNotBlank() }.toSet(),
                        customRules = obj.optBoolean("customRules", old?.customRules ?: false),
                        typeAll = obj.optBoolean("typeAll", old?.typeAll ?: false),
                        types = old?.types.orEmpty() + parseTypeArray(obj.optJSONArray("types")),
                        textKeywords = obj.optString("textKeywords", old?.textKeywords.orEmpty()),
                        quickBlockAll = obj.optBoolean("quickBlockAll", old?.quickBlockAll ?: false)
                    )
                }
                merged.values.toList()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        @JvmStatic
        fun encodeBindings(bindings: List<MessageBlockBinding>): String {
            val array = JSONArray()
            mergeBindings(bindings).forEach { binding ->
                val obj = JSONObject()
                obj.put("id", binding.id.ifBlank { bindingKey(binding.targetType, binding.targetId) })
                obj.put("targetType", binding.targetType)
                obj.put("targetId", binding.targetId)
                obj.put("label", binding.label)
                obj.put("enabled", binding.enabled)
                obj.put("action", binding.action)
                obj.put("templateIds", JSONArray().also { ids ->
                    binding.templateIds.forEach { ids.put(it) }
                })
                obj.put("customRules", binding.customRules)
                obj.put("typeAll", binding.typeAll)
                obj.put("types", JSONArray().also { typeArray ->
                    binding.types.forEach { typeArray.put(it) }
                })
                obj.put("textKeywords", binding.textKeywords)
                obj.put("quickBlockAll", binding.quickBlockAll)
                array.put(obj)
            }
            return array.toString()
        }

        @JvmStatic
        fun defaultRule(group: Boolean, official: Boolean = false): MessageBlockDefaultRule {
            val label = when {
                official -> "默认公众号规则"
                group -> "默认群聊规则"
                else -> "默认私聊规则"
            }
            return MessageBlockDefaultRule(
                group = group,
                official = official,
                label = label
            )
        }

        @JvmStatic
        fun parseDefaultRule(value: String?, group: Boolean, official: Boolean = false): MessageBlockDefaultRule {
            if (value.isNullOrBlank()) return defaultRule(group, official)
            return try {
                val obj = JSONObject(value)
                defaultRule(group, official).copy(
                    enabled = obj.optBoolean("enabled", false),
                    templateIds = parseStringArray(obj.optJSONArray("templateIds")).ifEmpty {
                        obj.optString("templateId").trim().takeIf { it.isNotEmpty() }?.let { setOf(it) }
                            ?: emptySet()
                    },
                    customRules = obj.optBoolean("customRules", false),
                    typeAll = obj.optBoolean("typeAll", false),
                    types = parseTypeArray(obj.optJSONArray("types")),
                    textKeywords = obj.optString("textKeywords")
                )
            } catch (_: Throwable) {
                defaultRule(group, official)
            }
        }

        @JvmStatic
        fun encodeDefaultRule(rule: MessageBlockDefaultRule, group: Boolean, official: Boolean = false): String {
            val label = when {
                official -> "默认公众号规则"
                group -> "默认群聊规则"
                else -> "默认私聊规则"
            }
            val normalized = rule.copy(group = group, official = official, label = label)
            val obj = JSONObject()
            obj.put("enabled", normalized.enabled)
            obj.put("templateIds", JSONArray().also { ids ->
                normalized.templateIds.forEach { ids.put(it) }
            })
            obj.put("customRules", normalized.customRules)
            obj.put("typeAll", normalized.typeAll)
            obj.put("types", JSONArray().also { typeArray ->
                normalized.types.forEach { typeArray.put(it) }
            })
            obj.put("textKeywords", normalized.textKeywords)
            return obj.toString()
        }

        @JvmStatic
        fun legacyBindingsFromTemplates(templates: List<MessageBlockTemplate>): List<MessageBlockBinding> {
            val merged = linkedMapOf<String, MessageBlockBinding>()
            fun add(targetType: String, targetId: String, action: String, template: MessageBlockTemplate) {
                val cleanType = normalizeBindingType(targetType)
                val cleanTarget = normalizeBindingTarget(cleanType, targetId) ?: return
                val key = bindingKey(cleanType, cleanTarget)
                val old = merged[key]
                merged[key] = MessageBlockBinding(
                    id = old?.id ?: key,
                    targetType = cleanType,
                    targetId = cleanTarget,
                    label = old?.label ?: cleanTarget,
                    enabled = old?.enabled ?: true,
                    action = action,
                    templateIds = old?.templateIds.orEmpty() + template.id
                )
            }
            templates.forEach { template ->
                splitListTokens(template.targets).forEach { token ->
                    val type = if (normalizeGroupMemberTarget(token) != null) TARGET_GROUP_MEMBER else TARGET_CONTACT
                    add(type, token, ACTION_BLOCK, template)
                }
                splitListTokens(template.targetGroupMembers).forEach { token ->
                    add(TARGET_GROUP_MEMBER, token, ACTION_BLOCK, template)
                }
                splitListTokens(template.excludes).forEach { token ->
                    val type = if (normalizeGroupMemberTarget(token) != null) TARGET_GROUP_MEMBER else TARGET_CONTACT
                    add(type, token, ACTION_EXCLUDE, template)
                }
                splitListTokens(template.excludeGroupMembers).forEach { token ->
                    add(TARGET_GROUP_MEMBER, token, ACTION_EXCLUDE, template)
                }
            }
            return merged.values.toList()
        }

        @JvmStatic
        fun clearTemplateTargets(template: MessageBlockTemplate): MessageBlockTemplate {
            return template.copy(
                mode = MODE_TARGETS,
                targets = "",
                targetGroupMembers = "",
                excludes = "",
                excludeGroupMembers = ""
            )
        }

        @JvmStatic
        fun bindingKey(targetType: String, targetId: String): String {
            return "${normalizeBindingType(targetType)}|${targetId.trim()}"
        }

        private fun parseTypeArray(array: JSONArray?): Set<String> {
            if (array == null) return emptySet()
            val result = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) result += value
            }
            if (result.containsAll(LEGACY_EXPLICIT_TYPES)) result += TYPE_UNKNOWN
            return result
        }

        private fun parseStringArray(array: JSONArray?): Set<String> {
            if (array == null) return emptySet()
            val result = LinkedHashSet<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) result += value
            }
            return result
        }

        private fun mergeBindings(bindings: List<MessageBlockBinding>): List<MessageBlockBinding> {
            val merged = linkedMapOf<String, MessageBlockBinding>()
            bindings.forEach { binding ->
                val targetType = normalizeBindingType(binding.targetType)
                val targetId = normalizeBindingTarget(targetType, binding.targetId) ?: return@forEach
                val action = if (binding.action == ACTION_EXCLUDE) ACTION_EXCLUDE else ACTION_BLOCK
                val key = bindingKey(targetType, targetId)
                val old = merged[key]
                merged[key] = MessageBlockBinding(
                    id = key,
                    targetType = targetType,
                    targetId = targetId,
                    label = binding.label.ifBlank { old?.label ?: targetId },
                    enabled = binding.enabled,
                    action = action,
                    templateIds = old?.templateIds.orEmpty() + binding.templateIds.filter { it.isNotBlank() },
                    customRules = binding.customRules || old?.customRules == true,
                    typeAll = binding.typeAll || old?.typeAll == true,
                    types = old?.types.orEmpty() + binding.types.filter { it.isNotBlank() },
                    textKeywords = binding.textKeywords.ifBlank { old?.textKeywords.orEmpty() },
                    quickBlockAll = binding.quickBlockAll || old?.quickBlockAll == true
                )
            }
            return merged.values.toList()
        }

        private fun splitListTokens(value: String): List<String> {
            return value.split("|", ",", "，", "\n", "\r")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun normalizeBindingTarget(targetType: String, targetId: String): String? {
            val clean = targetId.trim()
            if (clean.isEmpty()) return null
            if (targetType == TARGET_GROUP_MEMBER) return normalizeGroupMemberTarget(clean)
            return clean
        }

        private fun normalizeBindingType(targetType: String): String {
            return if (targetType == TARGET_GROUP_MEMBER) TARGET_GROUP_MEMBER else TARGET_CONTACT
        }

        private fun normalizeGroupMemberTarget(value: String): String? {
            val token = value.trim()
            val separators = charArrayOf('/', '#', ':', '：')
            val index = separators.map { token.indexOf(it) }
                .filter { it > 0 }
                .minOrNull()
                ?: return null
            val groupId = token.substring(0, index).trim()
            val memberId = token.substring(index + 1).trim()
            if (groupId.isEmpty() || memberId.isEmpty()) return null
            return "$groupId/$memberId"
        }
    }
}

data class MessageBlockTemplate(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val mode: Int,
    val targets: String,
    val targetGroupMembers: String,
    val excludes: String,
    val excludeGroupMembers: String,
    val typeAll: Boolean,
    val types: Set<String>,
    val textKeywords: String
)

data class MessageBlockBinding(
    val id: String,
    val targetType: String,
    val targetId: String,
    val label: String,
    val enabled: Boolean,
    val action: String,
    val templateIds: Set<String>,
    val customRules: Boolean = false,
    val typeAll: Boolean = false,
    val types: Set<String> = emptySet(),
    val textKeywords: String = "",
    val quickBlockAll: Boolean = false
)

data class MessageBlockBatchUpdateResult(
    val success: Boolean,
    val changed: Int,
    val total: Int
)

data class MessageBlockDefaultRule(
    val group: Boolean,
    val label: String,
    val official: Boolean = false,
    val enabled: Boolean = false,
    val templateIds: Set<String> = emptySet(),
    val customRules: Boolean = false,
    val typeAll: Boolean = false,
    val types: Set<String> = emptySet(),
    val textKeywords: String = ""
)
