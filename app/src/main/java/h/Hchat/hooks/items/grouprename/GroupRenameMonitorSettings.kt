package h.Hchat.hooks.items.grouprename

import org.json.JSONArray
import org.json.JSONObject

object GroupRenameMonitorSettings {
    const val PREFS_NAME = "Hchat_group_rename_monitor_config"

    const val KEY_NOTICE_ENABLE = "group_rename_notice_enable"
    const val KEY_NOTICE_TEXT = "group_rename_notice_text"
    const val KEY_NOTICE_SCOPE = "group_rename_notice_scope"
    const val KEY_NOTICE_GROUPS = "group_rename_notice_groups"
    const val KEY_SEND_ENABLE = "group_rename_send_enable"
    const val KEY_LISTEN_GROUPS = "group_rename_listen_groups"
    const val KEY_DELAY_SECONDS = "group_rename_delay_seconds"
    const val KEY_PROMPT_TYPE = "group_rename_prompt_type"
    const val KEY_BOTH_ORDER = "group_rename_both_order"
    const val KEY_TEXT = "group_rename_text"
    const val KEY_CARD_TITLE = "group_rename_card_title"
    const val KEY_CARD_DESC = "group_rename_card_desc"
    const val KEY_WXID_COLOR = "group_rename_wxid_color"
    const val KEY_TEMPLATES = "group_rename_templates"
    const val KEY_TEMPLATE_BINDINGS = "group_rename_template_bindings"

    const val PROMPT_TEXT = "text"
    const val PROMPT_CARD = "card"
    const val PROMPT_BOTH = "both"
    const val MODE_GLOBAL = "global"
    const val NOTICE_SCOPE_ALL = "all"
    const val NOTICE_SCOPE_SPECIFIC = "specific"
    const val BOTH_TEXT_FIRST = "text_first"
    const val BOTH_CARD_FIRST = "card_first"

    const val DEFAULT_NOTICE_ENABLE = false
    const val DEFAULT_NOTICE_TEXT = "%oldGroupNickname% 改名为 %newGroupNickname%(%userWxid%)"
    const val DEFAULT_NOTICE_SCOPE = NOTICE_SCOPE_ALL
    const val DEFAULT_SEND_ENABLE = false
    const val DEFAULT_DELAY_SECONDS = 0
    const val DEFAULT_PROMPT_TYPE = PROMPT_TEXT
    const val DEFAULT_BOTH_ORDER = BOTH_TEXT_FIRST
    const val DEFAULT_TEXT = "%userName% 将群内昵称从“%oldGroupNickname%”修改为“%newGroupNickname%”"
    const val DEFAULT_CARD_TITLE = "%userName% 修改了群内昵称"
    const val DEFAULT_CARD_DESC = "旧昵称：%oldGroupNickname%\n新昵称：%newGroupNickname%\n时间：%time%"
    const val DEFAULT_WXID_COLOR = "#576B95"

    fun normalizePromptType(value: String): String {
        return when (value) {
            PROMPT_TEXT,
            PROMPT_CARD,
            PROMPT_BOTH -> value
            else -> DEFAULT_PROMPT_TYPE
        }
    }

    fun normalizeBothOrder(value: String): String {
        return if (value == BOTH_CARD_FIRST) BOTH_CARD_FIRST else BOTH_TEXT_FIRST
    }

    fun groupKey(key: String, groupId: String): String = "${key}_${groupId}"

    fun parseTemplates(value: String?): List<GroupRenameReplyTemplate> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    add(
                        GroupRenameReplyTemplate(
                            id = obj.optString("id").ifBlank { "rename_${System.currentTimeMillis()}_$index" },
                            name = obj.optString("name").ifBlank { "模板 ${index + 1}" },
                            enabled = obj.optBoolean("enabled", true),
                            delaySeconds = obj.optInt("delaySeconds", DEFAULT_DELAY_SECONDS).coerceIn(0, 600),
                            promptType = normalizePromptType(obj.optString("promptType", DEFAULT_PROMPT_TYPE)),
                            bothOrder = normalizeBothOrder(obj.optString("bothOrder", DEFAULT_BOTH_ORDER)),
                            text = obj.optString("text", DEFAULT_TEXT),
                            cardTitle = obj.optString("cardTitle", DEFAULT_CARD_TITLE),
                            cardDesc = obj.optString("cardDesc", DEFAULT_CARD_DESC)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeTemplates(templates: List<GroupRenameReplyTemplate>): String {
        val array = JSONArray()
        templates.forEach { template ->
            array.put(JSONObject().apply {
                put("id", template.id)
                put("name", template.name)
                put("enabled", template.enabled)
                put("delaySeconds", template.delaySeconds.coerceIn(0, 600))
                put("promptType", normalizePromptType(template.promptType))
                put("bothOrder", normalizeBothOrder(template.bothOrder))
                put("text", template.text)
                put("cardTitle", template.cardTitle)
                put("cardDesc", template.cardDesc)
            })
        }
        return array.toString()
    }

    fun parseBindings(value: String?): List<GroupRenameTemplateBinding> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val groupId = obj.optString("groupId").trim()
                    val templateId = obj.optString("templateId").trim()
                    if (groupId.isNotEmpty() && templateId.isNotEmpty()) {
                        add(GroupRenameTemplateBinding(groupId, obj.optString("label").trim(), templateId))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun encodeBindings(bindings: List<GroupRenameTemplateBinding>): String {
        val array = JSONArray()
        bindings.distinctBy { it.groupId }.forEach { binding ->
            array.put(JSONObject().apply {
                put("groupId", binding.groupId.trim())
                put("label", binding.label.trim())
                put("templateId", binding.templateId.trim())
            })
        }
        return array.toString()
    }
}

data class GroupRenameReplyTemplate(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val delaySeconds: Int,
    val promptType: String,
    val bothOrder: String,
    val text: String,
    val cardTitle: String,
    val cardDesc: String
)

data class GroupRenameTemplateBinding(
    val groupId: String,
    val label: String,
    val templateId: String
)
