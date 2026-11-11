package h.Hchat.hooks.items.script

import h.Hchat.event.Events
import h.Hchat.dexkit.SettingsDexFinder
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.quickread.QuickMarkReadRuntime
import h.Hchat.hooks.items.shortvideo.FinderMediaDownloadSupport

class ScriptPluginFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "脚本插件"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ScriptPluginSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        QuickMarkReadRuntime.install(context)
        ScriptPluginRuntime.install(context)
        scheduleDexHooks(context)
        subscribe(Events.DexReady::class.java) {
            scheduleDexHooks(context)
            ScriptPluginRuntime.loadEnabledPluginsWhenReady(context.hostContext())
        }
    }

    private fun scheduleDexHooks(context: FeatureContext) {
        DexInstallScheduler.schedule(
            "shared:finder_feed_detail",
            "视频号详情解析",
            stage = DexInstallScheduler.Stage.WARMUP
        ) {
            FinderMediaDownloadSupport.install(context)
        }
        DexInstallScheduler.schedule("$ID:menus", "${name()}-菜单", stage = DexInstallScheduler.Stage.WARMUP) {
            val finder = SettingsDexFinder(
                context.dexKitBridge(),
                context.hostClassLoader(),
                context.hostContext()
            )
            finder.resolveAll(true)
            ScriptMenuDispatcher.install(context, finder, ::logFeatureError)
        }
        DexInstallScheduler.schedule("shared:send_button", "聊天发送按钮", stage = DexInstallScheduler.Stage.WARMUP) {
            ScriptSendButtonHook.install(context)
        }
        DexInstallScheduler.schedule("$ID:message", "${name()}-消息监听", stage = DexInstallScheduler.Stage.WARMUP) {
            ScriptMessageHook.install(context)
        }
        DexInstallScheduler.schedule("$ID:new_friend", "${name()}-好友申请", stage = DexInstallScheduler.Stage.WARMUP) {
            ScriptNewFriendHook.install(context)
        }
        DexInstallScheduler.schedule("$ID:member_change", "${name()}-成员变动", stage = DexInstallScheduler.Stage.WARMUP) {
            ScriptMemberChangeHook.install(context)
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "script_plugin"
    }
}
