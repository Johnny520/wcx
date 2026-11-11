package h.Hchat.hooks.items.emojisave

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.SingleMessageMenuLocator
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class EmojiSaveFeature : BaseFeature() {
    private var runtime: EmojiSaveRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "保存表情"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(EmojiSaveSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = EmojiSaveRuntime(context, ::logFeatureError)
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
        const val ID = "emoji_save"
    }
}

private class EmojiSaveRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), EmojiSaveSettings.PREFS_NAME)
    private val main = Handler(Looper.getMainLooper())
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, Any>())

    @Synchronized
    fun install(): Boolean {
        val createHooked = SingleMessageMenuLocator.menuCreateMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addSaveMenu(param)
                }
            })
        }
        val clickHooked = SingleMessageMenuLocator.menuClickMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleSaveClick(param)
                }
            })
        }
        if (createHooked <= 0) logger("保存表情菜单创建 Hook 未安装", null)
        if (clickHooked <= 0) logger("保存表情菜单点击 Hook 未安装", null)
        return createHooked > 0 && clickHooked > 0
    }

    fun destroy() {
        bindings.clear()
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("保存表情菜单 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addSaveMenu(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val args = param.args ?: return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View ?: return
        val message = resolveNativeMessage(view.tag) ?: resolveNativeMessage(args) ?: return
        if (!isEmojiMessage(message)) return
        val item = addMenuItem(menu, view) ?: return
        bindings[item] = message
    }

    private fun handleSaveClick(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != MENU_ITEM_ID) return
        val activity = WeChatApis.currentActivity()?.currentActivity()
        val message = bindings.remove(item) ?: resolveNativeMessage(param.args)
        val source = message?.let(::emojiSource).orEmpty()
        if (source.isBlank()) {
            toast(activity, "表情消息不可用")
            return
        }
        saveEmoji(activity, source)
    }

    private fun saveEmoji(activity: Activity?, source: String) {
        Thread({
            val result = runCatching {
                val data = WeChatApis.media()?.emojis()?.decodeData(source)
                if (data == null || data.isEmpty()) {
                    return@runCatching SaveResult(null, "表情文件不存在或尚未下载")
                }
                val target = buildSaveFile(data)
                    ?: return@runCatching SaveResult(null, "创建保存目录失败")
                if (!writeFile(data, target)) {
                    return@runCatching SaveResult(null, "表情保存失败")
                }
                SaveResult(target, "")
            }.getOrElse {
                logger("保存表情失败", it)
                SaveResult(null, "表情保存失败")
            }
            toast(
                activity,
                result.file?.let { "表情已保存: ${it.absolutePath}" }
                    ?: result.error.ifBlank { "表情保存失败" }
            )
        }, "Hchat-EmojiSave").start()
    }

    private fun buildSaveFile(data: ByteArray): File? {
        val dir = File(hchatMediaRoot(), "Emoji")
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val time = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(dir, "Hchat_emoji_$time${fileExtension(data)}")
    }

    private fun hchatMediaRoot(): File {
        val appContext = context.hostContext().applicationContext ?: context.hostContext()
        val mediaRoot = runCatching {
            appContext.externalMediaDirs?.firstOrNull { it != null }
        }.getOrNull()
        return File(mediaRoot ?: File("/storage/emulated/0/Android/media/${appContext.packageName}"), "Hchat")
    }

    private fun fileExtension(data: ByteArray): String {
        val count = data.size
        return when {
            count >= 6 && String(data, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> ".gif"
            count >= 8 && data.copyOfRange(0, 8).contentEquals(PNG_HEADER) -> ".png"
            count >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte() -> ".jpg"
            count >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> ".webp"
            else -> ".bin"
        }
    }

    private fun writeFile(data: ByteArray, target: File): Boolean {
        return runCatching {
            FileOutputStream(target, false).use { output -> output.write(data) }
            target.isFile && target.length() == data.size.toLong()
        }.getOrElse {
            target.delete()
            false
        }
    }

    private fun emojiSource(message: Any): String {
        val imagePath = readString(message, "getImgPath", "field_imgPath", "imgPath").trim()
        if (MD5_REGEX.matches(imagePath)) return imagePath
        val content = readString(message, "getContent", "field_content", "content")
        val contentMd5 = WeChatMessage.xmlAttr(content, "md5")
            .ifBlank { WeChatMessage.xmlTag(content, "md5") }
            .trim()
            .takeIf { MD5_REGEX.matches(it) }
            .orEmpty()
        return contentMd5.ifBlank { imagePath.takeIf { File(it).isFile }.orEmpty() }
    }

    private fun isEmojiMessage(message: Any): Boolean {
        val type = readNumber(message, "getType", "field_type", "type")?.toInt() ?: return false
        return type and 0xffff == 47
    }

    private fun readString(source: Any, getter: String, field: String, fallback: String): String {
        return KavaReflector.invokeMethod(source, getter)?.toString()
            ?: KavaReflector.readField(source, field)?.toString()
            ?: KavaReflector.readField(source, fallback)?.toString()
            ?: ""
    }

    private fun readNumber(source: Any, getter: String, field: String, fallback: String): Number? {
        return KavaReflector.invokeMethod(source, getter) as? Number
            ?: KavaReflector.readField(source, field) as? Number
            ?: KavaReflector.readField(source, fallback) as? Number
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return resolveNativeMessage(source, visited, 0)
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 5 || !visited.add(source)) return null
        val messageId = readNumber(source, "getMsgId", "field_msgId", "msgId")
            ?: KavaReflector.invokeMethod(source, "getMsgID") as? Number
        if (source.javaClass.name.startsWith("com.tencent.mm.storage.") && messageId?.toLong()?.let { it > 0L } == true) {
            return source
        }
        if (source is View) return resolveNativeMessage(source.tag, visited, depth + 1)
        if (source is Array<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { result -> return result } }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { result -> return result } }
            return null
        }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (field.type.isPrimitive || field.type == String::class.java) continue
                val value = KavaReflector.readField(field, source) ?: continue
                resolveNativeMessage(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun addMenuItem(menu: Any, view: View): MenuItem? {
        (KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) as? MenuItem)?.let { return it }
        val groupId = readMenuGroupId(menu)
        val iconRes = menuIconResId(view)
        if (iconRes != 0) {
            val method = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { candidate ->
                val types = candidate.parameterTypes
                candidate.name == "c" && types.size == 5 &&
                    types[0] == Integer.TYPE && types[1] == Integer.TYPE && types[2] == Integer.TYPE &&
                    types[3].isAssignableFrom(String::class.java) && types[4] == Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(method, menu, groupId, MENU_ITEM_ID, 0, MENU_TITLE, iconRes)) {
                return KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) as? MenuItem
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", groupId, MENU_ITEM_ID, 0, MENU_TITLE)
            ?: KavaReflector.invokeMethod(menu, "add", groupId, MENU_ITEM_ID, 0, MENU_TITLE as CharSequence)
        if (added is MenuItem) {
            if (iconRes != 0) runCatching { added.setIcon(iconRes) }
            return added
        }
        val fallback = KavaReflector.invokeMethod(menu, "f", MENU_ITEM_ID, MENU_TITLE)
            ?: KavaReflector.invokeMethod(menu, "f", MENU_ITEM_ID, MENU_TITLE as CharSequence)
        return (fallback as? MenuItem)
            ?: KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) as? MenuItem
    }

    private fun readMenuGroupId(menu: Any): Int {
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: 0
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            return item.groupId
        }
        return 0
    }

    private fun menuIconResId(view: View): Int {
        val resources = view.context.resources
        val packageName = view.context.packageName
        for (type in arrayOf("raw", "drawable")) {
            val id = resources.getIdentifier("icons_filled_download", type, packageName)
            if (id != 0) return id
        }
        return 0
    }

    private fun enabled(): Boolean {
        return prefs.getBoolean(EmojiSaveSettings.KEY_ENABLE, EmojiSaveSettings.DEFAULT_ENABLE)
    }

    private fun toast(activity: Activity?, message: String) {
        main.post {
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private data class SaveResult(val file: File?, val error: String)

    private companion object {
        const val MENU_ITEM_ID = 0x48434553
        const val MENU_TITLE = "保存[H]"
        val MD5_REGEX = Regex("[0-9a-fA-F]{32}")
        val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
    }
}
