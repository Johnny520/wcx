package h.Hchat.hooks.items.script

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.SettingsDexFinder
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.SingleMessageMenuLocator
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared menu dispatcher for script plugins.
 *
 * Host menu hooks are installed once per process. Entries are owned by the
 * plugin id, so reloading one plugin cannot leave another plugin's callback or
 * menu item behind.
 */
object ScriptMenuDispatcher {
    private const val PLUS_ITEM_ID_START = 0x48485000
    private const val MESSAGE_ITEM_ID_START = 0x48486000
    private const val CLICK_DEDUP_MS = 1_500L

    private val sequence = AtomicLong(1L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val plusItemIds = AtomicInteger(PLUS_ITEM_ID_START)
    private val messageItemIds = AtomicInteger(MESSAGE_ITEM_ID_START)
    private val knownPlusItemIds = ConcurrentHashMap.newKeySet<Int>()
    private val plusEntries = ConcurrentHashMap<String, PlusEntry>()
    private val messageEntries = ConcurrentHashMap<String, MessageEntry>()
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val plusAdapterViewHookedClasses = Collections.newSetFromMap(
        ConcurrentHashMap<Class<*>, Boolean>()
    )
    private val plusHelpers = Collections.synchronizedMap(
        WeakHashMap<Any, WeakReference<BaseAdapter>>()
    )
    private val messageBindings = Collections.synchronizedMap(
        WeakHashMap<MenuItem, MessageMenuTarget>()
    )
    private val messageBindingsById = ConcurrentHashMap<Int, MessageMenuTarget>()
    @Volatile
    private var lastPlusClickId = Int.MIN_VALUE
    @Volatile
    private var lastPlusClickAt = 0L
    @Volatile
    private var lastMessageClickId = Int.MIN_VALUE
    @Volatile
    private var lastMessageClickAt = 0L
    @Volatile
    private var settingsDexFinder: SettingsDexFinder? = null
    @Volatile
    private var logger: ((String, Throwable?) -> Unit)? = null

    data class ScriptMenuHandle internal constructor(
        private val token: String,
        private val removeAction: (String) -> Unit
    ) {
        private val removed = AtomicBoolean(false)

        fun remove() {
            if (removed.compareAndSet(false, true)) removeAction(token)
        }

        fun unregister() = remove()

        fun isRegistered(): Boolean = !removed.get() && ScriptMenuDispatcher.isRegisteredToken(token)
    }

    private data class PlusEntry(
        val token: String,
        val owner: String,
        val itemId: Int,
        val title: String,
        val iconPath: String,
        val pluginDir: File?,
        val front: Boolean,
        val sequence: Long,
        val onClick: (Any?) -> Unit
    )

    private data class MessageEntry(
        val token: String,
        val owner: String,
        val itemId: Int,
        val title: String,
        val iconPath: String,
        val pluginDir: File?,
        val front: Boolean,
        val sequence: Long,
        val onClick: (ScriptMessageBean) -> Unit
    )

    private data class MessageMenuTarget(
        val message: ScriptMessageBean
    )

    @Synchronized
    fun install(
        context: FeatureContext,
        finder: SettingsDexFinder,
        log: (String, Throwable?) -> Unit
    ): Boolean {
        settingsDexFinder = finder
        logger = log
        val plusInstalled = installPlusMenuHooks(finder)
        val messageInstalled = installMessageMenuHooks(context, log)
        if (!plusInstalled && plusEntries.isNotEmpty()) {
            log("脚本右上角加号菜单Hook未安装", null)
        }
        if (!messageInstalled && messageEntries.isNotEmpty()) {
            log("脚本长按消息菜单Hook未安装", null)
        }
        refreshPlusMenus()
        return plusInstalled && messageInstalled
    }

    fun registerPlusMenu(
        owner: String?,
        pluginDir: File?,
        title: String?,
        iconPath: String?,
        front: Boolean,
        onClick: (Any?) -> Unit
    ): ScriptMenuHandle? {
        val cleanOwner = owner?.trim().orEmpty()
        val cleanTitle = title?.trim().orEmpty()
        if (cleanOwner.isBlank() || cleanTitle.isBlank()) return null
        val order = sequence.getAndIncrement()
        val itemId = nextItemId(plusItemIds, PLUS_ITEM_ID_START)
        knownPlusItemIds += itemId
        val token = "$cleanOwner:plus:$order"
        plusEntries[token] = PlusEntry(
            token = token,
            owner = cleanOwner,
            itemId = itemId,
            title = cleanTitle,
            iconPath = iconPath?.trim().orEmpty(),
            pluginDir = pluginDir,
            front = front,
            sequence = order,
            onClick = onClick
        )
        refreshPlusMenus()
        return ScriptMenuHandle(token, ::unregister)
    }

    fun registerMessageMenu(
        owner: String?,
        pluginDir: File?,
        title: String?,
        iconPath: String?,
        front: Boolean,
        onClick: (ScriptMessageBean) -> Unit
    ): ScriptMenuHandle? {
        val cleanOwner = owner?.trim().orEmpty()
        val cleanTitle = title?.trim().orEmpty()
        if (cleanOwner.isBlank() || cleanTitle.isBlank()) return null
        val order = sequence.getAndIncrement()
        val token = "$cleanOwner:message:$order"
        messageEntries[token] = MessageEntry(
            token = token,
            owner = cleanOwner,
            itemId = nextItemId(messageItemIds, MESSAGE_ITEM_ID_START),
            title = cleanTitle,
            iconPath = iconPath?.trim().orEmpty(),
            pluginDir = pluginDir,
            front = front,
            sequence = order,
            onClick = onClick
        )
        return ScriptMenuHandle(token, ::unregister)
    }

    fun unregisterOwner(owner: String?) {
        val cleanOwner = owner?.trim().orEmpty()
        if (cleanOwner.isBlank()) return
        plusEntries.entries.removeIf { it.value.owner == cleanOwner }
        messageEntries.entries.removeIf { it.value.owner == cleanOwner }
        clearMessageBindings()
        refreshPlusMenus()
    }

    fun remove(handle: Any?) {
        (handle as? ScriptMenuHandle)?.remove()
    }

    private fun unregister(token: String) {
        plusEntries.remove(token)
        messageEntries.remove(token)
        clearMessageBindings()
        refreshPlusMenus()
    }

    private fun isRegisteredToken(token: String): Boolean {
        return plusEntries.containsKey(token) || messageEntries.containsKey(token)
    }

    private fun nextItemId(counter: AtomicInteger, start: Int): Int {
        while (true) {
            val id = counter.getAndIncrement()
            if (id > 0 && id < Int.MAX_VALUE &&
                knownPlusItemIds.contains(id).not() &&
                plusEntries.values.none { it.itemId == id } &&
                messageEntries.values.none { it.itemId == id }
            ) {
                return id
            }
            if (counter.get() <= 0 || counter.get() >= Int.MAX_VALUE - 1) {
                counter.set(start)
            }
        }
    }

    private fun installPlusMenuHooks(finder: SettingsDexFinder): Boolean {
        val helperClass = finder.plusSubMenuHelperClass ?: return false
        val adapterInstalled = finder.plusSubMenuAdapterMethod?.let { method ->
            hookOnce(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching {
                        appendPlusMenuItems(param.thisObject, param.result as? BaseAdapter)
                    }.onFailure { logError("脚本加号菜单添加失败", it) }
                }
            })
        } ?: false
        val clickInstalled = finder.plusSubMenuOnItemClickMethod?.let { method ->
            hookOnce(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handlePlusMenuClick(param)
                }
            })
        } ?: false
        findPlusMenuPopulateMethods(helperClass).forEach { method ->
            hookOnce(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    appendPlusMenuItems(param.thisObject, null)
                }
            })
        }
        findPlusMenuShowMethods(helperClass).forEach { method ->
            hookOnce(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    appendPlusMenuItems(param.thisObject, null)
                }
            })
        }
        return adapterInstalled && clickInstalled
    }

    private fun installMessageMenuHooks(
        context: FeatureContext,
        log: (String, Throwable?) -> Unit
    ): Boolean {
        var createHooked = false
        var clickHooked = false
        SingleMessageMenuLocator.menuCreateMethods(context, log).forEach { method ->
            createHooked = hookOnce(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addMessageMenus(param)
                }
            }) || createHooked
        }
        SingleMessageMenuLocator.menuClickMethods(context, log).forEach { method ->
            clickHooked = hookOnce(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleMessageMenuClick(param)
                }
            }) || clickHooked
        }
        return createHooked && clickHooked
    }

    private fun hookOnce(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logError("脚本菜单Hook安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun appendPlusMenuItems(helper: Any?, adapter: BaseAdapter?) {
        if (helper == null) return
        val items = findSparseArrayFieldValue(helper) ?: return
        val baseAdapter = adapter ?: findBaseAdapterFieldValue(helper)
        if (baseAdapter != null) {
            plusHelpers[helper] = WeakReference(baseAdapter)
            ensurePlusMenuAdapterViewHook(baseAdapter)
        }

        val nativeWrappers = ArrayList<Any>(items.size())
        val existingPluginWrappers = HashMap<Int, Any>()
        for (index in 0 until items.size()) {
            val wrapper = items.valueAt(index) ?: continue
            val wrapperItemId = itemId(wrapper)
            if (knownPlusItemIds.contains(wrapperItemId)) {
                existingPluginWrappers[wrapperItemId] = wrapper
            } else {
                nativeWrappers += wrapper
            }
        }

        val active = plusEntries.values.sortedWith(
            compareBy<PlusEntry> { if (it.front) 0 else 1 }
                .thenBy { it.sequence }
                .thenBy { it.itemId }
        )
        val sampleWrapper = nativeWrappers.firstOrNull()
            ?: existingPluginWrappers.values.firstOrNull()
        val sampleItem = findNestedItemObject(sampleWrapper)
        val resolveWrapper: (PlusEntry) -> Any? = { entry ->
            existingPluginWrappers[entry.itemId]
                ?: if (sampleWrapper != null && sampleItem != null) {
                    createPlusMenuWrapper(sampleWrapper, sampleItem, entry)
                } else {
                    null
                }
        }
        val frontWrappers = active.filter { it.front }.mapNotNull(resolveWrapper)
        val tailWrappers = active.filterNot { it.front }.mapNotNull(resolveWrapper)

        val ordered = ArrayList<Any>(
            nativeWrappers.size + frontWrappers.size + tailWrappers.size
        ).apply {
            addAll(frontWrappers)
            addAll(nativeWrappers)
            addAll(tailWrappers)
        }
        if (sparseArrayMatchesPositionOrder(items, ordered)) return

        items.clear()
        ordered.forEachIndexed { position, wrapper -> items.put(position, wrapper) }
        check(sparseArrayMatchesPositionOrder(items, ordered)) {
            "脚本加号菜单位置重建失败"
        }
        baseAdapter?.notifyDataSetChanged()
    }

    private fun refreshPlusMenus() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::refreshPlusMenus)
            return
        }
        val snapshots = synchronized(plusHelpers) {
            plusHelpers.entries.mapNotNull { (helper, adapterRef) ->
                adapterRef.get()?.let { helper to it }
            }
        }
        snapshots.forEach { (helper, adapter) ->
            runCatching { appendPlusMenuItems(helper, adapter) }
                .onFailure { logError("脚本加号菜单刷新失败", it) }
        }
    }

    private fun handlePlusMenuClick(param: XC_MethodHook.MethodHookParam) {
        val position = (param.args?.getOrNull(2) as? Number)?.toInt() ?: return
        val helper = param.thisObject
        val itemId = readPlusMenuItemId(helper, position)
        val entry = plusEntries.values.firstOrNull { it.itemId == itemId } ?: return
        param.result = null
        val now = System.currentTimeMillis()
        if (lastPlusClickId == itemId && now - lastPlusClickAt < CLICK_DEDUP_MS) return
        lastPlusClickId = itemId
        lastPlusClickAt = now
        dismissPlusMenu(helper)
        val activity = resolveActivity(findContextFieldValue(helper))
        runCatching { entry.onClick(activity) }
            .onFailure { logError("脚本加号菜单回调失败: ${entry.title}", it) }
    }

    private fun addMessageMenus(param: XC_MethodHook.MethodHookParam) {
        clearMessageBindings()
        val active = messageEntries.values.sortedWith(
            compareBy<MessageEntry> { if (it.front) 0 else 1 }
                .thenBy { it.sequence }
                .thenBy { it.itemId }
        )
        if (active.isEmpty()) return
        val menu = param.args?.getOrNull(0) ?: return
        val view = param.args?.getOrNull(1) as? View ?: return
        val target = resolveMessageTarget(view, param.args) ?: return
        val frontItems = ArrayList<MenuItem>()
        active.forEach { entry ->
            val item = addMessageMenuItem(menu, view, entry) ?: return@forEach
            messageBindings[item] = target
            messageBindingsById[item.itemId] = target
            if (entry.front) frontItems += item
        }
        moveMessageItemsToFront(menu, frontItems)
    }

    private fun handleMessageMenuClick(param: XC_MethodHook.MethodHookParam) {
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        val entry = messageEntries.values.firstOrNull { it.itemId == item.itemId } ?: return
        param.result = null
        val now = System.currentTimeMillis()
        if (lastMessageClickId == item.itemId && now - lastMessageClickAt < CLICK_DEDUP_MS) return
        lastMessageClickId = item.itemId
        lastMessageClickAt = now
        val target = messageBindings.remove(item)
            ?: messageBindingsById.remove(item.itemId)
        clearMessageBindings()
        if (target == null) {
            logError("脚本长按消息菜单缺少消息绑定: ${entry.title}", null)
            return
        }
        runCatching { entry.onClick(target.message) }
            .onFailure { logError("脚本长按消息菜单回调失败: ${entry.title}", it) }
    }

    private fun resolveMessageTarget(view: View, args: Array<Any?>?): MessageMenuTarget? {
        val native = resolveNativeMessage(view.tag)
            ?: resolveNativeMessage(args)
            ?: return null
        val id = nativeMessageId(native)
        if (id <= 0L) return null
        val message = runCatching { WeChatApis.messageStore()?.getMessageById(id) }.getOrNull()
            ?: return null
        return MessageMenuTarget(ScriptMessageBean(message))
    }

    private fun addMessageMenuItem(menu: Any, view: View, entry: MessageEntry): MenuItem? {
        val icon = loadIcon(view.context, entry.pluginDir, entry.iconPath)
        val existing = findMenuItem(menu, entry.itemId)
        val item = existing ?: run {
            val groupId = readMenuGroupId(menu)
            val added = KavaReflector.invokeMethod(menu, "add", groupId, entry.itemId, 0, entry.title)
                ?: KavaReflector.invokeMethod(menu, "add", groupId, entry.itemId, 0, entry.title as CharSequence)
            (added as? MenuItem) ?: findMenuItem(menu, entry.itemId)
        } ?: return null
        runCatching { item.title = entry.title }
        if (icon != null) runCatching { item.setIcon(icon) }
        return item
    }

    private fun moveMessageItemsToFront(menu: Any, itemsToMove: List<MenuItem>) {
        if (itemsToMove.isEmpty()) return
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                itemsToMove.asReversed().forEach { item ->
                    val index = items.indexOfFirst {
                        it === item || (it as? MenuItem)?.itemId == item.itemId
                    }
                    if (index >= 0) {
                        val moved = items.removeAt(index)
                        items.add(0, moved)
                    }
                }
                return
            }
            current = current.superclass
        }
    }

    private fun ensurePlusMenuAdapterViewHook(adapter: BaseAdapter) {
        val clazz = adapter.javaClass
        if (plusAdapterViewHookedClasses.contains(clazz)) return
        val method = findAdapterGetViewMethod(clazz) ?: return
        val hooked = hookOnce(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val result = param.result as? View ?: return
                val position = (param.args?.getOrNull(0) as? Number)?.toInt() ?: return
                val helper = findPlusMenuHelperFieldValue(param.thisObject) ?: return
                val entryId = readPlusMenuItemId(helper, position)
                val entry = plusEntries.values.firstOrNull { it.itemId == entryId } ?: return
                val icon = loadIcon(result.context, entry.pluginDir, entry.iconPath) ?: return
                findFirstImageView(result)?.apply {
                    visibility = View.VISIBLE
                    setImageTintList(null)
                    setColorFilter(null)
                    setImageDrawable(icon)
                    alpha = 1.0f
                }
            }
        })
        if (hooked) plusAdapterViewHookedClasses.add(clazz)
    }

    private fun findAdapterGetViewMethod(clazz: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "getView" && !Modifier.isStatic(method.modifiers) &&
                    types.size == 3 && types[0] == Integer.TYPE &&
                    View::class.java.isAssignableFrom(types[1]) &&
                    ViewGroup::class.java.isAssignableFrom(types[2]) &&
                    View::class.java.isAssignableFrom(method.returnType)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun findFirstImageView(view: View): android.widget.ImageView? {
        if (view is android.widget.ImageView) return view
        val group = view as? ViewGroup ?: return null
        for (index in 0 until group.childCount) {
            findFirstImageView(group.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun findPlusMenuPopulateMethods(clazz: Class<*>): Set<Method> {
        return KavaReflector.declaredMethods(clazz).filter {
            !Modifier.isStatic(it.modifiers) &&
                it.returnType == Boolean::class.javaPrimitiveType &&
                it.parameterCount == 0
        }.toSet()
    }

    private fun findPlusMenuShowMethods(clazz: Class<*>): Set<Method> {
        val result = LinkedHashSet<Method>()
        var current = clazz.superclass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).filter {
                val types = it.parameterTypes
                !Modifier.isStatic(it.modifiers) &&
                    it.returnType == Boolean::class.javaPrimitiveType &&
                    (types.isEmpty() || (types.size == 1 && types[0] == Integer.TYPE))
            }.forEach(result::add)
            current = current.superclass
        }
        return result
    }

    private fun findSparseArrayFieldValue(helper: Any?): SparseArray<Any?>? {
        if (helper == null) return null
        var current: Class<*>? = helper.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!Modifier.isStatic(field.modifiers) &&
                    SparseArray::class.java.isAssignableFrom(field.type)
                ) {
                    @Suppress("UNCHECKED_CAST")
                    return KavaReflector.readField(field, helper) as? SparseArray<Any?>
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findBaseAdapterFieldValue(helper: Any?): BaseAdapter? {
        if (helper == null) return null
        var current: Class<*>? = helper.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!Modifier.isStatic(field.modifiers) &&
                    BaseAdapter::class.java.isAssignableFrom(field.type)
                ) {
                    (KavaReflector.readField(field, helper) as? BaseAdapter)?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findPlusMenuHelperFieldValue(adapter: Any?): Any? {
        val helperClass = settingsDexFinder?.plusSubMenuHelperClass ?: return null
        if (adapter == null) return null
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value = KavaReflector.readField(field, adapter) ?: continue
                if (helperClass.isInstance(value)) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun sparseArrayMatchesPositionOrder(
        items: SparseArray<Any?>,
        ordered: List<Any>
    ): Boolean {
        if (items.size() != ordered.size) return false
        for (position in ordered.indices) {
            if (items.keyAt(position) != position || items.valueAt(position) !== ordered[position]) {
                return false
            }
        }
        return true
    }

    private fun findNestedItemObject(wrapper: Any?): Any? {
        if (wrapper == null) return null
        for (field in KavaReflector.declaredFields(wrapper.javaClass)) {
            if (Modifier.isStatic(field.modifiers)) continue
            val value = KavaReflector.readField(field, wrapper) ?: continue
            if (KavaReflector.declaredFields(value.javaClass).any {
                    !Modifier.isStatic(it.modifiers) && it.type == Integer.TYPE
                }
            ) return value
        }
        return null
    }

    private fun createPlusMenuWrapper(sampleWrapper: Any, sampleItem: Any, entry: PlusEntry): Any? {
        val itemClass = sampleItem.javaClass
        val item = KavaReflector.newInstanceByArgs(
            itemClass,
            arrayOf(entry.itemId, entry.title, "", 0, 0)
        ) ?: KavaReflector.newInstanceByArgs(
            itemClass,
            arrayOf(entry.itemId, entry.title, "", 0)
        ) ?: return null
        return KavaReflector.newInstanceByArgs(sampleWrapper.javaClass, arrayOf(item))
    }

    private fun itemId(wrapper: Any?): Int {
        val nested = findNestedItemObject(wrapper)
        listOfNotNull(nested, wrapper).forEach { target ->
            for (field in KavaReflector.declaredFields(target.javaClass)) {
                if (Modifier.isStatic(field.modifiers) || field.type != Integer.TYPE) continue
                val id = (KavaReflector.readField(field, target) as? Number)?.toInt() ?: continue
                if (knownPlusItemIds.contains(id)) return id
            }
        }
        return Int.MIN_VALUE
    }

    private fun readPlusMenuItemId(helper: Any?, position: Int): Int {
        val items = findSparseArrayFieldValue(helper) ?: return Int.MIN_VALUE
        return itemId(items.get(position))
    }

    private fun findMenuItem(menu: Any, itemId: Int): MenuItem? =
        KavaReflector.invokeMethod(menu, "findItem", itemId) as? MenuItem

    private fun readMenuGroupId(menu: Any): Int {
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: 0
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            return item.groupId
        }
        return 0
    }

    private fun clearMessageBindings() {
        messageBindings.clear()
        messageBindingsById.clear()
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return resolveNativeMessage(source, visited, 0)
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 5 || !visited.add(source)) return null
        if (isNativeMessage(source) && nativeMessageId(source) > 0L) return source
        if (source is View) return resolveNativeMessage(source.tag, visited, depth + 1)
        if (source is Array<*>) {
            source.forEach { item ->
                resolveNativeMessage(item, visited, depth + 1)?.let { return it }
            }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { item ->
                resolveNativeMessage(item, visited, depth + 1)?.let { return it }
            }
            return null
        }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive ||
                    field.type == String::class.java
                ) continue
                val value = KavaReflector.readField(field, source) ?: continue
                resolveNativeMessage(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun isNativeMessage(value: Any): Boolean = isNativeMessageClass(value.javaClass)

    private fun isNativeMessageClass(clazz: Class<*>): Boolean =
        clazz.name.startsWith("com.tencent.mm.storage.")

    private fun nativeMessageId(message: Any): Long {
        KavaReflector.declaredMethods(message.javaClass).firstOrNull { method ->
            method.parameterCount == 0 &&
                (method.name == "getMsgId" || method.name == "getMsgID" || method.name == "getId") &&
                (method.returnType == Long::class.javaPrimitiveType || method.returnType == Long::class.java)
        }?.let { method ->
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        return listOf("field_msgId", "msgId", "msgID", "id")
            .firstNotNullOfOrNull { name ->
                (KavaReflector.readField(message, name) as? Number)?.toLong()
            }
            ?: 0L
    }

    private fun resolveActivity(context: Any?): Activity? {
        if (context is Activity && !context.isFinishing && !context.isDestroyed) return context
        return (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    private fun findContextFieldValue(helper: Any?): Context? {
        if (helper == null) return null
        var current: Class<*>? = helper.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!Modifier.isStatic(field.modifiers) && Context::class.java.isAssignableFrom(field.type)) {
                    (KavaReflector.readField(field, helper) as? Context)?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun dismissPlusMenu(helper: Any?) {
        if (helper == null) return
        runCatching { KavaReflector.invokeMethod(helper, "a") }
    }

    private fun loadIcon(context: Context, pluginDir: File?, iconPath: String): Drawable? {
        if (iconPath.isBlank()) return null
        val file = File(iconPath).let { if (it.isAbsolute) it else File(pluginDir ?: return null, iconPath) }
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun logError(message: String, throwable: Throwable?) {
        logger?.invoke(message, throwable)
    }
}
