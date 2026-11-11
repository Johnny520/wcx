package h.Hchat.hooks.items.grouprename

import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.groupleave.GroupLeaveMonitorHighlighter

class GroupRenameMonitorFeature : BaseFeature() {
    private var runtime: GroupRenameMonitorRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "改名监控"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GroupRenameMonitorSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        GroupLeaveMonitorHighlighter.installProfileLinkClick(context, ::logError)
        val monitor = GroupRenameMonitorRuntime(context, ::logError)
        runtime = monitor
        monitor.install { subscription ->
            trackSubscription(subscription)
        }
        subscribe(Events.DexReady::class.java) {
            monitor.preloadSnapshots()
        }
    }

    companion object {
        const val ID = "group_rename_monitor"
    }
}
