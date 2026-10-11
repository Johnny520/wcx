package h.Hchat.hooks.api.sns

import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object SnsContextMenuLocator {
    private const val PREFS_NAME = "Hchat_sns_context_menu_method_cache"
    private const val CACHE_CREATE = "menu_create_v1"
    private const val CACHE_CLICK = "menu_click_v1"
    private const val CACHE_FOLD_BIND = "fold_menu_bind_v1"
    private const val SNS_PACKAGE = "com.tencent.mm.plugin.sns."

    private val improveClickAnchors = listOf(
        "com.tencent.mm.plugin.sns.ui.improve.item.click.BaseImproveClick\$register\$2",
        "com.tencent.mm.plugin.sns.ui.improve.item.click.BaseImproveClick\$register\$3",
        "com.tencent.mm.plugin.sns.ui.improve.item.click.ImproveMultiPhotoClick\$register\$1\$1\$1"
    )

    fun menuCreateMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        cachedMethods(context, CACHE_CREATE, ::isMenuCreateMethod)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        val methods = findMethods(
            context,
            MethodMatcher().apply {
                usingStrings(
                    listOf(
                        "MicroMsg.TimelineOnCreateContextMenuListener",
                        "onMMCreateContextMenu error"
                    )
                )
            },
            "定位朋友圈菜单创建方法失败",
            logger
        ).filter(::isMenuCreateMethod)
        return saveMethods(context, CACHE_CREATE, methods)
    }

    fun menuClickMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        cachedMethods(context, CACHE_CLICK, ::isMenuClickMethod)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        val methods = linkedSetOf<Method>()
        findMethods(
            context,
            MethodMatcher().apply {
                usingStrings(
                    listOf(
                        "delete comment fail!!! snsInfo is null",
                        "send photo fail, mediaObj is null",
                        "mediaObj is null, send failed!"
                    )
                )
            },
            "定位朋友圈旧版菜单点击方法失败",
            logger
        ).filterTo(methods, ::isMenuClickMethod)
        improveClickAnchors.forEach { anchor ->
            findMethods(
                context,
                MethodMatcher().apply {
                    usingStrings(listOf("onMMMenuItemSelected", anchor))
                },
                "定位朋友圈新版菜单点击方法失败: $anchor",
                logger
            ).filterTo(methods, ::isMenuClickMethod)
        }
        return saveMethods(context, CACHE_CLICK, methods.toList())
    }

    fun foldBindCompletionMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        cachedMethods(context, CACHE_FOLD_BIND, ::isFoldBindCompletionMethod)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        val methods = findMethods(
            context,
            MethodMatcher().apply {
                usingStrings(
                    listOf(
                        "com.tencent.mm.plugin.sns.ui.improve.item.FoldImproveTimelineItem\$loadCustomItemInfo\$2",
                        "invokeSuspend"
                    )
                )
            },
            "定位朋友圈折叠卡片绑定完成方法失败",
            logger
        ).filter(::isFoldBindCompletionMethod)
        return saveMethods(context, CACHE_FOLD_BIND, methods)
    }

    private fun findMethods(
        context: FeatureContext,
        methodMatcher: MethodMatcher,
        error: String,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply { matcher(methodMatcher) })
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .distinctBy { it.toGenericString() }
        }.onFailure { logger(error, it) }.getOrDefault(emptyList())
    }

    private fun cachedMethods(
        context: FeatureContext,
        name: String,
        validator: (Method) -> Boolean
    ): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        return DexMethodCache.loadList(prefs, key, context.hostClassLoader(), name)
            .filter(validator)
            .distinctBy { it.toGenericString() }
    }

    private fun saveMethods(
        context: FeatureContext,
        name: String,
        methods: List<Method>
    ): List<Method> {
        val result = methods.distinctBy { it.toGenericString() }
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        if (result.isEmpty()) {
            DexMethodCache.clear(prefs, key, name)
        } else {
            DexMethodCache.saveList(prefs, key, name, result)
        }
        return result
    }

    private fun isMenuCreateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isHookable(method) &&
            method.returnType == Void.TYPE &&
            method.name == "onCreateContextMenu" &&
            method.declaringClass.name.startsWith(SNS_PACKAGE) &&
            types.size == 3 &&
            ContextMenu::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2].name == "android.view.ContextMenu\$ContextMenuInfo"
    }

    private fun isMenuClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isHookable(method) &&
            method.returnType == Void.TYPE &&
            method.name == "onMMMenuItemSelected" &&
            types.size == 2 &&
            MenuItem::class.java.isAssignableFrom(types[0]) &&
            types[1] == Integer.TYPE
    }

    private fun isFoldBindCompletionMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isHookable(method) &&
            method.name == "invokeSuspend" &&
            method.returnType == Any::class.java &&
            types.size == 1 &&
            types[0] == Any::class.java
    }

    private fun isHookable(method: Method): Boolean {
        return !Modifier.isAbstract(method.modifiers) && !method.declaringClass.isInterface
    }
}
