package h.Hchat.hooks.items.textvoice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import h.Hchat.hooks.items.textspeech.TextSpeechEngineCatalog
import h.Hchat.utils.KavaReflector
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class AndroidTtsFileSynthesizer(private val context: Context) {
    private val engines = Collections.newSetFromMap(ConcurrentHashMap<TextToSpeech, Boolean>())
    private val cancellationGeneration = AtomicLong(0L)

    fun synthesize(
        text: String,
        enginePackage: String,
        voiceName: String,
        speechRate: Float,
        english: Boolean,
        target: File
    ): File {
        val appContext = context.applicationContext ?: context
        val generation = cancellationGeneration.get()
        val failures = arrayListOf<String>()
        var lastFailure: Throwable? = null
        for (candidate in TextSpeechEngineCatalog.connectionCandidates(appContext, enginePackage)) {
            ensureNotCancelled(generation)
            try {
                return synthesizeWithEngine(
                    appContext = appContext,
                    text = text,
                    enginePackage = candidate,
                    voiceName = voiceName,
                    speechRate = speechRate,
                    english = english,
                    target = target,
                    generation = generation
                )
            } catch (error: TtsInitializationException) {
                failures += "${candidate.ifBlank { "系统默认" }}：${error.message.orEmpty()}"
                lastFailure = error
                if (enginePackage.isNotBlank()) throw error
            }
        }
        val detail = failures.takeLast(3).joinToString("；")
        throw IllegalStateException(
            if (detail.isBlank()) "TTS 引擎初始化失败" else "可用 TTS 引擎均初始化失败（$detail）",
            lastFailure
        )
    }

    private fun synthesizeWithEngine(
        appContext: Context,
        text: String,
        enginePackage: String,
        voiceName: String,
        speechRate: Float,
        english: Boolean,
        target: File,
        generation: Long
    ): File {
        val initLatch = CountDownLatch(1)
        val initStatus = AtomicInteger(TextToSpeech.ERROR)
        val listener = TextToSpeech.OnInitListener { status ->
            initStatus.set(status)
            initLatch.countDown()
        }
        val engine = try {
            if (enginePackage.isBlank()) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, enginePackage)
            }
        } catch (error: Throwable) {
            throw TtsInitializationException("创建失败", error)
        }
        engines += engine
        try {
            ensureNotCancelled(generation)
            if (!initLatch.await(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw TtsInitializationException("连接超时")
            }
            ensureNotCancelled(generation)
            if (initStatus.get() != TextToSpeech.SUCCESS) {
                throw TtsInitializationException("状态 ${initStatus.get()}")
            }
            val activeEngine = (KavaReflector.readField(engine, "mCurrentEngine") as? String)
                .orEmpty()
                .trim()
            if (enginePackage.isNotBlank() && activeEngine.isNotBlank() && activeEngine != enginePackage) {
                throw TtsInitializationException("系统回退到 $activeEngine")
            }
            if (voiceName.isNotBlank()) {
                val voice = engine.voices?.firstOrNull { it.name == voiceName }
                    ?: error("所选 TTS 角色已不可用")
                if (engine.setVoice(voice) == TextToSpeech.ERROR) error("TTS 角色设置失败")
            } else {
                engine.setLanguage(if (english) Locale.US else Locale.SIMPLIFIED_CHINESE)
            }
            if (engine.setSpeechRate(TextVoiceSettings.normalizeSpeechRate(speechRate)) == TextToSpeech.ERROR) {
                error("TTS 语速设置失败")
            }

            target.parentFile?.let { dir ->
                if (!dir.isDirectory && !dir.mkdirs()) error("无法创建语音缓存目录")
            }
            target.delete()
            val utteranceId = "hchat_${UUID.randomUUID()}"
            val completionLatch = CountDownLatch(1)
            val synthesisError = AtomicReference<String?>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId) completionLatch.countDown()
                }

                @Deprecated("Deprecated in Android")
                override fun onError(id: String?) {
                    if (id == utteranceId) {
                        synthesisError.set("TTS 合成失败")
                        completionLatch.countDown()
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) {
                        synthesisError.set("TTS 合成失败: $errorCode")
                        completionLatch.countDown()
                    }
                }
            })
            val result = engine.synthesizeToFile(
                text,
                Bundle(),
                target,
                utteranceId
            )
            if (result == TextToSpeech.ERROR) error("TTS 引擎拒绝合成")
            if (!completionLatch.await(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                error("TTS 合成超时")
            }
            synthesisError.get()?.let(::error)
            if (!target.isFile || target.length() <= 0L) error("TTS 未生成语音文件")
            if (target.length() > MAX_AUDIO_BYTES) error("语音文件超过 16 MiB")
            return target
        } catch (throwable: Throwable) {
            target.delete()
            throw throwable
        } finally {
            engines -= engine
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
    }

    fun cancelAll() {
        cancellationGeneration.incrementAndGet()
        engines.toList().forEach { engine ->
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
        }
        engines.clear()
    }

    private fun ensureNotCancelled(generation: Long) {
        if (generation != cancellationGeneration.get() || Thread.currentThread().isInterrupted) {
            throw InterruptedException("TTS 合成已取消")
        }
    }

    private class TtsInitializationException(
        message: String,
        cause: Throwable? = null
    ) : IllegalStateException(message, cause)

    private companion object {
        const val INIT_TIMEOUT_SECONDS = 15L
        const val SYNTHESIS_TIMEOUT_SECONDS = 60L
        const val MAX_AUDIO_BYTES = 16L * 1024L * 1024L
    }
}
