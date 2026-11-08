package h.Hchat.hooks.items.realtail

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.groupnicknamecolor.GroupNicknameColorSettingsProvider
import h.Hchat.hooks.items.groupnicknamecolor.GroupNicknameColorStore

class RealNameTailFeature : BaseFeature() {
    private var scheduler: RealNameTailScheduler? = null
    private var store: RealNameTailStore? = null
    private var renderer: RealNameTailRenderer? = null
    private var profilePrefetcher: ContactProfilePrefetcher? = null

    override fun featureId(): String = ID

    override fun name(): String = "实名尾字"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(RealNameTailSettingsProvider())
        registerSettingsProvider(GroupNicknameColorSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val localStore = RealNameTailStore(context.hostContext())
        val nicknameColorStore = GroupNicknameColorStore(context.hostContext())
        store = localStore
        val query = BeforeTransferNameQuery(context, ::logFeatureError)
        val scheduler = RealNameTailScheduler(localStore, query, ::logFeatureError) { wxid ->
            renderer?.applyForSender(wxid)
        }
        this.scheduler = scheduler
        val prefetcher = ContactProfilePrefetcher(context, ::logFeatureError) { wxid ->
            renderer?.applyForSender(wxid)
        }
        profilePrefetcher = prefetcher
        renderer = RealNameTailRenderer(context, localStore, nicknameColorStore, scheduler, ::logFeatureError).also {
            it.profilePrefetcher = prefetcher
        }
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
        val observer = WeChatApis.message().observe() ?: return
        trackSubscription(observer.subscribe { message ->
            if (!localStore.isEnabled() || !localStore.messageQueryEnabled()) return@subscribe
            if (!message.isGroupChat() || message.isSend()) return@subscribe
            val roomId = message.talker
            val sender = message.sender
            if (sender.isBlank() || isSelf(sender)) return@subscribe
            scheduler.onMessage(roomId, sender)
            if (localStore.hasTail(sender)) renderer?.applyForSender(sender)
        })
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        scheduler?.destroy()
        scheduler = null
        renderer?.profilePrefetcher = null
        renderer = null
        profilePrefetcher = null
        store = null
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            renderer?.install() == true
        }
    }

    private fun isSelf(wxid: String): Boolean {
        val self = WeChatApis.account()?.selfWxId().orEmpty()
        return self.isNotEmpty() && self == wxid
    }

    companion object {
        const val ID = "real_name_tail"
    }
}
