package h.Hchat.hooks.items.floatingshortcut

import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class FloatingShortcutFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "悬浮快捷菜单"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(FloatingShortcutSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        FloatingShortcutRuntime.install(context.hostContext())
        // Activity 由早期注册的 Application 回调统一跟踪，避免重复派发挂载任务。
        trackSubscription(
            WeChatApis.chatPage()?.subscribe { event ->
                FloatingShortcutRuntime.onChatPageChanged(event.isEnter)
            }
        )
        WeChatApis.currentActivity()?.currentActivity()?.let(FloatingShortcutRuntime::attach)
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        FloatingShortcutRuntime.destroy()
    }

    companion object {
        const val ID = "floating_shortcut_menu"
    }
}

private class FloatingShortcutSettingsProvider : SimpleFeatureSettingsProvider(
    FloatingShortcutFeature.ID,
    "悬浮快捷菜单",
    "展开插件 Agent、自定义快捷项或微信页面",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
