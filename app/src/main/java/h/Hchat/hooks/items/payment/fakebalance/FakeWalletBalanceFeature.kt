package h.Hchat.hooks.items.payment.fakebalance

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.math.RoundingMode
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Locale
import java.util.WeakHashMap

class FakeWalletBalanceFeature : BaseFeature() {
    private var hooker: FakeWalletBalanceHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "伪造零钱"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(FakeWalletBalanceSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = FakeWalletBalanceHooker(context)
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
    }

    companion object {
        const val ID = "fake_wallet_balance"
    }
}

private class FakeWalletBalanceHooker(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), FakeWalletBalanceSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    private val tickerTextSizeReady = WeakHashMap<View, Boolean>()
    private val amountTextStates = WeakHashMap<View, AmountTextState>()
    // 微信金额控件会嵌套调用多个 setter，同一调用链只能应用一次动态增减。
    private val amountHookFrames = ThreadLocal<ArrayDeque<Boolean>>()
    private val activeAmountOverride = ThreadLocal<AmountOverride>()

    @Volatile
    private var moneyLoadingInstalled = false

    @Volatile
    private var tickerInstalled = false

    @Volatile
    private var mallWalletCellInstalled = false

    @Volatile
    private var lqtDetailEntryInstalled = false

    @Synchronized
    fun install(): Boolean {
        if (!moneyLoadingInstalled) {
            hookMoneyLoadingView()
        }
        if (!tickerInstalled) {
            hookTickerView()
        }
        if (!mallWalletCellInstalled) {
            hookMallWalletSectionCell()
        }
        if (!lqtDetailEntryInstalled) {
            hookLqtDetailEntry()
        }
        return moneyLoadingInstalled || tickerInstalled || mallWalletCellInstalled || lqtDetailEntryInstalled
    }

    private fun hookMoneyLoadingView() {
        val clazz = KavaReflector.loadClass(WC_PAY_MONEY_LOADING_VIEW, context.hostClassLoader()) ?: return
        val methods = KavaReflector.declaredMethods(clazz).filter { isMoneyLoadingMethod(it) }
        if (methods.isEmpty()) return
        var hooked = 0
        methods.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val raw = param.args?.getOrNull(0) as? String
                    val view = param.thisObject as? View
                    if (enterAmountHook(view, raw)) return
                    view ?: return
                    val target = targetForMoneyView(view) ?: AmountTarget.BALANCE
                    if (requiresFinalTextSize(target) && !moneyTickerReady(view)) return
                    val source = raw ?: return
                    if (!isEnabled(target)) return
                    val original = originalAmountText(view, target, source)
                    val amount = currentAmountText(target, original)
                    val rendered = formatAmountLike(original, amount)
                    rememberRenderedAmount(view, target, rendered)
                    markAmountOverride(target, original)
                    param.args?.set(0, rendered)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    exitAmountHook()
                }
            })
            hooked++
        }
        moneyLoadingInstalled = hooked > 0
    }

    private fun hookTickerView() {
        val methods = locateTickerMethods()
        val tickerClass = KavaReflector.loadClass(TICKER_VIEW, context.hostClassLoader())
        val textSizeMethod = KavaReflector.findDeclaredMethod(
            tickerClass,
            "setTextSize",
            java.lang.Float.TYPE
        )
        if (methods.isEmpty() && textSizeMethod == null) return
        var hooked = 0
        methods.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val raw = param.args?.getOrNull(0) as? String
                    val view = param.thisObject as? View
                    if (enterAmountHook(view, raw)) return
                    val source = raw ?: return
                    if (source.none { it.isDigit() }) return
                    view ?: return
                    val target = targetForMoneyView(view) ?: AmountTarget.BALANCE
                    if (requiresFinalTextSize(target) && tickerTextSizeReady[view] != true) return
                    if (!isEnabled(target)) return
                    val original = originalAmountText(view, target, source)
                    val amount = currentAmountText(target, original)
                    finishTickerAnimation(view)
                    val rendered = formatAmountLike(original, amount)
                    rememberRenderedAmount(view, target, rendered)
                    markAmountOverride(target, original)
                    param.args?.set(0, rendered)
                    if (method.parameterTypes.size == 2) {
                        param.args?.set(1, false)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    exitAmountHook()
                }
            })
            hooked++
        }
        if (textSizeMethod != null) {
            HookRegistry.get().hook(textSizeMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val target = targetForMoneyView(view) ?: AmountTarget.BALANCE
                    if (!isEnabled(target)) return
                    finishTickerAnimation(view)
                    if (view.parent != null && requiresFinalTextSize(target)) {
                        tickerTextSizeReady[view] = true
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val target = targetForMoneyView(view) ?: AmountTarget.BALANCE
                    if (!isEnabled(target)) return
                    refreshTickerCharacterMetrics(view)
                    if (requiresFinalTextSize(target) && tickerTextSizeReady[view] == true) {
                        applyCurrentAmountToTicker(view)
                    }
                }
            })
            hooked++
        }
        tickerInstalled = hooked > 0
    }

    private fun requiresFinalTextSize(target: AmountTarget?): Boolean {
        return target == AmountTarget.LQT || target == AmountTarget.BUSINESS
    }

    private fun moneyTickerReady(view: View): Boolean {
        val ticker = findTickerView(view) ?: return false
        return tickerTextSizeReady[ticker] == true
    }

    private fun findTickerView(view: View): View? {
        if (view.javaClass.name == TICKER_VIEW) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTickerView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun applyCurrentAmountToTicker(view: View) {
        val raw = synchronized(amountTextStates) {
            amountTextStates[view]?.original
        } ?: (KavaReflector.invokeMethod(view, "getText") as? String ?: return)
        if (raw.none { it.isDigit() }) return
        KavaReflector.invokeMethod(view, "setText", raw)
    }

    private fun finishTickerAnimation(tickerView: Any?) {
        val animator = tickerAnimator(tickerView) ?: return
        if (animator.isStarted) {
            animator.end()
        }
    }

    private fun refreshTickerCharacterMetrics(tickerView: Any?) {
        tickerAnimator(tickerView)?.setCurrentFraction(1.0f)
    }

    private fun tickerAnimator(tickerView: Any?): ValueAnimator? {
        if (tickerView == null) return null
        val animatorField = KavaReflector.declaredFields(tickerView.javaClass)
            .firstOrNull { field -> ValueAnimator::class.java.isAssignableFrom(field.type) }
            ?: return null
        return KavaReflector.readField(animatorField, tickerView) as? ValueAnimator
    }

    private fun hookMallWalletSectionCell() {
        val clazz = KavaReflector.loadClass(MALL_WALLET_SECTION_CELL_VIEW, context.hostClassLoader()) ?: return
        val methods = KavaReflector.declaredMethods(clazz).filter { isMallWalletSectionCellMethod(it) }
        if (methods.isEmpty()) return
        var hooked = 0
        methods.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val args = param.args
                    val view = param.thisObject as? View
                    val raw = args?.getOrNull(3) as? String
                    if (enterAmountHook(view, raw)) return
                    args ?: return
                    val target = when (KavaReflector.readField(args.getOrNull(0), "i") as? String) {
                        "balance_cell" -> AmountTarget.BALANCE
                        "lqt_cell" -> AmountTarget.LQT
                        else -> return
                    }
                    if (!isEnabled(target)) return
                    val original = originalAmountText(view, target, raw.orEmpty())
                    val rendered = formatAmountLike(original, currentAmountText(target, original))
                    rememberRenderedAmount(view, target, rendered)
                    markAmountOverride(target, original)
                    args[3] = rendered
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    exitAmountHook()
                }
            })
            hooked++
        }
        mallWalletCellInstalled = hooked > 0
    }

    private fun hookLqtDetailEntry() {
        val clazz = KavaReflector.loadClass(WX_CROSS_SERVICES, context.hostClassLoader()) ?: return
        val methods = KavaReflector.declaredMethods(clazz).filter { isLqtDetailEntryMethod(it) }
        if (methods.isEmpty()) return
        var hooked = 0
        methods.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (enterAmountHook(null, null)) return
                    if (!isEnabled(AmountTarget.LQT)) return
                    val actualCents = param.args?.getOrNull(1) as? Long ?: return
                    val actual = java.math.BigDecimal.valueOf(actualCents, 2).toPlainString()
                    val rendered = currentAmountText(AmountTarget.LQT, actual)
                    val cents = amountTextToCents(rendered) ?: return
                    markAmountOverride(AmountTarget.LQT, actual)
                    param.args?.set(1, cents)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    exitAmountHook()
                }
            })
            hooked++
        }
        lqtDetailEntryInstalled = hooked > 0
    }

    private fun locateTickerMethods(): List<Method> {
        val cacheKey = methodCacheKey()
        DexMethodCache.loadList(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_TICKER_METHODS)
            .filter { isTickerTextMethod(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        val clazz = KavaReflector.loadClass(TICKER_VIEW, context.hostClassLoader())
        val byClass = clazz?.let { targetClass ->
            KavaReflector.declaredMethods(targetClass).filter { isTickerTextMethod(it) }
        }.orEmpty()
        if (byClass.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, cacheKey, CACHE_TICKER_METHODS, byClass)
            return byClass
        }
        val byDex = findTickerMethods()
        if (byDex.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, cacheKey, CACHE_TICKER_METHODS, byDex)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_TICKER_METHODS)
        }
        return byDex
    }

    private fun findTickerMethods(): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(TICKER_VIEW)
                            usingEqStrings("Need to call #setCharacterLists first.")
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    ?.takeIf { isTickerTextMethod(it) }
            }
        }.getOrElse {
            HLog.e("$TAG 定位 TickerView 金额方法失败: ${it.message}", it)
            emptyList()
        }
    }

    private fun isMoneyLoadingMethod(method: Method): Boolean {
        val types = method.parameterTypes
        if (types.isEmpty() || types[0] != String::class.java) return false
        if (method.name in moneyLoadingNamedMethods && types.size == 1) return true
        return (types.size == 2 || types.size == 4) &&
            types.drop(1).all { it == java.lang.Boolean.TYPE }
    }

    private fun isTickerTextMethod(method: Method): Boolean {
        val types = method.parameterTypes
        if (types.isEmpty() || types[0] != String::class.java) return false
        return types.size == 1 || (types.size == 2 && types[1] == java.lang.Boolean.TYPE)
    }

    private fun isMallWalletSectionCellMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == java.lang.Void.TYPE &&
            types.size == 7 &&
            types[1].name == "org.json.JSONObject" &&
            types[2] == java.lang.Boolean.TYPE &&
            types[3] == String::class.java &&
            types[4] == java.lang.Boolean.TYPE
    }

    private fun isLqtDetailEntryMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.name == "startLqtDetailUseCaseWithBalanceInMMProcess" &&
            method.returnType == java.lang.Boolean.TYPE &&
            types.size == 2 &&
            Context::class.java.isAssignableFrom(types[0]) &&
            types[1] == java.lang.Long.TYPE
    }

    private fun targetForMoneyView(view: View?): AmountTarget? {
        return targetForLocalViewTree(view)
            ?: targetForContext(view?.context)
            ?: targetForCallStack()
    }

    private fun targetForLocalViewTree(view: View?): AmountTarget? {
        var current = view
        var depth = 0
        while (current != null && depth < 8) {
            targetForText(current.contentDescription?.toString())?.let { return it }
            if (current is TextView) {
                targetForText(current.text?.toString())?.let { return it }
            }
            val parent = current.parent as? View
            if (parent is ViewGroup) {
                targetForSiblingText(parent, current)?.let { return it }
            }
            current = parent
            depth++
        }
        return null
    }

    private fun targetForSiblingText(group: ViewGroup, source: View): AmountTarget? {
        var balance = false
        var lqt = false
        var business = false
        collectNearbyText(group, source, depth = 0) { text ->
            when (targetForText(text)) {
                AmountTarget.BUSINESS -> business = true
                AmountTarget.LQT -> lqt = true
                AmountTarget.BALANCE -> balance = true
                null -> Unit
            }
        }
        return when {
            business -> AmountTarget.BUSINESS
            lqt -> AmountTarget.LQT
            balance -> AmountTarget.BALANCE
            else -> null
        }
    }

    private fun collectNearbyText(view: View, source: View, depth: Int, block: (String) -> Unit) {
        if (depth > 3) return
        if (view !== source) {
            view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(block)
            if (view is TextView) {
                view.text?.toString()?.takeIf { it.isNotBlank() }?.let(block)
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectNearbyText(view.getChildAt(index), source, depth + 1, block)
            }
        }
    }

    private fun targetForText(text: String?): AmountTarget? {
        val normalized = text.orEmpty().replace("\\s+".toRegex(), "")
        if (normalized.isEmpty()) return null
        if (BUSINESS_ACCOUNT_LABELS.any { it in normalized }) return AmountTarget.BUSINESS
        if ("零钱通" in normalized || "理财通" in normalized) return AmountTarget.LQT
        if ("零钱" in normalized || "钱包余额" in normalized) return AmountTarget.BALANCE
        return null
    }

    private fun currentAmountText(target: AmountTarget, actual: String): String {
        val (amountKey, modeKey) = when (target) {
            AmountTarget.BALANCE -> FakeWalletBalanceSettings.KEY_BALANCE_AMOUNT to
                FakeWalletBalanceSettings.KEY_BALANCE_MODE
            AmountTarget.LQT -> FakeWalletBalanceSettings.KEY_LQT_AMOUNT to
                FakeWalletBalanceSettings.KEY_LQT_MODE
            AmountTarget.BUSINESS -> FakeWalletBalanceSettings.KEY_BUSINESS_AMOUNT to
                FakeWalletBalanceSettings.KEY_BUSINESS_MODE
        }
        val fallback = if (target == AmountTarget.BUSINESS) {
            prefs.getString(
                FakeWalletBalanceSettings.KEY_LQT_AMOUNT,
                FakeWalletBalanceSettings.DEFAULT_AMOUNT
            )
        } else {
            FakeWalletBalanceSettings.DEFAULT_AMOUNT
        }
        val raw = prefs.getString(amountKey, fallback)
        val fallbackMode = if (
            target == AmountTarget.BUSINESS &&
            !prefs.contains(FakeWalletBalanceSettings.KEY_BUSINESS_AMOUNT)
        ) {
            FakeWalletBalanceSettings.amountMode(
                prefs,
                FakeWalletBalanceSettings.KEY_LQT_MODE,
                fallback
            )
        } else {
            FakeWalletBalanceSettings.DEFAULT_MODE
        }
        val mode = FakeWalletBalanceSettings.amountMode(prefs, modeKey, raw, fallbackMode)
        return FakeWalletBalanceSettings.resolveAmount(raw, actual, mode)
    }

    private fun originalAmountText(view: View?, target: AmountTarget, raw: String): String {
        if (view == null) return raw
        synchronized(amountTextStates) {
            val state = amountTextStates[view]
            if (state?.target == target && sameAmount(raw, state.rendered)) {
                return formatAmountLike(raw, FakeWalletBalanceSettings.amountDecimal(state.original).toPlainString())
            }
            amountTextStates[view] = AmountTextState(target, raw, raw)
        }
        return raw
    }

    private fun rememberRenderedAmount(view: View?, target: AmountTarget, rendered: String) {
        if (view == null) return
        synchronized(amountTextStates) {
            val original = amountTextStates[view]
                ?.takeIf { it.target == target }
                ?.original
                ?: rendered
            amountTextStates[view] = AmountTextState(target, original, rendered)
        }
    }

    private fun enterAmountHook(view: View?, raw: String?): Boolean {
        val frames = amountHookFrames.get() ?: ArrayDeque<Boolean>().also(amountHookFrames::set)
        frames.addLast(false)
        val active = activeAmountOverride.get() ?: return false
        if (view != null && raw != null && raw.any { it.isDigit() }) {
            val original = formatAmountLike(raw, FakeWalletBalanceSettings.amountDecimal(active.original).toPlainString())
            synchronized(amountTextStates) {
                amountTextStates[view] = AmountTextState(active.target, original, raw)
            }
        }
        return true
    }

    private fun markAmountOverride(target: AmountTarget, original: String) {
        val frames = amountHookFrames.get() ?: return
        if (frames.isEmpty()) return
        frames.removeLast()
        frames.addLast(true)
        activeAmountOverride.set(AmountOverride(target, original))
    }

    private fun exitAmountHook() {
        val frames = amountHookFrames.get() ?: return
        if (frames.isEmpty()) {
            amountHookFrames.remove()
            activeAmountOverride.remove()
            return
        }
        if (frames.removeLast()) activeAmountOverride.remove()
        if (frames.isEmpty()) amountHookFrames.remove()
    }

    private fun sameAmount(first: String, second: String): Boolean {
        if (first.none { it.isDigit() } || second.none { it.isDigit() }) return false
        return FakeWalletBalanceSettings.amountDecimal(first)
            .compareTo(FakeWalletBalanceSettings.amountDecimal(second)) == 0
    }

    private fun isEnabled(target: AmountTarget): Boolean {
        val key = when (target) {
            AmountTarget.BALANCE -> FakeWalletBalanceSettings.KEY_BALANCE_ENABLE
            AmountTarget.LQT -> FakeWalletBalanceSettings.KEY_LQT_ENABLE
            AmountTarget.BUSINESS -> FakeWalletBalanceSettings.KEY_BUSINESS_ENABLE
        }
        return FakeWalletBalanceSettings.isAccountEnabled(prefs, key)
    }

    private fun targetForCallStack(): AmountTarget? {
        for (frame in Thread.currentThread().stackTrace) {
            val lower = frame.className.lowercase(Locale.US)
            when {
                "lqt" in lower -> return AmountTarget.LQT
                "walletbalancemanagerui" in lower -> return AmountTarget.BALANCE
                "mallindexui" in lower -> return AmountTarget.BALANCE
                "mallwallet" in lower -> return AmountTarget.BALANCE
            }
        }
        return null
    }

    private fun targetForContext(context: Context?): AmountTarget? {
        val activity = activityFrom(context) ?: return null
        targetForText(activity.title?.toString())?.let { return it }
        var current: Class<*>? = activity.javaClass
        while (current != null && current != Activity::class.java) {
            val lower = current.name.lowercase(Locale.US)
            when {
                "lqt" in lower -> return AmountTarget.LQT
                "moneyfund" in lower -> return AmountTarget.LQT
                "walletbalancemanagerui" in lower -> return AmountTarget.BALANCE
                "mallindexui" in lower -> return AmountTarget.BALANCE
                "mallwallet" in lower -> return AmountTarget.BALANCE
                ".wallet.balance.ui." in lower -> return AmountTarget.BALANCE
                ".plugin.mall.ui." in lower -> return AmountTarget.BALANCE
            }
            current = current.superclass
        }
        return null
    }

    private fun activityFrom(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun formatAmountLike(existing: String, amount: String): String {
        val trimmed = existing.trim()
        if (trimmed.isEmpty()) return amount
        val firstDigit = trimmed.indexOfFirst { it.isDigit() }
        if (firstDigit < 0) {
            return if (trimmed.any { it == '¥' || it == '￥' }) trimmed + amount else amount
        }
        var end = firstDigit
        while (end < trimmed.length) {
            val ch = trimmed[end]
            if (!ch.isDigit() && ch != ',' && ch != '.') break
            end++
        }
        return trimmed.substring(0, firstDigit) + amount + trimmed.substring(end)
    }

    private fun amountTextToCents(amount: String): Long? {
        return runCatching {
            FakeWalletBalanceSettings.amountDecimal(amount)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        }.getOrNull()
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    companion object {
        private const val TAG = "[Hchat:FakeWalletBalance]"
        private const val CACHE_PREFS = "Hchat_fake_wallet_balance_method_cache"
        private const val CACHE_SCHEMA = "fake_wallet_balance_wekit_style_v1"
        private const val CACHE_TICKER_METHODS = "ticker_methods"
        private const val MALL_WALLET_SECTION_CELL_VIEW =
            "com.tencent.mm.plugin.mall.ui.MallWalletSectionCellView"
        private const val WX_CROSS_SERVICES =
            "com.tencent.kinda.framework.WxCrossServices"
        private const val TICKER_VIEW = "com.robinhood.ticker.TickerView"
        private const val WC_PAY_MONEY_LOADING_VIEW =
            "com.tencent.mm.plugin.wallet_core.ui.view.WcPayMoneyLoadingView"
        private val moneyLoadingNamedMethods = setOf("setMoney", "setFirstMoney", "setNewMoney")
        private val BUSINESS_ACCOUNT_LABELS = listOf(
            "经营账户",
            "经营账号",
            "商户账户",
            "商户余额",
            "商家账户"
        )
    }

    private enum class AmountTarget {
        BALANCE,
        LQT,
        BUSINESS
    }

    private data class AmountOverride(
        val target: AmountTarget,
        val original: String
    )

    private data class AmountTextState(
        val target: AmountTarget,
        val original: String,
        val rendered: String
    )
}
