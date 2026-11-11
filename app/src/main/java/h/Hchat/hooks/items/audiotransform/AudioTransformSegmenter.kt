package h.Hchat.hooks.items.audiotransform

import h.Hchat.media.AudioTransformBridge
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

internal class AudioTransformSegmentBatch(
    private val workDir: File,
    val segments: List<AudioTransformSegment>
) : Closeable {
    override fun close() {
        runCatching { workDir.deleteRecursively() }
    }
}

internal data class AudioTransformSegment(
    val file: File,
    val durationMillis: Int
)

internal object AudioTransformSegmenter {
    private const val PCM_BYTES_PER_SAMPLE = 2L
    private const val COPY_BUFFER_SIZE = 64 * 1024

    fun prepare(
        bridge: AudioTransformBridge,
        sourcePath: String,
        cacheDir: File,
        durationSeconds: Long,
        startMillis: Long = 0L,
        endMillis: Long? = null,
        cancelled: AtomicBoolean,
        onProgress: (String) -> Unit
    ): AudioTransformSegmentBatch {
        require(durationSeconds >= AudioTransformSettings.MIN_SPLIT_DURATION_SECONDS) {
            "每段时长最少为 ${AudioTransformSettings.MIN_SPLIT_DURATION_SECONDS} 秒"
        }
        require(startMillis >= 0L) { "开始时间不能小于 0" }
        require(endMillis == null || endMillis > startMillis) { "结束时间必须晚于开始时间" }
        val source = File(sourcePath)
        require(source.isFile) { "输入文件不存在" }
        val root = File(cacheDir, "audio_transform_segments").apply { mkdirs() }
        val workDir = File(root, "segment_${System.currentTimeMillis()}_${System.nanoTime()}")
        check(workDir.mkdirs()) { "无法创建切割缓存目录" }
        try {
            checkCancelled(cancelled)
            onProgress("正在解码音频")
            val sourcePcm = File(workDir, "source.pcm")
            val decode = bridge.autoToMonoPcmWithInfo(sourcePath, sourcePcm.absolutePath)
            checkCancelled(cancelled)
            if (decode.code != 0) {
                throw IllegalStateException("音频解码失败：${bridge.getErrorMessage(decode.code)}")
            }
            val pcmLength = sourcePcm.length() and -PCM_BYTES_PER_SAMPLE
            check(pcmLength > 0L) { "音频解码失败：PCM 文件为空" }
            val bytesPerSecond = decode.sampleRate.toLong() * PCM_BYTES_PER_SAMPLE
            val startByte = pcmOffset(bytesPerSecond, startMillis, roundUp = false)
                .coerceAtMost(pcmLength)
            val endByte = endMillis
                ?.let { pcmOffset(bytesPerSecond, it, roundUp = true) }
                ?.coerceAtMost(pcmLength)
                ?: pcmLength
            check(endByte > startByte) { "选定音频区间为空" }
            val selectedLength = endByte - startByte
            val segmentBytes = alignedSegmentBytes(bytesPerSecond, durationSeconds)
            val totalLong = selectedLength / segmentBytes +
                if (selectedLength % segmentBytes == 0L) 0L else 1L
            check(totalLong in 1L..Int.MAX_VALUE.toLong()) { "音频分段数量过多" }
            val total = totalLong.toInt()
            val segments = ArrayList<AudioTransformSegment>(total)
            FileInputStream(sourcePcm).use { input ->
                input.channel.position(startByte)
                var remaining = selectedLength
                repeat(total) { index ->
                    checkCancelled(cancelled)
                    onProgress("正在准备 ${index + 1}/$total")
                    val partBytes = min(segmentBytes, remaining)
                    val partPcm = File(workDir, "part_${index + 1}.pcm")
                    val partSilk = File(workDir, "part_${index + 1}.silk")
                    val written = copyPart(input, partPcm, partBytes, cancelled)
                    check(written == partBytes) { "读取第 ${index + 1} 段音频失败" }
                    val code = bridge.pcmToSilk(
                        partPcm.absolutePath,
                        partSilk.absolutePath,
                        AudioTransformBridge.DEFAULT_HZ,
                        decode.sampleRate,
                        1
                    )
                    runCatching { partPcm.delete() }
                    if (code != 0) {
                        throw IllegalStateException(
                            "第 ${index + 1} 段转换失败：${bridge.getErrorMessage(code)}"
                        )
                    }
                    check(partSilk.isFile && partSilk.length() > 0L) {
                        "第 ${index + 1} 段转换失败：输出文件为空"
                    }
                    segments += AudioTransformSegment(
                        file = partSilk,
                        durationMillis = durationMillis(written, bytesPerSecond)
                    )
                    remaining -= written
                }
            }
            runCatching { sourcePcm.delete() }
            checkCancelled(cancelled)
            return AudioTransformSegmentBatch(workDir, segments)
        } catch (throwable: Throwable) {
            runCatching { workDir.deleteRecursively() }
            throw throwable
        }
    }

    private fun alignedSegmentBytes(bytesPerSecond: Long, durationSeconds: Long): Long {
        val raw = if (durationSeconds > Long.MAX_VALUE / bytesPerSecond) {
            Long.MAX_VALUE
        } else {
            bytesPerSecond * durationSeconds
        }
        return (raw and -PCM_BYTES_PER_SAMPLE).coerceAtLeast(PCM_BYTES_PER_SAMPLE)
    }

    private fun pcmOffset(bytesPerSecond: Long, millis: Long, roundUp: Boolean): Long {
        val numerator = if (millis > Long.MAX_VALUE / bytesPerSecond) {
            Long.MAX_VALUE
        } else {
            millis * bytesPerSecond
        }
        val raw = if (roundUp && numerator <= Long.MAX_VALUE - 999L) {
            (numerator + 999L) / 1000L
        } else {
            numerator / 1000L
        }
        return if (roundUp && raw < Long.MAX_VALUE) {
            ((raw + PCM_BYTES_PER_SAMPLE - 1L) and -PCM_BYTES_PER_SAMPLE)
        } else {
            raw and -PCM_BYTES_PER_SAMPLE
        }
    }

    private fun copyPart(
        input: FileInputStream,
        target: File,
        expectedBytes: Long,
        cancelled: AtomicBoolean
    ): Long {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var remaining = expectedBytes
        var written = 0L
        FileOutputStream(target).use { output ->
            while (remaining > 0L) {
                checkCancelled(cancelled)
                val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                remaining -= read
                written += read
            }
        }
        return written
    }

    private fun durationMillis(bytes: Long, bytesPerSecond: Long): Int {
        val millis = bytes / bytesPerSecond * 1000L +
            bytes % bytesPerSecond * 1000L / bytesPerSecond
        return millis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun checkCancelled(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw AudioTransformCancelledException()
    }
}

internal class AudioTransformCancelledException : RuntimeException("操作已取消")
