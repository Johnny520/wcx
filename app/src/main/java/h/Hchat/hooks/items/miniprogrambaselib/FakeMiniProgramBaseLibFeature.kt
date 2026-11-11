package h.Hchat.hooks.items.miniprogrambaselib

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Constructor
import java.lang.reflect.Method

class FakeMiniProgramBaseLibFeature : BaseFeature() {
    private var runtime: MiniProgramCompatibilityRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "兼容低版本小程序"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(FakeMiniProgramBaseLibSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MiniProgramCompatibilityRuntime(context, ::logFeatureError)
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.BRIDGE) {
            runtime?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "fake_mini_program_base_lib"
        private const val TAG = "[Hchat:FakeMiniProgramHostVersion]"
        internal const val CACHE_PREFS_NAME =
            "Hchat_fake_mini_program_host_version_member_cache"
        internal const val CACHE_PRIVATE_OPEN_URL_METHOD = "private_open_url_method"
        @Volatile private var appBrandProcessHookInstalled = false

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            return HchatStorage.preferences(context, FakeMiniProgramBaseLibSettings.PREFS_NAME)
                .getBoolean(
                    FakeMiniProgramBaseLibSettings.KEY_ENABLE,
                    FakeMiniProgramBaseLibSettings.DEFAULT_ENABLE
                )
        }

        @JvmStatic
        @Synchronized
        fun installAppBrandProcessHook(context: Context, classLoader: ClassLoader): Boolean {
            if (appBrandProcessHookInstalled) return true
            val prefs = DexMethodCache.prefs(context, CACHE_PREFS_NAME)
            val runtimeKey = DexMethodCache.runtimeKey(context, classLoader)
            val privateOpenUrlMethod = DexMethodCache.loadCrossProcess(
                prefs,
                runtimeKey,
                classLoader,
                CACHE_PRIVATE_OPEN_URL_METHOD
            )?.takeIf(::isPrivateOpenUrlMethod) ?: return false

            return runCatching {
                hookPrivateOpenUrlMethod(privateOpenUrlMethod, context)
                appBrandProcessHookInstalled = true
                true
            }.getOrElse {
                HLog.e("$TAG 小程序进程升级跳转Hook安装失败: ${it.message}", it)
                false
            }
        }
    }
}

private class MiniProgramCompatibilityRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val memberPrefs = DexMethodCache.prefs(
        context.hostContext(),
        FakeMiniProgramBaseLibFeature.CACHE_PREFS_NAME
    )
    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val launchConstructor = locateLaunchConstructor() ?: return false
            locatePrivateOpenUrlMethod() ?: return false
            HookRegistry.get().hook(launchConstructor, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!FakeMiniProgramBaseLibFeature.isEnabled(context.hostContext())) return
                    if (param.args.size <= LIB_VERSION_ARG_INDEX) return
                    param.args[LIB_VERSION_ARG_INDEX] = SPOOFED_LIB_VERSION
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("兼容低版本小程序Hook失败", it)
            false
        }
    }

    private fun locateLaunchConstructor(): Constructor<*>? {
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.loadConstructor(
            memberPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_LAUNCH_CONSTRUCTOR
        )?.takeIf(::isLaunchConstructor)?.let { return it }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(CGI_LOG_TAG, CGI_CONSTRUCTOR_LOG)
                        }
                    )
                }
            ).mapNotNull { data ->
                if (!data.isConstructor) return@mapNotNull null
                runCatching {
                    data.getConstructorInstance(context.hostClassLoader())
                }.getOrNull()
            }.filter(::isLaunchConstructor)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位小程序启动请求构造器失败", it)
            emptyList()
        }

        val constructor = candidates.singleOrNull()
        if (constructor != null) {
            DexMethodCache.saveConstructor(
                memberPrefs,
                runtimeKey,
                CACHE_LAUNCH_CONSTRUCTOR,
                constructor
            )
        } else {
            DexMethodCache.clear(memberPrefs, runtimeKey, CACHE_LAUNCH_CONSTRUCTOR)
            if (candidates.size > 1) {
                logger("小程序启动请求构造器候选不唯一: ${candidates.joinToString { it.toGenericString() }}", null)
            }
        }
        return constructor
    }

    private fun locatePrivateOpenUrlMethod(): Method? {
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(
            memberPrefs,
            runtimeKey,
            context.hostClassLoader(),
            FakeMiniProgramBaseLibFeature.CACHE_PRIVATE_OPEN_URL_METHOD
        )?.takeIf(::isPrivateOpenUrlMethod)?.let { return it }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(
                                PRIVATE_OPEN_URL_API,
                                PRIVATE_OPEN_URL_RAW_URL_KEY,
                                PRIVATE_OPEN_URL_APP_ID_KEY
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                if (data.isConstructor) return@mapNotNull null
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isPrivateOpenUrlMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位小程序私有网页跳转方法失败", it)
            emptyList()
        }

        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(
                memberPrefs,
                runtimeKey,
                FakeMiniProgramBaseLibFeature.CACHE_PRIVATE_OPEN_URL_METHOD,
                method
            )
        } else {
            DexMethodCache.clear(
                memberPrefs,
                runtimeKey,
                FakeMiniProgramBaseLibFeature.CACHE_PRIVATE_OPEN_URL_METHOD
            )
            if (candidates.size > 1) {
                logger("小程序私有网页跳转方法候选不唯一: ${candidates.joinToString { it.toGenericString() }}", null)
            }
        }
        return method
    }

    private fun isLaunchConstructor(constructor: Constructor<*>): Boolean {
        val types = constructor.parameterTypes
        return types.size == 17 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Boolean.TYPE &&
            types[5] == String::class.java &&
            types[LIB_VERSION_ARG_INDEX] == Integer.TYPE &&
            types[7].name == QUALITY_SESSION_CLASS &&
            types[8] == String::class.java &&
            types[9].name == LAUNCH_REFERRER_CLASS &&
            types[10] == String::class.java &&
            types[12] == Integer.TYPE &&
            types[13] == java.lang.Boolean.TYPE &&
            types[14] == String::class.java &&
            types[15] == java.lang.Boolean.TYPE &&
            types[16] == java.lang.Boolean.TYPE
    }

    companion object {
        private const val CACHE_LAUNCH_CONSTRUCTOR = "launch_wxa_app_constructor"
        private const val LIB_VERSION_ARG_INDEX = 6
        private const val SPOOFED_LIB_VERSION = 9999
        private const val QUALITY_SESSION_CLASS =
            "com.tencent.mm.plugin.appbrand.report.quality.QualitySession"
        private const val LAUNCH_REFERRER_CLASS =
            "com.tencent.mm.plugin.appbrand.config.AppBrandLaunchFromNotifyReferrer"
        private const val CGI_LOG_TAG = "MicroMsg.AppBrand.CgiLaunchWxaApp|func:1122"
        private const val CGI_CONSTRUCTOR_LOG =
            "<init> cgiHash[%d], username[%s] appId[%s] sync[%b] sessionId[%s] instanceId[%s] libVersion[%d], source:%s, launchMode:%d, migrate:%b, fallback:%b"
        private const val PRIVATE_OPEN_URL_API = "private_openUrl"
        private const val PRIVATE_OPEN_URL_RAW_URL_KEY = "rawUrl"
        private const val PRIVATE_OPEN_URL_APP_ID_KEY = "geta8key_open_webview_appid"
    }
}

private fun isPrivateOpenUrlMethod(method: Method): Boolean {
    return !KavaReflector.isStatic(method) &&
        method.returnType == java.lang.Void.TYPE &&
        method.parameterCount == 3 &&
        method.parameterTypes[1] == JSONObject::class.java &&
        method.parameterTypes[2] == Integer.TYPE &&
        method.declaringClass.name.startsWith("com.tencent.mm.plugin.appbrand.jsapi.")
}

private fun hookPrivateOpenUrlMethod(method: Method, context: Context) {
    HookRegistry.get().hook(method, object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            if (!FakeMiniProgramBaseLibFeature.isEnabled(context)) return
            val request = param.args.getOrNull(1) as? JSONObject ?: return
            val url = request.optString("url")
            if (!isWeChatUpdateUrl(url)) return
            request.put("url", "")
        }
    })
}

private fun isWeChatUpdateUrl(url: String): Boolean {
    return url == "https://support.weixin.qq.com/update" ||
        url.startsWith("https://support.weixin.qq.com/update/") ||
        url == "https://szsupport.weixin.qq.com/update" ||
        url.startsWith("https://szsupport.weixin.qq.com/update/")
}
