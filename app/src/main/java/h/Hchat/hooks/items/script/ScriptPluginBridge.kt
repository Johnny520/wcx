package h.Hchat.hooks.items.script

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.ui.miuix.ScriptFloatingGlassBarHost
import h.Hchat.utils.KavaReflector
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Properties
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.function.Function

class ScriptPluginBridge internal constructor(
    val hostContext: Context,
    val classLoader: ClassLoader,
    val scriptDir: File,
    val dexKit: ScriptDexKitBridge? = null
) {
    val apis: Class<WeChatApis> = WeChatApis::class.java
    private val pluginHooks = ConcurrentHashMap<String, CopyOnWriteArrayList<XC_MethodHook.Unhook>>()
    private val pluginFloatingBars = ConcurrentHashMap<String, CopyOnWriteArrayList<ScriptFloatingGlassBarHandle>>()
    private val pluginDialogCallbacks = ConcurrentHashMap<String, MutableSet<DialogCallbackGate<*>>>()
    private val configLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private class DialogCallbackGate<T>(callback: Consumer<T>?) : Consumer<T> {
        private val current = AtomicReference(callback)
        override fun accept(value: T) {
            current.getAndSet(null)?.let {
                runCatching { it.accept(value) }
                    .onFailure { error ->
                        h.Hchat.utils.HLog.e("[Hchat:Script] 对话框回调失败: ${error.message}", error)
                    }
            }
        }

        fun clear() {
            current.set(null)
        }
    }

    fun log(message: Any?) {
        XposedBridge.log("[Hchat:Script] ${message ?: "null"}")
    }

    fun log(pluginName: String?, pluginDir: File?, message: Any?) {
        val text = message?.toString() ?: "null"
        XposedBridge.log("[Hchat:Script] [${pluginName.orEmpty()}] $text")
        val dir = pluginDir ?: return
        runCatching {
            if (!dir.isDirectory) dir.mkdirs()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            File(dir, "log.txt").appendText("[$time] $text\n")
        }.onFailure {
            h.Hchat.utils.HLog.e("[Hchat:Script] 写入插件日志失败: ${pluginName.orEmpty()} ${it.message}", it)
        }
    }

    fun toast(message: Any?) {
        WeChatApis.interaction().notifier()?.showToast(message?.toString() ?: "null")
    }

    fun toast(pluginName: String?, message: Any?) {
        val prefix = pluginName?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
        WeChatApis.interaction().notifier()?.showToast(prefix + (message?.toString() ?: "null"))
    }

    fun showModuleDialog(title: String?, message: String?): Boolean {
        return showModuleDialog(title, message, null)
    }

    fun showModuleDialog(title: String?, message: String?, position: String?): Boolean {
        return showOnMain { activity ->
            VoiceForwardMiuixDialog.showMessage(
                activity = activity,
                title = title.orEmpty(),
                message = message.orEmpty(),
                onDismiss = {},
                position = VoiceForwardMiuixDialog.DialogPosition.from(position)
            )
        }
    }

    fun showModuleConfirmDialog(title: String?, message: String?, callback: Consumer<Boolean>?): Boolean {
        return showModuleConfirmDialog(title, message, null, callback)
    }

    fun showModuleConfirmDialogForPlugin(
        pluginId: String?, title: String?, message: String?, callback: Consumer<Boolean>?
    ): Boolean = showModuleConfirmDialog(
        title, message, null, trackDialogCallback(pluginId, callback)
    )

    fun showModuleConfirmDialogForPlugin(
        pluginId: String?, title: String?, message: String?, position: String?, callback: Consumer<Boolean>?
    ): Boolean = showModuleConfirmDialog(
        title, message, position, trackDialogCallback(pluginId, callback)
    )

    fun showModuleConfirmDialog(
        title: String?,
        message: String?,
        position: String?,
        callback: Consumer<Boolean>?
    ): Boolean {
        return showOnMain { activity ->
            VoiceForwardMiuixDialog.showConfirm(
                activity = activity,
                title = title.orEmpty(),
                message = message.orEmpty(),
                onResult = { dispatchDialogCallback(callback, it) },
                onDismiss = { clearDialogCallback(callback) },
                position = VoiceForwardMiuixDialog.DialogPosition.from(position)
            )
        }
    }

    fun showModuleInputDialog(
        title: String?,
        summary: String?,
        initialValue: String?,
        placeholder: String?,
        callback: Consumer<String>?
    ): Boolean {
        return showModuleInputDialog(title, summary, initialValue, placeholder, null, callback)
    }

    fun showModuleInputDialogForPlugin(
        pluginId: String?, title: String?, summary: String?, initialValue: String?,
        placeholder: String?, callback: Consumer<String>?
    ): Boolean = showModuleInputDialog(
        title, summary, initialValue, placeholder, null, trackDialogCallback(pluginId, callback)
    )

    fun showModuleInputDialogForPlugin(
        pluginId: String?, title: String?, summary: String?, initialValue: String?,
        placeholder: String?, position: String?, callback: Consumer<String>?
    ): Boolean = showModuleInputDialog(
        title, summary, initialValue, placeholder, position, trackDialogCallback(pluginId, callback)
    )

    fun showModuleInputDialog(
        title: String?,
        summary: String?,
        initialValue: String?,
        placeholder: String?,
        position: String?,
        callback: Consumer<String>?
    ): Boolean {
        return showOnMain { activity ->
            VoiceForwardMiuixDialog.showTextInput(
                activity = activity,
                title = title.orEmpty(),
                summary = summary.orEmpty(),
                initialValue = initialValue.orEmpty(),
                placeholder = placeholder.orEmpty(),
                maxLength = 4_000,
                allowEmpty = true,
                onConfirm = { dispatchDialogCallback(callback, it) },
                onDismiss = { clearDialogCallback(callback) },
                position = VoiceForwardMiuixDialog.DialogPosition.from(position)
            )
        }
    }

    fun showModuleChoiceDialog(
        title: String?,
        summary: String?,
        choices: List<*>?,
        callback: Consumer<Int>?
    ): Boolean {
        return showModuleChoiceDialog(title, summary, choices, null, callback)
    }

    fun showModuleChoiceDialogForPlugin(
        pluginId: String?, title: String?, summary: String?, choices: List<*>?, callback: Consumer<Int>?
    ): Boolean = showModuleChoiceDialog(
        title, summary, choices, null, trackDialogCallback(pluginId, callback)
    )

    fun showModuleChoiceDialogForPlugin(
        pluginId: String?, title: String?, summary: String?, choices: List<*>?, position: String?,
        callback: Consumer<Int>?
    ): Boolean = showModuleChoiceDialog(
        title, summary, choices, position, trackDialogCallback(pluginId, callback)
    )

    fun showModuleChoiceDialog(
        title: String?,
        summary: String?,
        choices: List<*>?,
        position: String?,
        callback: Consumer<Int>?
    ): Boolean {
        val items = choices.orEmpty().map { it?.toString().orEmpty() }
        if (items.isEmpty()) {
            clearDialogCallback(callback)
            return false
        }
        return showOnMain { activity ->
            VoiceForwardMiuixDialog.showChoices(
                activity = activity,
                title = title.orEmpty(),
                summary = summary.orEmpty(),
                choices = items.map { it to "" },
                onSelected = { dispatchDialogCallback(callback, it) },
                onDismiss = { clearDialogCallback(callback) },
                position = VoiceForwardMiuixDialog.DialogPosition.from(position)
            )
        }
    }

    fun showModuleMultiChoiceDialog(
        title: String?,
        summary: String?,
        choices: List<*>?,
        initialSelected: Set<*>?,
        callback: Consumer<Set<Int>>?
    ): Boolean {
        return showModuleMultiChoiceDialog(title, summary, choices, initialSelected, null, callback)
    }

    fun showModuleMultiChoiceDialogForPlugin(
        pluginId: String?, title: String?, summary: String?, choices: List<*>?, initialSelected: Set<*>?,
        callback: Consumer<Set<Int>>?
    ): Boolean = showModuleMultiChoiceDialog(
        title, summary, choices, initialSelected, null, trackDialogCallback(pluginId, callback)
    )

    fun showModuleMultiChoiceDialogForPlugin(
        pluginId: String?, title: String?, summary: String?, choices: List<*>?, initialSelected: Set<*>?,
        position: String?, callback: Consumer<Set<Int>>?
    ): Boolean = showModuleMultiChoiceDialog(
        title, summary, choices, initialSelected, position, trackDialogCallback(pluginId, callback)
    )

    fun showModuleMultiChoiceDialog(
        title: String?,
        summary: String?,
        choices: List<*>?,
        initialSelected: Set<*>?,
        position: String?,
        callback: Consumer<Set<Int>>?
    ): Boolean {
        val items = choices.orEmpty().map { it?.toString().orEmpty() }
        if (items.isEmpty()) {
            clearDialogCallback(callback)
            return false
        }
        val selected = initialSelected.orEmpty()
            .mapNotNull { (it as? Number)?.toInt() }
            .filter { it in items.indices }
            .toSet()
        return showOnMain { activity ->
            VoiceForwardMiuixDialog.showMultiChoices(
                activity = activity,
                title = title.orEmpty(),
                summary = summary.orEmpty(),
                choices = items.map { it to "" },
                initialSelected = selected,
                allowEmpty = true,
                onConfirm = { dispatchDialogCallback(callback, it) },
                onDismiss = { clearDialogCallback(callback) },
                position = VoiceForwardMiuixDialog.DialogPosition.from(position)
            )
        }
    }

    fun applyModuleFloatingGlassBar(
        pluginId: String?,
        bottomBar: View?
    ): ScriptFloatingGlassBarHandle? = applyModuleFloatingGlassBar(pluginId, bottomBar, null)

    fun applyModuleFloatingGlassBar(
        pluginId: String?,
        bottomBar: View?,
        options: Map<*, *>?
    ): ScriptFloatingGlassBarHandle? {
        val cleanPluginId = pluginId?.takeIf { it.isNotBlank() } ?: return null
        val target = bottomBar ?: return null
        return callOnMainForResult {
            val activity = findActivity(target.context)
                ?: WeChatApis.currentActivity()?.currentActivity()
                ?: return@callOnMainForResult null
            val publicHandleRef = AtomicReference<ScriptFloatingGlassBarHandle?>()
            val hostHandle = ScriptFloatingGlassBarHost.apply(activity, target, options, restored@{
                val publicHandle = publicHandleRef.get() ?: return@restored
                pluginFloatingBars[cleanPluginId]?.let { handles ->
                    handles.remove(publicHandle)
                    if (handles.isEmpty()) pluginFloatingBars.remove(cleanPluginId, handles)
                }
                publicHandle.markRestored()
            }) ?: return@callOnMainForResult null
            val publicHandle = ScriptFloatingGlassBarHandle(hostHandle) {
                callOnMainForResult {
                    hostHandle.restore()
                    true
                }
            }
            publicHandleRef.set(publicHandle)
            pluginFloatingBars.getOrPut(cleanPluginId) { CopyOnWriteArrayList() }.add(publicHandle)
            publicHandle
        }
    }

    fun registerPlusMenu(
        pluginId: String?,
        pluginDir: File?,
        title: String?,
        iconPath: String?,
        front: Boolean,
        callback: Consumer<Any?>?
    ): ScriptMenuDispatcher.ScriptMenuHandle? {
        if (callback == null) return null
        return ScriptMenuDispatcher.registerPlusMenu(
            owner = pluginId,
            pluginDir = pluginDir,
            title = title,
            iconPath = iconPath,
            front = front,
            onClick = { activity ->
                runCatching { callback.accept(activity) }
                    .onFailure {
                        h.Hchat.utils.HLog.e(
                            "[Hchat:Script] 加号菜单回调失败: ${pluginId.orEmpty()} ${it.message}",
                            it
                        )
                    }
            }
        )
    }

    fun registerMessageMenu(
        pluginId: String?,
        pluginDir: File?,
        title: String?,
        iconPath: String?,
        front: Boolean,
        callback: Consumer<ScriptMessageBean>?
    ): ScriptMenuDispatcher.ScriptMenuHandle? {
        if (callback == null) return null
        return ScriptMenuDispatcher.registerMessageMenu(
            owner = pluginId,
            pluginDir = pluginDir,
            title = title,
            iconPath = iconPath,
            front = front,
            onClick = { message ->
                runCatching { callback.accept(message) }
                    .onFailure {
                        h.Hchat.utils.HLog.e(
                            "[Hchat:Script] 长按消息菜单回调失败: ${pluginId.orEmpty()} ${it.message}",
                            it
                        )
                    }
            }
        )
    }

    fun removeMenu(handle: Any?) {
        ScriptMenuDispatcher.remove(handle)
    }

    fun prefs(name: String) = HchatStorage.preferences(hostContext, name)

    fun file(name: String): File = File(scriptDir, name)

    fun getString(pluginDir: File?, key: String?, defaultValue: String?): String {
        val normalizedKey = key?.takeIf { it.isNotBlank() } ?: return defaultValue.orEmpty()
        return loadPluginConfig(pluginDir).getProperty(normalizedKey) ?: defaultValue.orEmpty()
    }

    fun getStringSet(pluginDir: File?, key: String?, defaultValue: Set<*>?): Set<String> {
        val value = getString(pluginDir, key, null).takeIf { it.isNotEmpty() } ?: return normalizeStringSet(defaultValue)
        return value.split('\n')
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    fun getBoolean(pluginDir: File?, key: String?, defaultValue: Boolean): Boolean {
        val value = getString(pluginDir, key, null).lowercase(Locale.US)
        return when (value) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> defaultValue
        }
    }

    fun getInt(pluginDir: File?, key: String?, defaultValue: Int): Int {
        return getString(pluginDir, key, null).toIntOrNull() ?: defaultValue
    }

    fun getFloat(pluginDir: File?, key: String?, defaultValue: Float): Float {
        return getString(pluginDir, key, null).toFloatOrNull() ?: defaultValue
    }

    fun getLong(pluginDir: File?, key: String?, defaultValue: Long): Long {
        return getString(pluginDir, key, null).toLongOrNull() ?: defaultValue
    }

    fun putString(pluginDir: File?, key: String?, value: String?) {
        putConfigValue(pluginDir, key, value.orEmpty())
    }

    fun putStringSet(pluginDir: File?, key: String?, value: Set<*>?) {
        putConfigValue(pluginDir, key, normalizeStringSet(value).joinToString("\n"))
    }

    fun putBoolean(pluginDir: File?, key: String?, value: Boolean) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun putInt(pluginDir: File?, key: String?, value: Int) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun putFloat(pluginDir: File?, key: String?, value: Float) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun putLong(pluginDir: File?, key: String?, value: Long) {
        putConfigValue(pluginDir, key, value.toString())
    }

    fun findClass(className: String): Class<*> {
        return XposedHelpers.findClass(className, classLoader)
    }

    fun firstMethod(instance: Any?, methodName: String?): Method? = firstMethod(instance, methodName, -1)

    fun firstMethod(instance: Any?, methodName: String?, paramCount: Int): Method? {
        if (methodName.isNullOrBlank()) return null
        var current = targetClass(instance)
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull {
                it.name == methodName && (paramCount < 0 || it.parameterTypes.size == paramCount)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    fun firstConstructor(instance: Any?, paramCount: Int): Constructor<*>? {
        val clazz = targetClass(instance) ?: return null
        return KavaReflector.declaredConstructors(clazz)
            .firstOrNull { it.parameterTypes.size == paramCount }
    }

    fun firstField(instance: Any?, fieldName: String?): Field? {
        if (fieldName.isNullOrBlank()) return null
        return KavaReflector.findFieldRecursive(targetClass(instance), fieldName)
    }

    fun invokeMethod(instance: Any?, methodName: String?): Any? {
        return invokeMethod(instance, methodName, 0, emptyArray())
    }

    fun invokeMethod(instance: Any?, methodName: String?, params: Array<Any?>?): Any? {
        val safeParams = params ?: emptyArray()
        return invokeMethod(instance, methodName, safeParams.size, safeParams)
    }

    fun invokeMethod(instance: Any?, methodName: String?, paramCount: Int): Any? {
        return invokeMethod(instance, methodName, paramCount, emptyArray())
    }

    fun invokeMethod(instance: Any?, methodName: String?, paramCount: Int, params: Array<Any?>?): Any? {
        val safeParams = params ?: emptyArray()
        val method = compatibleMethod(instance, methodName, paramCount, safeParams) ?: return null
        return KavaReflector.invoke(method, receiverFor(instance, method), *safeParams)
    }

    fun createInstance(instance: Any?, paramCount: Int): Any? {
        return createInstance(instance, paramCount, emptyArray())
    }

    fun createInstance(instance: Any?, paramCount: Int, params: Array<Any?>?): Any? {
        val clazz = targetClass(instance) ?: return null
        val safeParams = params ?: emptyArray()
        val constructor = KavaReflector.declaredConstructors(clazz).firstOrNull {
            it.parameterTypes.size == paramCount && areAssignable(it.parameterTypes, safeParams)
        } ?: return null
        return KavaReflector.newInstance(constructor, *safeParams)
    }

    fun getField(instance: Any?, fieldName: String?): Any? {
        val field = firstField(instance, fieldName) ?: return null
        return KavaReflector.readField(field, receiverFor(instance, field))
    }

    fun setField(instance: Any?, fieldName: String?, value: Any?) {
        val field = firstField(instance, fieldName) ?: return
        KavaReflector.writeField(field, receiverFor(instance, field), value)
    }

    fun hookBefore(
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook = hookBefore(null, member, callback)

    fun hookBefore(
        pluginId: String?,
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook {
        val unhook = HookRegistry.get().hook(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                callback.accept(param)
            }
        })
        rememberHook(pluginId, unhook)
        return unhook
    }

    fun hookAfter(
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook = hookAfter(null, member, callback)

    fun hookAfter(
        pluginId: String?,
        member: Member,
        callback: Consumer<XC_MethodHook.MethodHookParam>
    ): XC_MethodHook.Unhook {
        val unhook = HookRegistry.get().hook(member, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                callback.accept(param)
            }
        })
        rememberHook(pluginId, unhook)
        return unhook
    }

    fun hookReplace(
        member: Member,
        callback: Function<XC_MethodHook.MethodHookParam, Any?>
    ): XC_MethodHook.Unhook = hookReplace(null, member, callback)

    fun hookReplace(
        pluginId: String?,
        member: Member,
        callback: Function<XC_MethodHook.MethodHookParam, Any?>
    ): XC_MethodHook.Unhook {
        val unhook = HookRegistry.get().hook(member, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = callback.apply(param)
            }
        })
        rememberHook(pluginId, unhook)
        return unhook
    }

    fun unhook(unhook: XC_MethodHook.Unhook?) {
        HookRegistry.get().unhook(unhook)
        if (unhook != null) {
            pluginHooks.values.forEach { it.remove(unhook) }
        }
    }

    fun unhook(pluginId: String?, unhook: XC_MethodHook.Unhook?) {
        if (pluginId != null && unhook != null) {
            pluginHooks[pluginId]?.remove(unhook)
        }
        unhook(unhook)
    }

    fun unhook(pluginId: String?, handle: Any?) {
        unhook(pluginId, handle as? XC_MethodHook.Unhook)
    }

    fun unhookPlugin(pluginId: String?) {
        if (pluginId.isNullOrBlank()) return
        pluginDialogCallbacks.remove(pluginId)?.let { callbacks ->
            synchronized(callbacks) { callbacks.forEach { it.clear() } }
        }
        runCatching { ScriptMenuDispatcher.unregisterOwner(pluginId) }.onFailure {
            h.Hchat.utils.HLog.e("[Hchat:Script] 清理插件菜单失败: $pluginId", it)
        }
        pluginFloatingBars.remove(pluginId)?.let { handles ->
            runCatching {
                callOnMainForResult {
                    handles.forEach { handle -> handle.restore() }
                    true
                }
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 恢复插件悬浮栏失败: $pluginId", it)
            }
        }
        for (hook in pluginHooks.remove(pluginId).orEmpty()) {
            runCatching { HookRegistry.get().unhook(hook) }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 解除插件 Hook 失败: $pluginId", it)
            }
        }
    }

    private fun rememberHook(pluginId: String?, unhook: XC_MethodHook.Unhook?) {
        if (pluginId.isNullOrBlank() || unhook == null) return
        pluginHooks.getOrPut(pluginId) { CopyOnWriteArrayList() }.add(unhook)
    }

    private fun targetClass(instance: Any?): Class<*>? {
        return when (instance) {
            null -> null
            is Class<*> -> instance
            else -> instance.javaClass
        }
    }

    private fun receiverFor(instance: Any?, method: Method): Any? {
        if (instance is Class<*> || KavaReflector.isStatic(method)) return null
        return instance
    }

    private fun receiverFor(instance: Any?, field: Field): Any? {
        if (instance is Class<*> || KavaReflector.isStatic(field)) return null
        return instance
    }

    private fun compatibleMethod(
        instance: Any?,
        methodName: String?,
        paramCount: Int,
        params: Array<Any?>
    ): Method? {
        if (methodName.isNullOrBlank()) return null
        var current = targetClass(instance)
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredMethods(current).firstOrNull {
                it.name == methodName
                        && it.parameterTypes.size == paramCount
                        && areAssignable(it.parameterTypes, params)
            }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun areAssignable(parameterTypes: Array<Class<*>>, params: Array<Any?>): Boolean {
        if (parameterTypes.size != params.size) return false
        return parameterTypes.indices.all { isAssignable(parameterTypes[it], params[it]) }
    }

    private fun isAssignable(parameterType: Class<*>, value: Any?): Boolean {
        if (value == null) return !parameterType.isPrimitive
        return boxType(parameterType).isAssignableFrom(value.javaClass)
    }

    private fun boxType(clazz: Class<*>): Class<*> {
        if (!clazz.isPrimitive) return clazz
        return when (clazz) {
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Void.TYPE -> java.lang.Void::class.java
            else -> clazz
        }
    }

    private fun loadPluginConfig(pluginDir: File?): Properties {
        val properties = Properties()
        val file = configFile(pluginDir) ?: return properties
        if (!file.isFile) return properties
        synchronized(configLock) {
            runCatching {
                withConfigFileLock(file) {
                    if (file.isFile) {
                        file.reader(Charsets.UTF_8).use { properties.load(it) }
                    }
                }
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 读取插件配置失败: ${pluginDir?.name.orEmpty()} ${it.message}", it)
            }
        }
        return properties
    }

    private fun putConfigValue(pluginDir: File?, key: String?, value: String) {
        val normalizedKey = key?.takeIf { it.isNotBlank() } ?: return
        val file = configFile(pluginDir) ?: return
        synchronized(configLock) {
            runCatching {
                withConfigFileLock(file) {
                    val properties = Properties()
                    if (file.isFile) {
                        file.reader(Charsets.UTF_8).use { properties.load(it) }
                    }
                    properties.setProperty(normalizedKey, value)
                    file.writer(Charsets.UTF_8).use {
                        properties.store(it, "Hchat script plugin config")
                    }
                }
            }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 写入插件配置失败: ${pluginDir?.name.orEmpty()} ${it.message}", it)
            }
        }
    }

    private fun configFile(pluginDir: File?): File? {
        val dir = pluginDir ?: return null
        return File(dir, "config.prop")
    }

    private inline fun <T> withConfigFileLock(file: File, action: () -> T): T {
        val parent = file.parentFile ?: error("插件配置目录不可用")
        if (!parent.isDirectory && !parent.mkdirs()) {
            error("无法创建插件配置目录: ${parent.absolutePath}")
        }
        val lockRoot = File(HchatStorage.storageDir(hostContext), "script_plugin_locks")
        if (!lockRoot.isDirectory && !lockRoot.mkdirs()) {
            error("无法创建插件配置锁目录: ${lockRoot.absolutePath}")
        }
        val lockName = "${Integer.toHexString(parent.absolutePath.hashCode())}.lock"
        val lockFile = File(lockRoot, lockName)
        return RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
            randomAccessFile.channel.use { channel ->
                val lock = channel.lock()
                try {
                    action()
                } finally {
                    lock.release()
                }
            }
        }
    }

    private fun normalizeStringSet(value: Set<*>?): Set<String> {
        if (value.isNullOrEmpty()) return emptySet()
        return value.mapNotNull { it?.toString() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun showOnMain(action: (Activity) -> Unit): Boolean {
        val activity = WeChatApis.currentActivity()?.currentActivity() ?: return false
        if (activity.isFinishing || activity.isDestroyed) return false
        val show = Runnable {
            if (activity.isFinishing || activity.isDestroyed) return@Runnable
            runCatching { action(activity) }.onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 显示模块弹窗失败: ${it.message}", it)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run()
        } else {
            activity.runOnUiThread(show)
        }
        return true
    }

    private fun <T> callOnMainForResult(action: () -> T?): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(action).onFailure {
                h.Hchat.utils.HLog.e("[Hchat:Script] 执行模块悬浮底栏操作失败: ${it.message}", it)
            }.getOrNull()
        }
        val pending = 0
        val running = 1
        val completed = 2
        val cancelled = 3
        val state = AtomicInteger(pending)
        val result = AtomicReference<T?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val task = Runnable {
            if (!state.compareAndSet(pending, running)) {
                latch.countDown()
                return@Runnable
            }
            try {
                result.set(action())
            } catch (throwable: Throwable) {
                error.set(throwable)
            } finally {
                state.set(completed)
                latch.countDown()
            }
        }
        if (!mainHandler.post(task)) return null

        var interrupted = false
        val finishedInTime = try {
            latch.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
            false
        }
        if (!finishedInTime) {
            if (state.compareAndSet(pending, cancelled)) {
                mainHandler.removeCallbacks(task)
                if (interrupted) {
                    Thread.currentThread().interrupt()
                    h.Hchat.utils.HLog.e("[Hchat:Script] 等待模块悬浮底栏操作被中断")
                } else {
                    h.Hchat.utils.HLog.e("[Hchat:Script] 执行模块悬浮底栏操作超时")
                }
                return null
            }
            while (state.get() != completed) {
                try {
                    latch.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        error.get()?.let {
            h.Hchat.utils.HLog.e("[Hchat:Script] 执行模块悬浮底栏操作失败: ${it.message}", it)
            return null
        }
        return result.get()
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

    private fun <T> dispatchDialogCallback(callback: Consumer<T>?, value: T) {
        runCatching { callback?.accept(value) }.onFailure {
            h.Hchat.utils.HLog.e("[Hchat:Script] 模块弹窗回调失败: ${it.message}", it)
        }
    }

    private fun clearDialogCallback(callback: Consumer<*>?) {
        (callback as? DialogCallbackGate<*>)?.clear()
    }

    private fun <T> trackDialogCallback(pluginId: String?, callback: Consumer<T>?): Consumer<T>? {
        if (callback == null) return null
        val cleanId = pluginId?.takeIf { it.isNotBlank() } ?: return callback
        val gate = DialogCallbackGate(callback)
        pluginDialogCallbacks.compute(cleanId) { _, old ->
            (old ?: Collections.synchronizedSet(
                Collections.newSetFromMap(WeakHashMap<DialogCallbackGate<*>, Boolean>())
            )).apply { add(gate) }
        }
        return gate
    }

    companion object {
        fun from(context: FeatureContext): ScriptPluginBridge {
            return ScriptPluginBridge(
                context.hostContext(),
                context.hostClassLoader(),
                ScriptPluginRuntime.scriptDir(context.hostContext()),
                ScriptDexKitBridge(
                    context.dexKitBridge(),
                    context.dexBridgeHolder(),
                    context.hostClassLoader()
                )
            )
        }
    }
}

class ScriptFloatingGlassBarHandle internal constructor(
    private val delegate: h.Hchat.ui.miuix.FloatingGlassBarHostHandle,
    private val restoreAction: () -> Unit
) {
    @Volatile
    private var restored = false

    fun restore() {
        if (restored) return
        restoreAction()
    }

    fun isApplied(): Boolean = !restored && delegate.isApplied()

    internal fun markRestored() {
        restored = true
    }
}
