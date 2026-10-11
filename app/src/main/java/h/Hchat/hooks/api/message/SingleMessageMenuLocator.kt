package h.Hchat.hooks.api.message

import android.view.MenuItem
import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object SingleMessageMenuLocator {
    const val HCHAT_REPEAT_MENU_ITEM_ID = 0x48435250
    const val HCHAT_FORWARD_MENU_ITEM_ID = 0x48434657

    private const val CHAT_VIEWITEMS_PACKAGE = "com.tencent.mm.ui.chatting.viewitems."
    private const val PREFS_NAME = "Hchat_single_message_menu_method_cache"
    private const val CACHE_MENU_CREATE = "menu_create_v1"
    private const val CACHE_MENU_CLICK = "menu_click_v1"

    @JvmStatic
    fun menuCreateMethods(context: FeatureContext, logger: (String, Throwable?) -> Unit): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.loadList(prefs, cacheKey, context.hostClassLoader(), CACHE_MENU_CREATE)
            .filter(::isMenuCreateMethod)
            .distinctBy { it.toGenericString() }
        if (cached.isNotEmpty()) return cached

        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(CHAT_VIEWITEMS_PACKAGE, StringMatchType.Contains, false)
                            returnType("void")
                            paramCount(3)
                            usingEqStrings("MicroMsg.ChattingItem", "msg is null!")
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isMenuCreateMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure {
            logger("定位单消息菜单创建方法失败", it)
        }.getOrDefault(emptyList())

        saveMethods(prefs, cacheKey, CACHE_MENU_CREATE, methods)
        return methods
    }

    @JvmStatic
    fun menuClickMethods(context: FeatureContext, logger: (String, Throwable?) -> Unit): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.loadList(prefs, cacheKey, context.hostClassLoader(), CACHE_MENU_CLICK)
            .filter(::isMenuClickMethod)
            .distinctBy { it.toGenericString() }
        if (cached.isNotEmpty()) return cached

        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(CHAT_VIEWITEMS_PACKAGE, StringMatchType.Contains, false)
                            returnType("void")
                            paramTypes("android.view.MenuItem", "int")
                            usingEqStrings(
                                "MicroMsg.ChattingItem",
                                "context item select failed, null dataTag"
                            )
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(::isMenuClickMethod)
                .distinctBy { it.toGenericString() }
        }.onFailure {
            logger("定位单消息菜单点击方法失败", it)
        }.getOrDefault(emptyList())

        saveMethods(prefs, cacheKey, CACHE_MENU_CLICK, methods)
        return methods
    }

    private fun saveMethods(
        prefs: android.content.SharedPreferences,
        cacheKey: String,
        name: String,
        methods: List<Method>
    ) {
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(prefs, cacheKey, name, methods)
        } else {
            DexMethodCache.clear(prefs, cacheKey, name)
        }
    }

    private fun isMenuCreateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            isHookable(method) &&
            !Modifier.isStatic(method.modifiers) &&
            types.size == 3 &&
            !MenuItem::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2].name == "android.view.ContextMenu\$ContextMenuInfo" &&
            method.declaringClass.name.startsWith(CHAT_VIEWITEMS_PACKAGE)
    }

    private fun isMenuClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            isHookable(method) &&
            !Modifier.isStatic(method.modifiers) &&
            types.size == 2 &&
            MenuItem::class.java.isAssignableFrom(types[0]) &&
            types[1] == Integer.TYPE &&
            method.declaringClass.name.startsWith(CHAT_VIEWITEMS_PACKAGE)
    }

    private fun isHookable(method: Method): Boolean {
        return !Modifier.isAbstract(method.modifiers) && !method.declaringClass.isInterface
    }
}
