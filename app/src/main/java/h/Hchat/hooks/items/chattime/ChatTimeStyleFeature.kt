package h.Hchat.hooks.items.chattime

import android.content.SharedPreferences
import android.view.View
import android.widget.AbsListView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class ChatTimeStyleFeature : BaseFeature() {
    private var runtime: ChatTimeStyleRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "会话时间样式"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ChatTimeStyleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = ChatTimeStyleRuntime(context)
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

    companion object {
        const val ID = "chat_time_style"
    }
}

private class ChatTimeStyleRuntime(
    private val context: FeatureContext
) {
    private data class BindState(val timeHolder: Any?)

    private data class BoundTime(
        val msgId: Long,
        val msgSvrId: Long,
        val createTime: Long,
        val position: Int,
        val nativeText: CharSequence,
        val nativeVisibility: Int,
        val holder: WeakReference<Any>,
        val root: WeakReference<View>
    )

    private val prefs = HchatStorage.preferences(context.hostContext(), ChatTimeStyleSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_chat_time_style_method_cache")
    private val rootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val timeFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val unsupportedTimeHolders = ConcurrentHashMap.newKeySet<Class<*>>()
    private val bindings = Collections.synchronizedMap(WeakHashMap<TextView, BoundTime>())
    private val bindStates = ThreadLocal<ArrayDeque<BindState>>()
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ChatTimeStyleSettings.KEY_ENABLE ||
            key == ChatTimeStyleSettings.KEY_MODE ||
            key == ChatTimeStyleSettings.KEY_TIME_FORMAT
        ) {
            if (!isEnabled() || currentMode() == ChatTimeStyleSettings.MODE_ORIGINAL) {
                restoreAndClearAttachedTimes()
            } else {
                refreshAttachedTimes()
            }
        }
    }

    @Volatile private var installed = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        restoreAndClearAttachedTimes()
    }

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = locateBindMethod() ?: run {
            HLog.e("$TAG 定位聊天时间绑定方法失败")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val active = isEnabled() && currentMode() != ChatTimeStyleSettings.MODE_ORIGINAL
                    if (!active && bindings.isEmpty()) return
                    val state = BindState(captureTimeHolder(param.args))
                    restoreBoundTime(param.args, state.timeHolder)
                    if (active) {
                        val stack = bindStates.get() ?: ArrayDeque<BindState>().also(bindStates::set)
                        stack.addLast(state)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = bindStates.get()
                    val state = stack?.pollLast() ?: return
                    if (stack.isEmpty()) bindStates.remove()
                    if (!isEnabled() || currentMode() == ChatTimeStyleSettings.MODE_ORIGINAL) return
                    bindTime(param.thisObject, param.args, state.timeHolder)
                }
            })
            installed = true
            true
        }.getOrElse {
            HLog.e("$TAG 安装聊天时间样式 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun bindTime(owner: Any?, args: Array<Any?>?, capturedTimeHolder: Any?) {
        if (!isEnabled()) return
        val mode = currentMode()
        if (mode == ChatTimeStyleSettings.MODE_ORIGINAL) return
        val holder = messageHolder(args) ?: return
        val root = findRootView(holder) ?: return
        val timeView = findBoundTimeView(holder, root, capturedTimeHolder) ?: return
        val message = resolveCurrentMessage(owner, args)
        val createTime = message?.let(::messageCreateTime) ?: 0L
        if (message == null || createTime <= 0L) {
            synchronized(bindings) { bindings.remove(timeView) }
            return
        }
        val bound = BoundTime(
            msgId = messageId(message),
            msgSvrId = messageServerId(message),
            createTime = createTime,
            position = messagePosition(args),
            nativeText = timeView.text ?: "",
            nativeVisibility = timeView.visibility,
            holder = WeakReference(holder),
            root = WeakReference(root)
        )
        bindings[timeView] = bound
        applyStyle(timeView, bound, mode)
    }

    private fun restoreBoundTime(args: Array<Any?>?, capturedTimeHolder: Any?) {
        val holder = messageHolder(args) ?: return
        val root = findRootView(holder) ?: return
        val timeView = findBoundTimeView(holder, root, capturedTimeHolder) ?: return
        val bound = synchronized(bindings) { bindings.remove(timeView) } ?: return
        timeView.text = bound.nativeText
        timeView.visibility = bound.nativeVisibility
    }

    private fun captureTimeHolder(args: Array<Any?>?): Any? {
        val holder = messageHolder(args) ?: return null
        val root = findRootView(holder) ?: return null
        return root.tag?.takeIf { taggedHolder ->
            findTimeView(taggedHolder)?.let { isViewWithinRoot(it, root) } == true
        }
    }

    private fun findBoundTimeView(holder: Any, root: View, capturedTimeHolder: Any?): TextView? {
        if (capturedTimeHolder != null) {
            findTimeView(capturedTimeHolder)
                ?.takeIf { isViewWithinRoot(it, root) }
                ?.let { return it }
        }

        findTimeView(holder)
            ?.takeIf { isViewWithinRoot(it, root) }
            ?.let { return it }

        // 微信把真正的聊天 BaseViewHolder 放在 itemView.tag 中；它本身通常
        // 没有 itemView 字段，不能再通过 findRootView(tag) 反查根 View。
        val taggedHolder = root.tag
        if (taggedHolder != null && taggedHolder !== holder) {
            findTimeView(taggedHolder)
                ?.takeIf { isViewWithinRoot(it, root) }
                ?.let { return it }
        }
        return null
    }

    private fun isViewWithinRoot(view: View, root: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 32) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    private fun applyStyle(view: TextView, bound: BoundTime, mode: String) {
        when (mode) {
            ChatTimeStyleSettings.MODE_HIDDEN -> view.visibility = View.GONE
            ChatTimeStyleSettings.MODE_EVERY -> {
                // 微信原生会隐藏大多数消息的时间行；该模式必须主动打开每一行。
                view.visibility = View.VISIBLE
                view.text = if (bound.createTime > 0L) {
                    formatTime(bound.createTime)
                } else {
                    bound.nativeText
                }
            }
            ChatTimeStyleSettings.MODE_CUSTOM -> {
                view.visibility = bound.nativeVisibility
                view.text = if (bound.nativeVisibility == View.VISIBLE && bound.createTime > 0L) {
                    formatTime(bound.createTime)
                } else {
                    bound.nativeText
                }
            }
            else -> {
                view.text = bound.nativeText
                view.visibility = bound.nativeVisibility
            }
        }
    }

    private fun refreshAttachedTimes() {
        val mode = currentMode()
        if (!isEnabled() || mode == ChatTimeStyleSettings.MODE_ORIGINAL) {
            restoreAndClearAttachedTimes()
            return
        }
        val attached = synchronized(bindings) { bindings.entries.map { it.key to it.value } }
        attached.forEach { (view, bound) ->
            view.post {
                if (view.parent != null && isCurrentBinding(view, bound)) {
                    applyStyle(view, bound, mode)
                }
            }
        }
    }

    private fun restoreAndClearAttachedTimes() {
        val attached = synchronized(bindings) { bindings.entries.map { it.key to it.value } }
        attached.forEach { (view, bound) ->
            view.post {
                if (!isCurrentBinding(view, bound)) return@post
                view.text = bound.nativeText
                view.visibility = bound.nativeVisibility
                synchronized(bindings) {
                    if (bindings[view] === bound) bindings.remove(view)
                }
            }
        }
    }

    private fun isCurrentBinding(view: TextView, bound: BoundTime): Boolean =
        synchronized(bindings) { bindings[view] === bound }

    private fun isEnabled(): Boolean = prefs.getBoolean(
        ChatTimeStyleSettings.KEY_ENABLE,
        ChatTimeStyleSettings.DEFAULT_ENABLE
    )

    private fun currentMode(): String = ChatTimeStyleSettings.normalizeMode(
        prefs.getString(ChatTimeStyleSettings.KEY_MODE, ChatTimeStyleSettings.DEFAULT_MODE)
    )

    private fun formatTime(timestamp: Long): String {
        val pattern = prefs.getString(
            ChatTimeStyleSettings.KEY_TIME_FORMAT,
            ChatTimeStyleSettings.DEFAULT_TIME_FORMAT
        ).orEmpty().ifBlank { ChatTimeStyleSettings.DEFAULT_TIME_FORMAT }
        return runCatching {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }.getOrElse {
            SimpleDateFormat(ChatTimeStyleSettings.DEFAULT_TIME_FORMAT, Locale.getDefault())
                .format(Date(timestamp))
        }
    }

    private fun locateBindMethod(): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_BIND_METHOD)
            ?.takeIf(::isBindCandidate)
            ?.let { return it }
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(listOf("MicroMsg.MvvmChattingItem", "[onBindView]"))
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.getOrElse {
            HLog.e("$TAG 定位聊天时间绑定方法异常: ${it.message}", it)
            emptyList()
        }
        val method = methods.firstOrNull(::isBindCandidate)
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_BIND_METHOD, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_BIND_METHOD)
        }
        return method
    }

    private fun isBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 6 &&
            isLikelyViewHolderClass(types[0]) &&
            !types[1].isPrimitive &&
            types[2] == Integer.TYPE &&
            types[3] == Integer.TYPE &&
            types[4] == java.lang.Boolean.TYPE &&
            java.util.List::class.java.isAssignableFrom(types[5])
    }

    private fun isLikelyViewHolderClass(clazz: Class<*>): Boolean {
        if (findRootField(clazz) != null) return true
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (KavaReflector.declaredFields(current).any { View::class.java.isAssignableFrom(it.type) }) return true
            current = current.superclass
        }
        return false
    }

    private fun messageHolder(args: Array<Any?>?): Any? {
        return args?.getOrNull(0)?.takeIf { findRootView(it) != null }
    }

    private fun findRootView(holder: Any): View? {
        (KavaReflector.readField(holder, "itemView") as? View)?.let { return it }
        return KavaReflector.readField(findRootField(holder.javaClass), holder) as? View
    }

    private fun findRootField(clazz: Class<*>): Field? {
        rootFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "itemView" || it.type == View::class.java
            }
            if (field != null) {
                rootFieldCache[clazz] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun findTimeView(holder: Any): TextView? {
        timeFieldCache[holder.javaClass]?.let {
            return KavaReflector.readField(it, holder) as? TextView
        }
        if (holder.javaClass in unsupportedTimeHolders) return null
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "timeTV" && TextView::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                timeFieldCache[holder.javaClass] = field
                return KavaReflector.readField(field, holder) as? TextView
            }
            current = current.superclass
        }
        unsupportedTimeHolders += holder.javaClass
        return null
    }

    private fun resolveCurrentMessage(owner: Any?, args: Array<Any?>?): Any? {
        // 微信原生绑定方法使用第二参数完成当前行的实际渲染。优先使用它，避免
        // RecyclerView 异步复用期间 data[position] 暂时仍指向会话内旧对象。
        resolveMessageItem(args?.getOrNull(1))?.let { return it }

        val position = messagePosition(args)
        if (owner != null && position >= 0) {
            val adapter = KavaReflector.readField(owner, "h")
            val data = adapter?.let { KavaReflector.invokeMethod(it, "getData") }
            itemAt(data, position)?.let { itemAtPosition ->
                resolveMessageItem(itemAtPosition)?.let { return it }
            }
        }
        return null
    }

    private fun resolveMessageItem(item: Any?): Any? {
        if (item == null) return null
        if (isNativeMessage(item)) return item

        val nested = KavaReflector.readField(item, "d")
        if (nested != null) {
            KavaReflector.readField(nested, "b")
                ?.takeIf(::isNativeMessage)
                ?.let { return it }
        }

        return KavaReflector.readField(item, "e")
            ?.takeIf(::isNativeMessage)
    }

    private fun itemAt(data: Any?, position: Int): Any? {
        return (data as? List<*>)?.getOrNull(position)
    }

    private fun isNativeMessage(value: Any): Boolean {
        if (!value.javaClass.name.startsWith("com.tencent.mm.storage.")) return false
        return messageCreateTime(value) > 0L && messageId(value) > 0L
    }

    private fun messageCreateTime(message: Any): Long {
        parseLong(KavaReflector.invoke(KavaReflector.findMethod(message.javaClass, "getCreateTime"), message))
            ?.let(::normalizeCreateTime)
            ?.let { if (it > 0L) return it }
        for (name in arrayOf("field_createTime", "createTime")) {
            parseLong(KavaReflector.readField(message, name))
                ?.let(::normalizeCreateTime)
                ?.let { if (it > 0L) return it }
        }
        return 0L
    }

    private fun normalizeCreateTime(value: Long): Long {
        return if (value in 1L until 10_000_000_000L) value * 1000L else value
    }

    private fun messageId(message: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID")) {
            parseLong(KavaReflector.invoke(KavaReflector.findMethod(message.javaClass, name), message))
                ?.let { if (it > 0L) return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID")) {
            parseLong(KavaReflector.readField(message, name))?.let { if (it > 0L) return it }
        }
        return 0L
    }

    private fun messageServerId(message: Any): Long {
        for (name in arrayOf("getMsgSvrId", "getMsgSvrID")) {
            parseLong(KavaReflector.invoke(KavaReflector.findMethod(message.javaClass, name), message))
                ?.let { if (it > 0L) return it }
        }
        for (name in arrayOf("field_msgSvrId", "msgSvrId", "msgSvrID")) {
            parseLong(KavaReflector.readField(message, name))?.let { if (it > 0L) return it }
        }
        return 0L
    }

    private fun messagePosition(args: Array<Any?>?): Int =
        parseInt(args?.getOrNull(2)) ?: -1

    private fun parseLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun parseInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private companion object {
        const val TAG = "[Hchat:ChatTimeStyle]"
        const val CACHE_SCHEMA = "chat_time_style_v2"
        const val CACHE_BIND_METHOD = "chat_time_bind"
    }
}
