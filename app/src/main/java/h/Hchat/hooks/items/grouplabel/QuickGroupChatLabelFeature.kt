package h.Hchat.hooks.items.grouplabel

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.MenuItem
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
import h.Hchat.hooks.items.conversationgroup.ConversationGroupRuntime
import h.Hchat.hooks.items.quickcontactedit.ConversationMenuLocator
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class QuickGroupChatLabelFeature : BaseFeature() {
    private var runtime: QuickGroupChatLabelRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "快捷设置群聊标签"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuickGroupChatLabelSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = QuickGroupChatLabelRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.installConversationMenuHook() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "quick_group_chat_label"
    }
}

private class QuickGroupChatLabelRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class MenuTarget(
        val activity: Activity,
        val talker: String
    )

    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        QuickGroupChatLabelSettings.PREFS_NAME
    )
    private val main = Handler(Looper.getMainLooper())
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, MenuTarget>())
    private val conversationMenuExtension = object : ConversationMenuExtension {
        override val itemId: Int = MENU_ITEM_ID
        override val order: Int = 105

        override fun title(target: ConversationMenuExtensionTarget): String = MENU_TITLE

        override fun isVisible(target: ConversationMenuExtensionTarget): Boolean {
            return enabled() && isGroup(target.talker)
        }

        override fun onClick(target: ConversationMenuExtensionTarget) {
            showLabelActions(MenuTarget(target.activity, target.talker))
        }
    }

    init {
        ConversationMenuExtensionRegistry.register(conversationMenuExtension)
    }

    @Synchronized
    fun installConversationMenuHook(): Boolean {
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
        if (!enabled()) return
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        if (!installMenuClickHook(param.thisObject)) return
        val target = resolveTarget(param.thisObject) ?: return
        menu.removeItem(MENU_ITEM_ID)
        val groupId = runCatching { menu.getItem(0).groupId }.getOrDefault(0)
        val item = menu.add(groupId, MENU_ITEM_ID, 0, MENU_TITLE)
        moveMenuItemToFront(menu, item)
        bindings[item] = target
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
                main.post { showLabelActions(target) }
            }
        })
    }

    private fun resolveTarget(listener: Any?): MenuTarget? {
        listener ?: return null
        val fields = KavaReflector.declaredFields(listener.javaClass)
        val activity = fields.asSequence()
            .filter { Activity::class.java.isAssignableFrom(it.type) }
            .mapNotNull { KavaReflector.readField(it, listener) as? Activity }
            .firstOrNull() ?: return null
        val talker = fields.asSequence()
            .filter { it.type == String::class.java }
            .mapNotNull { KavaReflector.readField(it, listener) as? String }
            .map { it.trim() }
            .firstOrNull(::isGroup)
            ?: return null
        return MenuTarget(activity, talker)
    }

    private fun showLabelActions(target: MenuTarget) {
        if (!canShow(target.activity) || !enabled() || !isGroup(target.talker)) return
        val labels = GroupChatLabelStore.load(target.activity)
        if (labels.isEmpty()) {
            showCreateLabelInput(target)
            return
        }
        val currentNames = labels.filter { target.talker in it.groupIds }.map { it.name }
        val choices = buildList {
            add(
                "设置已有标签" to currentNames.joinToString("、")
                    .ifBlank { "当前未加入任何标签" }
            )
            add("新建并添加标签" to "创建新的群聊标签并加入当前群聊")
            if (currentNames.isNotEmpty()) {
                add("移出全部标签" to "保留标签，只移除当前群聊")
            }
        }
        VoiceForwardMiuixDialog.showChoices(
            activity = target.activity,
            title = MENU_TITLE,
            summary = groupName(target.talker),
            choices = choices,
            onSelected = { index ->
                when (index) {
                    0 -> showExistingLabelPicker(target)
                    1 -> showCreateLabelInput(target)
                    2 -> updateGroupLabels(target, emptySet())
                }
            },
            onDismiss = {}
        )
    }

    private fun showExistingLabelPicker(target: MenuTarget) {
        if (!canShow(target.activity)) return
        val labels = GroupChatLabelStore.load(target.activity)
        if (labels.isEmpty()) {
            showCreateLabelInput(target)
            return
        }
        val initialSelected = labels.indices.filter { target.talker in labels[it].groupIds }.toSet()
        VoiceForwardMiuixDialog.showMultiChoices(
            activity = target.activity,
            title = "设置已有标签",
            summary = "取消全部勾选可将当前群聊移出所有标签",
            choices = labels.map { label ->
                label.name to "${label.groupIds.size} 个群聊"
            },
            initialSelected = initialSelected,
            allowEmpty = true,
            onConfirm = { selected ->
                updateGroupLabels(
                    target,
                    selected.mapNotNull { labels.getOrNull(it)?.id }.toSet()
                )
            },
            onDismiss = {}
        )
    }

    private fun showCreateLabelInput(target: MenuTarget) {
        if (!canShow(target.activity)) return
        VoiceForwardMiuixDialog.showTextInput(
            activity = target.activity,
            title = "新建群聊标签",
            summary = "创建后会立即加入当前群聊",
            placeholder = "输入标签名称",
            maxLength = 32,
            allowEmpty = false,
            onConfirm = { name -> createAndAssignLabel(target, name) },
            onDismiss = {}
        )
    }

    private fun updateGroupLabels(target: MenuTarget, selectedIds: Set<String>) {
        val labels = GroupChatLabelStore.load(target.activity)
        val next = labels.map { label ->
            label.copy(
                groupIds = if (label.id in selectedIds) {
                    label.groupIds + target.talker
                } else {
                    label.groupIds - target.talker
                }
            )
        }
        if (next != labels) {
            GroupChatLabelStore.save(target.activity, next)
            ConversationGroupRuntime.syncAsync(target.activity)
        }
        toast(target.activity, "群聊标签已更新")
    }

    private fun createAndAssignLabel(target: MenuTarget, rawName: String) {
        val name = rawName.trim()
        if (name.isEmpty()) return
        val labels = GroupChatLabelStore.load(target.activity)
        val existing = labels.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val next = if (existing != null) {
            labels.map { label ->
                if (label.id == existing.id) {
                    label.copy(groupIds = label.groupIds + target.talker)
                } else {
                    label
                }
            }
        } else {
            labels + GroupChatLabelStore.newLabel().copy(
                name = name,
                groupIds = setOf(target.talker)
            )
        }
        GroupChatLabelStore.save(target.activity, next)
        ConversationGroupRuntime.syncAsync(target.activity)
        toast(
            target.activity,
            if (existing == null) "群聊标签已创建" else "已加入现有群聊标签"
        )
    }

    private fun groupName(talker: String): String {
        return runCatching { WeChatApis.contacts()?.getContact(talker)?.displayName() }
            .getOrNull()
            .orEmpty()
            .ifBlank { talker }
    }

    private fun isGroup(talker: String): Boolean {
        val wxId = talker.trim().takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            val contacts = WeChatApis.contacts() ?: return@runCatching false
            contacts.isGroup(wxId) && contacts.getContact(wxId)?.isGroup() == true
        }.getOrDefault(false)
    }

    private fun enabled(): Boolean {
        return prefs.getBoolean(
            QuickGroupChatLabelSettings.KEY_ENABLE,
            QuickGroupChatLabelSettings.DEFAULT_ENABLE
        )
    }

    private fun canShow(activity: Activity): Boolean {
        return !activity.isFinishing && !activity.isDestroyed
    }

    private fun toast(activity: Activity, text: String) {
        if (canShow(activity)) Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
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
                    runCatching { items.add(0, items.removeAt(index)) }
                }
                if (index >= 0) return
            }
            current = current.superclass
        }
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("安装快捷设置群聊标签菜单 Hook 失败: ${method.toGenericString()}", it)
            false
        }
    }

    companion object {
        private const val MENU_ITEM_ID = 0x4843514C
        private const val MENU_TITLE = "群聊标签"
    }
}
