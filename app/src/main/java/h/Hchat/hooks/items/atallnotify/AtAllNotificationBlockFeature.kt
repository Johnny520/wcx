package h.Hchat.hooks.items.atallnotify

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.customnotify.CustomNotificationRuntime
import h.Hchat.utils.HLog

class AtAllNotificationBlockFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "屏蔽艾特所有人"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AtAllNotificationBlockSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        CustomNotificationRuntime.install(context)
        subscribe(Events.DexReady::class.java) {
            val observe = WeChatApis.message().observe()
            if (observe == null) {
                HLog.e("$TAG 消息观察 API 未就绪")
                return@subscribe
            }
            trackSubscription(observe.subscribe { message ->
                AtAllNotificationBlockRuntime.record(context.hostContext(), message)
            })
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        AtAllNotificationBlockRuntime.clear()
    }

    companion object {
        const val ID = "block_at_all_notification"
        private const val TAG = "[Hchat:BlockAtAllNotification]"
    }
}
