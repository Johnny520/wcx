package h.Hchat.hooks.items.forwardlimit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class RemoveForwardLimitFeature : BaseFeature() {
    private var hooker: RemoveForwardLimitHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "移除转发限制"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(RemoveForwardLimitSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val currentHooker = RemoveForwardLimitHooker(context, ::logRuntimeError)
        hooker = currentHooker
        currentHooker.installDirect()
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.installWithDexKit() == true
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker = null
    }

    private fun logRuntimeError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "remove_forward_limit"
    }
}

private class RemoveForwardLimitHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        RemoveForwardLimitSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS_NAME)
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()

    @Volatile
    private var legacyInstalled = false

    @Volatile
    private var uicInstalled = false

    @Synchronized
    fun installDirect(): Boolean {
        if (legacyInstalled) return true
        val limitMethod = locateDirectLimitMethod() ?: return false
        return installLegacyHooks(limitMethod)
    }

    @Synchronized
    fun installWithDexKit(): Boolean {
        if (!legacyInstalled) {
            val limitMethod = locateLimitMethodWithDexKit() ?: return false
            if (!installLegacyHooks(limitMethod)) return false
        }
        if (!requiresUicHook() || uicInstalled) return true
        val uicConfigMethod = locateUicConfigMethodWithDexKit() ?: return false
        if (!hookUicConfigMethod(uicConfigMethod)) return false
        uicInstalled = true
        return true
    }

    private fun installLegacyHooks(limitMethod: Method): Boolean {
        if (!hookLimitMethod(limitMethod)) return false
        val contactPageMethods = locateContactPageMethods()
        if (contactPageMethods.isEmpty()) {
            logger("未找到转发完整联系人选择页", null)
            return false
        }
        val contactHooksInstalled = contactPageMethods
            .map(::hookContactPageMethod)
            .all { it }
        if (!contactHooksInstalled) return false
        legacyInstalled = true
        return true
    }

    private fun hookLimitMethod(method: Method): Boolean {
        return hook(method, "转发会话数量限制") {
            if (isEnabled()) it.result = false
        }
    }

    private fun hookContactPageMethod(method: Method): Boolean {
        return hook(method, "转发完整联系人选择页") { param ->
            if (!isEnabled()) return@hook
            val activity = param.thisObject as? Activity ?: return@hook
            val intent = activity.intent ?: return@hook
            rewriteForwardLimit(intent)
        }
    }

    private fun hookUicConfigMethod(method: Method): Boolean {
        return hook(method, "新版转发联系人配置") { param ->
            if (!isEnabled()) return@hook
            val intent = KavaReflector.invokeMethod(param.thisObject, "getIntent") as? Intent
                ?: return@hook
            rewriteForwardLimit(intent)
        }
    }

    private fun rewriteForwardLimit(intent: Intent) {
        if (!isForwardIntent(intent)) return
        if (intent.getIntExtra(LIMIT_EXTRA_KEY, -1) != WECHAT_DEFAULT_LIMIT) return
        intent.putExtra(LIMIT_EXTRA_KEY, UNLIMITED_COUNT)
        intent.removeExtra(TOO_MANY_TIP_EXTRA_KEY)
    }

    private fun isForwardIntent(intent: Intent): Boolean {
        return intent.getIntExtra(LIST_TYPE_EXTRA_KEY, -1) == FORWARD_LIST_TYPE ||
            intent.getBooleanExtra(FORWARD_BY_UIC_EXTRA_KEY, false) ||
            intent.hasExtra(RETRANSMIT_MESSAGE_ID_EXTRA_KEY) ||
            intent.hasExtra(RETRANSMIT_VIEW_MODEL_EXTRA_KEY)
    }

    private fun hook(
        method: Method,
        label: String,
        before: (XC_MethodHook.MethodHookParam) -> Unit
    ): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    before(param)
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("$label Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun locateDirectLimitMethod(): Method? {
        val clazz = KavaReflector.loadClass(SELECT_CONVERSATION_UI, context.hostClassLoader())
            ?: return null
        return KavaReflector.declaredMethods(clazz)
            .filter(::isLimitMethod)
            .distinctBy { it.toGenericString() }
            .singleOrNull()
    }

    private fun locateLimitMethodWithDexKit(): Method? {
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_LIMIT_METHOD
        )?.let { cached ->
            if (isLimitMethod(cached)) return cached
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_LIMIT_METHOD)
        }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(SELECT_CONVERSATION_UI)
                            usingEqStrings(LIMIT_EXTRA_KEY)
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isLimitMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位转发会话数量限制方法失败", it)
            emptyList()
        }

        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_LIMIT_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_LIMIT_METHOD)
            logger(
                "转发会话数量限制方法定位结果异常: count=${candidates.size}",
                null
            )
        }
        return method
    }

    private fun locateUicConfigMethodWithDexKit(): Method? {
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
        DexMethodCache.load(
            methodPrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_UIC_CONFIG_METHOD
        )?.let { cached ->
            if (isUicConfigMethod(cached)) return cached
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_UIC_CONFIG_METHOD)
        }

        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(
                                MIN_LIMIT_EXTRA_KEY,
                                LIMIT_EXTRA_KEY,
                                FORWARD_BY_UIC_EXTRA_KEY
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isUicConfigMethod)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("定位新版转发联系人配置失败", it)
            return null
        }

        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(methodPrefs, runtimeKey, CACHE_UIC_CONFIG_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_UIC_CONFIG_METHOD)
            logger("新版转发联系人配置定位结果异常: count=${candidates.size}", null)
        }
        return method
    }

    private fun locateContactPageMethods(): List<Method> {
        return CONTACT_PAGE_CLASSES.mapNotNull { className ->
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader())
                ?: return@mapNotNull null
            KavaReflector.findDeclaredMethod(clazz, "onCreate", Bundle::class.java)
                ?.takeIf { method ->
                    method.returnType == Void.TYPE &&
                        !Modifier.isStatic(method.modifiers) &&
                        !Modifier.isAbstract(method.modifiers)
                }
        }.distinctBy { it.toGenericString() }
    }

    private fun isLimitMethod(method: Method): Boolean {
        val parameterTypes = method.parameterTypes
        return method.declaringClass.name == SELECT_CONVERSATION_UI &&
            method.returnType == java.lang.Boolean.TYPE &&
            parameterTypes.size == 1 &&
            parameterTypes[0] == java.lang.Boolean.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun isUicConfigMethod(method: Method): Boolean {
        return method.parameterTypes.isEmpty() &&
            method.returnType != Void.TYPE &&
            !method.returnType.isPrimitive &&
            !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers)
    }

    private fun requiresUicHook(): Boolean {
        val versionCode = WeChatApis.version()?.versionCode() ?: 0L
        return versionCode == 0L || versionCode >= UIC_FORWARD_MIN_VERSION_CODE
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(
            RemoveForwardLimitSettings.KEY_ENABLE,
            RemoveForwardLimitSettings.DEFAULT_ENABLE
        )
    }

    private companion object {
        const val SELECT_CONVERSATION_UI = "com.tencent.mm.ui.transmit.SelectConversationUI"
        const val MIN_LIMIT_EXTRA_KEY = "min_limit_num"
        const val LIMIT_EXTRA_KEY = "max_limit_num"
        const val TOO_MANY_TIP_EXTRA_KEY = "too_many_member_tip_string"
        const val LIST_TYPE_EXTRA_KEY = "list_type"
        const val FORWARD_BY_UIC_EXTRA_KEY = "ForwardParams_ForwardByUIC"
        const val RETRANSMIT_MESSAGE_ID_EXTRA_KEY = "Retr_Msg_Id"
        const val RETRANSMIT_VIEW_MODEL_EXTRA_KEY = "Retr_Msg_view_model"
        const val FORWARD_LIST_TYPE = 14
        const val WECHAT_DEFAULT_LIMIT = 9
        const val UNLIMITED_COUNT = Int.MAX_VALUE
        const val UIC_FORWARD_MIN_VERSION_CODE = 2841L
        const val CACHE_PREFS_NAME = "Hchat_remove_forward_limit_method_cache"
        const val CACHE_SCHEMA = "remove_forward_limit_v3"
        const val CACHE_LIMIT_METHOD = "select_conversation_limit"
        const val CACHE_UIC_CONFIG_METHOD = "uic_contact_config"
        val CONTACT_PAGE_CLASSES = listOf(
            "com.tencent.mm.ui.mvvm.MvvmSelectContactUI",
            "com.tencent.mm.ui.mvvm.MvvmContactListUI"
        )
    }
}
