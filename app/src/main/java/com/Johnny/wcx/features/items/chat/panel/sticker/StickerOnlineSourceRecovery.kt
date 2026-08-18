package com.Johnny.wcx.features.items.chat.panel.sticker

data class StickerOnlineSourceRecoveryProgress(
    val completed: Int,
    val total: Int,
    val message: String,
)

data class StickerOnlineSourceRecoveryResult(
    val selected: Int,
    val recovered: Int,
    val alreadyLinked: Int,
    val unmatched: Int,
)
