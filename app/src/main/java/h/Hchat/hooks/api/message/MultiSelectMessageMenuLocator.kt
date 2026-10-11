package h.Hchat.hooks.api.message

import android.view.MenuItem
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object MultiSelectMessageMenuLocator {
    private const val CHATTING_COMPONENT_PREFIX = "com.tencent.mm.ui.chatting.component."
    private const val CLICK_METHOD_ANCHOR = "FinalShareCountByType"
    private const val PREFS_NAME = "Hchat_multi_select_menu_method_cache"
    private const val CACHE_MENU_CREATE = "menu_create_v2"
    private const val CACHE_MENU_CLICK = "menu_click_v1"
    private const val CACHE_MULTI_SELECT_EXIT = "multi_select_exit_v1"

    @JvmStatic
    fun menuCreateMethod(context: FeatureContext, logger: (String, Throwable?) -> Unit): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, cacheKey, context.hostClassLoader(), CACHE_MENU_CREATE)
            ?.takeIf(::isMenuCreateMethod)
            ?.let { return it }
        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        name("onCreateMMMenu")
                        returnType("void")
                        addInvoke(MethodMatcher().apply {
                            declaredClass("com.tencent.wework.api.WWAPIFactory")
                        })
                    })
                }
            ).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isMenuCreateMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure { logger("定位多选消息菜单创建方法失败", it) }.getOrDefault(emptyList())
        return saveSingleMethod(prefs, cacheKey, CACHE_MENU_CREATE, candidates, "菜单创建", logger)
    }

    @JvmStatic
    fun menuClickMethod(context: FeatureContext, logger: (String, Throwable?) -> Unit): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, cacheKey, context.hostClassLoader(), CACHE_MENU_CLICK)
            ?.takeIf(::isMenuClickMethod)
            ?.let { return it }
        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        name("onMMMenuItemSelected")
                        returnType("void")
                        paramTypes("android.view.MenuItem", "int")
                        usingEqStrings(CLICK_METHOD_ANCHOR)
                    })
                }
            ).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isMenuClickMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure { logger("定位多选消息菜单点击方法失败", it) }.getOrDefault(emptyList())
        return saveSingleMethod(prefs, cacheKey, CACHE_MENU_CLICK, candidates, "菜单点击", logger)
    }

    @JvmStatic
    fun multiSelectExitMethod(
        context: FeatureContext,
        menuClickMethod: Method,
        logger: (String, Throwable?) -> Unit
    ): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, cacheKey, context.hostClassLoader(), CACHE_MULTI_SELECT_EXIT)
            ?.takeIf(::isMultiSelectExitMethod)
            ?.let { return it }
        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        returnType("void")
                        paramTypes()
                        declaredClass(CHATTING_COMPONENT_PREFIX, StringMatchType.StartsWith)
                        addCaller(MethodMatcher(menuClickMethod))
                    })
                }
            ).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isMultiSelectExitMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure { logger("定位多选消息原生退出方法失败", it) }.getOrDefault(emptyList())
        return saveSingleMethod(
            prefs,
            cacheKey,
            CACHE_MULTI_SELECT_EXIT,
            candidates,
            "原生退出",
            logger
        )
    }

    private fun saveSingleMethod(
        prefs: android.content.SharedPreferences,
        cacheKey: String,
        name: String,
        candidates: List<Method>,
        label: String,
        logger: (String, Throwable?) -> Unit
    ): Method? {
        val method = candidates.singleOrNull()
        if (method != null) {
            DexMethodCache.save(prefs, cacheKey, name, method)
        } else {
            DexMethodCache.clear(prefs, cacheKey, name)
            if (candidates.size > 1) {
                logger("多选消息${label}候选不唯一: ${candidates.joinToString { it.toGenericString() }}", null)
            }
        }
        return method
    }

    private fun isMenuCreateMethod(method: Method): Boolean {
        return method.returnType == Void.TYPE &&
            method.name == "onCreateMMMenu" &&
            method.parameterTypes.size == 1 &&
            method.declaringClass.name.startsWith(CHATTING_COMPONENT_PREFIX)
    }

    private fun isMenuClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            method.name == "onMMMenuItemSelected" &&
            types.size == 2 &&
            MenuItem::class.java.isAssignableFrom(types[0]) &&
            method.declaringClass.name.startsWith(CHATTING_COMPONENT_PREFIX)
    }

    private fun isMultiSelectExitMethod(method: Method): Boolean {
        return method.returnType == Void.TYPE &&
            method.parameterTypes.isEmpty() &&
            !Modifier.isStatic(method.modifiers) &&
            method.declaringClass.name.startsWith(CHATTING_COMPONENT_PREFIX)
    }
}
