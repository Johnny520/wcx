package h.Hchat.hooks.items.swipequote

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.VoiceMessageDurationResolver
import h.Hchat.hooks.api.message.SingleMessageMenuLocator
import h.Hchat.hooks.api.message.WeChatRetransmitPayload
import h.Hchat.hooks.api.message.WeChatRetransmitPayloadFactory
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

class SwipeQuoteFeature : BaseFeature() {
    private var adapter: SwipeQuoteAdapter? = null

    override fun featureId(): String = ID

    override fun name(): String = "滑动手势"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SwipeQuoteSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        adapter = SwipeQuoteAdapter(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            adapter?.install() == true
        }
        DexInstallScheduler.schedule(REPEAT_MENU_INSTALL_KEY, "长按菜单复读") {
            adapter?.installRepeatMenu() == true
        }
    }

    companion object {
        const val ID = "swipe_quote"
        private const val REPEAT_MENU_INSTALL_KEY = "swipe_quote:repeat_menu"
    }
}

private class SwipeQuoteAdapter(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val itemMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val itemListFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderRootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val newQuoteMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val quoteInfoMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val quoteIdMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val quoteVisibilityMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val footerRefreshMethodCache = ConcurrentHashMap<Class<*>, List<Method>>()
    private val voiceContentGetterCache = ConcurrentHashMap<Class<*>, Method?>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val repeatVoiceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-SwipeRepeatVoice").apply { isDaemon = true }
    }
    @Volatile private var dexQuoteMethod: Method? = null
    @Volatile private var lastFooterRef = WeakReference<Any>(null)
    private val rootTargets = Collections.synchronizedMap(WeakHashMap<View, QuoteTarget>())
    private val recyclerStates = Collections.synchronizedMap(WeakHashMap<View, TouchState>())
    private val repeatMenuTargets = Collections.synchronizedMap(WeakHashMap<MenuItem, QuoteTarget>())
    private val repeatMenuTargetsByGroup = ConcurrentHashMap<Int, QuoteTarget>()
    private val repeatMenuHookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val methodCachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_swipe_quote_method_cache")
    @Volatile private var adapterBindInstalled = false
    @Volatile private var recyclerInterceptInstalled = false
    @Volatile private var recyclerOnTouchInstalled = false
    @Volatile private var footerLifecycleInstalled = false
    @Volatile private var retransmitDoneHookInstalled = false

    @Synchronized
    fun install(): Boolean {
        val adapterOk = installAdapterBindHook()
        val touchOk = installRecyclerDispatchHook()
        val footerOk = installFooterLifecycleHook()
        val doneOk = installRetransmitDoneHook()
        return adapterOk && touchOk && footerOk && doneOk
    }

    @Synchronized
    fun installRepeatMenu(): Boolean {
        val menuMethods = SingleMessageMenuLocator.menuCreateMethods(context, logger)
        val clickMethods = SingleMessageMenuLocator.menuClickMethods(context, logger)
        var menuHooked = 0
        var clickHooked = 0
        menuMethods.forEach { method ->
            if (hookRepeatMenuMethod(method, menuCreate = true)) menuHooked++
        }
        clickMethods.forEach { method ->
            if (hookRepeatMenuMethod(method, menuCreate = false)) clickHooked++
        }
        if (menuHooked <= 0 || clickHooked <= 0) {
            logger("长按复读菜单Hook未安装", null)
        }
        return menuHooked > 0 && clickHooked > 0
    }

    private fun hookRepeatMenuMethod(method: Method, menuCreate: Boolean): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!repeatMenuHookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (menuCreate) addRepeatMenu(param)
                }

                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!menuCreate) handleRepeatMenuClick(param)
                }
            })
            true
        }.getOrElse {
            repeatMenuHookedMethods.remove(method)
            logger("长按复读菜单Hook安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addRepeatMenu(param: XC_MethodHook.MethodHookParam) {
        clearRepeatMenuTargets()
        if (!isRepeatMenuEnabled()) return
        val args = param.args ?: return
        val menu = args.getOrNull(0) ?: return
        val view = args.getOrNull(1) as? View ?: return
        val source = view.tag ?: return
        val nativeMessage = resolveNativeMessage(source) ?: return
        val msgId = messageId(nativeMessage)
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (msgId <= 0L || talker.isBlank()) return
        val target = QuoteTarget(talker, msgId, nativeMessage)
        if (!isRepeatMenuSupported(target)) return
        val menuItem = addRepeatMenuItem(menu, view, readMenuGroupId(menu)) ?: return
        repeatMenuTargets[menuItem] = target
        repeatMenuTargetsByGroup[menuItem.groupId] = target
    }

    private fun handleRepeatMenuClick(param: XC_MethodHook.MethodHookParam) {
        val menuItem = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        if (menuItem.itemId != MENU_REPEAT_ID) return
        val target = consumeRepeatMenuTarget(menuItem)
        if (target == null || !repeatNativeMessage(target)) {
            showToast("该消息暂不支持复读")
        }
    }

    private fun isRepeatMenuSupported(target: QuoteTarget): Boolean {
        val message = runCatching {
            messageFromNative(target.nativeMessage, target.talker, target.msgId)
                ?: WeChatApis.messageStore()?.getMessageById(target.msgId)
        }.getOrNull() ?: return false
        if (isUnsupportedRepeatMessage(message)) return false
        val normalizedType = WeChatMessageTypes.normalize(message.type)
        return message.isQuote() ||
            message.isVoice() ||
            WeChatRetransmitPayloadFactory.isRetransmittableAppMessage(message) ||
            message.isText() ||
            message.isShareCard() ||
            message.isImage() ||
            message.isEmoji() ||
            message.isLocation() ||
            message.isVideo() ||
            normalizedType == 62
    }

    private fun addRepeatMenuItem(menu: Any, view: View, groupId: Int): MenuItem? {
        val icon = RepeatMenuIconDrawable(view.context)
        findMenuItem(menu, MENU_REPEAT_ID)?.let { item ->
            applyRepeatMenuItem(menu, item, icon)
            return item
        }
        val iconRes = menuIconResId(view, "icons_filled_edit_photo_pencil")
        if (iconRes != 0) {
            val iconMethod = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "c" &&
                    types.size == 5 &&
                    types[0] == Integer.TYPE &&
                    types[1] == Integer.TYPE &&
                    types[2] == Integer.TYPE &&
                    types[3].isAssignableFrom(String::class.java) &&
                    types[4] == Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(
                    iconMethod,
                    menu,
                    groupId,
                    MENU_REPEAT_ID,
                    0,
                    MENU_REPEAT_TITLE,
                    iconRes
                )
            ) {
                return findMenuItem(menu, MENU_REPEAT_ID)?.also { item ->
                    applyRepeatMenuItem(menu, item, icon)
                }
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", groupId, MENU_REPEAT_ID, 0, MENU_REPEAT_TITLE)
            ?: KavaReflector.invokeMethod(menu, "add", groupId, MENU_REPEAT_ID, 0, MENU_REPEAT_TITLE as CharSequence)
        if (added is MenuItem) {
            applyRepeatMenuItem(menu, added, icon)
            return added
        }
        if (added != null) {
            return findMenuItem(menu, MENU_REPEAT_ID)?.also { item ->
                applyRepeatMenuItem(menu, item, icon)
            }
        }
        val fallback = KavaReflector.invokeMethod(menu, "f", MENU_REPEAT_ID, MENU_REPEAT_TITLE)
            ?: KavaReflector.invokeMethod(menu, "f", MENU_REPEAT_ID, MENU_REPEAT_TITLE as CharSequence)
        return ((fallback as? MenuItem) ?: findMenuItem(menu, MENU_REPEAT_ID))?.also { item ->
            applyRepeatMenuItem(menu, item, icon)
        }
    }

    private fun applyRepeatMenuItem(menu: Any, item: MenuItem, icon: Drawable) {
        runCatching { item.setIcon(icon) }
        moveMenuItemToFront(menu, item)
    }

    private fun moveMenuItemToFront(menu: Any, item: MenuItem) {
        var current: Class<*>? = menu.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!java.util.List::class.java.isAssignableFrom(field.type)) continue
                @Suppress("UNCHECKED_CAST")
                val items = KavaReflector.readField(field, menu) as? MutableList<Any?> ?: continue
                val index = items.indexOfFirst { candidate ->
                    candidate === item || (candidate as? MenuItem)?.itemId == MENU_REPEAT_ID
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

    private fun menuIconResId(view: View, iconName: String): Int {
        val resources = view.context.resources
        val packageName = view.context.packageName
        for (type in arrayOf("raw", "drawable")) {
            val id = resources.getIdentifier(iconName, type, packageName)
            if (id != 0) return id
        }
        return 0
    }

    private fun readMenuGroupId(menu: Any): Int {
        val size = (KavaReflector.invokeMethod(menu, "size") as? Number)?.toInt() ?: 0
        for (index in 0 until size) {
            val item = KavaReflector.invokeMethod(menu, "getItem", index) as? MenuItem ?: continue
            return item.groupId
        }
        return 0
    }

    private fun findMenuItem(menu: Any, itemId: Int): MenuItem? {
        return KavaReflector.invokeMethod(menu, "findItem", itemId) as? MenuItem
    }

    private fun clearRepeatMenuTargets() {
        repeatMenuTargets.clear()
        repeatMenuTargetsByGroup.clear()
    }

    private fun consumeRepeatMenuTarget(menuItem: MenuItem): QuoteTarget? {
        val target = repeatMenuTargets.remove(menuItem) ?: repeatMenuTargetsByGroup.remove(menuItem.groupId)
        clearRepeatMenuTargets()
        return target
    }

    private fun showToast(message: String) {
        val activity = WeChatApis.currentActivity()?.currentActivity()
        val toastContext = activity ?: context.hostContext()
        mainHandler.post {
            Toast.makeText(toastContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun installAdapterBindHook(): Boolean {
        if (adapterBindInstalled) return true
        val method = locateAdapterBindMethod() ?: run {
            logger("左滑引用定位聊天适配器失败", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bindMessageRoot(param)
                }
            })
            adapterBindInstalled = true
            true
        }.getOrElse {
            logger("左滑引用聊天适配器Hook失败", it)
            false
        }
    }

    private fun installRecyclerDispatchHook(): Boolean {
        if (recyclerInterceptInstalled && recyclerOnTouchInstalled) return true
        var interceptHooked = recyclerInterceptInstalled
        var touchHooked = recyclerOnTouchInstalled
        for (className in RECYCLER_VIEW_CLASSES) {
            val recyclerViewClass = KavaReflector.loadClass(className, context.hostClassLoader()) ?: continue
            if (!interceptHooked) {
                interceptHooked = hookRecyclerTouchMethod(recyclerViewClass, "onInterceptTouchEvent")
            }
            if (!touchHooked) {
                touchHooked = hookRecyclerTouchMethod(recyclerViewClass, "onTouchEvent")
            }
        }
        recyclerInterceptInstalled = interceptHooked
        recyclerOnTouchInstalled = touchHooked
        return interceptHooked && touchHooked
    }

    private fun installFooterLifecycleHook(): Boolean {
        if (footerLifecycleInstalled) return true
        val footerClass = KavaReflector.loadClass(CHAT_FOOTER, context.hostClassLoader()) ?: return false
        val onAttached = KavaReflector.findMethodRecursive(footerClass, "onAttachedToWindow") ?: return false
        val onDetached = KavaReflector.findMethodRecursive(footerClass, "onDetachedFromWindow")
        return runCatching {
            HookRegistry.get().hook(onAttached, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val footer = param.thisObject
                    if (footer is View) {
                        lastFooterRef = WeakReference(footer)
                    }
                }
            })
            if (onDetached != null) {
                HookRegistry.get().hook(onDetached, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (lastFooterRef.get() === param.thisObject) {
                            lastFooterRef = WeakReference(null)
                        }
                    }
                })
            }
            footerLifecycleInstalled = true
            true
        }.getOrElse {
            logger("左滑引用输入栏生命周期Hook失败", it)
            false
        }
    }

    private fun installRetransmitDoneHook(): Boolean {
        if (retransmitDoneHookInstalled) return true
        val methods = locateRetransmitDoneMethods()
        if (methods.isEmpty()) return false
        for (method in methods) {
            val accessible = KavaReflector.accessible(method) ?: continue
            runCatching {
                HookRegistry.get().hook(accessible, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? android.app.Activity ?: return
                        if (!activity.intent.getBooleanExtra(EXTRA_HCHAT_SILENT_REPEAT, false)) return
                        val targets = ArrayList<String>()
                        activity.intent.getStringExtra("Select_Conv_User")
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.let { targets.addAll(it) }
                        val result = Intent().apply {
                            putStringArrayListExtra("SendMsgUsernames", targets)
                            putExtra("sendResult", 0)
                        }
                        activity.setResult(android.app.Activity.RESULT_OK, result)
                        activity.finish()
                        param.result = null
                    }
                })
            }.onFailure {
                logger("左滑右滑重发完成Hook失败", it)
                return false
            }
        }
        retransmitDoneHookInstalled = true
        return true
    }

    private fun locateRetransmitDoneMethods(): List<Method> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.loadList(methodCachePrefs, methodCacheKey, context.hostClassLoader(), "retransmit_done")
            .filter { isRetransmitDoneCandidate(it) }
            .distinctBy { it.toGenericString() }
        if (cached.isNotEmpty()) return cached
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        declaredClass(MSG_RETRANSMIT_UI)
                        usingStrings(listOf("sendResult", "SendMsgUsernames"))
                    })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter { isRetransmitDoneCandidate(it) }
                .distinctBy { it.toGenericString() }
        }.getOrDefault(emptyList())
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(methodCachePrefs, methodCacheKey, "retransmit_done", methods)
        } else {
            DexMethodCache.clear(methodCachePrefs, methodCacheKey, "retransmit_done")
        }
        return methods
    }

    private fun isRetransmitDoneCandidate(method: Method): Boolean {
        return method.declaringClass.name == MSG_RETRANSMIT_UI &&
            method.parameterTypes.size == 1 &&
            method.parameterTypes[0] == String::class.java &&
            method.returnType == Void.TYPE
    }

    private fun hookRecyclerTouchMethod(recyclerViewClass: Class<*>, methodName: String): Boolean {
        val method = KavaReflector.findMethodRecursive(
            recyclerViewClass,
            methodName,
            MotionEvent::class.java
        )?.takeIf { it.declaringClass.name == recyclerViewClass.name } ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val event = param.args?.getOrNull(0) as? MotionEvent ?: return
                    if (!isEnabled()) return
                    val hit = findRecyclerTarget(view, event.x, event.y)
                    if (handleTouch(
                            view,
                            event,
                            hit,
                            recyclerStates,
                            methodName == "onInterceptTouchEvent"
                        )) {
                        param.result = true
                    }
                }
            })
            true
        }.getOrElse {
            logger("左滑引用列表触摸Hook失败: $methodName", it)
            false
        }
    }

    private fun bindMessageRoot(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.size < 2) return
        val holder = args[0] ?: return
        val position = args[1] as? Int ?: return
        val item = adapterItem(param.thisObject ?: return, position) ?: return
        val msg = resolveNativeMessage(item) ?: return
        val msgId = messageId(msg)
        if (msgId <= 0L) return
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (talker.isEmpty()) return
        val root = findRootView(holder) ?: return
        clearSwipeVisual(root)
        rootTargets[root] = QuoteTarget(talker, msgId, msg)
    }

    private fun handleTouch(
        view: View,
        event: MotionEvent,
        hit: QuoteHit?,
        stateMap: MutableMap<View, TouchState>,
        fromIntercept: Boolean
    ): Boolean {
        val state = stateMap.getOrPut(view) { TouchState() }
        if (event.actionMasked == MotionEvent.ACTION_MOVE &&
            state.lastEventTime == event.eventTime &&
            state.lastAction == event.actionMasked
        ) {
            return state.dragging
        }
        state.lastEventTime = event.eventTime
        state.lastAction = event.actionMasked
        // The intercept callback participates in gesture takeover, but the
        // touch callback owns the terminal event. Handling UP/CANCEL here
        // would clear the state before RecyclerView dispatches it to itself.
        if (fromIntercept &&
            (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) return state.dragging
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetSwipeVisual(state)
                state.downX = event.rawX
                state.downY = event.rawY
                state.hit = hit
                state.direction = SwipeDirection.NONE
                state.dragging = false
                state.armed = false
                state.hapticSent = false
                state.tracking = true
                state.triggered = false
                state.visualRow = null
                state.startTranslationX = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!state.tracking) return false
                if (state.triggered) return true
                val activeHit = state.hit ?: hit ?: return false
                val dx = event.rawX - state.downX
                val dy = event.rawY - state.downY
                val quoteEnabled = isQuoteEnabled()
                val repeatEnabled = isRepeatEnabled()
                if (!state.dragging && abs(dy) > dp(32f) && abs(dy) > abs(dx) * 1.2f) {
                    resetSwipeVisual(state)
                    state.tracking = false
                    return false
                }
                if (!state.dragging) {
                    val quoteHorizontalEnough = abs(dx) > dp(6f) && abs(dx) > abs(dy) * 1.15f
                    val repeatHorizontalEnough = abs(dx) > dp(18f) && abs(dx) > abs(dy) * 1.35f
                    val direction = when {
                        // rawX follows the physical screen direction: negative is left, positive is right.
                        quoteHorizontalEnough && dx < 0f && quoteEnabled -> SwipeDirection.LEFT_QUOTE
                        repeatHorizontalEnough && dx > 0f && repeatEnabled -> SwipeDirection.RIGHT_REPEAT
                        else -> SwipeDirection.NONE
                    }
                    if (direction == SwipeDirection.NONE) return false
                    state.direction = direction
                    state.dragging = true
                }
                val drag = when (state.direction) {
                    SwipeDirection.LEFT_QUOTE -> (-dx).coerceAtLeast(0f)
                    SwipeDirection.RIGHT_REPEAT -> dx.coerceAtLeast(0f)
                    SwipeDirection.NONE -> return false
                }.coerceAtMost(dp(150f))
                view.parent?.requestDisallowInterceptTouchEvent(true)
                state.armed = drag >= triggerDistance(state.direction)
                updateSwipeVisual(state, activeHit, drag)
                if (state.armed && !state.hapticSent) {
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    state.hapticSent = true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val activeHit = state.hit ?: hit
                val triggered = if (event.actionMasked == MotionEvent.ACTION_UP && state.armed && activeHit != null) {
                    when (state.direction) {
                        SwipeDirection.LEFT_QUOTE -> showNativeQuote(activeHit.row, activeHit.target)
                        SwipeDirection.RIGHT_REPEAT -> repeatNativeMessage(activeHit.target)
                        SwipeDirection.NONE -> false
                    }
                } else {
                    false
                }
                if (triggered) {
                    state.triggered = true
                }
                val consume = state.dragging || state.triggered
                resetSwipeVisual(state)
                state.tracking = false
                state.triggered = false
                state.hit = null
                state.direction = SwipeDirection.NONE
                state.dragging = false
                state.armed = false
                state.hapticSent = false
                view.parent?.requestDisallowInterceptTouchEvent(false)
                return consume
            }
        }
        return false
    }

    private fun updateSwipeVisual(state: TouchState, hit: QuoteHit, drag: Float) {
        val row = hit.row
        if (state.visualRow !== row) {
            resetSwipeVisual(state)
            state.visualRow = row
            state.startTranslationX = 0f
            clearSwipeVisual(row)
        }
        val maxOffset = dp(132f)
        val offset = drag.coerceAtMost(maxOffset)
        row.translationX = when (state.direction) {
            SwipeDirection.LEFT_QUOTE -> state.startTranslationX - offset
            SwipeDirection.RIGHT_REPEAT -> state.startTranslationX + offset
            SwipeDirection.NONE -> state.startTranslationX
        }
        row.alpha = 1f - 0.07f * (offset / maxOffset).coerceIn(0f, 1f)
    }

    private fun resetSwipeVisual(state: TouchState) {
        val row = state.visualRow ?: return
        row.animate().cancel()
        row.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(96L)
            .withEndAction {
                row.translationX = 0f
                row.alpha = 1f
            }
            .start()
        state.visualRow = null
        state.startTranslationX = 0f
    }

    private fun clearSwipeVisual(row: View?) {
        row ?: return
        row.animate().cancel()
        if (row.translationX != 0f) row.translationX = 0f
        if (row.alpha != 1f) row.alpha = 1f
    }

    private fun showNativeQuote(row: View, target: QuoteTarget): Boolean {
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (talker.isEmpty() || talker != target.talker) return false
        val footer = findChatFooterForQuote(row) ?: return false
        if (invokeQuoteInfoMethod(footer, target.nativeMessage)) {
            invokeQuoteIdMethod(footer, target.msgId)
            refreshQuoteUi(footer)
            refreshLegacyFooterComponent(footer, target.msgId)
            focusChatInput(footer)
            return true
        }
        if (invokeNewQuoteMethod(footer, talker, target.msgId, target.nativeMessage)) {
            refreshQuoteUi(footer)
            focusChatInput(footer)
            return true
        }
        if (invokeQuoteIdMethod(footer, target.msgId)) {
            refreshQuoteUi(footer)
            refreshLegacyFooterComponent(footer, target.msgId)
            focusChatInput(footer)
            return true
        }
        return false
    }

    private fun repeatNativeMessage(target: QuoteTarget): Boolean {
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (talker.isEmpty() || talker != target.talker) return false
        return runCatching {
            val selection = repeatSelectionForTarget(target) ?: return false
            val message = selection.message
            if (isUnsupportedRepeatMessage(message)) return false
            if (message.isQuote() && repeatQuoteMessage(talker, message)) return true
            if (message.isVoice() && repeatVoiceSilentlyAsync(
                    talker,
                    message,
                    target.nativeMessage,
                    selection.storedMessage,
                    selection.nativeMessage
                )
            ) return true
            val payload = buildRetransmitPayload(message, target.nativeMessage) ?: return false
            startQuickRetransmit(talker, payload)
        }.getOrElse {
            logger("复读消息处理失败", it)
            false
        }
    }

    private fun repeatSelectionForTarget(target: QuoteTarget): RepeatMessageSelection? {
        return runCatching {
            val storedMessage = runCatching { WeChatApis.messageStore()?.getMessageById(target.msgId) }.getOrNull()
            val nativeMessage = messageFromNative(target.nativeMessage, target.talker, target.msgId)
            val message = chooseRepeatMessage(storedMessage, nativeMessage) ?: return@runCatching null
            RepeatMessageSelection(message, storedMessage, nativeMessage)
        }.getOrNull()
    }

    private fun chooseRepeatMessage(storedMessage: WeChatMessage?, nativeMessage: WeChatMessage?): WeChatMessage? {
        val stored = storedMessage
        val native = nativeMessage
        if (native != null && WeChatRetransmitPayloadFactory.isRetransmittableAppMessage(native)) {
            if (stored == null ||
                !WeChatRetransmitPayloadFactory.isRetransmittableAppMessage(stored) ||
                stored.bodyContent().isBlank()
            ) {
                return native
            }
        }
        return stored ?: native
    }

    private fun isUnsupportedRepeatMessage(message: WeChatMessage): Boolean {
        return message.isRedPacket() || message.isTransfer()
    }

    private fun repeatQuoteMessage(talker: String, message: WeChatMessage): Boolean {
        if (talker.isBlank() || message.msgId <= 0L) return false
        val sender = WeChatApis.message()?.sender() ?: return false
        val title = message.getQuoteMsg()?.title?.takeIf { it.isNotBlank() }
            ?: WeChatMessage.xmlTag(message.bodyContent(), "title").takeIf { it.isNotBlank() }
            ?: message.bodyContent()
        return runCatching { sender.sendQuote(talker, message.msgId, title) }.getOrDefault(false)
    }

    private fun repeatVoiceSilentlyAsync(
        talker: String,
        message: WeChatMessage,
        nativeSource: Any?,
        vararg durationCandidates: WeChatMessage?
    ): Boolean {
        if (talker.isBlank()) return false
        val voices = WeChatApis.media()?.voices() ?: return false
        if (!voices.canSendSilently()) return false
        repeatVoiceExecutor.execute {
            runCatching {
                val candidates = voiceMessageCandidates(message, durationCandidates)
                val fileName = candidates.asSequence()
                    .map { voiceFileName(it) }
                    .firstOrNull { it.isNotBlank() }
                    ?: return@runCatching
                val path = voices.resolvePath(fileName).takeIf { it.isNotBlank() } ?: return@runCatching
                val duration = resolveVoiceDuration(candidates, nativeSource, fileName)
                mainHandler.post {
                    runCatching { voices.send(talker, path, duration) }
                        .onFailure { logger("复读语音发送失败", it) }
                }
            }.onFailure {
                logger("复读语音准备失败", it)
            }
        }
        return true
    }

    private fun startQuickRetransmit(talker: String, payload: WeChatRetransmitPayload): Boolean {
        val activity = WeChatApis.currentActivity()?.currentActivity()
        val launchContext = activity ?: context.hostContext()
        val intent = Intent()
        intent.setClassName(context.hostContext().packageName, MSG_RETRANSMIT_UI)
        if (activity == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("Retr_MsgQuickShare", true)
        intent.putExtra("Select_Conv_User", talker)
        intent.putExtra("custom_send_text", "")
        intent.putExtra("Retr_Msg_Type", payload.retrType)
        intent.putExtra("Retr_Msg_Id", payload.msgId)
        intent.putExtra("Retr_MsgTalker", payload.sourceTalker)
        intent.putExtra("Retr_Msg_content", payload.content)
        intent.putExtra("Retr_File_Name", payload.fileName)
        intent.putExtra("Edit_Mode_Sigle_Msg", true)
        intent.putExtra("Retr_MsgFromScene", payload.msgFromScene)
        intent.putExtra("Retr_show_success_tips", false)
        intent.putExtra("Retr_go_to_chattingUI", false)
        intent.putExtra("Retr_start_where_you_are", true)
        intent.putExtra(EXTRA_HCHAT_SILENT_REPEAT, true)
        intent.putExtra("scene_from", 17)
        if (payload.length > 0) {
            intent.putExtra("Retr_length", payload.length)
        }
        return runCatching {
            launchContext.startActivity(intent)
            true
        }.getOrElse {
            logger("复读启动微信转发失败", it)
            false
        }
    }

    private fun buildRetransmitPayload(message: WeChatMessage, nativeMessage: Any?): WeChatRetransmitPayload? {
        return WeChatRetransmitPayloadFactory.build(message, nativeMessage)
    }

    private fun voiceFileName(message: WeChatMessage): String {
        message.imagePath.takeIf { it.isNotBlank() }?.let { return it }
        val body = message.bodyContent()
        val colonParts = body.trimEnd('\n', '\r').split(':')
        if (colonParts.size >= 3 && body.indexOf('<') < 0) {
            return when {
                colonParts.size == 4 -> colonParts[1]
                else -> colonParts[0]
            }.trim()
        }
        return WeChatMessage.xmlAttr(body, "filename")
            .ifBlank { WeChatMessage.xmlAttr(body, "voiceurl") }
            .ifBlank { WeChatMessage.xmlTag(body, "filename") }
    }

    private fun resolveVoiceDuration(messages: List<WeChatMessage>, nativeSource: Any?, fileName: String): Int {
        val msgId = messages.firstOrNull { it.msgId > 0L }?.msgId ?: 0L
        val contents = messages.flatMap { message ->
            buildList {
                add(message.bodyContent())
                if (message.content != message.bodyContent()) add(message.content)
            }
        }
        return VoiceMessageDurationResolver.resolve(
            nativeSource,
            fileName,
            msgId,
            contents,
            DEFAULT_VOICE_DURATION_MS
        )
    }

    private fun voiceMessageCandidates(
        primary: WeChatMessage,
        others: Array<out WeChatMessage?>
    ): List<WeChatMessage> {
        val result = ArrayList<WeChatMessage>(others.size + 1)
        fun add(message: WeChatMessage?) {
            if (message == null || !message.isVoice()) return
            if (result.any { it === message }) return
            result += message
        }
        add(primary)
        others.forEach(::add)
        return result
    }

    private fun messageFromNative(nativeMessage: Any?, talker: String, msgId: Long): WeChatMessage? {
        val source = nativeMessage ?: return null
        val content = nativeMessageContent(source)
        val type = parseInt(readMessageValue(source, "getType", "field_type", "type"))
            ?.takeIf { it > 0 }
            ?: WeChatMessage.inferType(content)
        if (type <= 0) return null
        val imagePath = readMessageValue(source, "getImgPath", "field_imgPath", "imgPath") as? String ?: ""
        val sourceTalker = readMessageValue(source, "getTalker", "field_talker", "talker") as? String ?: talker
        val msgSource = readMessageValue(source, "getMsgSource", "field_msgSource", "msgSource") as? String ?: ""
        val isSend = parseInt(readMessageValue(source, "getIsSend", "field_isSend", "isSend")) ?: 0
        return WeChatMessage(
            msgId,
            0L,
            type,
            0,
            isSend,
            0L,
            sourceTalker,
            content,
            imagePath,
            "",
            "",
            0,
            msgSource,
            ""
        )
    }

    private fun nativeMessageContent(source: Any): String {
        (KavaReflector.readField(source, "field_content") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        (KavaReflector.readField(source, "content") as? String)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        voiceContentGetter(source)?.let { method ->
            (KavaReflector.invoke(method, source) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return (KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, "getContent"), source) as? String).orEmpty()
    }

    private fun voiceContentGetter(source: Any): Method? {
        val clazz = source.javaClass
        return voiceContentGetterCache.getOrPut(clazz) {
            KavaReflector.declaredMethods(clazz)
                .filter { method ->
                    method.parameterTypes.isEmpty() &&
                        method.returnType == String::class.java &&
                        method.name in arrayOf("getContent", "j", "A1", "U1", "W0")
                }
                .mapNotNull { method ->
                    val value = KavaReflector.invoke(method, source) as? String ?: return@mapNotNull null
                    val score = voiceContentScore(value)
                    if (score <= 0) null else method to score
                }
                .maxByOrNull { it.second }
                ?.first
                ?: arrayOf("getContent", "j", "A1", "U1", "W0").firstNotNullOfOrNull { name ->
                    KavaReflector.findMethod(clazz, name)
                        ?.takeIf { it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                }
        }
    }

    private fun voiceContentScore(value: String): Int {
        if (value.isBlank()) return 0
        var score = 1
        if (value.contains("voicelength", ignoreCase = true) || value.contains("length=", ignoreCase = true)) score += 12
        if (value.contains(':')) score += 4
        if (value.contains('<')) score += 2
        return score
    }

    private fun readMessageValue(source: Any, getter: String, fieldName: String, fallbackField: String): Any? {
        KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, getter), source)?.let { return it }
        KavaReflector.readField(source, fieldName)?.let { return it }
        return KavaReflector.readField(source, fallbackField)
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun findChatFooterForQuote(row: View): Any? {
        val cached = lastFooterRef.get()
        if (cached is View && cached.isAttachedToWindow) return cached
        row.rootView?.let { root ->
            findChatFooterInView(root)?.let { return it }
        }
        val activity = WeChatApis.currentActivity()?.currentActivity()
        val decor = activity?.window?.decorView
        if (decor != null) {
            findChatFooterInView(decor)?.let { return it }
        }
        return null
    }

    private fun locateAdapterBindMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodCachePrefs, methodCacheKey, context.hostClassLoader(), "adapter_bind")
            ?.takeIf { isAdapterBindCandidate(it) }
            ?.let { return it }
        val matches = findMethodsByStrings("MicroMsg.ChattingDataAdapterV3", "_onBindViewHolder[", "msgInfo")
            .ifEmpty { findMethodsByStrings("MicroMsg.ChattingDataAdapterV3", "holder", "itemView") }
        val method = matches.firstOrNull { isAdapterBindCandidate(it) }
        if (method != null) {
            DexMethodCache.save(methodCachePrefs, methodCacheKey, "adapter_bind", method)
        } else {
            DexMethodCache.clear(methodCachePrefs, methodCacheKey, "adapter_bind")
        }
        return method
    }

    private fun isAdapterBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size == 2 && types[1] == Integer.TYPE && isLikelyViewHolderClass(types[0])
    }

    private fun findMethodsByStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        usingStrings(strings.toList())
                    })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.getOrElse {
            logger("左滑引用定位方法失败", it)
            emptyList()
        }
    }

    private fun isLikelyViewHolderClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        if (isRecyclerViewHolder(clazz)) return true
        if (findRootField(clazz) != null) return true
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (KavaReflector.declaredFields(current).any { it.type == View::class.java }) return true
            current = current.superclass
        }
        return false
    }

    private fun isRecyclerViewHolder(clazz: Class<*>): Boolean {
        return runCatching {
            val rvHolder = context.hostClassLoader().loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            rvHolder.isAssignableFrom(clazz)
        }.getOrDefault(false)
    }

    private fun findRootView(holder: Any): View? {
        (KavaReflector.readField(holder, "itemView") as? View)?.let { return it }
        return KavaReflector.readField(findRootField(holder.javaClass), holder) as? View
    }

    private fun findRootField(clazz: Class<*>): Field? {
        holderRootFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "itemView" || it.type == View::class.java
            }
            if (field != null) {
                holderRootFieldCache[clazz] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun adapterItem(adapter: Any, position: Int): Any? {
        if (position < 0) return null
        itemMethodCache[adapter.javaClass]?.let { return KavaReflector.invoke(it, adapter, position) }
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            val method = KavaReflector.declaredMethods(current).firstOrNull {
                it.parameterTypes.size == 1
                    && (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == Int::class.java)
                    && (it.name == "J0" || it.name == "getItem" || it.name == "get")
            }
            if (method != null) {
                itemMethodCache[adapter.javaClass] = method
                KavaReflector.invoke(method, adapter, position)?.let { return it }
            }
            current = current.superclass
        }
        return adapterListItem(adapter, position)
    }

    private fun adapterListItem(adapter: Any, position: Int): Any? {
        itemListFieldCache[adapter.javaClass]?.let { field ->
            listItem(KavaReflector.readField(field, adapter), position)?.let { return it }
        }
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "K" || it.name == "items" || it.name == "data" || it.name == "list"
            }
            if (field != null) {
                itemListFieldCache[adapter.javaClass] = field
                return listItem(KavaReflector.readField(field, adapter), position)
            }
            current = current.superclass
        }
        return findNestedListItem(adapter, position, Collections.newSetFromMap(WeakHashMap<Any, Boolean>()), 0)
    }

    private fun listItem(list: Any?, position: Int): Any? {
        if (list == null || position < 0) return null
        if (list is List<*> && position < list.size) return list[position]
        return KavaReflector.invoke(KavaReflector.findMethod(list.javaClass, "get", Integer.TYPE), list, position)
            ?: KavaReflector.invoke(KavaReflector.findMethod(list.javaClass, "get", Int::class.java), list, position)
    }

    private fun findNestedListItem(source: Any?, position: Int, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || position < 0 || depth > 3 || !visited.add(source)) return null
        listItem(source, position)?.takeIf { resolveNativeMessage(it) != null }?.let { return it }
        val className = source.javaClass.name
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View || source is ViewGroup) return null
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val type = field.type
                if (type.isPrimitive || type.isArray) continue
                if (type == String::class.java || Number::class.java.isAssignableFrom(type)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                findNestedListItem(value, position, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun messageId(msg: Any): Long {
        for (name in arrayOf("getMsgId", "getMsgID", "getId")) {
            val value = KavaReflector.invoke(KavaReflector.findMethod(msg.javaClass, name), msg)
            parseLong(value)?.let { if (it > 0L) return it }
        }
        for (name in arrayOf("field_msgId", "msgId", "msgID", "id")) {
            val value = KavaReflector.readField(msg, name)
            parseLong(value)?.let { if (it > 0L) return it }
        }
        return 0L
    }

    private fun resolveNativeMessage(source: Any): Any? {
        return resolveNativeMessage(source, Collections.newSetFromMap(WeakHashMap<Any, Boolean>()), 0)
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 4 || !visited.add(source)) return null
        val className = source.javaClass.name
        if (isLikelyNativeMessage(source) && messageId(source) > 0L) return source
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View || source is ViewGroup) return null
        if (source is Collection<*>) {
            for (item in source) {
                resolveNativeMessage(item, visited, depth + 1)?.let { return it }
            }
            return null
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val type = field.type
                if (type.isPrimitive || type.isArray) continue
                if (type == String::class.java || Number::class.java.isAssignableFrom(type)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                resolveNativeMessage(value, visited, depth + 1)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    private fun isLikelyNativeMessage(value: Any): Boolean {
        val className = value.javaClass.name
        return className.startsWith("com.tencent.mm.storage.") ||
            KavaReflector.declaredMethods(value.javaClass).any { method ->
                method.parameterTypes.isEmpty() &&
                    (method.name == "getMsgId" || method.name == "getMsgID") &&
                    (method.returnType == java.lang.Long.TYPE || method.returnType == java.lang.Long::class.java)
            }
    }

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun findChatFooterInView(view: View): Any? {
        if (isChatFooter(view)) {
            lastFooterRef = WeakReference(view)
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findChatFooterInView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isChatFooter(value: Any): Boolean {
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java) {
            if (current.name == CHAT_FOOTER) return true
            current = current.superclass
        }
        return false
    }

    private fun findRecyclerTarget(recyclerView: View, x: Float, y: Float): QuoteHit? {
        val group = recyclerView as? ViewGroup ?: return null
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i) ?: continue
            if (x < child.left || x > child.right || y < child.top || y > child.bottom) continue
            findTargetFromViewTree(child)?.let { target ->
                return QuoteHit(child, target)
            }
        }
        return null
    }

    private fun findTargetFromViewTree(view: View): QuoteTarget? {
        rootTargets[view]?.let { return it }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTargetFromViewTree(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun refreshQuoteUi(footer: Any) {
        val method = findQuoteVisibilityMethod(footer.javaClass)
        if (method != null && KavaReflector.invokeSuccessfully(method, footer, View.VISIBLE)) {
            refreshViewTree(footer as? View)
            return
        }
        findTaggedQuoteView(footer)?.let { view ->
            view.visibility = View.VISIBLE
            refreshViewTree(view)
        }
    }

    private fun refreshLegacyFooterComponent(footer: Any, msgId: Long) {
        val component = findLegacyQuoteComponent(footer) ?: return
        writeLegacyQuoteState(component, footer, msgId)
        for (method in findFooterRefreshMethods(component.javaClass)) {
            KavaReflector.invokeSuccessfully(method, component)
        }
        refreshViewTree(footer as? View)
    }

    private fun findLegacyQuoteComponent(footer: Any): Any? {
        val chattingContext = KavaReflector.invokeMethod(footer, "getChattingContext") ?: return null
        val chattingContextHost = KavaReflector.invokeMethod(chattingContext, "a") ?: return null
        val manager = KavaReflector.readField(chattingContextHost, "c") ?: return null
        val interfaces = KavaReflector.loadClass("rb4.i1", context.hostClassLoader())?.let { listOf(it) }
            ?: emptyList()
        for (interfaceClass in interfaces) {
            val method = KavaReflector.findCompatibleMethod(manager.javaClass, "a", interfaceClass) ?: continue
            val component = KavaReflector.invoke(method, manager, interfaceClass)
            if (component != null && findFooterRefreshMethods(component.javaClass).isNotEmpty()) return component
        }
        return null
    }

    private fun writeLegacyQuoteState(component: Any, footer: Any, msgId: Long) {
        val quoteField = KavaReflector.findFieldRecursive(component.javaClass, "i")
        if (quoteField != null &&
            (quoteField.type == java.lang.Long.TYPE || quoteField.type == java.lang.Long::class.java)
        ) {
            KavaReflector.writeField(quoteField, component, msgId)
        }
        val textField = KavaReflector.findFieldRecursive(component.javaClass, "g")
        if (textField != null && textField.type == String::class.java) {
            val text = KavaReflector.invokeMethod(footer, "getLastText") as? String ?: ""
            KavaReflector.writeField(textField, component, text)
        }
    }

    private fun findFooterRefreshMethods(clazz: Class<*>): List<Method> {
        footerRefreshMethodCache[clazz]?.let { return it }
        val result = ArrayList<Method>()
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                if (method.returnType != Void.TYPE || Modifier.isStatic(method.modifiers)) continue
                if (method.parameterTypes.isNotEmpty()) continue
                if (method.name == "M0") {
                    KavaReflector.accessible(method)?.let { result.add(it) }
                }
            }
            current = current.superclass
        }
        footerRefreshMethodCache[clazz] = result
        return result
    }

    private fun refreshViewTree(view: View?) {
        var current = view
        repeat(4) {
            val target = current ?: return
            target.requestLayout()
            target.invalidate()
            current = target.parent as? View
        }
    }

    private fun focusChatInput(footer: Any) {
        switchTextInputMode(footer)
        val footerView = footer as? View ?: return
        requestKeyboardLater(footerView, 80L, 0)
    }

    private fun requestKeyboardLater(footer: View, delay: Long, retry: Int) {
        footer.postDelayed({
            val input = findNativeQuoteInput(footer) ?: return@postDelayed
            input.isFocusable = true
            input.isFocusableInTouchMode = true
            input.requestFocusFromTouch()
            input.requestFocus()
            if (input is EditText) {
                input.setSelection(input.text?.length ?: 0)
            }
            input.performClick()
            val imm = input.context.getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            if (retry < 2 && !input.hasFocus()) requestKeyboardLater(footer, 120L, retry + 1)
        }, delay)
    }

    private fun switchTextInputMode(footer: Any) {
        KavaReflector.invokeMethod(footer, "U0", true)
        KavaReflector.invokeMethod(footer, "setToSendTextColor", true)
        val method = KavaReflector.findMethod(footer.javaClass, "setMode", Integer.TYPE)
            ?: KavaReflector.findMethod(footer.javaClass, "setMode", Int::class.java)
            ?: return
        if (KavaReflector.invokeSuccessfully(method, footer, 1)) return
        KavaReflector.invokeSuccessfully(method, footer, 0)
    }

    private fun findNativeQuoteInput(footer: Any): View? {
        KavaReflector.readField(footer, "m")?.let { holder ->
            KavaReflector.invokeMethod(holder, "j")?.let { view ->
                (view as? View)?.let { return it }
            }
        }
        return null
    }

    private fun findQuoteVisibilityMethod(clazz: Class<*>): Method? {
        quoteVisibilityMethodCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                val types = method.parameterTypes
                if (method.returnType == Void.TYPE &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.name == "setMsgQuoteRlVisibility" &&
                    types.size == 1 &&
                    (types[0] == Integer.TYPE || types[0] == Int::class.java)
                ) {
                    val accessible = KavaReflector.accessible(method)
                    if (accessible != null) quoteVisibilityMethodCache[clazz] = accessible
                    return accessible
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun findTaggedQuoteView(footer: Any): View? {
        var current: Class<*>? = footer.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (!View::class.java.isAssignableFrom(field.type)) continue
                val view = KavaReflector.readField(field, footer) as? View ?: continue
                if (view.tag != null) return view
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeNewQuoteMethod(footer: Any, talker: String, msgId: Long, nativeMessage: Any?): Boolean {
        val method = findNewQuoteMethod(footer.javaClass) ?: return false
        if (KavaReflector.invokeSuccessfully(method, footer, talker, msgId, null)) return true
        if (nativeMessage != null && isAssignable(method.parameterTypes.getOrNull(2), nativeMessage) &&
            KavaReflector.invokeSuccessfully(method, footer, talker, msgId, nativeMessage)
        ) {
            return true
        }
        newQuoteMethodCache.remove(footer.javaClass)
        if (method == dexQuoteMethod) dexQuoteMethod = null
        return false
    }

    private fun findNewQuoteMethod(clazz: Class<*>): Method? {
        dexLocatedQuoteMethod()?.takeIf { clazz.isAssignableFrom(it.declaringClass) || it.declaringClass.isAssignableFrom(clazz) }?.let {
            return it
        }
        newQuoteMethodCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                val types = method.parameterTypes
                if (method.returnType == Void.TYPE &&
                    !Modifier.isStatic(method.modifiers) &&
                    types.size == 3 &&
                    types[0] == String::class.java &&
                    (types[1] == java.lang.Long.TYPE || types[1] == java.lang.Long::class.java) &&
                    !types[2].isPrimitive &&
                    !types[2].name.startsWith("java.") &&
                    !types[2].name.startsWith("android.")
                ) {
                    val accessible = KavaReflector.accessible(method)
                    if (accessible != null) newQuoteMethodCache[clazz] = accessible
                    return accessible
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun dexLocatedQuoteMethod(): Method? {
        dexQuoteMethod?.let { return it }
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodCachePrefs, methodCacheKey, context.hostClassLoader(), "quote_method")
            ?.takeIf { isNewQuoteCandidate(it) }
            ?.let {
                dexQuoteMethod = KavaReflector.accessible(it)
                return dexQuoteMethod
            }
        val located = runCatching {
            context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        usingStrings(listOf("invalid quote msg id"))
                    })
                }
            ).firstNotNullOfOrNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    ?.takeIf { method -> isNewQuoteCandidate(method) }
            }
        }.getOrNull()
        if (located != null) {
            dexQuoteMethod = KavaReflector.accessible(located)
            DexMethodCache.save(methodCachePrefs, methodCacheKey, "quote_method", located)
        } else {
            DexMethodCache.clear(methodCachePrefs, methodCacheKey, "quote_method")
        }
        return dexQuoteMethod
    }

    private fun isNewQuoteCandidate(method: Method): Boolean {
        return method.declaringClass.name == CHAT_FOOTER &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.size == 3 &&
            method.parameterTypes[0] == String::class.java &&
            (method.parameterTypes[1] == java.lang.Long.TYPE || method.parameterTypes[1] == java.lang.Long::class.java)
    }

    private fun invokeQuoteInfoMethod(footer: Any, nativeMessage: Any?): Boolean {
        if (nativeMessage == null) return false
        for (message in nativeMessageCandidates(nativeMessage)) {
            val method = findQuoteInfoMethod(footer.javaClass, message) ?: continue
            if (KavaReflector.invokeSuccessfully(method, footer, message)) return true
            quoteInfoMethodCache.remove(footer.javaClass)
        }
        return false
    }

    private fun nativeMessageCandidates(source: Any): List<Any> {
        val result = ArrayList<Any>()
        result.add(source)
        val sourceMsgId = messageId(source)
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, source) ?: continue
                if (value === source || result.any { it === value }) continue
                val className = value.javaClass.name
                if (!className.startsWith("com.tencent.mm.storage.") && sourceMsgId <= 0L) continue
                if (sourceMsgId > 0L && messageId(value) != sourceMsgId) continue
                result.add(value)
            }
            current = current.superclass
        }
        return result
    }

    private fun findQuoteInfoMethod(clazz: Class<*>, nativeMessage: Any): Method? {
        quoteInfoMethodCache[clazz]?.takeIf { isAssignable(it.parameterTypes.firstOrNull(), nativeMessage) }?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                val types = method.parameterTypes
                if (method.returnType == Void.TYPE &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.name == "setLastQuoteMsgInfo" &&
                    types.size == 1 &&
                    isAssignable(types[0], nativeMessage)
                ) {
                    val accessible = KavaReflector.accessible(method)
                    if (accessible != null) quoteInfoMethodCache[clazz] = accessible
                    return accessible
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeQuoteIdMethod(footer: Any, msgId: Long): Boolean {
        val method = findQuoteIdMethod(footer.javaClass) ?: return false
        if (KavaReflector.invokeSuccessfully(method, footer, msgId)) return true
        quoteIdMethodCache.remove(footer.javaClass)
        return false
    }

    private fun findQuoteIdMethod(clazz: Class<*>): Method? {
        quoteIdMethodCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            for (method in KavaReflector.declaredMethods(current)) {
                val types = method.parameterTypes
                if (method.returnType == Void.TYPE &&
                    !Modifier.isStatic(method.modifiers) &&
                    method.name == "setLastQuoteMsgId" &&
                    types.size == 1 &&
                    (types[0] == java.lang.Long.TYPE || types[0] == java.lang.Long::class.java)
                ) {
                    val accessible = KavaReflector.accessible(method)
                    if (accessible != null) quoteIdMethodCache[clazz] = accessible
                    return accessible
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun isAssignable(parameterType: Class<*>?, value: Any?): Boolean {
        if (parameterType == null || value == null) return false
        return parameterType.isAssignableFrom(value.javaClass)
    }

    private fun isEnabled(): Boolean {
        return isQuoteEnabled() || isRepeatEnabled()
    }

    private fun isQuoteEnabled(): Boolean {
        val sp = HchatStorage.preferences(context.hostContext(), SwipeQuoteSettings.PREFS_NAME)
        return sp.getBoolean(SwipeQuoteSettings.KEY_ENABLE, SwipeQuoteSettings.DEFAULT_ENABLE)
    }

    private fun isRepeatEnabled(): Boolean {
        val sp = HchatStorage.preferences(context.hostContext(), SwipeQuoteSettings.PREFS_NAME)
        return sp.getBoolean(SwipeQuoteSettings.KEY_REPEAT_ENABLE, SwipeQuoteSettings.DEFAULT_REPEAT_ENABLE)
    }

    private fun isRepeatMenuEnabled(): Boolean {
        val sp = HchatStorage.preferences(context.hostContext(), SwipeQuoteSettings.PREFS_NAME)
        return sp.getBoolean(
            SwipeQuoteSettings.KEY_REPEAT_MENU_ENABLE,
            SwipeQuoteSettings.DEFAULT_REPEAT_MENU_ENABLE
        )
    }

    private fun dp(value: Float): Float {
        return value * context.hostContext().resources.displayMetrics.density
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun triggerDistance(direction: SwipeDirection): Float {
        return when (direction) {
            SwipeDirection.RIGHT_REPEAT -> dp(92f)
            SwipeDirection.LEFT_QUOTE -> dp(52f)
            SwipeDirection.NONE -> Float.MAX_VALUE
        }
    }

    private data class QuoteHit(val row: View, val target: QuoteTarget)

    private data class QuoteTarget(val talker: String, val msgId: Long, val nativeMessage: Any?)

    private data class RepeatMessageSelection(
        val message: WeChatMessage,
        val storedMessage: WeChatMessage?,
        val nativeMessage: WeChatMessage?
    )

    private enum class SwipeDirection {
        NONE,
        LEFT_QUOTE,
        RIGHT_REPEAT
    }

    private class TouchState {
        var downX = 0f
        var downY = 0f
        var hit: QuoteHit? = null
        var visualRow: View? = null
        var startTranslationX = 0f
        var direction = SwipeDirection.NONE
        var tracking = false
        var dragging = false
        var armed = false
        var hapticSent = false
        var triggered = false
        var lastEventTime = 0L
        var lastAction = -1
    }

    private companion object {
        const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
        const val MSG_RETRANSMIT_UI = "com.tencent.mm.ui.transmit.MsgRetransmitUI"
        const val EXTRA_HCHAT_SILENT_REPEAT = "hchat_silent_repeat"
        const val MENU_REPEAT_ID = SingleMessageMenuLocator.HCHAT_REPEAT_MENU_ITEM_ID
        const val MENU_REPEAT_TITLE = "复读[H]"
        const val DEFAULT_VOICE_DURATION_MS = 1000
        val RECYCLER_VIEW_CLASSES = arrayOf(
            "androidx.recyclerview.widget.RecyclerView",
            "android.support.v7.widget.RecyclerView"
        )
    }

    private class RepeatMenuIconDrawable(context: android.content.Context) : Drawable() {
        private val density = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if ((context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            ) {
                Color.WHITE
            } else {
                Color.rgb(51, 51, 51)
            }
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paint.color
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        override fun draw(canvas: Canvas) {
            val bounds = bounds
            val size = minOf(bounds.width(), bounds.height()).toFloat()
            if (size <= 0f) return
            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()
            paint.strokeWidth = maxOf(1.6f * density, size * 0.065f)
            canvas.drawCircle(centerX, centerY, size * 0.38f, paint)
            textPaint.textSize = size * 0.34f
            val metrics = textPaint.fontMetrics
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText("+1", centerX, baseline, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            textPaint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = (32f * density).toInt()

        override fun getIntrinsicHeight(): Int = (32f * density).toInt()
    }
}
