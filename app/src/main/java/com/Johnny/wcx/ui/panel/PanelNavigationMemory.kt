package com.Johnny.wcx.ui.panel

import com.Johnny.wcx.features.items.chat.panel.StickerDestination
import com.Johnny.wcx.features.items.chat.panel.VoiceDestination
import com.Johnny.wcx.features.items.chat.panel.VoiceItem
import com.Johnny.wcx.features.items.chat.panel.VoicePack

internal data class StickerPanelNavigation(
    val destination: StickerDestination,
    val selectedLocalPackId: String?,
    val localPackDetailId: String?,
    val showingMyUploads: Boolean,
    val selectedOnlinePackId: String?,
)

internal data class VoicePanelNavigation(
    val destination: VoiceDestination,
    val selectedLocalPackId: String?,
    val localPackDetailId: String?,
    val ttsMode: TtsMode,
    val managingClones: Boolean,
    val cloneSource: String?,
    val cloneSharedPack: VoicePack?,
    val selectedExampleGroup: String?,
    val providerId: String,
    val providerParent: VoiceItem?,
    val providerPage: Int,
    val onlineSearchQuery: String,
    val onlineSearchParent: VoiceItem?,
    val onlineSearchPage: Int,
    val onlineSearchExecuted: Boolean,
    val selectedSharedPack: VoicePack?,
)

internal object PanelNavigationMemory {
    var sticker: StickerPanelNavigation? = null
    var voice: VoicePanelNavigation? = null

    fun clear() {
        sticker = null
        voice = null
    }
}
