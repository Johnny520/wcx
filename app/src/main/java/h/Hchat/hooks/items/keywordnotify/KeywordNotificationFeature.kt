package h.Hchat.hooks.items.keywordnotify

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.HLog

class KeywordNotificationFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "关键词通知"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(KeywordNotificationSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        subscribe(Events.DexReady::class.java) {
            val observe = WeChatApis.message().observe()
            if (observe == null) {
                HLog.e("$TAG 消息观察 API 未就绪")
                return@subscribe
            }
            trackSubscription(observe.subscribe { message ->
                KeywordNotificationRuntime.handleMessage(context.hostContext(), message)
            })
        }
        subscribe(Events.MessageBlocked::class.java) { event ->
            KeywordNotificationRuntime.handleBlockedMessage(context.hostContext(), event)
        }
    }

    companion object {
        const val ID = "keyword_notification"
        private const val TAG = "[Hchat:KeywordNotification]"
    }
}
