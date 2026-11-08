package h.Hchat.hooks.items.messagetextcolor

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.R
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatMessage
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
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class MessageTextColorFeature : BaseFeature() {
    private var renderer: MessageTextColorRenderer? = null

    override fun featureId(): String = ID

    override fun name(): String = "消息文本颜色"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MessageTextColorSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        renderer = MessageTextColorRenderer(context)
        DexInstallScheduler.schedule(ID, name()) {
            renderer?.install() == true
        }
    }

    companion object {
        const val ID = "message_text_color"
    }
}

private class MessageTextColorRenderer(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MessageTextColorSettings.PREFS_NAME)
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_message_text_color_method_cache")
    private val itemMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val itemListFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderRootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val appliedBindingStates =
        Collections.synchronizedMap(WeakHashMap<View, AppliedBindingState>())
    private val nativeMessageCache =
        Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val colorSpecCache = ConcurrentHashMap<String, ColorSpec>()
    private val neatViewClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val neatMethodCache = ConcurrentHashMap<String, Method>()
    private val neatMethodMissCache = ConcurrentHashMap.newKeySet<String>()
    private val neatFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val neatFieldMissCache = ConcurrentHashMap.newKeySet<Class<*>>()
    private val messageBodyViewId by lazy {
        context.hostContext().resources.getIdentifier(MESSAGE_BODY_VIEW_RESOURCE, "id", HOST_PACKAGE)
    }
    private val voipTextViewId by lazy {
        context.hostContext().resources.getIdentifier(VOIP_TEXT_VIEW_RESOURCE, "id", HOST_PACKAGE)
    }
    private val chatRecordTextViewIds by lazy {
        CHAT_RECORD_TEXT_VIEW_RESOURCES.mapNotNull { name ->
            context.hostContext().resources.getIdentifier(name, "id", HOST_PACKAGE)
                .takeIf { it != 0 }
        }
    }
    @Volatile private var adapterBindInstalled = false

    @Synchronized
    fun install(): Boolean {
        if (adapterBindInstalled) return true
        val method = locateAdapterBindMethod() ?: run {
            HLog.e("$TAG 定位聊天消息绑定方法失败")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    applyTextColor(param)
                }
            })
            adapterBindInstalled = true
            true
        }.getOrElse {
            HLog.e("$TAG 安装聊天消息绑定 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun applyTextColor(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        if (args.size < 2) return
        val holder = args[0] ?: return
        val position = args[1] as? Int ?: return
        val root = findRootView(holder) ?: return
        if (!isEnabled()) {
            clearBinding(root)
            return
        }

        val adapter = param.thisObject ?: run {
            clearBinding(root)
            return
        }
        val item = adapterItem(adapter, position) ?: run {
            clearBinding(root)
            return
        }
        val nativeMessage = resolveNativeMessage(item) ?: run {
            clearBinding(root)
            return
        }
        val msgId = messageId(nativeMessage)
        val talker = WeChatApis.chatPage()?.currentTalker().orEmpty()
        val message = messageFromNative(nativeMessage, talker, msgId) ?: run {
            clearBinding(root)
            return
        }
        val spec = colorSpec(outgoing = message.isOutgoing(), darkMode = isDarkMode(root.context))
            ?: run {
                clearBinding(root)
                return
            }
        val body = message.bodyContent()
        val overrideMessageSpans = message.isQuote() || message.isNote() ||
            (message.isText() && (body.contains('#') || body.contains('＃')))
        val messageKey = if (msgId > 0L) {
            msgId
        } else {
            (position.toLong() shl 32) xor System.identityHashCode(nativeMessage).toLong()
        }
        val previous = synchronized(appliedBindingStates) { appliedBindingStates[root] }
        if (previous?.messageKey == messageKey &&
            previous.spec == spec &&
            previous.overrideSpanColors == overrideMessageSpans &&
            isBindingApplied(previous)
        ) {
            return
        }
        clearBinding(root)
        val targets = resolveMessageTextTargets(root, message)
        if (targets.isEmpty()) return
        targets.forEach { target ->
            applyColorSpec(
                target,
                spec,
                overrideSpanColors = overrideMessageSpans || hasClickableSpan(target)
            )
        }
        synchronized(appliedBindingStates) {
            appliedBindingStates[root] = AppliedBindingState(
                messageKey = messageKey,
                spec = spec,
                overrideSpanColors = overrideMessageSpans,
                targetViews = targets.map { WeakReference(it.view) }
            )
        }
    }

    private fun isBindingApplied(state: AppliedBindingState): Boolean {
        return state.targetViews.isNotEmpty() && state.targetViews.all { reference ->
            reference.get()?.getTag(R.id.hchat_message_text_color_applied) == true
        }
    }

    private fun resolveMessageTextTargets(root: View, message: WeChatMessage): List<TextTarget> {
        if (message.isVoip()) return voipTextTargets(root)
        if (isChatRecord(message)) return chatRecordTextTargets(root)
        val target = boundMessageBodyTarget(root, message)
            ?: originalMessageTextTarget(root, message)
            ?: findMessageTextTarget(root, messageTextCandidates(message))
            ?: visibleMessageTextTarget(root, message)
        return target?.let(::listOf).orEmpty()
    }

    private fun isChatRecord(message: WeChatMessage): Boolean {
        return message.isApp() && message.appMsgType() == CHAT_RECORD_APP_MSG_TYPE
    }

    private fun chatRecordTextTargets(root: View): List<TextTarget> {
        return chatRecordTextViewIds.mapNotNull { id ->
            val view = root.findViewById<View>(id) ?: return@mapNotNull null
            if (view.visibility != View.VISIBLE) return@mapNotNull null
            val textView = targetTextView(view) ?: return@mapNotNull null
            TextTarget(view, textView)
        }.distinctBy { it.view }
    }

    private fun voipTextTargets(root: View): List<TextTarget> {
        val id = voipTextViewId.takeIf { it != 0 } ?: return emptyList()
        val view = root.findViewById<View>(id) ?: return emptyList()
        if (view.visibility != View.VISIBLE) return emptyList()
        val textView = targetTextView(view) ?: return emptyList()
        if (renderedText(view, textView).isBlank()) return emptyList()
        return listOf(TextTarget(view, textView))
    }

    private fun hasClickableSpan(target: TextTarget): Boolean {
        val text = renderedText(target.view, target.textView) as? Spanned ?: return false
        if (text.isEmpty()) return false
        return text.getSpans(0, text.length, ClickableSpan::class.java).isNotEmpty()
    }

    private fun renderedText(view: View, textView: TextView): CharSequence {
        if (isMessageNeatTextView(view)) {
            invokeNeatMethod(view, "a")
                ?.let { it as? CharSequence }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            readNeatTextField(view)
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return textView.text
    }

    private fun originalMessageTextTarget(root: View, message: WeChatMessage): TextTarget? {
        if (!message.isText()) return null
        val body = message.bodyContent().takeIf { it.isNotBlank() } ?: return null
        return findOriginalMessageTextTarget(root, body)
    }

    private fun clearBinding(root: View) {
        val state = synchronized(appliedBindingStates) {
            appliedBindingStates.remove(root)
        } ?: return
        state.targetViews.forEach { reference ->
            reference.get()?.let(::clearAppliedTextView)
        }
    }

    private fun clearAppliedTextView(view: View) {
        if (view.getTag(R.id.hchat_message_text_color_applied) == true) {
            val textView = targetTextView(view)
            val state = view.getTag(R.id.hchat_message_text_color_original) as? AppliedTextState
            if (textView != null && state != null) {
                if (state.appliedShader != null && textView.paint.shader === state.appliedShader) {
                    textView.paint.shader = state.originalShader
                }
                if (textView.currentTextColor == state.appliedColor) {
                    setTargetTextColors(view, textView, state.originalTextColors)
                }
                if (textView.paint.linkColor == state.appliedColor) {
                    setTargetLinkTextColors(
                        view,
                        textView,
                        state.originalLinkTextColors,
                        state.originalLinkColor
                    )
                }
            } else {
                val originalColor = view.getTag(R.id.hchat_message_text_color_original) as? Int
                val appliedColor = view.getTag(R.id.hchat_message_text_color_value) as? Int
                if (originalColor != null &&
                    appliedColor != null &&
                    textView != null &&
                    textView.currentTextColor == appliedColor
                ) {
                    textView.paint.shader = null
                    setTargetTextColor(view, textView, originalColor)
                }
            }
            view.setTag(R.id.hchat_message_text_color_applied, null)
            view.setTag(R.id.hchat_message_text_color_original, null)
            view.setTag(R.id.hchat_message_text_color_value, null)
            view.invalidate()
        }
    }

    private fun applyColorSpec(target: TextTarget, spec: ColorSpec, overrideSpanColors: Boolean) {
        val view = target.view
        val textView = target.textView
        val state = AppliedTextState(
            originalTextColors = textView.textColors,
            originalLinkTextColors = textView.linkTextColors,
            originalLinkColor = textView.paint.linkColor,
            originalShader = textView.paint.shader,
            appliedColor = spec.startColor
        )
        view.setTag(R.id.hchat_message_text_color_applied, true)
        view.setTag(R.id.hchat_message_text_color_original, state)
        view.setTag(R.id.hchat_message_text_color_value, spec.startColor)
        setTargetTextColor(view, textView, spec.startColor)
        setTargetLinkTextColor(view, textView, spec.startColor)
        if (spec.isGradient || overrideSpanColors) {
            updateTextShader(view, textView, spec, state)
        }
    }

    private fun updateTextShader(
        view: View,
        textView: TextView,
        spec: ColorSpec,
        state: AppliedTextState
    ) {
        val width = textView.paint.measureText(textView.text?.toString().orEmpty())
            .coerceAtLeast(view.width.toFloat())
        if (width <= 0f) {
            if (!state.shaderUpdatePosted && state.shaderRetryCount < MAX_SHADER_RETRIES) {
                state.shaderUpdatePosted = true
                view.post {
                    state.shaderUpdatePosted = false
                    if (view.getTag(R.id.hchat_message_text_color_original) === state) {
                        state.shaderRetryCount++
                        updateTextShader(view, textView, spec, state)
                    }
                }
            }
            return
        }
        val shader = LinearGradient(
            0f,
            0f,
            width,
            0f,
            spec.startColor,
            spec.endColor,
            Shader.TileMode.CLAMP
        )
        state.appliedShader = shader
        textView.paint.shader = shader
        textView.invalidate()
        if (view !== textView) view.invalidate()
    }

    private fun colorSpec(outgoing: Boolean, darkMode: Boolean): ColorSpec? {
        val key = when {
            outgoing && darkMode -> MessageTextColorSettings.KEY_RIGHT_DARK_COLOR
            outgoing -> MessageTextColorSettings.KEY_RIGHT_LIGHT_COLOR
            darkMode -> MessageTextColorSettings.KEY_LEFT_DARK_COLOR
            else -> MessageTextColorSettings.KEY_LEFT_LIGHT_COLOR
        }
        val fallback = when {
            outgoing && darkMode -> MessageTextColorSettings.DEFAULT_RIGHT_DARK_COLOR
            outgoing -> MessageTextColorSettings.DEFAULT_RIGHT_LIGHT_COLOR
            darkMode -> MessageTextColorSettings.DEFAULT_LEFT_DARK_COLOR
            else -> MessageTextColorSettings.DEFAULT_LEFT_LIGHT_COLOR
        }
        val raw = prefs.getString(key, fallback) ?: fallback
        val cacheKey = "$key|$raw"
        colorSpecCache[cacheKey]?.let { return it }
        val parsed = parseColorSpec(raw) ?: parseColorSpec(fallback)
        parsed?.let { colorSpecCache[cacheKey] = it }
        return parsed
    }

    private fun parseColorSpec(value: String): ColorSpec? {
        val cleaned = MessageTextColorSettings.cleanColorSpec(value)
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(',').take(2)
        val start = parseColor(parts.firstOrNull().orEmpty()) ?: return null
        val end = parseColor(parts.getOrNull(1).orEmpty()) ?: start
        return ColorSpec(start, end)
    }

    private fun parseColor(value: String): Int? {
        val cleaned = MessageTextColorSettings.cleanColor(value)
        if (cleaned.isEmpty()) return null
        return runCatching { Color.parseColor(cleaned) }.getOrNull()
    }

    private fun findOriginalMessageTextTarget(root: View, body: String): TextTarget? {
        val targets = originalMessageTextTargets(body)
        if (targets.isEmpty()) return null
        val matches = ArrayList<TextTarget>()
        collectOriginalMessageTextTargets(root, targets, exact = true, matches)
        if (matches.isEmpty()) {
            collectOriginalMessageTextTargets(root, targets, exact = false, matches)
        }
        return matches.sortedWith(
            compareBy<TextTarget> { legacyNormalizeText(neatText(it.view, it.textView)).length }
                .thenByDescending { it.textView.textSize }
        ).firstOrNull()
    }

    private fun boundMessageBodyTarget(root: View, message: WeChatMessage): TextTarget? {
        if (!message.isText() && !message.isQuote() && !message.isNote() && !message.isVoice()) {
            return null
        }
        val id = messageBodyViewId.takeIf { it != 0 } ?: return null
        val view = root.findViewById<View>(id) ?: return null
        val textView = targetTextView(view) ?: return null
        return TextTarget(view, textView)
    }

    private fun originalMessageTextTargets(body: String): List<String> {
        val normalized = legacyNormalizeText(body)
        if (normalized.isEmpty()) return emptyList()
        val targets = LinkedHashSet<String>()
        targets += normalized
        val groupPrefixEnd = normalized.indexOf(":\n")
        if (groupPrefixEnd > 0 && groupPrefixEnd + 2 < normalized.length) {
            targets += legacyNormalizeText(normalized.substring(groupPrefixEnd + 2))
        }
        return targets.filter { it.isNotBlank() }
    }

    private fun collectOriginalMessageTextTargets(
        view: View,
        targets: List<String>,
        exact: Boolean,
        out: MutableList<TextTarget>
    ) {
        val textView = targetTextView(view)
        if (textView != null) {
            val text = legacyNormalizeText(neatText(view, textView))
            if (text.isNotEmpty() && targets.any { target ->
                    if (exact) text == target else legacyTextContainsMessage(text, target)
                }
            ) {
                out += TextTarget(view, textView)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectOriginalMessageTextTargets(view.getChildAt(i), targets, exact, out)
            }
        }
    }

    private fun findMessageTextTarget(root: View, candidates: List<TextCandidate>): TextTarget? {
        if (candidates.isEmpty()) return null
        val matches = ArrayList<TextMatch>()
        collectMessageTextTargets(root, candidates, exact = true, matches)
        if (matches.isEmpty()) {
            collectMessageTextTargets(root, candidates, exact = false, matches)
        }
        return matches.sortedWith(
            compareBy<TextMatch> { it.candidate.priority }
                .thenBy { if (it.exact) 0 else 1 }
                .thenBy { normalizeText(neatText(it.target.view, it.target.textView)).length }
                .thenByDescending { it.target.textView.textSize }
        ).firstOrNull()?.target
    }

    private fun visibleMessageTextTarget(root: View, message: WeChatMessage): TextTarget? {
        if (!message.isText()) return null
        val matches = ArrayList<TextTarget>()
        collectVisibleMessageTextTargets(root, matches)
        return matches.sortedWith(
            compareByDescending<TextTarget> { visibleTargetScore(root, it) }
                .thenBy { normalizeText(neatText(it.view, it.textView)).length }
        ).firstOrNull()
    }

    private fun targetTextView(view: View): TextView? {
        if (isMessageNeatTextView(view)) {
            return invokeNeatMethod(view, "getWrappedTextView") as? TextView
                ?: view as? TextView
        }
        return view as? TextView
    }

    private fun isMessageNeatTextView(view: View): Boolean {
        val clazz = view.javaClass
        neatViewClassCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        var result = false
        while (current != null && current != Any::class.java) {
            val name = current.name
            if (name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
                name == "com.tencent.neattextview.textview.view.NeatTextView" ||
                name.contains("NeatTextView")
            ) {
                result = true
                break
            }
            current = current.superclass
        }
        neatViewClassCache.putIfAbsent(clazz, result)
        return result
    }

    private fun collectMessageTextTargets(
        view: View,
        candidates: List<TextCandidate>,
        exact: Boolean,
        out: MutableList<TextMatch>
    ) {
        val textView = targetTextView(view)
        if (textView != null) {
            val text = normalizeText(neatText(view, textView))
            if (text.isNotEmpty()) {
                val candidate = candidates.firstOrNull { candidate ->
                    if (exact) {
                        textMatchesMessage(text, candidate.text)
                    } else {
                        textContainsMessage(text, candidate.text)
                    }
                }
                if (candidate != null) {
                    out += TextMatch(TextTarget(view, textView), candidate, exact)
                }
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectMessageTextTargets(view.getChildAt(i), candidates, exact, out)
            }
        }
    }

    private fun collectVisibleMessageTextTargets(view: View, out: MutableList<TextTarget>) {
        val textView = if (isMessageNeatTextView(view)) targetTextView(view) else null
        if (textView != null) {
            val text = normalizeText(neatText(view, textView))
            if (isVisibleMessageBodyText(text, textView)) {
                out += TextTarget(view, textView)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectVisibleMessageTextTargets(view.getChildAt(i), out)
            }
        }
    }

    private fun isVisibleMessageBodyText(text: String, textView: TextView): Boolean {
        if (text.isBlank()) return false
        if (text.length > 1200) return false
        if (text.matches(Regex("""\d{1,2}:\d{2}"""))) return false
        if (text == "已读" || text == "未读") return false
        val width = textView.width.takeIf { it > 0 } ?: textView.measuredWidth
        val height = textView.height.takeIf { it > 0 } ?: textView.measuredHeight
        if (width <= 0 || height <= 0) return false
        if (height < dp(textView, 12f)) return false
        return true
    }

    private fun visibleTargetScore(root: View, target: TextTarget): Int {
        val view = target.view
        val textView = target.textView
        val width = view.width.takeIf { it > 0 } ?: textView.width.takeIf { it > 0 } ?: textView.measuredWidth
        val height = view.height.takeIf { it > 0 } ?: textView.height.takeIf { it > 0 } ?: textView.measuredHeight
        var score = width.coerceAtLeast(0) * height.coerceAtLeast(0)
        if (view.background != null || textView.background != null) score += 100_000
        if (textView.textSize >= dp(textView, 13f)) score += 20_000
        val location = IntArray(2)
        val rootLocation = IntArray(2)
        view.getLocationOnScreen(location)
        root.getLocationOnScreen(rootLocation)
        if (location[1] >= rootLocation[1]) score += 5_000
        return score
    }

    private fun neatText(view: View, textView: TextView): String {
        if (isMessageNeatTextView(view)) {
            invokeNeatMethod(view, "a")
                ?.let { it as? CharSequence }
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            readNeatTextField(view)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        view.contentDescription
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return textView.text?.toString().orEmpty()
    }

    private fun invokeNeatMethod(view: View, name: String, vararg args: Any?): Any? {
        val key = "${view.javaClass.name}#$name"
        val method = neatMethodCache[key] ?: run {
            if (neatMethodMissCache.contains(key)) return null
            KavaReflector.findCompatibleMethod(view.javaClass, name, *args)?.also {
                neatMethodCache.putIfAbsent(key, it)
            } ?: run {
                neatMethodMissCache.add(key)
                return null
            }
        }
        return KavaReflector.invoke(method, view, *args)
    }

    private fun readNeatTextField(view: View): CharSequence? {
        val clazz = view.javaClass
        val field = neatFieldCache[clazz] ?: run {
            if (neatFieldMissCache.contains(clazz)) return null
            KavaReflector.findFieldRecursive(clazz, "x")?.also {
                neatFieldCache.putIfAbsent(clazz, it)
            } ?: run {
                neatFieldMissCache.add(clazz)
                return null
            }
        }
        return KavaReflector.readField(field, view) as? CharSequence
    }

    private fun messageTextCandidates(message: WeChatMessage): List<TextCandidate> {
        if (!message.isText() && !message.isQuote() && !message.isNote()) return emptyList()
        val rawTexts = ArrayList<Pair<Int, String>>()
        if (message.isText()) {
            addRawText(rawTexts, priority = 0, value = message.bodyContent())
        }
        if (message.isQuote()) {
            val raw = message.bodyContent()
            val quote = message.getQuoteMsg()
            addRawText(rawTexts, priority = 0, value = quote?.title)
            addRawText(rawTexts, priority = 0, value = WeChatMessage.xmlTag(raw, "title"))
        }
        if (message.isNote()) {
            addRawText(
                rawTexts,
                priority = 0,
                value = WeChatMessage.xmlTag(message.bodyContent(), "title")
            )
        }
        val targets = LinkedHashMap<String, Int>()
        for ((priority, rawText) in rawTexts) {
            for (target in messageTextTargets(rawText)) {
                val oldPriority = targets[target]
                if (oldPriority == null || priority < oldPriority) {
                    targets[target] = priority
                }
            }
        }
        return targets.entries
            .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .map { TextCandidate(it.key, it.value) }
    }

    private fun addRawText(out: MutableList<Pair<Int, String>>, priority: Int, value: String?) {
        value?.takeIf { it.isNotBlank() }?.let { out += priority to it }
    }

    private fun messageTextTargets(body: String?): List<String> {
        if (body.isNullOrBlank()) return emptyList()
        val targets = LinkedHashSet<String>()
        addMessageTextTarget(targets, decodeMessageText(body))
        return targets.filter { it.isNotBlank() }
    }

    private fun addMessageTextTarget(targets: MutableSet<String>, value: String) {
        val normalized = normalizeText(value)
        if (normalized.isEmpty()) return
        addNormalizedTarget(targets, normalized)
        val visible = stripMarkupText(normalized)
        if (visible != normalized) {
            addNormalizedTarget(targets, visible)
        }
    }

    private fun addNormalizedTarget(targets: MutableSet<String>, value: String) {
        val normalized = normalizeText(value)
        if (normalized.isEmpty()) return
        targets += normalized
        val groupPrefixEnd = normalized.indexOf(":\n")
        if (groupPrefixEnd > 0 && groupPrefixEnd + 2 < normalized.length) {
            targets += normalizeText(normalized.substring(groupPrefixEnd + 2))
        }
    }

    private fun stripMarkupText(value: String): String {
        return normalizeText(
            value
                .replace(Regex("<[^>]+>"), "")
                .replace(Regex("\\{\\{[^}]+}}"), "")
        )
    }

    private fun legacyTextContainsMessage(text: String, target: String): Boolean {
        if (target.length < 2) return false
        if (text.length > target.length * 3 + 12) return false
        return text.contains(target)
    }

    private fun textContainsMessage(text: String, target: String): Boolean {
        if (target.length < 2) return false
        if (text.length > target.length * 3 + 12) return false
        if (text.contains(target)) return true
        return hashTopicComparable(text, target) { left, right ->
            right.length >= 2 && left.length <= right.length * 3 + 12 && left.contains(right)
        }
    }

    private fun textMatchesMessage(text: String, target: String): Boolean {
        if (text == target) return true
        return hashTopicComparable(text, target) { left, right -> left == right }
    }

    private fun hashTopicComparable(text: String, target: String, match: (String, String) -> Boolean): Boolean {
        if (!target.contains('#')) return false
        val plainTarget = target.replace("#", "")
        if (plainTarget.length < 2) return false
        val plainText = text.replace("#", "")
        return match(plainText, plainTarget)
    }

    private fun normalizeText(value: String): String {
        return buildString(value.length) {
            for (char in value) {
                when (char) {
                    '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF', '\uFFFC' -> Unit
                    '\u00A0', '\u2007', '\u202F' -> append(' ')
                    '＃' -> append('#')
                    else -> append(char)
                }
            }
        }
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun dp(view: View, value: Float): Int {
        return android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            value,
            view.resources.displayMetrics
        ).toInt()
    }

    private fun legacyNormalizeText(value: String): String {
        return value
            .replace('\u200B'.toString(), "")
            .replace('\uFEFF'.toString(), "")
            .replace('\u00A0', ' ')
            .trim()
    }

    private fun decodeMessageText(value: String): String {
        var result = value
        repeat(2) {
            result = result
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
            result = Regex("&#(x[0-9a-fA-F]+|[0-9]+);").replace(result) { match ->
                val raw = match.groupValues[1]
                val codePoint = runCatching {
                    if (raw.startsWith("x", ignoreCase = true)) raw.substring(1).toInt(16) else raw.toInt()
                }.getOrNull()
                codePoint?.let { String(Character.toChars(it)) } ?: match.value
            }
        }
        return result
    }

    private fun setTargetTextColor(view: View, textView: TextView, color: Int) {
        if (isMessageNeatTextView(view)) {
            invokeNeatMethod(view, "setTextColor", color)
        }
        textView.setTextColor(color)
    }

    private fun setTargetTextColors(view: View, textView: TextView, colors: ColorStateList) {
        if (isMessageNeatTextView(view)) {
            invokeNeatMethod(view, "setTextColor", colors.defaultColor)
        }
        textView.setTextColor(colors)
    }

    private fun setTargetLinkTextColor(view: View, textView: TextView, color: Int) {
        textView.setLinkTextColor(color)
        if (isMessageNeatTextView(view)) {
            invokeNeatMethod(view, "setLinkTextColor", color)
        }
    }

    private fun setTargetLinkTextColors(
        view: View,
        textView: TextView,
        colors: ColorStateList,
        currentColor: Int
    ) {
        textView.setLinkTextColor(colors)
        if (isMessageNeatTextView(view)) {
            invokeNeatMethod(view, "setLinkTextColor", currentColor)
        }
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(MessageTextColorSettings.KEY_ENABLE, MessageTextColorSettings.DEFAULT_ENABLE)
    }

    private fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun locateAdapterBindMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), CACHE_ADAPTER_BIND)
            ?.takeIf { isAdapterBindCandidate(it) }
            ?.let { return it }
        val matches = findMethodsByStrings("MicroMsg.ChattingDataAdapterV3", "_onBindViewHolder[", "msgInfo")
            .ifEmpty { findMethodsByStrings("MicroMsg.ChattingDataAdapterV3", "holder", "itemView") }
        val method = matches.firstOrNull { isAdapterBindCandidate(it) }
        if (method != null) {
            DexMethodCache.save(methodPrefs, methodCacheKey, CACHE_ADAPTER_BIND, method)
        } else {
            DexMethodCache.clear(methodPrefs, methodCacheKey, CACHE_ADAPTER_BIND)
        }
        return method
    }

    private fun findMethodsByStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(strings.toList())
                        }
                    )
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.getOrElse {
            HLog.e("$TAG 定位聊天消息绑定方法异常: ${it.message}", it)
            emptyList()
        }
    }

    private fun isAdapterBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size == 2 && types[1] == Integer.TYPE && isLikelyViewHolderClass(types[0])
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
        itemMethodCache[adapter.javaClass]?.let { method ->
            KavaReflector.invoke(method, adapter, position)
                ?.takeIf { item -> resolveNativeMessage(item) != null }
                ?.let { return it }
            itemMethodCache.remove(adapter.javaClass, method)
        }
        for (methodName in ITEM_METHOD_NAMES) {
            var current: Class<*>? = adapter.javaClass
            while (current != null && current != Any::class.java) {
                val methods = KavaReflector.declaredMethods(current)
                    .filter {
                        it.parameterTypes.size == 1 &&
                            (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == Int::class.java) &&
                            it.returnType != Void.TYPE &&
                            it.name == methodName
                    }
                for (method in methods) {
                    KavaReflector.invoke(method, adapter, position)
                        ?.takeIf { item -> resolveNativeMessage(item) != null }
                        ?.let { item ->
                            itemMethodCache[adapter.javaClass] = method
                            return item
                        }
                }
                current = current.superclass
            }
        }
        return adapterListItem(adapter, position)
    }

    private fun adapterListItem(adapter: Any, position: Int): Any? {
        itemListFieldCache[adapter.javaClass]?.let { field ->
            listItem(KavaReflector.readField(field, adapter), position)?.let { return it }
            itemListFieldCache.remove(adapter.javaClass, field)
        }
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == "K" || it.name == "I" || it.name == "items" || it.name == "data" || it.name == "list"
            }
            if (field != null) {
                listItem(KavaReflector.readField(field, adapter), position)?.let {
                    itemListFieldCache[adapter.javaClass] = field
                    return it
                }
            }
            current = current.superclass
        }
        return findNestedListItem(adapter, position, Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()), 0)
    }

    private fun listItem(list: Any?, position: Int): Any? {
        if (list == null || position < 0) return null
        if (list is List<*> && position < list.size) return list[position]
        var current: Class<*>? = list.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                java.util.List::class.java.isAssignableFrom(it.type) &&
                    it.name in arrayOf("o", "items", "data", "list")
            }
            if (field != null) {
                listItem(KavaReflector.readField(field, list), position)?.let { return it }
            }
            current = current.superclass
        }
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

    private fun resolveNativeMessage(source: Any): Any? {
        synchronized(nativeMessageCache) {
            nativeMessageCache[source]?.get()?.let { return it }
        }
        val resolved = resolveNativeMessage(
            source,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            0
        )
        if (resolved != null) {
            synchronized(nativeMessageCache) {
                nativeMessageCache[source] = WeakReference(resolved)
            }
        }
        return resolved
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

    private fun messageFromNative(nativeMessage: Any?, talker: String, msgId: Long): WeChatMessage? {
        val source = nativeMessage ?: return null
        val content = nativeMessageContent(source)
        val type = parseInt(readMessageValue(source, "getType", "field_type", "type"))
            ?.takeIf { it > 0 }
            ?: WeChatMessage.inferType(content)
        if (type <= 0) return null
        val sourceTalker = readMessageValue(source, "getTalker", "field_talker", "talker") as? String ?: talker
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
            "",
            "",
            "",
            0,
            "",
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
        return (KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, "getContent"), source) as? String).orEmpty()
    }

    private fun readMessageValue(source: Any, getter: String, fieldName: String, fallbackField: String): Any? {
        KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, getter), source)?.let { return it }
        KavaReflector.readField(source, fieldName)?.let { return it }
        return KavaReflector.readField(source, fallbackField)
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

    private fun parseLong(value: Any?): Long? {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private data class ColorSpec(val startColor: Int, val endColor: Int) {
        val isGradient: Boolean = startColor != endColor
    }

    private data class AppliedTextState(
        val originalTextColors: ColorStateList,
        val originalLinkTextColors: ColorStateList,
        val originalLinkColor: Int,
        val originalShader: Shader?,
        val appliedColor: Int,
        var appliedShader: Shader? = null,
        var shaderUpdatePosted: Boolean = false,
        var shaderRetryCount: Int = 0
    )

    private data class AppliedBindingState(
        val messageKey: Long,
        val spec: ColorSpec,
        val overrideSpanColors: Boolean,
        val targetViews: List<WeakReference<View>>
    )

    private data class TextCandidate(val text: String, val priority: Int)

    private data class TextTarget(val view: View, val textView: TextView)

    private data class TextMatch(val target: TextTarget, val candidate: TextCandidate, val exact: Boolean)

    private companion object {
        const val TAG = "[Hchat:MessageTextColor]"
        val ITEM_METHOD_NAMES = arrayOf("getItem", "K0")
        const val HOST_PACKAGE = "com.tencent.mm"
        const val MESSAGE_BODY_VIEW_RESOURCE = "bkl"
        const val VOIP_TEXT_VIEW_RESOURCE = "bs3"
        const val CHAT_RECORD_APP_MSG_TYPE = 19
        const val CACHE_SCHEMA = "message_text_color_v4"
        const val CACHE_ADAPTER_BIND = "adapter_bind"
        const val MAX_SHADER_RETRIES = 2
        val CHAT_RECORD_TEXT_VIEW_RESOURCES = listOf("bjx", "bj2")
    }
}
