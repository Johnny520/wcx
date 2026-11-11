package h.Hchat.hooks.items.textvoice

import android.app.Activity
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.SingleMessageMenuLocator
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.script.ScriptSendButtonHook
import h.Hchat.utils.KavaReflector
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class TextVoiceRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private enum class SendMode { CHINESE, ENGLISH }

    private data class TextPayload(val text: String)

    private data class SynthesisConfig(
        val engine: String,
        val voiceId: String,
        val ttsVoice: String,
        val speechRate: Float,
        val english: Boolean
    ) {
        val usesTts: Boolean get() = TextVoiceSettings.isTtsEngine(engine)
    }

    private val prefs = TextVoiceSettings.preferences(context.hostContext())
    private val main = Handler(Looper.getMainLooper())
    private val client = BiliTextVoiceClient()
    private val ttsSynthesizer = AndroidTtsFileSynthesizer(context.hostContext())
    private val active = AtomicBoolean(true)
    private val sendModes = ConcurrentHashMap<String, SendMode>()
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, TextPayload>())
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { runnable -> Thread(runnable, "Hchat-TextVoice").apply { isDaemon = true } }
    )
    private var player: MediaPlayer? = null
    private var playerFile: File? = null
    private var playerGeneration = 0L
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == TextVoiceSettings.KEY_SEND_ENABLE && !sendEnabled()) {
            sendModes.clear()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        cacheDirectory().listFiles()?.forEach { file ->
            if (file.name.startsWith(CACHE_PREFIX)) file.delete()
        }
    }

    @Synchronized
    fun installMessageMenu(): Boolean {
        val createHooked = SingleMessageMenuLocator.menuCreateMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addPlayMenu(param)
                }
            })
        }
        val clickHooked = SingleMessageMenuLocator.menuClickMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handlePlayClick(param)
                }
            })
        }
        if (createHooked <= 0) logger("文本转语音菜单创建 Hook 未安装", null)
        if (clickHooked <= 0) logger("文本转语音菜单点击 Hook 未安装", null)
        return createHooked > 0 && clickHooked > 0
    }

    fun handleSendButton(rawText: String): Boolean {
        if (!sendEnabled()) return false
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty().trim()
        val command = rawText.trim().lowercase()
        if (command == COMMAND || command == COMMAND_ENGLISH) {
            if (talker.isBlank()) {
                toast("当前聊天不可用")
                return true
            }
            val requested = if (command == COMMAND_ENGLISH) SendMode.ENGLISH else SendMode.CHINESE
            val previous = sendModes[talker]
            if (previous == requested || command == COMMAND && previous != null) {
                sendModes.remove(talker)
                toast("当前聊天文字转语音已关闭")
            } else {
                sendModes[talker] = requested
                toast(if (requested == SendMode.ENGLISH) "当前聊天英文转语音已开启" else "当前聊天文字转语音已开启")
            }
            return true
        }

        val mode = sendModes[talker] ?: return false
        if (rawText.isBlank()) return false
        enqueueSend(talker, rawText, synthesisConfig(mode))
        return true
    }

    fun destroy() {
        if (!active.compareAndSet(true, false)) return
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        sendModes.clear()
        bindings.clear()
        client.cancelAll()
        ttsSynthesizer.cancelAll()
        executor.shutdownNow()
        main.post { releasePlayer(deleteFile = true) }
    }

    private fun enqueueSend(talker: String, text: String, config: SynthesisConfig) {
        try {
            executor.execute {
                val file = newCacheFile(config)
                try {
                    synthesize(text, config, file)
                    if (!active.get()) return@execute
                    if (!sendEnabled()) {
                        ScriptSendButtonHook.restoreInputTextIfEmpty(talker, text)
                        return@execute
                    }
                    val sent = WeChatApis.media()?.voices()?.send(talker, file.absolutePath) == true
                    if (!sent) throw IllegalStateException("微信语音发送失败")
                } catch (throwable: Throwable) {
                    if (active.get()) {
                        logger("文字转语音发送失败", throwable)
                        ScriptSendButtonHook.restoreInputTextIfEmpty(talker, text)
                        toast(throwable.userMessage("文字转语音发送失败"))
                    }
                } finally {
                    file.delete()
                    File(file.parentFile, file.name + ".part").delete()
                }
            }
        } catch (_: RejectedExecutionException) {
            ScriptSendButtonHook.restoreInputTextIfEmpty(talker, text)
            toast("待处理语音过多，请稍后重试")
        }
    }

    private fun enqueuePlayback(text: String) {
        try {
            executor.execute {
                val config = synthesisConfig(null)
                val file = newCacheFile(config)
                var handedToPlayer = false
                try {
                    synthesize(text, config, file)
                    if (!active.get() || !playEnabled()) return@execute
                    handedToPlayer = true
                    main.post { playFile(file) }
                } catch (throwable: Throwable) {
                    if (active.get()) {
                        logger("文字转语音播放失败", throwable)
                        toast(throwable.userMessage("文字转语音播放失败"))
                    }
                } finally {
                    if (!handedToPlayer) file.delete()
                    File(file.parentFile, file.name + ".part").delete()
                }
            }
        } catch (_: RejectedExecutionException) {
            toast("待处理语音过多，请稍后重试")
        }
    }

    private fun playFile(file: File) {
        if (!active.get() || !playEnabled() || !file.isFile) {
            file.delete()
            return
        }
        releasePlayer(deleteFile = true)
        val generation = ++playerGeneration
        val current = MediaPlayer()
        player = current
        playerFile = file
        runCatching {
            current.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            current.setDataSource(file.absolutePath)
            current.setOnPreparedListener { mediaPlayer ->
                if (generation == playerGeneration && active.get()) mediaPlayer.start()
            }
            current.setOnCompletionListener {
                if (generation == playerGeneration) releasePlayer(deleteFile = true)
            }
            current.setOnErrorListener { _, _, _ ->
                if (generation == playerGeneration) {
                    toast("语音播放失败")
                    releasePlayer(deleteFile = true)
                }
                true
            }
            current.prepareAsync()
        }.onFailure {
            logger("启动文字转语音播放器失败", it)
            toast("语音播放失败")
            releasePlayer(deleteFile = true)
        }
    }

    private fun releasePlayer(deleteFile: Boolean) {
        playerGeneration++
        val currentPlayer = player
        val currentFile = playerFile
        player = null
        playerFile = null
        runCatching { currentPlayer?.stop() }
        runCatching { currentPlayer?.reset() }
        runCatching { currentPlayer?.release() }
        if (deleteFile) currentFile?.delete()
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("文本转语音菜单 Hook 安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addPlayMenu(param: XC_MethodHook.MethodHookParam) {
        if (!playEnabled()) return
        val args = param.args ?: return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View ?: return
        val message = resolveNativeMessage(view.tag) ?: resolveNativeMessage(args) ?: return
        val payload = textPayload(message) ?: return
        val item = addMenuItem(menu, view) ?: return
        bindings[item] = payload
    }

    private fun handlePlayClick(param: XC_MethodHook.MethodHookParam) {
        if (!playEnabled()) return
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (item.itemId != MENU_ITEM_ID) return
        val payload = bindings.remove(item)
        if (payload == null) {
            toast("文字消息不可用")
        } else {
            toast("正在生成语音")
            enqueuePlayback(payload.text)
        }
    }

    private fun textPayload(message: Any): TextPayload? {
        val type = readNumber(message, "getType", "field_type", "type")?.toInt() ?: return null
        if ((type and 0xffff) != 1) return null
        val talker = readString(message, "getTalker", "field_talker", "talker")
        var text = readString(message, "getContent", "field_content", "content")
        if ((talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"))) {
            val prefixEnd = text.indexOf(":\n")
            if (prefixEnd > 0) text = text.substring(prefixEnd + 2)
        }
        return text.trim().takeIf { it.isNotEmpty() }?.let(::TextPayload)
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        return resolveNativeMessage(source, visited, 0)
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 5 || !visited.add(source)) return null
        val messageId = readNumber(source, "getMsgId", "field_msgId", "msgId")
            ?: KavaReflector.invokeMethod(source, "getMsgID") as? Number
        if (source.javaClass.name.startsWith("com.tencent.mm.storage.") &&
            messageId?.toLong()?.let { it > 0L } == true
        ) {
            return source
        }
        if (source is View) return resolveNativeMessage(source.tag, visited, depth + 1)
        if (source is Array<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { value -> return value } }
            return null
        }
        if (source is Collection<*>) {
            source.forEach { resolveNativeMessage(it, visited, depth + 1)?.let { value -> return value } }
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
        (KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) as? MenuItem)?.let {
            moveMenuItemToFront(menu, it)
            return it
        }
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
                return (KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) as? MenuItem)
                    ?.also { moveMenuItemToFront(menu, it) }
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", groupId, MENU_ITEM_ID, 0, MENU_TITLE)
            ?: KavaReflector.invokeMethod(menu, "add", groupId, MENU_ITEM_ID, 0, MENU_TITLE as CharSequence)
        if (added is MenuItem) {
            if (iconRes != 0) runCatching { added.setIcon(iconRes) }
            moveMenuItemToFront(menu, added)
            return added
        }
        val fallback = KavaReflector.invokeMethod(menu, "f", MENU_ITEM_ID, MENU_TITLE)
            ?: KavaReflector.invokeMethod(menu, "f", MENU_ITEM_ID, MENU_TITLE as CharSequence)
        return ((fallback as? MenuItem)
            ?: KavaReflector.invokeMethod(menu, "findItem", MENU_ITEM_ID) as? MenuItem)
            ?.also { moveMenuItemToFront(menu, it) }
    }

    private fun moveMenuItemToFront(menu: Any, item: MenuItem) {
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
            val id = resources.getIdentifier("icons_filled_volume_up", type, packageName)
            if (id != 0) return id
        }
        return 0
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

    private fun cacheDirectory(): File {
        return File(context.hostContext().cacheDir, "hchat_text_voice").apply {
            if (!isDirectory) mkdirs()
        }
    }

    private fun synthesisConfig(mode: SendMode?): SynthesisConfig {
        val engine = TextVoiceSettings.selectedEngine(context.hostContext())
        val onlineVoice = if (mode == SendMode.ENGLISH) {
            TextVoiceSettings.ENGLISH_VOICE_ID
        } else {
            TextVoiceSettings.voiceId(TextVoiceSettings.selectedVoiceKey(context.hostContext()))
        }
        return SynthesisConfig(
            engine = engine,
            voiceId = onlineVoice,
            ttsVoice = prefs.getString(
                TextVoiceSettings.KEY_TTS_VOICE,
                TextVoiceSettings.DEFAULT_TTS_VOICE
            ).orEmpty(),
            speechRate = TextVoiceSettings.selectedSpeechRate(context.hostContext()),
            english = mode == SendMode.ENGLISH
        )
    }

    private fun synthesize(text: String, config: SynthesisConfig, file: File): File {
        return if (config.usesTts) {
            ttsSynthesizer.synthesize(
                text,
                TextVoiceSettings.ttsPackage(config.engine),
                config.ttsVoice,
                config.speechRate,
                config.english,
                file
            )
        } else {
            client.synthesize(
                text,
                config.voiceId,
                TextVoiceSettings.onlineSpeechRate(config.speechRate),
                file
            )
        }
    }

    private fun newCacheFile(config: SynthesisConfig): File {
        val extension = if (config.usesTts) ".wav" else ".mp3"
        return File(cacheDirectory(), "$CACHE_PREFIX${UUID.randomUUID()}$extension")
    }

    private fun sendEnabled(): Boolean {
        return active.get() && prefs.getBoolean(
            TextVoiceSettings.KEY_SEND_ENABLE,
            TextVoiceSettings.DEFAULT_SEND_ENABLE
        )
    }

    private fun playEnabled(): Boolean {
        return active.get() && prefs.getBoolean(
            TextVoiceSettings.KEY_PLAY_ENABLE,
            TextVoiceSettings.DEFAULT_PLAY_ENABLE
        )
    }

    private fun toast(message: String) {
        val activity = WeChatApis.currentActivity()?.currentActivity()
        main.post {
            if (activity == null || !activity.isUsable()) {
                Toast.makeText(context.hostContext(), message, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun Activity.isUsable(): Boolean = !isFinishing && !isDestroyed

    private fun Throwable.userMessage(fallback: String): String {
        if (this is InterruptedException) return "操作已取消"
        return message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
    }

    private companion object {
        const val COMMAND = "#tts"
        const val COMMAND_ENGLISH = "#tts e"
        const val CACHE_PREFIX = "hchat_text_voice_"
        const val MENU_ITEM_ID = 0x48435456
        const val MENU_TITLE = "转语音播放[H]"
    }
}
