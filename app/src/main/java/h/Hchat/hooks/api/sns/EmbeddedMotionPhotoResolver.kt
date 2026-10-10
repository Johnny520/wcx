package h.Hchat.hooks.api.sns

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest

internal data class ResolvedMotionPhoto(
    val imagePath: String,
    val videoPath: String
)

/**
 * Splits Android Motion Photo files, which store the still image and MP4 in one file.
 * The extracted files live in the app cache because WeChat consumes them asynchronously.
 */
internal object EmbeddedMotionPhotoResolver {
    private const val MAX_XMP_BYTES = 4 * 1024 * 1024
    private const val SEARCH_CHUNK_BYTES = 1024 * 1024
    private const val EOI_SEARCH_BYTES = 4 * 1024 * 1024
    private val ASCII = Charset.forName("ISO-8859-1")
    private val offsetPatterns = listOf(
        Regex("""(?:GCamera|Camera):(?:MicroVideoOffset|MotionPhotoOffset)\s*=\s*["'](\d+)["']"""),
        Regex("""<(?:GCamera|Camera):(?:MicroVideoOffset|MotionPhotoOffset)>\s*(\d+)\s*</"""),
        Regex("""(?:Item|GContainerItem):Length\s*=\s*["'](\d+)["'][^>]{0,1024}(?:Item|GContainerItem):Semantic\s*=\s*["'](?:MotionPhoto|MicroVideo)["']""", RegexOption.IGNORE_CASE),
        Regex("""(?:Item|GContainerItem):Semantic\s*=\s*["'](?:MotionPhoto|MicroVideo)["'][^>]{0,1024}(?:Item|GContainerItem):Length\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)
    )

    private data class VideoSpan(val start: Long, val end: Long)

    @Synchronized
    fun resolve(source: File, cacheDirectory: File): ResolvedMotionPhoto? {
        if (!source.isFile || source.length() < 16L) return null
        val video = RandomAccessFile(source, "r").use { raf ->
            val candidates = LinkedHashSet<Long>()
            readXmp(source).let { xmp ->
                offsetPatterns.forEach { pattern ->
                    pattern.findAll(xmp).forEach { match ->
                        val value = match.groupValues.getOrNull(1)?.toLongOrNull()
                        if (value != null && value > 0L) {
                            candidates += (source.length() - value).coerceAtLeast(0L)
                        }
                    }
                }
            }
            candidates.asSequence()
                .mapNotNull { validateMp4(raf, source.length(), it) }
                .firstOrNull()
                ?: scanForMp4(raf, source.length())
        } ?: return null

        val cacheRoot = File(cacheDirectory, "Hchat_sns_live_photo")
        if (!cacheRoot.exists() && !cacheRoot.mkdirs()) return null
        val key = cacheKey(source, video)
        val imageExtension = if (isJpeg(source)) "jpg" else imageExtension(source)
        val imageFile = File(cacheRoot, "${key}_image.$imageExtension")
        val videoFile = File(cacheRoot, "${key}_video.mp4")
        val imageEnd = if (isJpeg(source)) jpegEndBefore(source, video.start) else video.start
        if (imageEnd <= 0L || imageEnd > video.start) return null

        if (!writeRange(source, imageFile, 0L, imageEnd) ||
            !writeRange(source, videoFile, video.start, video.end - video.start)
        ) {
            imageFile.delete()
            videoFile.delete()
            return null
        }
        return ResolvedMotionPhoto(imageFile.absolutePath, videoFile.absolutePath)
    }

    private fun readXmp(source: File): String {
        val size = source.length().coerceAtMost(MAX_XMP_BYTES.toLong()).toInt()
        val bytes = ByteArray(size)
        RandomAccessFile(source, "r").use { raf ->
            raf.readFully(bytes)
        }
        return String(bytes, ASCII)
    }

    private fun scanForMp4(raf: RandomAccessFile, fileLength: Long): VideoSpan? {
        if (fileLength < 16L) return null
        val buffer = ByteArray(SEARCH_CHUNK_BYTES)
        var scanStart = 0L
        while (scanStart < fileLength) {
            raf.seek(scanStart)
            val readLength = minOf(buffer.size.toLong(), fileLength - scanStart).toInt()
            raf.readFully(buffer, 0, readLength)
            for (index in 4 until readLength - 4) {
                if (buffer[index] != 'f'.code.toByte() ||
                    buffer[index + 1] != 't'.code.toByte() ||
                    buffer[index + 2] != 'y'.code.toByte() ||
                    buffer[index + 3] != 'p'.code.toByte()
                ) continue
                val candidate = scanStart + index - 4L
                validateMp4(raf, fileLength, candidate)?.let { return it }
            }
            if (readLength < buffer.size) break
            scanStart += readLength - 8L
        }
        return null
    }

    private fun validateMp4(raf: RandomAccessFile, fileLength: Long, start: Long): VideoSpan? {
        if (start < 0L || start + 8L > fileLength) return null
        var position = start
        var boxCount = 0
        var hasFtyp = false
        var hasMdat = false
        var hasMoov = false
        while (position + 8L <= fileLength && boxCount++ < 10_000) {
            raf.seek(position)
            val size32 = raf.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4)
            raf.readFully(type)
            if (!type.all { (it.toInt() and 0xff) in 0x20..0x7e }) {
                break
            }
            val boxSize = when (size32) {
                0L -> fileLength - position
                1L -> if (position + 16L <= fileLength) raf.readLong() else return null
                else -> size32
            }
            if (boxSize < (if (size32 == 1L) 16L else 8L) || boxSize > fileLength - position) {
                break
            }
            val boxType = String(type, ASCII)
            if (position == start && boxType != "ftyp") return null
            when (boxType) {
                "ftyp" -> hasFtyp = true
                "mdat" -> hasMdat = true
                "moov" -> hasMoov = true
            }
            position += boxSize
            if (size32 == 0L) break
        }
        return if (hasFtyp && hasMdat && hasMoov && position > start) {
            VideoSpan(start, position)
        } else {
            null
        }
    }

    private fun isJpeg(source: File): Boolean {
        return runCatching {
            RandomAccessFile(source, "r").use { raf ->
                raf.readUnsignedByte() == 0xff && raf.readUnsignedByte() == 0xd8
            }
        }.getOrDefault(false)
    }

    private fun imageExtension(source: File): String {
        val extension = source.extension.lowercase()
        return when (extension) {
            "heic", "heif", "avif", "png", "webp" -> extension
            else -> "jpg"
        }
    }

    private fun jpegEndBefore(source: File, videoStart: Long): Long {
        val start = (videoStart - EOI_SEARCH_BYTES).coerceAtLeast(2L)
        val size = (videoStart - start).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val bytes = ByteArray(size)
        RandomAccessFile(source, "r").use { raf ->
            raf.seek(start)
            raf.readFully(bytes)
        }
        for (index in bytes.size - 2 downTo 0) {
            if (bytes[index] == 0xff.toByte() && bytes[index + 1] == 0xd9.toByte()) {
                return start + index + 2L
            }
        }
        return videoStart
    }

    private fun writeRange(source: File, target: File, start: Long, length: Long): Boolean {
        if (length <= 0L) return false
        if (target.isFile && target.length() == length) return true
        val temporary = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            RandomAccessFile(source, "r").use { input ->
                input.seek(start)
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var remaining = length
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) error("unexpected end of source")
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.fd.sync()
                }
            }
            if (target.exists() && !target.delete()) error("cannot replace cache file")
            if (!temporary.renameTo(target)) error("cannot finalize cache file")
            true
        }.getOrElse {
            temporary.delete()
            false
        }
    }

    private fun cacheKey(source: File, video: VideoSpan): String {
        val value = "${source.absolutePath}|${source.length()}|${source.lastModified()}|${video.start}|${video.end}"
        return runCatching {
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(24)
        }.getOrDefault(value.hashCode().toString())
    }
}
