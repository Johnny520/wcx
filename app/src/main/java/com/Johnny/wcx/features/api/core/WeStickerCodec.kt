package com.Johnny.wcx.features.api.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tencent.mm.plugin.gif.MMWXGFJNI
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.KnownPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * 贴纸文件的格式识别与转码（纯文件处理，不涉及发送、不碰微信服务）。
 *
 * 从 [WeMessageApi] 抽出来：这段逻辑只依赖字节与临时文件，和「怎么发」无关，
 * 留在 2.5k 行的门面里既难复用（表情面板、保存到本地都要用），也让门面的职责更混杂。
 */
internal object WeStickerCodec {

    private const val TAG = "WeStickerCodec"

    /** 微信直传表情的尺寸上限：超过它只能按普通图片发。 */
    const val MAX_EMOJI_DIMENSION = 1024

    /** 转码临时文件的最长保留时间，避免 `sticker-send-*` 在缓存目录里堆积。 */
    private const val TEMP_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** 识别结果只描述文件本身，不含任何发送决策。 */
    fun inspect(path: Path): StickerFileInfo {
        val header = ByteArray(12)
        val headerSize = Files.newInputStream(path).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            offset
        }

        val format = when {
            headerSize >= 3 && header.hasMagic(0, 0x47, 0x49, 0x46) -> StickerFileFormat.GIF
            headerSize >= 8 && header.hasMagic(0, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> StickerFileFormat.PNG
            headerSize >= 12 &&
                    header.hasMagic(0, 0x52, 0x49, 0x46, 0x46) &&
                    header.hasMagic(8, 0x57, 0x45, 0x42, 0x50) -> StickerFileFormat.WEBP

            headerSize >= 3 && header.hasMagic(0, 0xff, 0xd8, 0xff) -> StickerFileFormat.JPEG
            isWxgf(path) -> StickerFileFormat.WXGF
            else -> StickerFileFormat.OTHER
        }

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (format != StickerFileFormat.WXGF) {
            BitmapFactory.decodeFile(path.absolutePathString(), options)
        }
        return StickerFileInfo(format, path.fileSize(), options.outWidth, options.outHeight)
    }

    /** WebP → PNG（微信直传只接受 GIF 与尺寸受限的 PNG）。 */
    fun convertToPng(source: Path, output: Path) {
        val bitmap = BitmapFactory.decodeFile(source.absolutePathString())
            ?: error("failed to decode sticker bitmap")
        try {
            require(bitmap.width in 1..MAX_EMOJI_DIMENSION && bitmap.height in 1..MAX_EMOJI_DIMENSION) {
                "sticker dimensions exceed direct emoji limit"
            }
            output.outputStream().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "failed to encode sticker as PNG"
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** WXGF/WXAM → GIF（保留动画）。 */
    fun convertWxgfToGif(source: Path, output: Path) {
        val converted = MMWXGFJNI.nativeWxamToGif(source.readBytes())
        require(converted.size >= 3 && converted.hasMagic(0, 0x47, 0x49, 0x46)) {
            "failed to decode WXGF sticker"
        }
        output.writeBytes(converted)
    }

    fun createTemp(extension: String): Path =
        KnownPaths.moduleCache / "sticker-send-converted-${UUID.randomUUID()}.$extension"

    /** 清掉过期的转码临时文件；失败只记日志，不影响发送。 */
    fun cleanupStale() {
        val cutoff = System.currentTimeMillis() - TEMP_MAX_AGE_MS
        runCatching {
            Files.list(KnownPaths.moduleCache).use { paths ->
                paths.filter {
                    it.isRegularFile() &&
                            it.name.startsWith("sticker-send-") &&
                            Files.getLastModifiedTime(it).toMillis() < cutoff
                }.forEach(Path::deleteIfExists)
            }
        }.onFailure { WeLogger.w(TAG, "failed to clean stale sticker send cache", it) }
    }

    private fun isWxgf(path: Path): Boolean = runCatching {
        val bytes = path.readBytes()
        bytes.isNotEmpty() && MMWXGFJNI.isWxGF(bytes, bytes.size)
    }.getOrElse {
        WeLogger.w(TAG, "WXGF detection failed", it)
        false
    }
}

internal enum class StickerFileFormat {
    GIF,
    PNG,
    WEBP,
    WXGF,
    JPEG,
    OTHER,
}

internal data class StickerFileInfo(
    val format: StickerFileFormat,
    val byteCount: Long,
    val width: Int,
    val height: Int,
)

/** 是否可以直接作为微信表情上传：尺寸必须在上限内。 */
internal fun StickerFileInfo.fitsDirectEmoji(): Boolean =
    width in 1..WeStickerCodec.MAX_EMOJI_DIMENSION && height in 1..WeStickerCodec.MAX_EMOJI_DIMENSION

/** 按文件头比对 magic（越界会抛，调用方先判长度）。 */
internal fun ByteArray.hasMagic(offset: Int, vararg expected: Int): Boolean =
    expected.indices.all { this[offset + it].toInt() and 0xff == expected[it] }
