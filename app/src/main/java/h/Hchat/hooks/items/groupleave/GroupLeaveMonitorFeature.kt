package h.Hchat.hooks.items.groupleave

import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext

class GroupLeaveMonitorFeature : BaseFeature() {
    private var monitor: GroupLeaveMonitorRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "进退群监控"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GroupLeaveMonitorSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        GroupLeaveMonitorHighlighter.install(context, ::logError)
        val runtime = GroupLeaveMonitorRuntime(context, ::logError)
        monitor = runtime
        runtime.install { subscription ->
            trackSubscription(subscription)
        }
        subscribe(Events.DexReady::class.java) {
            runtime.preloadSnapshots()
        }
    }

    companion object {
        const val ID = "group_leave_monitor"
    }
}
