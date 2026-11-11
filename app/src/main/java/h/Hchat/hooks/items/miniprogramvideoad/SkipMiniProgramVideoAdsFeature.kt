package h.Hchat.hooks.items.miniprogramvideoad

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import java.util.HashSet

class SkipMiniProgramVideoAdsFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "跳过小程序视频广告"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SkipMiniProgramVideoAdsSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "skip_mini_program_video_ads"
        private const val TAG = "[Hchat:MiniProgramVideoAds]"
        private const val BRIDGE_CLASS =
            "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding"
        private const val EVENT_VIDEO_TIME_UPDATE = "onVideoTimeUpdate"
        private val installedLoaders = HashSet<String>()

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return HchatStorage.preferences(
                context,
                SkipMiniProgramVideoAdsSettings.PREFS_NAME
            ).getBoolean(
                SkipMiniProgramVideoAdsSettings.KEY_ENABLE,
                SkipMiniProgramVideoAdsSettings.DEFAULT_ENABLE
            )
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?): Boolean {
            if (context == null || classLoader == null) return false
            val loaderKey = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
            synchronized(installedLoaders) {
                if (installedLoaders.contains(loaderKey)) return true
                val bridgeClass = KavaReflector.loadClass(BRIDGE_CLASS, classLoader)
                if (bridgeClass == null) {
                    HLog.e("$TAG 未找到小程序 JS Bridge: $BRIDGE_CLASS")
                    return false
                }
                val subscribeHandler = KavaReflector.findMethod(
                    bridgeClass,
                    "subscribeHandler",
                    String::class.java,
                    String::class.java,
                    Integer.TYPE,
                    String::class.java
                )
                if (subscribeHandler == null || subscribeHandler.returnType != java.lang.Void.TYPE) {
                    HLog.e("$TAG 未找到 subscribeHandler(String,String,int,String)")
                    return false
                }
                return runCatching {
                    HookRegistry.get().hook(subscribeHandler, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (param.args.getOrNull(0) != EVENT_VIDEO_TIME_UPDATE) return
                            if (!isEnabled(context)) return
                            val payload = param.args.getOrNull(1) as? String ?: return
                            val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
                            json.put("position", 60)
                            json.put("duration", 1)
                            param.args[1] = json.toString()
                        }
                    })
                    installedLoaders.add(loaderKey)
                    true
                }.getOrElse {
                    HLog.e("$TAG 安装小程序视频广告 Hook 失败: ${it.message}", it)
                    false
                }
            }
        }
    }
}
