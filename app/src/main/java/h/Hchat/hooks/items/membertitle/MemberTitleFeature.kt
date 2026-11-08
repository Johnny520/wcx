package h.Hchat.hooks.items.membertitle

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext

class MemberTitleFeature : BaseFeature() {
    private var renderer: MemberTitleRenderer? = null

    override fun featureId(): String = ID

    override fun name(): String = "群员头衔"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MemberTitleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val store = MemberTitleStore(context.hostContext())
        renderer = MemberTitleRenderer(context, store, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
        val chatroomChanges = WeChatApis.contact().chatroomChanges() ?: return
        trackSubscription(chatroomChanges.subscribe { change ->
            if (!store.isEnabled()) return@subscribe
            val roomId = change.chatroomId()
            if (roomId.isBlank()) return@subscribe
            renderer?.refreshRoom(roomId)
        })
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            renderer?.install() == true
        }
    }

    companion object {
        const val ID = "member_title"
    }
}
