package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

object ScriptPluginAgentSpeech {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText = ""

    fun speak(context: Context, text: String) {
        val content = text.trim()
        if (content.isBlank()) return
        mainHandler.post {
            pendingText = content
            if (ready) {
                speakNow(content)
                return@post
            }
            if (tts != null) return@post
            tts = TextToSpeech(context.applicationContext) { status ->
                mainHandler.post {
                    ready = status == TextToSpeech.SUCCESS
                    if (!ready) {
                        tts?.shutdown()
                        tts = null
                        pendingText = ""
                        return@post
                    }
                    tts?.language = Locale.getDefault()
                    val value = pendingText
                    pendingText = ""
                    if (value.isNotBlank()) speakNow(value)
                }
            }
        }
    }

    private fun speakNow(text: String) {
        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            Bundle(),
            "hchat_script_agent_${System.currentTimeMillis()}"
        )
    }
}
