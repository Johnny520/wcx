package h.Hchat.hooks.items.messagebubble

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.R
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.messagetextcolor.MessageTextColorSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class MessageBubbleFeature : BaseFeature() {
    private var renderer: MessageBubbleRenderer? = null

    override fun featureId(): String = ID

    override fun name(): String = "消息气泡"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MessageBubbleSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        renderer = MessageBubbleRenderer(context)
        DexInstallScheduler.schedule(ID, name()) {
            renderer?.install() == true
        }
    }

    companion object {
        const val ID = "message_bubble"
    }
}

private class MessageBubbleRenderer(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MessageBubbleSettings.PREFS_NAME)
    private val textColorPrefs = HchatStorage.preferences(
        context.hostContext(),
        MessageTextColorSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), METHOD_CACHE_PREFS)
    private val holderRootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderClickAreaFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderViewFieldsCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val holderMainContainerMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val holderWithoutMainContainerMethod = ConcurrentHashMap.newKeySet<Class<*>>()
    private val messageNestedFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val nativeMessageClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val bindStates = ThreadLocal<ArrayDeque<BindState>>()
    private val bubbleViewIds by lazy {
        BUBBLE_VIEW_RESOURCES.associateWith { name ->
            context.hostContext().resources.getIdentifier(name, "id", HOST_PACKAGE)
        }.filterValues { it != 0 }
    }
    @Volatile private var adapterBindInstalled = false

    @Synchronized
    fun install(): Boolean {
        if (adapterBindInstalled) return true
        if (prefs.getBoolean(MessageBubbleSettings.KEY_ENABLE, MessageBubbleSettings.DEFAULT_ENABLE)) {
            val useDarkAssets = isDarkMode(context.hostContext()) && prefs.getBoolean(
                MessageBubbleSettings.KEY_SEPARATE_DARK_MODE,
                MessageBubbleSettings.DEFAULT_SEPARATE_DARK_MODE
            )
            MessageBubbleStore.preload(context.hostContext(), useDarkAssets)
        }
        if (bubbleViewIds.isEmpty()) {
            HLog.e("$TAG 未找到聊天气泡资源: ${BUBBLE_VIEW_RESOURCES.joinToString()}")
            return false
        }
        val method = locateAdapterBindMethod() ?: run {
            HLog.e("$TAG 定位聊天消息绑定方法失败")
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val state = captureBindState(param.args)
                    state.root?.let(::restoreBoundBubble)
                    val stack = bindStates.get() ?: ArrayDeque<BindState>().also(bindStates::set)
                    stack.addLast(state)
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = bindStates.get()
                    val state = stack?.pollLast() ?: return
                    if (stack.isEmpty()) bindStates.remove()
                    val boundHolder = state.root?.tag?.takeIf(::isNativeMessageHolder)
                        ?: state.nativeHolder
                    applyBubble(param.args, state.copy(nativeHolder = boundHolder))
                }
            })
            adapterBindInstalled = true
            true
        }.getOrElse {
            HLog.e("$TAG 安装聊天气泡 Hook 失败: ${it.message}", it)
            false
        }
    }

    private fun captureBindState(args: Array<Any?>?): BindState {
        val holder = args?.firstOrNull { it != null && findRootView(it) != null }
        val root = holder?.let(::findRootView)
        val nativeHolder = root?.tag?.takeIf(::isNativeMessageHolder)
        return BindState(root, nativeHolder)
    }

    private fun restoreBoundBubble(root: View) {
        when (val bound = root.getTag(R.id.hchat_message_bubble_target)) {
            is View -> restoreOriginal(bound)
            is Collection<*> -> bound.filterIsInstance<View>().forEach(::restoreOriginal)
        }
        root.setTag(R.id.hchat_message_bubble_target, null)
    }

    private fun applyBubble(args: Array<Any?>?, state: BindState) {
        val root = state.root ?: return
        val message = resolveNativeMessageFromArgs(args)
        val messageType = message?.let {
            parseInt(readMessageValue(it, "getType", "field_type", "type"))
        }
        val messageContent = message?.let {
            readMessageValue(it, "getContent", "field_content", "content")?.toString()
        }.orEmpty()
        val isQuoteMessage = isQuoteMessage(messageType, messageContent)
        val isGroupSolitaireMessage = isGroupSolitaireMessage(messageType, messageContent)
        val isSystemMessage = messageType?.let(::isSystemBubbleMessageType) == true
        val isPatMessage = messageType != null && messageType in PAT_MESSAGE_TYPES
        if (!isSupportedBubbleMessage(
                messageType,
                messageContent,
                isQuoteMessage,
                isGroupSolitaireMessage,
                isSystemMessage
            )
        ) {
            return
        }
        val targets = resolveBubbleTargets(
            root,
            state.nativeHolder,
            messageType,
            isQuoteMessage,
            isGroupSolitaireMessage,
            isSystemMessage,
            isPatMessage,
            messageContent
        )
        if (targets.isEmpty()) return
        if (!prefs.getBoolean(MessageBubbleSettings.KEY_ENABLE, MessageBubbleSettings.DEFAULT_ENABLE)) return

        val messageOutgoing = message?.let {
            parseInt(readMessageValue(it, "getIsSend", "field_isSend", "isSend"))?.let { value -> value != 0 }
        }
        val darkMode = isDarkMode(root.context)
        val separateDark = prefs.getBoolean(
            MessageBubbleSettings.KEY_SEPARATE_DARK_MODE,
            MessageBubbleSettings.DEFAULT_SEPARATE_DARK_MODE
        )
        var kind = resolveBubbleKind(messageType, messageContent)
        val layoutDirection = if (kind == MessageBubbleKind.SYSTEM) {
            null
        } else {
            directionFromLayout(root, targets.first())
        }
        if (kind == MessageBubbleKind.GENERAL &&
            messageType == null &&
            layoutDirection == null &&
            targets.any(::isSystemTextTarget)
        ) {
            kind = MessageBubbleKind.SYSTEM
        }
        val outgoing = if (kind == MessageBubbleKind.SYSTEM) {
            false
        } else {
            messageOutgoing ?: layoutDirection ?: return
        }
        val customTextColorActive = textColorPrefs.getBoolean(
            MessageTextColorSettings.KEY_ENABLE,
            MessageTextColorSettings.DEFAULT_ENABLE
        ) && (messageType?.let(WeChatMessageTypes::isText) == true ||
            messageType?.let(WeChatMessageTypes::isVoice) == true || isGroupSolitaireMessage ||
            isQuoteMessage || isChatRecordMessage(messageType, messageContent) ||
            (messageType != null && messageType in VOIP_MESSAGE_TYPES))
        val autoTextContrast = kind != MessageBubbleKind.GENERAL || !customTextColorActive
        val associatedTextTargets = if (messageType?.let(WeChatMessageTypes::isVoice) == true) {
            resolveVoiceTextTargets(root, state.nativeHolder)
        } else {
            emptyList()
        }
        var associatedTextBound = false
        val applied = ArrayList<View>(targets.size)
        targets.forEach { target ->
            val choice = resolveDrawable(target.context, kind, outgoing, darkMode, separateDark)
                ?: return@forEach
            val additionalTextTargets = if (!associatedTextBound) associatedTextTargets else emptyList()
            applyDrawable(
                target,
                choice.drawable,
                choice.slot,
                autoTextContrast,
                additionalTextTargets
            )
            if (additionalTextTargets.isNotEmpty()) associatedTextBound = true
            applied += target
        }
        if (applied.isNotEmpty()) root.setTag(R.id.hchat_message_bubble_target, applied)
    }

    private fun resolveDrawable(
        context: Context,
        kind: MessageBubbleKind,
        outgoing: Boolean,
        darkMode: Boolean,
        separateDark: Boolean
    ): DrawableChoice? {
        val useDark = darkMode && separateDark
        val candidates = ArrayList<MessageBubbleSlot>()
        fun addCandidate(candidateKind: MessageBubbleKind, candidateDark: Boolean) {
            val slot = MessageBubbleSlot.resolve(candidateKind, outgoing, candidateDark)
            if (slot !in candidates) candidates += slot
        }
        addCandidate(kind, useDark)
        if (useDark) addCandidate(kind, false)
        candidates.forEach { slot ->
            MessageBubbleStore.createDrawable(context, slot)?.let { return DrawableChoice(it, slot) }
        }
        return null
    }

    private fun resolveBubbleKind(messageType: Int?, messageContent: String): MessageBubbleKind {
        if (messageType != null && isSystemBubbleMessageType(messageType)) {
            return MessageBubbleKind.SYSTEM
        }
        val content = messageContent.lowercase()
        val isAppMessage = messageType?.let { WeChatMessageTypes.isApp(it) } == true ||
            content.contains("<appmsg") || content.contains("<wcpayinfo")
        if (!isAppMessage) {
            return MessageBubbleKind.GENERAL
        }
        if (content.contains("receivehongbao") ||
            content.contains("wxhb_personalreceive") ||
            content.contains("hongbao") ||
            content.contains("/hongbao/") ||
            RED_PACKET_TYPE_PATTERN.containsMatchIn(content)
        ) {
            return MessageBubbleKind.RED_PACKET
        }
        if ((content.contains("<wcpayinfo") &&
                (content.contains("<transferid>") || content.contains("<transcationid>") ||
                    content.contains("<transactionid>") || content.contains("transfer_id=") ||
                    content.contains("trans_id=") || content.contains("transferoperation"))) ||
            TRANSFER_TYPE_PATTERN.containsMatchIn(content)
        ) {
            return MessageBubbleKind.TRANSFER
        }
        return MessageBubbleKind.GENERAL
    }

    private fun isSupportedBubbleMessage(
        messageType: Int?,
        messageContent: String,
        isQuoteMessage: Boolean,
        isGroupSolitaireMessage: Boolean,
        isSystemMessage: Boolean
    ): Boolean {
        if (messageType == null) return false
        if (WeChatMessageTypes.isText(messageType) ||
            WeChatMessageTypes.isVoice(messageType) ||
            isSystemMessage ||
            messageType in VOIP_MESSAGE_TYPES
        ) {
            return true
        }
        if (isGroupSolitaireMessage) return true
        if (isQuoteMessage && !isChatRecordMessage(messageType, messageContent)) return true
        return when (resolveBubbleKind(messageType, messageContent)) {
            MessageBubbleKind.RED_PACKET,
            MessageBubbleKind.TRANSFER -> true
            else -> false
        }
    }

    private fun isSystemBubbleMessageType(messageType: Int): Boolean {
        return WeChatMessageTypes.isSystem(messageType) ||
            messageType in EXTRA_SYSTEM_BUBBLE_MESSAGE_TYPES
    }

    private fun isQuoteMessage(messageType: Int?, messageContent: String): Boolean {
        val isAppMessage = messageType?.let { WeChatMessageTypes.isApp(it) } == true ||
            messageContent.contains("<appmsg", ignoreCase = true)
        if (!isAppMessage) return false
        return QUOTE_TYPE_PATTERN.containsMatchIn(messageContent) ||
            messageContent.contains("<refermsg", ignoreCase = true)
    }

    private fun isChatRecordMessage(messageType: Int?, messageContent: String): Boolean {
        val isAppMessage = messageType?.let { WeChatMessageTypes.isApp(it) } == true ||
            messageContent.contains("<appmsg", ignoreCase = true)
        return isAppMessage && CHAT_RECORD_TYPE_PATTERN.containsMatchIn(messageContent)
    }

    private fun isGroupSolitaireMessage(messageType: Int?, messageContent: String): Boolean {
        val isAppMessage = messageType?.let { WeChatMessageTypes.isApp(it) } == true ||
            messageContent.contains("<appmsg", ignoreCase = true)
        return isAppMessage && (
            GROUP_SOLITAIRE_TYPE_PATTERN.containsMatchIn(messageContent) ||
                messageContent.contains("solitaire", ignoreCase = true)
        )
    }

    private fun resolveBubbleTargets(
        root: View,
        holder: Any?,
        messageType: Int?,
        isQuoteMessage: Boolean,
        isGroupSolitaireMessage: Boolean,
        isSystemMessage: Boolean,
        isPatMessage: Boolean,
        messageContent: String
    ): List<View> {
        if (isQuoteMessage) {
            return resolveQuoteBodyTarget(root, messageContent)?.let(::listOf).orEmpty()
        }
        if (isSystemMessage) {
            return resolveSystemBubbleTargets(root, holder, isPatMessage)
        }
        if (isGroupSolitaireMessage) {
            val textBubbleId = bubbleViewIds[TEXT_BUBBLE_VIEW_RESOURCE]
            if (textBubbleId != null) {
                (holderViewsById(holder)[textBubbleId] ?: root.findViewById<View>(textBubbleId))
                    ?.takeIf { isBubbleCandidate(it, root) }
                    ?.let { return listOf(it) }
            }
        }
        val clickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache)
            ?.takeIf { isBubbleCandidate(it, root) }
        if (clickArea != null) return listOf(clickArea)

        val holderViews = holderViewsById(holder)
        val specialized = LinkedHashSet<View>()
        SPECIALIZED_BUBBLE_VIEW_RESOURCES.forEach { name ->
            val id = bubbleViewIds[name] ?: return@forEach
            if (name == TEXT_BUBBLE_VIEW_RESOURCE && messageType == VOICE_MESSAGE_TYPE) return@forEach
            (holderViews[id] ?: root.findViewById<View>(id))
                ?.takeIf { isBubbleCandidate(it, root) }
                ?.let(specialized::add)
        }
        if (specialized.isNotEmpty()) return specialized.toList()

        holderMainContainerView(holder)
            ?.takeIf { isBubbleCandidate(it, root) }
            ?.let { return listOf(it) }

        val defaultId = bubbleViewIds[DEFAULT_BUBBLE_VIEW_RESOURCE] ?: return emptyList()
        return root.findViewById<View>(defaultId)
            ?.takeIf { isBubbleCandidate(it, root) }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun resolveSystemBubbleTargets(root: View, holder: Any?, isPatMessage: Boolean): List<View> {
        if (isPatMessage) {
            val patTargets = resolvePatBubbleTargets(root)
            if (patTargets.isNotEmpty()) return patTargets
        }
        val textBubbleId = bubbleViewIds[TEXT_BUBBLE_VIEW_RESOURCE]
        if (textBubbleId != null) {
            holderViewsById(holder)[textBubbleId]
                ?.takeIf { isViewWithinRoot(it, root) }
                ?.let { return listOf(it) }
            root.findViewById<View>(textBubbleId)
                ?.takeIf { isViewWithinRoot(it, root) }
                ?.let { return listOf(it) }
        }
        return findFoldSystemBubbleTarget(root, root)?.let(::listOf).orEmpty()
    }

    private fun resolvePatBubbleTargets(root: View): List<View> {
        val container = bubbleViewIds[PAT_BUBBLE_CONTAINER_RESOURCE]
            ?.let { root.findViewById<View>(it) }
            ?.takeIf { isViewWithinRoot(it, root) }
            ?: root
        val targets = ArrayList<View>()
        collectPatBubbleTargets(container, root, targets)
        return targets
    }

    private fun collectPatBubbleTargets(view: View, root: View, out: MutableList<View>) {
        if (view.javaClass.name == PAT_BUBBLE_TEXT_VIEW_CLASS &&
            view.background != null &&
            isViewWithinRoot(view, root)
        ) {
            out += view
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectPatBubbleTargets(view.getChildAt(index), root, out)
            }
        }
    }

    private fun findFoldSystemBubbleTarget(view: View, root: View): View? {
        if (view.javaClass.name == FOLD_SYSTEM_VIEW_CLASS) {
            if (view is TextView && isViewWithinRoot(view, root)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    if (child is TextView && isViewWithinRoot(child, root)) return child
                }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findFoldSystemBubbleTarget(view.getChildAt(index), root)?.let { return it }
            }
        }
        return null
    }

    private fun resolveQuoteBodyTarget(root: View, messageContent: String): View? {
        val textBubbleId = bubbleViewIds[TEXT_BUBBLE_VIEW_RESOURCE] ?: return null
        val candidates = ArrayList<View>()
        collectViewsById(root, textBubbleId, candidates)
        val valid = candidates.filter { isBubbleCandidate(it, root) }
        if (valid.isEmpty()) return null

        val title = normalizeVisibleText(WeChatMessage.xmlTag(messageContent, "title"))
        if (title.isNotEmpty()) {
            valid.firstOrNull { view ->
                val text = normalizeVisibleText(readVisibleText(view))
                text == title || textContainsMessage(text, title)
            }?.let { return it }
        }
        return valid.first()
    }

    private fun collectViewsById(view: View, targetId: Int, out: MutableList<View>) {
        if (view.id == targetId) out += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectViewsById(view.getChildAt(index), targetId, out)
            }
        }
    }

    private fun resolveVoiceTextTargets(root: View, holder: Any?): List<TextTarget> {
        val textViewId = bubbleViewIds[TEXT_BUBBLE_VIEW_RESOURCE] ?: return emptyList()
        val view = holderViewsById(holder)[textViewId] ?: root.findViewById(textViewId) ?: return emptyList()
        if (!isViewWithinRoot(view, root)) return emptyList()
        return textTarget(view)?.let(::listOf).orEmpty()
    }

    private fun readVisibleText(view: View): String {
        val textView = KavaReflector.invokeMethod(view, "getWrappedTextView") as? TextView
            ?: view as? TextView
        KavaReflector.invokeMethod(view, "a")
            ?.let { it as? CharSequence }
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        KavaReflector.readField(view, "x")
            ?.let { it as? CharSequence }
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return view.contentDescription?.toString().orEmpty()
            .ifBlank { textView?.text?.toString().orEmpty() }
    }

    private fun normalizeVisibleText(value: String): String {
        var result = value
        repeat(2) {
            result = result
                .replace("&lt;", "<", ignoreCase = true)
                .replace("&gt;", ">", ignoreCase = true)
                .replace("&quot;", "\"", ignoreCase = true)
                .replace("&apos;", "'", ignoreCase = true)
                .replace("&amp;", "&", ignoreCase = true)
        }
        return buildString(result.length) {
            for (char in result) {
                when (char) {
                    '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF', '\uFFFC' -> Unit
                    '\u00A0', '\u2007', '\u202F' -> append(' ')
                    else -> append(char)
                }
            }
        }
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    private fun textContainsMessage(text: String, target: String): Boolean {
        if (target.length < 2 || text.length > target.length * 3 + 12) return false
        return text.contains(target)
    }

    private fun isBubbleCandidate(view: View, root: View): Boolean {
        return view.background != null && isViewWithinRoot(view, root)
    }

    private fun isSystemTextTarget(view: View): Boolean {
        return view.id == bubbleViewIds[TEXT_BUBBLE_VIEW_RESOURCE]
    }

    private fun holderMainContainerView(holder: Any?): View? {
        holder ?: return null
        val holderClass = holder.javaClass
        holderMainContainerMethodCache[holderClass]?.let {
            return KavaReflector.invoke(it, holder) as? View
        }
        if (holderWithoutMainContainerMethod.contains(holderClass)) return null
        var current: Class<*>? = holderClass
        while (current != null && current != Any::class.java) {
            val method = KavaReflector.declaredMethods(current).firstOrNull {
                it.name == "getMainContainerView" &&
                    it.parameterTypes.isEmpty() &&
                    View::class.java.isAssignableFrom(it.returnType)
            }
            if (method != null) {
                holderMainContainerMethodCache[holderClass] = method
                return KavaReflector.invoke(method, holder) as? View
            }
            current = current.superclass
        }
        holderWithoutMainContainerMethod += holderClass
        return null
    }

    private fun findHolderView(
        holder: Any?,
        fieldName: String,
        cache: ConcurrentHashMap<Class<*>, Field>
    ): View? {
        holder ?: return null
        cache[holder.javaClass]?.let { return KavaReflector.readField(it, holder) as? View }
        var current: Class<*>? = holder.javaClass
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.name == fieldName && View::class.java.isAssignableFrom(it.type)
            }
            if (field != null) {
                cache[holder.javaClass] = field
                return KavaReflector.readField(field, holder) as? View
            }
            current = current.superclass
        }
        return null
    }

    private fun holderViewsById(holder: Any?): Map<Int, View> {
        holder ?: return emptyMap()
        val fields = holderViewFieldsCache[holder.javaClass] ?: run {
            val result = ArrayList<Field>()
            var current: Class<*>? = holder.javaClass
            while (current != null && current != Any::class.java) {
                result += KavaReflector.declaredFields(current).filter { field ->
                    View::class.java.isAssignableFrom(field.type)
                }
                current = current.superclass
            }
            result.also { holderViewFieldsCache.putIfAbsent(holder.javaClass, it) }
        }
        return buildMap {
            fields.forEach { field ->
                val view = KavaReflector.readField(field, holder) as? View ?: return@forEach
                if (view.id != View.NO_ID) putIfAbsent(view.id, view)
            }
        }
    }

    private fun isNativeMessageHolder(value: Any): Boolean {
        return value.javaClass.name.startsWith(CHATTING_VIEW_ITEMS_PACKAGE)
    }

    private fun isViewWithinRoot(view: View, root: View): Boolean {
        if (view === root) return true
        var current = view.parent
        var depth = 0
        while (current is View && depth < 16) {
            if (current === root) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun applyDrawable(
        view: View,
        drawable: Drawable,
        slot: MessageBubbleSlot,
        autoTextContrast: Boolean,
        additionalTextTargets: List<TextTarget>
    ) {
        val recommendedTextColor = if (autoTextContrast) {
            MessageBubbleStore.recommendedTextColor(view.context, slot)
        } else {
            null
        }
        val textColorStates = recommendedTextColor?.let { color ->
            val targets = LinkedHashMap<TextView, TextTarget>()
            collectTextTargets(view).forEach { target -> targets.putIfAbsent(target.textView, target) }
            additionalTextTargets.forEach { target -> targets.putIfAbsent(target.textView, target) }
            targets.values.mapNotNull { target ->
                createTextColorState(target, color)
            }
        }.orEmpty()
        val state = OriginalState(
            background = view.background,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            textColorStates = textColorStates
        )
        view.setTag(R.id.hchat_message_bubble_original, state)
        view.background = drawable
        val textBubbleId = bubbleViewIds[TEXT_BUBBLE_VIEW_RESOURCE]
        if (textBubbleId != null && view.id == textBubbleId) {
            val drawablePadding = Rect()
            val hasHorizontalPadding = drawable.getPadding(drawablePadding) &&
                drawablePadding.left + drawablePadding.right > 0
            val left = if (hasHorizontalPadding) {
                drawablePadding.left
            } else {
                (state.paddingLeft + state.paddingRight) / 2
            }
            val right = if (hasHorizontalPadding) {
                drawablePadding.right
            } else {
                state.paddingLeft + state.paddingRight - left
            }
            view.setPadding(left, state.paddingTop, right, state.paddingBottom)
        } else {
            view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
        }
        recommendedTextColor?.let { color ->
            textColorStates.forEach { textState -> applyTextContrast(textState, color) }
        }
        view.invalidate()
    }

    private fun collectTextTargets(root: View): List<TextTarget> {
        val targets = LinkedHashMap<TextView, View>()
        fun collect(view: View) {
            val target = textTarget(view)
            if (target != null && (view === root || view.background == null)) {
                targets.putIfAbsent(target.textView, target.owner)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(root)
        return targets.map { (textView, owner) -> TextTarget(owner, textView) }
    }

    private fun textTarget(view: View): TextTarget? {
        val textView = when {
            isMessageNeatTextView(view) -> {
                KavaReflector.invokeMethod(view, "getWrappedTextView") as? TextView
                    ?: view as? TextView
            }
            view is TextView -> view
            else -> null
        } ?: return null
        return TextTarget(view, textView)
    }

    private fun createTextColorState(target: TextTarget, recommendedColor: Int): TextColorState? {
        val textColorNeedsChange = hasInsufficientContrast(target.textView.currentTextColor, recommendedColor)
        val linkColorNeedsChange = hasInsufficientContrast(
            target.textView.linkTextColors.defaultColor,
            recommendedColor
        )
        if (!textColorNeedsChange && !linkColorNeedsChange) return null
        return TextColorState(
            target = target,
            textColors = target.textView.textColors,
            linkTextColors = target.textView.linkTextColors,
            changeTextColor = textColorNeedsChange,
            changeLinkColor = linkColorNeedsChange,
            appliedColor = recommendedColor
        )
    }

    private fun applyTextContrast(state: TextColorState, color: Int) {
        val target = state.target
        if (state.changeTextColor) setTargetTextColor(target, color)
        if (state.changeLinkColor) target.textView.setLinkTextColor(color)
        target.textView.invalidate()
        target.owner.invalidate()
    }

    private fun restoreTextColor(state: TextColorState) {
        val target = state.target
        val messageTextColorActive = target.owner.getTag(R.id.hchat_message_text_color_applied) == true ||
            target.textView.getTag(R.id.hchat_message_text_color_applied) == true
        if (state.changeTextColor &&
            !messageTextColorActive &&
            target.textView.currentTextColor == state.appliedColor
        ) {
            if (isMessageNeatTextView(target.owner)) {
                KavaReflector.invokeMethod(target.owner, "setTextColor", state.textColors.defaultColor)
            }
            target.textView.setTextColor(state.textColors)
        }
        if (state.changeLinkColor &&
            !messageTextColorActive &&
            target.textView.linkTextColors.defaultColor == state.appliedColor
        ) {
            target.textView.setLinkTextColor(state.linkTextColors)
        }
        target.textView.invalidate()
        target.owner.invalidate()
    }

    private fun setTargetTextColor(target: TextTarget, color: Int) {
        if (isMessageNeatTextView(target.owner)) {
            KavaReflector.invokeMethod(target.owner, "setTextColor", color)
        }
        target.textView.setTextColor(color)
    }

    private fun isMessageNeatTextView(view: View): Boolean {
        var current: Class<*>? = view.javaClass
        while (current != null && current != Any::class.java) {
            val name = current.name
            if (name == "com.tencent.mm.ui.widget.MMNeat7extView" ||
                name == "com.tencent.neattextview.textview.view.NeatTextView" ||
                name.contains("NeatTextView")
            ) {
                return true
            }
            current = current.superclass
        }
        return false
    }

    private fun hasInsufficientContrast(textColor: Int, recommendedColor: Int): Boolean {
        val backgroundColor = if (recommendedColor == Color.BLACK) Color.WHITE else Color.BLACK
        val opaqueTextColor = compositeOver(textColor, backgroundColor)
        val lighter = maxOf(relativeLuminance(opaqueTextColor), relativeLuminance(backgroundColor))
        val darker = minOf(relativeLuminance(opaqueTextColor), relativeLuminance(backgroundColor))
        return (lighter + 0.05) / (darker + 0.05) < MIN_TEXT_CONTRAST
    }

    private fun compositeOver(foreground: Int, background: Int): Int {
        val alpha = Color.alpha(foreground) / 255.0
        if (alpha >= 1.0) return foreground
        fun channel(foregroundChannel: Int, backgroundChannel: Int): Int {
            return (foregroundChannel * alpha + backgroundChannel * (1.0 - alpha)).toInt()
        }
        return Color.rgb(
            channel(Color.red(foreground), Color.red(background)),
            channel(Color.green(foreground), Color.green(background)),
            channel(Color.blue(foreground), Color.blue(background))
        )
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.04045) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun restoreOriginal(view: View) {
        val state = view.getTag(R.id.hchat_message_bubble_original) as? OriginalState ?: return
        view.background = state.background
        view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
        state.textColorStates.forEach(::restoreTextColor)
        view.setTag(R.id.hchat_message_bubble_original, null)
        view.invalidate()
    }

    private fun resolveNativeMessageFromArgs(args: Array<Any?>?): Any? {
        args ?: return null
        for (index in 1 until args.size) {
            resolveNativeMessage(args[index])?.let { return it }
        }
        return null
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        return resolveNativeMessage(
            source,
            Collections.newSetFromMap(WeakHashMap<Any, Boolean>()),
            depth = 0
        )
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 4 || !visited.add(source)) return null
        val className = source.javaClass.name
        if (isLikelyNativeMessage(source)) return source
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View || source is ViewGroup) return null
        if (source is Collection<*>) {
            for (item in source) {
                resolveNativeMessage(item, visited, depth + 1)?.let { return it }
            }
            return null
        }
        for (field in messageNestedFields(source.javaClass)) {
            val value = KavaReflector.readField(field, source) ?: continue
            resolveNativeMessage(value, visited, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isLikelyNativeMessage(value: Any): Boolean {
        val clazz = value.javaClass
        nativeMessageClassCache[clazz]?.let { return it }
        val result = KavaReflector.findFieldRecursive(clazz, "field_msgId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_msgSvrId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_type") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_isSend") != null
        nativeMessageClassCache.putIfAbsent(clazz, result)
        return result
    }

    private fun messageNestedFields(type: Class<*>): List<Field> {
        return messageNestedFieldCache[type] ?: run {
            val fields = ArrayList<Field>()
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                fields += KavaReflector.declaredFields(current).filter { field ->
                    val fieldType = field.type
                    !fieldType.isPrimitive &&
                        !fieldType.isArray &&
                        fieldType != String::class.java &&
                        !Number::class.java.isAssignableFrom(fieldType)
                }
                current = current.superclass
            }
            fields.also { messageNestedFieldCache.putIfAbsent(type, it) }
        }
    }

    private fun readMessageValue(source: Any, getter: String, fieldName: String, fallbackField: String): Any? {
        KavaReflector.invoke(KavaReflector.findMethod(source.javaClass, getter), source)?.let { return it }
        KavaReflector.readField(source, fieldName)?.let { return it }
        return KavaReflector.readField(source, fallbackField)
    }

    private fun directionFromLayout(root: View, target: View): Boolean? {
        val rootWidth = root.width.takeIf { it > 0 } ?: root.measuredWidth
        val targetWidth = target.width.takeIf { it > 0 } ?: target.measuredWidth
        if (rootWidth <= 0 || targetWidth <= 0) return null
        val rootLocation = IntArray(2)
        val targetLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        target.getLocationOnScreen(targetLocation)
        val rootCenter = rootLocation[0] + rootWidth / 2f
        val targetCenter = targetLocation[0] + targetWidth / 2f
        if (kotlin.math.abs(targetCenter - rootCenter) < rootWidth * 0.08f) return null
        return targetCenter > rootCenter
    }

    private fun parseInt(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
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

    private fun locateAdapterBindMethod(): Method? {
        val cacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_ADAPTER_BIND)
            ?.takeIf(::isMessageViewBindCandidate)
            ?.let { return it }
        val matches = findMethodsByStrings("MicroMsg.MvvmChattingItem", "[onBindView]")
        val method = matches.firstOrNull(::isMessageViewBindCandidate)
        if (method != null) {
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_ADAPTER_BIND, method)
        } else {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_ADAPTER_BIND)
        }
        return method
    }

    private fun findMethodsByStrings(vararg strings: String): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingStrings(strings.toList()) })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }
        }.getOrElse {
            HLog.e("$TAG 定位聊天消息绑定方法异常: ${it.message}", it)
            emptyList()
        }
    }

    private fun isMessageViewBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size >= 3 &&
            types.any { it == Integer.TYPE || it == java.lang.Integer::class.java } &&
            types.any { findRootField(it) != null }
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private fun isDarkMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private data class OriginalState(
        val background: Drawable?,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val textColorStates: List<TextColorState>
    )

    private data class TextTarget(
        val owner: View,
        val textView: TextView
    )

    private data class TextColorState(
        val target: TextTarget,
        val textColors: ColorStateList,
        val linkTextColors: ColorStateList,
        val changeTextColor: Boolean,
        val changeLinkColor: Boolean,
        val appliedColor: Int
    )

    private data class DrawableChoice(
        val drawable: Drawable,
        val slot: MessageBubbleSlot
    )

    private data class BindState(
        val root: View?,
        val nativeHolder: Any?
    )

    private companion object {
        const val TAG = "[Hchat:MessageBubble]"
        const val HOST_PACKAGE = "com.tencent.mm"
        const val CHATTING_VIEW_ITEMS_PACKAGE = "com.tencent.mm.ui.chatting.viewitems."
        const val FOLD_SYSTEM_VIEW_CLASS =
            "com.tencent.mm.ui.chatting.viewitems.foldItem.ChattingItemFoldSys\$ExpandTextView"
        const val DEFAULT_BUBBLE_VIEW_RESOURCE = "bkg"
        const val TEXT_BUBBLE_VIEW_RESOURCE = "bkl"
        const val PAT_BUBBLE_CONTAINER_RESOURCE = "kpw"
        const val PAT_BUBBLE_TEXT_VIEW_CLASS = "com.tencent.mm.ui.widget.MMNeat7extView"
        const val METHOD_CACHE_PREFS = "Hchat_message_bubble_method_cache"
        const val CACHE_SCHEMA = "message_bubble_v3"
        const val CACHE_ADAPTER_BIND = "adapter_bind"
        const val VOICE_MESSAGE_TYPE = 34
        const val MIN_TEXT_CONTRAST = 4.5
        val VOIP_MESSAGE_TYPES = setOf(50, 1000052, 1000053)
        val RED_PACKET_TYPE_PATTERN = Regex("<type>\\s*2001\\s*</type>")
        val TRANSFER_TYPE_PATTERN = Regex("<type>\\s*(2000|2011)\\s*</type>")
        val QUOTE_TYPE_PATTERN = Regex("<type>\\s*57\\s*</type>", RegexOption.IGNORE_CASE)
        val CHAT_RECORD_TYPE_PATTERN = Regex("<type>\\s*19\\s*</type>", RegexOption.IGNORE_CASE)
        val GROUP_SOLITAIRE_TYPE_PATTERN = Regex("<type>\\s*53\\s*</type>", RegexOption.IGNORE_CASE)
        val EXTRA_SYSTEM_BUBBLE_MESSAGE_TYPES = setOf(
            64,
            570425393,
            603979825,
            889192497,
            922746929,
            -1879048191,
            1077936177
        )
        val PAT_MESSAGE_TYPES = setOf(889192497, 922746929)
        val SPECIALIZED_BUBBLE_VIEW_RESOURCES = listOf(
            TEXT_BUBBLE_VIEW_RESOURCE,
            "brp",
            "brl",
            "bro",
            "bs0",
            "bs2"
        )
        val BUBBLE_VIEW_RESOURCES = listOf(
            DEFAULT_BUBBLE_VIEW_RESOURCE,
            PAT_BUBBLE_CONTAINER_RESOURCE
        ) + SPECIALIZED_BUBBLE_VIEW_RESOURCES
    }
}
