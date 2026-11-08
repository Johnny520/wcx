package h.Hchat.hooks.items.quickcontactedit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.ContactLabelBean
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.ConversationMenuExtension
import h.Hchat.hooks.core.ConversationMenuExtensionRegistry
import h.Hchat.hooks.core.ConversationMenuExtensionTarget
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class QuickContactEditFeature : BaseFeature() {
    private var runtime: QuickContactEditRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "快捷设置备注和标签"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuickContactEditSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = QuickContactEditRuntime(context, ::logFeatureError)
        if (runtime?.installSnsAvatarHook() != true) {
            logFeatureError("好友朋友圈头像长按 Hook 未安装", null)
        }
        scheduleDexInstall()
        subscribe(Events.DexReady::class.java) { scheduleDexInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleDexInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            runtime?.installConversationMenuHook() == true
        }
        DexInstallScheduler.schedule("$ID:sns_avatar_menu", "${name()}朋友圈头像菜单") {
            runtime?.installSnsAvatarMenuHook() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "quick_contact_edit"
    }
}

private class QuickContactEditRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class MenuTarget(
        val activity: Activity,
        val talker: String,
        val allowOpenIm: Boolean = false,
        val isGroup: Boolean = false
    )
    private data class UpdateResult(val success: Boolean, val message: String)

    private val prefs = HchatStorage.preferences(context.hostContext(), QuickContactEditSettings.PREFS_NAME)
    private val main = Handler(Looper.getMainLooper())
    private val hookedMethods = ConcurrentHashMap.newKeySet<Method>()
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, MenuTarget>())
    private val snsMenuBindings = Collections.synchronizedMap(WeakHashMap<MenuItem, MenuTarget>())
    @Volatile
    private var pendingSnsMenuTarget: MenuTarget? = null
    private val avatarBindings = Collections.synchronizedMap(WeakHashMap<View, AvatarBinding>())
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == QuickContactEditSettings.KEY_ENABLE && !enabled()) {
            main.post(::restoreAvatarBindings)
        }
    }
    private val conversationMenuExtension = object : ConversationMenuExtension {
        override val itemId: Int = MENU_ITEM_ID
        override val order: Int = 100

        override fun title(target: ConversationMenuExtensionTarget): String {
            return if (conversationTarget(target.activity, target.talker)?.isGroup == true) {
                GROUP_MENU_TITLE
            } else {
                MENU_TITLE
            }
        }

        override fun isVisible(target: ConversationMenuExtensionTarget): Boolean {
            return enabled() && conversationTarget(target.activity, target.talker) != null
        }

        override fun onClick(target: ConversationMenuExtensionTarget) {
            conversationTarget(target.activity, target.talker)?.let(::showActions)
        }
    }

    private data class AvatarBinding(
        var activity: WeakReference<Activity>,
        var talker: String,
        val originalListener: View.OnLongClickListener?,
        val originalLongClickable: Boolean
    )

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        ConversationMenuExtensionRegistry.register(conversationMenuExtension)
    }

    @Synchronized
    fun installConversationMenuHook(): Boolean {
        val create = ConversationMenuLocator.menuCreateMethod(context, logger)
        val createInstalled = create != null && hook(create, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                addMenuItem(param)
            }
        })
        if (!createInstalled) logger("快捷设置备注和标签菜单创建 Hook 未安装", null)
        return createInstalled
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        ConversationMenuExtensionRegistry.unregister(conversationMenuExtension)
        bindings.clear()
        snsMenuBindings.clear()
        pendingSnsMenuTarget = null
        restoreAvatarBindings()
    }

    @Synchronized
    fun installSnsAvatarMenuHook(): Boolean {
        val methods = SnsAvatarMenuLocator.locate(context, logger)
        val createInstalled = methods.createMethods.count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addSnsAvatarMenuItem(param)
                }
            })
        }
        val clickInstalled = methods.clickMethods.count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleSnsAvatarMenuClick(param, method)
                }
            })
        }
        if (createInstalled <= 0) logger("朋友圈头像菜单创建 Hook 未安装", null)
        if (clickInstalled <= 0) logger("朋友圈头像菜单点击 Hook 未安装", null)
        return createInstalled > 0 && clickInstalled > 0
    }

    private fun addSnsAvatarMenuItem(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        val view = param.args?.getOrNull(1) as? View ?: return
        val activity = findActivity(view.context) ?: return
        val talker = snsAuthorFromCallback(param.thisObject) ?: return
        menu.removeItem(SNS_MENU_ITEM_ID)
        snsMenuBindings.clear()
        pendingSnsMenuTarget = null
        val target = MenuTarget(activity, talker, allowOpenIm = true)
        val item = menu.add(0, SNS_MENU_ITEM_ID, menu.size(), MENU_TITLE)
        snsMenuBindings[item] = target
        pendingSnsMenuTarget = target
    }

    private fun handleSnsAvatarMenuClick(param: XC_MethodHook.MethodHookParam, method: Method) {
        if (!enabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != SNS_MENU_ITEM_ID) return
        val target = snsMenuBindings.remove(item) ?: pendingSnsMenuTarget ?: return
        pendingSnsMenuTarget = null
        param.result = if (method.returnType == Boolean::class.javaPrimitiveType) true else null
        main.post { showActions(target) }
    }

    private fun snsAuthorFromCallback(callback: Any?): String? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return findSnsAuthor(callback, visited, 0)?.takeIf(::isEditableSnsContact)
    }

    private fun findSnsAuthor(value: Any?, visited: MutableSet<Any>, depth: Int): String? {
        if (value == null || depth > SNS_CALLBACK_SCAN_DEPTH || !visited.add(value)) return null
        snsUserName(value)?.let { return it }
        if (depth == SNS_CALLBACK_SCAN_DEPTH) return null
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                val nested = KavaReflector.readField(field, value) ?: continue
                if (!shouldScanSnsCallbackObject(nested)) continue
                findSnsAuthor(nested, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun snsUserName(value: Any): String? {
        if (value.javaClass.name == SNS_INFO_CLASS) {
            return (KavaReflector.invokeMethod(value, "getUserName")
                ?: KavaReflector.readField(value, "field_userName"))
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        val getter = KavaReflector.findMethodRecursive(value.javaClass, "getUserName")
            ?.takeIf { method ->
                method.parameterTypes.isEmpty() && method.returnType == String::class.java
            }
            ?: return null
        return (KavaReflector.invoke(getter, value) as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun shouldScanSnsCallbackObject(value: Any): Boolean {
        val name = value.javaClass.name
        return value !is String &&
            !name.startsWith("android.") &&
            !name.startsWith("androidx.") &&
            !name.startsWith("java.") &&
            !name.startsWith("kotlin.") &&
            !name.startsWith("kotlinx.")
    }

    private fun restoreAvatarBindings() {
        val snapshot = synchronized(avatarBindings) {
            avatarBindings.entries.map { it.key to it.value }.also { avatarBindings.clear() }
        }
        snapshot.forEach { (view, binding) ->
            runCatching {
                view.setOnLongClickListener(binding.originalListener)
                view.isLongClickable = binding.originalLongClickable
            }
        }
    }

    private fun restoreAvatarBinding(view: View): View.OnLongClickListener? {
        val binding = synchronized(avatarBindings) { avatarBindings.remove(view) } ?: return null
        runCatching {
            view.setOnLongClickListener(binding.originalListener)
            view.isLongClickable = binding.originalLongClickable
        }
        return binding.originalListener
    }

    @Synchronized
    fun installSnsAvatarHook(): Boolean {
        val headerClass = KavaReflector.loadClass(SNS_HEADER_CLASS, context.hostClassLoader())
            ?: return false
        val method = KavaReflector.findMethodRecursive(
            headerClass,
            "setAvatarOnClickListener",
            View.OnClickListener::class.java
        ) ?: return false
        return hook(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                bindSnsAvatar(param.thisObject, param.args?.getOrNull(0) as? View.OnClickListener)
            }
        })
    }

    private fun bindSnsAvatar(header: Any?, clickListener: View.OnClickListener?) {
        if (header == null || clickListener == null) return
        val holder = KavaReflector.invokeMethod(header, "getViewHeader") ?: return
        val avatar = KavaReflector.declaredFields(holder.javaClass)
            .asSequence()
            .filter { ImageView::class.java.isAssignableFrom(it.type) }
            .mapNotNull { KavaReflector.readField(it, holder) as? ImageView }
            .firstOrNull { currentClickListener(it) === clickListener }
            ?: return
        val activity = findActivity(avatar.context) ?: return
        if (activity.javaClass.name != SNS_USER_UI_CLASS) return
        val talker = activity.intent?.getStringExtra(SNS_USERNAME_EXTRA)?.trim().orEmpty()
        if (!isEditableSnsContact(talker)) return
        if (!enabled()) {
            restoreAvatarBinding(avatar)
            return
        }
        synchronized(avatarBindings) {
            avatarBindings[avatar]?.let { existing ->
                existing.activity = WeakReference(activity)
                existing.talker = talker
                return
            }
            val original = currentLongClickListener(avatar)
            avatarBindings[avatar] = AvatarBinding(
                activity = WeakReference(activity),
                talker = talker,
                originalListener = original,
                originalLongClickable = avatar.isLongClickable
            )
            avatar.setOnLongClickListener { view ->
                val binding = synchronized(avatarBindings) { avatarBindings[view] }
                    ?: return@setOnLongClickListener false
                if (!enabled()) {
                    return@setOnLongClickListener restoreAvatarBinding(view)?.onLongClick(view) == true
                }
                val currentActivity = binding.activity.get()
                    ?.takeUnless { it.isFinishing || it.isDestroyed }
                    ?: return@setOnLongClickListener false
                if (!isEditableSnsContact(binding.talker)) {
                    return@setOnLongClickListener binding.originalListener?.onLongClick(view) == true
                }
                main.post {
                    showActions(MenuTarget(currentActivity, binding.talker, allowOpenIm = true))
                }
                true
            }
        }
    }

    private fun currentClickListener(view: View): View.OnClickListener? {
        return listenerInfo(view)?.let { KavaReflector.readField(it, "mOnClickListener") }
            as? View.OnClickListener
    }

    private fun currentLongClickListener(view: View): View.OnLongClickListener? {
        return listenerInfo(view)?.let { KavaReflector.readField(it, "mOnLongClickListener") }
            as? View.OnLongClickListener
    }

    private fun listenerInfo(view: View): Any? {
        return KavaReflector.readField(view, "mListenerInfo")
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

    private fun isEditableFriend(talker: String): Boolean {
        if (talker.isBlank()) return false
        return runCatching { WeChatApis.contacts()?.isFriend(talker) == true }.getOrDefault(false)
    }

    private fun isEditableSnsContact(talker: String): Boolean {
        if (isEditableFriend(talker)) return true
        if (!talker.endsWith(OPEN_IM_SUFFIX, ignoreCase = true)) return false
        if (WeChatApis.users()?.isSelf(talker) == true) return false
        return runCatching {
            val contact = WeChatApis.contacts()?.getContact(talker) ?: return@runCatching false
            !contact.isGroup() && !contact.isOfficialAccount()
        }.getOrDefault(false)
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("安装快捷设置 Hook 失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addMenuItem(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val menu = param.args?.getOrNull(0) as? ContextMenu ?: return
        if (!installMenuClickHook(param.thisObject)) return
        val target = resolveTarget(param.thisObject) ?: return
        menu.removeItem(MENU_ITEM_ID)
        val groupId = runCatching { menu.getItem(0).groupId }.getOrDefault(0)
        val title = if (target.isGroup) GROUP_MENU_TITLE else MENU_TITLE
        val item = menu.add(groupId, MENU_ITEM_ID, 0, title)
        moveMenuItemToFront(menu, item, MENU_ITEM_ID)
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
                handleMenuClick(param)
            }
        })
    }

    private fun handleMenuClick(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val item = param.args?.getOrNull(0) as? MenuItem ?: return
        if (item.itemId != MENU_ITEM_ID) return
        val target = bindings.remove(item) ?: return
        param.result = null
        main.post { showActions(target) }
    }

    private fun moveMenuItemToFront(menu: ContextMenu, item: MenuItem, targetItemId: Int) {
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                val index = items.indexOfFirst { candidate ->
                    candidate === item || (candidate as? MenuItem)?.itemId == targetItemId
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

    private fun resolveTarget(listener: Any?): MenuTarget? {
        listener ?: return null
        val fields = KavaReflector.declaredFields(listener.javaClass)
        val activity = fields.asSequence()
            .filter { Activity::class.java.isAssignableFrom(it.type) }
            .mapNotNull { KavaReflector.readField(it, listener) as? Activity }
            .firstOrNull() ?: return null
        val contacts = WeChatApis.contacts() ?: return null
        val talker = fields.asSequence()
            .filter { it.type == String::class.java }
            .mapNotNull { KavaReflector.readField(it, listener) as? String }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { value ->
                runCatching {
                    contacts.isFriend(value) ||
                        (contacts.isGroup(value) && contacts.getContact(value)?.isGroup() == true)
                }.getOrDefault(false)
            } ?: return null
        return MenuTarget(activity, talker, isGroup = contacts.isGroup(talker))
    }

    private fun conversationTarget(activity: Activity, talker: String): MenuTarget? {
        val wxId = talker.trim().takeIf { it.isNotEmpty() } ?: return null
        val contacts = WeChatApis.contacts() ?: return null
        return runCatching {
            val isGroup = contacts.isGroup(wxId) && contacts.getContact(wxId)?.isGroup() == true
            if (!isGroup && !contacts.isFriend(wxId)) return@runCatching null
            MenuTarget(activity, wxId, isGroup = isGroup)
        }.getOrNull()
    }

    private fun showActions(target: MenuTarget) {
        val activity = target.activity
        if (!canShow(activity)) return
        val contacts = WeChatApis.contacts() ?: return
        val contact = runCatching { contacts.getContact(target.talker) }
            .getOrNull() ?: return
        if (target.isGroup) {
            if (!contacts.isGroup(target.talker) || !contact.isGroup()) return
            openChatroomRemark(target)
            return
        }
        if (!contacts.isFriend(target.talker) &&
            !(target.allowOpenIm && isEditableSnsContact(target.talker))
        ) return
        val remark = contact.remarkName.ifBlank { "未设置" }
        VoiceForwardMiuixDialog.showChoices(
            activity = activity,
            title = MENU_TITLE,
            summary = contact.displayName(),
            choices = listOf(
                "修改备注" to "当前备注：$remark",
                "设置好友标签" to "勾选、取消或清空已有标签",
                "新建并添加标签" to "创建微信好友标签并添加给该好友"
            ),
            onSelected = { index ->
                when (index) {
                    0 -> showRemarkInput(target, contact.remarkName)
                    1 -> loadLabelPicker(target)
                    2 -> showNewLabelInput(target)
                }
            },
            onDismiss = {}
        )
    }

    private fun openChatroomRemark(target: MenuTarget) {
        val activity = target.activity
        if (!canShow(activity)) return
        val intent = Intent().apply {
            setClassName(activity, CHATROOM_REMARK_ACTIVITY)
            putExtra(CHATROOM_REMARK_SCENE_EXTRA, CHATROOM_REMARK_SCENE)
            putExtra(CHATROOM_REMARK_ROOM_EXTRA, target.talker)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure {
                logger("打开微信原生群聊备注页失败: talker=${target.talker}", it)
                toast(activity, "当前微信版本无法打开群聊备注")
            }
    }

    private fun showRemarkInput(target: MenuTarget, currentRemark: String) {
        if (!canShow(target.activity)) return
        VoiceForwardMiuixDialog.showTextInput(
            activity = target.activity,
            title = "修改好友备注",
            summary = "留空并确定可清除备注",
            initialValue = currentRemark,
            placeholder = "输入好友备注",
            maxLength = 100,
            allowEmpty = true,
            onConfirm = { remark ->
                performUpdate(target.activity, "正在修改好友备注...") {
                    val success = WeChatApis.contacts()?.modifyContactRemark(target.talker, remark) == true
                    UpdateResult(success, if (success) "好友备注已更新" else "修改好友备注失败")
                }
            },
            onDismiss = {}
        )
    }

    private fun loadLabelPicker(target: MenuTarget) {
        val activity = target.activity
        if (!canShow(activity)) return
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            onDismiss = {},
            title = "设置好友标签",
            message = "正在载入好友标签..."
        )
        Thread({
            val result = runCatching {
                val api = WeChatApis.contacts() ?: error("联系人标签不可用")
                api.getContactLabelList()
                    .filter { it.labelName.isNotBlank() || it.labelId.isNotBlank() }
                    .distinctBy { it.labelName.ifBlank { it.labelId } }
                    .sortedBy { it.labelName.ifBlank { it.labelId }.lowercase(Locale.US) }
            }
            main.post {
                loading.close()
                postAfterOverlay(activity) {
                    val labels = result.getOrElse {
                        logger("载入好友标签失败", it)
                        toast(activity, "载入好友标签失败")
                        return@postAfterOverlay
                    }
                    if (labels.isEmpty()) {
                        toast(activity, "暂无好友标签，请先新建标签")
                        return@postAfterOverlay
                    }
                    showLabelPicker(target, labels)
                }
            }
        }, "Hchat-QuickContactLabels").start()
    }

    private fun showLabelPicker(target: MenuTarget, labels: List<ContactLabelBean>) {
        if (!canShow(target.activity)) return
        val initial = labels.indices.filter { target.talker in labels[it].userNameList }.toSet()
        VoiceForwardMiuixDialog.showMultiChoices(
            activity = target.activity,
            title = "设置好友标签",
            summary = "取消全部勾选可清空该好友的标签",
            choices = labels.map { label ->
                label.labelName.ifBlank { label.labelId } to "${label.userNameList.size} 位好友"
            },
            initialSelected = initial,
            allowEmpty = true,
            onConfirm = { selected ->
                val names = selected.mapNotNull { index ->
                    labels.getOrNull(index)?.labelName?.trim()?.takeIf { it.isNotEmpty() }
                }
                performUpdate(target.activity, "正在更新好友标签...") {
                    val success = WeChatApis.contacts()?.replaceContactLabelList(target.talker, names) == true
                    UpdateResult(success, if (success) "好友标签已更新" else "更新好友标签失败")
                }
            },
            onDismiss = {}
        )
    }

    private fun showNewLabelInput(target: MenuTarget) {
        if (!canShow(target.activity)) return
        VoiceForwardMiuixDialog.showTextInput(
            activity = target.activity,
            title = "新建并添加标签",
            summary = "标签创建后会自动添加给当前好友",
            placeholder = "输入标签名称",
            maxLength = 32,
            allowEmpty = false,
            onConfirm = { labelName ->
                performUpdate(target.activity, "正在创建并添加标签...") {
                    createAndAssignLabel(target.talker, labelName)
                }
            },
            onDismiss = {}
        )
    }

    private fun createAndAssignLabel(talker: String, labelName: String): UpdateResult {
        val api = WeChatApis.contacts()
            ?: return UpdateResult(false, "联系人标签不可用")
        var labels = runCatching { api.getContactLabelList() }.getOrDefault(emptyList())
        if (labels.none { it.labelName == labelName }) {
            if (api.addContactLabel(labelName).isBlank()) {
                return UpdateResult(false, "创建好友标签失败")
            }
            var attempts = 0
            while (attempts < LABEL_SYNC_ATTEMPTS && labels.none { it.labelName == labelName }) {
                attempts++
                runCatching { Thread.sleep(LABEL_SYNC_INTERVAL_MS) }
                labels = runCatching { api.getContactLabelList() }.getOrDefault(emptyList())
            }
        }
        if (labels.none { it.labelName == labelName }) {
            return UpdateResult(false, "标签尚未同步，请稍后重试")
        }
        val success = runCatching { api.modifyContactLabelList(talker, labelName) }.getOrDefault(false)
        return UpdateResult(success, if (success) "标签已创建并添加" else "添加好友标签失败")
    }

    private fun performUpdate(activity: Activity, message: String, operation: () -> UpdateResult) {
        if (!canShow(activity)) return
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            onDismiss = {},
            title = MENU_TITLE,
            message = message
        )
        Thread({
            val result = runCatching(operation).getOrElse {
                logger("更新好友资料失败", it)
                UpdateResult(false, "更新好友资料失败")
            }
            main.post {
                loading.close()
                postAfterOverlay(activity) { toast(activity, result.message) }
            }
        }, "Hchat-QuickContactUpdate").start()
    }

    private fun postAfterOverlay(activity: Activity, action: () -> Unit) {
        val decor = activity.window?.decorView ?: return
        decor.postOnAnimation {
            if (canShow(activity)) action()
        }
    }

    private fun toast(activity: Activity, text: String) {
        if (!canShow(activity)) return
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    private fun enabled(): Boolean {
        return prefs.getBoolean(QuickContactEditSettings.KEY_ENABLE, QuickContactEditSettings.DEFAULT_ENABLE)
    }

    private fun canShow(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed

    companion object {
        private const val MENU_ITEM_ID = 0x4843524D
        private const val SNS_MENU_ITEM_ID = 0x48435253
        private const val MENU_TITLE = "设置备注和标签"
        private const val GROUP_MENU_TITLE = "设置群聊备注"
        private const val CHATROOM_REMARK_ACTIVITY = "com.tencent.mm.chatroom.ui.ModRemarkRoomNameUI"
        private const val CHATROOM_REMARK_SCENE_EXTRA = "Key_Scenen"
        private const val CHATROOM_REMARK_ROOM_EXTRA = "Key_Room_Id"
        private const val CHATROOM_REMARK_SCENE = 2
        private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"
        private const val SNS_HEADER_CLASS = "com.tencent.mm.plugin.sns.ui.SnsHeader"
        private const val SNS_USER_UI_CLASS = "com.tencent.mm.plugin.sns.ui.SnsUserUI"
        private const val SNS_USERNAME_EXTRA = "sns_userName"
        private const val OPEN_IM_SUFFIX = "@openim"
        private const val SNS_CALLBACK_SCAN_DEPTH = 3
        private const val LABEL_SYNC_ATTEMPTS = 15
        private const val LABEL_SYNC_INTERVAL_MS = 1_000L
    }
}
