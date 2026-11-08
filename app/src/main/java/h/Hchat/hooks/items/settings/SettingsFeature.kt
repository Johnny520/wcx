package h.Hchat.hooks.items.settings

import h.Hchat.crash.CrashReportSettingsProvider
import h.Hchat.dexkit.SettingsDexFinder
import h.Hchat.hooks.api.ui.SettingsInjector
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.Feature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.quickread.QuickMarkReadRuntime
import h.Hchat.hooks.items.quickterminate.QuickTerminateRuntime
import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.utils.HLog

/**
 * 微信内设置入口注入功能。
 */
class SettingsFeature : Feature {
    override fun featureId(): String = ID

    override fun name(): String = "设置入口"

    @Throws(Throwable::class)
    override fun onInit(context: FeatureContext) {
        context.uiRegistry().registerProvider(SettingsEntryProvider())
        context.uiRegistry().registerProvider(PluginAgentEntryProvider())
        context.uiRegistry().registerProvider(CrashReportSettingsProvider())
    }

    @Throws(Throwable::class)
    override fun install(context: FeatureContext) {
        DexInstallScheduler.runDexKitTask {
            installSettingsHook(context)
        }
    }

    private fun installSettingsHook(context: FeatureContext) {
        try {
            val settingsDexFinder = SettingsDexFinder(
                context.dexKitBridge(),
                context.hostClassLoader(),
                context.hostContext()
            )
            val entrySettings = SettingsEntrySettings(context.hostContext())
            settingsDexFinder.resolveAll(
                entrySettings.plusMenuEnabled() ||
                    entrySettings.pluginAgentPlusMenuEnabled() ||
                    QuickMarkReadRuntime.isPlusMenuEnabled(context.hostContext()) ||
                    QuickTerminateRuntime.isEnabled(context.hostContext())
            )

            val injector = SettingsInjector(
                context.hostContext(),
                context.hostClassLoader(),
                settingsDexFinder,
                entrySettings
            )
            injector.hookSettings()
        } catch (e: Throwable) {
            HLog.e("[Hchat:Settings] 设置入口安装失败", e)
        }
    }

    companion object {
        const val ID = "settings"
    }
}

class SettingsEntryProvider : FeatureSettingsProvider {
    override fun featureId(): String = SettingsFeature.ID

    override fun title(): String = "入口设置"

    override fun subtitle(): String = "控制加号菜单和长按加号入口"

    override fun category(): String = FeatureSettingsProvider.CATEGORY_PRACTICAL
}

class PluginAgentEntryProvider : FeatureSettingsProvider {
    override fun featureId(): String = ID

    override fun title(): String = "插件 Agent 入口"

    override fun subtitle(): String = "控制右上角加号菜单中的 Agent 入口"

    override fun category(): String = FeatureSettingsProvider.CATEGORY_PRACTICAL

    companion object {
        const val ID = "plugin_agent_entry"
    }
}
