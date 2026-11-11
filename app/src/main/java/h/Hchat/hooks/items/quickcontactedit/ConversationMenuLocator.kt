package h.Hchat.hooks.items.quickcontactedit

import android.widget.AdapterView
import android.view.ContextMenu
import android.view.View
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal object ConversationMenuLocator {
    private const val PREFS_NAME = "Hchat_conversation_menu_method_cache"
    private const val CACHE_CREATE = "context_menu_create_v2"
    private const val CONVERSATION_PACKAGE = "com.tencent.mm.ui.conversation."
    private const val LONG_CLICK_LOG = "headercount:%d, postion:%d"

    fun menuCreateMethod(context: FeatureContext, logger: (String, Throwable?) -> Unit): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(prefs, runtimeKey, context.hostClassLoader(), CACHE_CREATE)
            ?.takeIf(::isCreateMethod)
            ?.let { return it }

        val create = runCatching {
            val longClick = context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(CONVERSATION_PACKAGE, StringMatchType.Contains, false)
                            returnType("boolean")
                            paramTypes(
                                "android.widget.AdapterView",
                                "android.view.View",
                                "int",
                                "long"
                            )
                            usingEqStrings(LONG_CLICK_LOG)
                        }
                    )
                }
            ).asSequence()
                .mapNotNull { data ->
                    runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
                .firstOrNull(::isLongClickMethod)
                ?: return@runCatching null
            KavaReflector.findDeclaredMethod(
                longClick.declaringClass,
                "onCreateContextMenu",
                ContextMenu::class.java,
                View::class.java,
                ContextMenu.ContextMenuInfo::class.java
            )?.takeIf(::isCreateMethod)
        }.onFailure {
            logger("定位会话长按菜单创建方法失败", it)
        }.getOrNull()

        if (create != null) {
            DexMethodCache.save(prefs, runtimeKey, CACHE_CREATE, create)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_CREATE)
        }
        return create
    }

    private fun isLongClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return isHookable(method) &&
            method.returnType == Boolean::class.javaPrimitiveType &&
            types.size == 4 &&
            AdapterView::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2] == Integer.TYPE &&
            types[3] == java.lang.Long.TYPE
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

    private fun isHookable(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) &&
            !Modifier.isAbstract(method.modifiers) &&
            !method.declaringClass.isInterface
    }
}
