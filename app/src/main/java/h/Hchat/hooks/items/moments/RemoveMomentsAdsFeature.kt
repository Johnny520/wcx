package h.Hchat.hooks.items.moments

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.util.HashSet

class RemoveMomentsAdsFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "去除朋友圈广告"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(RemoveMomentsAdsSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "remove_moments_ads"
        private const val TAG = "[Hchat:MomentsAds]"
        private const val AD_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.ADInfo"
        private val installedLoaders = HashSet<String>()

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val sp = HchatStorage.preferences(context, RemoveMomentsAdsSettings.PREFS_NAME)
            return sp.getBoolean(
                RemoveMomentsAdsSettings.KEY_ENABLE,
                RemoveMomentsAdsSettings.DEFAULT_ENABLE
            )
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null) return
            val key = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
            synchronized(installedLoaders) {
                if (installedLoaders.contains(key)) return
                val clazz = KavaReflector.loadClass(AD_INFO_CLASS, classLoader)
                if (clazz == null) {
                    HLog.e("$TAG 未找到朋友圈广告信息类: $AD_INFO_CLASS")
                    return
                }
                val constructor = KavaReflector.findConstructor(clazz, String::class.java)
                if (constructor == null) {
                    HLog.e("$TAG 未找到朋友圈广告信息构造方法: $AD_INFO_CLASS(String)")
                    return
                }
                runCatching {
                    HookRegistry.get().hook(constructor, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled(context)) return
                            param.result = null
                        }
                    })
                    installedLoaders.add(key)
                }.onFailure {
                    HLog.e("$TAG Hook 朋友圈广告信息失败: ${it.message}", it)
                }
            }
        }
    }
}
