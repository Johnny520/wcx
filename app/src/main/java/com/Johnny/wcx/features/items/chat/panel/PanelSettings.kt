package com.Johnny.wcx.features.items.chat.panel

import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption

object PanelSettings {
    const val DEFAULT_FUNBOX_API_CLIENT_WXID = "wxid_1234567890abcd"

    var stickerLastDestination by prefOption("sticker_panel_last_destination", "RECENT")
    var voiceLastDestination by prefOption("voice_panel_last_destination", "RECENT")
    var stickerColumnCount by prefOption("sticker_panel_column_count", 5)
    var stickerMaxHistory by prefOption("sticker_panel_max_history", 50L)
    var voiceMaxHistory by prefOption("voice_panel_max_history", 50L)
    var stickerRecentSortMode by prefOption("sticker_panel_recent_sort_mode", 0)
    var voiceRecentSortMode by prefOption("voice_panel_recent_sort_mode", 0)
    private var legacyStickerSortType by prefOption("sticker_panel_sort_type", 0)
    private var legacyVoiceSortType by prefOption("voice_panel_sort_type", 0)
    private var stickerPackSortModeValue by prefOption("sticker_panel_pack_sort_mode", "")
    private var stickerItemSortModeValue by prefOption("sticker_panel_item_sort_mode", "")
    private var voicePackSortModeValue by prefOption("voice_panel_pack_sort_mode", "")
    private var voiceItemSortModeValue by prefOption("voice_panel_item_sort_mode", "")
    var stickerPackCustomSortHintShown by prefOption("sticker_panel_pack_custom_sort_hint_shown", false)
    var stickerItemCustomSortHintShown by prefOption("sticker_panel_item_custom_sort_hint_shown", false)
    var voicePackCustomSortHintShown by prefOption("voice_panel_pack_custom_sort_hint_shown", false)
    var voiceItemCustomSortHintShown by prefOption("voice_panel_item_custom_sort_hint_shown", false)
    var panelAutoClose by prefOption("panel_auto_close", true)
    var wrapPanelActions by prefOption("panel_actions_wrap", false)
    private var legacyOnlineStickerPacksUseList by prefOption("sticker_panel_online_packs_use_list", false)
    private var localStickerPackLayoutValue by prefOption("sticker_panel_local_pack_layout", "TABS")
    private var onlineStickerPackLayoutValue by prefOption("sticker_panel_online_pack_layout", "")
    private var localVoicePackLayoutValue by prefOption("voice_panel_local_pack_layout", "TABS")
    var onlineStickerSortMode by prefOption("sticker_panel_online_sort_mode", 0)
    var selectedVoiceProvider by prefOption("voice_panel_selected_provider", "funbox_share")
    var selectedEdgeVoice by prefOption("voice_panel_edge_voice", "zh-CN-XiaoxiaoNeural")
    var funBoxApiClientWxId by prefOption(
        "funbox_api_client_wxid",
        DEFAULT_FUNBOX_API_CLIENT_WXID,
    )
    var telegramBotToken by prefOption("sticker_panel_telegram_bot_token", "")
    var stickerRemoveRoundedVideoMask by prefOption(
        "sticker_panel_remove_rounded_video_mask",
        false,
    )

    var localStickerPackLayout: StickerPackLayout
        get() = StickerPackLayout.entries.firstOrNull { it.name == localStickerPackLayoutValue }
            ?: StickerPackLayout.TABS
        set(value) {
            localStickerPackLayoutValue = value.name
        }

    var onlineStickerPackLayout: StickerPackLayout
        get() = StickerPackLayout.entries
            .firstOrNull { it != StickerPackLayout.TABS && it.name == onlineStickerPackLayoutValue }
            ?: if (legacyOnlineStickerPacksUseList) StickerPackLayout.LIST else StickerPackLayout.GRID
        set(value) {
            val normalized = value.takeUnless { it == StickerPackLayout.TABS } ?: StickerPackLayout.GRID
            onlineStickerPackLayoutValue = normalized.name
            legacyOnlineStickerPacksUseList = normalized == StickerPackLayout.LIST
        }

    var localVoicePackLayout: VoicePackLayout
        get() = VoicePackLayout.entries.firstOrNull { it.name == localVoicePackLayoutValue }
            ?: VoicePackLayout.TABS
        set(value) {
            localVoicePackLayoutValue = value.name
        }

    var stickerPackSortMode: LocalSortMode
        get() = sortMode(stickerPackSortModeValue, legacyStickerSortType)
        set(value) {
            stickerPackSortModeValue = value.name
        }

    var stickerItemSortMode: LocalSortMode
        get() = sortMode(stickerItemSortModeValue, legacyStickerSortType)
        set(value) {
            stickerItemSortModeValue = value.name
        }

    var voicePackSortMode: LocalSortMode
        get() = sortMode(voicePackSortModeValue, legacyVoiceSortType)
        set(value) {
            voicePackSortModeValue = value.name
        }

    var voiceItemSortMode: LocalSortMode
        get() = sortMode(voiceItemSortModeValue, legacyVoiceSortType)
        set(value) {
            voiceItemSortModeValue = value.name
        }

    val effectiveFunBoxApiClientWxId: String
        get() = funBoxApiClientWxId.takeIf(::isValidFunBoxApiClientWxId)
            ?: DEFAULT_FUNBOX_API_CLIENT_WXID

    fun isValidFunBoxApiClientWxId(value: String): Boolean =
        FUNBOX_WXID_REGEX.matches(value.trim())

    fun randomFunBoxApiClientWxId(): String {
        val bytes = ByteArray(7)
        java.security.SecureRandom().nextBytes(bytes)
        return "wxid_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidTelegramBotToken(value: String): Boolean =
        TELEGRAM_BOT_TOKEN_REGEX.matches(value.trim())

    private fun sortMode(value: String, legacyValue: Int): LocalSortMode =
        LocalSortMode.entries.firstOrNull { it.name == value }
            ?: if (legacyValue == 1) LocalSortMode.MODIFIED else LocalSortMode.NAME

    private val FUNBOX_WXID_REGEX = Regex("[A-Za-z][A-Za-z0-9_-]{5,63}")
    private val TELEGRAM_BOT_TOKEN_REGEX = Regex("[0-9]{6,12}:[A-Za-z0-9_-]{30,64}")
}
