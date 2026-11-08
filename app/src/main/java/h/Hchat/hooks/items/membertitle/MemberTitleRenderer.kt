package h.Hchat.hooks.items.membertitle

import android.text.SpannableStringBuilder
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.contact.WeChatChatroomApi
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.chatname.ChatNameDecorationLayout
import h.Hchat.ui.miuix.MemberTitleMiuixDialog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class MemberTitleRenderer(
    private val context: FeatureContext,
    private val store: MemberTitleStore,
    private val logger: (String, Throwable?) -> Unit
) {
    private val msgSenderCache = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val boundViews = Collections.synchronizedMap(WeakHashMap<TextView, Target>())
    private val holderTextFieldCache = ConcurrentHashMap<Class<*>, Field?>()
    private val likelyMsgCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val inSetText = ThreadLocal<Boolean>()
    private val methodCachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_member_title_method_cache")
    @Volatile private var usernameBindInstalled = false

    fun install(): Boolean {
        return installUsernameBindHook()
    }

    fun refreshRoom(roomId: String) {
        if (!isRoom(roomId)) return
        WeChatApis.tasks()?.runOnMain {
            val targets = synchronized(boundViews) { boundViews.values.toSet() }
                .asSequence()
                .filter { it.roomId == roomId }
                .toList()
            targets.forEach { refreshTarget(it) }
        }
    }

    private fun refreshTarget(target: Target) {
        WeChatApis.tasks()?.runOnMain {
            val snapshot = synchronized(boundViews) { boundViews.entries.toList() }
            snapshot.forEach { (view, value) ->
                if (value == target) {
                    applyTitle(view, target.roomId, target.sender)
                }
            }
        }
    }

    private fun installUsernameBindHook(): Boolean {
        if (usernameBindInstalled) return true
        val method = locateUsernameBindMethod() ?: return false
        return try {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleUsernameBind(param)
                }
            })
            usernameBindInstalled = true
            true
        } catch (e: Throwable) {
            logger("群员头衔Hook安装失败", e)
            false
        }
    }

    private fun handleUsernameBind(param: XC_MethodHook.MethodHookParam) {
        if (!store.isEnabled()) return
        val roomId = currentRoomId()
        if (!isRoom(roomId)) return
        val args = param.args ?: return
        val holder = args.firstOrNull() ?: return
        val tv = findUserTextView(holder) ?: return
        var msg: Any? = null
        var raw = ""
        val stringCandidates = mutableListOf<String>()
        args.forEach {
            if (it is String) {
                if (raw.isEmpty()) raw = it
                val value = it.trim()
                if (MemberTitleStore.isValidMemberId(value)) stringCandidates += value
            }
            if (it != null && isLikelyMessageClass(it.javaClass)) msg = it
        }
        if (isOutgoingMessage(msg)) {
            clearBoundTitle(tv)
            return
        }
        val sender = resolveBindSender(msg, raw, stringCandidates)
        if (!MemberTitleStore.isValidMemberId(sender) || isSelf(sender)) {
            clearBoundTitle(tv)
            return
        }
        if (!applyTitle(tv, roomId, sender)) scheduleDelayedApply(tv, roomId, sender)
    }

    private fun applyTitle(tv: TextView, roomId: String, sender: String): Boolean {
        if (!MemberTitleStore.isValidMemberId(sender) || !isRoom(roomId)) return false
        val target = Target(roomId, sender)
        boundViews[tv] = target
        val chatrooms = WeChatApis.contact().chatrooms()
        val role = chatrooms?.getMemberRole(roomId, sender) ?: WeChatChatroomApi.ROLE_MEMBER
        val customTitle = store.customTitle(roomId, sender)
        val title = customTitle.ifEmpty { store.roleTitle(role) }
        if (title.isEmpty() || (role == WeChatChatroomApi.ROLE_MEMBER && customTitle.isEmpty() && !store.showMemberEnabled())) {
            removeTitleOnly(tv)
            return false
        }
        val colorSpec = store.customColorSpec(roomId, sender)
            ?: if (customTitle.isNotEmpty()) store.customDefaultColorSpec() else store.roleColorSpec(role)
        val textColorSpec = store.customTextColorSpec(roomId, sender)
            ?: if (customTitle.isNotEmpty()) store.customDefaultTextColorSpec() else store.roleTextColorSpec(role)
        val nativeHidden = tv.visibility != View.VISIBLE
        if (nativeHidden) tv.visibility = View.VISIBLE
        if (inSetText.get() == true) return false
        return try {
            inSetText.set(true)
            val row = ChatNameDecorationLayout.ensure(tv)
            val cur = ChatNameDecorationLayout.displayNameText(tv)
            val sb = SpannableStringBuilder(if (nativeHidden) "" else cur)
            removeExistingTitle(sb)
            flatten(sb)
            val name = normalizedDisplayName(sb.toString(), sender)
            if (sb.toString() != name) {
                sb.clear()
                sb.append(name)
            }
            ChatNameDecorationLayout.setBaseName(row, sb)
            fitNameView(tv)
            ChatNameDecorationLayout.showTitle(
                row,
                title,
                colorSpec.startColor,
                colorSpec.endColor,
                textColorSpec.startColor,
                textColorSpec.endColor
            ) { showEditDialog(row.titleView, target) }
            true
        } finally {
            inSetText.set(false)
        }
    }

    private fun removeTitleOnly(tv: TextView) {
        ChatNameDecorationLayout.current(tv)?.let { ChatNameDecorationLayout.hideTitle(it) }
        clearTitle(tv)
    }

    private fun clearBoundTitle(tv: TextView) {
        boundViews.remove(tv)
        removeTitleOnly(tv)
    }

    private fun clearTitle(tv: TextView) {
        val cur = tv.text ?: return
        if (inSetText.get() == true) return
        try {
            inSetText.set(true)
            val sb = SpannableStringBuilder(cur)
            removeExistingTitle(sb)
            flatten(sb)
            tv.text = sb
        } finally {
            inSetText.set(false)
        }
    }

    private fun showEditDialog(anchor: TextView, target: Target) {
        val role = WeChatApis.contact().chatrooms()?.getMemberRoleName(target.roomId, target.sender).orEmpty()
        MemberTitleMiuixDialog.show(
            anchor = anchor,
            title = store.customTitle(target.roomId, target.sender),
            color = store.customColorSpec(target.roomId, target.sender)?.toConfigString().orEmpty(),
            textColor = store.customTextColorSpec(target.roomId, target.sender)?.toConfigString().orEmpty(),
            summary = "${target.sender}${if (role.isNotEmpty()) " · $role" else ""}",
            onSave = { title, color, textColor ->
                store.saveCustom(target.roomId, target.sender, title, color, textColor)
                refreshTarget(target)
            },
            onReset = {
                store.clearCustom(target.roomId, target.sender)
                refreshTarget(target)
            }
        )
    }

    private fun removeExistingTitle(sb: SpannableStringBuilder) {
        while (sb.startsWith(OLD_BADGE_OBJECT)) {
            val firstSpace = sb.indexOf(" ").takeIf { it >= 0 } ?: break
            sb.delete(0, (firstSpace + 1).coerceAtMost(sb.length))
        }
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

    private fun fitNameView(tv: TextView) {
        runCatching {
            tv.setHorizontallyScrolling(false)
            tv.ellipsize = null
            tv.setSingleLine(false)
            tv.maxLines = Int.MAX_VALUE
        }
    }

    private fun scheduleDelayedApply(tv: TextView, roomId: String, sender: String) {
        val target = Target(roomId, sender)
        boundViews[tv] = target
        WeChatApis.tasks()?.runOnMainDelayed("member_title_apply_${System.identityHashCode(tv)}", 120L) {
            if (boundViews[tv] == target) {
                applyTitle(tv, roomId, sender)
            }
        }
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
            logger("群员头衔定位方法失败", it)
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
        if (msg == null) return fallback.takeIf { MemberTitleStore.isValidMemberId(it) }.orEmpty()
        msgSenderCache[msg]?.let { return it }
        val methodNames = arrayOf("A0", "B0", "C0", "D0", "E0", "F0", "getSender", "getSendTalker", "getTalker", "j", "R1", "P1", "o0", "x0", "j0")
        for (name in methodNames) {
            val value = KavaReflector.invoke(KavaReflector.findMethod(msg.javaClass, name), msg) as? String
            if (MemberTitleStore.isValidMemberId(value)) {
                val sender = value?.trim().orEmpty()
                msgSenderCache[msg] = sender
                return sender
            }
        }
        val fieldNames = arrayOf("field_talker", "talker", "field_sender", "sender", "sendTalker", "field_talkerUsername", "P")
        for (name in fieldNames) {
            val value = KavaReflector.readField(msg, name) as? String
            if (MemberTitleStore.isValidMemberId(value)) {
                val sender = value?.trim().orEmpty()
                msgSenderCache[msg] = sender
                return sender
            }
        }
        contentSender(msg)?.let {
            msgSenderCache[msg] = it
            return it
        }
        return fallback.takeIf { MemberTitleStore.isValidMemberId(it) }.orEmpty().also {
            if (it.isNotEmpty()) msgSenderCache[msg] = it
        }
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
            if (MemberTitleStore.isValidMemberId(sender)) return sender
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
        return content.substring(0, p).takeIf { MemberTitleStore.isValidMemberId(it) }.orEmpty()
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

    private data class Target(val roomId: String, val sender: String)

    companion object {
        private const val OLD_BADGE_OBJECT = "\uFFFC"
    }
}
