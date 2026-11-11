package h.Hchat.hooks.items.typingreport

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

class TypingReportBlockFeature : BaseFeature() {
    private var hooker: TypingReportBlockHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "拦截正在输入上报"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(TypingReportBlockSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = TypingReportBlockHooker(context, ::logFeatureError)
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
        const val ID = "block_typing_report"
    }
}

private class TypingReportBlockHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val settingsPrefs = HchatStorage.preferences(
        context.hostContext(),
        TypingReportBlockSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(
        context.hostContext(),
        "Hchat_block_typing_report_method_cache"
    )

    @Volatile
    private var installed = false

    @Synchronized
    fun install(allowDexSearch: Boolean): Boolean {
        if (installed) return true
        val method = locateSendTypingMethod(allowDexSearch) ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (isEnabled()) {
                        param.result = null
                    }
                }
            })
            installed = true
            true
        }.getOrElse {
            logger("正在输入上报 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun locateSendTypingMethod(allowDexSearch: Boolean): Method? {
        val cacheKey = methodCacheKey()
        val cached = DexMethodCache.load(
            methodPrefs,
            cacheKey,
            context.hostClassLoader(),
            CACHE_SEND_TYPING_METHOD
        )
        if (cached != null) {
            if (isSendTypingMethod(cached)) return cached
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_SEND_TYPING_METHOD)
        }
        if (!allowDexSearch) return null

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(SIGNALLING_TAG, DIRECT_SEND_NULL_CONTEXT_LOG)
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isSendTypingMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位正在输入上报方法失败", it)
            emptyList()
        }

        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_SEND_TYPING_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_SEND_TYPING_METHOD)
            if (candidates.size > 1) {
                logger("正在输入上报方法定位结果不唯一: ${candidates.joinToString { it.toGenericString() }}", null)
            }
        }
        return method
    }

    private fun isSendTypingMethod(method: Method): Boolean {
        val parameterTypes = method.parameterTypes
        return method.declaringClass.name.startsWith(CHATTING_COMPONENT_PREFIX) &&
            method.returnType == Void.TYPE &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == Integer.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun isEnabled(): Boolean {
        return settingsPrefs.getBoolean(
            TypingReportBlockSettings.KEY_ENABLE,
            TypingReportBlockSettings.DEFAULT_ENABLE
        )
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private companion object {
        const val CACHE_SCHEMA = "block_typing_report_v1_direct_send"
        const val CACHE_SEND_TYPING_METHOD = "send_typing_method"
        const val CHATTING_COMPONENT_PREFIX = "com.tencent.mm.ui.chatting.component."
        const val SIGNALLING_TAG = "MicroMsg.SignallingComponent"
        const val DIRECT_SEND_NULL_CONTEXT_LOG = "[doDirectSend] mChattingContext is null!"
    }
}
