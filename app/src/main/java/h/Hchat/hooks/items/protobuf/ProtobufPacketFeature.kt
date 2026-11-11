package h.Hchat.hooks.items.protobuf

import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage

class ProtobufPacketFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "Protobuf"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ProtobufPacketSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) {
            scheduleInstall(context)
        }
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            try {
                val prefs = HchatStorage.preferences(context.hostContext(), ProtobufPacketSettings.PREFS_NAME)
                val hook = ProtobufPacketHook(
                    context.hostClassLoader(),
                    context.dexFinder(),
                    prefs,
                    ProtobufPacketFileLogger(context.hostContext())
                )
                val ok = hook.install()
                if (ok) ProtobufPacketRuntime.install(hook)
                ok
            } catch (e: Throwable) {
                logError("Protobuf 安装失败", e)
                false
            }
        }
    }

    companion object {
        const val ID = "protobuf_packet"
    }
}
