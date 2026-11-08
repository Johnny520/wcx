package h.Hchat.hooks.items.payment.gift

import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext

/**
 * 自动抢礼物功能入口。
 */
class AutoGiftFeature : BaseFeature() {
    private var hooker: GiftHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "自动抢礼物"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GiftSettingsProvider())
    }

    @Throws(Throwable::class)
    override fun onFeatureInstall(context: FeatureContext) {
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) {
            scheduleInstall(context)
        }
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            try {
                val localHooker = hooker ?: GiftHooker(
                    context.hostContext(),
                    context.hostClassLoader(),
                    context.dexFinder(),
                    GiftSettings(context.hostClassLoader(), context.hostContext())
                ).also { hooker = it }
                for (subscription in localHooker.hookAll()) {
                    trackSubscription(subscription)
                }
                localHooker.isReady()
            } catch (e: Throwable) {
                logError("自动抢礼物安装失败", e)
                false
            }
        }
    }

    companion object {
        const val ID = "auto_gift"
    }
}
