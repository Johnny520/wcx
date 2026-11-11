package h.Hchat.hooks.items.realtail

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.chatname.ChatNameDecorationLayout
import h.Hchat.hooks.items.groupnicknamecolor.GroupNicknameColorStore
import h.Hchat.hooks.items.membertitle.MemberTitleStore
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class RealNameTailRenderer(
    private val context: FeatureContext,
    private val store: RealNameTailStore,
    private val nicknameColorStore: GroupNicknameColorStore,
    private val scheduler: RealNameTailScheduler,
    private val logger: (String, Throwable?) -> Unit
) {
    private val msgSenderCache = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val boundViews = Collections.synchronizedMap(WeakHashMap<TextView, String>())
    private val holderTextFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val likelyMsgCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val inSetText = ThreadLocal<Boolean>()
    private val methodCachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_real_tail_method_cache")
    @Volatile private var installed = false
    var profilePrefetcher: ContactProfilePrefetcher? = null

    fun install(): Boolean {
        return installUsernameBindHook()
    }

    private fun installUsernameBindHook(): Boolean {
        if (installed) return true
        val method = locateUsernameBindMethod() ?: return false
        return try {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleUsernameBind(param)
                }
            })
            installed = true
            true
        } catch (e: Throwable) {
            logger("实名尾字Hook安装失败", e)
            false
        }
    }

    fun applyForSender(wxid: String) {
        val id = wxid.trim()
        if (!RealNameTailStore.isValidWxid(id)) return
        WeChatApis.tasks()?.runOnMain {
            val snapshot = synchronized(boundViews) { boundViews.entries.toList() }
            snapshot.forEach { (view, sender) ->
                if (sender == id) {
                    applyConfiguredDecorations(view, id)
                }
            }
        }
    }

    private fun handleUsernameBind(param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val holder = args.firstOrNull() ?: return
        val tv = findUserTextView(holder) ?: return
        val tailEnabled = store.isEnabled()
        val nicknameColorEnabled = nicknameColorStore.isEnabled()
        if (!tailEnabled && !nicknameColorEnabled) {
            clearBoundDecorations(tv)
            return
        }
        val roomId = currentRoomId()
        if (!isRoom(roomId)) {
            clearBoundDecorations(tv)
            return
        }
        var msg: Any? = null
        var raw = ""
        val stringCandidates = mutableListOf<String>()
        args.forEach {
            if (it is String) {
                if (raw.isEmpty()) raw = it
                val value = it.trim()
                if (RealNameTailStore.isValidWxid(value)) stringCandidates += value
            }
            if (it != null && isLikelyMessageClass(it.javaClass)) msg = it
        }
        if (isOutgoingMessage(msg)) {
            clearBoundDecorations(tv)
            return
        }
        val sender = resolveBindSender(msg, raw, stringCandidates)
        if (!RealNameTailStore.isValidWxid(sender) || isSelf(sender)) {
            clearBoundDecorations(tv)
            return
        }
        boundViews[tv] = sender
        if (tailEnabled) {
            val rawTail = store.cachedTail(sender)
            if (rawTail.isNotEmpty()) {
                if (!applyTail(tv, sender, rawTail)) scheduleDelayedApply(tv, sender, rawTail)
            } else {
                hideTail(tv)
                if (store.visibleQueryEnabled()) {
                    scheduler.onVisible(roomId, sender)
                }
            }
        } else {
            hideTail(tv)
        }
        if (nicknameColorEnabled) {
            applyNicknameColor(tv)
        } else {
            hideNicknameColor(tv)
        }
    }

    private fun applyConfiguredDecorations(tv: TextView, sender: String) {
        if (store.isEnabled()) {
            applyTail(tv, sender)
        } else {
            hideTail(tv)
        }
        if (nicknameColorStore.isEnabled()) {
            applyNicknameColor(tv)
        } else {
            hideNicknameColor(tv)
        }
    }

    private fun applyNicknameColor(tv: TextView): Boolean {
        if (tv.visibility != View.VISIBLE) return false
        val row = ChatNameDecorationLayout.ensure(tv)
        ChatNameDecorationLayout.showBaseNameStyle(
            row,
            nicknameColorStore.color(),
            nicknameColorStore.weight()
        )
        return true
    }

    private fun hideNicknameColor(tv: TextView) {
        ChatNameDecorationLayout.current(tv)?.let { ChatNameDecorationLayout.hideBaseNameStyle(it) }
    }

    private fun applyTail(tv: TextView, sender: String): Boolean {
        return applyTail(tv, sender, store.cachedTail(sender))
    }

    private fun applyTail(tv: TextView, sender: String, rawTail: String): Boolean {
        if (!RealNameTailStore.isValidWxid(sender)) return false
        boundViews[tv] = sender
        val tail = store.displayTail(sender)
        if (tail.isEmpty()) {
            hideTail(tv)
            return false
        }
        val nativeHidden = tv.visibility != View.VISIBLE
        if (nativeHidden) tv.visibility = View.VISIBLE
        val row = ChatNameDecorationLayout.ensure(tv)
        val cur = ChatNameDecorationLayout.displayNameText(tv)
        val sb = SpannableStringBuilder(if (nativeHidden) "" else cur)
        removeKnownSuffixes(sb, tail)
        removeSuffixForRawTail(sb, rawTail)
        removeAppendedTailSuffixes(sb)
        flatten(sb)
        val name = normalizedDisplayName(sb.toString(), sender)
        if (name.startsWith("微信用户(") || name.startsWith("微信用户（")) {
            ChatNameDecorationLayout.hideTail(row)
            return false
        }
        if (sb.toString() != name) {
            sb.clear()
            sb.append(name)
        }
        if (inSetText.get() == true) {
            return false
        }
        return try {
            inSetText.set(true)
            val suffixParts = displaySuffixParts(sender, tail)
            ChatNameDecorationLayout.setBaseName(row, sb)
            fitNameViewForTail(tv)
            ChatNameDecorationLayout.showTail(row, buildColoredSuffix(suffixParts))
            true
        } finally {
            inSetText.set(false)
        }
    }

    private fun displaySuffixParts(sender: String, tail: String): List<SuffixPart> {
        val parts = mutableListOf(SuffixPart(tail, store.tailColor(), store.tailWeight()))
        if (store.showGenderEnabled()) {
            val gender = genderText(sender)
            gender.takeIf { it.isNotEmpty() }?.let { parts += SuffixPart(it, store.genderColor(), store.genderWeight()) }
            if (gender.isEmpty()) profilePrefetcher?.requestIfMissing(sender)
        }
        if (store.showRegionEnabled()) {
            val region = regionText(sender)
            region.takeIf { it.isNotEmpty() }?.let { parts += SuffixPart(it, store.regionColor(), store.regionWeight()) }
            if (region.isEmpty()) profilePrefetcher?.requestIfMissing(sender)
        }
        return parts
    }

    private fun buildColoredSuffix(parts: List<SuffixPart>): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val bracketColor = store.bracketColor()
        val bracketWeight = store.bracketWeight()
        val openingStart = sb.length
        sb.append("(")
        sb.setSpan(
            ChatNameDecorationLayout.StyledTextSpan(bracketColor, bracketWeight),
            openingStart,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        parts.forEachIndexed { index, part ->
            if (index > 0) sb.append(" ")
            val start = sb.length
            sb.append(part.text)
            sb.setSpan(
                ChatNameDecorationLayout.StyledTextSpan(part.color, part.weight),
                start,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val closingStart = sb.length
        sb.append(")")
        sb.setSpan(
            ChatNameDecorationLayout.StyledTextSpan(bracketColor, bracketWeight),
            closingStart,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    private fun normalizedDisplayName(value: String, sender: String): String {
        val name = value.trim()
        if (name.isNotEmpty()) return name
        return WeChatApis.contact().contacts()?.getDisplayName(sender)
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() && !it.startsWith("微信用户(") && !it.startsWith("微信用户（") }
            ?: "\u00A0"
    }

    private data class SuffixPart(val text: String, val color: MemberTitleStore.ColorSpec?, val weight: Int)

    private fun genderText(sender: String): String {
        val gender = WeChatApis.contact().contacts()?.getGender(sender) ?: 0
        return store.genderText(gender)
    }

    private fun regionText(sender: String): String {
        return WeChatApis.contact().contacts()?.getRegion(sender)
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
    }

    private fun fitNameViewForTail(tv: TextView) {
        runCatching {
            tv.setHorizontallyScrolling(false)
            tv.ellipsize = null
            tv.setSingleLine(false)
            tv.maxLines = Int.MAX_VALUE
        }
    }

    private fun scheduleDelayedApply(tv: TextView, sender: String, rawTail: String) {
        boundViews[tv] = sender
        WeChatApis.tasks()?.runOnMainDelayed("real_tail_apply_${System.identityHashCode(tv)}", 120L) {
            if (boundViews[tv] == sender) {
                applyTail(tv, sender, rawTail)
                if (nicknameColorStore.isEnabled()) applyNicknameColor(tv)
            }
        }
    }

    private fun clearBoundDecorations(tv: TextView) {
        boundViews.remove(tv)
        hideTail(tv)
        hideNicknameColor(tv)
    }

    private fun hideTail(tv: TextView) {
        ChatNameDecorationLayout.current(tv)?.let { ChatNameDecorationLayout.hideTail(it) }
    }

    private fun removeKnownSuffixes(sb: SpannableStringBuilder, tail: String) {
        repeat(4) {
            val value = sb.toString()
            val start = maxOf(value.lastIndexOf('('), value.lastIndexOf('（'))
            if (start < 0 || start >= value.length - 2) return
            val close = if (value[start] == '（') '）' else ')'
            if (value.last() != close) return
            val inner = value.substring(start + 1, value.length - 1)
            if (isKnownDecoratedSuffix(inner, tail)) {
                sb.delete(start, sb.length)
            } else {
                return
            }
        }
    }

    private fun isKnownDecoratedSuffix(inner: String, tail: String): Boolean {
        if (inner.isEmpty() || inner.length > 64) return false
        if (inner.contains('*')) return true
        if (inner == tail || inner.startsWith("$tail ")) return true
        val last = tail.takeLast(1)
        if (last.isNotEmpty() && inner.endsWith(last)) return true
        return inner.contains(" 男") || inner.contains(" 女")
    }

    private fun removeSuffixForRawTail(sb: SpannableStringBuilder, rawTail: String) {
        if (rawTail.isBlank()) return
        val value = sb.toString()
        val start = maxOf(value.lastIndexOf('('), value.lastIndexOf('（'))
        if (start < 0 || start >= value.length - 2) return
        val close = if (value[start] == '（') '）' else ')'
        if (value.last() != close) return
        val inner = value.substring(start + 1, value.length - 1)
        if (inner.isEmpty() || inner.length > 16) return
        val last = rawTail.takeLast(1)
        if (inner == rawTail || (last.isNotEmpty() && inner.endsWith(last))) {
            sb.delete(start, sb.length)
        }
    }

    private fun removeAppendedTailSuffixes(sb: SpannableStringBuilder) {
        repeat(4) {
            val value = sb.toString()
            val start = maxOf(value.lastIndexOf('('), value.lastIndexOf('（'))
            if (start < 0 || start >= value.length - 2) return
            val close = if (value[start] == '（') '）' else ')'
            if (value.last() != close) return
            val inner = value.substring(start + 1, value.length - 1)
            if (inner.length in 1..16 && inner.contains('*')) {
                sb.delete(start, sb.length)
            } else {
                return
            }
        }
    }

    private fun flatten(sb: SpannableStringBuilder) {
        var lastWasSpace = false
        var i = 0
        while (i < sb.length) {
            val c = sb[i]
            val isSpace = c == '\n' || c == '\r' || c == '\t' || c == ' ' || c == '\u3000'
            if (!isSpace) {
                lastWasSpace = false
                i++
                continue
            }
            if (lastWasSpace) {
                sb.delete(i, i + 1)
            } else {
                sb.replace(i, i + 1, " ")
                lastWasSpace = true
                i++
            }
        }
        while (sb.isNotEmpty() && sb[0] == ' ') sb.delete(0, 1)
        while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
    }

    private fun locateUsernameBindMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodCachePrefs, methodCacheKey, context.hostClassLoader(), "username_bind")
            ?.takeIf { isUsernameBindCandidate(it) }
            ?.let { return it }
        val matches = findMethodsByStrings("MicroMsg.ChattingItem", "fillingUsername:need getKfInfo")
            .ifEmpty { findMethodsByStrings("fillingUsername:need getKfInfo") }
        val method = matches.firstOrNull { isUsernameBindCandidate(it) }
        if (method != null) {
            DexMethodCache.save(methodCachePrefs, methodCacheKey, "username_bind", method)
        } else {
            DexMethodCache.clear(methodCachePrefs, methodCacheKey, "username_bind")
        }
        return method
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
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
            logger("实名尾字定位方法失败", it)
            emptyList()
        }
    }

    private fun isUsernameBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size >= 3
            && types.any { it == String::class.java }
            && types.any { isLikelyMessageClass(it) }
            && findUserTextField(types[0]) != null
    }

    private fun isLikelyMessageClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        likelyMsgCache[clazz]?.let { return it }
        val result = clazz.name.startsWith("com.tencent.mm.storage.")
            || KavaReflector.declaredMethods(clazz).any {
                it.returnType == String::class.java && it.parameterTypes.isEmpty()
                    && arrayOf("R1", "P1", "o0", "x0", "j0", "getSender", "getSendTalker").contains(it.name)
            }
        likelyMsgCache[clazz] = result
        return result
    }

    private fun findUserTextView(holder: Any): TextView? {
        holderTextFieldCache[holder.javaClass]?.let { return KavaReflector.readField(it, holder) as? TextView }
        val field = findUserTextField(holder.javaClass)
        holderTextFieldCache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? TextView
    }

    private fun findUserTextField(clazz: Class<*>?): Field? {
        if (clazz == null) return null
        holderTextFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val field = KavaReflector.declaredFields(current).firstOrNull {
                it.type == TextView::class.java &&
                    (it.name == "userTV" || it.name == "brc" || it.name.lowercase().contains("user"))
            }
            if (field != null) {
                holderTextFieldCache[clazz] = field
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun extractSender(msg: Any?, fallback: String): String {
        if (msg == null) return fallback.takeIf { RealNameTailStore.isValidWxid(it) }.orEmpty()
        msgSenderCache[msg]?.let { return it }
        val methodNames = arrayOf("A0", "B0", "C0", "D0", "E0", "F0", "getSender", "getSendTalker", "getTalker", "j", "R1", "P1", "o0", "x0", "j0")
        for (name in methodNames) {
            val value = KavaReflector.invoke(KavaReflector.findMethod(msg.javaClass, name), msg) as? String
            if (RealNameTailStore.isValidWxid(value)) {
                val sender = value?.trim().orEmpty()
                msgSenderCache[msg] = sender
                return sender
            }
        }
        val fieldNames = arrayOf("field_talker", "talker", "field_sender", "sender", "sendTalker", "field_talkerUsername", "P")
        for (name in fieldNames) {
            val value = KavaReflector.readField(msg, name) as? String
            if (RealNameTailStore.isValidWxid(value)) {
                val sender = value?.trim().orEmpty()
                msgSenderCache[msg] = sender
                return sender
            }
        }
        contentSender(msg)?.let {
            msgSenderCache[msg] = it
            return it
        }
        return fallback.takeIf { RealNameTailStore.isValidWxid(it) }.orEmpty()
    }

    private fun resolveBindSender(msg: Any?, fallback: String, stringCandidates: List<String>): String {
        stringCandidates.firstOrNull { !isSelf(it) }?.let { return it }
        contentSender(msg)?.takeIf { !isSelf(it) }?.let { return it }
        return extractSender(msg, fallback)
    }

    private fun contentSender(msg: Any?): String? {
        if (msg == null) return null
        val contentFields = arrayOf("field_content", "content", "msgContent", "field_xml", "xml")
        for (name in contentFields) {
            val content = KavaReflector.readField(msg, name) as? String ?: continue
            val sender = groupSenderPrefix(content)
            if (RealNameTailStore.isValidWxid(sender)) return sender
        }
        return null
    }

    private fun groupSenderPrefix(content: String): String {
        val p = when {
            content.indexOf(":\n") > 0 -> content.indexOf(":\n")
            content.indexOf(":\r\n") > 0 -> content.indexOf(":\r\n")
            else -> -1
        }
        if (p <= 0 || p > 80) return ""
        return content.substring(0, p).takeIf { RealNameTailStore.isValidWxid(it) }.orEmpty()
    }

    private fun currentRoomId(): String = WeChatApis.chatPage()?.currentTalker().orEmpty()

    private fun isRoom(value: String?): Boolean {
        val id = value.orEmpty()
        return id.endsWith("@chatroom") || id.endsWith("@im.chatroom")
    }

    private fun isSelf(wxid: String): Boolean {
        val self = WeChatApis.account()?.selfWxId().orEmpty()
        return self.isNotEmpty() && self == wxid
    }

    private fun isOutgoingMessage(msg: Any?): Boolean {
        if (msg == null) return false
        for (name in arrayOf("field_isSend", "isSend")) {
            val value = KavaReflector.readField(msg, name)
            if (value is Number) return value.toInt() == 1
            if (value is Boolean) return value
        }
        return false
    }

    companion object {
    }
}
