package h.Hchat.hooks.items.conversationgroup

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext

class ConversationGroupFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "聊天分组"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ConversationGroupSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val generation = ConversationGroupRuntime.beginInstall()
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.BRIDGE) {
            if (!ConversationGroupRuntime.isInstallGenerationActive(generation)) {
                true
            } else {
                ConversationGroupRuntime.install(context, generation)
            }
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        ConversationGroupRuntime.destroy(context)
    }

    companion object {
        const val ID = "conversation_group"
    }
}
