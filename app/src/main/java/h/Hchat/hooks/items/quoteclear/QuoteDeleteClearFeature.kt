package h.Hchat.hooks.items.quoteclear

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class QuoteDeleteClearFeature : BaseFeature() {
    private var runtime: QuoteDeleteClearRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "删除键清引用"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(QuoteDeleteClearSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = QuoteDeleteClearRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
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
        const val ID = "quote_delete_clear"
    }
}

private class QuoteDeleteClearRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val methodCachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_quote_delete_clear_method_cache")
    private val footerFieldCache = ConcurrentHashMap<Class<*>, Field>()
    @Volatile private var currentFooterRef = WeakReference<Any>(null)
    @Volatile private var installed = false
    @Volatile private var footerLifecycleHookInstalled = false
    @Volatile private var inputConnectionHookInstalled = false
    @Volatile private var onKeyHookInstalled = false
    @Volatile private var supportOnKeyMethod: Method? = null
    @Volatile private var quoteClearMethod: Method? = null
    @Volatile private var quoteClearMethodResolved = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val footerOk = installFooterLifecycleHook()
        val inputOk = installInputConnectionHook()
        val onKeyOk = installOnKeyHook()
        warmQuoteClearMethod()
        installed = footerOk && (inputOk || onKeyOk)
        return installed
    }

    private fun installFooterLifecycleHook(): Boolean {
        if (footerLifecycleHookInstalled) return true
        val footerClass = KavaReflector.loadClass(CHAT_FOOTER, context.hostClassLoader()) ?: return false
        val onAttached = KavaReflector.findMethodRecursive(footerClass, "onAttachedToWindow") ?: return false
        val onDetached = KavaReflector.findMethodRecursive(footerClass, "onDetachedFromWindow")
        return runCatching {
            HookRegistry.get().hook(onAttached, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val footer = param.thisObject
                    if (footer is View) {
                        currentFooterRef = WeakReference(footer)
                    }
                }
            })
            if (onDetached != null) {
                HookRegistry.get().hook(onDetached, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (currentFooterRef.get() === param.thisObject) {
                            currentFooterRef = WeakReference(null)
                        }
                    }
                })
            }
            footerLifecycleHookInstalled = true
            true
        }.getOrElse {
            logger("删除键清引用输入栏生命周期Hook失败", it)
            false
        }
    }

    private fun installInputConnectionHook(): Boolean {
        if (inputConnectionHookInstalled) return true
        val method = KavaReflector.findMethodRecursive(
            TextView::class.java,
            "onCreateInputConnection",
            EditorInfo::class.java
        ) ?: return false
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val input = param.thisObject as? TextView ?: return
                    val footer = footerForInput(input) ?: return
                    val target = param.result as? InputConnection ?: return
                    val footerRef = WeakReference(footer)
                    param.result = QuoteDeleteInputConnection(target) {
                        clearQuoteByInputDelete(input, footerRef)
                    }
                }
            })
            inputConnectionHookInstalled = true
            true
        }.getOrElse {
            logger("删除键清引用输入法删除Hook失败", it)
            false
        }
    }

    private fun installOnKeyHook(): Boolean {
        if (onKeyHookInstalled) return true
        val method = locateSupportAutoCompleteOnKeyMethod() ?: run {
            logger("删除键清引用定位按键入口失败", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (handleOnKeyDelete(param)) {
                        param.setResult(true)
                    }
                }
            })
            onKeyHookInstalled = true
            true
        }.getOrElse {
            logger("删除键清引用按键Hook失败", it)
            false
        }
    }

    private fun handleOnKeyDelete(param: XC_MethodHook.MethodHookParam): Boolean {
        if (!isEnabled()) return false
        val event = param.args?.getOrNull(2) as? KeyEvent ?: return false
        if (event.action != KeyEvent.ACTION_DOWN || event.keyCode != KeyEvent.KEYCODE_DEL) return false
        val footer = findChatFooter(param) ?: currentFooter() ?: return false
        if (inputText(param, footer).isNotEmpty()) return false
        return clearQuoteAndRefresh(footer)
    }

    private fun clearQuoteByInputDelete(input: TextView, footerRef: WeakReference<Any>): Boolean {
        if (!isEnabled()) return false
        if (!input.text.isNullOrEmpty()) return false
        val footer = footerRef.get()?.takeIf { isAttachedFooter(it) }
            ?: footerForInput(input)
            ?: return false
        return clearQuoteAndRefresh(footer)
    }

    private fun clearQuoteAndRefresh(footer: Any): Boolean {
        if (!invokeQuoteClearMethod(footer)) return false
        refreshViewTree(footer as? View)
        return true
    }

    private fun findChatFooter(param: XC_MethodHook.MethodHookParam): Any? {
        (param.args?.getOrNull(0) as? View)?.let { view ->
            findChatFooterInParents(view)?.let { return it }
        }
        return findChatFooterFromObject(param.thisObject, 0)
    }

    private fun footerForInput(input: View): Any? {
        findChatFooterInParents(input)?.let { return it }
        val footer = currentFooter() ?: return null
        if (footer is View && footer.rootView === input.rootView) return footer
        return null
    }

    private fun currentFooter(): Any? {
        val footer = currentFooterRef.get() ?: return null
        return footer.takeIf { isAttachedFooter(it) }
    }

    private fun isAttachedFooter(footer: Any): Boolean {
        return footer is View && footer.isAttachedToWindow && isChatFooter(footer)
    }

    private fun findChatFooterInParents(view: View): Any? {
        var current: View? = view
        repeat(12) {
            val target = current ?: return@repeat
            if (isChatFooter(target)) return target
            current = target.parent as? View
        }
        return null
    }

    private fun findChatFooterFromObject(source: Any?, depth: Int): Any? {
        if (source == null || depth > 2) return null
        if (isChatFooter(source)) return source
        footerFieldCache[source.javaClass]?.let { field ->
            val cached = KavaReflector.readField(field, source)
            if (cached != null && isChatFooter(cached)) return cached
            footerFieldCache.remove(source.javaClass, field)
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (Modifier.isStatic(field.modifiers)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                if (isChatFooter(value)) {
                    footerFieldCache[source.javaClass] = field
                    return value
                }
                if (value.javaClass.name.startsWith("com.tencent.mm.pluginsdk.ui.chat.")) {
                    findChatFooterFromObject(value, depth + 1)?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun invokeQuoteClearMethod(footer: Any): Boolean {
        val method = findQuoteClearMethod(footer.javaClass) ?: return false
        return KavaReflector.invokeSuccessfully(method, footer, false, true)
    }

    private fun findQuoteClearMethod(clazz: Class<*>): Method? {
        return locateQuoteClearMethod()?.takeIf {
            clazz.isAssignableFrom(it.declaringClass) || it.declaringClass.isAssignableFrom(clazz)
        }
    }

    private fun warmQuoteClearMethod() {
        runCatching { locateQuoteClearMethod() }
    }

    private fun locateQuoteClearMethod(): Method? {
        quoteClearMethod?.let { return it }
        if (quoteClearMethodResolved) return null
        val key = methodCacheKey()
        DexMethodCache.load(methodCachePrefs, key, context.hostClassLoader(), "quote_clear_method")
            ?.takeIf { isQuoteClearCandidate(it) }
            ?.let {
                quoteClearMethod = KavaReflector.accessible(it)
                quoteClearMethodResolved = true
                return quoteClearMethod
            }
        val located = try {
            locateQuoteClearMethodByStrings("handleQuoteMsgFillingFrom")
                ?: locateQuoteClearMethodByStrings("openim_card_type_name", "err_not_started")
        } catch (e: Throwable) {
            logger("删除键清引用定位原生清理方法失败", e)
            return null
        }
        if (located != null) {
            quoteClearMethod = KavaReflector.accessible(located)
            quoteClearMethodResolved = true
            DexMethodCache.save(methodCachePrefs, key, "quote_clear_method", located)
        } else {
            quoteClearMethodResolved = true
            DexMethodCache.clear(methodCachePrefs, key, "quote_clear_method")
        }
        return quoteClearMethod
    }

    private fun lastText(footer: Any): String {
        return (KavaReflector.invokeMethod(footer, "getLastText") as? CharSequence)?.toString().orEmpty()
    }

    private fun inputText(param: XC_MethodHook.MethodHookParam, footer: Any): String {
        val input = param.args?.getOrNull(0) as? TextView
        return input?.text?.toString() ?: lastText(footer)
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

    private fun locateSupportAutoCompleteOnKeyMethod(): Method? {
        supportOnKeyMethod?.let { return it }
        val key = methodCacheKey()
        DexMethodCache.load(methodCachePrefs, key, context.hostClassLoader(), "support_auto_complete_on_key")
            ?.takeIf { isSupportOnKeyCandidate(it) }
            ?.let {
                supportOnKeyMethod = KavaReflector.accessible(it)
                return supportOnKeyMethod
            }
        val located = try {
            context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        name("onKey")
                        usingEqStrings("ChatFooterKtHelper", "supportAutoComplete err")
                    })
                }
            ).firstNotNullOfOrNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    ?.takeIf { method -> isSupportOnKeyCandidate(method) }
            }
        } catch (e: Throwable) {
            logger("删除键清引用定位按键入口异常", e)
            return null
        }
        if (located != null) {
            supportOnKeyMethod = KavaReflector.accessible(located)
            DexMethodCache.save(methodCachePrefs, key, "support_auto_complete_on_key", located)
        } else {
            DexMethodCache.clear(methodCachePrefs, key, "support_auto_complete_on_key")
        }
        return supportOnKeyMethod
    }

    private fun locateQuoteClearMethodByStrings(vararg strings: String): Method? {
        return context.dexKitBridge().findMethod(
            org.luckypray.dexkit.query.FindMethod().apply {
                matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                    declaredClass(CHAT_FOOTER)
                    returnType("void")
                    paramTypes("boolean", "boolean")
                    usingEqStrings(*strings)
                })
            }
        ).firstNotNullOfOrNull { data ->
            runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                ?.takeIf { method -> isQuoteClearCandidate(method) }
        }
    }

    private fun isSupportOnKeyCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "onKey" &&
            method.returnType == java.lang.Boolean.TYPE &&
            types.size == 3 &&
            View::class.java.isAssignableFrom(types[0]) &&
            (types[1] == Integer.TYPE || types[1] == Int::class.java) &&
            types[2] == KeyEvent::class.java
    }

    private fun isQuoteClearCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return method.declaringClass.name == CHAT_FOOTER &&
            method.returnType == Void.TYPE &&
            types.size == 2 &&
            isBooleanType(types[0]) &&
            isBooleanType(types[1])
    }

    private fun isBooleanType(type: Class<*>?): Boolean {
        return type == java.lang.Boolean.TYPE || type == Boolean::class.java
    }

    private fun isChatFooter(value: Any): Boolean {
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java) {
            if (current.name == CHAT_FOOTER) return true
            current = current.superclass
        }
        return false
    }

    private fun isEnabled(): Boolean {
        val sp = HchatStorage.preferences(context.hostContext(), QuoteDeleteClearSettings.PREFS_NAME)
        return sp.getBoolean(QuoteDeleteClearSettings.KEY_ENABLE, QuoteDeleteClearSettings.DEFAULT_ENABLE)
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private companion object {
        const val CHAT_FOOTER = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
    }
}

private class QuoteDeleteInputConnection(
    target: InputConnection,
    private val clearQuote: () -> Boolean
) : InputConnectionWrapper(target, true) {
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0 && clearQuote()) return true
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        if (beforeLength > 0 && afterLength == 0 && clearQuote()) return true
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && clearQuote()) {
            return true
        }
        return super.sendKeyEvent(event)
    }
}
