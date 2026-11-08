package h.Hchat.hooks.items.textvoice

import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.script.ScriptSendButtonHook

class TextVoiceFeature : BaseFeature() {
    private var runtime: TextVoiceRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "文本转语音"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(TextVoiceSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val current = TextVoiceRuntime(context, ::logFeatureError)
        runtime = current
        trackSubscription(
            ScriptSendButtonHook.registerHandler(ID) { text -> current.handleSendButton(text) }
        )
        scheduleSendButtonHook(context)
        scheduleMessageMenuInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleSendButtonHook(context)
            scheduleMessageMenuInstall()
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleSendButtonHook(context: FeatureContext) {
        DexInstallScheduler.schedule(
            "shared:send_button",
            "聊天发送按钮",
            stage = DexInstallScheduler.Stage.WARMUP
        ) {
            ScriptSendButtonHook.install(context)
        }
    }

    private fun scheduleMessageMenuInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.installMessageMenu() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "text_voice"
    }
}
