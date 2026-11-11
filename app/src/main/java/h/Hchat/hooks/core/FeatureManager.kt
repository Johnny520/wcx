package h.Hchat.hooks.core

import h.Hchat.event.Events
import h.Hchat.hooks.items.settings.SettingsFeature
import h.Hchat.hooks.items.conversationgroup.ConversationGroupHomeProjection
import h.Hchat.preferences.TermsGate
import h.Hchat.utils.HLog
import java.util.Collections

/**
 * 功能模块调度器。
 */
class FeatureManager {
    private val features = ArrayList<Feature>()
    private val installedFeatures = ArrayList<Feature>()

    fun register(feature: Feature?): FeatureManager {
        if (feature != null) {
            features.add(feature)
        }
        return this
    }

    fun features(): List<Feature> = Collections.unmodifiableList(features)

    fun installedFeatures(): List<Feature> = Collections.unmodifiableList(installedFeatures)

    fun installAll(context: FeatureContext) {
        val termsAccepted = TermsGate.isAccepted(context.configStore())
        val initializedFeatures = ArrayList<Feature>()
        for (feature in features) {
            val displayName = safeName(feature)
            if (!termsAccepted && feature.featureId() != SettingsFeature.ID) {
                continue
            }
            try {
                feature.onInit(context)
                initializedFeatures.add(feature)
            } catch (e: Throwable) {
                HLog.e("$TAG onInit 失败: $displayName, error=$e", e)
            }
        }

        for (feature in initializedFeatures) {
            val displayName = safeName(feature)
            try {
                if (!feature.isEnabled(context)) {
                    continue
                }
            } catch (e: Throwable) {
                HLog.e("$TAG isEnabled 检查异常，默认启用: $displayName", e)
            }

            try {
                feature.install(context)
                installedFeatures.add(feature)
                try {
                    context.eventBus().post(Events.FeatureInstalled(displayName))
                } catch (_: Throwable) {
                }
            } catch (e: Throwable) {
                HLog.e("$TAG 功能安装失败: $displayName, error=$e", e)
            }
        }
        DexInstallScheduler.markDexBridgeReady()
    }

    fun notifyConfigChanged(context: FeatureContext, featureId: String?, key: String?) {
        for (feature in installedFeatures) {
            if (featureId != null && featureId != feature.featureId()) continue
            try {
                feature.onConfigChanged(context, key)
            } catch (e: Throwable) {
                HLog.e("$TAG onConfigChanged 异常: ${safeName(feature)}, error=$e", e)
            }
        }
    }

    fun destroyAll(context: FeatureContext) {
        DexInstallScheduler.cancelAll()
        // 先使旧安装租约失效，不等待后台定位，也不允许销毁后重新挂钩。
        HookRegistry.get().unhookAll()
        for (i in installedFeatures.size - 1 downTo 0) {
            val feature = installedFeatures[i]
            try {
                feature.onDestroy(context)
            } catch (e: Throwable) {
                HLog.e("$TAG onDestroy 异常: ${safeName(feature)}, error=$e", e)
            }
        }
        installedFeatures.clear()
        // 首页投影由聊天分组与标签栏共享，只在全量卸载后复位。
        ConversationGroupHomeProjection.resetAfterUnhookAll()
    }

    private fun safeName(feature: Feature): String {
        return try {
            val name = feature.name()
            if (name.isEmpty()) feature.javaClass.name else name
        } catch (_: Throwable) {
            feature.javaClass.name
        }
    }

    companion object {
        private const val TAG = "[Hchat:FeatureManager]"
    }
}
