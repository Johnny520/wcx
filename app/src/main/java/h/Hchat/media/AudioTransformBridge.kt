package h.Hchat.media

import h.Hchat.utils.KavaReflector
import me.yun.silk.AacCodec
import me.yun.silk.SilkCodec
import java.util.function.Consumer
import kotlin.math.min

open class AudioTransformBridge(
    private val logger: ((String) -> Unit)? = null
) {
    internal data class PcmDecodeResult(
        val code: Int,
        val sampleRate: Int
    )

    companion object {
        const val DEFAULT_HZ: Int = 24000
        private const val DEFAULT_SAMPLE_RATE: Int = 44100
        private const val DEFAULT_CHANNELS: Int = 1
        private val SUPPORTED_SILK_HZ = setOf(8000, 12000, 16000, DEFAULT_HZ)
    }

    private val silkCodec: SilkCodec by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SilkCodec() }

    fun getFileType(filePath: String?): Int {
        val path = validPath(filePath) ?: return 0
        return callInt("getFileType") { silkCodec.getFileType(path) } ?: 0
    }

    fun mp3ToSilk(mp3Path: String?, silkPath: String?): Int {
        return mp3ToSilk(mp3Path, silkPath, DEFAULT_HZ)
    }

    fun mp3ToSilk(mp3Path: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(mp3Path) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("mp3ToSilk") {
            silkCodec.mp3ToSilk(input, output, validSilkHz(hz))
        } ?: -301
    }

    fun wavToSilk(wavPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(wavPath) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("wavToSilk") {
            silkCodec.wavToSilk(input, output, validSilkHz(hz))
        } ?: -501
    }

    fun flacToSilk(flacPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(flacPath) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("flacToSilk") {
            silkCodec.flacToSilk(input, output, validSilkHz(hz))
        } ?: -601
    }

    fun oggToSilk(oggPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(oggPath) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("oggToSilk") {
            AacCodec.oggToSilkCompat(input, output, silkCodec, validSilkHz(hz))
        } ?: -401
    }

    fun pcmToSilk(
        pcmPath: String?,
        silkPath: String?,
        hz: Int,
        pcmHz: Int,
        channels: Int
    ): Int {
        val input = validPath(pcmPath) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("pcmToSilk") {
            silkCodec.pcmToSilk(
                input,
                output,
                validSilkHz(hz),
                validSampleRate(pcmHz),
                validChannels(channels)
            )
        } ?: -701
    }

    fun autoToSilk(audioPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(audioPath) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("autoToSilk") {
            AacCodec.autoToSilkCompat(input, output, silkCodec, validSilkHz(hz))
        } ?: -2
    }

    fun silkToMp3(silkPath: String?, mp3Path: String?): Int {
        return silkToMp3(silkPath, mp3Path, DEFAULT_HZ)
    }

    fun silkToMp3(silkPath: String?, mp3Path: String?, hz: Int): Int {
        val input = validPath(silkPath) ?: return -1
        val output = validPath(mp3Path) ?: return -11
        return callInt("silkToMp3") {
            silkCodec.silkToMp3(input, output, validSilkHz(hz))
        } ?: -201
    }

    fun silkToPcm(silkPath: String?, pcmPath: String?, hz: Int): Int {
        val input = validPath(silkPath) ?: return -1
        val output = validPath(pcmPath) ?: return -12
        return callInt("silkToPcm") {
            silkCodec.silkToPcm(input, output, validSilkHz(hz))
        } ?: -201
    }

    fun mp3ToPcm(mp3Path: String?, pcmPath: String?): Int {
        val input = validPath(mp3Path) ?: return -1
        val output = validPath(pcmPath) ?: return -12
        return callInt("mp3ToPcm") {
            silkCodec.mp3ToPcm(input, output)
        } ?: -301
    }

    fun wavToPcm(wavPath: String?, pcmPath: String?): Int {
        val input = validPath(wavPath) ?: return -1
        val output = validPath(pcmPath) ?: return -12
        return callInt("wavToPcm") {
            silkCodec.wavToPcm(input, output)
        } ?: -501
    }

    fun flacToPcm(flacPath: String?, pcmPath: String?): Int {
        val input = validPath(flacPath) ?: return -1
        val output = validPath(pcmPath) ?: return -12
        return callInt("flacToPcm") {
            silkCodec.flacToPcm(input, output)
        } ?: -601
    }

    fun oggToPcm(oggPath: String?, pcmPath: String?): Int {
        val input = validPath(oggPath) ?: return -1
        val output = validPath(pcmPath) ?: return -12
        return callInt("oggToPcm") {
            AacCodec.oggToPcmCompat(input, output, silkCodec)
        } ?: -401
    }

    fun autoToPcm(audioPath: String?, pcmPath: String?): Int {
        val input = validPath(audioPath) ?: return -1
        val output = validPath(pcmPath) ?: return -12
        return callInt("autoToPcm") {
            AacCodec.autoToPcmCompat(input, output, silkCodec)
        } ?: -2
    }

    internal fun autoToMonoPcmWithInfo(
        audioPath: String?,
        pcmPath: String?,
        silkSampleRate: Int = DEFAULT_HZ
    ): PcmDecodeResult {
        val input = validPath(audioPath) ?: return PcmDecodeResult(-1, DEFAULT_SAMPLE_RATE)
        val output = validPath(pcmPath) ?: return PcmDecodeResult(-12, DEFAULT_SAMPLE_RATE)
        return runCatching {
            val result = AacCodec.autoToMonoPcmWithInfo(
                input,
                output,
                silkCodec,
                validSilkHz(silkSampleRate)
            )
            PcmDecodeResult(result.code, validSampleRate(result.sampleRate))
        }.onFailure {
            log("autoToMonoPcmWithInfo 失败: ${it.message}")
        }.getOrElse {
            PcmDecodeResult(-2, DEFAULT_SAMPLE_RATE)
        }
    }

    fun getAudioInfo(filePath: String?): Map<String, Any?> {
        val path = validPath(filePath) ?: return emptyMap()
        val info = runCatching { AacCodec.getAudioInfo(path) }.onFailure {
            log("读取音频信息失败: ${it.message}")
        }.getOrNull() ?: return emptyMap()
        val sampleRate = (KavaReflector.readField(info, "sampleRate") as? Number)?.toInt() ?: DEFAULT_SAMPLE_RATE
        val channelCount = (KavaReflector.readField(info, "channelCount") as? Number)?.toInt() ?: DEFAULT_CHANNELS
        return linkedMapOf(
            "sampleRate" to sampleRate,
            "channelCount" to channelCount
        )
    }

    fun decodeAacFile(aacPath: String?, pcmPath: String?): Int {
        val input = validPath(aacPath) ?: return -801
        val output = validPath(pcmPath) ?: return -12
        return callInt("decodeAacFile") {
            AacCodec.decodeAacFile(input, output, null)
        } ?: -803
    }

    fun encodePcmToAac(pcmPath: String?, aacPath: String?, sampleRate: Int, channels: Int): Int {
        val input = validPath(pcmPath) ?: return -901
        val output = validPath(aacPath) ?: return -11
        return callInt("encodePcmToAac") {
            AacCodec.encodePcmToAac(input, output, validSampleRate(sampleRate), validChannels(channels), null)
        } ?: -902
    }

    fun encodePcmToM4a(pcmPath: String?, m4aPath: String?, sampleRate: Int, channels: Int): Int {
        val input = validPath(pcmPath) ?: return -911
        val output = validPath(m4aPath) ?: return -911
        return callInt("encodePcmToM4a") {
            AacCodec.encodePcmToM4a(input, output, validSampleRate(sampleRate), validChannels(channels), null)
        } ?: -912
    }

    fun mp4ToSilk(mp4Path: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(mp4Path) ?: return -801
        val output = validPath(silkPath) ?: return -10
        return callInt("mp4ToSilk") {
            AacCodec.mp4ToSilk(input, output, silkCodec, validSilkHz(hz))
        } ?: -1031
    }

    fun silkToM4a(silkPath: String?, m4aPath: String?, hz: Int): Int {
        val input = validPath(silkPath) ?: return -1001
        val output = validPath(m4aPath) ?: return -911
        return callInt("silkToM4a") {
            AacCodec.silkToM4a(input, output, silkCodec, validSilkHz(hz))
        } ?: -1001
    }

    fun mp4ToM4a(mp4Path: String?, m4aPath: String?, hz: Int): Int {
        val input = validPath(mp4Path) ?: return -801
        val output = validPath(m4aPath) ?: return -911
        return callInt("mp4ToM4a") {
            AacCodec.mp4ToM4a(input, output, validSampleRate(hz))
        } ?: -1061
    }

    fun mp4ToAac(mp4Path: String?, aacPath: String?, hz: Int): Int {
        val input = validPath(mp4Path) ?: return -801
        val output = validPath(aacPath) ?: return -901
        return callInt("mp4ToAac") {
            AacCodec.mp4ToAac(input, output, validSampleRate(hz))
        } ?: -1051
    }

    fun m4aToSilk(m4aPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(m4aPath) ?: return -801
        val output = validPath(silkPath) ?: return -10
        return callInt("m4aToSilk") {
            AacCodec.m4aToSilk(input, output, silkCodec, validSilkHz(hz))
        } ?: -1031
    }

    fun aacToSilk(aacPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(aacPath) ?: return -801
        val output = validPath(silkPath) ?: return -10
        return callInt("aacToSilk") {
            AacCodec.aacToSilk(input, output, silkCodec, validSilkHz(hz))
        } ?: -1031
    }

    fun m4aToAac(m4aPath: String?, aacPath: String?, hz: Int): Int {
        val input = validPath(m4aPath) ?: return -801
        val output = validPath(aacPath) ?: return -901
        return callInt("m4aToAac") {
            AacCodec.m4aToAac(input, output, validSampleRate(hz))
        } ?: -1051
    }

    fun m4aToM4a(m4aPath: String?, m4aPathOut: String?, hz: Int): Int {
        val input = validPath(m4aPath) ?: return -801
        val output = validPath(m4aPathOut) ?: return -911
        return callInt("m4aToM4a") {
            AacCodec.m4aToM4a(input, output, validSampleRate(hz))
        } ?: -1061
    }

    fun autoToAac(inputPath: String?, aacPath: String?, hz: Int): Int {
        val input = validPath(inputPath) ?: return -1
        val output = validPath(aacPath) ?: return -901
        return callInt("autoToAac") {
            AacCodec.autoToAac(input, output, silkCodec, validAutoAacHz(input, hz))
        } ?: -2
    }

    fun autoToM4a(inputPath: String?, m4aPath: String?, hz: Int): Int {
        val input = validPath(inputPath) ?: return -1
        val output = validPath(m4aPath) ?: return -911
        return callInt("autoToM4a") {
            AacCodec.autoToM4a(input, output, silkCodec, validAutoAacHz(input, hz))
        } ?: -2
    }

    fun autoAacToSilk(inputPath: String?, silkPath: String?, hz: Int): Int {
        val input = validPath(inputPath) ?: return -1
        val output = validPath(silkPath) ?: return -10
        return callInt("autoAacToSilk") {
            AacCodec.autoAacToSilk(input, output, silkCodec, validSilkHz(hz))
        } ?: -1031
    }

    fun silkToAac(silkPath: String?, aacPath: String?, hz: Int): Int {
        val input = validPath(silkPath) ?: return -1001
        val output = validPath(aacPath) ?: return -901
        return callInt("silkToAac") {
            AacCodec.silkToAac(input, output, silkCodec, validSilkHz(hz))
        } ?: -1001
    }

    fun aacToPcm(aacPath: String?, pcmPath: String?): Int {
        val input = validPath(aacPath) ?: return -801
        val output = validPath(pcmPath) ?: return -12
        return callInt("aacToPcm") {
            AacCodec.aacToPcm(input, output)
        } ?: -803
    }

    fun pcmToAac(pcmPath: String?, aacPath: String?, sampleRate: Int, channels: Int): Int {
        val input = validPath(pcmPath) ?: return -901
        val output = validPath(aacPath) ?: return -901
        return callInt("pcmToAac") {
            AacCodec.pcmToAac(input, output, validSampleRate(sampleRate), validChannels(channels))
        } ?: -902
    }

    fun pcmToM4a(pcmPath: String?, m4aPath: String?, sampleRate: Int, channels: Int): Int {
        val input = validPath(pcmPath) ?: return -911
        val output = validPath(m4aPath) ?: return -911
        return callInt("pcmToM4a") {
            AacCodec.pcmToM4a(input, output, validSampleRate(sampleRate), validChannels(channels))
        } ?: -912
    }

    fun m4aToPcm(m4aPath: String?, pcmPath: String?): Int {
        val input = validPath(m4aPath) ?: return -801
        val output = validPath(pcmPath) ?: return -12
        return callInt("m4aToPcm") {
            AacCodec.m4aToPcm(input, output)
        } ?: -803
    }

    fun decodeM4aFile(m4aPath: String?, pcmPath: String?): Int {
        val input = validPath(m4aPath) ?: return -801
        val output = validPath(pcmPath) ?: return -12
        return callInt("decodeM4aFile") {
            AacCodec.decodeM4aFile(input, output, null)
        } ?: -803
    }

    fun getDuration(filePath: String?): Long {
        val path = validPath(filePath) ?: return 0L
        return callLong("getDuration") { silkCodec.getDuration(path) } ?: 0L
    }

    fun getDurationLimited(filePath: String?): Long {
        val path = validPath(filePath) ?: return 0L
        return min(getDuration(path), SilkCodec.MAX_DURATION_MS)
    }

    fun getErrorMessage(code: Int): String {
        return when {
            code == 0 -> "成功"
            code == -1 -> "无法获取文件扩展名"
            code == -2 -> "不支持的音频格式"
            code == -3 -> "PCM 转 Silk 需要额外参数"
            code == -4 -> "输入已经是 PCM 格式"
            code == -5 -> "输入已经是 Silk 格式"
            code == -10 -> "输出必须是 .silk 或 .slk"
            code == -11 -> "输出必须是 .mp3"
            code == -12 -> "输出必须是 .pcm 或 .raw"
            code == -13 -> "文件格式与方法不匹配"
            code in -201..-202 -> "Silk 转 MP3 文件错误"
            code == -301 -> "MP3 解码错误"
            code == -302 -> "MP3 文件错误"
            code == -401 -> "OGG 解码错误"
            code == -402 -> "OGG 文件错误"
            code == -501 -> "WAV 解码错误"
            code == -502 -> "WAV 文件错误"
            code == -601 -> "FLAC 解码错误"
            code == -602 -> "FLAC 文件错误"
            code == -701 || code == -703 -> "PCM 参数错误"
            code == -702 -> "PCM 文件错误"
            code == -801 -> "AAC/M4A 解码错误 (文件不存在)"
            code == -802 -> "AAC/M4A 解码错误 (未找到音频轨道)"
            code == -803 -> "AAC/M4A 解码错误 (格式不支持)"
            code == -804 -> "音频解码已取消"
            code in -901..-902 -> "AAC 编码错误"
            code in -911..-912 -> "M4A 编码错误"
            code in -1001..-1009 -> "Silk 转 AAC/M4A 错误"
            code in -1011..-1012 -> "MP3 转 AAC/M4A 错误"
            code in -1021..-1022 -> "WAV 转 AAC/M4A 错误"
            code in -1031..-1039 -> "M4A/AAC 转 Silk 错误"
            code in -1051..-1059 -> "M4A/AAC 转 AAC 错误"
            code in -1061..-1069 -> "M4A/AAC 转 M4A 错误"
            code == -2000 -> "M4A/AAC 转 Silk 错误 (解码失败)"
            else -> runCatching { AacCodec.getErrorMessage(code) }
                .getOrElse { "错误码: $code -> 未知错误" }
        }
    }

    fun startTransform(
        type: Int,
        inputPath: String?,
        outputPath: String?,
        sampleRate: Int,
        callback: Consumer<Any?>?
    ) {
        val input = validPath(inputPath) ?: return
        val output = validPath(outputPath) ?: return
        val silkHz = validSilkHz(sampleRate)
        val audioSampleRate = validSampleRate(sampleRate)
        Thread({
            try {
                val result = when (type) {
                    0 -> silkToMp3(input, output, silkHz)
                    1 -> mp3ToSilk(input, output, silkHz)
                    5 -> autoToSilk(input, output, silkHz)
                    6 -> autoToPcm(input, output)
                    7 -> autoToAac(input, output, audioSampleRate)
                    8 -> autoToM4a(input, output, audioSampleRate)
                    9 -> autoAacToSilk(input, output, silkHz)
                    else -> -2
                }
                if (result == 0) {
                    callback?.accept(linkedMapOf("type" to "progress", "progress" to 100))
                } else {
                    callback?.accept(
                        linkedMapOf(
                            "type" to "message",
                            "message" to "错误码:$result -> ${getErrorMessage(result)}"
                        )
                    )
                }
            } catch (t: Throwable) {
                log("startTransform 失败: ${t.message}")
                callback?.accept(
                    linkedMapOf(
                        "type" to "message",
                        "message" to "异常: ${t.message}"
                    )
                )
            }
        }, "Hchat-Audio-Transform").start()
    }

    private fun validPath(path: String?): String? {
        return path?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun validSilkHz(hz: Int): Int {
        return if (hz in SUPPORTED_SILK_HZ) hz else DEFAULT_HZ
    }

    private fun validSampleRate(sampleRate: Int): Int {
        return if (sampleRate > 0) sampleRate else DEFAULT_SAMPLE_RATE
    }

    private fun validAutoAacHz(inputPath: String, hz: Int): Int {
        val fileType = runCatching { silkCodec.getFileType(inputPath) }.getOrDefault(0)
        return if (fileType == 1) validSilkHz(hz) else validSampleRate(hz)
    }

    private fun validChannels(channels: Int): Int {
        return if (channels > 0) channels else DEFAULT_CHANNELS
    }

    private inline fun callInt(label: String, block: () -> Int): Int? {
        return runCatching(block).onFailure {
            log("$label 失败: ${it.message}")
        }.getOrNull()
    }

    private inline fun callLong(label: String, block: () -> Long): Long? {
        return runCatching(block).onFailure {
            log("$label 失败: ${it.message}")
        }.getOrNull()
    }

    protected fun log(message: String) {
        logger?.invoke(message)
    }
}
