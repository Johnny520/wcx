package h.Hchat.hooks.api.media

import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

object FavoriteMenuLocator {
    const val HCHAT_GENERIC_FAVORITE_FORWARD_MENU_ITEM_ID = 0x48434641
    const val HCHAT_VOICE_FAVORITE_FORWARD_MENU_ITEM_ID = 0x48435646

    private const val FAVORITE_UI_PREFIX = "com.tencent.mm.plugin.fav.ui."
    private const val PREFS_NAME = "Hchat_favorite_menu_method_cache"

    @JvmStatic
    fun menuCreateMethods(
        context: FeatureContext,
        includeDetails: Boolean,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        val cacheName = if (includeDetails) "menu_create_all_v6" else "menu_create_list_v4"
        cachedMethods(context, cacheName) { method ->
            isFavoriteMenuCreateMethod(method) && (includeDetails || !isFavoriteDetailMethod(method))
        }
            .takeIf { isCompleteMenuCreateCache(it, includeDetails) }
            ?.let { return it }

        val methods = linkedSetOf<Method>()
        findMethods(
            context,
            MethodMatcher().apply { usingStrings(listOf("OnCreateContextMMMenu")) },
            "定位收藏列表菜单创建方法失败",
            logger
        ).filter { method ->
            isFavoriteListMenuCreateMethod(method) && (includeDetails || !isFavoriteDetailMethod(method))
        }.toCollection(methods)
        // 搜索和类型筛选页使用 Android ContextMenu 长按回调，始终和主页列表一起安装。
        findContextMenuMethods(context, logger).filter { method ->
            isFavoriteMenuCreateMethod(method) && (includeDetails || !isFavoriteDetailMethod(method))
        }.toCollection(methods)
        findMethods(
            context,
            MethodMatcher().apply { usingStrings(listOf("MicroMsg.FavSearchManager")) },
            "定位收藏顶部筛选菜单创建方法失败",
            logger
        ).filterTo(methods) { method ->
            isFavoriteListMenuCreateMethod(method) && favoriteListPage(method) == FAVORITE_TOP_SEARCH_PAGE
        }
        if (includeDetails) {
            findMethods(
                context,
                MethodMatcher().apply {
                    name("onCreateMMMenu")
                    returnType("void")
                },
                "定位收藏详情菜单创建方法失败",
                logger
            ).filterTo(methods, ::isFavoriteMenuCreateMethod)
        }
        return saveMethods(context, cacheName, methods.toList())
    }

    @JvmStatic
    fun menuClickMethods(
        context: FeatureContext,
        includeDetails: Boolean,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        val cacheName = if (includeDetails) "menu_click_all_v5" else "menu_click_list_v4"
        cachedMethods(context, cacheName) { method ->
            isFavoriteClickMethod(method) && (includeDetails || !isFavoriteDetailMethod(method))
        }.takeIf { isCompleteMenuClickCache(it, includeDetails) }
            ?.let { return it }

        val matcher = MethodMatcher().apply {
            name("onMMMenuItemSelected")
            returnType("void")
            paramTypes("android.view.MenuItem", "int")
            if (!includeDetails) {
                usingStrings(
                    listOf(
                        "do transmit, long click info is %s",
                        "do edit, long click info is %s",
                        "do tag, long click info is %s"
                    )
                )
            }
        }
        val methods = findMethods(
            context,
            matcher,
            "定位收藏菜单点击方法失败",
            logger
        ).filter { method ->
            isFavoriteClickMethod(method) && (includeDetails || !isFavoriteDetailMethod(method))
        }.toMutableSet()
        if (!includeDetails) {
            findMethods(
                context,
                MethodMatcher().apply {
                    name("onMMMenuItemSelected")
                    returnType("void")
                    paramTypes("android.view.MenuItem", "int")
                },
                "补充定位收藏列表菜单点击方法失败",
                logger
            ).filterTo(methods) { method ->
                isFavoriteClickMethod(method) && favoriteListPage(method) != null
            }
            findMethods(
                context,
                MethodMatcher().apply { usingStrings(listOf("MicroMsg.FavSearchManager")) },
                "定位收藏顶部筛选菜单点击方法失败",
                logger
            ).filterTo(methods) { method ->
                isFavoriteClickMethod(method) && favoriteListPage(method) == FAVORITE_TOP_SEARCH_PAGE
            }
        }
        return saveMethods(context, cacheName, methods.toList())
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

    private fun findContextMenuMethods(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        val methods = linkedSetOf<Method>()
        findMethods(
            context,
            MethodMatcher().apply {
                name("onCreateContextMenu")
                returnType("void")
                paramTypes(
                    "android.view.ContextMenu",
                    "android.view.View",
                    "android.view.ContextMenu\$ContextMenuInfo"
                )
            },
            "定位收藏搜索菜单创建方法失败",
            logger
        ).filterTo(methods, ::isFavoriteMenuCreateMethod)

        // Some WeChat builds retain the interface callback but DexKit cannot
        // match its parameter types after class-loader resolution. Also merge
        // the name-only result so a partial exact query cannot omit one page.
        findMethods(
            context,
            MethodMatcher().apply {
                name("onCreateContextMenu")
                returnType("void")
            },
            "定位收藏搜索菜单创建方法失败",
            logger
        ).filterTo(methods, ::isFavoriteMenuCreateMethod)
        return methods.toList()
    }

    private fun cachedMethods(
        context: FeatureContext,
        cacheName: String,
        validator: (Method) -> Boolean
    ): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        return DexMethodCache.loadList(prefs, cacheKey, context.hostClassLoader(), cacheName)
            .filter(validator)
            .distinctBy { it.toGenericString() }
    }

    private fun saveMethods(
        context: FeatureContext,
        cacheName: String,
        methods: List<Method>
    ): List<Method> {
        val result = methods.distinctBy { it.toGenericString() }
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        if (result.isEmpty()) {
            DexMethodCache.clear(prefs, cacheKey, cacheName)
        } else {
            DexMethodCache.saveList(prefs, cacheKey, cacheName, result)
        }
        return result
    }

    private fun isFavoriteListMenuCreateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            method.declaringClass.name.startsWith(FAVORITE_UI_PREFIX) &&
            types.size == 3 &&
            View::class.java.isAssignableFrom(types[1]) &&
            (ContextMenu::class.java.isAssignableFrom(types[0]) || method.name == "a")
    }

    private fun isFavoriteMenuCreateMethod(method: Method): Boolean {
        if (isFavoriteListMenuCreateMethod(method)) return true
        return method.returnType == Void.TYPE &&
            method.name == "onCreateMMMenu" &&
            method.parameterTypes.size == 1 &&
            method.declaringClass.name.startsWith("${FAVORITE_UI_PREFIX}detail.")
    }

    private fun isCompleteMenuCreateCache(methods: List<Method>, includeDetails: Boolean): Boolean {
        if (methods.isEmpty()) return false
        val pages = methods.mapNotNull(::favoriteListPage).toSet()
        val hasAllLists = REQUIRED_LIST_PAGES.all(pages::contains)
        if (!includeDetails) return hasAllLists
        val hasDetail = methods.any { method -> method.name == "onCreateMMMenu" }
        return hasAllLists && hasDetail
    }

    private fun isCompleteMenuClickCache(methods: List<Method>, includeDetails: Boolean): Boolean {
        if (methods.isEmpty()) return false
        if (includeDetails) return methods.any(::isFavoriteDetailMethod)
        val pages = methods.mapNotNull(::favoriteListPage).toSet()
        return REQUIRED_LIST_PAGES.all(pages::contains)
    }

    private fun favoriteListPage(method: Method): String? {
        var current: Class<*>? = method.declaringClass
        while (current != null && current != Any::class.java) {
            for (field in current.declaredFields) {
                val typeName = field.type.name
                if (typeName in REQUIRED_LIST_PAGES) return typeName
                if (typeName.startsWith(FAVORITE_UI_PREFIX) && hasFavoriteAdapterField(field.type)) {
                    return FAVORITE_TOP_SEARCH_PAGE
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun isFavoriteClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 2 &&
            MenuItem::class.java.isAssignableFrom(types[0]) &&
            method.declaringClass.name.startsWith(FAVORITE_UI_PREFIX)
    }

    private fun isFavoriteDetailMethod(method: Method): Boolean {
        return method.declaringClass.name.startsWith("${FAVORITE_UI_PREFIX}detail.")
    }

    private fun hasFavoriteAdapterField(clazz: Class<*>): Boolean {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (current.declaredFields.any { field ->
                    field.type.name.startsWith("com.tencent.mm.plugin.fav.ui.adapter.")
                }) return true
            current = current.superclass
        }
        return false
    }

    private val REQUIRED_LIST_PAGES = setOf(
        "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI",
        "com.tencent.mm.plugin.fav.ui.FavSearchUI",
        "com.tencent.mm.plugin.fav.ui.FavFilterUI",
        FAVORITE_TOP_SEARCH_PAGE
    )

    private const val FAVORITE_TOP_SEARCH_PAGE = "favorite_top_search"
}
