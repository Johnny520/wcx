package h.Hchat.hooks.items.tablet

import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.Button
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.api.runtime.WeChatVersionApi
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.wrap.DexMethod
import java.lang.reflect.Method
import java.util.HashSet

class WeChatTabletFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "平板模式"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(WeChatTabletSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        if (!isEnabled(context.hostContext())) return
        install(context.hostContext(), context.hostClassLoader(), context.dexKitBridge())
    }

    companion object {
        const val ID = "wechat_tablet"
        private const val TAG = "[Hchat:Tablet]"
        private const val CHAT_VOICE_STACK = "com.tencent.mm.pluginsdk.ui.chat"
        private const val CACHE_PREFS = "Hchat_wechat_tablet_cache"
        private const val CACHE_KEY = "cache_key"
        private const val CACHE_PAD_METHOD = "pad_method"
        private const val CACHE_LOGIN_BUTTON_METHOD = "login_button_method"
        private val padHookedLoaders = HashSet<String>()
        private val loginButtonHookedLoaders = HashSet<String>()

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val sp = HchatStorage.preferences(context, WeChatTabletSettings.PREFS_NAME)
            return sp.getBoolean(WeChatTabletSettings.KEY_ENABLE, WeChatTabletSettings.DEFAULT_ENABLE)
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?, dexKit: org.luckypray.dexkit.DexKitBridge?) {
            if (context == null || classLoader == null || dexKit == null) return
            if (!isEnabled(context)) return
            val cache = HchatStorage.preferences(context, CACHE_PREFS)
            val runtimeKey = cacheKey(context, classLoader)
            val padAlreadyHooked = isPadHooked(classLoader)
            val padMethod = if (padAlreadyHooked) null else {
                findMethod(context, cache, runtimeKey, dexKit, classLoader, CACHE_PAD_METHOD, "Lenovo TB-9707F")
            }
            if (!padAlreadyHooked && padMethod == null) {
                HLog.e("$TAG 安装失败: 未定位平板检测方法")
                return
            }
            if (padMethod != null) hookPadCheck(padMethod, classLoader)
            if (!isLoginButtonHooked(classLoader)) {
                val loginButtonMethod = findMethod(
                    context,
                    cache,
                    runtimeKey,
                    dexKit,
                    classLoader,
                    CACHE_LOGIN_BUTTON_METHOD,
                    "loginAsOtherDeviceBtn"
                )
                if (loginButtonMethod != null) {
                    hookLoginButton(loginButtonMethod, classLoader)
                    saveCachedDescriptor(cache, runtimeKey, CACHE_LOGIN_BUTTON_METHOD, loginButtonMethod)
                }
            }
        }

        @JvmStatic
        fun installCached(context: Context?, classLoader: ClassLoader?): Boolean {
            if (context == null || classLoader == null) return false
            if (!isEnabled(context)) return false
            val cache = HchatStorage.preferences(context, CACHE_PREFS)
            val runtimeKey = cacheKey(context, classLoader)
            var padReady = isPadHooked(classLoader)
            if (!padReady) {
                val padMethod = loadCachedMethod(cache, runtimeKey, classLoader, CACHE_PAD_METHOD)
                    ?: return false
                hookPadCheck(padMethod, classLoader)
                saveCachedDescriptor(cache, runtimeKey, CACHE_PAD_METHOD, padMethod)
                padReady = isPadHooked(classLoader)
            }
            if (!isLoginButtonHooked(classLoader)) {
                loadCachedMethod(cache, runtimeKey, classLoader, CACHE_LOGIN_BUTTON_METHOD)?.let {
                    hookLoginButton(it, classLoader)
                    saveCachedDescriptor(cache, runtimeKey, CACHE_LOGIN_BUTTON_METHOD, it)
                }
            }
            return padReady
        }

        private fun hookPadCheck(method: Method, fallbackClassLoader: ClassLoader) {
            val key = hookKey(method, fallbackClassLoader)
            synchronized(WeChatTabletFeature::class.java) {
                if (padHookedLoaders.contains(key)) return
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.result = Throwable().stackTrace.none {
                            it.className.contains(CHAT_VOICE_STACK)
                        }
                    }
                })
                padHookedLoaders.add(key)
            }
        }

        private fun hookLoginButton(method: Method, fallbackClassLoader: ClassLoader) {
            val key = hookKey(method, fallbackClassLoader)
            synchronized(WeChatTabletFeature::class.java) {
                if (loginButtonHookedLoaders.contains(key)) return
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val button = param.args?.firstOrNull() as? Button ?: return
                        if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE
                    }
                })
                loginButtonHookedLoaders.add(key)
            }
        }

        private fun isPadHooked(classLoader: ClassLoader): Boolean {
            return synchronized(WeChatTabletFeature::class.java) {
                padHookedLoaders.contains(loaderKey(classLoader))
            }
        }

        private fun isLoginButtonHooked(classLoader: ClassLoader): Boolean {
            return synchronized(WeChatTabletFeature::class.java) {
                loginButtonHookedLoaders.contains(loaderKey(classLoader))
            }
        }

        private fun hookKey(method: Method, fallbackClassLoader: ClassLoader): String {
            val loader = method.declaringClass.classLoader ?: fallbackClassLoader
            return loaderKey(loader)
        }

        private fun loaderKey(classLoader: ClassLoader): String {
            return "${System.identityHashCode(classLoader)}:${classLoader}"
        }

        private fun cacheKey(context: Context, classLoader: ClassLoader): String {
            val info = WeChatVersionApi.build(context, classLoader)
            return listOf(
                info.packageName,
                info.versionName,
                info.versionCode.toString(),
                info.clientVersion,
                info.tinkerId,
                info.patchId,
                info.sourceLastModified.toString()
            ).joinToString("|")
        }

        private fun findMethod(
            context: Context,
            cache: SharedPreferences,
            runtimeKey: String,
            dexKit: org.luckypray.dexkit.DexKitBridge,
            classLoader: ClassLoader,
            cacheName: String,
            vararg strings: String
        ): Method? {
            loadCachedMethod(cache, runtimeKey, classLoader, cacheName)?.let { return it }
            val found = runCatching {
                val methodData = dexKit.findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply {
                            usingStrings(strings.toList())
                        })
                    }
                ).firstOrNull() ?: return@runCatching null
                saveCachedMethod(cache, runtimeKey, cacheName, methodData.descriptor)
                methodData.getMethodInstance(classLoader)
            }.getOrElse {
                HLog.e("$TAG 定位失败: ${strings.joinToString()} ${it.message}", it)
                null
            }
            if (found == null) {
                runCatching {
                    cache.edit()
                        .putString(CACHE_KEY, runtimeKey)
                        .remove(cacheName)
                        .apply()
                }
                HLog.e("$TAG 未命中方法: ${strings.joinToString()} pkg=${context.packageName}")
            }
            return found
        }

        private fun loadCachedMethod(
            cache: SharedPreferences,
            runtimeKey: String,
            classLoader: ClassLoader,
            cacheName: String
        ): Method? {
            if (runtimeKey.isEmpty()) return null
            if (cache.getString(CACHE_KEY, "") != runtimeKey) return null
            val descriptor = cache.getString(cacheName, "")?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { DexMethod(descriptor).getMethodInstance(classLoader) }.getOrNull()
        }

        private fun saveCachedMethod(
            cache: SharedPreferences,
            runtimeKey: String,
            cacheName: String,
            descriptor: String?
        ) {
            if (runtimeKey.isEmpty() || descriptor.isNullOrEmpty()) return
            runCatching {
                val editor = cache.edit()
                if (cache.getString(CACHE_KEY, "") != runtimeKey) {
                    editor.clear()
                }
                editor
                    .putString(CACHE_KEY, runtimeKey)
                    .putString(cacheName, descriptor)
                    .apply()
            }
        }

        private fun saveCachedDescriptor(
            cache: SharedPreferences,
            runtimeKey: String,
            cacheName: String,
            method: Method
        ) {
            val descriptor = method.toDexDescriptor()
            saveCachedMethod(cache, runtimeKey, cacheName, descriptor)
        }

        private fun Method.toDexDescriptor(): String {
            return buildString {
                append('L')
                append(declaringClass.name.replace('.', '/'))
                append(";->")
                append(name)
                append('(')
                parameterTypes.forEach { append(it.toDexType()) }
                append(')')
                append(returnType.toDexType())
            }
        }

        private fun Class<*>.toDexType(): String {
            if (isPrimitive) {
                return when (this) {
                    java.lang.Void.TYPE -> "V"
                    java.lang.Boolean.TYPE -> "Z"
                    java.lang.Byte.TYPE -> "B"
                    java.lang.Character.TYPE -> "C"
                    java.lang.Short.TYPE -> "S"
                    java.lang.Integer.TYPE -> "I"
                    java.lang.Long.TYPE -> "J"
                    java.lang.Float.TYPE -> "F"
                    java.lang.Double.TYPE -> "D"
                    else -> "V"
                }
            }
            if (isArray) return name.replace('.', '/')
            return "L${name.replace('.', '/')};"
        }
    }
}
