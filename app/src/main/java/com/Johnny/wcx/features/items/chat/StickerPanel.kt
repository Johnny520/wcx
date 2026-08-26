package com.Johnny.wcx.features.items.chat

import android.content.ContentResolver
import android.view.View
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.panel.PanelPaths
import com.Johnny.wcx.features.items.chat.panel.PickedPanelFile
import com.Johnny.wcx.features.items.chat.panel.StickerItem
import com.Johnny.wcx.features.items.chat.panel.listPanelTreeFiles
import com.Johnny.wcx.features.items.chat.panel.pickPanelDirectory
import com.Johnny.wcx.features.items.chat.panel.pickPanelFile
import com.Johnny.wcx.features.items.chat.panel.pickPanelFiles
import com.Johnny.wcx.features.items.chat.panel.service.FunBoxServiceClient
import com.Johnny.wcx.features.items.chat.panel.service.FunBoxStickerRepository
import com.Johnny.wcx.features.items.chat.panel.sticker.StickerPanelRepository
import com.Johnny.wcx.features.items.chat.panel.sticker.TelegramStickerPackRepository
import com.Johnny.wcx.ui.panel.StickerImportMode
import com.Johnny.wcx.ui.panel.StickerPanelActions
import com.Johnny.wcx.ui.panel.showStickerPanelSheet
import com.Johnny.wcx.utils.fs.asPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

@Feature(
    name = "表情面板",
    categories = ["聊天"],
    description = "长按表情按钮打开表情面板"
)
object StickerPanel : SwitchFeature() { // entry implementation in ChatFooterHooks

    fun openPanel(anchor: View) {
        showStickerPanelSheet(
            context = anchor.context,
            actions = StickerPanelActions(
                        reloadLocal = ::loadLocalPacks,
                        importSticker = { packId, mode, onStarted, onComplete ->
                            when (mode) {
                                StickerImportMode.MULTIPLE_FILES -> pickPanelFiles(
                                    anchor.context,
                                    STICKER_MIME_TYPES,
                                ) { files, activity ->
                                    onStarted()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = importStickerBatch(
                                            packId,
                                            files,
                                            activity.contentResolver,
                                        )
                                        withContext(Dispatchers.Main) {
                                            onComplete(result)
                                            activity.finish()
                                        }
                                    }
                                }

                                StickerImportMode.DIRECTORY -> pickPanelDirectory(anchor.context) { treeUri, activity ->
                                    onStarted()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = runCatching {
                                            listPanelTreeFiles(activity.contentResolver, treeUri)
                                        }.mapCatching { files ->
                                            importStickerBatch(
                                                packId,
                                                files,
                                                activity.contentResolver,
                                            ).getOrThrow()
                                        }
                                        withContext(Dispatchers.Main) {
                                            onComplete(result)
                                            activity.finish()
                                        }
                                    }
                                }

                                StickerImportMode.TELEGRAM -> Unit
                            }
                        },
                        importTelegramStickerSet = TelegramStickerPackRepository::importStickerSet,
                        createPack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.createPack(name) } },
                        renamePack = StickerPanelRepository::renamePack,
                        deletePack = StickerPanelRepository::deletePack,
                        loadOnlinePacks = FunBoxStickerRepository::loadCatalog,
                        loadMyUploads = FunBoxStickerRepository::loadMyUploads,
                        loadOnlineItems = FunBoxStickerRepository::loadPack,
                        searchOnline = FunBoxStickerRepository::searchText,
                        pickSimilarityImage = { onComplete ->
                            pickPanelFile(anchor.context, arrayOf("image/*")) { _, uri, activity ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    val result = runCatching {
                                        activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            ?: error("无法读取所选图片")
                                    }.mapCatching { bytes ->
                                        require(bytes.isNotEmpty()) { "所选图片内容为空" }
                                        bytes
                                    }
                                    withContext(Dispatchers.Main) {
                                        onComplete(result)
                                        activity.finish()
                                    }
                                }
                            }
                        },
                        loadSimilarityImage = ::resolveStickerBytes,
                        searchSimilar = FunBoxStickerRepository::searchSimilar,
                        uploadPack = FunBoxStickerRepository::uploadPack,
                        setCustomTitle = StickerPanelRepository::setCustomTitle,
                        setPackCover = StickerPanelRepository::setPackCover,
                        deleteSticker = StickerPanelRepository::deleteSticker,
                        deleteStickers = StickerPanelRepository::deleteStickers,
                        savePackOrder = StickerPanelRepository::savePackOrder,
                        saveItemOrder = StickerPanelRepository::saveItemOrder,
                        ensurePack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.ensurePack(name) } },
                        saveOnlineSticker = { packId, item -> saveOnlineSticker(packId, item) },
                ),
        ) { item ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val path = resolveStickerPath(item).getOrThrow()
                    val temporary = item.localPath == null
                    try {
                        check(WeMessageApi.sendSticker(WeCurrentConversationApi.value, path)) {
                            "表情发送失败"
                        }
                        if (temporary) StickerPanelRepository.recordOnlineRecent(item)
                        else StickerPanelRepository.recordRecent(path)
                    } finally {
                        if (temporary) path.asPath.deleteIfExists()
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            PanelPaths.cleanupStalePanelCache()
        }
    }

    private fun loadLocalPacks() = buildList {
        val recents = StickerPanelRepository.getRecents()
        if (recents.items.isNotEmpty()) add(recents)
        addAll(StickerPanelRepository.loadPacks())
    }

    private suspend fun resolveStickerPath(item: StickerItem): Result<String> = withContext(Dispatchers.IO) {
        cancellableResult {
            item.localPath?.let { return@cancellableResult it }
            val bytes = resolveStickerBytes(item).getOrThrow()
            val extension = StickerPanelRepository.detectImageExtension(bytes)
                ?: error("服务器返回了不支持的图片格式")
            val path = PanelPaths.panelCacheDir / "sticker-${UUID.randomUUID()}.$extension"
            path.writeBytes(bytes)
            path.absolutePathString()
        }
    }

    private suspend fun resolveStickerBytes(item: StickerItem): Result<ByteArray> = withContext(Dispatchers.IO) {
        cancellableResult {
            val bytes = item.localPath?.asPath?.readBytes() ?: run {
                val objectId = item.remoteObjectId ?: error("没有可用表情对象")
                FunBoxServiceClient.downloadObject("image", objectId).getOrThrow()
            }
            require(bytes.isNotEmpty()) { "表情图片内容为空" }
            bytes
        }
    }

    private suspend fun saveOnlineSticker(packId: String, item: StickerItem): Result<Unit> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                if (StickerPanelRepository.hasOnlineSticker(packId, item)) return@cancellableResult
                val path = resolveStickerPath(item).getOrThrow()
                val temporary = item.localPath == null
                try {
                    Files.newInputStream(path.asPath).use { input ->
                        StickerPanelRepository.importOnlineSticker(item, packId, input).getOrThrow()
                    }
                } finally {
                    if (temporary) path.asPath.deleteIfExists()
                }
            }.map { }
        }

    private suspend inline fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun importStickerBatch(
        packId: String,
        files: List<PickedPanelFile>,
        resolver: ContentResolver,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val supported = files.filter { StickerPanelRepository.supportsFileName(it.name) }
        if (supported.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("所选内容中没有支持的图片文件"))
        }

        var imported = 0
        val failures = mutableListOf<Pair<String, Throwable>>()
        supported.forEach { file ->
            runCatching {
                val input = resolver.openInputStream(file.uri) ?: error("无法读取文件")
                input.use {
                    StickerPanelRepository.importSticker(packId, file.name, it).getOrThrow()
                }
            }.onSuccess {
                imported++
            }.onFailure {
                failures += file.name to it
            }
        }

        if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            val first = failures.first()
            Result.failure(
                IllegalStateException(
                    "已导入 $imported 个，${failures.size} 个失败；${first.first}: " +
                            (first.second.message ?: "未知错误"),
                    first.second,
                ),
            )
        }
    }

    private val STICKER_MIME_TYPES = arrayOf(
        "image/gif",
        "image/png",
        "image/webp",
        "image/jpeg",
        "image/*",
    )
}
