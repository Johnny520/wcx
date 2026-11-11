package h.Hchat.hooks.items.voiceforward

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.FavoriteMenuItemResolver
import h.Hchat.hooks.api.media.FavoriteMenuLocator
import h.Hchat.hooks.api.media.VoiceMessageDurationResolver
import h.Hchat.hooks.api.message.MultiSelectMessageMenuLocator
import h.Hchat.hooks.api.message.MultiSelectMessageResolver
import h.Hchat.hooks.api.message.MultiSelectMessageUi
import h.Hchat.hooks.api.model.WeChatContact
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.messageforward.MessageForwardSettings
import h.Hchat.hooks.items.selectedmessages.ForwardSendPolicy
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.ui.miuix.pickerDisplayName
import h.Hchat.utils.KavaReflector
import h.Hchat.utils.WeChatIdRules
import me.yun.silk.AacCodec
import me.yun.silk.SilkCodec
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoiceForwardFeature : BaseFeature() {
    private var hooker: VoiceForwardHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "语音转发保存"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(VoiceForwardSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = VoiceForwardHooker(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.shutdown()
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "voice_forward"
    }
}

private class VoiceForwardHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), VoiceForwardSettings.PREFS_NAME)
    private val messageForwardPrefs = HchatStorage.preferences(
        context.hostContext(),
        MessageForwardSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_voice_forward_method_cache")
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val menuMethodsByClickMethod = ConcurrentHashMap<Method, Method>()
    private val favoriteDataPathMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val messageIdMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val voiceFileMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val activityMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val seenDialogs = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val favoriteBindingsByMenuItem = Collections.synchronizedMap(WeakHashMap<MenuItem, Any>())
    private val favoriteItemsByOwner = Collections.synchronizedMap(WeakHashMap<Any, Any>())
    private val favoriteItemsByActivity = Collections.synchronizedMap(WeakHashMap<Activity, Any>())
    private val favoriteVoiceSourcesByOwner = Collections.synchronizedMap(WeakHashMap<Any, VoiceSource>())
    private val favoriteVoiceSourcesByActivity = Collections.synchronizedMap(WeakHashMap<Activity, VoiceSource>())
    private val favoriteSelectorAdapters = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val favoriteSelectorFilters = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    )
    private val favoriteFilterClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val forwardExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-VoiceForwardSend").apply { isDaemon = true }
    }
    @Volatile
    private var recentContactsCache: CachedContacts? = null
    @Volatile
    private var lastFavoriteItem: Any? = null
    @Volatile
    private var lastFavoriteVoiceSource: VoiceSource? = null
    private val silkCodec by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SilkCodec() }

    fun install(): Boolean {
        val clickMethods = locateClickMethods()
        if (clickMethods.isEmpty()) {
            logger("转发语音定位菜单点击方法失败", null)
        }
        var hooked = 0
        clickMethods.forEach { method ->
            val menuMethod = locateMenuMethod(method.declaringClass) ?: return@forEach
            if (hookChatMethod(menuMethod, true)) hooked++
            if (hookChatMethod(method, false)) {
                menuMethodsByClickMethod[method] = menuMethod
                hooked++
            }
        }
        val favoriteCreateMethods = FavoriteMenuLocator.menuCreateMethods(
            context,
            includeDetails = false,
            logger = logger
        )
        val favoriteClickMethods = FavoriteMenuLocator.menuClickMethods(
            context,
            includeDetails = false,
            logger = logger
        )
        favoriteCreateMethods.forEach { method ->
            if (hookFavoriteMenuCreateMethod(method)) hooked++
        }
        favoriteClickMethods.forEach { method ->
            if (hookFavoriteClickMethod(method)) hooked++
        }
        hooked += hookFavoriteSelectClick()
        hooked += hookFavoriteSelectorCreate()
        hooked += hookFavoriteSelectorAdapterQueries()
        val multiMenuClickMethod = MultiSelectMessageMenuLocator.menuClickMethod(context, logger)
        val multiExitMethod = multiMenuClickMethod?.let {
            MultiSelectMessageMenuLocator.multiSelectExitMethod(context, it, logger)
        }
        if (multiMenuClickMethod != null && multiExitMethod != null) {
            MultiSelectMessageMenuLocator.menuCreateMethod(context, logger)?.let { method ->
                if (hookMultiNativeMenuCreateMethod(method)) hooked++
            }
            if (hookMultiNativeMenuClickMethod(multiMenuClickMethod, multiExitMethod)) hooked++
        }
        if (hooked <= 0) {
            logger("转发语音Hook未安装", null)
        }
        return hooked > 0
    }

    private fun hookChatMethod(method: Method, menuCreate: Boolean): Boolean {
        return hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (menuCreate) addForwardMenu(param)
            }

            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!menuCreate) handleForwardClick(param)
            }
        })
    }

    private fun hookFavoriteMenuCreateMethod(method: Method): Boolean {
        return hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                addFavoriteMenu(param)
            }
        })
    }

    private fun hookFavoriteClickMethod(method: Method): Boolean {
        return hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                handleFavoriteClick(param)
            }
        })
    }

    private fun hookFavoriteSelectClick(): Int {
        var hooked = 0
        val selectClass = KavaReflector.loadClass(
            "com.tencent.mm.plugin.fav.ui.FavSelectUI",
            context.hostClassLoader()
        )
        val selectMethod = selectClass?.let { clazz ->
            KavaReflector.findMethod(
                clazz, "onItemClick", AdapterView::class.java, View::class.java,
                java.lang.Integer.TYPE, java.lang.Long.TYPE
            )
        }
        val methods = buildList {
            if (selectMethod != null) add(selectMethod)
            addAll(locateFavoriteSearchItemClickMethods())
        }.distinctBy(Method::toGenericString)
        methods.forEach { method ->
            if (hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        handleFavoriteSelectClick(param)
                    }
                })) hooked++
        }
        return hooked
    }

    private fun locateFavoriteSearchItemClickMethods(): List<Method> {
        val cacheName = "favorite_search_item_click_v3"
        DexMethodCache.loadList(
            methodPrefs,
            methodCacheKey(),
            context.hostClassLoader(),
            cacheName
        ).filter(::isFavoriteSearchItemClickMethod).takeIf { it.isNotEmpty() }?.let { return it }
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(listOf(FAVORITE_TOP_SEARCH_CLICK_ANCHOR))
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
                .filter(::isFavoriteSearchItemClickMethod)
                .distinctBy(Method::toGenericString)
        }.onFailure {
            logger("收藏语音搜索结果点击方法定位失败", it)
        }.getOrDefault(emptyList())
        if (methods.isEmpty()) {
            DexMethodCache.clear(methodPrefs, methodCacheKey(), cacheName)
        } else {
            DexMethodCache.saveList(methodPrefs, methodCacheKey(), cacheName, methods)
        }
        return methods
    }

    private fun isFavoriteSearchItemClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            method.declaringClass.name.startsWith("com.tencent.mm.plugin.fav.ui.") &&
            types.size == 4 &&
            AdapterView::class.java.isAssignableFrom(types[0]) &&
            View::class.java.isAssignableFrom(types[1]) &&
            types[2] == Int::class.javaPrimitiveType &&
            types[3] == Long::class.javaPrimitiveType
    }

    private fun hookFavoriteSelectorCreate(): Int {
        var hooked = 0
        arrayOf(
            "com.tencent.mm.plugin.fav.ui.FavSelectUI",
            "com.tencent.mm.plugin.fav.ui.FavSearchUI",
            "com.tencent.mm.plugin.fav.ui.FavFilterUI"
        ).forEach { className ->
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: return@forEach
            val method = KavaReflector.findMethod(clazz, "onCreate", Bundle::class.java)
                ?: return@forEach
            if (hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        removeVoiceFromFavoriteSelectorIntent(param.thisObject)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        allowVoiceInFavoriteSelector(param.thisObject)
                    }
                })) {
                hooked++
            }
        }
        return hooked
    }

    private fun hookFavoriteSelectorAdapterQueries(): Int {
        val clazz = KavaReflector.loadClass(
            "com.tencent.mm.plugin.fav.ui.adapter.c",
            context.hostClassLoader()
        ) ?: run {
            logger("收藏语音筛选适配器类未加载", null)
            return 0
        }
        var hooked = 0
        KavaReflector.declaredMethods(clazz)
            .filter { method ->
                method.returnType == Void.TYPE &&
                    method.parameterTypes.contentEquals(
                        arrayOf(List::class.java, List::class.java, List::class.java)
                    )
            }
            .forEach { queryMethod ->
                if (hookMethod(queryMethod, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            removeVoiceFromFavoriteAdapter(param.thisObject)
                        }
                    })) {
                    hooked++
                }
            }
        arrayOf("b", "c").forEach { name ->
            val resetMethod = KavaReflector.declaredMethods(clazz).firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Void.TYPE
            }
            if (resetMethod != null && hookMethod(resetMethod, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        removeVoiceFromFavoriteAdapter(param.thisObject)
                    }
                })) {
                hooked++
            }
        }
        if (hooked == 0) {
            logger("收藏语音筛选适配器查询Hook未安装", null)
        }
        return hooked
    }

    private fun removeVoiceFromFavoriteSelectorIntent(owner: Any?) {
        if (!favoriteClickForwardEnabled()) return
        val activity = owner as? Activity ?: return
        val intent = activity.intent ?: return
        if (activity.javaClass.name !in FAVORITE_SELECTOR_UI_NAMES) return
        val blockedTypes = intent.getStringExtra("key_fav_item_id") ?: return
        val updated = blockedTypes.split(',')
            .map(String::trim)
            .filter { it.isNotEmpty() && it != FAVORITE_VOICE_TYPE.toString() }
            .distinct()
            .joinToString(",")
        if (updated != blockedTypes) {
            intent.putExtra("key_fav_item_id", updated)
        }
    }

    private fun allowVoiceInFavoriteSelector(owner: Any?) {
        if (!favoriteClickForwardEnabled()) return
        val activity = owner as? Activity ?: return
        if (activity.javaClass.name !in FAVORITE_SELECTOR_UI_NAMES) return
        val selectorAdapter = findFavoriteSelectorAdapter(owner) ?: return
        favoriteSelectorAdapters.add(selectorAdapter)
        registerFavoriteSelectorFilter(selectorAdapter)
        val filterField = KavaReflector.findFieldRecursive(selectorAdapter.javaClass, "f")
        @Suppress("UNCHECKED_CAST")
        val filter = filterField?.let {
            KavaReflector.readField(it, selectorAdapter) as? MutableSet<Any>
        }
        filter?.remove(FAVORITE_VOICE_TYPE)
        removeVoiceFromFavoriteSelectorFields(owner)
        favoriteSelectorAdapters.add(selectorAdapter)
        runCatching {
            refreshFavoriteSelectorAdapter(selectorAdapter)
            KavaReflector.invokeMethod(selectorAdapter, "notifyDataSetChanged")
        }.onFailure {
            logger("刷新收藏语音筛选列表失败", it)
        }
    }

    private fun removeVoiceFromFavoriteAdapter(adapter: Any) {
        if (!favoriteClickForwardEnabled()) return
        val adapterActivity = favoriteSelectorActivity(adapter)
        val currentActivity = WeChatApis.currentActivity()?.currentActivity()
        val selectorActivity = adapterActivity ?: currentActivity
        val currentName = selectorActivity?.javaClass?.name
        if (adapter !in favoriteSelectorAdapters && currentName !in FAVORITE_SELECTOR_UI_NAMES) return
        favoriteSelectorAdapters.add(adapter)
        val filterField = KavaReflector.findFieldRecursive(adapter.javaClass, "f") ?: return
        val filter = KavaReflector.readField(filterField, adapter)
        removeFavoriteVoiceType(filter)
        registerFavoriteSelectorFilter(adapter)
    }

    private fun registerFavoriteSelectorFilter(adapter: Any) {
        val filter = KavaReflector.findFieldRecursive(adapter.javaClass, "g")?.let {
            KavaReflector.readField(it, adapter)
        } ?: return
        if (!favoriteSelectorFilters.add(filter)) return
        val filterClass = filter.javaClass
        if (!favoriteFilterClasses.add(filterClass)) return
        var current: Class<*>? = filterClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current)
                .filter { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.isNotEmpty() &&
                        !method.parameterTypes[0].isPrimitive &&
                        method.parameterTypes.drop(1).all { type ->
                            type == Boolean::class.javaPrimitiveType || type == Boolean::class.java
                        }
                }
                .forEach { method ->
                    hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!favoriteClickForwardEnabled()) return
                            if (param.thisObject !in favoriteSelectorFilters) return
                            val item = param.args?.getOrNull(0) ?: return
                            if (favoriteType(item) == FAVORITE_VOICE_TYPE) {
                                param.result = false
                            }
                        }
                    })
                }
            current = current.superclass
        }
    }

    private fun removeFavoriteVoiceType(value: Any?) {
        val collection = value as? MutableCollection<Any?> ?: return
        val iterator = collection.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val isVoiceType = when (item) {
                is Number -> item.toInt() == FAVORITE_VOICE_TYPE
                is String -> item.trim() == FAVORITE_VOICE_TYPE.toString()
                else -> false
            }
            if (isVoiceType) {
                try {
                    iterator.remove()
                } catch (_: Throwable) {
                    return
                }
            }
        }
    }

    private fun refreshFavoriteSelectorAdapter(adapter: Any) {
        for (name in arrayOf("b", "c")) {
            val resetMethod = KavaReflector.declaredMethods(adapter.javaClass).firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Void.TYPE
            } ?: continue
            KavaReflector.invoke(resetMethod, adapter)
            return
        }
        logger("收藏语音筛选适配器没有找到重置方法: adapter=${adapter.javaClass.name}", null)
    }

    private fun removeVoiceFromFavoriteSelectorFields(owner: Any) {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                @Suppress("UNCHECKED_CAST")
                val value = KavaReflector.readField(field, owner) as? MutableSet<Any> ?: continue
                value.remove(FAVORITE_VOICE_TYPE)
            }
            current = current.superclass
        }
    }

    private fun findFavoriteSelectorAdapter(owner: Any): Any? {
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, owner) ?: continue
                if (value is ListAdapter && value.javaClass.name.startsWith("com.tencent.mm.plugin.fav.ui.adapter.")) {
                    return value
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun favoriteSelectorActivity(adapter: Any): Activity? {
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, adapter)
                if (value is ListView) {
                    return unwrapActivity(value.context)
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun unwrapActivity(context: Context?): Activity? {
        var current = context
        repeat(6) {
            when (current) {
                is Activity -> return current
                is ContextWrapper -> current = current.baseContext
                else -> return null
            }
        }
        return null
    }

    private fun hookMultiNativeMenuCreateMethod(method: Method): Boolean {
        return hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                addMultiNativeVoiceMenu(param)
            }
        })
    }

    private fun hookMultiNativeMenuClickMethod(method: Method, exitMethod: Method): Boolean {
        return hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                handleMultiNativeVoiceMenuClick(param, exitMethod)
            }
        })
    }

    private fun hookMethod(method: Method, callback: XC_MethodHook): Boolean {
        if (!hookedMethods.add(method)) return true
        return try {
            HookRegistry.get().hook(method, callback)
            true
        } catch (e: Throwable) {
            hookedMethods.remove(method)
            logger("转发语音Hook安装失败: ${method.name}", e)
            false
        }
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun addForwardMenu(param: XC_MethodHook.MethodHookParam) {
        if (!isEnabled()) return
        val allowForward = chatForwardEnabled() && !messageForwardMenuEnabled()
        val allowSave = chatSaveEnabled()
        if (!allowForward && !allowSave) return
        val args = param.args ?: return
        if (args.size < 3) return
        val menu = args[0] ?: return
        val message = resolveNativeMessage(args) ?: return
        if (!isVoiceMessage(message)) return
        val groupId = readMenuGroupId(args.getOrNull(1))
        val view = args.getOrNull(1) as? View
        if (allowForward) {
            addVoiceMenuItem(menu, view, groupId, MENU_FORWARD_ID, MENU_FORWARD_TITLE, "icons_filled_share")
        }
        if (allowSave) {
            addVoiceMenuItem(menu, view, groupId, MENU_SAVE_ID, MENU_SAVE_TITLE, "icons_filled_download")
        }
    }

    private fun addFavoriteMenu(param: XC_MethodHook.MethodHookParam) {
        if (!isEnabled()) return
        val allowForward = favoriteMenuForwardEnabled()
        val allowSave = favoriteSaveEnabled()
        if (!allowForward && !allowSave) return
        val args = param.args ?: return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View
        val favorite = resolveFavoriteVoiceMenuItem(param) ?: return
        if (!isFavoriteVoice(favorite)) return
        rememberFavoriteItem(param.thisObject, view, favorite)
        resolveFavoriteVoiceSource(favorite)?.let { source ->
            rememberFavoriteVoiceSource(param.thisObject, view, source)
        }
        if (allowForward) {
            addVoiceMenuItem(menu, view, 0, MENU_FORWARD_ID, MENU_FORWARD_TITLE, "icons_filled_share")
            bindFavoriteMenuItem(menu, MENU_FORWARD_ID, favorite)
        }
        if (allowSave) {
            addVoiceMenuItem(menu, view, 0, MENU_SAVE_ID, MENU_SAVE_TITLE, "icons_filled_download")
            bindFavoriteMenuItem(menu, MENU_SAVE_ID, favorite)
        }
    }

    private fun bindFavoriteMenuItem(menu: Any, itemId: Int, favorite: Any) {
        (KavaReflector.invokeMethod(menu, "findItem", itemId) as? MenuItem)?.let { item ->
            favoriteBindingsByMenuItem[item] = favorite
        }
    }

    private fun addMultiNativeVoiceMenu(param: XC_MethodHook.MethodHookParam) {
        if (!isEnabled() || (!chatMultiForwardEnabled() && !chatMultiMergeEnabled())) return
        val messages = MultiSelectMessageResolver.resolve(param.thisObject)
        if (messages.isEmpty() || messages.any { !isVoiceMessage(it) }) return
        val menu = param.args?.getOrNull(0) ?: return
        if (chatMultiForwardEnabled()) {
            addVoiceMenuItem(menu, null, 0, MULTI_VOICE_NATIVE_FORWARD_ID, MULTI_VOICE_NATIVE_FORWARD_TITLE, "icons_filled_share")
        }
        if (chatMultiMergeEnabled() && messages.size >= 2) {
            addVoiceMenuItem(menu, null, 0, MULTI_VOICE_MERGE_ID, MULTI_VOICE_MERGE_TITLE, "icons_filled_share")
        }
    }

    private fun addVoiceMenuItem(menu: Any, view: View?, groupId: Int, itemId: Int, title: String, iconName: String) {
        if (KavaReflector.invokeMethod(menu, "findItem", itemId) != null) return
        val iconRes = menuIconResId(view, iconName)
        if (iconRes != 0) {
            val iconMethod = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "c" &&
                    types.size == 5 &&
                    types[0] == java.lang.Integer.TYPE &&
                    types[1] == java.lang.Integer.TYPE &&
                    types[2] == java.lang.Integer.TYPE &&
                    types[3].isAssignableFrom(String::class.java) &&
                    types[4] == java.lang.Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(iconMethod, menu, groupId, itemId, 0, title, iconRes)) {
                return
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", groupId, itemId, 0, title)
            ?: KavaReflector.invokeMethod(menu, "add", groupId, itemId, 0, title as CharSequence)
        if (added is MenuItem && iconRes != 0) {
            runCatching { added.setIcon(iconRes) }
            return
        }
        if (added != null) {
            return
        }
        KavaReflector.invokeMethod(menu, "f", itemId, title)
            ?: KavaReflector.invokeMethod(menu, "f", itemId, title as CharSequence)
    }

    private fun menuIconResId(view: View?, iconName: String): Int {
        val iconContext = view?.context ?: WeChatApis.currentActivity()?.currentActivity() ?: return 0
        val resources = iconContext.resources
        val packageName = iconContext.packageName
        for (type in arrayOf("raw", "drawable")) {
            val id = resources.getIdentifier(iconName, type, packageName)
            if (id != 0) return id
        }
        return 0
    }

    private fun handleForwardClick(param: XC_MethodHook.MethodHookParam) {
        if (!isEnabled()) return
        val args = param.args ?: return
        val menuItem = args.getOrNull(0) as? MenuItem ?: return
        if (menuItem.itemId != MENU_FORWARD_ID && menuItem.itemId != MENU_SAVE_ID) return
        if (menuItem.itemId == MENU_FORWARD_ID && !chatForwardEnabled()) return
        if (menuItem.itemId == MENU_SAVE_ID && !chatSaveEnabled()) return
        val message = resolveNativeMessage(args)
        val activity = resolveActivity(args.getOrNull(1))
        if (message == null || activity == null) {
            toast(activity, "语音消息不可用")
            param.result = true
            return
        }
        val source = resolveVoiceSource(message)
        if (source == null) {
            toast(activity, "语音文件不存在")
            param.result = true
            return
        }
        if (menuItem.itemId == MENU_SAVE_ID) {
            saveVoice(activity, source)
        } else {
            showContactPicker(activity, source)
        }
        param.result = true
    }

    private fun handleFavoriteClick(param: XC_MethodHook.MethodHookParam) {
        if (!isEnabled()) return
        val args = param.args ?: return
        val menuItem = args.getOrNull(0) as? MenuItem ?: return
        if (menuItem.itemId != MENU_FORWARD_ID && menuItem.itemId != MENU_SAVE_ID) return
        if (menuItem.itemId == MENU_FORWARD_ID && !favoriteMenuForwardEnabled()) return
        if (menuItem.itemId == MENU_SAVE_ID && !favoriteSaveEnabled()) return
        val activity = WeChatApis.currentActivity()?.currentActivity() as? Activity
        val favorite = favoriteBindingsByMenuItem.remove(menuItem)
            ?: resolveFavoriteItem(param.thisObject)
            ?: resolveFavoriteItem(args)
            ?: pendingFavoriteItem(param.thisObject, activity)
        val source = favorite?.let { resolveFavoriteVoiceSource(it) }
            ?: pendingFavoriteVoiceSource(param.thisObject, activity)
        if (source == null) {
            toast(activity, if (favorite == null) "收藏语音不可用" else "收藏语音文件不存在")
            param.result = null
            return
        }
        val targetActivity = activity
        if (targetActivity == null) {
            param.result = null
            return
        }
        if (menuItem.itemId == MENU_SAVE_ID) {
            saveVoice(targetActivity, source)
        } else {
            showContactPicker(targetActivity, source)
        }
        param.result = null
    }

    private fun handleFavoriteSelectClick(param: XC_MethodHook.MethodHookParam) {
        if (!favoriteClickForwardEnabled()) return
        val view = param.args?.getOrNull(1) as? View ?: return
        val selectActivity = favoriteSelectActivity(param.thisObject)
            ?: unwrapActivity(view.context)
        val adapterView = param.args?.getOrNull(0) as? AdapterView<*>
        val position = (param.args?.getOrNull(2) as? Number)?.toInt() ?: -1
        val adapterPosition = position - (adapterView?.let { viewGroup ->
            (viewGroup as? android.widget.ListView)?.headerViewsCount
        } ?: 0) - if (selectActivity?.javaClass?.name in FAVORITE_SEARCH_UI_NAMES) 1 else 0
        val favorite = sequenceOf(
            resolveFavoriteItem(view.tag),
            resolveFavoriteMenuItem(view),
            resolveFavoriteItem(view),
            FavoriteMenuItemResolver.resolveAdapterItem(adapterView?.adapter, adapterPosition)
        ).filterNotNull().firstOrNull(::isFavoriteVoice) ?: return
        val activity = selectActivity
            ?: WeChatApis.currentActivity()?.currentActivity() as? Activity
        val target = favoriteSelectTargetTalker(activity ?: param.thisObject)
        if (target.isBlank()) {
            toast(activity, "当前聊天不可用")
            param.result = null
            return
        }
        val source = resolveFavoriteVoiceSource(favorite)
        if (source == null) {
            toast(activity, "收藏语音文件不存在")
            param.result = null
            return
        }
        val targetActivity = activity
        if (targetActivity == null || targetActivity.isFinishing) {
            param.result = null
            return
        }
        if (!seenDialogs.contains(targetActivity)) {
            seenDialogs.add(targetActivity)
            VoiceForwardMiuixDialog.showFavoriteVoiceSendConfirm(
                activity = targetActivity,
                preparePreview = {
                    preparePreviewVoice(source)
                },
                onConfirm = {
                    val sent = sendVoiceForForward(source, target)
                    toast(targetActivity, if (sent) "收藏语音已发送" else "收藏语音发送失败")
                    if (sent && !targetActivity.isFinishing) {
                        targetActivity.finish()
                    }
                },
                onDismiss = {
                    seenDialogs.remove(targetActivity)
                }
            )
        }
        param.result = null
    }

    private fun handleMultiNativeVoiceMenuClick(param: XC_MethodHook.MethodHookParam, exitMethod: Method) {
        if (!isEnabled()) return
        val args = param.args ?: return
        val menuItem = args.getOrNull(0) as? MenuItem ?: return
        val isIndividualForward = menuItem.itemId == MULTI_VOICE_NATIVE_FORWARD_ID
        val isMerge = menuItem.itemId == MULTI_VOICE_MERGE_ID
        if (!isIndividualForward && !isMerge) return
        if (isIndividualForward && !chatMultiForwardEnabled()) return
        if (isMerge && !chatMultiMergeEnabled()) return
        val activity = WeChatApis.currentActivity()?.currentActivity() as? Activity
        val messages = MultiSelectMessageResolver.resolve(param.thisObject)
        if (messages.isEmpty() || messages.any { !isVoiceMessage(it) }) {
            toast(activity, "未找到选中的语音消息")
            param.result = null
            return
        }
        val sources = messages.mapNotNull { resolveVoiceSource(it) }
        if (sources.size != messages.size) {
            toast(activity, "部分语音文件不存在")
            param.result = null
            return
        }
        val exitTarget = MultiSelectMessageUi.resolveExitTarget(param.thisObject, exitMethod, logger)
        if (exitTarget == null) {
            toast(activity, "无法退出多选状态，请稍后重试")
            param.result = null
            return
        }
        if (activity == null) {
            toast(null, "当前页面不可用")
        } else if (isIndividualForward) {
            showContactPicker(activity, sources, exitTarget)
        } else {
            VoiceForwardMiuixDialog.showChoices(
                activity = activity,
                title = "合并语音",
                summary = "请选择合并语音的操作",
                choices = listOf(
                    "转发" to "选择好友或群聊发送合并语音",
                    "保存" to "保存合并后的 MP3 文件"
                ),
                onSelected = { choice ->
                    if (choice == 1) {
                        mergeMultiVoices(
                            activity = activity,
                            sources = sources,
                            saveOutput = true,
                            exitTarget = exitTarget
                        )
                    } else {
                        showContactPicker(activity, sources, exitTarget) { targets ->
                            mergeMultiVoices(
                                activity = activity,
                                sources = sources,
                                saveOutput = false,
                                exitTarget = exitTarget,
                                forwardTargets = targets
                            )
                        }
                    }
                },
                onDismiss = {}
            )
        }
        param.result = null
    }

    private fun mergeMultiVoices(
        activity: Activity,
        sources: List<VoiceSource>,
        saveOutput: Boolean,
        exitTarget: MultiSelectMessageUi.ExitTarget,
        forwardTargets: List<VoiceForwardMiuixDialog.ContactItem> = emptyList()
    ) {
        if (sources.size < 2) {
            toast(activity, "至少选择两条语音")
            return
        }
        val cancelled = AtomicBoolean(false)
        val closingByTask = AtomicBoolean(false)
        val loading = VoiceForwardMiuixDialog.showLoading(
            activity = activity,
            title = "合并语音",
            message = "正在合并 ${sources.size} 条语音...",
            onDismiss = {
                if (!closingByTask.get()) cancelled.set(true)
            }
        )
        Thread({
            val merged = runCatching {
                mergeVoiceSources(sources, saveOutput)
            }.onFailure {
                logger("合并语音失败", it)
            }.getOrNull()
            Handler(Looper.getMainLooper()).post {
                if (cancelled.get()) {
                    deleteVoiceFiles(listOfNotNull(merged))
                    return@post
                }
                closingByTask.set(true)
                loading.close()
                if (activity.isFinishing || activity.isDestroyed) {
                    deleteVoiceFiles(listOfNotNull(merged))
                    return@post
                }
                val decor = activity.window?.decorView
                if (decor == null) {
                    deleteVoiceFiles(listOfNotNull(merged))
                    return@post
                }
                decor.postOnAnimation {
                    if (activity.isFinishing || activity.isDestroyed) {
                        deleteVoiceFiles(listOfNotNull(merged))
                        return@postOnAnimation
                    }
                    if (merged == null) {
                        toast(activity, "语音合并失败")
                        return@postOnAnimation
                    }
                    if (saveOutput) {
                        toast(activity, "合并语音已保存: ${merged.path}")
                        exitTarget.exit(logger)
                    } else if (forwardTargets.isEmpty()) {
                        deleteVoiceFiles(listOf(merged))
                        toast(activity, "未选择转发对象")
                    } else {
                        sendVoicesToTargets(activity, listOf(merged), forwardTargets)
                        exitTarget.exit(logger)
                    }
                }
            }
        }, "Hchat-VoiceMerge").start()
    }

    private fun rememberFavoriteItem(owner: Any?, view: View?, favorite: Any) {
        if (owner != null) favoriteItemsByOwner[owner] = favorite
        val activity = (view?.context as? Activity) ?: (WeChatApis.currentActivity()?.currentActivity() as? Activity)
        if (activity != null && !activity.isFinishing) {
            favoriteItemsByActivity[activity] = favorite
        }
        lastFavoriteItem = favorite
    }

    private fun rememberFavoriteVoiceSource(owner: Any?, view: View?, source: VoiceSource) {
        if (owner != null) favoriteVoiceSourcesByOwner[owner] = source
        val activity = (view?.context as? Activity) ?: (WeChatApis.currentActivity()?.currentActivity() as? Activity)
        if (activity != null && !activity.isFinishing) {
            favoriteVoiceSourcesByActivity[activity] = source
        }
        lastFavoriteVoiceSource = source
    }

    private fun pendingFavoriteItem(owner: Any?, activity: Activity?): Any? {
        if (owner != null) favoriteItemsByOwner[owner]?.let { return it }
        if (activity != null) favoriteItemsByActivity[activity]?.let { return it }
        return lastFavoriteItem
    }

    private fun pendingFavoriteVoiceSource(owner: Any?, activity: Activity?): VoiceSource? {
        if (owner != null) favoriteVoiceSourcesByOwner[owner]?.let { return it }
        if (activity != null) favoriteVoiceSourcesByActivity[activity]?.let { return it }
        return lastFavoriteVoiceSource
    }

    private fun favoriteSelectTargetTalker(owner: Any?): String {
        favoriteSelectActivity(owner)?.intent
            ?.getStringExtra("key_to_user")
            ?.takeIf { isPlausibleTalkerId(it) }
            ?.let { return it }
        for (fieldName in arrayOf("T", "P", "S", "Q")) {
            (KavaReflector.readField(owner, fieldName) as? String)
                ?.takeIf { isPlausibleTalkerId(it) }
                ?.let { return it }
        }
        WeChatApis.chatPage()?.currentTalker()
            ?.takeIf { isPlausibleTalkerId(it) }
            ?.let { return it }
        if (owner == null) return ""
        var current: Class<*>? = owner.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type != String::class.java) continue
                (KavaReflector.readField(field, owner) as? String)
                    ?.takeIf { isLikelyTalkerId(it) }
                    ?.let { return it }
            }
            current = current.superclass
        }
        return ""
    }

    private fun favoriteSelectActivity(owner: Any?): Activity? {
        if (owner is Activity) return owner
        owner ?: return null
        var type: Class<*>? = owner.javaClass
        while (type != null && type != Any::class.java) {
            for (field in KavaReflector.declaredFields(type)) {
                val value = KavaReflector.readField(field, owner)
                if (value is Activity) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun isLikelyTalkerId(value: String): Boolean {
        val text = value.trim()
        if (text == "filehelper") return true
        if (text.endsWith("@chatroom") || text.endsWith("@im.chatroom") || text.endsWith("@openim")) return true
        return WeChatIdRules.isLikelyContactId(text)
    }

    private fun isPlausibleTalkerId(value: String): Boolean {
        val text = value.trim()
        if (text.isBlank() || text.length > 128) return false
        if (text.contains('/') || text.contains('\\') || text.contains('<') || text.contains('\n')) return false
        return text.matches(Regex("[A-Za-z0-9_@.\\-]+"))
    }

    private fun locateClickMethods(): List<Method> {
        val cached = DexMethodCache.loadList(methodPrefs, methodCacheKey(), context.hostClassLoader(), "voice_menu_click")
            .filter { isVoiceClickMethod(it) }
        if (cached.isNotEmpty()) return cached

        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(listOf("ChattingItemVoice", "Retr_Msg_content", "Retr_Msg_Type"))
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter { isVoiceClickMethod(it) }
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("转发语音DexKit定位失败", it)
            emptyList()
        }

        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, methodCacheKey(), "voice_menu_click", methods)
        } else {
            DexMethodCache.clear(methodPrefs, methodCacheKey(), "voice_menu_click")
        }
        return methods
    }

    private fun isVoiceClickMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == java.lang.Boolean.TYPE &&
            types.size >= 3 &&
            MenuItem::class.java.isAssignableFrom(types[0]) &&
            method.declaringClass.name.startsWith("com.tencent.mm.ui.chatting.viewitems.")
    }

    private fun locateMenuMethod(owner: Class<*>): Method? {
        menuMethodsByClickMethod.values.firstOrNull { it.declaringClass == owner }?.let { return it }
        return KavaReflector.declaredMethods(owner).firstOrNull { method ->
            val types = method.parameterTypes
            method.returnType == java.lang.Boolean.TYPE &&
                !Modifier.isStatic(method.modifiers) &&
                types.size in 3..4 &&
                !MenuItem::class.java.isAssignableFrom(types[0]) &&
                View::class.java.isAssignableFrom(types[1])
        }
    }

    private fun isEnabled(): Boolean {
        return chatForwardEnabled() ||
            chatSaveEnabled() ||
            chatMultiForwardEnabled() ||
            chatMultiMergeEnabled() ||
            favoriteForwardEnabled() ||
            favoriteSaveEnabled()
    }

    private fun chatForwardEnabled(): Boolean {
        return voiceSwitchEnabled(VoiceForwardSettings.KEY_CHAT_FORWARD_ENABLE)
    }

    private fun chatSaveEnabled(): Boolean {
        return voiceSwitchEnabled(VoiceForwardSettings.KEY_CHAT_SAVE_ENABLE)
    }

    private fun favoriteForwardEnabled(): Boolean {
        return voiceSwitchEnabled(VoiceForwardSettings.KEY_FAVORITE_FORWARD_ENABLE)
    }

    private fun favoriteClickForwardEnabled(): Boolean {
        return favoriteForwardEnabled() || messageFavoriteForwardEnabled()
    }

    private fun favoriteMenuForwardEnabled(): Boolean {
        return favoriteForwardEnabled() && !messageFavoriteForwardEnabled()
    }

    private fun messageFavoriteForwardEnabled(): Boolean {
        return messageForwardPrefs.getBoolean(
            MessageForwardSettings.KEY_FAVORITE_FORWARD_ENABLE,
            MessageForwardSettings.DEFAULT_ENABLE
        )
    }

    private fun favoriteSaveEnabled(): Boolean {
        return voiceSwitchEnabled(VoiceForwardSettings.KEY_FAVORITE_SAVE_ENABLE)
    }

    private fun chatMultiForwardEnabled(): Boolean {
        return voiceSwitchEnabled(VoiceForwardSettings.KEY_CHAT_MULTI_FORWARD_ENABLE)
    }

    private fun chatMultiMergeEnabled(): Boolean {
        return prefs.getBoolean(VoiceForwardSettings.KEY_CHAT_MULTI_MERGE_ENABLE, false)
    }

    private fun messageForwardMenuEnabled(): Boolean {
        return messageForwardPrefs.getBoolean(
            MessageForwardSettings.KEY_ENABLE,
            MessageForwardSettings.DEFAULT_ENABLE
        )
    }

    private fun voiceSwitchEnabled(key: String): Boolean {
        val legacyDefault = prefs.getBoolean(VoiceForwardSettings.KEY_ENABLE, VoiceForwardSettings.DEFAULT_ENABLE)
        return prefs.getBoolean(key, legacyDefault)
    }

    private fun readMenuGroupId(view: Any?): Int {
        val tag = (view as? View)?.tag ?: return 0
        for (name in arrayOf("c", "d")) {
            val value = KavaReflector.invokeMethod(tag, name)
            if (value is Number) return value.toInt()
        }
        return 0
    }

    private fun resolveActivity(chattingContext: Any?): Activity? {
        val current = WeChatApis.currentActivity()?.currentActivity()
        if (current is Activity && !current.isFinishing) return current
        val target = chattingContext ?: return null
        cachedMethod(activityMethodCache, target.javaClass) {
            KavaReflector.declaredMethods(target.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() && Activity::class.java.isAssignableFrom(method.returnType)
            }
        }?.let { method ->
            val activity = KavaReflector.invoke(method, target) as? Activity
            if (activity != null && !activity.isFinishing) return activity
        }
        return null
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        return resolveNativeMessage(source, Collections.newSetFromMap(WeakHashMap<Any, Boolean>()), 0)
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 5 || !visited.add(source)) return null
        if (isLikelyNativeMessage(source) && messageId(source) > 0L) return source
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View) return resolveNativeMessage(source.tag, visited, depth + 1)
        if (source is Array<*>) {
            source.forEach { item -> resolveNativeMessage(item, visited, depth + 1)?.let { return it } }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { item -> resolveNativeMessage(item, visited, depth + 1)?.let { return it } }
            return null
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val type = field.type
                if (type.isPrimitive || type.isArray || type == String::class.java) continue
                val value = KavaReflector.readField(field, source) ?: continue
                resolveNativeMessage(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun isLikelyNativeMessage(value: Any): Boolean {
        return value.javaClass.name.startsWith("com.tencent.mm.storage.") ||
            KavaReflector.declaredMethods(value.javaClass).any { method ->
                method.parameterTypes.isEmpty() &&
                    (method.name == "getMsgId" || method.name == "getMsgID") &&
                    (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java)
            }
    }

    private fun isVoiceMessage(message: Any): Boolean {
        val type = firstNumber(message, "getType", "getMsgType", "getMsgTypeValue")
            ?: firstNumberField(message, "field_type", "type")
        return type?.toInt() == 34
    }

    private fun isFavoriteVoice(favorite: Any): Boolean {
        return favoriteType(favorite) == 3 && firstFavoriteDataItem(favorite) != null
    }

    private fun resolveVoiceSource(message: Any): VoiceSource? {
        val fileName = voiceFileName(message)
        val path = WeChatApis.media()?.voices()?.resolvePath(message, fileName).orEmpty()
        if (path.isBlank() || !File(path).isFile) return null
        return VoiceSource(path, resolveVoiceDuration(message, fileName))
    }

    private fun resolveFavoriteVoiceSource(favorite: Any): VoiceSource? {
        if (!isFavoriteVoice(favorite)) return null
        val data = firstFavoriteDataItem(favorite) ?: return null
        val rawPaths = linkedSetOf<String>()
        favoriteDataPaths(data).forEach { rawPaths += it }
        scanFavoriteVoicePath(data)?.let { rawPaths += it }
        scanFavoriteVoicePath(favorite)?.let { rawPaths += it }
        val path = rawPaths.asSequence()
            .mapNotNull { materializeVoicePath(it) }
            .firstOrNull()
            ?: return null
        val duration = normalizeVoiceDurationMillis(firstNumberField(data, "y", "duration", "length")?.toLong())
            ?: DEFAULT_VOICE_DURATION_MS
        return VoiceSource(path, duration)
    }

    private fun favoriteDataPaths(data: Any): List<String> {
        val dataClass = data.javaClass
        val dataId = favoriteDataId(data)
        val paths = linkedSetOf<String>()
        favoriteDataPathMethodCache[dataClass]?.let { method ->
            (KavaReflector.invoke(method, null, data) as? String)
                ?.takeIf { favoriteDataPathScore(it, dataId, method) >= 0 }
                ?.let { paths += it }
        }
        val cacheName = "fav_data_path_v2_${dataClass.name}"
        val cached = DexMethodCache.load(methodPrefs, methodCacheKey(), context.hostClassLoader(), cacheName)
        if (cached != null && isFavoriteDataPathMethod(cached, dataClass)) {
            favoriteDataPathMethodCache[dataClass] = cached
            (KavaReflector.invoke(cached, null, data) as? String)
                ?.takeIf { favoriteDataPathScore(it, dataId, cached) >= 0 }
                ?.let { paths += it }
        }
        val candidates = locateFavoriteDataPathMethods(dataClass)
        val ranked = candidates
            .mapNotNull { method ->
                val path = KavaReflector.invoke(method, null, data) as? String ?: return@mapNotNull null
                val score = favoriteDataPathScore(path, dataId, method)
                if (score < 0) return@mapNotNull null
                Triple(method, path, score)
            }
            .sortedByDescending { it.third }
        val selected = ranked.firstOrNull()
        if (selected != null) {
            favoriteDataPathMethodCache[dataClass] = selected.first
            DexMethodCache.save(methodPrefs, methodCacheKey(), cacheName, selected.first)
        }
        ranked.forEach { paths += it.second }
        return paths.toList()
    }

    private fun favoriteDataId(source: Any): String {
        for (fieldName in arrayOf("T", "Z")) {
            (KavaReflector.readField(source, fieldName) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return ""
    }

    private fun favoriteDataPathScore(path: String, dataId: String, method: Method? = null): Int {
        if (path.isBlank() || (!path.contains('/') && !path.contains("://"))) return -1
        val file = File(path)
        val lowerPath = path.lowercase(Locale.US)
        val lowerName = file.name.lowercase(Locale.US)
        var score = 0
        when (method?.name) {
            "x", "w" -> score += 80
            "X" -> score -= 20
        }
        if (dataId.isNotBlank() && file.name == dataId) score += 40
        if (dataId.isNotBlank() && file.name.startsWith(dataId)) score += 30
        if (dataId.isNotBlank() && path.contains(dataId)) score += 20
        if (!lowerName.endsWith("_t")) score += 10 else score -= 30
        if (lowerPath.contains("/favorite") || lowerPath.contains("/fav/")) score += 8
        if (lowerPath.contains("voice")) score += 6
        if (pathExists(path)) score += 24
        return score
    }

    private fun materializeVoicePath(path: String): String? {
        if (File(path).isFile) return path
        val input = openVfsInputStream(path) ?: return null
        val ext = File(path).extension
            .takeIf { it.isNotBlank() && it.length <= 8 }
            ?: "silk"
        val dir = File(context.hostContext().cacheDir, "Hchat_fav_voice")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "fav_${Integer.toHexString(path.hashCode())}.$ext")
        return runCatching {
            input.use { stream ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            target.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        }.getOrNull()
    }

    private fun pathExists(path: String): Boolean {
        if (path.isBlank()) return false
        if (File(path).isFile) return true
        return vfsPathExists(path, "j", "k")
    }

    private fun openVfsInputStream(path: String): InputStream? {
        if (path.isBlank()) return null
        for (className in arrayOf("com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6")) {
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: continue
            for (methodName in arrayOf("E", "F")) {
                val method = KavaReflector.findMethod(clazz, methodName, String::class.java) ?: continue
                val stream = KavaReflector.invoke(method, null, path) as? InputStream
                if (stream != null) return stream
            }
            for (method in KavaReflector.declaredMethods(clazz)) {
                if (!Modifier.isStatic(method.modifiers)) continue
                if (method.returnType != InputStream::class.java) continue
                val types = method.parameterTypes
                if (types.size != 1 || types[0] != String::class.java) continue
                val stream = KavaReflector.invoke(method, null, path) as? InputStream
                if (stream != null) return stream
            }
        }
        return null
    }

    private fun vfsPathExists(path: String, vararg methodNames: String): Boolean {
        for (className in arrayOf("com.tencent.mm.vfs.w6", "com.tencent.mm.vfs.p6")) {
            val clazz = KavaReflector.loadClass(className, context.hostClassLoader()) ?: continue
            for (method in KavaReflector.declaredMethods(clazz)) {
                if (method.name !in methodNames) continue
                if (!Modifier.isStatic(method.modifiers)) continue
                val types = method.parameterTypes
                if (types.size != 1 || types[0] != String::class.java) continue
                val value = KavaReflector.invoke(method, null, path)
                when (value) {
                    true -> return true
                    is Number -> if (value.toLong() > 0L) return true
                }
            }
        }
        return false
    }

    private fun scanFavoriteVoicePath(source: Any): String? {
        val dataId = favoriteDataId(source)
        val candidates = mutableListOf<Pair<String, Int>>()
        collectExistingPaths(
            source = source,
            dataId = dataId,
            candidates = candidates,
            visited = Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
            depth = 0
        )
        return candidates.maxByOrNull { it.second }?.first
    }

    private fun collectExistingPaths(
        source: Any?,
        dataId: String,
        candidates: MutableList<Pair<String, Int>>,
        visited: MutableSet<Any>,
        depth: Int
    ) {
        if (source == null || depth > 4 || !visited.add(source)) return
        when (source) {
            is String -> {
                favoriteVoicePathScore(source, dataId).takeIf { it >= 0 }?.let { score ->
                    candidates += source to score
                }
                return
            }
            is Array<*> -> {
                source.forEach { collectExistingPaths(it, dataId, candidates, visited, depth + 1) }
                return
            }
            is Collection<*> -> {
                source.forEach { collectExistingPaths(it, dataId, candidates, visited, depth + 1) }
                return
            }
        }
        if (source.javaClass.isArray) return
        val className = source.javaClass.name
        if (className.startsWith("android.") || className.startsWith("java.lang.") || className.startsWith("java.io.")) {
            return
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive) continue
                val value = KavaReflector.readField(field, source) ?: continue
                collectExistingPaths(value, dataId, candidates, visited, depth + 1)
            }
            current = current.superclass
        }
    }

    private fun favoriteVoicePathScore(path: String, dataId: String): Int {
        if (path.isBlank()) return -1
        if (!path.contains('/') && !path.contains("://")) return -1
        val file = File(path)
        if (!pathExists(path)) return -1
        val lowerPath = path.lowercase(Locale.US)
        val lowerName = file.name.lowercase(Locale.US)
        var score = 0
        if (dataId.isNotBlank() && file.name == dataId) score += 30
        if (dataId.isNotBlank() && file.name.startsWith(dataId)) score += 24
        if (dataId.isNotBlank() && path.contains(dataId)) score += 18
        if (!lowerName.endsWith("_t")) score += 12 else score -= 30
        if (lowerName.endsWith(".silk") || lowerName.endsWith(".slk") || lowerName.endsWith(".amr") ||
            lowerName.endsWith(".spx") || lowerName.endsWith(".speex") || lowerName.endsWith(".mp3")
        ) {
            score += 16
        }
        if (lowerPath.contains("/favorite") || lowerPath.contains("/fav/")) score += 4
        if (lowerPath.contains("voice")) score += 4
        if (file.isFile && file.length() > 0L) score += 2
        return score
    }

    private fun locateFavoriteDataPathMethods(dataClass: Class<*>): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            returnType("java.lang.String")
                            paramTypes(dataClass.name)
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter { isFavoriteDataPathMethod(it, dataClass) }
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("收藏语音定位文件路径方法失败", it)
            emptyList()
        }
    }

    private fun isFavoriteDataPathMethod(method: Method, dataClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return Modifier.isStatic(method.modifiers) &&
            method.returnType == String::class.java &&
            types.size == 1 &&
            types[0].isAssignableFrom(dataClass)
    }

    private fun voiceFileName(message: Any): String {
        for (name in arrayOf("field_imgPath", "imgPath", "voicePath", "fileName")) {
            (KavaReflector.readField(message, name) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        cachedMethod(voiceFileMethodCache, message.javaClass) {
            firstStringGetter(message.javaClass, "z0", "m0", "getFileName", "getVoiceFileName")
        }?.let { method ->
            (KavaReflector.invoke(method, message) as? String)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun resolveVoiceDuration(message: Any, fileName: String): Int {
        val msgId = messageId(message)
        val storedMessage = runCatching {
            WeChatApis.messageStore()?.getMessageById(msgId)
        }.getOrNull()
        val contentCandidates = buildList {
            storedMessage?.bodyContent()?.takeIf { it.isNotBlank() }?.let(::add)
            storedMessage?.content?.takeIf { it.isNotBlank() }?.let(::add)
        }
        return VoiceMessageDurationResolver.resolve(
            message,
            fileName,
            msgId,
            contentCandidates,
            DEFAULT_VOICE_DURATION_MS
        )
    }

    private fun normalizeVoiceDurationMillis(raw: Long?): Int? {
        val value = raw ?: return null
        if (value <= 0L) return null
        val millis = if (value in 1L..600L) value * 1000L else value
        return millis.coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun firstStringGetter(clazz: Class<*>, vararg names: String): Method? {
        for (name in names) {
            val method = KavaReflector.findMethod(clazz, name)
            if (method != null && method.parameterTypes.isEmpty() && method.returnType == String::class.java) {
                return method
            }
        }
        return null
    }

    private fun messageId(message: Any): Long {
        cachedMethod(messageIdMethodCache, message.javaClass) {
            KavaReflector.declaredMethods(message.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    (method.name == "getMsgId" || method.name == "getMsgID" || method.name == "getId") &&
                    (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java)
            }
        }?.let { method ->
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        return firstNumberField(message, "field_msgId", "msgId", "msgID", "id")?.toLong() ?: 0L
    }

    private fun resolveFavoriteItem(source: Any?): Any? {
        return FavoriteMenuItemResolver.resolve(source)
    }

    private fun resolveFavoriteMenuItem(source: Any?): Any? {
        return FavoriteMenuItemResolver.resolveMenuItem(source)
    }

    private fun resolveFavoriteVoiceMenuItem(param: XC_MethodHook.MethodHookParam): Any? {
        val args = param.args ?: return null
        val candidates = listOfNotNull(
            args.getOrNull(2),
            args.getOrNull(1) as? View,
            args,
            param.thisObject
        )
        val visited = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
        candidates.forEach { candidate ->
            if (!visited.add(candidate)) return@forEach
            resolveFavoriteMenuItem(candidate)?.takeIf(::isFavoriteVoice)?.let { return it }
        }
        return null
    }

    private fun favoriteType(favorite: Any): Int {
        return (firstNumberField(favorite, "field_type", "type") ?: return 0).toInt()
    }

    private fun firstFavoriteDataItem(favorite: Any): Any? {
        val proto = KavaReflector.readField(favorite, "field_favProto") ?: return null
        val list = KavaReflector.readField(proto, "f") as? List<*> ?: return null
        return list.firstOrNull()
    }

    private fun firstNumber(message: Any, vararg names: String): Number? {
        for (name in names) {
            val method = KavaReflector.findMethod(message.javaClass, name)
            val value = KavaReflector.invoke(method, message)
            if (value is Number) return value
        }
        return null
    }

    private fun firstNumberField(message: Any, vararg names: String): Number? {
        for (name in names) {
            val value = KavaReflector.readField(message, name)
            if (value is Number) return value
        }
        return null
    }

    private fun cachedMethod(
        cache: ConcurrentHashMap<Class<*>, Method>,
        clazz: Class<*>,
        resolver: () -> Method?
    ): Method? {
        cache[clazz]?.let { return it }
        val method = resolver() ?: return null
        cache[clazz] = method
        return method
    }

    private fun showContactPicker(activity: Activity, source: VoiceSource) {
        if (seenDialogs.contains(activity)) return
        seenDialogs.add(activity)
        cachedContacts()?.let { cached ->
            showLoadedContactPicker(activity, source, cached.contacts)
            return
        }
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = runCatching { loadContacts().also { recentContactsCache = CachedContacts(it, System.currentTimeMillis()) } }
            main.post {
                if (activity.isFinishing) {
                    seenDialogs.remove(activity)
                    return@post
                }
                result.onSuccess { contacts ->
                    showLoadedContactPicker(activity, source, contacts)
                }.onFailure {
                    seenDialogs.remove(activity)
                    toast(activity, "联系人列表不可用")
                }
            }
        }, "Hchat-VoiceForwardContacts").start()
    }

    private fun showLoadedContactPicker(activity: Activity, source: VoiceSource, contacts: List<ContactRow>) {
        if (contacts.isEmpty()) {
            seenDialogs.remove(activity)
            toast(activity, "没有可用联系人")
            return
        }
        VoiceForwardMiuixDialog.showContacts(
            activity = activity,
            contacts = contacts.map {
                VoiceForwardMiuixDialog.ContactItem(
                    id = it.id,
                    label = it.label,
                    group = it.group,
                    avatarUrl = it.avatarUrl,
                    avatarBackupUrl = it.avatarBackupUrl,
                    labels = it.labels,
                    searchAliases = it.searchAliases
                )
            },
            onConfirm = { targets ->
                sendVoiceToTargets(activity, source, targets)
            },
            onDismiss = { seenDialogs.remove(activity) }
        )
    }

    private fun showContactPicker(
        activity: Activity,
        sources: List<VoiceSource>,
        exitTarget: MultiSelectMessageUi.ExitTarget,
        onTargetsSelected: ((List<VoiceForwardMiuixDialog.ContactItem>) -> Unit)? = null
    ) {
        if (sources.isEmpty()) return
        if (seenDialogs.contains(activity)) return
        seenDialogs.add(activity)
        cachedContacts()?.let { cached ->
            showLoadedContactPicker(activity, sources, cached.contacts, exitTarget, onTargetsSelected)
            return
        }
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = runCatching { loadContacts().also { recentContactsCache = CachedContacts(it, System.currentTimeMillis()) } }
            main.post {
                if (activity.isFinishing) {
                    cleanupTemporarySources(sources)
                    seenDialogs.remove(activity)
                    return@post
                }
                result.onSuccess { contacts ->
                    showLoadedContactPicker(activity, sources, contacts, exitTarget, onTargetsSelected)
                }.onFailure {
                    cleanupTemporarySources(sources)
                    seenDialogs.remove(activity)
                    toast(activity, "联系人列表不可用")
                }
            }
        }, "Hchat-VoiceForwardContacts").start()
    }

    private fun showLoadedContactPicker(
        activity: Activity,
        sources: List<VoiceSource>,
        contacts: List<ContactRow>,
        exitTarget: MultiSelectMessageUi.ExitTarget,
        onTargetsSelected: ((List<VoiceForwardMiuixDialog.ContactItem>) -> Unit)?
    ) {
        if (contacts.isEmpty()) {
            cleanupTemporarySources(sources)
            seenDialogs.remove(activity)
            toast(activity, "没有可用联系人")
            return
        }
        val confirmRequested = AtomicBoolean(false)
        VoiceForwardMiuixDialog.showContacts(
            activity = activity,
            contacts = contacts.map {
                VoiceForwardMiuixDialog.ContactItem(
                    id = it.id,
                    label = it.label,
                    group = it.group,
                    avatarUrl = it.avatarUrl,
                    avatarBackupUrl = it.avatarBackupUrl,
                    labels = it.labels,
                    searchAliases = it.searchAliases
                )
            },
            onConfirm = { targets ->
                if (onTargetsSelected != null) {
                    onTargetsSelected(targets)
                } else {
                    sendVoicesToTargets(activity, sources, targets)
                    exitTarget.exit(logger)
                }
            },
            onDismiss = {
                if (!confirmRequested.get()) cleanupTemporarySources(sources)
                seenDialogs.remove(activity)
            },
            onConfirmRequest = { confirmRequested.set(true) }
        )
    }

    private fun sendVoiceToTargets(
        activity: Activity,
        source: VoiceSource,
        targets: List<VoiceForwardMiuixDialog.ContactItem>
    ) {
        if (targets.isEmpty()) return
        val main = Handler(Looper.getMainLooper())
        forwardExecutor.execute {
            var success = 0
            targets.forEachIndexed { index, target ->
                if (sendVoiceForForward(source, target.id)) {
                    success++
                }
                if (index < targets.lastIndex && !waitBetweenForwards()) return@execute
            }
            main.post {
                if (!activity.isFinishing) {
                    val message = if (targets.size == 1) {
                        if (success == 1) "语音转发成功" else "语音转发失败"
                    } else {
                        "语音转发完成: $success/${targets.size}"
                    }
                    toast(activity, message)
                }
            }
        }
    }

    private fun sendVoicesToTargets(
        activity: Activity,
        sources: List<VoiceSource>,
        targets: List<VoiceForwardMiuixDialog.ContactItem>
    ) {
        if (sources.isEmpty()) return
        if (targets.isEmpty()) {
            cleanupTemporarySources(sources)
            return
        }
        val main = Handler(Looper.getMainLooper())
        forwardExecutor.execute {
            try {
                var success = 0
                val total = targets.size * sources.size
                var sentCount = 0
                targets.forEach { target ->
                    sources.forEach { source ->
                        if (sendVoiceForForward(source, target.id)) {
                            success++
                        }
                        sentCount++
                        if (sentCount < total && !waitBetweenForwards()) return@execute
                    }
                }
                main.post {
                    if (!activity.isFinishing) {
                        toast(activity, "语音转发完成: $success/$total")
                    }
                }
            } finally {
                cleanupTemporarySources(sources)
            }
        }
    }

    private fun cleanupTemporarySources(sources: Collection<VoiceSource>) {
        sources.asSequence()
            .filter { it.deleteAfterUse }
            .map { it.path }
            .distinct()
            .forEach { path -> runCatching { File(path).delete() } }
    }

    private fun deleteVoiceFiles(sources: Collection<VoiceSource>) {
        sources.asSequence()
            .map { it.path }
            .distinct()
            .forEach { path -> runCatching { File(path).delete() } }
    }

    private fun sendVoiceForForward(source: VoiceSource, targetId: String): Boolean {
        val voiceApi = WeChatApis.media()?.voices() ?: return false
        if (!voiceApi.canSendSilently() || !File(source.path).isFile) return false
        return runCatching {
            voiceApi.send(targetId, source.path, source.durationMillis)
        }.getOrElse {
            logger("语音转发发送异常", it)
            false
        }
    }

    private fun waitBetweenForwards(): Boolean {
        return try {
            TimeUnit.MILLISECONDS.sleep(ForwardSendPolicy.MIN_SEND_INTERVAL_MS)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun shutdown() {
        favoriteBindingsByMenuItem.clear()
        favoriteItemsByOwner.clear()
        favoriteItemsByActivity.clear()
        favoriteVoiceSourcesByOwner.clear()
        favoriteVoiceSourcesByActivity.clear()
        favoriteSelectorFilters.clear()
        favoriteFilterClasses.clear()
        lastFavoriteItem = null
        lastFavoriteVoiceSource = null
        forwardExecutor.shutdownNow()
    }

    private fun saveVoice(activity: Activity, source: VoiceSource) {
        Thread({
            val result = runCatching {
                val target = buildSaveFile()
                    ?: return@runCatching SaveResult(false, null, "创建保存目录失败")
                val ok = saveAsMp3(File(source.path), target)
                SaveResult(ok, target.takeIf { ok }, if (ok) "" else "语音转 MP3 失败")
            }.getOrElse {
                logger("保存语音失败", it)
                SaveResult(false, null, "语音保存失败")
            }
            toast(
                activity,
                if (result.success && result.file != null) {
                    "语音已保存: ${result.file.absolutePath}"
                } else {
                    result.error.ifBlank { "语音保存失败" }
                }
            )
        }, "Hchat-VoiceSave").start()
    }

    private fun preparePreviewVoice(source: VoiceSource): String? {
        return runCatching {
            val target = File(File(hchatMediaRoot(), "Cache"), "favorite_voice_preview.mp3")
            if (saveAsMp3(File(source.path), target)) target.absolutePath else null
        }.getOrElse {
            logger("收藏语音预览转码失败", it)
            null
        }
    }

    private fun buildSaveFile(): File? {
        val dir = File(hchatMediaRoot(), "Voice")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "Hchat_voice_$time.mp3")
    }

    private fun hchatMediaRoot(): File {
        val appContext = context.hostContext().applicationContext ?: context.hostContext()
        val mediaRoot = runCatching {
            appContext.externalMediaDirs?.firstOrNull { it != null }
        }.getOrNull()
        return File(mediaRoot ?: File("/storage/emulated/0/Android/media/${appContext.packageName}"), "Hchat")
    }

    private fun saveAsMp3(source: File, target: File): Boolean {
        if (!source.isFile) return false
        return runCatching {
            target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            if (isMp3(source)) {
                copyFile(source, target)
            } else {
                val code = silkCodec.silkToMp3(source.absolutePath, target.absolutePath, SAVE_MP3_HZ)
                if (code != 0 || !target.isFile || target.length() <= 0L) {
                    target.delete()
                    false
                } else {
                    true
                }
            }
        }.getOrElse {
            logger("语音转 MP3 失败", it)
            target.delete()
            false
        }
    }

    private fun mergeVoiceSources(sources: List<VoiceSource>, saveOutput: Boolean): VoiceSource? {
        if (sources.size < 2) return null
        val cacheRoot = File(hchatMediaRoot(), "Cache")
        if (!cacheRoot.isDirectory && !cacheRoot.mkdirs()) return null
        val target = if (saveOutput) {
            buildMergedSaveFile() ?: return null
        } else {
            File(cacheRoot, "Hchat_merged_voice_${System.currentTimeMillis()}.silk")
        }
        val workDir = File(cacheRoot, "voice_merge_${System.currentTimeMillis()}_${Thread.currentThread().id}")
        if (!workDir.mkdirs()) {
            target.delete()
            return null
        }
        var keepTarget = false
        try {
            val mergedPcm = File(workDir, "merged.pcm")
            sources.forEachIndexed { index, source ->
                val pcm = File(workDir, "part_$index.pcm")
                if (!decodeVoiceToPcm(File(source.path), pcm, workDir)) {
                    throw IllegalStateException("第 ${index + 1} 条语音解码失败")
                }
                if (!appendFile(pcm, mergedPcm)) {
                    throw IllegalStateException("第 ${index + 1} 条语音拼接失败")
                }
            }
            val mergedSilk = File(workDir, "merged.silk")
            val silkCode = silkCodec.pcmToSilk(
                mergedPcm.absolutePath,
                mergedSilk.absolutePath,
                SAVE_MP3_HZ,
                SAVE_MP3_HZ,
                1
            )
            if (silkCode != 0 || !mergedSilk.isFile || mergedSilk.length() <= 0L) {
                throw IllegalStateException("PCM 转 Silk 失败: $silkCode")
            }
            if (saveOutput) {
                val mp3Code = silkCodec.silkToMp3(
                    mergedSilk.absolutePath,
                    target.absolutePath,
                    SAVE_MP3_HZ
                )
                if (mp3Code != 0 || !target.isFile || target.length() <= 0L) {
                    throw IllegalStateException("Silk 转 MP3 失败: $mp3Code")
                }
            } else if (!copyFile(mergedSilk, target) || !target.isFile || target.length() <= 0L) {
                throw IllegalStateException("保存合并语音缓存失败")
            }
            keepTarget = true
            val detectedDuration = runCatching { silkCodec.getDuration(mergedSilk.absolutePath) }
                .getOrDefault(0L)
            val sourceDuration = sources.sumOf { it.durationMillis.toLong().coerceAtLeast(1L) }
            val duration = (if (detectedDuration > 0L) detectedDuration else sourceDuration)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
            return VoiceSource(target.absolutePath, duration, deleteAfterUse = !saveOutput)
        } finally {
            workDir.deleteRecursively()
            if (!keepTarget) target.delete()
        }
    }

    private fun decodeVoiceToPcm(source: File, target: File, workDir: File): Boolean {
        if (!source.isFile) return false
        val fileType = runCatching { silkCodec.getFileType(source.absolutePath) }.getOrDefault(0)
        val code = if (fileType == FILE_TYPE_SILK) {
            silkCodec.silkToPcm(source.absolutePath, target.absolutePath, SAVE_MP3_HZ)
        } else {
            val normalizedSilk = File(workDir, "${target.nameWithoutExtension}.silk")
            val normalizeCode = AacCodec.autoToSilkCompat(
                source.absolutePath,
                normalizedSilk.absolutePath,
                silkCodec,
                SAVE_MP3_HZ
            )
            if (normalizeCode != 0) normalizeCode
            else silkCodec.silkToPcm(normalizedSilk.absolutePath, target.absolutePath, SAVE_MP3_HZ)
        }
        return code == 0 && target.isFile && target.length() > 0L
    }

    private fun buildMergedSaveFile(): File? {
        val dir = File(hchatMediaRoot(), "Voice")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "Hchat_merged_voice_$time.mp3")
    }

    private fun appendFile(source: File, target: File): Boolean {
        if (!source.isFile) return false
        return runCatching {
            target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(target, true).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun isMp3(source: File): Boolean {
        if (source.extension.equals("mp3", ignoreCase = true)) return true
        return runCatching { silkCodec.getFileType(source.absolutePath) == 2 }.getOrDefault(false)
    }

    private fun copyFile(source: File, target: File): Boolean {
        if (!source.isFile) return false
        return runCatching {
            target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            FileInputStream(source).use { input ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun loadContacts(): List<ContactRow> {
        val api = WeChatApis.contact().contacts() ?: return emptyList()
        if (!api.isAvailable) return emptyList()
        val rows = ArrayList<ContactRow>()
        val labelsByUser = linkedMapOf<String, MutableList<String>>()
        runCatching { api.getContactLabelList() }.getOrDefault(emptyList()).forEach { label ->
            val labelName = label.labelName.ifBlank { label.labelId }
            if (labelName.isBlank()) return@forEach
            label.userNameList.forEach { wxId ->
                if (wxId.isNotBlank()) {
                    labelsByUser.getOrPut(wxId) { arrayListOf() }.add(labelName)
                }
            }
        }
        rows += api.getPickerContacts().mapNotNull { it.toRow(false, labelsByUser[it.wxId].orEmpty()) }
        rows += api.getPickerGroups().mapNotNull { it.toRow(true, emptyList()) }
        val conversationOrder = WeChatApis.conversations()
            ?.getRecentConversationUsernames(10000)
            .orEmpty()
            .mapIndexed { index, username -> username to index }
            .toMap()
        return rows.distinctBy { it.id }.sortedWith(
            compareBy<ContactRow> { conversationOrder[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.group }
                .thenBy { it.label.lowercase(Locale.US) }
        )
    }

    private fun cachedContacts(): CachedContacts? {
        val cached = recentContactsCache ?: return null
        if (!cached.isValid()) {
            recentContactsCache = null
            return null
        }
        return cached
    }

    private fun WeChatContact?.toRow(group: Boolean, labels: List<String>): ContactRow? {
        if (this == null || wxId.isBlank()) return null
        return ContactRow(
            id = wxId,
            label = pickerDisplayName(group),
            group = group,
            avatarUrl = avatarUrl,
            avatarBackupUrl = avatarBackupUrl,
            labels = labels.distinct(),
            searchAliases = listOf(remarkName, nickname, customWxId)
                .filter { it.isNotBlank() }
                .distinct()
        )
    }

    private fun toast(activity: Activity?, text: String) {
        val target = activity ?: WeChatApis.currentActivity()?.currentActivity()
        if (target == null) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(target, text, Toast.LENGTH_SHORT).show()
        }
    }

    private data class ContactRow(
        val id: String,
        val label: String,
        val group: Boolean,
        val avatarUrl: String,
        val avatarBackupUrl: String,
        val labels: List<String>,
        val searchAliases: List<String>
    )

    private data class CachedContacts(
        val contacts: List<ContactRow>,
        val cachedAt: Long
    ) {
        fun isValid(): Boolean = contacts.isNotEmpty() && System.currentTimeMillis() - cachedAt <= 60_000L
    }

    private data class VoiceSource(
        val path: String,
        val durationMillis: Int,
        val deleteAfterUse: Boolean = false
    )
    private data class SaveResult(val success: Boolean, val file: File?, val error: String)

    companion object {
        private val FAVORITE_SEARCH_UI_NAMES = setOf(
            "com.tencent.mm.plugin.fav.ui.FavSearchUI",
            "com.tencent.mm.plugin.fav.ui.FavFilterUI"
        )
        private const val FAVORITE_TOP_SEARCH_CLICK_ANCHOR =
            "FavTopSearchUIC\$initOnItemClickListener\$1"
        private const val FAVORITE_SELECT_UI_CLASS = "com.tencent.mm.plugin.fav.ui.FavSelectUI"
        private val FAVORITE_SELECTOR_UI_NAMES = setOf(
            FAVORITE_SELECT_UI_CLASS,
            "com.tencent.mm.plugin.fav.ui.FavSearchUI",
            "com.tencent.mm.plugin.fav.ui.FavFilterUI"
        )
        private const val DEFAULT_VOICE_DURATION_MS = 1000
        private const val MULTI_VOICE_NATIVE_FORWARD_ID = 0x4843564d
        private const val MULTI_VOICE_NATIVE_FORWARD_TITLE = "逐条转发语音[H]"
        private const val MULTI_VOICE_MERGE_ID = 0x4843564e
        private const val MULTI_VOICE_MERGE_TITLE = "合并语音[H]"
        private const val MENU_FORWARD_ID = 0x48435646
        private const val MENU_SAVE_ID = 0x48435653
        private const val MENU_FORWARD_TITLE = "转发[H]"
        private const val MENU_SAVE_TITLE = "保存[H]"
        private const val SAVE_MP3_HZ = 24000
        private const val FILE_TYPE_SILK = 1
        private const val FAVORITE_VOICE_TYPE = 3
    }
}
