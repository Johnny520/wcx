package h.Hchat.hooks.items.customnotify

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.HLog

class CustomNotificationFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "自定义通知"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(CustomNotificationSettingsProvider())
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
                CustomNotificationRuntime.handleMessage(context.hostContext(), message)
            })
        }
    }

    companion object {
        const val ID = "custom_notification"
        private const val TAG = "[Hchat:CustomNotification]"
    }
}
