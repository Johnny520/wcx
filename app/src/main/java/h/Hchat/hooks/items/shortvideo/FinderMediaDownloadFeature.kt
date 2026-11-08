package h.Hchat.hooks.items.shortvideo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.ContextMenu
import android.view.MenuItem
import android.widget.Toast
import h.Hchat.R
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class FinderMediaDownloadFeature : BaseFeature() {
    private var hooker: FinderMediaDownloadHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "视频号媒体下载"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(FinderMediaDownloadSettingsProvider())
    }

    override fun isEnabled(context: FeatureContext): Boolean {
        val sp = HchatStorage.preferences(context.hostContext(), FinderMediaDownloadSettings.PREFS_NAME)
        return sp.getBoolean(FinderMediaDownloadSettings.KEY_ENABLE, FinderMediaDownloadSettings.DEFAULT_ENABLE)
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = FinderMediaDownloadHooker(context, ::logError)
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) {
            scheduleInstall(context)
        }
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule(
            "shared:finder_feed_detail",
            "视频号详情解析",
            stage = DexInstallScheduler.Stage.WARMUP
        ) {
            FinderMediaDownloadSupport.install(context)
        }
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    companion object {
        const val ID = "finder_media_download"
    }
}

private class FinderMediaDownloadHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_finder_media_download_method_cache")
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())

    fun install(): Boolean {
        var createHooked = 0
        var clickHooked = 0
        locateCreateMenuMethods().forEach { if (hookCreateMenu(it)) createHooked++ }
        locateClickMethods().forEach { if (hookClick(it)) clickHooked++ }
        if (createHooked <= 0 || clickHooked <= 0) {
            logger("视频号媒体下载Hook未安装", null)
        }
        return createHooked > 0 && clickHooked > 0
    }

    private fun hookCreateMenu(method: Method): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    findMenuObject(param.args)?.let { addMenuItems(it) }
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("视频号菜单创建Hook失败", it)
            false
        }
    }

    private fun hookClick(method: Method): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleMenuClick(param)
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("视频号菜单点击Hook失败", it)
            false
        }
    }

    private fun addMenuItems(menu: Any) {
        runCatching {
            addFinderMenuItem(
                menu,
                MENU_COPY_LINK,
                "复制链接",
                "icons_filled_link",
                R.drawable.ic_hchat_copy_link
            )
            addFinderMenuItem(
                menu,
                MENU_DOWNLOAD,
                "下载视频",
                "icons_filled_download",
                R.drawable.ic_hchat_download_video
            )
        }.onFailure {
            logger("视频号菜单注入失败", it)
        }
    }

    private fun addFinderMenuItem(
        menu: Any,
        id: Int,
        text: String,
        nativeIconName: String,
        fallbackIconRes: Int
    ) {
        if (findExistingItem(menu, id) != null) return
        val nativeIconRes = menuIconResId(nativeIconName)
        if (nativeIconRes != 0 && addNativeIconMenuItem(menu, id, text, nativeIconRes)) return
        val icon = moduleDrawable(fallbackIconRes)
        val customAdd = methodsRecursive(menu.javaClass).firstOrNull { method ->
            method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == Integer.TYPE &&
                CharSequence::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                Drawable::class.java.isAssignableFrom(method.parameterTypes[2])
        }
        if (KavaReflector.invokeSuccessfully(customAdd, menu, id, text, icon)) {
            findExistingItem(menu, id)?.let { runCatching { it.isEnabled = true } }
            return
        }
        val added = if (menu is ContextMenu) {
            menu.add(0, id, 0, text)
        } else {
            KavaReflector.invokeMethod(menu, "add", 0, id, 0, text)
                ?: KavaReflector.invokeMethod(menu, "add", 0, id, 0, text as CharSequence)
        }
        if (added is MenuItem) {
            runCatching { added.isEnabled = true }
            if (icon != null) runCatching { added.setIcon(icon) }
            return
        }
        if (added != null) return
        KavaReflector.invokeMethod(menu, "f", id, text)
            ?: KavaReflector.invokeMethod(menu, "f", id, text as CharSequence)
    }

    private fun addNativeIconMenuItem(menu: Any, id: Int, text: String, iconRes: Int): Boolean {
        val iconOnlyMethod = methodsRecursive(menu.javaClass).firstOrNull { method ->
            val types = method.parameterTypes
            MenuItem::class.java.isAssignableFrom(method.returnType) &&
                types.size == 3 &&
                types[0] == Integer.TYPE &&
                CharSequence::class.java.isAssignableFrom(types[1]) &&
                types[2] == Integer.TYPE
        }
        if (KavaReflector.invokeSuccessfully(iconOnlyMethod, menu, id, text, iconRes)) return true

        val iconColorStateMethod = methodsRecursive(menu.javaClass).firstOrNull { method ->
            val types = method.parameterTypes
            MenuItem::class.java.isAssignableFrom(method.returnType) &&
                types.size == 5 &&
                types[0] == Integer.TYPE &&
                CharSequence::class.java.isAssignableFrom(types[1]) &&
                types[2] == Integer.TYPE &&
                types[3] == Integer.TYPE &&
                types[4] == java.lang.Boolean.TYPE
        }
        return KavaReflector.invokeSuccessfully(
            iconColorStateMethod,
            menu,
            id,
            text,
            iconRes,
            0,
            false
        )
    }

    private fun menuIconResId(iconName: String): Int {
        val iconContext = h.Hchat.hooks.api.core.WeChatApis.currentActivity()?.currentActivity()
            ?: context.hostContext()
        val resources = iconContext.resources
        val packageName = iconContext.packageName
        for (type in arrayOf("raw", "drawable")) {
            val id = resources.getIdentifier(iconName, type, packageName)
            if (id != 0) return id
        }
        return 0
    }

    private fun moduleDrawable(resId: Int): Drawable? {
        return runCatching {
            val size = (32f * context.hostContext().resources.displayMetrics.density + 0.5f).toInt()
            val source = context.moduleContext().getDrawable(resId)?.mutate() ?: return@runCatching null
            source.setTint(Color.rgb(35, 35, 35))
            source.setBounds(0, 0, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            source.draw(Canvas(bitmap))
            BitmapDrawable(context.hostContext().resources, bitmap).apply {
                setBounds(0, 0, size, size)
            }
        }.getOrNull()
    }

    private fun findExistingItem(menu: Any, id: Int): MenuItem? {
        (KavaReflector.invokeMethod(menu, "findItem", id) as? MenuItem)?.let { return it }
        if (menu is ContextMenu) {
            for (i in 0 until menu.size()) {
                val item = menu.getItem(i)
                if (item.itemId == id) return item
            }
        }
        return null
    }

    private fun methodsRecursive(clazz: Class<*>?): List<Method> {
        val methods = ArrayList<Method>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            methods += KavaReflector.declaredMethods(current)
            current = current.superclass
        }
        return methods
    }

    private fun handleMenuClick(param: XC_MethodHook.MethodHookParam) {
        val item = param.args?.firstOrNull { it is MenuItem } as? MenuItem ?: return
        if (item.itemId != MENU_COPY_LINK && item.itemId != MENU_DOWNLOAD) return
        val media = findFinderMedia(param) ?: run {
            toast("未找到视频号媒体")
            param.result = null
            return
        }
        if (item.itemId == MENU_COPY_LINK) {
            copyMediaLinks(media)
        } else {
            downloadMedia(media)
        }
        param.result = null
    }

    private fun copyMediaLinks(media: FinderMediaDownloadSupport.FinderMedia) {
        val text = FinderMediaDownloadSupport.mediaLinks(media)
        if (text.isBlank()) {
            toast("未知的媒体类型，无法复制")
            return
        }
        val clipboard = context.hostContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Hchat Finder Media", text))
        toast("已复制")
    }

    private fun downloadMedia(media: FinderMediaDownloadSupport.FinderMedia) {
        val threadName = when (media.type) {
            FinderMediaDownloadSupport.MEDIA_TYPE_IMAGE -> "Hchat-FinderImageDownload"
            FinderMediaDownloadSupport.MEDIA_TYPE_VIDEO -> "Hchat-FinderVideoDownload"
            else -> {
                toast("未知的媒体类型，无法下载")
                return
            }
        }
        Thread({
            when (media.type) {
                FinderMediaDownloadSupport.MEDIA_TYPE_IMAGE -> {
                    val files = FinderMediaDownloadSupport.downloadAllImages(
                        context.hostContext(),
                        media,
                        null
                    )
                    toast(if (files.isNotEmpty()) "已下载 ${files.size} 张图片到 Hchat/Finder" else "图片下载失败")
                }
                FinderMediaDownloadSupport.MEDIA_TYPE_VIDEO -> {
                    val file = FinderMediaDownloadSupport.downloadItem(
                        context.hostContext(),
                        media,
                        0,
                        null
                    )
                    toast(if (file != null) "已下载视频到 Hchat/Finder" else "视频下载失败")
                }
            }
        }, threadName).start()
    }

    private fun findFinderMedia(
        param: XC_MethodHook.MethodHookParam
    ): FinderMediaDownloadSupport.FinderMedia? {
        param.args?.forEach { arg ->
            if (arg !is MenuItem) {
                FinderMediaDownloadSupport.extractMedia(arg)
                    ?.takeIf { it.items.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return findFinderMediaFromOwner(param.thisObject)
    }

    private fun findFinderMediaFromOwner(owner: Any?): FinderMediaDownloadSupport.FinderMedia? {
        if (owner == null) return null
        FinderMediaDownloadSupport.extractMedia(owner)
            ?.takeIf { it.items.isNotEmpty() }
            ?.let { return it }
        val directValues = directFieldValues(owner)
        directValues.forEach { value ->
            FinderMediaDownloadSupport.extractMedia(value)
                ?.takeIf { it.items.isNotEmpty() }
                ?.let { return it }
        }
        directValues.forEach { holder ->
            if (shouldSkipFieldType(holder.javaClass)) return@forEach
            directFieldValues(holder).forEach { value ->
                FinderMediaDownloadSupport.extractMedia(value)
                    ?.takeIf { it.items.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun directFieldValues(source: Any): List<Any> {
        val values = ArrayList<Any>()
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).forEach { field ->
                if (shouldSkipFieldType(field.type)) return@forEach
                KavaReflector.readField(field, source)?.let(values::add)
            }
            current = current.superclass
        }
        return values
    }

    private fun shouldSkipFieldType(type: Class<*>): Boolean {
        if (type.isPrimitive || type.isEnum || type.isArray) return true
        val name = type.name
        return name.startsWith("java.lang.") ||
            name.startsWith("java.util.") ||
            name.startsWith("android.") ||
            name.startsWith("kotlin.")
    }

    private fun findMenuObject(args: Array<Any?>?): Any? {
        args ?: return null
        args.firstOrNull { it is ContextMenu }?.let { return it }
        return args.firstOrNull(::isMenuLikeObject)
    }

    private fun isMenuLikeObject(value: Any?): Boolean {
        value ?: return false
        val methods = methodsRecursive(value.javaClass)
        return methods.any {
            it.name == "findItem" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == Integer.TYPE
        } && methods.any { method ->
            val types = method.parameterTypes
            (method.name == "add" && types.size >= 4) ||
                (method.name == "f" && types.size >= 2) ||
                (types.size == 5 &&
                    types[0] == Integer.TYPE &&
                    types[1].isAssignableFrom(String::class.java))
        }
    }

    private fun locateCreateMenuMethods(): List<Method> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.loadList(
            methodPrefs,
            methodCacheKey,
            context.hostClassLoader(),
            MENU_CREATE_CACHE
        )
            .filter { isCreateMenuMethod(it) }
        if (cached.isNotEmpty()) return cached
        val methods = linkedSetOf<Method>()
        methods += findWeKitMethods("onCreateMMMenu", "pos is error ").filter { isCreateMenuMethod(it) }
        methods += findMethods(
            null,
            "feed",
            "menu",
            "sheet",
            "holder",
            "KEY_FINDER_SELF_FLAG"
        ).filter { isCreateMenuMethod(it) }
        methods += findWeKitMethods(
            "onCreateMMMenu",
            "getCreateSecondMoreMenuListener: username="
        ).filter { isCreateMenuMethod(it) }
        methods += findWeKitMethods(
            "onCreateMMMenu",
            "ref_eid",
            "tridot",
            "delete",
            "forward"
        ).filter { isCreateMenuMethod(it) }
        saveOrClear(methodCacheKey, MENU_CREATE_CACHE, methods.toList())
        return methods.toList()
    }

    private fun locateClickMethods(): List<Method> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.loadList(
            methodPrefs,
            methodCacheKey,
            context.hostClassLoader(),
            MENU_CLICK_CACHE
        )
            .filter { isClickMethod(it) }
        if (cached.isNotEmpty()) return cached
        val methods = linkedSetOf<Method>()
        methods += findWeKitMethods(
            "onMMMenuItemSelected",
            "[getMoreMenuItemSelectedListener] feed "
        ).filter { isClickMethod(it) }
        methods += findMethods(
            null,
            "getMoreMenuItemSelectedListener feed "
        ).filter { isClickMethod(it) }
        methods += findWeKitMethods(
            "onMMMenuItemSelected",
            "button_speedplay",
            "ref_eid"
        ).filter { isClickMethod(it) }
        saveOrClear(methodCacheKey, MENU_CLICK_CACHE, methods.toList())
        return methods.toList()
    }

    private fun findMethods(methodName: String?, vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            if (methodName != null) name(methodName)
                            usingStrings(strings.toList())
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.distinctBy { it.toGenericString() }
        }.getOrElse {
            logger("视频号菜单DexKit定位失败", it)
            emptyList()
        }
    }

    private fun findWeKitMethods(methodName: String, vararg strings: String): List<Method> {
        val precise = findMethods(methodName, *strings)
        return precise.ifEmpty { findMethods(null, *strings) }
    }

    private fun isCreateMenuMethod(method: Method): Boolean {
        return method.parameterTypes.any { ContextMenu::class.java.isAssignableFrom(it) } ||
            (method.name == "onCreateMMMenu" && method.parameterTypes.isNotEmpty())
    }

    private fun isClickMethod(method: Method): Boolean {
        return method.parameterTypes.any { MenuItem::class.java.isAssignableFrom(it) }
    }

    private fun saveOrClear(methodCacheKey: String, name: String, methods: List<Method>) {
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, methodCacheKey, name, methods)
        } else {
            DexMethodCache.clear(methodPrefs, methodCacheKey, name)
        }
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun toast(message: String) {
        val tasks = h.Hchat.hooks.api.core.WeChatApis.runtime().tasks()
        if (tasks != null) {
            tasks.runOnMain(Runnable {
                Toast.makeText(context.hostContext(), message, Toast.LENGTH_SHORT).show()
            })
        } else {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context.hostContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val MENU_COPY_LINK = 0x48434601
        private const val MENU_DOWNLOAD = 0x48434602
        private const val MENU_CREATE_CACHE = "finder_menu_create_v2"
        private const val MENU_CLICK_CACHE = "finder_menu_click_v2"
    }
}
