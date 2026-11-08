package h.Hchat.hooks.items.patblock

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

class PatBlockFeature : BaseFeature() {
    private var hooker: PatBlockHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "禁止拍一拍"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(PatBlockSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = PatBlockHooker(context, ::logFeatureError)
        if (hooker?.install(allowDexSearch = false) != true) {
            scheduleInstall()
        }
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install(allowDexSearch = true) == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "disable_pat"
    }
}

private class PatBlockHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val settingsPrefs = HchatStorage.preferences(
        context.hostContext(),
        PatBlockSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(
        context.hostContext(),
        "Hchat_disable_pat_method_cache"
    )

    @Volatile
    private var installed = false

    @Synchronized
    fun install(allowDexSearch: Boolean): Boolean {
        if (installed) return true
        val method = locateAvatarDoubleClickMethod(allowDexSearch) ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        param.result = true
                    }
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("禁止拍一拍 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun locateAvatarDoubleClickMethod(allowDexSearch: Boolean): Method? {
        val cacheKey = methodCacheKey()
        val cached = DexMethodCache.load(
            methodPrefs,
            cacheKey,
            context.hostClassLoader(),
            CACHE_AVATAR_DOUBLE_CLICK
        )
        if (cached != null) {
            if (isAvatarDoubleClickMethod(cached)) return cached
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_AVATAR_DOUBLE_CLICK)
        }
        if (!allowDexSearch) return null

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(
                                DOUBLE_CLICK_LOG_TAG,
                                DOUBLE_CLICK_NULL_TAG_LOG,
                                DOUBLE_CLICK_USER_LOG
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isAvatarDoubleClickMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位聊天头像双击入口失败", it)
            emptyList()
        }

        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_AVATAR_DOUBLE_CLICK, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_AVATAR_DOUBLE_CLICK)
            if (candidates.size > 1) {
                logger("聊天头像双击入口定位结果不唯一: ${candidates.joinToString { it.toGenericString() }}", null)
            }
        }
        return method
    }

    private fun isAvatarDoubleClickMethod(method: Method): Boolean {
        val parameterTypes = method.parameterTypes
        return method.declaringClass.name.startsWith(CHATTING_CLASS_PREFIX) &&
            method.returnType == java.lang.Boolean.TYPE &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == View::class.java &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun isEnabled(): Boolean {
        return settingsPrefs.getBoolean(PatBlockSettings.KEY_ENABLE, PatBlockSettings.DEFAULT_ENABLE)
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private companion object {
        const val CACHE_SCHEMA = "disable_pat_v1_avatar_double_click"
        const val CACHE_AVATAR_DOUBLE_CLICK = "avatar_double_click_method"
        const val CHATTING_CLASS_PREFIX = "com.tencent.mm.ui.chatting."
        const val DOUBLE_CLICK_LOG_TAG = "MicroMsg.AvatarDoubleClickListener"
        const val DOUBLE_CLICK_NULL_TAG_LOG = "onDoubleClick tag null"
        const val DOUBLE_CLICK_USER_LOG = "onDoubleClick: %s"
    }
}
