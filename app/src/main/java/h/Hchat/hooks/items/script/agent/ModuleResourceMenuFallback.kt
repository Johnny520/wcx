package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.content.res.Resources
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import h.Hchat.utils.HLog

object ModuleResourceMenuFallback {

    private const val MENU_BUILDER = "com.android.internal.view.menu.MenuBuilder"

    private val INT = Int::class.javaPrimitiveType!!

    private val selectionMenus = java.util.WeakHashMap<Any, Boolean>()

    private val LABELS = mapOf(
        "copy_text" to "复制",
        "paste_text" to "粘贴",
        "text_selection_more" to "更多",
        "select_all" to "全选",
    )

    private val LABELS_BY_ITEM = mapOf(1 to "复制", 2 to "粘贴", 3 to "更多")

    fun install(classLoader: ClassLoader) {
        hookTitleResourceOverload(classLoader, "add")
        hookTitleResourceOverload(classLoader, "addSubMenu")
        hookClickDispatch(classLoader)
    }

    private fun hookClickDispatch(classLoader: ClassLoader) {
        try {
            val menuBuilderClass = Class.forName(MENU_BUILDER, false, classLoader)
            XposedHelpers.findAndHookMethod(
                MENU_BUILDER,
                classLoader,
                "dispatchMenuItemSelected",
                menuBuilderClass,
                android.view.MenuItem::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val item = param.args?.getOrNull(1) as? android.view.MenuItem ?: return
                        val menu = param.args?.getOrNull(0) ?: param.thisObject
                        if (!isSelectionMenu(menu) && !isSelectionMenu(param.thisObject)) return
                        val terminal = TerminalBridge.current() ?: return
                        when (item.itemId) {
                            1 -> {
                                val text = terminal.selectedText()
                                if (text.isNullOrEmpty()) return
                                terminal.copyToClipboard(text)
                                terminal.stopSelection()
                            }
                            2 -> {
                                terminal.stopSelection()
                                terminal.pasteFromClipboard()
                            }
                            3 -> {
                                terminal.stopSelection()
                                terminal.showMoreMenu()
                            }
                            else -> return
                        }
                        closeActionMode(menu)
                        param.result = true
                    }
                },
            )
        } catch (t: Throwable) {
            HLog.e("选词菜单点击接管安装失败", t)
        }
    }

    private fun closeActionMode(menu: Any?) {
        runCatching {
            val actionMode = XposedHelpers.getObjectField(menu, "mCallback") ?: return
            XposedHelpers.callMethod(actionMode, "finish")
        }
    }

    private fun markSelectionMenu(menuBuilder: Any) {
        synchronized(selectionMenus) { selectionMenus[menuBuilder] = true }
    }

    private fun isSelectionMenu(menuBuilder: Any?): Boolean {
        if (menuBuilder == null) return false
        return synchronized(selectionMenus) { selectionMenus.containsKey(menuBuilder) }
    }

    private fun hookTitleResourceOverload(classLoader: ClassLoader, method: String) {
        try {
            XposedHelpers.findAndHookMethod(
                MENU_BUILDER,
                classLoader,
                method,
                INT,
                INT,
                INT,
                INT,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args ?: return
                        if (args.size < 4) return
                        val resId = args[3] as? Int ?: return
                        if (resId == 0) return
                        val context = contextOf(param.thisObject) ?: return

                        val entry = ModuleResources.entryName(context, resId)
                        val hostRes = resourcesOf(param.thisObject)
                        val hostIsString = hostRes != null && runCatching {
                            hostRes.getResourceTypeName(resId)
                        }.getOrNull() == "string"

                        val termuxMenu = isTermuxSelectionMenu(param.thisObject)
                        val label: CharSequence? = when {
                            entry != null && LABELS.containsKey(entry) && (!hostIsString || termuxMenu) ->
                                LABELS[entry]
                            entry != null && !hostIsString && termuxMenu -> ModuleResources.text(context, resId)
                            entry == null && !hostIsString && termuxMenu ->
                                LABELS_BY_ITEM[args[1] as? Int ?: -1]
                            else -> null
                        }
                        if (label == null) return
                        if (termuxMenu) markSelectionMenu(param.thisObject)

                        val item = runCatching {
                            XposedHelpers.callMethod(
                                param.thisObject,
                                method,
                                arrayOf(INT, INT, INT, CharSequence::class.java),
                                args[0],
                                args[1],
                                args[2],
                                label,
                            )
                        }.getOrNull() ?: return
                        param.result = item
                    }
                },
            )
        } catch (t: Throwable) {
            HLog.e("菜单资源兜底安装失败: $method", t)
        }
    }

    private fun isTermuxSelectionMenu(menuBuilder: Any): Boolean =
        fromCallStack() || fromMenuCallback(menuBuilder)

    private fun fromCallStack(): Boolean = runCatching {
        Throwable().stackTrace.any { it.className.startsWith("com.termux.view") }
    }.getOrDefault(false)

    private fun fromMenuCallback(menuBuilder: Any): Boolean = runCatching {
        val actionMode = XposedHelpers.getObjectField(menuBuilder, "mCallback") ?: return false
        val callback = XposedHelpers.getObjectField(actionMode, "mCallback") ?: return false
        callback.javaClass.name.startsWith("com.termux.view")
    }.getOrDefault(false)

    private fun resourcesOf(menuBuilder: Any): Resources? =
        runCatching { XposedHelpers.getObjectField(menuBuilder, "mResources") as? Resources }
            .getOrNull()
            ?: runCatching { XposedHelpers.callMethod(menuBuilder, "getResources") as? Resources }
                .getOrNull()

    private fun contextOf(menuBuilder: Any): Context? =
        runCatching { XposedHelpers.callMethod(menuBuilder, "getContext") as? Context }.getOrNull()
}
