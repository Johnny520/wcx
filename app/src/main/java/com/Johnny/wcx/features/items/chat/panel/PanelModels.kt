package com.Johnny.wcx.features.items.chat.panel

import kotlinx.serialization.Serializable

enum class PanelSource {
    LOCAL,
    RECENT,
    SHARED,
    ONLINE,
    IMPORTED,
}

@Serializable
data class StickerItem(
    val id: String,
    val title: String = "",
    val customTitle: String? = null,
    val localPath: String? = null,
    val remoteObjectId: String? = null,
    val thumbnailUrl: String? = null,
    val imageUrl: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val packId: String = "",
    val sendCount: Long = 0,
    val lastSentAt: Long = 0,
)

@Serializable
data class StickerPack(
    val id: String,
    val title: String,
    val cover: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val onlineSourcePackId: String? = null,
    val order: Int = 0,
    val itemCount: Int = 0,
    val badge: String? = null,
    val uploadTime: Long = 0,
    val downloadCount: Int = 0,
    val items: List<StickerItem> = emptyList(),
)

@Serializable
data class VoiceItem(
    val id: String,
    val title: String,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val remoteObjectId: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val packId: String = "",
    val durationMs: Long = 0,
    val format: String = "",
    val sendCount: Long = 0,
    val lastSentAt: Long = 0,
    val isContainer: Boolean = false,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class VoicePack(
    val id: String,
    val title: String,
    val cover: String? = null,
    val source: PanelSource = PanelSource.LOCAL,
    val order: Int = 0,
    val itemCount: Int = 0,
    val badge: String? = null,
    val items: List<VoiceItem> = emptyList(),
)

@Serializable
data class CloneVoice(
    val id: String,
    val name: String,
    val fileName: String,
)

data class CloneExample(
    val group: String,
    val fileName: String,
) {
    val title: String get() = fileName.substringBeforeLast('.').ifBlank { fileName }
}

data class VoiceProviderPage(
    val items: List<VoiceItem>,
    val page: Int,
    val hasMore: Boolean,
)

data class VoicePreview(
    val path: String,
    val temporary: Boolean,
)

sealed interface PanelUiState<out T> {
    data object Loading : PanelUiState<Nothing>
    data class Content<T>(val value: T) : PanelUiState<T>
    data class Empty(val message: String) : PanelUiState<Nothing>
    data class Error(val message: String) : PanelUiState<Nothing>
}

enum class StickerDestination {
    RECENT,
    SEARCH,
    PACKS,
    ONLINE,
    ONLINE_SEARCH,
    SETTINGS,
}

enum class StickerPackLayout {
    TABS,
    GRID,
    LIST,
}

enum class VoicePackLayout {
    TABS,
    LIST,
}

enum class LocalSortMode(val label: String) {
    NAME("名称"),
    MODIFIED("最近修改"),
    RECENT("最近使用"),
    FREQUENT("最常使用"),
    CUSTOM("自定义");

    fun next(): LocalSortMode = entries[(ordinal + 1) % entries.size]
}

enum class VoiceDestination {
    RECENT,
    SEARCH,
    LOCAL,
    TTS,
    ONLINE,
    ONLINE_SEARCH,
    SHARED,
    SETTINGS,
}

const val RECENT_PACK_ID = "!!recent:data_!!"
