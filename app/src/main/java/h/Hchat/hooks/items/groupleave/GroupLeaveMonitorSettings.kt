package h.Hchat.hooks.items.groupleave

import org.json.JSONArray
import org.json.JSONObject

object GroupLeaveMonitorSettings {
    const val PREFS_NAME = "Hchat_group_leave_monitor_config"

    const val KEY_ENABLE = "group_leave_monitor_enable"
    const val KEY_LEAVE_NOTICE_TEXT = "group_leave_monitor_notice_text"
    const val KEY_NOTICE_SCOPE = "group_monitor_notice_scope"
    const val KEY_NOTICE_GROUPS = "group_monitor_notice_groups"
    const val KEY_WXID_COLOR = "group_leave_monitor_wxid_color"
    const val KEY_INVITE_DETAIL_ENABLE = "group_invite_detail_enable"
    const val KEY_INVITE_NOTICE_TEXT = "group_invite_detail_notice_text"
    const val KEY_INVITE_COUNT_PREFIX = "group_invite_detail_count_"
    const val KEY_REPLY_ENABLE = "group_member_reply_enable"
    const val KEY_LISTEN_GROUPS = "group_member_reply_listen_groups"
    const val KEY_JOIN_REPLY_ENABLE = "group_member_reply_join_enable"
    const val KEY_LEFT_REPLY_ENABLE = "group_member_reply_left_enable"
    const val KEY_JOIN_DISABLED_GROUPS = "group_member_reply_join_disabled_groups"
    const val KEY_LEFT_DISABLED_GROUPS = "group_member_reply_left_disabled_groups"
    const val KEY_DELAY_SECONDS = "group_member_reply_delay_seconds"
    const val KEY_PROMPT_TYPE = "group_member_reply_prompt_type"
    const val KEY_BOTH_ORDER = "group_member_reply_both_order"
    const val KEY_JOIN_TEXT = "group_member_reply_join_text"
    const val KEY_LEFT_TEXT = "group_member_reply_left_text"
    const val KEY_JOIN_CARD_TITLE = "group_member_reply_join_card_title"
    const val KEY_JOIN_CARD_DESC = "group_member_reply_join_card_desc"
    const val KEY_LEFT_CARD_TITLE = "group_member_reply_left_card_title"
    const val KEY_LEFT_CARD_DESC = "group_member_reply_left_card_desc"
    const val KEY_MEDIA_ORDER = "group_member_reply_media_order"
    const val KEY_MEDIA_SEQUENCE = "group_member_reply_media_sequence"
    const val KEY_JOIN_IMAGE_PATHS = "group_member_reply_join_image_paths"
    const val KEY_LEFT_IMAGE_PATHS = "group_member_reply_left_image_paths"
    const val KEY_JOIN_VOICE_PATHS = "group_member_reply_join_voice_paths"
    const val KEY_LEFT_VOICE_PATHS = "group_member_reply_left_voice_paths"
    const val KEY_JOIN_EMOJI_PATHS = "group_member_reply_join_emoji_paths"
    const val KEY_LEFT_EMOJI_PATHS = "group_member_reply_left_emoji_paths"
    const val KEY_JOIN_VIDEO_PATHS = "group_member_reply_join_video_paths"
    const val KEY_LEFT_VIDEO_PATHS = "group_member_reply_left_video_paths"
    const val KEY_JOIN_FILE_PATHS = "group_member_reply_join_file_paths"
    const val KEY_LEFT_FILE_PATHS = "group_member_reply_left_file_paths"
    const val KEY_JOIN_FAVORITE_PATHS = "group_member_reply_join_favorite_paths"
    const val KEY_LEFT_FAVORITE_PATHS = "group_member_reply_left_favorite_paths"
    const val KEY_PROMPT_DELAY_MS = "group_member_reply_prompt_delay_ms"
    const val KEY_IMAGE_DELAY_MS = "group_member_reply_image_delay_ms"
    const val KEY_VOICE_DELAY_MS = "group_member_reply_voice_delay_ms"
    const val KEY_EMOJI_DELAY_MS = "group_member_reply_emoji_delay_ms"
    const val KEY_VIDEO_DELAY_MS = "group_member_reply_video_delay_ms"
    const val KEY_FILE_DELAY_MS = "group_member_reply_file_delay_ms"
    const val KEY_FAVORITE_DELAY_MS = "group_member_reply_favorite_delay_ms"
    const val KEY_MEDIA_MODE_PREFIX = "group_member_reply_media_mode_"
    const val KEY_DELAY_MODE_PREFIX = "group_member_reply_delay_mode_"
    const val KEY_REPLY_TEMPLATES = "group_member_reply_templates"
    const val KEY_REPLY_TEMPLATE_BINDINGS = "group_member_reply_template_bindings"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_LEAVE_NOTICE_TEXT = "%displayName%(%userWxid%) 退出了群聊"
    const val DEFAULT_WXID_COLOR = "#576B95"
    const val DEFAULT_INVITE_DETAIL_ENABLE = false
    const val DEFAULT_INVITE_NOTICE_TEXT = "邀请者：%inviterName% (%inviterWxid%)\n被邀请者：%inviteeName% (%inviteeWxid%)\n累计邀请：%inviteCount%"
    const val DEFAULT_REPLY_ENABLE = false
    const val DEFAULT_JOIN_REPLY_ENABLE = true
    const val DEFAULT_LEFT_REPLY_ENABLE = true
    const val DEFAULT_DELAY_SECONDS = 0
    const val PROMPT_TEXT = "text"
    const val PROMPT_CARD = "card"
    const val PROMPT_BOTH = "both"
    const val BOTH_TEXT_FIRST = "text_first"
    const val BOTH_CARD_FIRST = "card_first"
    const val MEDIA_NONE = "none"
    const val MEDIA_BEFORE = "before"
    const val MEDIA_AFTER = "after"
    const val MODE_GLOBAL = "global"
    const val MODE_CUSTOM = "custom"
    const val NOTICE_SCOPE_ALL = "all"
    const val NOTICE_SCOPE_SPECIFIC = "specific"
    const val DEFAULT_NOTICE_SCOPE = NOTICE_SCOPE_ALL
    const val DEFAULT_PROMPT_TYPE = PROMPT_TEXT
    const val DEFAULT_BOTH_ORDER = BOTH_TEXT_FIRST
    const val DEFAULT_MEDIA_ORDER = MEDIA_NONE
    const val DEFAULT_MEDIA_SEQUENCE = "image,voice,emoji,video,file,favorite"
    const val DEFAULT_JOIN_TEXT = "[AtWx=%userWxid%]\n欢迎进群\n时间：%time%\n群昵称：%groupName%\n进群者微信昵称：%userName%\n进群者群内昵称：%groupNickname%\n进群者ID：%userWxid%"
    const val DEFAULT_LEFT_TEXT = "退群通知：\n时间：%time%\n群昵称：%groupName%\n退群者微信昵称：%userName%\n退群者群内昵称：%groupNickname%\n退群者ID：%userWxid%"
    const val DEFAULT_JOIN_CARD_TITLE = "欢迎：%userName%"
    const val DEFAULT_JOIN_CARD_DESC = "ID：%userWxid%\n名片：%groupNickname%\n时间：%time%"
    const val DEFAULT_LEFT_CARD_TITLE = "离群：%userName%"
    const val DEFAULT_LEFT_CARD_DESC = "ID：%userWxid%\n名片：%groupNickname%\n时间：%time%"
    const val DEFAULT_PROMPT_DELAY_MS = 0
    const val DEFAULT_MEDIA_DELAY_MS = 100

    fun parseTemplates(value: String?): List<GroupLeaveReplyTemplate> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            val result = ArrayList<GroupLeaveReplyTemplate>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                result += GroupLeaveReplyTemplate(
                    id = obj.optString("id").ifBlank { System.currentTimeMillis().toString() + "_" + i },
                    name = obj.optString("name").ifBlank { "模板 ${i + 1}" },
                    enabled = obj.optBoolean("enabled", true),
                    joinEnabled = obj.optBoolean("joinEnabled", DEFAULT_JOIN_REPLY_ENABLE),
                    leftEnabled = obj.optBoolean("leftEnabled", DEFAULT_LEFT_REPLY_ENABLE),
                    promptType = normalizePromptType(obj.optString("promptType", DEFAULT_PROMPT_TYPE)),
                    bothOrder = normalizeBothOrder(obj.optString("bothOrder", DEFAULT_BOTH_ORDER)),
                    joinText = obj.optString("joinText", DEFAULT_JOIN_TEXT),
                    leftText = obj.optString("leftText", DEFAULT_LEFT_TEXT),
                    joinCardTitle = obj.optString("joinCardTitle", DEFAULT_JOIN_CARD_TITLE),
                    joinCardDesc = obj.optString("joinCardDesc", DEFAULT_JOIN_CARD_DESC),
                    leftCardTitle = obj.optString("leftCardTitle", DEFAULT_LEFT_CARD_TITLE),
                    leftCardDesc = obj.optString("leftCardDesc", DEFAULT_LEFT_CARD_DESC),
                    mediaMode = normalizeMediaMode(obj.optString("mediaMode", MODE_GLOBAL)),
                    mediaOrder = normalizeMediaOrder(obj.optString("mediaOrder", DEFAULT_MEDIA_ORDER)),
                    mediaSequence = obj.optString("mediaSequence", DEFAULT_MEDIA_SEQUENCE),
                    joinImages = obj.optString("joinImages"),
                    leftImages = obj.optString("leftImages"),
                    joinVoices = obj.optString("joinVoices"),
                    leftVoices = obj.optString("leftVoices"),
                    joinEmojis = obj.optString("joinEmojis"),
                    leftEmojis = obj.optString("leftEmojis"),
                    joinVideos = obj.optString("joinVideos"),
                    leftVideos = obj.optString("leftVideos"),
                    joinFiles = obj.optString("joinFiles"),
                    leftFiles = obj.optString("leftFiles"),
                    joinFavorites = obj.optString("joinFavorites"),
                    leftFavorites = obj.optString("leftFavorites"),
                    delayMode = normalizeMode(obj.optString("delayMode", MODE_GLOBAL)),
                    promptDelayMs = obj.optInt("promptDelayMs", DEFAULT_PROMPT_DELAY_MS).coerceAtLeast(0),
                    imageDelayMs = obj.optInt("imageDelayMs", DEFAULT_MEDIA_DELAY_MS).coerceAtLeast(0),
                    voiceDelayMs = obj.optInt("voiceDelayMs", DEFAULT_MEDIA_DELAY_MS).coerceAtLeast(0),
                    emojiDelayMs = obj.optInt("emojiDelayMs", DEFAULT_MEDIA_DELAY_MS).coerceAtLeast(0),
                    videoDelayMs = obj.optInt("videoDelayMs", DEFAULT_MEDIA_DELAY_MS).coerceAtLeast(0),
                    fileDelayMs = obj.optInt("fileDelayMs", DEFAULT_MEDIA_DELAY_MS).coerceAtLeast(0),
                    favoriteDelayMs = obj.optInt("favoriteDelayMs", DEFAULT_MEDIA_DELAY_MS).coerceAtLeast(0)
                )
            }
            result
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun encodeTemplates(templates: List<GroupLeaveReplyTemplate>): String {
        val array = JSONArray()
        templates.forEach { template ->
            val obj = JSONObject()
            obj.put("id", template.id)
            obj.put("name", template.name)
            obj.put("enabled", template.enabled)
            obj.put("joinEnabled", template.joinEnabled)
            obj.put("leftEnabled", template.leftEnabled)
            obj.put("promptType", normalizePromptType(template.promptType))
            obj.put("bothOrder", normalizeBothOrder(template.bothOrder))
            obj.put("joinText", template.joinText)
            obj.put("leftText", template.leftText)
            obj.put("joinCardTitle", template.joinCardTitle)
            obj.put("joinCardDesc", template.joinCardDesc)
            obj.put("leftCardTitle", template.leftCardTitle)
            obj.put("leftCardDesc", template.leftCardDesc)
            obj.put("mediaMode", normalizeMediaMode(template.mediaMode))
            obj.put("mediaOrder", normalizeMediaOrder(template.mediaOrder))
            obj.put("mediaSequence", template.mediaSequence)
            obj.put("joinImages", template.joinImages)
            obj.put("leftImages", template.leftImages)
            obj.put("joinVoices", template.joinVoices)
            obj.put("leftVoices", template.leftVoices)
            obj.put("joinEmojis", template.joinEmojis)
            obj.put("leftEmojis", template.leftEmojis)
            obj.put("joinVideos", template.joinVideos)
            obj.put("leftVideos", template.leftVideos)
            obj.put("joinFiles", template.joinFiles)
            obj.put("leftFiles", template.leftFiles)
            obj.put("joinFavorites", template.joinFavorites)
            obj.put("leftFavorites", template.leftFavorites)
            obj.put("delayMode", normalizeMode(template.delayMode))
            obj.put("promptDelayMs", template.promptDelayMs.coerceAtLeast(0))
            obj.put("imageDelayMs", template.imageDelayMs.coerceAtLeast(0))
            obj.put("voiceDelayMs", template.voiceDelayMs.coerceAtLeast(0))
            obj.put("emojiDelayMs", template.emojiDelayMs.coerceAtLeast(0))
            obj.put("videoDelayMs", template.videoDelayMs.coerceAtLeast(0))
            obj.put("fileDelayMs", template.fileDelayMs.coerceAtLeast(0))
            obj.put("favoriteDelayMs", template.favoriteDelayMs.coerceAtLeast(0))
            array.put(obj)
        }
        return array.toString()
    }

    fun parseBindings(value: String?): List<GroupLeaveReplyTemplateBinding> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(value)
            val merged = linkedMapOf<String, GroupLeaveReplyTemplateBinding>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val groupId = obj.optString("groupId").trim()
                val templateId = obj.optString("templateId").trim()
                if (groupId.isEmpty() || templateId.isEmpty()) continue
                merged[groupId] = GroupLeaveReplyTemplateBinding(
                    groupId = groupId,
                    label = obj.optString("label").ifBlank { groupId },
                    templateId = templateId
                )
            }
            merged.values.toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun encodeBindings(bindings: List<GroupLeaveReplyTemplateBinding>): String {
        val array = JSONArray()
        bindings
            .filter { it.groupId.isNotBlank() && it.templateId.isNotBlank() }
            .distinctBy { it.groupId.trim() }
            .forEach { binding ->
                val obj = JSONObject()
                obj.put("groupId", binding.groupId.trim())
                obj.put("label", binding.label)
                obj.put("templateId", binding.templateId.trim())
                array.put(obj)
            }
        return array.toString()
    }

    fun normalizePromptType(value: String): String {
        return when (value) {
            MODE_GLOBAL,
            PROMPT_CARD,
            PROMPT_BOTH,
            PROMPT_TEXT -> value
            else -> DEFAULT_PROMPT_TYPE
        }
    }

    fun normalizeBothOrder(value: String): String {
        return if (value == BOTH_CARD_FIRST) BOTH_CARD_FIRST else BOTH_TEXT_FIRST
    }

    fun normalizeMediaMode(value: String): String {
        return when (value) {
            MODE_CUSTOM -> MODE_CUSTOM
            MEDIA_NONE -> MEDIA_NONE
            else -> MODE_GLOBAL
        }
    }

    fun normalizeMediaOrder(value: String): String {
        return when (value) {
            MEDIA_BEFORE,
            MEDIA_AFTER,
            MEDIA_NONE -> value
            else -> DEFAULT_MEDIA_ORDER
        }
    }

    fun normalizeMode(value: String): String {
        return if (value == MODE_CUSTOM) MODE_CUSTOM else MODE_GLOBAL
    }
}

data class GroupLeaveReplyTemplate(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val joinEnabled: Boolean,
    val leftEnabled: Boolean,
    val promptType: String,
    val bothOrder: String,
    val joinText: String,
    val leftText: String,
    val joinCardTitle: String,
    val joinCardDesc: String,
    val leftCardTitle: String,
    val leftCardDesc: String,
    val mediaMode: String,
    val mediaOrder: String,
    val mediaSequence: String,
    val joinImages: String,
    val leftImages: String,
    val joinVoices: String,
    val leftVoices: String,
    val joinEmojis: String,
    val leftEmojis: String,
    val joinVideos: String,
    val leftVideos: String,
    val joinFiles: String,
    val leftFiles: String,
    val joinFavorites: String,
    val leftFavorites: String,
    val delayMode: String,
    val promptDelayMs: Int,
    val imageDelayMs: Int,
    val voiceDelayMs: Int,
    val emojiDelayMs: Int,
    val videoDelayMs: Int,
    val fileDelayMs: Int,
    val favoriteDelayMs: Int
)

data class GroupLeaveReplyTemplateBinding(
    val groupId: String,
    val label: String,
    val templateId: String
)
