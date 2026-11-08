package h.Hchat.hooks.items.groupleave

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

object GroupLeaveMonitorHighlighter {
    @Volatile private var textCleanupInstalled = false
    @Volatile private var uriClickInstalled = false

    fun install(context: FeatureContext, logger: (String, Throwable?) -> Unit): Boolean {
        val textOk = installTextCleanup(context.hostContext(), logger)
        installProfileLinkClick(context, logger)
        return textOk
    }

    fun installProfileLinkClick(
        context: FeatureContext,
        logger: (String, Throwable?) -> Unit
    ) {
        if (!installUriClick(context, allowDexSearch = false, logger)) {
            DexInstallScheduler.schedule(
                "${GroupLeaveMonitorFeature.ID}_uri_click",
                "退群监控链接点击",
                stage = DexInstallScheduler.Stage.BRIDGE
            ) {
                installUriClick(context, allowDexSearch = true, logger)
            }
        }
    }

    private fun installTextCleanup(context: Context, logger: (String, Throwable?) -> Unit): Boolean {
        if (textCleanupInstalled) return true
        val method = KavaReflector.findDeclaredMethod(
            TextView::class.java,
            "setText",
            CharSequence::class.java,
            TextView.BufferType::class.java
        )
        if (method == null) {
            logger("退群监控 wxid 高亮 Hook 未找到", null)
            return false
        }
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val original = param.args?.getOrNull(0) as? CharSequence ?: return
                    val highlighted = highlight(context, original) ?: return
                    param.args[0] = highlighted
                }
            })
            textCleanupInstalled = true
            true
        }.getOrElse {
            logger("退群监控 wxid 高亮 Hook 安装失败", it)
            false
        }
    }

    private fun highlight(
        context: Context,
        original: CharSequence
    ): CharSequence? {
        val raw = original.toString()
        val normalized = normalizeLegacyTemplateXml(raw)
        if (normalized == null &&
            (raw.contains("<_wc_custom_link_", ignoreCase = true) || raw.contains("<sysmsg", ignoreCase = true))
        ) {
            return null
        }
        val text = normalized ?: raw
        val notice = findLeaveNotice(text) ?: return null
        val prefs = HchatStorage.preferences(context, GroupLeaveMonitorSettings.PREFS_NAME)
        if (!prefs.getBoolean(GroupLeaveMonitorSettings.KEY_ENABLE, GroupLeaveMonitorSettings.DEFAULT_ENABLE)) {
            return null
        }
        val color = parseColor(
            prefs.getString(
                GroupLeaveMonitorSettings.KEY_WXID_COLOR,
                GroupLeaveMonitorSettings.DEFAULT_WXID_COLOR
            )
        ) ?: parseColor(GroupLeaveMonitorSettings.DEFAULT_WXID_COLOR) ?: return null
        val builder = if (normalized != null) {
            SpannableStringBuilder(normalized)
        } else {
            SpannableStringBuilder(original)
        }
        builder.getSpans(0, builder.length, ForegroundColorSpan::class.java).forEach {
            builder.removeSpan(it)
        }
        builder.setSpan(
            ForegroundColorSpan(color),
            notice.wxidStart,
            notice.wxidEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (SPAN_PRIORITY shl Spanned.SPAN_PRIORITY_SHIFT)
        )
        return builder
    }

    private fun findLeaveNotice(text: String): LeaveNotice? {
        val close = text.lastIndexOf(LEAVE_SUFFIX_TEXT)
        if (close <= 0) return null
        findWxidInTail(text, close, '(', ')')?.let { return it }
        return findWxidInTail(text, close, '[', ']')
    }

    private fun findWxidInTail(text: String, suffixStart: Int, openChar: Char, closeChar: Char): LeaveNotice? {
        var bracketClose = suffixStart - 1
        while (bracketClose > 0 && isTailSpace(text[bracketClose])) {
            bracketClose--
        }
        if (bracketClose <= 0 || text[bracketClose] != closeChar) return null
        val bracketOpen = text.lastIndexOf(openChar, bracketClose)
        if (bracketOpen < 0 || bracketOpen + 1 >= bracketClose) return null
        val wxid = text.substring(bracketOpen + 1, bracketClose).trim()
        if (wxid.isEmpty()) return null
        return LeaveNotice(wxid, bracketOpen + 1, bracketClose)
    }

    private fun legacyBracketNotice(text: String): LeaveNotice? {
        val close = text.lastIndexOf(LEAVE_SUFFIX_TEXT)
        if (close <= 0) return null
        var bracketClose = close - 1
        while (bracketClose > 0 && isTailSpace(text[bracketClose])) {
            bracketClose--
        }
        if (bracketClose <= 0 || text[bracketClose] != ']') return null
        val bracketOpen = text.lastIndexOf('[', bracketClose)
        if (bracketOpen < 0 || bracketOpen + 1 >= bracketClose) return null
        val wxid = text.substring(bracketOpen + 1, bracketClose).trim()
        if (wxid.isEmpty()) return null
        return LeaveNotice(wxid, bracketOpen + 1, bracketClose)
    }

    private fun normalizeLegacyTemplateXml(text: String): String? {
        if (!text.contains("<sysmsg", ignoreCase = true) ||
            !text.contains("tmpl_type_profile", ignoreCase = true) ||
            !text.contains(LEAVE_SUFFIX.trim(), ignoreCase = false)
        ) {
            return null
        }
        val username = tagValue(text, "username")
        val nickname = tagValue(text, "nickname")
        val visible = nickname.ifBlank { username }.trim()
        if (visible.isEmpty()) return null
        val wxid = username.ifBlank {
            val notice = legacyBracketNotice("$visible$LEAVE_SUFFIX")
            notice?.wxid.orEmpty()
        }
        val memberText = if (visible.contains("(") && visible.contains(")")) {
            visible
        } else if (visible.contains("[") && visible.contains("]")) {
            legacyBracketNotice("$visible$LEAVE_SUFFIX")?.let {
                visible.replaceRange(it.wxidStart - 1, it.wxidEnd + 1, "(${it.wxid})")
            } ?: visible
        } else if (wxid.isNotBlank()) {
            "$visible($wxid)"
        } else {
            visible
        }
        return "$memberText$LEAVE_SUFFIX"
    }

    private fun isTailSpace(ch: Char): Boolean {
        return ch.isWhitespace() || ch == '\u00A0' || ch == '\u3000'
    }

    private fun tagValue(text: String, tag: String): String {
        val match = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: return ""
        return unescapeXml(match.groupValues.getOrNull(1).orEmpty().trim())
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun parseColor(value: String?): Int? {
        val raw = value?.substringBefore(',')?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("#")) raw else "#$raw"
        val hex = normalized.substring(1)
        if ((hex.length != 6 && hex.length != 8) || hex.any { !it.isDigit() && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return null
        }
        return runCatching { Color.parseColor("#${hex.uppercase()}") }.getOrNull()
    }

    private data class LeaveNotice(
        val wxid: String,
        val wxidStart: Int,
        val wxidEnd: Int
    )

    private const val LEAVE_SUFFIX_TEXT = "退出了群聊"
    private const val LEAVE_SUFFIX = " $LEAVE_SUFFIX_TEXT"
    private const val SPAN_PRIORITY = 0xFF

    @Synchronized
    private fun installUriClick(
        context: FeatureContext,
        allowDexSearch: Boolean,
        logger: (String, Throwable?) -> Unit
    ): Boolean {
        if (uriClickInstalled) return true
        val methods = locateUriSpanClickMethods(context, allowDexSearch, logger)
        if (methods.isEmpty()) return false
        var count = 0
        var lastError: Throwable? = null
        for (method in methods) {
            runCatching {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val uri = findHchatUri(param.args) ?: return
                        val memberId = GroupLeaveMonitorLinks.memberIdFromUri(uri)
                        if (memberId.isBlank()) return
                        val viewContext = param.args?.firstOrNull { it is View }?.let { (it as View).context }
                            ?: context.hostContext()
                        openProfile(viewContext, memberId)
                        param.result = if (method.returnType == java.lang.Boolean.TYPE ||
                            method.returnType == Boolean::class.javaObjectType
                        ) {
                            true
                        } else {
                            null
                        }
                    }
                })
                count++
            }.onFailure {
                lastError = it
            }
        }
        uriClickInstalled = count > 0
        if (!uriClickInstalled) {
            logger("退群监控链接点击 Hook 安装失败", lastError)
        }
        return uriClickInstalled
    }

    private fun locateUriSpanClickMethods(
        context: FeatureContext,
        allowDexSearch: Boolean,
        logger: (String, Throwable?) -> Unit
    ): List<Method> {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val key = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
        if (key.isBlank()) return emptyList()
        DexMethodCache.loadList(prefs, key, context.hostClassLoader(), CACHE_URI_SPAN_CLICK)
            .filter { isUriSpanClickMethod(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        if (!allowDexSearch) return emptyList()
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings(
                                "MicroMsg.URISpanHandlerSet",
                                "LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null"
                            )
                        }
                    )
                }
            ).mapNotNull { it.getMethodInstance(context.hostClassLoader()) }
        }.getOrElse {
            logger("退群监控链接点击方法定位失败", it)
            emptyList()
        }.filter { isUriSpanClickMethod(it) }
            .distinctBy { it.toGenericString() }
        if (methods.isNotEmpty()) {
            DexMethodCache.saveList(prefs, key, CACHE_URI_SPAN_CLICK, methods)
        } else {
            DexMethodCache.clear(prefs, key, CACHE_URI_SPAN_CLICK)
        }
        return methods
    }

    private fun isUriSpanClickMethod(method: Method): Boolean {
        val types = method.parameterTypes ?: return false
        return types.size >= 2 && types.any { View::class.java.isAssignableFrom(it) }
    }

    private fun findHchatUri(args: Array<Any?>?): String? {
        if (args == null) return null
        for (arg in args) {
            val uri = extractUri(arg)
            if (uri.startsWith(GroupLeaveMonitorLinks.PROFILE_URI_PREFIX)) return uri
        }
        return null
    }

    private fun extractUri(value: Any?): String {
        if (value == null) return ""
        if (value is CharSequence) return value.toString()
        val direct = value.toString()
        if (direct.startsWith(GroupLeaveMonitorLinks.PROFILE_URI_PREFIX)) return direct
        var current: Class<*>? = value.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val raw = KavaReflector.readField(field, value) ?: continue
                val str = raw.toString()
                if (str.startsWith(GroupLeaveMonitorLinks.PROFILE_URI_PREFIX)) return str
            }
            current = current.superclass
        }
        return ""
    }

    private fun openProfile(context: Context, wxid: String) {
        if (wxid.isBlank()) return
        val chatroomId = WeChatApis.chatPage()?.currentTalker().orEmpty()
        val intent = Intent()
        intent.component = ComponentName(context.packageName, CONTACT_INFO_UI)
        intent.putExtra("Contact_User", wxid)
        if (chatroomId.endsWith("@chatroom") || chatroomId.endsWith("@im.chatroom")) {
            intent.putExtra("Contact_ChatRoomId", chatroomId)
            intent.putExtra("room_name", chatroomId)
            intent.putExtra("Contact_Scene", 14)
        } else {
            intent.putExtra("Contact_Scene", 3)
        }
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(intent)
        }
    }

    private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
    private const val CACHE_PREFS = "Hchat_group_leave_monitor_method_cache"
    private const val CACHE_SCHEMA = "group_leave_monitor_uri_click_v1"
    private const val CACHE_URI_SPAN_CLICK = "uri_span_click_methods"
}
