package h.Hchat.hooks.items.script

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.Editable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.OutgoingTextDecoratorRegistry
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object ScriptSendButtonHook {
    private const val TAG = "[Hchat:Script]"
    private const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    private const val TEXT_DECORATION_EXTRA = "hchat_send_text_decoration"
    private const val INPUT_SESSION_EXTRA = "hchat_send_input_session"
    @Volatile
    private var installed = false
    @Volatile
    private var clickHookInstalled = false
    @Volatile
    private var longPressTouchHookInstalled = false
    @Volatile
    private var inputTrackingHookInstalled = false
    private val chatFooterFieldCache = mutableMapOf<Class<*>, Field?>()
    private val inputFieldCache = mutableMapOf<Class<*>, Field?>()
    private val noArgMethodCache = ConcurrentHashMap<String, Method>()
    private val oneArgMethodCache = ConcurrentHashMap<String, Method>()
    private val handlers = CopyOnWriteArrayList<RegisteredHandler>()
    private val longPressStates = Collections.synchronizedMap(
        WeakHashMap<View, SendLongPressState>()
    )
    private val trackedInputViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val inputStarts = Collections.synchronizedMap(WeakHashMap<View, InputStart>())
    private val pendingInputStarts = Collections.synchronizedMap(
        WeakHashMap<View, PendingInputStart>()
    )
    private val dispatchingSyntheticCancel = ThreadLocal<Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastChatFooter = WeakReference<Any>(null)

    fun interface Handler {
        fun onClick(text: String): Boolean
    }

    class Subscription internal constructor(private val unsubscribeAction: () -> Unit) {
        @Volatile
        private var active = true

        fun unsubscribe() {
            if (!active) return
            active = false
            unsubscribeAction()
        }
    }

    fun registerHandler(id: String, handler: Handler): Subscription {
        handlers.removeAll { it.id == id }
        val entry = RegisteredHandler(id, handler)
        handlers += entry
        return Subscription { handlers.remove(entry) }
    }

    @Synchronized
    fun install(context: FeatureContext): Boolean {
        if (installed) return true
        runCatching {
            val finder = context.dexFinder()
            finder.resolveScriptSendHookApi()
            val method = finder.chatFooterSendClickMethod
            if (method == null) {
                h.Hchat.utils.HLog.e("$TAG 发送按钮Hook失败: 未定位到ChatFooter发送入口")
                return false
            }
            if (!clickHookInstalled) {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val footer = findChatFooter(param.thisObject) ?: return
                        lastChatFooter = WeakReference(footer)
                        rememberActivityFromFooter(footer)
                        val input = findInputView(footer)
                        trackInputView(input)
                        val inputView = input as? View
                        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
                        val inputStart = inputView?.let { currentInputStart(it, talker) }
                        val inputDuration = inputStart
                            ?.let { (SystemClock.elapsedRealtime() - it.startedAt).coerceAtLeast(0L) }
                            ?: 0L
                        param.setObjectExtra(
                            INPUT_SESSION_EXTRA,
                            InputSendSession(footer, inputView)
                        )
                        val text = readInputText(footer)
                        val handled = OutgoingTextDecoratorRegistry.withInputDuration(inputDuration) {
                            val handledByModule = dispatchHandlers(text)
                            val handledByScript = if (handledByModule) {
                                false
                            } else {
                                ScriptPluginRuntime.dispatchOnClickSendBtn(text).intercepted
                            }
                            handledByModule || handledByScript
                        }
                        if (!handled) {
                            val currentText = readInputText(footer)
                            runCatching {
                                applyTextDecorators(
                                    footer,
                                    talker,
                                    currentText,
                                    inputDuration
                                )
                            }
                                .onFailure {
                                    h.Hchat.utils.HLog.e("$TAG 发送文字装饰失败: ${it.message}", it)
                                }
                                .getOrNull()
                                ?.let { param.setObjectExtra(TEXT_DECORATION_EXTRA, it) }
                            return
                        }
                        clearInputText(footer)
                        inputView?.let { view ->
                            rememberPendingInputStart(view, inputStart, talker, text)
                            clearInputStart(view)
                        }
                        param.result = null
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val decoration = param.getObjectExtra(TEXT_DECORATION_EXTRA)
                            as? AppliedTextDecoration
                        if (decoration != null) {
                            runCatching { restoreRejectedDecoration(decoration) }
                                .onFailure {
                                    h.Hchat.utils.HLog.e("$TAG 恢复未发送文字失败: ${it.message}", it)
                                }
                        }
                        val session = param.getObjectExtra(INPUT_SESSION_EXTRA) as? InputSendSession
                            ?: return
                        val inputView = session.inputView ?: return
                        if (readInputText(session.footer).isEmpty()) {
                            clearInputStart(inputView)
                        }
                    }
                })
                clickHookInstalled = true
            }
            inputTrackingHookInstalled = installInputTrackingHook(context.hostClassLoader())
            longPressTouchHookInstalled = installLongPressTouchHook(method.declaringClass)
            installed = clickHookInstalled && inputTrackingHookInstalled && longPressTouchHookInstalled
        }.onFailure {
            h.Hchat.utils.HLog.e("$TAG 发送按钮Hook异常: ${it.message}", it)
            return false
        }
        return installed
    }

    private fun installInputTrackingHook(classLoader: ClassLoader): Boolean {
        if (inputTrackingHookInstalled) return true
        val footerClass = KavaReflector.loadClass(CHAT_FOOTER, classLoader) ?: run {
            h.Hchat.utils.HLog.e("$TAG 输入时长Hook失败: 未找到ChatFooter")
            return false
        }
        val attachMethod = KavaReflector.declaredMethods(footerClass).singleOrNull { candidate ->
            candidate.name == "onAttachedToWindow" &&
                candidate.returnType == Void.TYPE &&
                candidate.parameterTypes.isEmpty()
        } ?: run {
            h.Hchat.utils.HLog.e("$TAG 输入时长Hook失败: 未找到ChatFooter挂载方法")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(attachMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    trackInputView(findInputView(param.thisObject))
                }
            })
            true
        }.getOrElse {
            h.Hchat.utils.HLog.e(
                "$TAG 输入时长Hook失败: ${attachMethod.toGenericString()} ${it.message}",
                it
            )
            false
        }
    }

    private fun installLongPressTouchHook(sendClickListenerClass: Class<*>): Boolean {
        if (longPressTouchHookInstalled) return true
        val method = KavaReflector.declaredMethods(View::class.java).firstOrNull { candidate ->
            candidate.name == "dispatchTouchEvent" &&
                candidate.returnType == Boolean::class.javaPrimitiveType &&
                candidate.parameterTypes.contentEquals(arrayOf(MotionEvent::class.java))
        }
        if (method == null) {
            h.Hchat.utils.HLog.e("$TAG 长按发送按钮Hook失败: 未找到View.dispatchTouchEvent")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (dispatchingSyntheticCancel.get() == true) return
                    runCatching {
                        handleLongPressTouch(param, sendClickListenerClass)
                    }.onFailure {
                        h.Hchat.utils.HLog.e("$TAG 长按发送按钮触摸处理异常: ${it.message}", it)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val event = param.args?.getOrNull(0) as? MotionEvent ?: return
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && param.result != true) {
                        cancelLongPressState(view)
                    }
                }
            })
            true
        }.getOrElse {
            h.Hchat.utils.HLog.e(
                "$TAG 长按发送按钮Hook失败: ${method.toGenericString()} ${it.message}",
                it
            )
            false
        }
    }

    private fun handleLongPressTouch(
        param: XC_MethodHook.MethodHookParam,
        sendClickListenerClass: Class<*>
    ) {
        val view = param.thisObject as? View ?: return
        val event = param.args?.getOrNull(0) as? MotionEvent ?: return
        val action = event.actionMasked
        if (isTrackedInputView(view)) {
            if (action == MotionEvent.ACTION_DOWN) beginInputSession(view)
            return
        }
        val current = longPressState(view)
        if (current?.consumed == true && action != MotionEvent.ACTION_DOWN) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                action == MotionEvent.ACTION_OUTSIDE
            ) {
                cancelLongPressState(view, current)
            }
            view.isPressed = false
            param.result = true
            return
        }

        when (action) {
            MotionEvent.ACTION_DOWN -> beginLongPress(
                view,
                event,
                sendClickListenerClass
            )

            MotionEvent.ACTION_MOVE -> {
                val state = current ?: return
                val pointerIndex = event.findPointerIndex(state.pointerId)
                if (pointerIndex < 0 ||
                    kotlin.math.abs(event.getX(pointerIndex) - state.downX) > state.touchSlop ||
                    kotlin.math.abs(event.getY(pointerIndex) - state.downY) > state.touchSlop
                ) {
                    cancelLongPressState(view, state)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> current?.let {
                cancelLongPressState(view, it)
            }

            MotionEvent.ACTION_POINTER_UP -> current?.let { state ->
                if (event.getPointerId(event.actionIndex) == state.pointerId) {
                    cancelLongPressState(view, state)
                }
            }

            MotionEvent.ACTION_UP -> current?.let { state ->
                if (event.eventTime - state.downTime >= state.timeoutMs) {
                    triggerLongPress(state, sendClickListenerClass)
                }
                if (state.consumed && longPressState(view) === state) {
                    cancelLongPressState(view, state)
                    view.isPressed = false
                    param.result = true
                } else {
                    cancelLongPressState(view, state)
                }
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_OUTSIDE -> current?.let { state ->
                cancelLongPressState(view, state)
            }
        }
    }

    private fun beginLongPress(
        view: View,
        event: MotionEvent,
        sendClickListenerClass: Class<*>
    ) {
        cancelLongPressState(view)
        if (!ScriptPluginRuntime.hasLongSendButtonCallbacks() || !view.isEnabled || !view.isClickable) {
            return
        }
        val clickListener = currentClickListener(view) ?: return
        if (!sendClickListenerClass.isInstance(clickListener) || findChatFooter(clickListener) == null) {
            return
        }
        val actionIndex = event.actionIndex
        val state = SendLongPressState(
            viewRef = WeakReference(view),
            pointerId = event.getPointerId(actionIndex),
            downTime = event.downTime,
            downX = event.getX(actionIndex),
            downY = event.getY(actionIndex),
            touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop,
            timeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
        )
        val trigger = Runnable { triggerLongPress(state, sendClickListenerClass) }
        state.trigger = trigger
        synchronized(longPressStates) {
            longPressStates[view] = state
        }
        if (!mainHandler.postDelayed(trigger, state.timeoutMs)) {
            cancelLongPressState(view, state)
        }
    }

    private fun triggerLongPress(
        state: SendLongPressState,
        sendClickListenerClass: Class<*>
    ) {
        val view = state.viewRef.get() ?: return
        if (longPressState(view) !== state || !view.isAttachedToWindow || !view.isShown ||
            !view.hasWindowFocus() || !view.isEnabled ||
            !ScriptPluginRuntime.hasLongSendButtonCallbacks()
        ) {
            cancelLongPressState(view, state)
            return
        }
        val clickListener = currentClickListener(view)
        if (!sendClickListenerClass.isInstance(clickListener)) {
            cancelLongPressState(view, state)
            return
        }
        val footer = findChatFooter(clickListener)
        if (footer == null) {
            cancelLongPressState(view, state)
            return
        }
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val intercepted = runCatching {
            lastChatFooter = WeakReference(footer)
            rememberActivityFromFooter(footer)
            val input = findInputView(footer)
            trackInputView(input)
            val inputView = input as? View
            val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
            val inputStart = inputView?.let { currentInputStart(it, talker) }
            val inputDuration = inputStart
                ?.let { (SystemClock.elapsedRealtime() - it.startedAt).coerceAtLeast(0L) }
                ?: 0L
            val text = readInputText(footer)
            val handled = OutgoingTextDecoratorRegistry.withInputDuration(inputDuration) {
                ScriptPluginRuntime.dispatchOnLongClickSendBtn(text).intercepted
            }
            if (handled && inputView != null) {
                rememberPendingInputStart(inputView, inputStart, talker, text)
                clearInputStart(inputView)
            }
            handled
        }.getOrElse {
            cancelLongPressState(view, state)
            h.Hchat.utils.HLog.e("$TAG 长按发送按钮分发异常: ${it.message}", it)
            return
        }
        if (!intercepted) {
            cancelLongPressState(view, state)
            return
        }
        if (longPressState(view) !== state) return
        state.consumed = true
        runCatching { clearInputText(footer) }
            .onFailure {
                h.Hchat.utils.HLog.e("$TAG 长按发送按钮清空输入框失败: ${it.message}", it)
            }
        view.cancelLongPress()
        dispatchSyntheticCancel(view, state)
        view.isPressed = false
    }

    private fun dispatchSyntheticCancel(view: View, state: SendLongPressState) {
        val event = MotionEvent.obtain(
            state.downTime,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL,
            state.downX,
            state.downY,
            0
        )
        dispatchingSyntheticCancel.set(true)
        try {
            runCatching { view.dispatchTouchEvent(event) }
                .onFailure {
                    h.Hchat.utils.HLog.e("$TAG 发送按钮取消原触摸失败: ${it.message}", it)
                }
        } finally {
            dispatchingSyntheticCancel.remove()
            event.recycle()
        }
    }

    private fun longPressState(view: View): SendLongPressState? =
        synchronized(longPressStates) { longPressStates[view] }

    private fun cancelLongPressState(
        view: View,
        expected: SendLongPressState? = null
    ): SendLongPressState? {
        val removed = synchronized(longPressStates) {
            val current = longPressStates[view] ?: return@synchronized null
            if (expected != null && current !== expected) return@synchronized null
            longPressStates.remove(view)
        }
        removed?.trigger?.let { mainHandler.removeCallbacks(it) }
        return removed
    }

    private fun currentClickListener(view: View): View.OnClickListener? {
        val listenerInfo = KavaReflector.readField(view, "mListenerInfo") ?: return null
        return KavaReflector.readField(listenerInfo, "mOnClickListener") as? View.OnClickListener
    }

    internal fun trackInputView(input: Any?) {
        val view = input as? View ?: return
        synchronized(trackedInputViews) { trackedInputViews[view] = true }
    }

    private fun isTrackedInputView(view: View): Boolean =
        synchronized(trackedInputViews) { trackedInputViews.containsKey(view) }

    private fun beginInputSession(view: View) {
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
        synchronized(pendingInputStarts) { pendingInputStarts.remove(view) }
        synchronized(inputStarts) {
            val current = inputStarts[view]
            if (current == null || current.talker != talker) {
                inputStarts[view] = InputStart(SystemClock.elapsedRealtime(), talker)
            }
        }
    }

    private fun clearInputStart(view: View) {
        synchronized(inputStarts) { inputStarts.remove(view) }
    }

    private fun currentInputStart(view: View, talker: String): InputStart? =
        synchronized(inputStarts) {
            inputStarts[view]?.takeIf { it.talker == talker }
        }

    private fun rememberPendingInputStart(
        view: View,
        inputStart: InputStart?,
        talker: String,
        text: String
    ) {
        if (inputStart == null || inputStart.talker != talker || text.isEmpty()) return
        synchronized(pendingInputStarts) {
            pendingInputStarts[view] = PendingInputStart(inputStart, talker, text)
        }
    }

    private fun restorePendingInputStart(view: View, talker: String, text: String) {
        val pending = synchronized(pendingInputStarts) {
            pendingInputStarts[view]?.takeIf { it.talker == talker && it.text == text }
                ?.also { pendingInputStarts.remove(view) }
        } ?: return
        synchronized(inputStarts) { inputStarts[view] = pending.inputStart }
    }

    fun restoreInputTextIfEmpty(talker: String, text: String) {
        if (talker.isBlank() || text.isEmpty()) return
        mainHandler.post {
            if (h.Hchat.hooks.api.core.WeChatApis.chatPage()?.currentTalker().orEmpty() != talker) return@post
            val footer = lastChatFooter.get() ?: return@post
            val input = findInputView(footer) ?: return@post
            val view = input as? View ?: footer as? View ?: return@post
            if (!view.isAttachedToWindow || readInputText(footer).isNotEmpty()) return@post
            if (setInputText(input, text)) {
                (input as? View)?.let { restorePendingInputStart(it, talker, text) }
            }
        }
    }

    private fun dispatchHandlers(text: String): Boolean {
        for (entry in handlers) {
            try {
                if (entry.handler.onClick(text)) return true
            } catch (throwable: Throwable) {
                h.Hchat.utils.HLog.e("$TAG 发送按钮模块回调失败: ${entry.id} ${throwable.message}", throwable)
            }
        }
        return false
    }

    private fun applyTextDecorators(
        chatFooter: Any,
        talker: String,
        originalText: String,
        inputDurationMs: Long
    ): AppliedTextDecoration? {
        val decoration = OutgoingTextDecoratorRegistry.decorate(
            talker,
            originalText,
            inputDurationMs
        ) ?: return null
        val combinedPrefix = decoration.prefix
        val combinedSuffix = decoration.suffix
        val currentText = decoration.text

        val input = findInputView(chatFooter)
        val editable = when (input) {
            is TextView -> input.text as? Editable
            null -> null
            else -> invokeNoArg(input, "getText") as? Editable
        }
        if (editable != null) {
            if (combinedSuffix.isNotEmpty()) editable.insert(editable.length, combinedSuffix)
            if (combinedPrefix.isNotEmpty()) editable.insert(0, combinedPrefix)
            return AppliedTextDecoration(
                input = input ?: chatFooter,
                originalText = originalText,
                decoratedText = currentText,
                prefixLength = combinedPrefix.length,
                suffixLength = combinedSuffix.length
            )
        }
        val receiver = input ?: chatFooter
        if (!setInputText(receiver, currentText)) return null
        return AppliedTextDecoration(
            input = receiver,
            originalText = originalText,
            decoratedText = currentText,
            prefixLength = combinedPrefix.length,
            suffixLength = combinedSuffix.length
        )
    }

    private fun restoreRejectedDecoration(decoration: AppliedTextDecoration) {
        val current = when (val input = decoration.input) {
            is TextView -> input.text
            else -> invokeNoArg(input, "getText") as? CharSequence
        } ?: return
        if (current.toString() != decoration.decoratedText) return

        val editable = current as? Editable
        if (editable != null) {
            if (decoration.suffixLength > 0) {
                editable.delete(editable.length - decoration.suffixLength, editable.length)
            }
            if (decoration.prefixLength > 0) {
                editable.delete(0, decoration.prefixLength)
            }
        } else {
            setInputText(decoration.input, decoration.originalText)
        }
    }

    private fun setInputText(input: Any, text: String): Boolean {
        if (input is TextView) {
            input.text = text
            return true
        }
        val method = findOneArgMethod(input.javaClass, "setText", String::class.java)
            ?: return false
        KavaReflector.invoke(method, input, text)
        return true
    }

    private fun findChatFooter(listener: Any?): Any? {
        if (listener == null) return null
        val clazz = listener.javaClass
        val cached = synchronized(chatFooterFieldCache) {
            if (chatFooterFieldCache.containsKey(clazz)) chatFooterFieldCache[clazz] else null
        }
        if (cached != null) return KavaReflector.readField(cached, listener)
        if (synchronized(chatFooterFieldCache) { chatFooterFieldCache.containsKey(clazz) }) return null
        val resolved = allFields(clazz).firstOrNull { it.type.name == CHAT_FOOTER }
            ?.let { KavaReflector.accessible(it) }
        synchronized(chatFooterFieldCache) { chatFooterFieldCache[clazz] = resolved }
        return resolved?.let { KavaReflector.readField(it, listener) }
    }

    private fun rememberActivityFromFooter(chatFooter: Any) {
        val activity = activityFromContext((chatFooter as? View)?.context)
            ?: activityFromContext((findInputView(chatFooter) as? View)?.context)
            ?: return
        h.Hchat.hooks.api.core.WeChatApis.currentActivity()?.updateCurrentActivity(activity)
    }

    private fun activityFromContext(context: Context?): Activity? {
        var current = context
        var depth = 0
        while (current != null && depth < 8) {
            if (current is Activity) return current
            current = (current as? ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun readInputText(chatFooter: Any): String {
        findInputView(chatFooter)?.let { input ->
            invokeNoArg(input, "getText")?.let { return it.toString() }
        }
        invokeNoArg(chatFooter, "getText")?.let { return it.toString() }
        return ""
    }

    private fun clearInputText(chatFooter: Any) {
        findInputView(chatFooter)?.let { input ->
            if (clearTextOnInput(input)) return
        }
        invokeOneArg(chatFooter, "setText", "")
    }

    private fun clearTextOnInput(input: Any): Boolean {
        if (input is TextView) {
            input.text = ""
            return true
        }
        val text = invokeNoArg(input, "getText")
        val clear = findNoArgMethod(text?.javaClass, "clear")
        if (text != null && clear != null) {
            KavaReflector.invoke(clear, text)
            return true
        }
        val setText = findOneArgMethod(input.javaClass, "setText", CharSequence::class.java)
            ?: findOneArgMethod(input.javaClass, "setText", String::class.java)
        if (setText != null) {
            KavaReflector.invoke(setText, input, "")
            return true
        }
        return false
    }

    internal fun findInputView(chatFooter: Any): Any? {
        val clazz = chatFooter.javaClass
        val cached = synchronized(inputFieldCache) {
            if (inputFieldCache.containsKey(clazz)) inputFieldCache[clazz] else null
        }
        if (cached != null) return KavaReflector.readField(cached, chatFooter)
        var best: Field? = null
        var bestScore = 0
        for (field in allFields(clazz)) {
            if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
            KavaReflector.accessible(field)
            val value = KavaReflector.readField(field, chatFooter) ?: continue
            val score = inputScore(value.javaClass)
            if (score > bestScore) {
                best = field
                bestScore = score
            }
        }
        val resolved = if (bestScore >= 3) best else null
        if (resolved != null) {
            synchronized(inputFieldCache) { inputFieldCache[clazz] = resolved }
        }
        return resolved?.let { KavaReflector.readField(it, chatFooter) }
    }

    private fun inputScore(clazz: Class<*>): Int {
        var score = 0
        if (findNoArgMethod(clazz, "getText") != null) score += 3
        if (findNoArgMethod(clazz, "clearComposingText") != null) score += 2
        if (findNoArgMethod(clazz, "getSimilarPasteSeqStr") != null) score += 2
        if (findNoArgMethod(clazz, "getPasterContent") != null) score += 1
        if (View::class.java.isAssignableFrom(clazz)) score += 1
        return score
    }

    private fun invokeNoArg(receiver: Any, name: String): Any? {
        val method = findNoArgMethod(receiver.javaClass, name) ?: return null
        return KavaReflector.invoke(method, receiver)
    }

    private fun invokeOneArg(receiver: Any, name: String, arg: Any): Any? {
        val method = findOneArgMethod(receiver.javaClass, name, arg.javaClass) ?: return null
        return KavaReflector.invoke(method, receiver, arg)
    }

    private fun findNoArgMethod(clazz: Class<*>?, name: String): Method? {
        if (clazz == null) return null
        val key = clazz.name + "#" + name
        noArgMethodCache[key]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (method.name == name && method.parameterTypes.isEmpty()) {
                    val accessible = KavaReflector.accessible(method) ?: return null
                    noArgMethodCache[key] = accessible
                    return accessible
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findOneArgMethod(clazz: Class<*>?, name: String, argType: Class<*>): Method? {
        if (clazz == null) return null
        val key = clazz.name + "#" + name + "#" + argType.name
        oneArgMethodCache[key]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (method.name != name || method.parameterTypes.size != 1) continue
                val paramType = method.parameterTypes[0]
                if (wrap(paramType).isAssignableFrom(wrap(argType))) {
                    val accessible = KavaReflector.accessible(method) ?: return null
                    oneArgMethodCache[key] = accessible
                    return accessible
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun wrap(type: Class<*>): Class<*> {
        if (!type.isPrimitive) return type
        return when (type) {
            Boolean::class.javaPrimitiveType -> Boolean::class.javaObjectType
            Byte::class.javaPrimitiveType -> Byte::class.javaObjectType
            Char::class.javaPrimitiveType -> Char::class.javaObjectType
            Short::class.javaPrimitiveType -> Short::class.javaObjectType
            Int::class.javaPrimitiveType -> Int::class.javaObjectType
            Long::class.javaPrimitiveType -> Long::class.javaObjectType
            Float::class.javaPrimitiveType -> Float::class.javaObjectType
            Double::class.javaPrimitiveType -> Double::class.javaObjectType
            Void.TYPE -> Void::class.java
            else -> type
        }
    }

    private fun allFields(clazz: Class<*>?): List<Field> {
        val result = ArrayList<Field>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            result += KavaReflector.declaredFields(current)
            current = current.superclass
        }
        return result
    }

    internal data class RegisteredHandler(val id: String, val handler: Handler)

    private data class AppliedTextDecoration(
        val input: Any,
        val originalText: String,
        val decoratedText: String,
        val prefixLength: Int,
        val suffixLength: Int
    )

    private data class InputStart(
        val startedAt: Long,
        val talker: String
    )

    private data class InputSendSession(
        val footer: Any,
        val inputView: View?
    )

    private data class PendingInputStart(
        val inputStart: InputStart,
        val talker: String,
        val text: String
    )

    private class SendLongPressState(
        val viewRef: WeakReference<View>,
        val pointerId: Int,
        val downTime: Long,
        val downX: Float,
        val downY: Float,
        val touchSlop: Int,
        val timeoutMs: Long
    ) {
        @Volatile
        var consumed = false
        var trigger: Runnable? = null
    }
}
