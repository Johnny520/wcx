package me.yun.silk.utils

import me.yun.silk.AacCodec
import me.yun.silk.SilkCodec

class Conversion {
    interface ConversionCallback {
        fun onMessage(msg: String)
        fun onProgress(progress: Int) {}
    }

    companion object {
        @JvmStatic
        fun startTransform(
            codec: SilkCodec,
            type: Int,
            inputPath: String,
            outputPath: String,
            sampleRate: Int,
            callback: ConversionCallback
        ) {
            Thread {
                try {
                    val result = when (type) {
                        0 -> codec.silkToMp3(inputPath, outputPath, sampleRate)
                        1 -> codec.mp3ToSilk(inputPath, outputPath, sampleRate)
                        5 -> AacCodec.autoToSilkCompat(
                            inputPath,
                            outputPath,
                            codec,
                            sampleRate
                        )
                        6 -> AacCodec.autoToPcmCompat(inputPath, outputPath, codec)
                        7 -> AacCodec.autoToAac(inputPath, outputPath, codec, sampleRate)
                        8 -> AacCodec.autoToM4a(inputPath, outputPath, codec, sampleRate)
                        9 -> AacCodec.m4aToSilk(inputPath, outputPath, codec, sampleRate)
                        else -> -2
                    }

                    if (result == 0) {
                        callback.onProgress(100)
                    } else {
                        callback.onMessage(getErrorMsg(result))
                    }
                } catch (t: Throwable) {
                    callback.onMessage("异常: ${t.message}")
                }
            }.start()
        }

        @JvmStatic
        fun silkToAac(codec: SilkCodec, silkPath: String, aacPath: String, hz: Int): Int {
            return AacCodec.silkToAac(silkPath, aacPath, codec, hz)
        }

        @JvmStatic
        fun silkToM4a(codec: SilkCodec, silkPath: String, m4aPath: String, hz: Int): Int {
            return AacCodec.silkToM4a(silkPath, m4aPath, codec, hz)
        }

        @JvmStatic
        fun m4aToSilk(codec: SilkCodec, m4aPath: String, silkPath: String, hz: Int): Int {
            return AacCodec.m4aToSilk(m4aPath, silkPath, codec, hz)
        }

        @JvmStatic
        fun m4aToPcm(m4aPath: String, pcmPath: String): Int {
            return AacCodec.m4aToPcm(m4aPath, pcmPath)
        }

        @JvmStatic
        fun pcmToAac(pcmPath: String, aacPath: String, sampleRate: Int, channels: Int): Int {
            return AacCodec.pcmToAac(pcmPath, aacPath, sampleRate, channels)
        }

        @JvmStatic
        fun pcmToM4a(pcmPath: String, m4aPath: String, sampleRate: Int, channels: Int): Int {
            return AacCodec.pcmToM4a(pcmPath, m4aPath, sampleRate, channels)
        }

        private fun getErrorMsg(code: Int): String {
            return when (code) {
                0 -> "成功"
                -1 -> "错误码:-1 -> 无法获取文件扩展名"
                -2 -> "错误码:-2 -> 不支持的音频格式"
                -3 -> "错误码:-3 -> PCM 转 Silk 需要额外参数"
                -4 -> "错误码:-4 -> 输入已经是 PCM 格式"
                -5 -> "错误码:-5 -> 输入已经是 Silk 格式"
                -10 -> "错误码:-10 -> 输出必须是 .silk 或 .slk"
                -11 -> "错误码:-11 -> 输出必须是 .mp3"
                -12 -> "错误码:-12 -> 输出必须是 .pcm 或 .raw"
                -13 -> "错误码:-13 -> 文件格式与方法不匹配"
                -201, -202 -> "错误码:$code -> Silk 转 MP3 文件错误"
                -301, -302 -> "错误码:$code -> MP3 解码错误"
                -401, -402 -> "错误码:$code -> OGG 解码错误"
                -501, -502 -> "错误码:$code -> WAV 解码错误"
                -601, -602 -> "错误码:$code -> FLAC 解码错误"
                -701, -702, -703 -> "错误码:$code -> PCM 参数错误"
                -801, -802 -> "错误码:$code -> AAC/M4A 解码错误 (文件读取失败)"
                -803 -> "错误码:-803 -> AAC/M4A 解码错误 (格式不支持)"
                -901, -902 -> "错误码:$code -> AAC/M4A 编码错误"
                -911, -912 -> "错误码:$code -> M4A 编码错误"
                -1001 -> "错误码:-1001 -> Silk 转 AAC/M4A 错误"
                -1011, -1012 -> "错误码:$code -> MP3 转 AAC/M4A 错误"
                -1021, -1022 -> "错误码:$code -> WAV 转 AAC/M4A 错误"
                -1031 -> "错误码:-1031 -> AAC/M4A 转 Silk 错误"
                -1041 -> "错误码:-1041 -> 中间转换错误"
                -2000 -> "错误码:-2000 -> M4A/AAC 转 Silk 错误 (解码失败)"
                else -> "错误码:$code -> 未知错误"
            }
        }
    }
}
