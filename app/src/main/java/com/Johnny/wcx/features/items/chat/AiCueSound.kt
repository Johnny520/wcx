package com.Johnny.wcx.features.items.chat

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.utils.WeLogger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * 双击提示音。两种音色都是现场合成的短音，不带音频资源（资源要打进 APK、换音色得重新打包）。
 *
 * 共同点：整体压在 1kHz 以下、不做高频泛音、包络很短 —— 铃铛那种 1kHz 以上的长音最刺耳。
 *
 * - [Style.WOOD]：一下木质敲击，300Hz 基频，约 90ms 收干净；
 * - [Style.DEEP]：一声低频水滴，160→340Hz，衰减更慢、更柔和。
 *
 * 走 **媒体** 通道（`USAGE_MEDIA`）而不是提示音通道：后者挂在系统音量的「提示音」档上，
 * MIUI 上静音或该档为 0 时完全没有声音（实机上就是这么「哑」的）。
 */
internal object AiCueSound {

    /** 音色。id 存进 `AiConfig`，增删枚举不会让已选的音色错位。 */
    enum class Style(val id: String, val label: String, val hint: String, val durationMs: Int) {
        WOOD(AiConfig.CUE_STYLE_WOOD, "木质", "一下低沉的敲击", 220),
        DEEP(AiConfig.CUE_STYLE_DEEP, "低沉", "一声柔和的低频水滴", 460),
        CRISP("crisp", "清脆", "短促的一声高音", 180),
        BELL("bell", "叮咚", "两声上行的短铃", 340),
        SOFT("soft", "柔和", "一段平缓的正弦，安静环境用", 380),
        KNOCK("knock", "叩门", "两下快速的木质叩击", 300);

        companion object {
            /** id → 序号；未知 id 落到第一个，不抛异常。 */
            fun indexOf(id: String): Int = values().indexOfFirst { it.id == id }.coerceAtLeast(0)
        }
    }

    private const val TAG = "AiCueSound"
    private const val SAMPLE_RATE = 44_100

    /** 连点两轮双击时不要叠成两声。 */
    @Volatile
    private var playing = false

    /** 双击触发时调用：关掉开关就什么都不做。 */
    fun play() {
        if (!AiConfig.isCueSoundEnabled) return
        preview(Style.indexOf(AiConfig.cueSoundId))
    }

    /** 试听指定音色。设置页用它，**不受开关影响** —— 关着也能先听再决定。 */
    fun preview(style: Int) {
        if (playing) return
        playing = true
        val resolved = style.coerceIn(0, Style.values().lastIndex)
        Thread {
            runCatching { playNow(resolved) }.onFailure { WeLogger.w(TAG, "提示音播放失败", it) }
            playing = false
        }.apply { isDaemon = true }.start()
    }

    private fun playNow(style: Int) {
        val pcm = render(style)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        try {
            // 静态模式下写入之前 state 恒为 STATE_NO_STATIC_DATA(2)，写完才变 STATE_INITIALIZED。
            // 所以这里不能拿 state 当门槛（之前就是被它挡住、一声没出），只看写入结果。
            val written = track.write(pcm, 0, pcm.size)
            if (written <= 0) {
                WeLogger.w(TAG, "写入失败：written=$written state=${track.state}")
                return
            }
            track.play()
            val durationMs = pcm.size * 1000L / SAMPLE_RATE
            WeLogger.d(TAG, "播放提示音 #$style：$written 帧 / ${durationMs}ms")
            Thread.sleep(durationMs + 120L)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun render(style: Int): ShortArray {
        val spec = Style.values().getOrElse(style) { Style.WOOD }
        val total = SAMPLE_RATE * spec.durationMs / 1000
        val pcm = ShortArray(total)
        for (index in 0 until total) {
            val time = index.toFloat() / SAMPLE_RATE
            val value = when (spec) {
                Style.WOOD -> wood(time)
                Style.DEEP -> deep(time)
                Style.CRISP -> crisp(time)
                Style.BELL -> bell(time)
                Style.SOFT -> soft(time)
                Style.KNOCK -> knock(time)
            }
            pcm[index] = (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
        return pcm
    }

    /** 木质敲击：300Hz 基频 + 很弱的 600Hz，衰减极快（约 90ms 收干净）。 */
    private fun wood(time: Float): Double {
        val attack = (time / 0.0015f).coerceAtMost(1f)
        val seconds = time.toDouble()
        val fundamental = sin(2.0 * PI * 300.0 * seconds) * exp(-38.0 * seconds)
        val partial = sin(2.0 * PI * 600.0 * seconds) * exp(-55.0 * seconds) * 0.18
        return (fundamental + partial) * attack * 0.34
    }

    /** 低频水滴：160 → 340Hz 上滑 130ms，衰减更慢，尾巴更柔。 */
    private fun deep(time: Float): Double {
        val span = 0.13f
        val sweep = time.coerceAtMost(span)
        val tail = time - sweep
        val from = 160.0
        val to = 340.0
        // 上滑段结束后频率固定在 340Hz，否则尾巴会继续往上飘、又回到刺耳的高频区。
        val phase = 2.0 * PI * (
            from * sweep + (to - from) * sweep * sweep / (2.0 * span) + to * tail
            )
        val attack = (time / 0.008f).coerceAtMost(1f)
        return sin(phase) * exp(-8.5 * time.toDouble()) * attack * 0.38
    }

    /** 清脆：760Hz 主音 + 很弱的 1520Hz 泛音，衰减快，180ms 内收干净。 */
    private fun crisp(time: Float): Double {
        val t = time.toDouble()
        val attack = (time / 0.001f).coerceAtMost(1f)
        val fundamental = sin(2.0 * PI * 760.0 * t) * exp(-24.0 * t)
        val partial = sin(2.0 * PI * 1520.0 * t) * exp(-40.0 * t) * 0.22
        return (fundamental + partial) * attack * 0.30
    }

    /** 叮咚：660Hz → 880Hz 两声，每声约 120ms，第二声起音稍软。 */
    private fun bell(time: Float): Double {
        val t = time.toDouble()
        val firstEnd = 0.11
        return if (t < firstEnd) {
            sin(2.0 * PI * 660.0 * t) * exp(-14.0 * t) * 0.28
        } else {
            val u = t - firstEnd
            val attack = (u / 0.004).coerceAtMost(1.0)
            sin(2.0 * PI * 880.0 * u) * exp(-15.0 * u) * attack * 0.30
        }
    }

    /** 柔和：440Hz 单一正弦 + 慢起慢落，适合安静环境。 */
    private fun soft(time: Float): Double {
        val t = time.toDouble()
        val attack = (time / 0.02f).coerceAtMost(1f)
        return sin(2.0 * PI * 440.0 * t) * exp(-6.0 * t) * attack * 0.26
    }

    /** 叩门：两下 280Hz 的木质敲击，间隔 90ms。 */
    private fun knock(time: Float): Double {
        val t = time.toDouble()
        val gap = 0.09
        val beat = if (t < gap) t else t - gap
        val attack = (beat / 0.0015).coerceAtMost(1.0)
        return sin(2.0 * PI * 280.0 * beat) * exp(-42.0 * beat) * attack * 0.32
    }
}
