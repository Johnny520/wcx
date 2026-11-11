package h.Hchat.hooks.items.miniprogramsplashad

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.HashSet

class SkipGlobalMiniProgramSplashAdsFeature : BaseFeature() {
    private var runtime: SkipGlobalMiniProgramSplashAdsRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "跳过全局小程序开屏广告"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SkipGlobalMiniProgramSplashAdsSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = SkipGlobalMiniProgramSplashAdsRuntime(context, ::logFeatureError)
        DexInstallScheduler.schedule(
            ID,
            name(),
            stage = DexInstallScheduler.Stage.BRIDGE,
            priority = -100
        ) {
            runtime?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "skip_global_mini_program_splash_ads"
        private const val TAG = "[Hchat:MiniProgramSplashAds]"
        internal const val CACHE_PREFS_NAME =
            "Hchat_skip_global_mini_program_splash_ads_method_cache"
        internal const val CACHE_SPLASH_AD_CHECK_METHOD = "splash_ad_check_method"
        private val installedLoaders = HashSet<String>()

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return HchatStorage.preferences(
                context,
                SkipGlobalMiniProgramSplashAdsSettings.PREFS_NAME
            ).getBoolean(
                SkipGlobalMiniProgramSplashAdsSettings.KEY_ENABLE,
                SkipGlobalMiniProgramSplashAdsSettings.DEFAULT_ENABLE
            )
        }

        @JvmStatic
        fun scheduleAppBrandProcessHook(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null) return
            val loaderKey = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
            DexInstallScheduler.schedule(
                "$ID:appbrand:$loaderKey",
                "小程序开屏广告子进程 Hook",
                stage = DexInstallScheduler.Stage.EARLY,
                priority = -100
            ) {
                installAppBrandProcessHook(context, classLoader)
            }
        }

        @JvmStatic
        fun installAppBrandProcessHook(context: Context?, classLoader: ClassLoader?): Boolean {
            if (context == null || classLoader == null) return false
            val loaderKey = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
            synchronized(installedLoaders) {
                if (loaderKey in installedLoaders) return true
                val prefs = DexMethodCache.prefs(context, CACHE_PREFS_NAME)
                val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
                val method = DexMethodCache.loadCrossProcess(
                    prefs,
                    runtimeKey,
                    classLoader,
                    CACHE_SPLASH_AD_CHECK_METHOD
                )?.takeIf(::isSplashAdCheckMethod) ?: return false
                return runCatching {
                    hookSplashAdCheckMethod(method, context)
                    installedLoaders.add(loaderKey)
                    true
                }.getOrElse {
                    HLog.e("$TAG 小程序进程开屏广告 Hook 安装失败: ${it.message}", it)
                    false
                }
            }
        }
    }
}

private class SkipGlobalMiniProgramSplashAdsRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val cachePrefs = DexMethodCache.prefs(
        context.hostContext(),
        SkipGlobalMiniProgramSplashAdsFeature.CACHE_PREFS_NAME
    )
    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val method = locateSplashAdCheckMethod() ?: return false
            hookSplashAdCheckMethod(method, context.hostContext())
            installed = true
            true
        }.getOrElse {
            logger("小程序开屏广告 Hook 安装失败", it)
            false
        }
    }

    private fun locateSplashAdCheckMethod(): Method? {
        val runtimeKey = DexMethodCache.runtimeKey(
            context.hostContext(),
            context.hostClassLoader()
        )
        DexMethodCache.load(
            cachePrefs,
            runtimeKey,
            context.hostClassLoader(),
            SkipGlobalMiniProgramSplashAdsFeature.CACHE_SPLASH_AD_CHECK_METHOD
        )?.takeIf(::isSplashAdCheckMethod)?.let { return it }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(SPLASH_AD_LOG_TAG, SPLASH_AD_RESULT_LOG)
                        }
                    )
                }
            ).mapNotNull { data ->
                if (data.isConstructor) return@mapNotNull null
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isSplashAdCheckMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位小程序开屏广告资格方法失败", it)
            emptyList()
        }
        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(
                cachePrefs,
                runtimeKey,
                SkipGlobalMiniProgramSplashAdsFeature.CACHE_SPLASH_AD_CHECK_METHOD,
                method
            )
        } else {
            DexMethodCache.clear(
                cachePrefs,
                runtimeKey,
                SkipGlobalMiniProgramSplashAdsFeature.CACHE_SPLASH_AD_CHECK_METHOD
            )
            if (candidates.size > 1) {
                logger("小程序开屏广告资格方法候选不唯一: ${candidates.joinToString { it.toGenericString() }}", null)
            }
        }
        return method
    }

    companion object {
        private const val SPLASH_AD_LOG_TAG = "MicroMsg.AppBrandAdUtils[AppBrandSplashAd]"
        private const val SPLASH_AD_RESULT_LOG = "isAdContact, appId:%s, canShowAd:%s"
    }
}

private fun isSplashAdCheckMethod(method: Method): Boolean {
    return method.returnType == java.lang.Boolean.TYPE &&
        method.parameterCount == 1 &&
        method.parameterTypes[0].name.startsWith("com.tencent.mm.plugin.appbrand.")
}

private fun hookSplashAdCheckMethod(method: Method, context: Context) {
    HookRegistry.get().hook(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (SkipGlobalMiniProgramSplashAdsFeature.isEnabled(context)) {
                param.result = false
            }
        }
    })
}
