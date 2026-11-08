package h.Hchat.hooks.items.voicepreview

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
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSnapshot
import h.Hchat.media.AudioTransformBridge
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VoicePreviewFeature : BaseFeature() {
    private var runtime: VoicePreviewRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "语音消息预览"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(VoicePreviewSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = VoicePreviewRuntime(context, ::logFeatureError)
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
        const val ID = "voice_preview"
    }
}

private class VoicePreviewRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class MenuMessageBinding(val msgId: Long, val nativeMessage: Any)

    private val prefs = HchatStorage.preferences(context.hostContext(), VoicePreviewSettings.PREFS_NAME)
    private val main = Handler(Looper.getMainLooper())
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val messagesByMenuItem = Collections.synchronizedMap(
        WeakHashMap<MenuItem, MenuMessageBinding>()
    )
    private val messagesByMenuGroup = ConcurrentHashMap<Int, MenuMessageBinding>()
    private val messageIdMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val audioTransform = AudioTransformBridge()
    @Volatile private var previewDialog: VoiceForwardMiuixDialog.DialogHandle? = null
    @Volatile private var previewFile: File? = null

    init {
        cacheDirectory().listFiles()
            ?.filter { it.name.startsWith(CACHE_PREFIX) }
            ?.forEach { it.delete() }
    }

    @Synchronized
    fun install(): Boolean {
        val createHooked = SingleMessageMenuLocator.menuCreateMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addPreviewMenu(param)
                }
            })
        }
        val clickHooked = SingleMessageMenuLocator.menuClickMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handlePreviewClick(param)
                }
            })
        }
        if (createHooked <= 0) logger("语音预览菜单创建 Hook 未安装", null)
        if (clickHooked <= 0) logger("语音预览菜单点击 Hook 未安装", null)
        return createHooked > 0 && clickHooked > 0
    }

    fun destroy() {
        clearMenuMessageBinding()
        main.post {
            previewDialog?.close()
            previewDialog = null
            previewFile?.delete()
            previewFile = null
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
            logger("语音预览菜单 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addPreviewMenu(param: XC_MethodHook.MethodHookParam) {
        clearMenuMessageBinding()
        if (!enabled()) return
        val args = param.args ?: return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View ?: return
        val message = resolveNativeMessage(view.tag) ?: return
        if (!isVoiceMessage(message)) return
        val item = addMenuItem(menu, view) ?: return
        val binding = MenuMessageBinding(messageId(message), message)
        if (binding.msgId <= 0L) return
        messagesByMenuItem[item] = binding
        messagesByMenuGroup[item.groupId] = binding
    }

    private fun handlePreviewClick(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != MENU_ITEM_ID) return
        val activity = WeChatApis.currentActivity()?.currentActivity()
        val binding = consumeMenuMessageBinding(item)
        if (activity == null || binding == null) {
            toast(activity, "语音消息不可用")
            return
        }
        main.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                showPreview(activity, binding.nativeMessage)
            }
        }
    }

    private fun showPreview(activity: Activity, message: Any) {
        val snapshot = SelectedMessageSnapshot.fromNative(message)
        if (snapshot == null || !WeChatMessageTypes.isVoice(snapshot.type)) {
            toast(activity, "语音文件不存在或尚未下载")
            return
        }
        val source = File(snapshot.voicePath)
        if (!source.isFile) {
            toast(activity, "语音文件不存在或尚未下载")
            return
        }

        previewDialog?.close()
        previewFile?.delete()
        val target = File(cacheDirectory(), "$CACHE_PREFIX${UUID.randomUUID()}.mp3")
        val dismissed = AtomicBoolean(false)
        lateinit var handle: VoiceForwardMiuixDialog.DialogHandle
        handle = VoiceForwardMiuixDialog.showVoicePreview(
            activity = activity,
            durationMillis = snapshot.voiceDurationMillis,
            preparePreview = {
                val prepared = preparePreview(source, target)
                if (dismissed.get()) {
                    target.delete()
                    null
                } else {
                    prepared
                }
            },
            onDismiss = {
                dismissed.set(true)
                target.delete()
                if (previewFile == target) previewFile = null
                if (previewDialog === handle) previewDialog = null
            }
        )
        if (!handle.isShowing()) {
            dismissed.set(true)
            target.delete()
            return
        }
        previewFile = target
        previewDialog = handle
    }

    private fun preparePreview(source: File, target: File): String? {
        return runCatching {
            target.parentFile?.takeIf { !it.isDirectory }?.mkdirs()
            val success = synchronized(audioTransform) {
                if (source.extension.equals("mp3", ignoreCase = true) ||
                    audioTransform.getFileType(source.absolutePath) == FILE_TYPE_MP3
                ) {
                    source.copyTo(target, overwrite = true)
                    true
                } else {
                    audioTransform.silkToMp3(
                        source.absolutePath,
                        target.absolutePath,
                        AudioTransformBridge.DEFAULT_HZ
                    ) == 0
                }
            }
            if (success && target.isFile && target.length() > 0L) {
                target.absolutePath
            } else {
                target.delete()
                null
            }
        }.getOrElse {
            target.delete()
            logger("语音消息预览转码失败", it)
            null
        }
    }

    private fun cacheDirectory(): File {
        return File(context.hostContext().cacheDir, "hchat_voice_preview").apply {
            if (!isDirectory) mkdirs()
        }
    }

    private fun isVoiceMessage(message: Any): Boolean {
        val type = readNumber(message, "getType", "field_type", "type")?.toInt() ?: return false
        return WeChatMessageTypes.isVoice(type)
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        val tag = if (source is View) source.tag else source
        if (tag == null) return null
        if (isNativeMessageClass(tag.javaClass) && messageId(tag) > 0L) return tag

        var current: Class<*>? = tag.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (KavaReflector.isStatic(field) || !isNativeMessageClass(field.type)) continue
                val value = KavaReflector.readField(field, tag) ?: continue
                if (messageId(value) > 0L) return value
            }
            current = current.superclass
        }

        current = tag.javaClass
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (KavaReflector.isStatic(method) || method.parameterTypes.isNotEmpty()) continue
                if (!isNativeMessageClass(method.returnType)) continue
                val value = KavaReflector.invoke(method, tag) ?: continue
                if (messageId(value) > 0L) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun isNativeMessageClass(clazz: Class<*>): Boolean {
        return clazz.name.startsWith("com.tencent.mm.storage.")
    }

    private fun messageId(message: Any): Long {
        messageIdMethodCache[message.javaClass]?.let { method ->
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        val method = KavaReflector.declaredMethods(message.javaClass).firstOrNull { candidate ->
            candidate.parameterTypes.isEmpty() &&
                candidate.name in setOf("getMsgId", "getMsgID", "getId") &&
                (candidate.returnType == java.lang.Long.TYPE ||
                    candidate.returnType == java.lang.Long::class.java)
        }
        if (method != null) {
            messageIdMethodCache.putIfAbsent(message.javaClass, method)
            (KavaReflector.invoke(method, message) as? Number)?.toLong()?.let { return it }
        }
        return readNumber(message, "getMsgId", "field_msgId", "msgId")?.toLong() ?: 0L
    }

    private fun clearMenuMessageBinding() {
        messagesByMenuItem.clear()
        messagesByMenuGroup.clear()
    }

    private fun consumeMenuMessageBinding(item: MenuItem): MenuMessageBinding? {
        val binding = messagesByMenuItem.remove(item) ?: messagesByMenuGroup.remove(item.groupId)
        clearMenuMessageBinding()
        return binding
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
            if (KavaReflector.invokeSuccessfully(
                    method,
                    menu,
                    groupId,
                    MENU_ITEM_ID,
                    0,
                    MENU_TITLE,
                    iconRes
                )
            ) {
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
        for (name in arrayOf("icons_filled_play", "icons_filled_volume_up")) {
            for (type in arrayOf("raw", "drawable")) {
                val id = resources.getIdentifier(name, type, packageName)
                if (id != 0) return id
            }
        }
        return 0
    }

    private fun readNumber(source: Any, getter: String, field: String, fallback: String): Number? {
        return KavaReflector.invokeMethod(source, getter) as? Number
            ?: KavaReflector.readField(source, field) as? Number
            ?: KavaReflector.readField(source, fallback) as? Number
    }

    private fun enabled(): Boolean {
        return prefs.getBoolean(VoicePreviewSettings.KEY_ENABLE, VoicePreviewSettings.DEFAULT_ENABLE)
    }

    private fun toast(activity: Activity?, message: String) {
        main.post {
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val MENU_ITEM_ID = 0x48435650
        const val MENU_TITLE = "预览语音[H]"
        const val CACHE_PREFIX = "hchat_voice_preview_"
        const val FILE_TYPE_MP3 = 2
    }
}
