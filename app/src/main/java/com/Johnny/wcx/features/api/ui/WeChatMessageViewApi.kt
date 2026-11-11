package com.Johnny.wcx.features.api.ui

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "消息 View 创建监听服务", categories = ["API"], description = "提供消息 View 创建监听能力")
object WeChatMessageViewApi : ApiFeature(), IResolveDex {

    fun interface ICreateViewListener {
        fun onCreateView(
            param: XC_MethodHook.MethodHookParam, view: View
        )
    }

    interface IMessageViewLifecycleListener {
        fun onMessageViewAttached(view: View, message: MessageInfo) {}
        fun onMessageViewDetached(view: View, message: MessageInfo) {}
        fun onMessageViewRecycled(view: View, message: MessageInfo) {}
    }

    private val listeners = CopyOnWriteArrayList<ICreateViewListener>()
    private val lifecycleListeners = CopyOnWriteArrayList<IMessageViewLifecycleListener>()
    private val currentBindings =
        Collections.synchronizedMap(WeakHashMap<View, MessageInfo>())
    private val attachStateListeners =
        Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())

    fun addListener(listener: ICreateViewListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: ICreateViewListener) {
        val removed = listeners.remove(listener)
        WeLogger.i(
            TAG,
            "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}"
        )
    }

    fun addLifecycleListener(listener: IMessageViewLifecycleListener) {
        if (!lifecycleListeners.contains(listener)) {
            lifecycleListeners.add(listener)
        }
    }

    fun removeLifecycleListener(listener: IMessageViewLifecycleListener) {
        val removed = lifecycleListeners.remove(listener)
        WeLogger.i(
            TAG,
            "lifecycle listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${lifecycleListeners.size}"
        )
    }

    private const val TAG = "WeChatMessageViewApi"

    // 锚点修正（2026-11-06，8.0.79 / 8.0.80 双版本逐串验证）：
    //  · 旧锚点里的 "[onBindView]" 是**孤儿串** —— 两版 APK 里实际只存在
    //    "[onBindView] finish position:" / "[onBindView] never attach!" 这类更长串，
    //    DexKit 按字符串常量精确匹配，旧串恒命中不到，导致整个消息 View 监听服务不工作
    //    （表现：防撤回能拦住，但既没有灰标也没有「已撤回」标记）；
    //  · "MicroMsg.MvvmChattingItem" 两版都存活，且与该日志串位于同一方法
    //    （8.0.79 = vm5/g.h，8.0.80 = nm5/g.h），两串 AND 唯一命中。
    // 同时允许失败：锚点再失效也不会像以前那样把整个 API 拖垮。
    private val methodChatItemOnBindView by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingStrings(
                "MicroMsg.MvvmChattingItem",
                "[onBindView] finish position:"
            )
        }
    }

    override fun onEnable() {
        if (methodChatItemOnBindView.isPlaceholder) {
            WeLogger.w(TAG, "onBindView 锚点未命中：消息 View 监听（含防撤回徽标）将不可用")
        }
        methodChatItemOnBindView.hookAfter {
            // adapter 缓存要在 View 解析之前做：即使下面拿不到 View 而提前返回，
            // 也要把 adapter 存下来，供 findMessageViewById 按消息 id 反查气泡。
            cacheAdapter(this)
            val holder = args[0] ?: return@hookAfter
            val view = holder.reflekt()
                .firstFieldOrNull {
                    type = View::class
                    superclass()
                }
                ?.get() as? View ?: return@hookAfter
            val message = runCatching { getMsgInfoFromParam(this) }.getOrElse {
                WeLogger.w(TAG, "getMsgInfoFromParam failed: ${it.message}")
                return@hookAfter
            }
            ensureAttachStateListener(view)

            val previous = synchronized(currentBindings) { currentBindings[view] }
            val bindingChanged = previous?.instance !== message.instance
            if (view.isAttachedToWindow && bindingChanged && previous != null) {
                dispatchLifecycle { it.onMessageViewDetached(view, previous) }
            }
            synchronized(currentBindings) {
                currentBindings[view] = message
            }
            if (view.isAttachedToWindow && bindingChanged) {
                dispatchLifecycle { it.onMessageViewAttached(view, message) }
            }

            for (listener in listeners) {
                try {
                    listener.onCreateView(this, view)
                } catch (ex: Exception) {
                    WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
                }
            }
        }
    }

    // ── 按消息 id 反查 View（不依赖 currentBindings） ──────────────────────────
    //
    // 消息列表 adapter（8.0.79/8.0.80 = com.tencent.mm.ui.chatting.adapter.k）同时提供：
    //   b1(J)Landroid/view/View;              按消息 id 取该行 View
    //   X0(J)Lcom/tencent/mm/storage/f9;      按消息 id 取 MsgInfo
    // 这里按「1 个 long 形参 + 返回类型」做行为匹配，不写死混淆后的方法名。
    // 用途：撤回发生时目标消息可能还没走过一次 onBind（currentBindings 里没有记录），
    // 只靠绑定表刷不到，必须能按 id 直接找到已经显示在屏幕上的那一行。

    @Volatile
    private var cachedAdapter: Any? = null

    private fun cacheAdapter(param: XC_MethodHook.MethodHookParam) {
        if (cachedAdapter != null) return
        runCatching {
            if (WeMessageApi.classChattingDataAdapter.isPlaceholder) return
            val adapter = param.thisObject?.reflekt()
                ?.firstFieldOrNull { type = WeMessageApi.classChattingDataAdapter.clazz }
                ?.get()
            if (adapter != null) {
                cachedAdapter = adapter
                WeLogger.i(TAG, "cached chatting adapter: ${adapter.javaClass.name}")
            }
        }.onFailure { WeLogger.w(TAG, "cacheAdapter failed: ${it.message}") }
    }

    /** 按 msgSvrId 找到已创建的消息 View 及其 MessageInfo；找不到返回 null。 */
    fun findMessageViewById(serverId: Long): Pair<View, MessageInfo>? {
        if (serverId == 0L) return null
        val adapter = cachedAdapter ?: return null
        val view = runCatching {
            pickAdapterMethod(adapter, View::class.java)?.invoke(adapter, serverId) as? View
        }.getOrNull() ?: return null
        if (WeMessageApi.classMsgInfo.isPlaceholder) return null
        val msg = runCatching {
            pickAdapterMethod(adapter, WeMessageApi.classMsgInfo.clazz)?.invoke(adapter, serverId)
        }.getOrNull() ?: return null
        return view to MessageInfo(msg)
    }

    private fun pickAdapterMethod(
        target: Any,
        returnType: Class<*>,
    ): java.lang.reflect.Method? = runCatching {
        target.javaClass.methods.firstOrNull { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == java.lang.Long::class.java &&
                returnType.isAssignableFrom(method.returnType)
        }
    }.getOrNull()

    private fun ensureAttachStateListener(view: View) {
        synchronized(attachStateListeners) {
            if (attachStateListeners.containsKey(view)) return
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewAttached(view, message) }
                }

                override fun onViewDetachedFromWindow(view: View) {
                    val message = synchronized(currentBindings) { currentBindings[view] } ?: return
                    dispatchLifecycle { it.onMessageViewDetached(view, message) }
                }
            }
            attachStateListeners[view] = listener
            view.addOnAttachStateChangeListener(listener)
        }
    }

    private fun dispatchLifecycle(callback: (IMessageViewLifecycleListener) -> Unit) {
        for (listener in lifecycleListeners) {
            try {
                callback(listener)
            } catch (ex: Exception) {
                WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", ex)
            }
        }
    }

    fun getChattingContextFromParam(param: XC_MethodHook.MethodHookParam): Any {
        return param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingContext.clazz }
            .get()!!
    }

    /** Returns every (view, message) pair currently bound whose message satisfies [matcher]. */
    fun findBoundViews(matcher: (MessageInfo) -> Boolean): List<Pair<View, MessageInfo>> {
        synchronized(currentBindings) {
            return currentBindings.entries
                .filter { matcher(it.value) }
                .map { it.key to it.value }
        }
    }

    fun getBoundMessage(view: View): MessageInfo? =
        synchronized(currentBindings) { currentBindings[view] }

    fun getMsgInfoFromParam(param: XC_MethodHook.MethodHookParam): MessageInfo {
        val item = param.args[1]
        val current = item?.let { findMessageInfo(it) }
        if (current != null) return current

        val chattingDataAdapter = param.thisObject!!.reflekt()
            .firstField { type = WeMessageApi.classChattingDataAdapter.clazz }
            .get()!!
        val msgId = param.args[2] as Int
        val msgInfo = chattingDataAdapter.reflekt()
            .firstMethod { name = "getItem" }
            .invoke(msgId)!!
        return MessageInfo(msgInfo)
    }

    private fun findMessageInfo(value: Any, depth: Int = 2): MessageInfo? {
        if (value.javaClass == WeMessageApi.classMsgInfo.clazz) {
            return MessageInfo(value)
        }
        if (depth == 0) return null

        value.reflekt().fields { superclass() }.forEach { field ->
            val nested = runCatching { field.get() }.getOrNull() ?: return@forEach
            val result = findMessageInfo(nested, depth - 1)
            if (result != null) return result
        }
        return null
    }
}
