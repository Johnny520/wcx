package h.Hchat.hooks.items.profileid

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.WeChatIdRules
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector

class ProfileIdFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "资料页显示ID"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(ProfileIdSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "profile_id"
        private const val TAG = "[Hchat:ProfileId]"
        private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
        private const val CHATROOM_INFO_UI = "com.tencent.mm.chatroom.ui.ChatroomInfoUI"
        private const val CONTACT_USER = "Contact_User"
        private const val CONTACT_USERNAME = "Contact_Username"
        private const val ROOM_INFO_ID = "RoomInfo_Id"
        private const val INJECTED_TAG = "Hchat:ProfileId:Row"
        private const val INJECTED_VALUE_TAG = "Hchat:ProfileId:Value:"
        private const val CHATROOM_PREF_KEY = "hchat_profile_id"
        private const val CHATROOM_NAME_PREF_KEY = "room_name"
        private const val CHATROOM_BIND_RETRY_LIMIT = 8
        private val CHATROOM_ANCHOR_PREF_KEYS = arrayOf("room_name")
        private const val LABEL = "ID"

        @Volatile
        private var installed = false

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val sp = HchatStorage.preferences(context, ProfileIdSettings.PREFS_NAME)
            return sp.getBoolean(ProfileIdSettings.KEY_ENABLE, ProfileIdSettings.DEFAULT_ENABLE)
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null || installed) return
            synchronized(this) {
                if (installed) return
                hookContactProfile(context, classLoader)
                hookChatroomProfile(context, classLoader)
                installed = true
            }
        }

        private fun hookContactProfile(context: Context, classLoader: ClassLoader) {
            val clazz = KavaReflector.loadClass(CONTACT_INFO_UI, classLoader)
            if (clazz == null) {
                h.Hchat.utils.HLog.e("$TAG 未找到好友资料页类")
                return
            }
            hookAfter(clazz, "initView") { param ->
                injectProfileId(context, param.thisObject as? Activity, ProfileKind.CONTACT)
            }
            hookPreferenceTreeClick(clazz, ProfileKind.CONTACT)
        }

        private fun hookChatroomProfile(context: Context, classLoader: ClassLoader) {
            val clazz = KavaReflector.loadClass(CHATROOM_INFO_UI, classLoader)
            if (clazz == null) {
                h.Hchat.utils.HLog.e("$TAG 未找到群聊资料页类")
                return
            }
            hookAfter(clazz, "initView") { param ->
                injectChatroomProfileId(context, param.thisObject as? Activity)
            }
            hookPreferenceTreeClick(clazz, ProfileKind.CHATROOM)
        }

        /**
         * Preference 行的点击最终路由到所在 Activity 的 onPreferenceTreeClick，
         * 只在 Preference 对象上挂 OnClickListener 在部分版本不生效。
         * 群聊页一直靠这个 Hook 完成复制，好友资料页此前缺失导致点不动，这里两页统一。
         */
        private fun hookPreferenceTreeClick(clazz: Class<*>, kind: ProfileKind) {
            methodsOf(clazz)
                .firstOrNull { it.name == "onPreferenceTreeClick" && it.parameterTypes.size >= 2 }
                ?.let { method ->
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val preference = param.args.getOrNull(1) ?: return
                            if (preferenceKey(preference) != CHATROOM_PREF_KEY) return
                            val profileId = resolveProfileId(activity, kind) ?: return
                            copyProfileId(activity, profileId)
                            param.result = true
                        }
                    })
                }
                ?: h.Hchat.utils.HLog.e("$TAG 未找到资料页 Preference 点击方法: ${clazz.name}")
        }

        private fun injectChatroomProfileId(context: Context, activity: Activity?) {
            if (activity == null || !isEnabled(context)) return
            val profileId = resolveProfileId(activity, ProfileKind.CHATROOM) ?: return
            activity.window?.decorView?.post {
                injectChatroomPreferenceId(activity, profileId)
            }
        }

        private fun hookAfter(
            clazz: Class<*>,
            methodName: String,
            vararg parameterTypes: Class<*>,
            block: (XC_MethodHook.MethodHookParam) -> Unit
        ) {
            val method = KavaReflector.findMethodRecursive(clazz, methodName, *parameterTypes)
            if (method == null) {
                h.Hchat.utils.HLog.e("$TAG 未找到 ${clazz.name}#$methodName")
                return
            }
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { block(param) }.onFailure {
                        h.Hchat.utils.HLog.e("$TAG 注入资料页 ID 失败: ${it.message}", it)
                    }
                }
            })
        }

        private fun injectProfileId(context: Context, activity: Activity?, kind: ProfileKind, post: Boolean = true) {
            if (activity == null || !isEnabled(context)) return
            val profileId = resolveProfileId(activity, kind) ?: return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            if (post) {
                // 先于历史发言记录完成插入，后者会识别本行并排到其后。
                // View 没有 postAtFrontOfQueue，队首插入要走它绑定的 Handler。
                val handler = decor.handler
                if (handler != null) {
                    handler.postAtFrontOfQueue { injectProfileIdNow(activity, profileId, kind) }
                } else {
                    injectProfileIdNow(activity, profileId, kind)
                }
            } else {
                injectProfileIdNow(activity, profileId, kind)
            }
        }

        private fun injectProfileIdNow(activity: Activity, profileId: String, kind: ProfileKind) {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val existing = findInjectedRow(decor)
            if (injectContactPreferenceId(activity, profileId)) {
                existing?.let { (it.parent as? ViewGroup)?.removeView(it) }
                return
            }
            if (existing != null) {
                bindRow(existing, activity, profileId)
                return
            }
            val (container, anchorRow) = findProfileAnchor(decor, kind) ?: return
            val row = createRow(activity, profileId, anchorRow)
            val index = container.indexOfChild(anchorRow)
            container.addView(row, index.coerceIn(0, container.childCount), row.layoutParams)
        }

        private fun injectContactPreferenceId(activity: Activity, profileId: String): Boolean {
            val decor = activity.window?.decorView as? ViewGroup ?: return false
            val adapter = findProfileListView(decor)?.adapter
            if (adapter != null && injectContactPreference(adapter, activity, profileId)) {
                // 好友页原来的点击只绑在 Preference 对象上，部分版本点不动；
                // 和群聊页一样，渲染完成后再给整行 View 补一次。
                bindRenderedRow(activity, profileId)
                return true
            }
            val screen = KavaReflector.invokeMethod(activity, "getPreferenceScreen") ?: return false
            if (!injectContactPreference(screen, activity, profileId)) return false
            bindRenderedRow(activity, profileId)
            return true
        }

        private fun injectContactPreference(container: Any, activity: Activity, profileId: String): Boolean {
            val anchor = findListedPreference(container) { preferenceTitle(it) == "朋友资料" } ?: return false
            val titleGetter = findPreferenceTitleGetter(anchor, "朋友资料")
            val existing = findListedPreference(container) { preferenceKey(it) == CHATROOM_PREF_KEY }
            if (existing != null) {
                bindPreference(existing, profileId, titleGetter)
                bindPreferenceClick(existing, activity, profileId)
                notifyPreferenceChanged(container)
                return true
            }
            val preferenceClass = KavaReflector.loadClass(
                "com.tencent.mm.ui.base.preference.Preference",
                activity.classLoader
            ) ?: return false
            val preference = KavaReflector.newInstance(
                KavaReflector.findConstructor(preferenceClass, Context::class.java),
                activity
            ) ?: return false
            setPreferenceKey(preference, CHATROOM_PREF_KEY)
            bindPreference(preference, profileId, titleGetter)
            bindPreferenceClick(preference, activity, profileId)
            copyLayoutResource(anchor, preference)
            val anchorIndex = listedPreferenceIndex(container, anchor) ?: return false
            if (!invokeAddPreference(container, preference, anchorIndex)) return false
            notifyPreferenceChanged(container)
            return true
        }

        private fun findListedPreference(container: Any, predicate: (Any) -> Boolean): Any? {
            for (index in 0 until listedPreferenceCount(container)) {
                val preference = listedPreferenceAt(container, index) ?: continue
                if (preferenceKey(preference) != null && predicate(preference)) return preference
            }
            return null
        }

        private fun listedPreferenceIndex(container: Any, target: Any): Int? {
            for (index in 0 until listedPreferenceCount(container)) {
                if (listedPreferenceAt(container, index) === target) return index
            }
            return null
        }

        private fun listedPreferenceCount(container: Any): Int {
            return methodsOf(container.javaClass).firstNotNullOfOrNull { method ->
                if (method.name == "getCount" && method.parameterTypes.isEmpty()) {
                    (KavaReflector.invoke(method, container) as? Number)?.toInt()
                } else null
            } ?: 0
        }

        private fun listedPreferenceAt(container: Any, index: Int): Any? {
            return methodsOf(container.javaClass).firstNotNullOfOrNull { method ->
                if (method.name == "getItem" && method.parameterTypes.size == 1 &&
                    (method.parameterTypes[0] == Integer.TYPE || method.parameterTypes[0] == java.lang.Integer::class.java)
                ) {
                    KavaReflector.invoke(method, container, index)
                } else null
            }
        }

        private fun findProfileListView(group: ViewGroup): android.widget.ListView? {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is android.widget.ListView) return child
                if (child is ViewGroup) findProfileListView(child)?.let { return it }
            }
            return null
        }

        private fun preferenceTitle(preference: Any?): String {
            if (preference == null) return ""
            for (name in arrayOf("i", "h")) {
                (KavaReflector.readField(preference, name) as? CharSequence)
                    ?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
            return methodsOf(preference.javaClass).firstNotNullOfOrNull { method ->
                if (method.parameterTypes.isEmpty() && CharSequence::class.java.isAssignableFrom(method.returnType)) {
                    (KavaReflector.invoke(method, preference) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
                } else null
            }.orEmpty()
        }

        private fun injectChatroomPreferenceId(activity: Activity, profileId: String) {
            val screen = KavaReflector.invokeMethod(activity, "getPreferenceScreen") ?: return
            val roomName = findPreference(screen, CHATROOM_NAME_PREF_KEY)
            val titleGetter = roomName?.let { findRoomNameTitleGetter(it) }
            val existing = findPreference(screen, CHATROOM_PREF_KEY)
            if (existing != null) {
                bindPreference(existing, profileId, titleGetter)
                bindPreferenceClick(existing, activity, profileId)
                notifyPreferenceChanged(screen)
                bindRenderedRow(activity, profileId)
                return
            }
            val preferenceClass = KavaReflector.loadClass(
                "com.tencent.mm.ui.base.preference.Preference",
                activity.classLoader
            ) ?: return
            val preference = KavaReflector.newInstance(
                KavaReflector.findConstructor(preferenceClass, Context::class.java),
                activity
            ) ?: return
            setPreferenceKey(preference, CHATROOM_PREF_KEY)
            bindPreference(preference, profileId, titleGetter)
            bindPreferenceClick(preference, activity, profileId)
            copyLayoutResource(roomName, preference)
            val anchor = findChatroomAnchorPreference(screen)
            if (!addPreferenceBeforeAnchor(screen, preference, anchor)) {
                h.Hchat.utils.HLog.e("$TAG 群聊 ID Preference 插入失败")
                return
            }
            notifyPreferenceChanged(screen)
            bindRenderedRow(activity, profileId)
        }

        private fun findChatroomAnchorPreference(screen: Any): Any? {
            for (key in CHATROOM_ANCHOR_PREF_KEYS) {
                findPreference(screen, key)?.let { return it }
            }
            return null
        }

        private fun findPreference(screen: Any, key: String): Any? {
            return methodsOf(screen.javaClass).firstNotNullOfOrNull { method ->
                if (method.parameterTypes.size != 1 ||
                    method.parameterTypes[0] != String::class.java ||
                    !method.returnType.name.contains("Preference")
                ) {
                    null
                } else {
                    KavaReflector.invoke(method, screen, key)?.takeIf { preferenceKey(it) == key }
                }
            }
        }

        private fun addPreferenceBeforeAnchor(screen: Any, preference: Any, anchor: Any?): Boolean {
            val index = preferenceIndex(screen, anchor)
                ?: CHATROOM_ANCHOR_PREF_KEYS.firstNotNullOfOrNull { preferenceIndexByKey(screen, it) }
                ?: return false
            return invokeAddPreference(screen, preference, index)
        }

        private fun invokeAddPreference(screen: Any, preference: Any, index: Int): Boolean {
            return methodsOf(screen.javaClass).any { method ->
                if (method.parameterTypes.size != 2 ||
                    !method.parameterTypes[0].isAssignableFrom(preference.javaClass) ||
                    method.parameterTypes[1] != Integer.TYPE
                ) {
                    false
                } else {
                    KavaReflector.invokeSuccessfully(method, screen, preference, index)
                }
            }
        }

        private fun preferenceIndex(screen: Any, preference: Any?): Int? {
            if (preference == null) return null
            val key = preferenceKey(preference) ?: return null
            return preferenceIndexByKey(screen, key)
        }

        private fun preferenceIndexByKey(screen: Any, key: String): Int? {
            methodsOf(screen.javaClass).forEach { method ->
                if (method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == String::class.java &&
                    method.returnType == Integer.TYPE
                ) {
                    val index = KavaReflector.invoke(method, screen, key) as? Int
                    if (index != null && index >= 0) return index
                }
            }
            return null
        }

        private fun setPreferenceKey(preference: Any, key: String) {
            KavaReflector.writeField(preference, "q", key)
            KavaReflector.findMethod(preference.javaClass, "C", String::class.java)
                ?.let { KavaReflector.invoke(it, preference, key) }
        }

        private fun bindPreference(
            preference: Any,
            profileId: String,
            titleGetter: java.lang.reflect.Method? = null
        ) {
            val title = "$LABEL: $profileId"
            KavaReflector.writeField(preference, "h", title)
            KavaReflector.writeField(preference, "m", "")
            val titleSetters = titleGetter?.let { findTitleSetters(preference, it) }.orEmpty()
            if (titleSetters.isNotEmpty()) {
                clearCharSequenceValues(preference, titleSetters)
                titleSetters.forEach { KavaReflector.invoke(it, preference, title) }
            } else {
                KavaReflector.findMethod(preference.javaClass, "L", CharSequence::class.java)?.let {
                    KavaReflector.invoke(it, preference, title)
                }
            }
            KavaReflector.writeField(preference, "m", "")
        }

        private fun bindPreferenceClick(preference: Any, activity: Activity, profileId: String) {
            methodsOf(preference.javaClass).forEach { method ->
                if (method.parameterTypes.size == 1 &&
                    View.OnClickListener::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == Void.TYPE
                ) {
                    KavaReflector.invoke(method, preference, View.OnClickListener {
                        copyProfileId(activity, profileId)
                    })
                }
            }
        }

        /** Preference 的点击回调在部分版本不生效，渲染完成后直接给整行 View 补一次点击。 */
        private fun bindRenderedRow(activity: Activity, profileId: String, attempt: Int = 0) {
            val decor = activity.window?.decorView as? ViewGroup ?: return
            decor.postDelayed({
                val title = "$LABEL: $profileId"
                val titleView = findTextView(decor) { it == title } ?: findTextView(decor) {
                    it.startsWith("$LABEL: ") && it.contains(profileId)
                }
                if (titleView != null) {
                    bindRenderedClickTargets(titleView, activity, profileId)
                    return@postDelayed
                }
                if (attempt + 1 < CHATROOM_BIND_RETRY_LIMIT) {
                    bindRenderedRow(activity, profileId, attempt + 1)
                }
            }, if (attempt == 0) 0L else 120L)
        }

        private fun bindRenderedClickTargets(titleView: TextView, activity: Activity, profileId: String) {
            val listener = View.OnClickListener { copyProfileId(activity, profileId) }
            titleView.setOnClickListener(listener)
            titleView.isClickable = true
            titleView.isFocusable = false
            // 一路绑到 ListView 的直接子项（列表项根 View），保证点整行任意位置都能复制；
            // 固定层数在层级更深的布局里会漏掉列表项根，点文字以外的空白就没有反应。
            var current: View? = titleView
            var depth = 0
            while (current != null && depth < 10) {
                current.setOnClickListener(listener)
                current.isClickable = true
                current.isFocusable = false
                val parent = current.parent
                if (parent is android.widget.ListView) break
                current = parent as? View
                depth++
            }
        }

        private fun findRoomNameTitleGetter(preference: Any): java.lang.reflect.Method? {
            return findPreferenceTitleGetter(preference, "群聊名称")
        }

        private fun findPreferenceTitleGetter(preference: Any, expectedTitle: String): java.lang.reflect.Method? {
            return methodsOf(preference.javaClass).firstOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    CharSequence::class.java.isAssignableFrom(method.returnType) &&
                    (KavaReflector.invoke(method, preference)?.toString() == expectedTitle)
            }
        }

        private fun findTitleSetters(
            preference: Any,
            titleGetter: java.lang.reflect.Method
        ): List<java.lang.reflect.Method> {
            val setters = methodsOf(preference.javaClass).filter { method ->
                method.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == Void.TYPE
            }
            val result = ArrayList<java.lang.reflect.Method>()
            setters.forEachIndexed { index, method ->
                val marker = "HchatTitleProbe$index"
                clearCharSequenceValues(preference, emptyList())
                KavaReflector.invoke(method, preference, marker)
                if (KavaReflector.invoke(titleGetter, preference)?.toString() == marker) {
                    result += method
                }
            }
            clearCharSequenceValues(preference, emptyList())
            return result
        }

        private fun clearCharSequenceValues(
            preference: Any,
            keep: List<java.lang.reflect.Method>
        ) {
            methodsOf(preference.javaClass).forEach { method ->
                if (method in keep) return@forEach
                if (method.parameterTypes.size == 1 &&
                    CharSequence::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == Void.TYPE
                ) {
                    KavaReflector.invoke(method, preference, "")
                }
            }
            KavaReflector.writeField(preference, "h", "")
            KavaReflector.writeField(preference, "m", "")
        }

        private fun preferenceKey(preference: Any?): String? {
            if (preference == null) return null
            (KavaReflector.readField(preference, "q") as? String)?.let { return it }
            return methodsOf(preference.javaClass).firstNotNullOfOrNull { method ->
                if (method.parameterTypes.isEmpty() && method.returnType == String::class.java) {
                    KavaReflector.invoke(method, preference) as? String
                } else {
                    null
                }
            }
        }

        private fun copyLayoutResource(from: Any?, to: Any) {
            if (from == null) return
            val layout = KavaReflector.invokeMethod(from, "getLayoutResource") as? Number ?: return
            if (layout.toInt() == 0) return
            KavaReflector.invokeMethod(to, "setLayoutResource", layout.toInt())
        }

        private fun notifyPreferenceChanged(screen: Any) {
            KavaReflector.invokeMethod(screen, "notifyDataSetChanged")
            methodsOf(screen.javaClass).firstOrNull { method ->
                method.name == "notifyDataSetChanged" && method.parameterTypes.isEmpty()
            }?.let { KavaReflector.invoke(it, screen) }
        }

        private fun methodsOf(clazz: Class<*>?): List<java.lang.reflect.Method> {
            val methods = ArrayList<java.lang.reflect.Method>()
            var current = clazz
            while (current != null && current != Any::class.java) {
                methods += KavaReflector.declaredMethods(current)
                current = current.superclass
            }
            return methods
        }

        private fun resolveProfileId(activity: Activity, kind: ProfileKind): String? {
            val intent = activity.intent
            val direct = when (kind) {
                ProfileKind.CONTACT -> firstNonBlank(
                    intent.getStringExtra(CONTACT_USER),
                    intent.getStringExtra(CONTACT_USERNAME)
                )
                ProfileKind.CHATROOM -> intent.getStringExtra(ROOM_INFO_ID)
            }
            if (!direct.isNullOrBlank()) return direct.trim()
            val extras = intent.extras ?: return null
            for (key in extras.keySet()) {
                val value = runCatching { extras.get(key) as? String }.getOrNull()?.trim().orEmpty()
                if (value.isBlank()) continue
                if (kind == ProfileKind.CHATROOM && isChatroomId(value)) return value
                if (kind == ProfileKind.CONTACT && isPossibleContactId(value)) return value
            }
            return null
        }

        private fun firstNonBlank(vararg values: String?): String? {
            return values.firstOrNull { !it.isNullOrBlank() }?.trim()
        }

        private fun isChatroomId(value: String): Boolean {
            return value.endsWith("@chatroom") || value.endsWith("@im.chatroom")
        }

        private fun isPossibleContactId(value: String): Boolean {
            return WeChatIdRules.isLikelyContactId(value)
        }

        private fun findInjectedRow(root: ViewGroup): View? {
            if ((root.tag as? String)?.startsWith(INJECTED_VALUE_TAG) == true || root.tag == INJECTED_TAG) {
                return root
            }
            for (index in 0 until root.childCount) {
                val child = root.getChildAt(index)
                if ((child.tag as? String)?.startsWith(INJECTED_VALUE_TAG) == true || child.tag == INJECTED_TAG) {
                    return child
                }
                if (child is ViewGroup) {
                    val injected = findInjectedRow(child)
                    if (injected != null) return injected
                }
            }
            return null
        }

        private fun findProfileAnchor(root: ViewGroup, kind: ProfileKind): Pair<LinearLayout, View>? {
            val anchorTitle = if (kind == ProfileKind.CHATROOM) "群聊名称" else "朋友资料"
            var best: Pair<LinearLayout, View>? = null
            var bestScore = Int.MAX_VALUE

            fun visit(group: ViewGroup) {
                if (group is LinearLayout && group.orientation == LinearLayout.VERTICAL) {
                    for (index in 0 until group.childCount) {
                        val child = group.getChildAt(index) as? ViewGroup ?: continue
                        val lines = textOf(child).lineSequence()
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toList()
                        if (anchorTitle !in lines) continue
                        if (findTextView(child) { true } == null) continue
                        val score = lines.size * 1000 + lines.sumOf { it.length }
                        if (score < bestScore) {
                            best = group to child
                            bestScore = score
                        }
                    }
                }
                forEachChild(group) { child ->
                    if (child is ViewGroup) visit(child)
                }
            }

            visit(root)
            return best
        }

        private fun collectText(view: View, depth: Int, block: (String) -> Unit) {
            if (depth > 4) return
            if (view is TextView) {
                val text = view.text?.toString().orEmpty()
                if (text.isNotBlank()) block(text)
            }
            if (view is ViewGroup) {
                forEachChild(view) { child -> collectText(child, depth + 1, block) }
            }
        }

        private fun textOf(view: View): String {
            val builder = StringBuilder()
            collectText(view, 0) { builder.append(it).append('\n') }
            return builder.toString()
        }

        private fun createRow(activity: Activity, profileId: String, template: View): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(activity, 56f)
                setPadding(dp(activity, 16f), dp(activity, 12f), dp(activity, 16f), dp(activity, 12f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            // 文本按内容收窄、超长单行省略：ID 本身很短，用 weight 铺满整行会让右侧
            // 留出一大片空白，看起来像是挤掉了别的信息。
            val text = TextView(activity).apply {
                textSize = 16f
                includeFontPadding = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(text)
            // 完全照抄“朋友资料”或“群聊名称”行的高度、内边距、字号、颜色和字体。
            applyTemplateStyle(row, text, template, activity)
            // 整行点击即复制，不再放箭头：箭头意味着"还有下一级"，这里并没有跳转。
            row.isClickable = true
            bindRow(row, activity, profileId)
            return row
        }

        private fun applyTemplateStyle(row: LinearLayout, text: TextView, template: View, activity: Activity) {
            row.minimumHeight = template.height.takeIf { it > 0 } ?: template.minimumHeight
            row.setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
            row.isClickable = true
            val sourceText = (template as? ViewGroup)?.let { findTextView(it) { true } } ?: (template as? TextView)
            if (sourceText != null) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sourceText.textSize)
                text.setTextColor(sourceText.textColors)
                text.typeface = sourceText.typeface
                text.includeFontPadding = sourceText.includeFontPadding
                text.gravity = sourceText.gravity
                text.letterSpacing = sourceText.letterSpacing
                val vertical = sourceText.paddingTop
                text.setPadding(0, vertical, 0, vertical)
                alignTextToTemplate(text, template, sourceText)
            }
            // 上限留出整行可用宽度，超出交给省略号，避免长 ID 撑出屏幕。
            text.maxWidth = (activity.resources.displayMetrics.widthPixels -
                row.paddingLeft - row.paddingRight).coerceAtLeast(dp(activity, 120f))
        }

        /** 让注入文本的左边线与“朋友资料”或“群聊名称”的原生文本严格对齐。 */
        private fun alignTextToTemplate(text: TextView, template: View, sourceText: TextView) {
            val offset = leftWithin(template, sourceText) - template.paddingLeft
            val params = text.layoutParams as? LinearLayout.LayoutParams ?: return
            val margin = offset.coerceAtLeast(0)
            if (params.marginStart != margin) {
                params.marginStart = margin
                params.marginEnd = 0
                text.layoutParams = params
            }
        }

        /** descendant 相对 ancestor 的水平偏移；不在同一棵树上时返回 0。 */
        private fun leftWithin(ancestor: View, descendant: View): Int {
            var offset = 0
            var current: View? = descendant
            while (current != null && current !== ancestor) {
                offset += current.left
                current = current.parent as? View
            }
            return if (current === ancestor) offset else 0
        }

        private fun bindRow(row: View, activity: Activity, profileId: String) {
            row.tag = INJECTED_VALUE_TAG + profileId
            val textView = if (row is ViewGroup) {
                firstTextChild(row)
            } else {
                null
            }
            textView?.text = "$LABEL: $profileId"
            row.setOnClickListener {
                copyProfileId(activity, profileId)
            }
        }

        private fun copyProfileId(activity: Activity, profileId: String) {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(LABEL, profileId))
            Toast.makeText(activity, "已复制ID", Toast.LENGTH_SHORT).show()
        }

        private fun dp(context: Context, value: Float): Int {
            return (value * context.resources.displayMetrics.density + 0.5f).toInt()
        }

        private fun forEachChild(group: ViewGroup, block: (View) -> Unit) {
            for (index in 0 until group.childCount) {
                block(group.getChildAt(index))
            }
        }

        private fun firstTextChild(group: ViewGroup): TextView? {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView) return child
            }
            return null
        }

        private fun findTextView(group: ViewGroup, predicate: (String) -> Boolean): TextView? {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is TextView && predicate(child.text?.toString().orEmpty())) {
                    return child
                }
                if (child is ViewGroup) {
                    findTextView(child, predicate)?.let { return it }
                }
            }
            return null
        }

    }

    private enum class ProfileKind {
        CONTACT,
        CHATROOM
    }
}
