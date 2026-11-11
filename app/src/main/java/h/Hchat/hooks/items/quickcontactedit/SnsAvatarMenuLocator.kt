package h.Hchat.hooks.items.quickcontactedit

import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class SnsAvatarMenuMethods(
    val createMethods: List<Method>,
    val clickMethods: List<Method>
)

internal object SnsAvatarMenuLocator {
    private const val PREFS_NAME = "Hchat_sns_avatar_menu_method_cache"
    private const val CACHE_CREATE = "avatar_menu_create_v2"
    private const val CACHE_CLICK = "avatar_menu_click_v2"

    fun locate(context: FeatureContext, logger: (String, Throwable?) -> Unit): SnsAvatarMenuMethods {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cachedCreate = DexMethodCache.loadList(
            prefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_CREATE
        ).filter(::isCreateMethod)
        val cachedClick = DexMethodCache.loadList(
            prefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_CLICK
        ).filter(::isClickMethod)
        if (cachedCreate.isNotEmpty() && cachedClick.isNotEmpty()) {
            return SnsAvatarMenuMethods(cachedCreate, cachedClick)
        }

        val createMethods = findMethods(
            context,
            MethodMatcher().apply {
                returnType("void")
                paramTypes(
                    "android.view.ContextMenu",
                    "android.view.View",
                    "android.view.ContextMenu\$ContextMenuInfo"
                )
                usingEqStrings("MMSocialBlackListFlag", "3552365301")
            },
            ::isCreateMethod,
            logger
        )
        val legacyClicks = findMethods(
            context,
            MethodMatcher().apply {
                returnType("void")
                paramTypes("android.view.MenuItem", "int")
                usingEqStrings(
                    "sns_permission_userName",
                    "clicfg_sns_expose_config_switch_android"
                )
            },
            ::isClickMethod,
            logger
        )
        val improveClicks = findMethods(
            context,
            MethodMatcher().apply {
                returnType("boolean")
                paramTypes("android.view.MenuItem", "int")
                usingEqStrings(
                    "click AVATER_MENU_ID_PERMISSION",
                    "click AVATER_MENU_ID_EXPOSE"
                )
            },
            ::isClickMethod,
            logger
        )
        val oldImproveClicks = findMethods(
            context,
            MethodMatcher().apply {
                returnType("void")
                paramTypes("android.view.MenuItem", "int")
                usingEqStrings(
                    "com.tencent.mm.plugin.sns.ui.item.improve.TimelineItemClick\$popPermissionMenuWindow\$2"
                )
            },
            ::isClickMethod,
            logger
        )
        val clickMethods = (legacyClicks + improveClicks + oldImproveClicks)
            .distinctBy(Method::toGenericString)

        if (createMethods.isNotEmpty()) {
            DexMethodCache.saveList(prefs, runtimeKey, CACHE_CREATE, createMethods)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_CREATE)
        }
        if (clickMethods.isNotEmpty()) {
            DexMethodCache.saveList(prefs, runtimeKey, CACHE_CLICK, clickMethods)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_CLICK)
        }
        return SnsAvatarMenuMethods(createMethods, clickMethods)
    }

    private fun findMethods(
        context: FeatureContext,
        matcher: MethodMatcher,
        predicate: (Method) -> Boolean,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply { matcher(matcher) })
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .filter(predicate)
                .distinctBy(Method::toGenericString)
        }.onFailure {
            logger("定位朋友圈头像长按菜单方法失败", it)
        }.getOrDefault(emptyList())
    }

    private fun isCreateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isHookable(method) &&
            method.returnType == Void.TYPE &&
            types.size == 3 &&
            ContextMenu::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2].name == "android.view.ContextMenu\$ContextMenuInfo"
    }

    private fun isClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isHookable(method) &&
            (method.returnType == Void.TYPE || method.returnType == Boolean::class.javaPrimitiveType) &&
            types.size == 2 &&
            MenuItem::class.java.isAssignableFrom(types[0]) &&
            types[1] == Integer.TYPE
    }

    private fun isHookable(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface
    }
}
