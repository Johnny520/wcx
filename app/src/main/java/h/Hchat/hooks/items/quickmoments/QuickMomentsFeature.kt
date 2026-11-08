package h.Hchat.hooks.items.quickmoments

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.ConversationMenuExtension
import h.Hchat.hooks.core.ConversationMenuExtensionRegistry
import h.Hchat.hooks.core.ConversationMenuExtensionTarget
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.quickcontactedit.ConversationMenuLocator
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class QuickMomentsFeature : BaseFeature() {
    private var runtime: QuickMomentsMenuRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "快捷查看朋友圈"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuickMomentsSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = QuickMomentsMenuRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "quick_moments"
    }
}

object QuickMomentsRuntime {
    fun isEnabled(context: Context?): Boolean {
        if (context == null) return false
        return HchatStorage.preferences(context, QuickMomentsSettings.PREFS_NAME)
            .getBoolean(QuickMomentsSettings.KEY_ENABLE, QuickMomentsSettings.DEFAULT_ENABLE)
    }

    fun canOpen(talker: String?): Boolean {
        val wxId = talker?.trim().orEmpty()
        if (wxId.isEmpty()) return false
        if (runCatching { WeChatApis.account()?.selfWxId() == wxId }.getOrDefault(false)) return true
        return runCatching {
            val contacts = WeChatApis.contacts() ?: return@runCatching false
            if (contacts.isGroup(wxId)) return@runCatching false
            if (contacts.isFriend(wxId)) return@runCatching true
            if (!wxId.endsWith(OPEN_IM_SUFFIX, ignoreCase = true)) return@runCatching false
            val contact = contacts.getContact(wxId) ?: return@runCatching false
            !contact.isGroup() && !contact.isOfficialAccount()
        }.getOrDefault(false)
    }

    fun open(activity: Activity, talker: String): Boolean {
        val wxId = talker.trim()
        if (!canOpen(wxId) || activity.isFinishing || activity.isDestroyed) return false
        return runCatching {
            val intent = Intent().apply {
                setClassName(activity, SNS_USER_UI_CLASS)
                putExtra(SNS_USERNAME_EXTRA, wxId)
            }
            activity.startActivity(intent)
            true
        }.getOrElse {
            HLog.e("[Hchat:${QuickMomentsFeature.ID}] 打开个人朋友圈失败: talker=$wxId", it)
            false
        }
    }

    private const val SNS_USER_UI_CLASS = "com.tencent.mm.plugin.sns.ui.SnsUserUI"
    private const val SNS_USERNAME_EXTRA = "sns_userName"
    private const val OPEN_IM_SUFFIX = "@openim"
}

private class QuickMomentsMenuRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class MenuTarget(val activity: Activity, val talker: String)

    private val main = Handler(Looper.getMainLooper())
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, MenuTarget>())
    private val conversationMenuExtension = object : ConversationMenuExtension {
        override val itemId: Int = MENU_ITEM_ID
        override val order: Int = 110

        override fun title(target: ConversationMenuExtensionTarget): String = MENU_TITLE

        override fun isVisible(target: ConversationMenuExtensionTarget): Boolean {
            return QuickMomentsRuntime.isEnabled(target.activity) &&
                QuickMomentsRuntime.canOpen(target.talker)
        }

        override fun onClick(target: ConversationMenuExtensionTarget) {
            openTarget(target.activity, target.talker)
        }
    }

    init {
        ConversationMenuExtensionRegistry.register(conversationMenuExtension)
    }

    @Synchronized
    fun install(): Boolean {
        val create = ConversationMenuLocator.menuCreateMethod(context, logger) ?: return false
        return hook(create, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                addMenuItem(param)
            }
        })
    }

    fun destroy() {
        ConversationMenuExtensionRegistry.unregister(conversationMenuExtension)
        bindings.clear()
    }

    private fun addMenuItem(param: XC_MethodHook.MethodHookParam) {
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        val view = param.args?.getOrNull(1) as? View ?: return
        val activity = findActivity(view.context) ?: return
        if (!QuickMomentsRuntime.isEnabled(activity)) return
        if (!installMenuClickHook(param.thisObject)) return
        val talker = resolveTalker(param.thisObject) ?: return

        menu.removeItem(MENU_ITEM_ID)
        val groupId = runCatching { menu.getItem(0).groupId }.getOrDefault(0)
        val item = menu.add(groupId, MENU_ITEM_ID, 0, MENU_TITLE)
        moveMenuItemToFront(menu, item)
        bindings[item] = MenuTarget(activity, talker)
    }

    private fun installMenuClickHook(listener: Any?): Boolean {
        listener ?: return false
        val method = KavaReflector.declaredFields(listener.javaClass)
            .asSequence()
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapNotNull { field -> KavaReflector.readField(field, listener) }
            .mapNotNull { callback ->
                KavaReflector.findMethod(
                    callback.javaClass,
                    "onMMMenuItemSelected",
                    MenuItem::class.java,
                    Integer.TYPE
                )
            }
            .firstOrNull { candidate ->
                candidate.returnType == Void.TYPE &&
                    candidate.declaringClass.name.startsWith("com.tencent.mm.ui.conversation.")
            } ?: return false
        return hook(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val item = param.args?.getOrNull(0) as? MenuItem ?: return
                if (item.itemId != MENU_ITEM_ID) return
                val target = bindings.remove(item) ?: return
                param.result = null
                main.post { openTarget(target.activity, target.talker) }
            }
        })
    }

    private fun openTarget(activity: Activity, talker: String) {
        if (!QuickMomentsRuntime.open(activity, talker)) {
            Toast.makeText(activity, "无法打开该联系人的朋友圈", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resolveTalker(listener: Any?): String? {
        listener ?: return null
        var current: Class<*>? = listener.javaClass
        while (current != null && current != Any::class.java) {
            val talker = KavaReflector.declaredFields(current)
                .asSequence()
                .filter { it.type == String::class.java }
                .mapNotNull { KavaReflector.readField(it, listener) as? String }
                .map { it.trim() }
                .firstOrNull(QuickMomentsRuntime::canOpen)
            if (talker != null) return talker
            current = current.superclass
        }
        return null
    }

    private fun moveMenuItemToFront(menu: ContextMenu, item: MenuItem) {
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                val index = items.indexOfFirst { candidate ->
                    candidate === item || (candidate as? MenuItem)?.itemId == MENU_ITEM_ID
                }
                if (index > 0) {
                    runCatching {
                        val moved = items.removeAt(index)
                        items.add(0, moved)
                    }
                }
                if (index >= 0) return
            }
            current = current.superclass
        }
    }

    private fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            val base = current.baseContext
            if (base === current) break
            current = base
        }
        return current as? Activity
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("安装快捷查看朋友圈菜单 Hook 失败: ${method.toGenericString()}", it)
            false
        }
    }

    companion object {
        private const val MENU_ITEM_ID = 0x48434D53
        private const val MENU_TITLE = "朋友圈"
    }
}
