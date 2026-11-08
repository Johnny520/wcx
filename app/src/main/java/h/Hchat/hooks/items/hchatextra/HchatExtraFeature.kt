package h.Hchat.hooks.items.hchatextra

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.hideavatar.HideChatAvatarSettings
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.json.JSONObject
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

class HchatExtraFeature : BaseFeature() {
    private var hooker: HchatExtraHooker? = null

    override fun featureId(): String = ID

    override fun name(): String = "分支扩展功能"

    override fun onFeatureInit(context: FeatureContext) {
        HchatExtraSettings.migrateLegacyPreferences(context.hostContext())
        registerSettingsProvider(MessageDetailsSettingsProvider())
        registerSettingsProvider(GroupMemberHistorySettingsProvider())
        registerSettingsProvider(RedPacketDetailsSettingsProvider())
        registerSettingsProvider(SkipWebRiskSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        hooker = HchatExtraHooker(context, ::logError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) {
            scheduleInstall()
        }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        hooker?.destroy()
        hooker = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            hooker?.install() == true
        }
        DexInstallScheduler.schedule("hchat_extra_message_details", "消息显示时间") {
            hooker?.installMessageDetails() == true
        }
        DexInstallScheduler.schedule("hchat_extra_forwarded_types", "聊天记录消息类型") {
            hooker?.installForwardedRecordTypes() == true
        }
    }

    companion object {
        const val ID = "hchat_extra"
    }
}

private class HchatExtraHooker(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class MessageDetailsBinding(
        val root: View,
        val nativeTimeLabel: TextView?,
        val holder: Any,
        val container: View?,
        val nativeMessage: Any?,
        val details: MessageDetails
    )

    private data class MessageDetailsBindState(
        val holder: Any?,
        val root: View? = null,
        var message: Any? = null
    )

    private data class AvatarDetailsAnchor(
        val parent: RelativeLayout,
        val positionView: View,
        val hidden: Boolean
    )

    private data class MessageDetailsConfig(
        val enabled: Boolean,
        val format: String,
        val tokens: Set<String>,
        val timeFormatter: DateTimeFormatter,
        val textSizeSp: Float,
        val marginLeftDp: Int,
        val marginTopDp: Int,
        val marginRightDp: Int,
        val marginBottomDp: Int,
        val lightTextColor: Int,
        val darkTextColor: Int,
        val lightBgColor: Int,
        val darkBgColor: Int
    )

    private data class MessageAccessorKey(
        val type: Class<*>,
        val getter: String,
        val primaryField: String,
        val fallbackField: String
    )

    private data class MessageAccessor(
        val getter: Method?,
        val primaryField: Field?,
        val fallbackField: Field?
    )

    private val prefs = HchatStorage.preferences(context.hostContext(), HchatExtraSettings.PREFS_NAME)
    private val timeFormatterCache = ConcurrentHashMap<String, DateTimeFormatter>()
    private val hideAvatarPrefs = HchatStorage.preferences(
        context.hostContext(),
        HideChatAvatarSettings.PREFS_NAME
    )
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_extra_method_cache")
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val hookedClasses = Collections.newSetFromMap(ConcurrentHashMap<Class<*>, Boolean>())
    private val holderRootFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderRootMissing = ConcurrentHashMap.newKeySet<Class<*>>()
    private val itemMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val itemListFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderTimeFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderAvatarFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderClickAreaFieldCache = ConcurrentHashMap<Class<*>, Field>()
    private val holderMainContainerMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val holderWithoutMainContainerMethod = ConcurrentHashMap.newKeySet<Class<*>>()
    private val holderContentFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val messageAccessorCache = ConcurrentHashMap<MessageAccessorKey, MessageAccessor>()
    private val nativeMessageClassCache = ConcurrentHashMap<Class<*>, Boolean>()
    private val messageNestedFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()
    private val messageDetailsFailureKeys = ConcurrentHashMap.newKeySet<String>()
    private val messageDetailsBindings = WeakHashMap<TextView, MessageDetailsBinding>()
    private val messageDetailsReservedPaddings = WeakHashMap<View, IntArray>()
    private val messageDetailsAttachCallbacks = MessageDetailsAttachQueue()
    private val messageDetailsRetryListeners = MessageDetailsPreDrawQueue()
    private val messageDetailsColorListeners = MessageDetailsPreDrawQueue()
    private val messageDetailsBindStates = ThreadLocal<ArrayDeque<MessageDetailsBindState>>()
    private val messageDetailsBindTokens = WeakHashMap<View, Any>()
    @Volatile private var messageDetailsConfig = readMessageDetailsConfig()
    @Volatile private var hideSelfAvatar = hideAvatarPrefs.getBoolean(
        HideChatAvatarSettings.KEY_HIDE_SELF,
        HideChatAvatarSettings.DEFAULT_HIDE_SELF
    )
    @Volatile private var hideOtherAvatar = hideAvatarPrefs.getBoolean(
        HideChatAvatarSettings.KEY_HIDE_OTHER,
        HideChatAvatarSettings.DEFAULT_HIDE_OTHER
    )
    private val forwardedRecordTypes = ForwardedRecordTypeLabels(
        context,
        enabled = { messageDetailsConfig.enabled && "type" in messageDetailsConfig.tokens },
        styleLabel = ::applyForwardedRecordLabel,
        logger = logger
    )
    private val messageDetailsPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in MESSAGE_DETAILS_CONFIG_KEYS) {
            messageDetailsConfig = readMessageDetailsConfig()
            forwardedRecordTypes.refresh()
        }
        when {
            key in MESSAGE_DETAILS_COLOR_KEYS -> refreshAttachedMessageDetailsLabels()
            key in MESSAGE_DETAILS_REBIND_KEYS -> refreshAttachedMessageDetailsLayouts()
        }
    }
    private val hideAvatarPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == HideChatAvatarSettings.KEY_HIDE_SELF || key == HideChatAvatarSettings.KEY_HIDE_OTHER) {
            hideSelfAvatar = hideAvatarPrefs.getBoolean(
                HideChatAvatarSettings.KEY_HIDE_SELF,
                HideChatAvatarSettings.DEFAULT_HIDE_SELF
            )
            hideOtherAvatar = hideAvatarPrefs.getBoolean(
                HideChatAvatarSettings.KEY_HIDE_OTHER,
                HideChatAvatarSettings.DEFAULT_HIDE_OTHER
            )
            refreshAttachedMessageDetailsLayouts()
        }
    }
    private var chattingDataAdapterClass: Class<*>? = null
    @Volatile private var injectingGroupHistory = false

    init {
        migrateMessageDetailsDefaultFormat()
        messageDetailsConfig = readMessageDetailsConfig()
        prefs.registerOnSharedPreferenceChangeListener(messageDetailsPrefsListener)
        hideAvatarPrefs.registerOnSharedPreferenceChangeListener(hideAvatarPrefsListener)
    }

    private fun migrateMessageDetailsDefaultFormat() {
        if (!prefs.contains(HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT)) return
        val stored = prefs.getString(HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT, null)
        if (stored == HchatExtraSettings.LEGACY_MESSAGE_DETAILS_FORMAT) {
            prefs.edit()
                .putString(
                    HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
                    HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT
                )
                .apply()
        }
    }

    private fun readMessageDetailsConfig(): MessageDetailsConfig {
        val format = prefs.getString(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT
        ).orEmpty().ifBlank { HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT }
        val timePattern = prefs.getString(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_TIME_FORMAT,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT
        ).orEmpty().ifBlank { HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT }
        val timeFormatter = runCatching { DateTimeFormatter.ofPattern(timePattern) }
            .getOrDefault(DEFAULT_MESSAGE_DETAILS_TIME_FORMATTER)
        val marginLeftDp = messageDetailsMargin(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_LEFT,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL
        )
        val marginTopDp = messageDetailsMargin(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_TOP,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL
        )
        val marginRightDp = messageDetailsMargin(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_RIGHT,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_HORIZONTAL
        )
        val marginBottomDp = messageDetailsMargin(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_BOTTOM,
            HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_MARGIN_VERTICAL
        )
        val lightText = messageDetailsTextColor(false)
        val darkText = messageDetailsTextColor(true)
        val lightBg = messageDetailsBgColor(false)
        val darkBg = messageDetailsBgColor(true)
        return MessageDetailsConfig(
            enabled = prefs.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS
            ),
            format = format,
            tokens = MESSAGE_DETAILS_TOKEN_PATTERN.findAll(format)
                .map { match -> match.groups[1]?.value ?: match.groups[2]?.value.orEmpty() }
                .toSet(),
            timeFormatter = timeFormatter,
            textSizeSp = prefs.getInt(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_TEXT_SIZE,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TEXT_SIZE
            ).coerceIn(
                HchatExtraSettings.MESSAGE_DETAILS_TEXT_SIZE_MIN,
                HchatExtraSettings.MESSAGE_DETAILS_TEXT_SIZE_MAX
            ).toFloat(),
            marginLeftDp = marginLeftDp,
            marginTopDp = marginTopDp,
            marginRightDp = marginRightDp,
            marginBottomDp = marginBottomDp,
            lightTextColor = resolveMessageDetailsColor(
                lightText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT,
                darkText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT,
                Color.RED
            ),
            darkTextColor = resolveMessageDetailsColor(
                darkText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT,
                lightText,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT,
                Color.RED
            ),
            lightBgColor = resolveMessageDetailsColor(
                lightBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG,
                darkBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG,
                Color.TRANSPARENT
            ),
            darkBgColor = resolveMessageDetailsColor(
                darkBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG,
                lightBg,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG,
                Color.TRANSPARENT
            )
        )
    }

    private fun messageDetailsMargin(key: String, default: Int): Int {
        return prefs.getInt(key, default).coerceIn(
            HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MIN,
            HchatExtraSettings.MESSAGE_DETAILS_MARGIN_MAX
        )
    }

    fun destroy() {
        forwardedRecordTypes.destroy()
        prefs.unregisterOnSharedPreferenceChangeListener(messageDetailsPrefsListener)
        hideAvatarPrefs.unregisterOnSharedPreferenceChangeListener(hideAvatarPrefsListener)
        val labels = synchronized(messageDetailsBindings) {
            messageDetailsBindings.keys.toList()
        }
        labels.forEach { removeMessageDetailsLabel(it) }
        val containers = synchronized(messageDetailsReservedPaddings) {
            messageDetailsReservedPaddings.keys.toList()
        }
        containers.forEach { restoreMessageDetailsPadding(it) }
        messageDetailsAttachCallbacks.clear()
        messageDetailsRetryListeners.clear()
        messageDetailsColorListeners.clear()
        synchronized(messageDetailsBindTokens) { messageDetailsBindTokens.clear() }
    }

    fun installForwardedRecordTypes(): Boolean = forwardedRecordTypes.install()

    fun install(): Boolean {
        var hooked = 0
        runCatching {
            if (installSkipWebRisk()) hooked++
            if (installRedPacketDetails()) hooked++
            if (installGroupMemberHistory()) hooked++
        }.onFailure {
            logger("Hchat扩展功能安装异常", it)
        }
        return hooked > 0
    }

    private fun enabled(key: String, def: Boolean): Boolean =
        prefs.getBoolean(key, def)

    private fun installSkipWebRisk(): Boolean {
        var hooked = false
        val intercept = locateMethod(
            "web_risk_intercept_enabled",
            listOf("MicroMsg.WebViewHighRiskAdH5Interceptor", "isInterceptEnabled, expt=")
        ) ?: return false
        hooked = hookOnce(intercept) { param ->
            if (enabled(HchatExtraSettings.KEY_SKIP_WEB_RISK, HchatExtraSettings.DEFAULT_SKIP_WEB_RISK)) {
                param.result = false
            }
        } || hooked
        val safe = locateMethodsInClass(
            "web_risk_url_safe",
            intercept.declaringClass,
            listOf("http", "https")
        ).firstOrNull { it.returnType == java.lang.Boolean.TYPE || it.returnType == java.lang.Boolean::class.java }
            ?: return hooked
        hooked = hookOnce(safe) { param ->
            if (enabled(HchatExtraSettings.KEY_SKIP_WEB_RISK, HchatExtraSettings.DEFAULT_SKIP_WEB_RISK)) {
                param.result = true
            }
        } || hooked
        return hooked
    }

    private fun installRedPacketDetails(): Boolean {
        var hooked = false
        locateRedPacketTimeFormatterMethod()?.let { method ->
            hooked = hookAfterOnce(method) { param ->
                if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookAfterOnce
                val timestamp = (param.args?.getOrNull(1) as? Number)?.toLong()
                    ?.takeIf { it in RED_PACKET_TIME_MILLIS_RANGE }
                    ?: return@hookAfterOnce
                param.result = formatRedPacketRecordTime(timestamp)
            } || hooked
        }
        val bindMethod = locateMethod(
            "lucky_money_detail_bind_direct",
            listOf("MicroMsg.LuckyMoneyDetailUI", "try get user contact: %s")
        )
        if (bindMethod != null) {
            hooked = hookAfterOnce(bindMethod) { param ->
                if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookAfterOnce
                bindRedPacketRecordTime(param.args?.getOrNull(0), param.args?.getOrNull(1))
            } || hooked
        }

        val classes = locateClasses(
            "lucky_money_scene_classes",
            listOf(
                listOf("MicroMsg.NetSceneOpenLuckyMoney", "/cgi-bin/mmpay-bin/openwxhb"),
                listOf("MicroMsg.NetSceneLuckyMoneyDetail", "/cgi-bin/mmpay-bin/qrydetailwxhb")
            )
        )
        classes.forEach { clazz ->
            KavaReflector.declaredMethods(clazz)
                .firstOrNull { method ->
                    method.name == "onGYNetEnd" &&
                        method.parameterTypes.size == 3 &&
                        method.parameterTypes[0] == Integer.TYPE &&
                        method.parameterTypes[1] == String::class.java &&
                        JSONObject::class.java.isAssignableFrom(method.parameterTypes[2])
                }?.let { method ->
                    hooked = hookOnce(method) { param ->
                        if (!enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) return@hookOnce
                        val json = param.args?.getOrNull(2) as? JSONObject ?: return@hookOnce
                        processRedPacketJson(json)
                    } || hooked
                }
        }
        return hooked
    }

    private fun locateRedPacketTimeFormatterMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), "lucky_money_time_formatter")
            ?.takeIf { isRedPacketTimeFormatterMethod(it) }
            ?.let { return it }
        val method = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    searchPackages("com.tencent.mm.plugin.luckymoney.model")
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(listOf("HH:mm"))
                        }
                    )
                }
            ).mapNotNull { it.toRuntimeMethodOrNull() }
                .firstOrNull { isRedPacketTimeFormatterMethod(it) }
        }.getOrElse {
            logger("红包详情时间格式化定位失败", it)
            null
        }
        if (method != null) DexMethodCache.save(methodPrefs, methodCacheKey, "lucky_money_time_formatter", method)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, "lucky_money_time_formatter")
        return method
    }

    private fun isRedPacketTimeFormatterMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == String::class.java &&
            types.size == 2 &&
            Context::class.java.isAssignableFrom(types[0]) &&
            types[1] == java.lang.Long.TYPE
    }

    private fun installGroupMemberHistory(): Boolean {
        var hooked = false
        val clazz = KavaReflector.loadClass("com.tencent.mm.plugin.profile.ui.ContactInfoUI", context.hostClassLoader())
            ?: return false
        val firstInstall = hookedClasses.add(clazz)
        KavaReflector.findMethodRecursive(clazz, "initView")?.let { method ->
            hooked = hookAfterOnce(method) { param ->
                val activity = param.thisObject as? Activity ?: return@hookAfterOnce
                if (!enabled(HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY, HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY)) return@hookAfterOnce
                installGroupMemberHistoryFromList(activity)
            } || hooked
        }
        methodsRecursive(clazz).firstOrNull { it.name == "onPreferenceTreeClick" }?.let { method ->
            hooked = hookOnce(method) { param ->
                val activity = param.thisObject as? Activity ?: return@hookOnce
                if (!enabled(HchatExtraSettings.KEY_GROUP_MEMBER_HISTORY, HchatExtraSettings.DEFAULT_GROUP_MEMBER_HISTORY)) return@hookOnce
                val pref = groupHistoryClickedPreference(param.args)
                    ?: return@hookOnce
                openGroupMemberHistory(activity)
                param.result = true
            } || hooked
        }
        if (!hooked && firstInstall) hookedClasses.remove(clazz)
        return hooked
    }

    fun installMessageDetails(): Boolean {
        val itemBind = locateMessageViewBindMethod()
        val adapterBinds = locateChattingAdapterBindMethods()
        val adapterClass = locateChattingDataAdapterClass()
        val binds = (listOfNotNull(itemBind) + adapterBinds).distinct()
        var complete = itemBind != null && adapterClass != null &&
            adapterBinds.any { it.parameterTypes.size == 2 } && adapterBinds.any { it.parameterTypes.size == 3 }
        for (bind in binds) {
            if (!hookedMethods.add(bind)) continue
            val installed = runCatching {
                HookRegistry.get().hook(bind, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // The payload method belongs to a shared base adapter. Only chat rows apply.
                        if (bind in adapterBinds && adapterClass?.isInstance(param.thisObject) != true) return
                        val state = runCatching {
                            captureMessageDetailsBindState(param.args)
                        }.getOrElse {
                                logger("消息显示时间绑定前状态读取失败", it)
                                MessageDetailsBindState(null)
                            }
                        val stack = messageDetailsBindStates.get()
                            ?: ArrayDeque<MessageDetailsBindState>().also(messageDetailsBindStates::set)
                        stack.addLast(state)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (bind in adapterBinds && adapterClass?.isInstance(param.thisObject) != true) return
                        val stack = messageDetailsBindStates.get()
                        val state = stack?.pollLast() ?: MessageDetailsBindState(null)
                        val outer = stack?.lastOrNull { state.root != null && it.root === state.root }
                        if (outer != null && state.message != null) outer.message = state.message
                        if (stack?.isEmpty() == true) messageDetailsBindStates.remove()
                        if (outer != null || !messageDetailsConfig.enabled || param.hasThrowable()) return
                        runCatching {
                            bindBottomMessageDetails(
                                param.thisObject, param.args, 0,
                                state.holder, state.message, state.root
                            )
                        }.onFailure { logger("消息显示时间绑定失败", it) }
                    }
                })
                true
            }.getOrElse {
                hookedMethods.remove(bind)
                logger("消息显示时间Hook安装失败: $bind", it)
                false
            }
            complete = installed && complete
        }
        return complete
    }

    private fun captureMessageDetailsBindState(args: Array<Any?>?): MessageDetailsBindState {
        val (adapterHolder, holderRoot) = messageBindHolderAndRoot(args)
            ?: return MessageDetailsBindState(null)
        if (isScrollingContainer(holderRoot)) return MessageDetailsBindState(null)
        val root = messageRowRoot(holderRoot)
        val holder = holderRoot.tag?.takeIf { isMessageDetailsHolder(it, root) }
        val state = MessageDetailsBindState(holder, root, resolveMessageFromBindArgs(args))
        // A full/payload adapter bind invokes the item hook synchronously. Clean each row
        // once and mount from its outermost hook after all native mutations finish.
        if (messageDetailsBindStates.get()?.any { it.root === root } == true) return state
        messageDetailsAttachCallbacks.cancel(root)
        synchronized(messageDetailsBindTokens) { messageDetailsBindTokens[root] = Any() }
        messageDetailsRetryListeners.cancel(root)
        removeMessageDetailsFromRoot(root)
        return state
    }

    private fun hookOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching { block(param) }.onFailure { logger("Hchat扩展Hook执行失败: ${method.name}", it) }
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("Hchat扩展Hook安装失败: ${method.name}", it)
            false
        }
    }

    private fun hookAfterOnce(method: Method, block: (XC_MethodHook.MethodHookParam) -> Unit): Boolean {
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { block(param) }.onFailure { logger("Hchat扩展Hook执行失败: ${method.name}", it) }
                }
            })
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger("Hchat扩展Hook安装失败: ${method.name}", it)
            false
        }
    }

    private fun locateMethod(cacheName: String, strings: List<String>, searchPackage: String? = null): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), cacheName)?.let { return it }
        val method = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    if (!searchPackage.isNullOrBlank()) searchPackages(searchPackage)
                    matcher(
                        MethodMatcher().apply {
                            usingStrings(strings)
                        }
                    )
                }
            ).firstNotNullOfOrNull { it.toRuntimeMethodOrNull() }
        }.getOrElse {
            logger("DexKit定位失败: $cacheName", it)
            null
        }
        if (method != null) DexMethodCache.save(methodPrefs, methodCacheKey, cacheName, method)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, cacheName)
        return method
    }

    private fun locateMessageViewBindMethod(): Method? {
        val methodCacheKey = methodCacheKey()
        DexMethodCache.load(methodPrefs, methodCacheKey, context.hostClassLoader(), "chat_message_view_bind")
            ?.takeIf { isMessageViewBindCandidate(it) }
            ?.let { return it }
        val matches = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(listOf("MicroMsg.MvvmChattingItem", "[onBindView]"))
                    })
                }
            ).mapNotNull { it.toRuntimeMethodOrNull() }
        }.getOrElse {
            logger("消息显示时间定位失败", it)
            emptyList()
        }
        val method = matches.firstOrNull { isMessageViewBindCandidate(it) }
        if (method != null) DexMethodCache.save(methodPrefs, methodCacheKey, "chat_message_view_bind", method)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, "chat_message_view_bind")
        return method
    }

    private fun locateChattingAdapterBindMethods(): List<Method> {
        val adapterClass = locateChattingDataAdapterClass() ?: return emptyList()
        val runtimeKey = methodCacheKey()
        val cacheName = "chat_adapter_message_binds_v3"
        fun isBind(method: Method): Boolean {
            val types = method.parameterTypes
            return !Modifier.isAbstract(method.modifiers) && !Modifier.isStatic(method.modifiers) &&
                method.returnType == Void.TYPE && method.declaringClass.isAssignableFrom(adapterClass) &&
                types.size in 2..3 && isLikelyViewHolderClass(types[0]) && types[1] == Integer.TYPE &&
                (types.size == 2 || types[2] == List::class.java)
        }
        val cached = DexMethodCache.loadList(methodPrefs, runtimeKey, context.hostClassLoader(), cacheName)
            .filter(::isBind)
        if (cached.any { it.parameterTypes.size == 2 } && cached.any { it.parameterTypes.size == 3 }) {
            return cached
        }
        val fullBinds = runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().apply {
                    declaredClass(adapterClass.name)
                    usingStrings(listOf("MicroMsg.ChattingDataAdapterV3", "_onBindViewHolder[", "msgInfo"))
                })
            }).mapNotNull { it.toRuntimeMethodOrNull() }
                .filter { isBind(it) && it.parameterTypes.size == 2 }
        }.getOrElse {
            logger("聊天适配器完整绑定定位失败", it)
            emptyList()
        }
        // Pair the verified full binder's exact holder type with (holder, int, List).
        // This excludes bridge holders and unrelated shared-adapter callbacks.
        val payloadBinds = methodsRecursive(adapterClass).filter { method ->
            isBind(method) && method.parameterTypes.size == 3 &&
                fullBinds.any { it.parameterTypes[0] == method.parameterTypes[0] }
        }.distinctBy { it.name to it.parameterTypes.toList() }
        val binds = (fullBinds + payloadBinds).distinct()
        if (fullBinds.isNotEmpty() && payloadBinds.isNotEmpty()) {
            DexMethodCache.saveList(methodPrefs, runtimeKey, cacheName, binds)
        } else {
            DexMethodCache.clear(methodPrefs, runtimeKey, cacheName)
        }
        return binds
    }

    private fun locateChattingDataAdapterClass(): Class<*>? {
        chattingDataAdapterClass?.let { return it }
        val methodCacheKey = methodCacheKey()
        val prefs = DexMethodCache.prefs(context.hostContext(), "Hchat_extra_class_cache")
        val key = HchatExtraCacheKey.forLookup(methodCacheKey, "chatting_data_adapter")
        key?.let { prefs.getString(it, null) }?.let { name ->
            KavaReflector.loadClass(name, context.hostClassLoader())?.let {
                chattingDataAdapterClass = it
                return it
            }
        }
        val clazz = runCatching {
            context.dexKitBridge().findClass(
                FindClass().apply {
                    matcher(ClassMatcher().apply {
                        usingStrings(listOf("MicroMsg.ChattingDataAdapterV3", "[handleMsgChange] isLockNotify:"))
                    })
                }
            ).firstOrNull()?.let { KavaReflector.loadClass(it.name, context.hostClassLoader()) }
        }.getOrElse {
            logger("Hchat聊天消息Adapter定位失败", it)
            null
        }
        if (clazz != null) {
            chattingDataAdapterClass = clazz
            key?.let { prefs.edit().putString(it, clazz.name).apply() }
        } else if (key != null) {
            prefs.edit().remove(key).apply()
        }
        return clazz
    }

    private fun isMessageViewBindCandidate(method: Method): Boolean {
        val types = method.parameterTypes
        return types.size >= 3 && types.any { it == Integer.TYPE || it == java.lang.Integer::class.java } &&
            types.any { isLikelyViewHolderClass(it) }
    }

    private fun isLikelyViewHolderClass(clazz: Class<*>?): Boolean {
        if (clazz == null) return false
        if (findRootField(clazz) != null) return true
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (KavaReflector.declaredFields(current).any { it.type == View::class.java }) return true
            current = current.superclass
        }
        return false
    }

    private fun locateMethodsInClass(cacheName: String, clazz: Class<*>, strings: List<String>): List<Method> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.loadList(methodPrefs, methodCacheKey, context.hostClassLoader(), cacheName)
            .filter { it.declaringClass == clazz }
        if (cached.isNotEmpty()) return cached
        val methods = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            declaredClass(clazz.name)
                            usingStrings(strings)
                        }
                    )
                }
            ).mapNotNull { it.toRuntimeMethodOrNull() }
        }.getOrElse {
            logger("DexKit定位失败: $cacheName", it)
            emptyList()
        }
        if (methods.isNotEmpty()) DexMethodCache.saveList(methodPrefs, methodCacheKey, cacheName, methods)
        else DexMethodCache.clear(methodPrefs, methodCacheKey, cacheName)
        return methods
    }

    private fun MethodData.toRuntimeMethodOrNull(): Method? {
        return runCatching { getMethodInstance(context.hostClassLoader()) }.getOrNull()
    }

    private fun locateClasses(cacheName: String, stringGroups: List<List<String>>): List<Class<*>> {
        val methodCacheKey = methodCacheKey()
        val cached = DexMethodCache.prefs(context.hostContext(), "Hchat_extra_class_cache")
        val classes = linkedSetOf<Class<*>>()
        stringGroups.forEachIndexed { index, strings ->
            val key = "${cacheName}_$index"
            val preferenceKey = HchatExtraCacheKey.forLookup(methodCacheKey, key)
            preferenceKey?.let { cached.getString(it, null) }?.let { name ->
                KavaReflector.loadClass(name, context.hostClassLoader())?.let { classes += it }
            }
            if (classes.size > index) return@forEachIndexed
            val clazz = runCatching {
                context.dexKitBridge().findClass(
                    FindClass().apply {
                        matcher(ClassMatcher().apply { usingStrings(strings) })
                    }
                ).firstOrNull()?.let { KavaReflector.loadClass(it.name, context.hostClassLoader()) }
            }.getOrElse {
                logger("DexKit定位类失败: $key", it)
                null
            }
            if (clazz != null) {
                classes += clazz
                preferenceKey?.let { cached.edit().putString(it, clazz.name).apply() }
            } else if (preferenceKey != null) {
                cached.edit().remove(preferenceKey).apply()
            }
        }
        return classes.toList()
    }

    private fun methodCacheKey(): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun bindRedPacketRecordTime(holder: Any?, record: Any?) {
        val timestamp = getTimestampFromRecord(record) ?: return
        val text = formatRedPacketRecordTime(timestamp)
        var applied = setRedPacketRecordTimeByHolder(holder, text)
        val textView = getRedPacketRecordTimeTextView(holder, timestamp)
        if (textView != null) {
            textView.text = text
            textView.post {
                if (enabled(HchatExtraSettings.KEY_RED_PACKET_DETAILS, HchatExtraSettings.DEFAULT_RED_PACKET_DETAILS)) {
                    textView.text = text
                }
            }
            applied = true
        }
        if (!applied) {
            val itemView = holder?.let { findRootView(it) }
            itemView?.post {
                setRedPacketRecordTimeByHolder(holder, text)
                getRedPacketRecordTimeTextView(holder, timestamp)?.text = text
            }
        }
    }

    /** ofPattern 每次都要解析一遍 pattern，红包列表滚动时不该重复付这个成本。 */
    private fun timeFormatter(pattern: String): DateTimeFormatter =
        timeFormatterCache.getOrPut(pattern) { DateTimeFormatter.ofPattern(pattern) }

    private fun formatRedPacketRecordTime(timestamp: Long): String {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        val today = LocalDate.now()
        val pattern = when {
            localDateTime.toLocalDate() == today -> "HH:mm:ss"
            localDateTime.year == today.year -> "M月d日 HH:mm:ss"
            else -> "yyyy年M月d日 HH:mm:ss"
        }
        return localDateTime.format(timeFormatter(pattern))
    }

    private fun getRedPacketRecordTimeTextView(holder: Any?, timestamp: Long): TextView? {
        findRedPacketRecordTimeTextViewByHolder(holder)?.let { return it }
        val itemView = holder?.let { findRootView(it) } as? ViewGroup ?: return null
        findRedPacketRecordTimeTextViewById(itemView)?.let { return it }
        findRedPacketRecordTimeTextViewByText(itemView, timestamp)?.let { return it }
        return findViewByChildIndexes(itemView, 0, 1, 1, 1, 1) as? TextView
    }

    private fun setRedPacketRecordTimeByHolder(holder: Any?, text: String): Boolean {
        if (holder == null) return false
        val resId = redPacketRecordTimeResId() ?: return false
        var ok = false
        methodsRecursive(holder.javaClass).forEach { method ->
            if (method.parameterTypes.size != 2 ||
                method.parameterTypes[0] != Integer.TYPE ||
                !method.parameterTypes[1].isAssignableFrom(String::class.java)
            ) {
                return@forEach
            }
            val result = runCatching {
                method.isAccessible = true
                method.invoke(holder, resId, text)
                true
            }.getOrDefault(false)
            if (result) ok = true
        }
        return ok
    }

    private fun findRedPacketRecordTimeTextViewByHolder(holder: Any?): TextView? {
        if (holder == null) return null
        val resId = redPacketRecordTimeResId() ?: return null
        return methodsRecursive(holder.javaClass).firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size != 1 ||
                method.parameterTypes[0] != Integer.TYPE ||
                !View::class.java.isAssignableFrom(method.returnType)
            ) {
                return@firstNotNullOfOrNull null
            }
            runCatching {
                method.isAccessible = true
                method.invoke(holder, resId) as? TextView
            }.getOrNull()
        }
    }

    private fun redPacketRecordTimeResId(): Int? {
        return runCatching {
            context.hostContext().resources.getIdentifier(
                RED_PACKET_RECORD_TIME_ID_NAME,
                "id",
                context.hostContext().packageName
            ).takeIf { it != 0 }
        }.getOrNull()
    }

    private fun findRedPacketRecordTimeTextViewById(root: ViewGroup): TextView? {
        return runCatching {
            val resId = root.resources.getIdentifier(
                RED_PACKET_RECORD_TIME_ID_NAME,
                "id",
                root.context.packageName
            )
            if (resId == 0) null else root.findViewById<TextView>(resId)
        }.getOrNull()
    }

    private fun findRedPacketRecordTimeTextViewByText(root: View, timestamp: Long): TextView? {
        val candidates = redPacketRecordTimeWithoutSecondsCandidates(timestamp)
            .map { normalizeRedPacketTimeText(it) }
            .filter { it.isNotEmpty() }
            .toSet()
        if (candidates.isEmpty()) return null
        val shortTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
            .format(timeFormatter("HH:mm"))
        return findTextViewRecursive(root) { textView ->
            val text = normalizeRedPacketTimeText(textView.text)
            text in candidates || (
                text.endsWith(shortTime) &&
                    text.length <= 20 &&
                    !text.contains("元") &&
                    !text.contains("/")
                )
        }
    }

    private fun redPacketRecordTimeWithoutSecondsCandidates(timestamp: Long): Set<String> {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
        val today = LocalDate.now()
        val patterns = linkedSetOf("HH:mm")
        if (localDateTime.toLocalDate() != today) {
            patterns += "M月d日 HH:mm"
            if (localDateTime.year != today.year) {
                patterns += "yyyy年M月d日 HH:mm"
            }
        }
        return patterns.mapTo(linkedSetOf()) {
            localDateTime.format(timeFormatter(it))
        }
    }

    private fun normalizeRedPacketTimeText(text: CharSequence?): String {
        return text?.toString()?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    }

    private fun findTextViewRecursive(root: View, predicate: (TextView) -> Boolean): TextView? {
        if (root is TextView && root.visibility == View.VISIBLE && predicate(root)) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findTextViewRecursive(root.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun findViewByChildIndexes(root: ViewGroup, vararg indexes: Int): View? {
        var current: View = root
        indexes.forEach { index ->
            val group = current as? ViewGroup ?: return null
            if (index < 0 || index >= group.childCount) return null
            current = group.getChildAt(index)
        }
        return current
    }

    private fun getTimestampFromRecord(record: Any?): Long? {
        return findTimestampInObject(record, 0, Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()))
    }

    private fun findTimestampInObject(value: Any?, depth: Int, visited: MutableSet<Any>): Long? {
        if (value == null || depth > 4 || !visited.add(value)) return null
        when (value) {
            is String -> return timestampFromString(value)
            is Number -> return timestampFromNumber(value)
        }
        val clazz = value.javaClass
        if (clazz.isPrimitive ||
            clazz.isArray ||
            clazz.name.startsWith("android.") ||
            clazz.name.startsWith("java.") ||
            clazz.name.startsWith("kotlin.")
        ) {
            return null
        }
        fieldsRecursive(clazz).forEach { field ->
            val fieldValue = KavaReflector.readField(field, value) ?: return@forEach
            when (fieldValue) {
                is String -> timestampFromString(fieldValue)?.let { return it }
                is Number -> timestampFromNumber(fieldValue)?.let { return it }
                else -> findTimestampInObject(fieldValue, depth + 1, visited)?.let { return it }
            }
        }
        return null
    }

    private fun timestampFromString(value: String): Long? {
        val clean = value.trim()
        if (clean.length == 10 && clean.all { it.isDigit() }) {
            return timestampFromSeconds(clean.toLongOrNull())
        }
        if (clean.length == 13 && clean.all { it.isDigit() }) {
            return clean.toLongOrNull()?.takeIf { it in RED_PACKET_TIME_MILLIS_RANGE }
        }
        return null
    }

    private fun timestampFromNumber(value: Number): Long? {
        val raw = value.toLong()
        if (raw in RED_PACKET_TIME_SECONDS_RANGE) return raw * 1000L
        if (raw in RED_PACKET_TIME_MILLIS_RANGE) return raw
        return null
    }

    private fun timestampFromSeconds(value: Long?): Long? {
        val seconds = value ?: return null
        return seconds.takeIf { it in RED_PACKET_TIME_SECONDS_RANGE }?.times(1000L)
    }

    private fun processRedPacketJson(json: JSONObject) {
        val totalAmount = json.optInt("totalAmount", 0)
        val totalNum = json.optInt("totalNum", 0)
        val recNum = json.optInt("recNum", 0)
        val recAmount = json.optInt("recAmount", 0)
        if (totalAmount <= 0 && totalNum <= 0) return
        val remaining = (totalAmount - recAmount) / 100.0
        val title = buildString {
            append("金额:").append(recAmount / 100.0).append('/').append(totalAmount / 100.0).append("元\n")
            append("数量:").append(recNum).append('/').append(totalNum)
            if (remaining > 0.0) append("\n剩余:").append(remaining).append("元")
        }
        json.put("headTitle", title)
    }

    private fun bindBottomMessageDetails(
        bindObject: Any?,
        args: Array<Any?>?,
        attempt: Int,
        capturedHolder: Any? = null,
        capturedMessage: Any? = null,
        capturedRoot: View? = null
    ): Boolean {
        // 绑定前状态里已经反射解析过 holder 与行根容器，命中时直接复用，
        // 不再为每条消息重复 findRootView / 父链遍历。重试路径仍走完整解析。
        val holder: Any
        val holderRoot: View
        if (capturedRoot != null) {
            holderRoot = capturedRoot
            // 捕获阶段 holder 可能没解析出来（行 tag 不是 holder），这不算失败，补一次解析。
            holder = capturedHolder
                ?: messageBindHolderAndRoot(args)?.first
                ?: return false
        } else {
            val resolved = messageBindHolderAndRoot(args) ?: return false
            holder = resolved.first
            holderRoot = resolved.second
            if (isScrollingContainer(holderRoot)) return false
        }
        val root = capturedRoot ?: messageRowRoot(holderRoot)
        messageDetailsRetryListeners.cancel(root)
        val nativeMessage = capturedMessage ?: resolveMessageFromBindArgs(args)
            ?: resolveMessageFromAdapter(bindObject, messageBindPosition(args))
        if (nativeMessage == null) {
            if (attempt >= MESSAGE_DETAILS_MAX_RETRY) {
                removeMessageDetailsFromRoot(root)
                logMessageDetailsFailure("message", holder, root)
            }
            scheduleMessageDetailsRetry(root, bindObject, args, attempt, capturedHolder)
            return false
        }
        val messageType = messageType(nativeMessage)
        if (!MessageDetailsVisibility.shouldShow(messageType)) {
            clearExcludedMessageDetails(root)
            return true
        }
        val details = messageDetails(nativeMessage, knownType = messageType)
        val viewTag = holderRoot.tag?.takeIf { isMessageDetailsHolder(it, root) }
            ?: capturedHolder?.takeIf { isMessageDetailsHolder(it, root) }
            ?: holder
        val label = findHolderTextView(viewTag, "timeTV", holderTimeFieldCache)
        val inserted = insertMessageDetailsLabel(root, label, viewTag, details, nativeMessage = nativeMessage)
        if (!inserted) {
            if (attempt >= MESSAGE_DETAILS_MAX_RETRY) {
                removeMessageDetailsFromRoot(root)
                logMessageDetailsFailure("layout", viewTag, root)
            }
            scheduleMessageDetailsRetry(root, bindObject, args, attempt, viewTag, nativeMessage)
        }
        return inserted
    }

    private fun resolveMessageFromBindArgs(args: Array<Any?>?): Any? {
        val item = args?.getOrNull(1) ?: return null
        // Adapter args[1] is a position; payloads and recycled holder fields are not messages.
        if (item is Number || item is Collection<*>) return null
        return resolveNativeMessage(item)
    }

    private fun logMessageDetailsFailure(reason: String, holder: Any, root: View) {
        val key = "$reason|${holder.javaClass.name}"
        if (!messageDetailsFailureKeys.add(key)) return
        val hasTime = findHolderTextView(holder, "timeTV", holderTimeFieldCache) != null
        val hasAvatar = findHolderAvatarView(holder) != null || findChattingAvatarView(root) != null
        val hasClickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache) != null
        logger(
            "消息详情绑定失败 reason=$reason holder=${holder.javaClass.name} " +
                "root=${root.javaClass.name} " +
                "time=$hasTime avatar=$hasAvatar clickArea=$hasClickArea",
            null
        )
    }

    private fun scheduleMessageDetailsRetry(
        root: View,
        bindObject: Any?,
        args: Array<Any?>?,
        attempt: Int,
        holder: Any?,
        nativeMessage: Any? = null
    ) {
        if (attempt >= MESSAGE_DETAILS_MAX_RETRY) return
        val nextAttempt = attempt + 1
        val token = messageDetailsBindToken(root)
        val bindArgs = args?.copyOf()
        messageDetailsRetryListeners.schedule(root, root) {
            if (isCurrentMessageDetailsBind(root, token)) {
                bindBottomMessageDetails(bindObject, bindArgs, nextAttempt, holder, nativeMessage)
            }
        }
    }

    private fun messageDetailsBindToken(root: View): Any = synchronized(messageDetailsBindTokens) {
        messageDetailsBindTokens.getOrPut(root) { Any() }
    }

    private fun isCurrentMessageDetailsBind(root: View, token: Any): Boolean =
        messageDetailsConfig.enabled && synchronized(messageDetailsBindTokens) {
            messageDetailsBindTokens[root] === token
        }

    private fun isMessageDetailsHolder(holder: Any, root: View): Boolean {
        val timeView = findHolderTextView(holder, "timeTV", holderTimeFieldCache) ?: return false
        return isViewWithinRoot(timeView, root)
    }

    private fun insertMessageDetailsLabel(
        root: View,
        nativeTimeLabel: TextView?,
        holder: Any,
        details: MessageDetails,
        preferredLabel: TextView? = null,
        nativeMessage: Any? = null
    ): Boolean {
        // Preference refreshes and deferred layout/attachment callbacks use this entry too.
        if (!MessageDetailsVisibility.shouldShow(details.type)) {
            clearExcludedMessageDetails(root)
            return true
        }
        val configuredAvatarHidden = configuredAvatarHidden(details.isSelf)
        val avatarAnchor = findAvatarDetailsAnchor(root, holder, configuredAvatarHidden)
        if (avatarAnchor == null) {
            removeMessageDetailsFromRoot(root)
            return false
        }
        val label = preferredLabel?.takeIf { it.tag == TAG_MESSAGE_DETAILS_VIEW }
            ?: attachedMessageDetailsLabel(root)
            ?: TextView(nativeTimeLabel?.context ?: root.context).also {
                it.tag = TAG_MESSAGE_DETAILS_VIEW
            }
        // 时间标签不再承载"点击查看详情"，避免拦截消息行的点击与长按。
        if (label.isClickable) {
            label.setOnClickListener(null)
            label.isClickable = false
        }
        configureMessageDetailsLabel(label)
        applyMessageDetailsLabel(label, details)
        val container = attachMessageDetailsLabel(
            root,
            avatarAnchor.positionView,
            nativeTimeLabel,
            label,
            details.isSelf
        )
            ?: run {
                removeMessageDetailsLabel(label)
                return false
            }
        rememberMessageDetailsBinding(label, root, nativeTimeLabel, holder, container, nativeMessage, details)
        observeMessageDetailsAttachment(root, label)
        return true
    }

    /** 取消息行里已经挂过的标签，复用同一实例，避免每次 bind 新建 TextView。 */
    private fun attachedMessageDetailsLabel(root: View): TextView? {
        val group = root as? ViewGroup ?: return null
        return group.findViewWithTag(TAG_MESSAGE_DETAILS_VIEW) as? TextView
    }

    private fun observeMessageDetailsAttachment(root: View, label: TextView) {
        val reference = WeakReference(label)
        val token = messageDetailsBindToken(root)
        messageDetailsAttachCallbacks.observe(root) { attached ->
            if (!isCurrentMessageDetailsBind(attached, token)) return@observe
            val currentLabel = reference.get() ?: return@observe
            val binding = synchronized(messageDetailsBindings) { messageDetailsBindings[currentLabel] }
                ?: return@observe
            if (binding.root !== attached) return@observe
            runCatching {
                insertMessageDetailsLabel(
                    attached, binding.nativeTimeLabel, binding.holder, binding.details,
                    preferredLabel = currentLabel, nativeMessage = binding.nativeMessage
                )
            }.onFailure { logger("消息详情重新附着校验失败", it) }
        }
    }

    private fun applyMessageDetailsLabel(label: TextView, details: MessageDetails) {
        val text = formatMessageDetailsLabel(details)
        if (!TextUtils.equals(label.text, text)) {
            label.text = text
        }
        applyMessageDetailsColors(label)
        if (label.alpha != 1f) label.alpha = 1f
        if (!label.includeFontPadding) label.includeFontPadding = true
        val horizontalPadding = dp(label.context, 4f)
        val verticalPadding = dp(label.context, 2f)
        if (label.paddingLeft != horizontalPadding || label.paddingTop != verticalPadding ||
            label.paddingRight != horizontalPadding || label.paddingBottom != verticalPadding
        ) {
            label.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }
        val textSize = messageDetailsConfig.textSizeSp
        if (kotlin.math.abs(label.textSize / label.resources.displayMetrics.scaledDensity - textSize) > 0.01f) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
        }
    }

    private fun applyForwardedRecordLabel(label: TextView, text: String) {
        if (!TextUtils.equals(label.text, text)) label.text = text
        applyMessageDetailsColors(label)
        label.alpha = 1f
        label.includeFontPadding = true
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, messageDetailsConfig.textSizeSp)
        label.setPadding(dp(label.context, 4f), dp(label.context, 2f), dp(label.context, 4f), dp(label.context, 2f))
    }

    private fun applyMessageDetailsColors(label: TextView) {
        val textColor = messageDetailsResolvedTextColor(label.context)
        if (label.currentTextColor != textColor) label.setTextColor(textColor)
        val bgColor = messageDetailsResolvedBgColor(label.context)
        val currentBgColor = (label.background as? ColorDrawable)?.color
        if (currentBgColor != bgColor && !(currentBgColor == null && bgColor == Color.TRANSPARENT)) {
            label.setBackgroundColor(bgColor)
        }
    }

    private fun rememberMessageDetailsBinding(
        label: TextView,
        root: View,
        nativeTimeLabel: TextView?,
        holder: Any,
        container: View?,
        nativeMessage: Any?,
        details: MessageDetails
    ) {
        synchronized(messageDetailsBindings) {
            messageDetailsBindings[label] =
                MessageDetailsBinding(root, nativeTimeLabel, holder, container, nativeMessage, details)
        }
    }

    private fun refreshAttachedMessageDetailsLabels() {
        val bindings = synchronized(messageDetailsBindings) {
            messageDetailsBindings.entries.map { it.key to it.value }
        }
        bindings.forEach { (label, binding) ->
            binding.root.post {
                if (label.parent === binding.root) applyMessageDetailsColors(label)
            }
        }
    }

    private fun refreshAttachedMessageDetailsLayouts() {
        val bindings = synchronized(messageDetailsBindings) {
            messageDetailsBindings.entries.map { it.key to it.value }
        }
        bindings.forEach { (label, binding) ->
            binding.root.post {
                val currentBinding = synchronized(messageDetailsBindings) { messageDetailsBindings[label] }
                if (currentBinding !== binding) return@post
                if (!messageDetailsConfig.enabled) {
                    messageDetailsAttachCallbacks.cancel(binding.root)
                    messageDetailsRetryListeners.cancel(binding.root)
                    removeMessageDetailsLabel(label)
                    return@post
                }
                if (label.parent === binding.root) {
                    val details = binding.nativeMessage?.let { messageDetails(it) } ?: binding.details
                    insertMessageDetailsLabel(
                        binding.root,
                        binding.nativeTimeLabel,
                        binding.holder,
                        details,
                        preferredLabel = label,
                        nativeMessage = binding.nativeMessage
                    )
                }
            }
        }
    }

    /**
     * 时间仍由 RelativeLayout 布局，避免恢复成预绘制坐标计算造成闪屏。
     * 锚点使用头像在消息行根容器中的直接子 View，贴着头像上/下边界显示。
     */
    private fun attachMessageDetailsLabel(
        root: View,
        positionView: View,
        nativeTimeLabel: TextView?,
        label: TextView,
        isSelf: Boolean
    ): View? {
        val relativeRoot = root as? RelativeLayout ?: return null
        val anchor = directChildWithin(relativeRoot, positionView) ?: return null
        val timeAnchor = nativeTimeLabel
            ?.takeIf { isViewWithinRoot(it, relativeRoot) }
            ?.let { directChildWithin(relativeRoot, it) }
            ?.takeIf { it !== anchor }
        if (label.parent !== relativeRoot) {
            (label.parent as? ViewGroup)?.removeView(label)
            relativeRoot.addView(label)
        }
        val anchorId = ensureViewId(anchor)
        val timeAnchorId = timeAnchor?.let(::ensureViewId)
        // 布局规则、间距、字号都没变时复用上次结果：setLayoutParams 会向整行请求重新布局，
        // 滚动时每条消息都设一次会把列表变成持续的测量/布局。
        var signature = anchorId
        signature = signature * 31 + (timeAnchorId ?: 0)
        signature = signature * 31 + if (isSelf) 1 else 0
        signature = signature * 31 + messageDetailsConfig.marginLeftDp
        signature = signature * 31 + messageDetailsConfig.marginTopDp
        signature = signature * 31 + messageDetailsConfig.marginRightDp
        signature = signature * 31 + messageDetailsConfig.marginBottomDp
        signature = signature * 31 + (messageDetailsConfig.textSizeSp * 100).toInt()
        signature = signature * 31 + (label.resources.displayMetrics.scaledDensity * 100).toInt()
        if ((label.getTag(TAG_MESSAGE_DETAILS_LAYOUT) as? Int) == signature) {
            applyMessageDetailsReservedPadding(anchor, label.measuredHeight)
            return anchor
        }
        label.setTag(TAG_MESSAGE_DETAILS_LAYOUT, signature)
        val params = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT,
            RelativeLayout.LayoutParams.WRAP_CONTENT
        )
        // 与 XChat 一致：优先接在微信原时间下方；无可用时间锚点时贴头像容器顶部。
        params.addRule(
            if (timeAnchor != null) RelativeLayout.BELOW else RelativeLayout.ALIGN_TOP,
            timeAnchorId ?: anchorId
        )
        params.addRule(
            if (isSelf) RelativeLayout.ALIGN_RIGHT else RelativeLayout.ALIGN_LEFT,
            anchorId
        )
        params.leftMargin = dp(relativeRoot.context, messageDetailsConfig.marginLeftDp.toFloat())
        params.topMargin = dp(relativeRoot.context, messageDetailsConfig.marginTopDp.toFloat())
        params.rightMargin = dp(relativeRoot.context, messageDetailsConfig.marginRightDp.toFloat())
        params.bottomMargin = dp(relativeRoot.context, messageDetailsConfig.marginBottomDp.toFloat())
        label.layoutParams = params
        label.gravity = Gravity.CENTER
        label.includeFontPadding = false
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        applyMessageDetailsReservedPadding(anchor, label.measuredHeight)
        return anchor
    }

    /** RelativeLayout 的相对规则必须引用根容器的直接子 View。 */
    private fun directChildWithin(root: ViewGroup, view: View): View? {
        if (view === root || !isViewWithinRoot(view, root)) return null
        var current = view
        while (current.parent !== root) {
            current = current.parent as? View ?: return null
        }
        return current
    }

    private fun ensureViewId(view: View): Int {
        if (view.id == View.NO_ID) view.id = View.generateViewId()
        return view.id
    }

    /** 只补偿头像容器的顶部空间，避免撑高整条消息；回收时恢复原始 padding。 */
    private fun applyMessageDetailsReservedPadding(container: View, labelHeight: Int) {
        if (labelHeight <= 0) return
        val original = synchronized(messageDetailsReservedPaddings) {
            messageDetailsReservedPaddings.getOrPut(container) {
                intArrayOf(
                    container.paddingLeft,
                    container.paddingTop,
                    container.paddingRight,
                    container.paddingBottom
                )
            }
        }
        val verticalMargin = dp(
            container.context,
            (messageDetailsConfig.marginTopDp + messageDetailsConfig.marginBottomDp).toFloat()
        )
        val reserved = (
            labelHeight + verticalMargin - dp(container.context, MESSAGE_DETAILS_PADDING_OVERLAP_DP)
            ).coerceAtLeast(0)
        val top = maxOf(original[1], reserved)
        if (container.paddingLeft == original[0] && container.paddingTop == top &&
            container.paddingRight == original[2] && container.paddingBottom == original[3]
        ) {
            return
        }
        container.setPadding(original[0], top, original[2], original[3])
    }

    private fun restoreMessageDetailsPadding(container: View?) {
        if (container == null) return
        val original = synchronized(messageDetailsReservedPaddings) {
            messageDetailsReservedPaddings.remove(container)
        } ?: return
        if (container.paddingLeft == original[0] && container.paddingTop == original[1] &&
            container.paddingRight == original[2] && container.paddingBottom == original[3]
        ) {
            return
        }
        container.setPadding(original[0], original[1], original[2], original[3])
    }

    private fun removeMessageDetailsFromRoot(root: View) {
        // 常规路径：标签就挂在行根容器里，按子树找一次即可，
        // 不必为每条消息遍历整张绑定表（滚动时那是几十到上百次比较）。
        var removed = false
        if (root is ViewGroup) {
            while (true) {
                val label = root.findViewWithTag(TAG_MESSAGE_DETAILS_VIEW) as? TextView ?: break
                removeMessageDetailsLabel(label)
                removed = true
            }
        }
        if (removed) return
        // 兜底：标签被微信挪到行外时只按绑定表清理。
        val labels = synchronized(messageDetailsBindings) {
            messageDetailsBindings.entries.filter { it.value.root === root }.map { it.key }
        }
        labels.forEach { removeMessageDetailsLabel(it) }
    }

    private fun isViewWithinRoot(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    /** 时间标签固定单行；文本过长时省略，避免撑开消息行。 */
    private fun configureMessageDetailsLabel(label: TextView) {
        if (label.maxLines != 1) {
            label.setSingleLine(true)
            label.maxLines = 1
            label.setHorizontallyScrolling(false)
        }
        if (label.ellipsize != TextUtils.TruncateAt.END) label.ellipsize = TextUtils.TruncateAt.END
        if (label.minWidth != 0) label.minWidth = 0
        if (label.maxWidth != Int.MAX_VALUE) label.maxWidth = Int.MAX_VALUE
    }

    private fun messageBindPosition(args: Array<Any?>?): Int? {
        args ?: return null
        return args.getOrNull(2) as? Int
            ?: args.firstOrNull { it is Int } as? Int
    }

    private fun messageBindHolder(args: Array<Any?>?): Any? = messageBindHolderAndRoot(args)?.first

    /** 解析 holder 时顺手带出行根容器，避免对同一对象再反射一次。 */
    private fun messageBindHolderAndRoot(args: Array<Any?>?): Pair<Any, View>? {
        args ?: return null
        args.getOrNull(0)?.let { first -> findRootView(first)?.let { return first to it } }
        for (arg in args) {
            if (arg == null) continue
            findRootView(arg)?.let { return arg to it }
        }
        return null
    }

    private fun resolveMessageFromAdapter(bindObject: Any?, position: Int?): Any? {
        if (bindObject == null || position == null) return null
        val adapter = chattingAdapterFromBindObject(bindObject) ?: return null
        return resolveNativeMessage(adapterItem(adapter, position))
    }

    private fun chattingAdapterFromBindObject(source: Any): Any? {
        val adapterClass = locateChattingDataAdapterClass()
        if (adapterClass?.isInstance(source) == true) return source
        itemListFieldCache[source.javaClass]?.let { field ->
            KavaReflector.readField(field, source)?.let { return it }
        }
        var current: Class<*>? = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                if (adapterClass != null && !adapterClass.isAssignableFrom(field.type)) continue
                val value = KavaReflector.readField(field, source) ?: continue
                if (hasGetItemMethod(value.javaClass)) {
                    itemListFieldCache[source.javaClass] = field
                    return value
                }
            }
            current = current.superclass
        }
        if (adapterClass != null) return null
        current = source.javaClass
        while (current != null && current != Any::class.java) {
            for (field in KavaReflector.declaredFields(current)) {
                val value = KavaReflector.readField(field, source) ?: continue
                if (hasGetItemMethod(value.javaClass)) {
                    itemListFieldCache[source.javaClass] = field
                    return value
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun hasGetItemMethod(clazz: Class<*>): Boolean {
        return methodsRecursive(clazz).any { method ->
            method.name == "getItem" &&
                method.parameterTypes.size == 1 &&
                (method.parameterTypes[0] == Integer.TYPE || method.parameterTypes[0] == java.lang.Integer::class.java)
        }
    }

    private fun findRootView(holder: Any): View? {
        val clazz = holder.javaClass
        holderRootFieldCache[clazz]?.let { return KavaReflector.readField(it, holder) as? View }
        if (holderRootMissing.contains(clazz)) return null
        // 首条消息付一次按名字查字段的代价，之后直接走缓存的 Field.get。
        (KavaReflector.readField(holder, "itemView") as? View)?.let { view ->
            findItemViewField(clazz)?.let { field -> holderRootFieldCache[clazz] = field }
            return view
        }
        val field = findRootField(clazz)
        if (field == null) {
            holderRootMissing.add(clazz)
            return null
        }
        holderRootFieldCache[clazz] = field
        return KavaReflector.readField(field, holder) as? View
    }

    private fun findItemViewField(clazz: Class<*>): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            KavaReflector.declaredFields(current).firstOrNull { it.name == "itemView" }?.let { return it }
            current = current.superclass
        }
        return null
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

    private fun findHolderTextView(holder: Any, fieldName: String, cache: ConcurrentHashMap<Class<*>, Field>): TextView? {
        cache[holder.javaClass]?.let { return KavaReflector.readField(it, holder) as? TextView }
        val field = fieldsRecursive(holder.javaClass).firstOrNull {
            it.name == fieldName && TextView::class.java.isAssignableFrom(it.type)
        } ?: return null
        cache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? TextView
    }

    private fun findHolderView(
        holder: Any,
        fieldName: String,
        cache: ConcurrentHashMap<Class<*>, Field>
    ): View? {
        cache[holder.javaClass]?.let { return KavaReflector.readField(it, holder) as? View }
        val field = fieldsRecursive(holder.javaClass).firstOrNull {
            it.name == fieldName && View::class.java.isAssignableFrom(it.type)
        } ?: return null
        cache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? View
    }

    private fun findHolderAvatarView(holder: Any): View? {
        holderAvatarFieldCache[holder.javaClass]?.let {
            return KavaReflector.readField(it, holder) as? View
        }
        val fields = fieldsRecursive(holder.javaClass)
            .filter { View::class.java.isAssignableFrom(it.type) }
        val field = fields.firstOrNull { it.name.equals("avatarIV", ignoreCase = true) }
            ?: fields.firstOrNull { it.name.contains("avatar", ignoreCase = true) }
            ?: return null
        holderAvatarFieldCache[holder.javaClass] = field
        return KavaReflector.readField(field, holder) as? View
    }

    private fun findAvatarDetailsAnchor(
        root: View,
        holder: Any,
        expectedHidden: Boolean = false
    ): AvatarDetailsAnchor? {
        // Some message holders keep several avatar fields and reuse the same holder class.
        // Prefer the avatar that is actually attached to this message row so a cached field
        // from another layout variant cannot move the label to a different horizontal anchor.
        val holderAvatar = findHolderAvatarView(holder)
            ?.takeIf { isViewWithinRoot(it, root) }
        val avatar = holderAvatar?.takeIf {
            it.javaClass.name == CHATTING_AVATAR_VIEW_CLASS &&
                (expectedHidden || isVisibleAvatar(it))
        } ?: findChattingAvatarView(root)
            ?: holderAvatar
            ?: return null
        var child = avatar
        var relativeParent: RelativeLayout? = null
        var mask: View? = null
        val directParent = avatar.parent as? View
        while (child !== root) {
            val parent = child.parent as? ViewGroup ?: return null
            if (parent.javaClass.name == MASK_LAYOUT_CLASS) {
                mask = parent
            }
            if (parent is RelativeLayout) {
                relativeParent = parent
            }
            if (parent === root) {
                val targetParent = relativeParent ?: return null
                val hidden = expectedHidden || avatar.visibility != View.VISIBLE || mask?.layoutParams?.width == 0
                val positionView = if (hidden) mask ?: directParent ?: avatar else avatar
                return AvatarDetailsAnchor(targetParent, positionView, hidden)
            }
            if (isScrollingContainer(parent)) return null
            child = parent
        }
        return null
    }

    private fun findChattingAvatarView(root: View): View? {
        findChattingAvatarView(root, visibleOnly = true)?.let { return it }
        return findChattingAvatarView(root, visibleOnly = false)
    }

    private fun findChattingAvatarView(root: View, visibleOnly: Boolean): View? {
        if (root.javaClass.name == CHATTING_AVATAR_VIEW_CLASS && (!visibleOnly || isVisibleAvatar(root))) {
            return root
        }
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findChattingAvatarView(root.getChildAt(index), visibleOnly)?.let { return it }
        }
        return null
    }

    private fun isVisibleAvatar(view: View): Boolean {
        return view.visibility == View.VISIBLE &&
            (view.width > 0 || view.measuredWidth > 0) &&
            (view.height > 0 || view.measuredHeight > 0)
    }

    private fun isScrollingContainer(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("RecyclerView") || name.contains("ListView") || name.contains("ScrollView")
    }

    private fun clearExcludedMessageDetails(root: View) {
        messageDetailsAttachCallbacks.cancel(root)
        messageDetailsRetryListeners.cancel(root)
        synchronized(messageDetailsBindTokens) { messageDetailsBindTokens[root] = Any() }
        removeMessageDetailsFromRoot(root)
    }

    private fun removeMessageDetailsLabel(label: TextView) {
        messageDetailsColorListeners.cancel(label)
        val binding = synchronized(messageDetailsBindings) { messageDetailsBindings.remove(label) }
        (label.parent as? ViewGroup)?.removeView(label)
        if (label.tag == TAG_MESSAGE_DETAILS_VIEW) label.tag = null
        restoreMessageDetailsPadding(binding?.container)
    }

    private fun adapterItem(adapter: Any, position: Int): Any? {
        if (position < 0) return null
        itemMethodCache[adapter.javaClass]?.let { return KavaReflector.invoke(it, adapter, position) }
        var current: Class<*>? = adapter.javaClass
        while (current != null && current != Any::class.java) {
            val method = KavaReflector.declaredMethods(current).firstOrNull {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0] == Integer.TYPE || it.parameterTypes[0] == Int::class.java) &&
                    (it.name == "J0" || it.name == "getItem" || it.name == "get")
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
        return findNestedListItem(
            adapter,
            position,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            0
        )
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
        for (field in messageNestedFields(source.javaClass)) {
            val value = KavaReflector.readField(field, source) ?: continue
            findNestedListItem(value, position, visited, depth + 1)?.let { return it }
        }
        return null
    }

    private fun resolveNativeMessage(source: Any?): Any? {
        if (source == null) return null
        if (isLikelyNativeMessage(source) && messageId(source) > 0L) return source
        return resolveNativeMessage(
            source,
            Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()),
            0
        )
    }

    private fun resolveNativeMessage(source: Any?, visited: MutableSet<Any>, depth: Int): Any? {
        if (source == null || depth > 4 || !visited.add(source)) return null
        val className = source.javaClass.name
        if (isLikelyNativeMessage(source) && messageId(source) > 0L) return source
        if (className.startsWith("java.") || className.startsWith("android.")) return null
        if (source is View || source is ViewGroup) return null
        if (source is Collection<*>) {
            for (item in source) resolveNativeMessage(item, visited, depth + 1)?.let { return it }
            return null
        }
        for (field in messageNestedFields(source.javaClass)) {
            val value = KavaReflector.readField(field, source) ?: continue
            resolveNativeMessage(value, visited, depth + 1)?.let { return it }
        }
        return null
    }

    private fun messageNestedFields(type: Class<*>): List<Field> {
        return messageNestedFieldCache[type] ?: fieldsRecursive(type)
            .filter { field ->
                val fieldType = field.type
                !fieldType.isPrimitive &&
                    !fieldType.isArray &&
                    fieldType != String::class.java &&
                    !Number::class.java.isAssignableFrom(fieldType)
            }
            .also { messageNestedFieldCache.putIfAbsent(type, it) }
    }

    private fun isLikelyNativeMessage(value: Any): Boolean {
        val clazz = value.javaClass
        nativeMessageClassCache[clazz]?.let { return it }
        val result = KavaReflector.findFieldRecursive(clazz, "field_msgId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_msgSvrId") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_type") != null &&
            KavaReflector.findFieldRecursive(clazz, "field_content") != null
        nativeMessageClassCache.putIfAbsent(clazz, result)
        return result
    }

    private fun messageId(message: Any): Long {
        parseLong(readMessageValue(message, "getMsgId", "field_msgId", "msgId"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "getMsgID", "msgID", "id"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "getId", "id", "field_msgId"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "", "field_msgId", "msgId"))
            ?.takeIf { it > 0L }?.let { return it }
        parseLong(readMessageValue(message, "", "msgID", "id"))
            ?.takeIf { it > 0L }?.let { return it }
        return 0L
    }

    private fun installGroupMemberHistoryFromList(activity: Activity) {
        val listView = findListView(activity.window?.decorView as? ViewGroup)
        val adapter = listView?.adapter
        if (adapter != null) {
            if (injectGroupMemberHistoryPreference(activity, adapter)) return
        }
        injectGroupMemberHistoryPreference(activity, null)
    }

    private fun injectGroupMemberHistoryPreference(activity: Activity, adapter: Any?): Boolean {
        val groupId = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (!groupId.endsWith("@chatroom") && !groupId.endsWith("@im.chatroom")) return false
        if (!isGroupMemberProfile(activity, groupId)) return false
        val memberId = currentWxId(activity) ?: return false
        if (memberId.endsWith("@chatroom") || memberId == groupId) return false
        if (adapter != null && injectPreferenceByAdapter(activity, adapter, PREF_GROUP_MEMBER_HISTORY, "历史发言记录")) return true
        val screen = KavaReflector.invokeMethod(activity, "getPreferenceScreen") ?: return false
        val existing = findPreference(screen, PREF_GROUP_MEMBER_HISTORY)
        if (existing != null) {
            setGroupHistoryPreferenceClick(existing, activity)
            notifyPreferenceChanged(screen)
            return true
        }
        val prefClass = KavaReflector.loadClass("com.tencent.mm.ui.base.preference.Preference", context.hostClassLoader())
            ?: return false
        val pref = KavaReflector.newInstance(KavaReflector.findConstructor(prefClass, Context::class.java), activity)
            ?: return false
        setPreferenceKey(pref, PREF_GROUP_MEMBER_HISTORY)
        setPreferenceTitle(pref, "历史发言记录")
        setGroupHistoryPreferenceClick(pref, activity)
        val added = addPreference(screen, pref, screenInsertionIndex(screen))
        if (added) notifyPreferenceChanged(screen)
        return added
    }

    private fun groupHistoryClickedPreference(args: Array<Any?>?): Any? {
        val pref = args?.getOrNull(1)?.takeIf { isGroupHistoryPreference(it) }
        if (pref != null) return pref
        return args?.firstOrNull { isGroupHistoryPreference(it) }
    }

    private fun openGroupMemberHistory(activity: Activity) {
        val groupId = WeChatApis.chatPage()?.currentTalker().orEmpty()
        if (!groupId.endsWith("@chatroom") && !groupId.endsWith("@im.chatroom")) return
        val memberId = currentWxId(activity) ?: return
        if (memberId.endsWith("@chatroom") || memberId == groupId) return
        val clazz = KavaReflector.loadClass("com.tencent.mm.chatroom.ui.SelectedMemberChattingRecordUI", context.hostClassLoader())
            ?: return
        activity.startActivity(Intent(activity, clazz).apply {
            putExtra("RoomInfo_Id", groupId)
            putExtra("room_member", memberId)
            putExtra("title", "查看群成员消息历史")
        })
    }

    private fun currentWxId(activity: Activity): String? {
        val intent = activity.intent ?: return null
        return listOf(
            "Contact_User",
            "RoomInfo_Id",
            "room_name",
            "Contact_ChatRoomId",
            "Chat_User"
        ).firstNotNullOfOrNull { key ->
            intent.getStringExtra(key)?.takeIf { it.isNotBlank() }
        }
    }

    private fun isGroupMemberProfile(activity: Activity, groupId: String): Boolean {
        val intent = activity.intent ?: return false
        if (intent.getStringExtra("Contact_ChatRoomId") == groupId) return true
        if (intent.getStringExtra("Chat_User") == groupId) return true
        if (intent.getStringExtra("Contact_User") == groupId) return false
        return intent.getStringExtra("Contact_ChatRoomId")?.endsWith("@chatroom") == true ||
            intent.getStringExtra("Chat_User")?.endsWith("@chatroom") == true
    }

    private fun messageType(message: Any): Int {
        return parseInt(readMessageValue(message, "getType", "field_type", "type"))
            ?: readInt(message, "field_type", "type")
    }

    private fun messageDetails(
        message: Any,
        knownType: Int? = null,
        includeContent: Boolean = false
    ): MessageDetails {
        val config = messageDetailsConfig
        val tokens = config.tokens
        val needsAtUsers = includeContent || tokens.any {
            it == "atUserList" || it == "rawAtUserList" || it == "mentionedUsers"
        }
        val needsContent = includeContent || needsAtUsers || "type" in tokens || "appType" in tokens
        val content = if (needsContent) {
            readMessageString(message, "getContent", "field_content", "content")
        } else {
            ""
        }
        val talker = if (includeContent || "talker" in tokens) {
            readMessageString(message, "getTalker", "field_talker", "talker")
        } else {
            ""
        }
        val msgSource = if (needsAtUsers) {
            readMessageString(message, "getMsgSource", "field_msgSource", "msgSource")
                .ifBlank { readMessageSourceFromLvBuffer(message) }
        } else {
            ""
        }
        val body = if (needsContent) stripGroupSenderPrefix(content) else ""
        val serverId = if (includeContent || "msgSvrId" in tokens) {
            parseLong(readMessageValue(message, "getMsgSvrId", "field_msgSvrId", "msgSvrId")) ?: 0L
        } else {
            0L
        }
        val messageId = if ("msgId" in tokens) messageId(message) else 0L
        val createTime = if ("time" in tokens || "relativeTime" in tokens || "createTime" in tokens) {
            parseLong(readMessageValue(message, "getCreateTime", "field_createTime", "createTime")) ?: 0L
        } else {
            0L
        }
        return MessageDetails(
            type = knownType ?: messageType(message),
            id = messageId,
            serverId = serverId,
            talker = talker,
            sender = senderOf(talker, content),
            content = content,
            body = body,
            msgSource = msgSource,
            atUserList = if (needsAtUsers) atUserList(msgSource, content) else "",
            nativeClassName = message.javaClass.simpleName,
            createTime = createTime,
            isSelf = (parseInt(readMessageValue(message, "isSend", "field_isSend", "isSend"))
                ?: parseInt(readMessageValue(message, "getIsSend", "field_isSend", "isSend"))
                ?: readInt(message, "field_isSend", "isSend")) == 1
        )
    }

    private fun showMessageDetailsDialog(context: Context, details: MessageDetails) {
        val raw = stripGroupSenderPrefix(details.content).ifBlank { details.body }.ifBlank { details.msgSource }
        val shouldFormat = prefs.getBoolean(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT_CONTENT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT_CONTENT
            ) || looksLikeXml(raw)
        val initialText = if (shouldFormat) {
            formatXmlLikeContent(raw)
        } else {
            raw
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dp(context, 8f)
            val padTop = dp(context, 4f)
            setPadding(padH, padTop, padH, 0)
        }

        val searchStatusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(context).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            text = "搜索"
        }
        val closeSearchButton = TextView(context).apply {
            text = "×"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(33, 150, 243))
            visibility = View.GONE
        }
        searchStatusRow.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchStatusRow.addView(closeSearchButton, LinearLayout.LayoutParams(dp(context, 40f), ViewGroup.LayoutParams.WRAP_CONTENT))

        val searchPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(context, 4f), 0, 0)
        }
        searchPanel.addView(searchStatusRow)
        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchInput = EditText(context).apply {
            hint = "搜索"
            setSingleLine(true)
            textSize = 12f
        }
        val replaceInput = EditText(context).apply {
            hint = "替换为"
            setSingleLine(true)
            textSize = 12f
        }
        searchRow.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(replaceInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchPanel.addView(searchRow)
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun compactAction(title: String): TextView = TextView(context).apply {
            text = title
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(33, 150, 243))
            val padH = dp(context, 8f)
            val padV = dp(context, 4f)
            setPadding(padH, padV, padH, padV)
        }
        val prevButton = compactAction("上一个")
        val nextButton = compactAction("下一个")
        val replaceButton = compactAction("替换")
        val replaceAllButton = compactAction("全部替换")
        actionRow.addView(prevButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(replaceButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(replaceAllButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchPanel.addView(actionRow)
        root.addView(searchPanel)

        val mentionTargets = mentionTargets(details)
        if (mentionTargets.isNotEmpty()) {
            val mentionedUsersText = TextView(context).apply {
                text = buildString {
                    append("艾特对象")
                    mentionTargets.forEach { target -> append('\n').append(target) }
                }
                setTextIsSelectable(true)
                textSize = 13f
                setTextColor(Color.GRAY)
                includeFontPadding = true
                setPadding(0, dp(context, 4f), 0, dp(context, 8f))
            }
            root.addView(
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = mentionTargets.size > 4
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(
                        mentionedUsersText,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    minOf(dp(context, 120f), dp(context, 30f + mentionTargets.size * 20f))
                )
            )
        }

        val contentHeight = if (initialText.length > 600) {
            minOf(dp(context, 720f), (context.resources.displayMetrics.heightPixels * 0.78f).toInt())
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val viewText = TextView(context).apply {
            text = initialText
            setTextIsSelectable(true)
            textSize = 18f
            typeface = Typeface.DEFAULT
            includeFontPadding = true
            setPadding(0, 0, 0, 0)
        }
        val viewScroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                viewText,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val editor = EditText(context).apply {
            setText(initialText)
            setSelectAllOnFocus(false)
            setHorizontallyScrolling(false)
            minLines = 14
            maxLines = 24
            textSize = 18f
            typeface = Typeface.DEFAULT
            background = null
            setPadding(0, 0, 0, 0)
            includeFontPadding = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.START or Gravity.TOP
        }
        root.addView(
            viewScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                contentHeight
            )
        )

        var currentMatch = -1
        var editMode = false
        var lastSelectedEditorText = ""
        lateinit var dialog: AlertDialog
        fun updateLastSelectedEditorText() {
            val allText = editor.text?.toString().orEmpty()
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtMost(allText.length)
            if (end > start) {
                lastSelectedEditorText = allText.substring(start, end)
            }
        }
        fun editedPayload(): String {
            val displayText = editor.text?.toString().orEmpty()
            return if (displayText == initialText) raw else unformatXmlLikeContent(displayText)
        }
        fun updateButtons() {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = if (editMode) "搜索" else "编辑"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = if (editMode) "发送" else "关闭"
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "复制"
        }
        fun enterEditMode() {
            if (editMode) return
            editMode = true
            val previousScrollY = viewScroll.scrollY
            val visibleOffset = visibleTextOffset(viewText, previousScrollY)
            root.removeView(viewScroll)
            root.addView(
                editor,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    contentHeight
                )
            )
            editor.requestFocus()
            editor.setSelection(visibleOffset.coerceIn(0, editor.text?.length ?: 0))
            editor.post {
                editor.scrollTo(0, previousScrollY)
            }
            status.text = "编辑"
            updateButtons()
        }
        fun matchPositions(): List<Int> {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) return emptyList()
            val text = editor.text?.toString().orEmpty()
            val result = ArrayList<Int>()
            var from = 0
            while (from <= text.length) {
                val index = text.indexOf(query, from)
                if (index < 0) break
                result += index
                from = index + query.length
            }
            return result
        }
        fun showSearchPanel(show: Boolean) {
            if (show && !editMode) enterEditMode()
            searchPanel.visibility = if (show) View.VISIBLE else View.GONE
            closeSearchButton.visibility = if (show) View.VISIBLE else View.GONE
            if (contentHeight > 0 && editMode) {
                editor.layoutParams = (editor.layoutParams as LinearLayout.LayoutParams).apply {
                    height = if (show) {
                        (contentHeight - dp(context, 96f)).coerceAtLeast(dp(context, 420f))
                    } else {
                        contentHeight
                    }
                }
            }
            if (show) {
                updateLastSelectedEditorText()
                val allText = editor.text?.toString().orEmpty()
                val selectedText = lastSelectedEditorText.takeIf { it.isNotEmpty() && allText.contains(it) }.orEmpty()
                if (selectedText.isNotEmpty()) {
                    searchInput.setText(selectedText)
                    searchInput.setSelection(searchInput.text?.length ?: 0)
                    currentMatch = -1
                }
                searchInput.requestFocus()
                status.text = "搜索结果: ${matchPositions().size}"
            } else {
                status.text = "搜索"
            }
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentMatch = -1
                status.text = "搜索结果: ${matchPositions().size}"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        fun selectMatch(next: Boolean) {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) {
                status.text = "请输入搜索内容"
                return
            }
            val matches = matchPositions()
            status.text = "搜索结果: ${matches.size}"
            if (matches.isEmpty()) {
                currentMatch = -1
                status.text = "未找到: $query"
                return
            }
            currentMatch = if (currentMatch < 0) {
                if (next) 0 else matches.lastIndex
            } else if (next) {
                (currentMatch + 1) % matches.size
            } else {
                (currentMatch - 1 + matches.size) % matches.size
            }
            val start = matches[currentMatch]
            editor.setSelection(start, start + query.length)
            status.text = "${currentMatch + 1}/${matches.size}"
        }
        prevButton.setOnClickListener { selectMatch(false) }
        nextButton.setOnClickListener { selectMatch(true) }
        replaceButton.setOnClickListener {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) {
                status.text = "请输入搜索内容"
                return@setOnClickListener
            }
            val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
            val selected = editor.text?.subSequence(start, end)?.toString().orEmpty()
            if (selected != query) {
                selectMatch(true)
                return@setOnClickListener
            }
            val replacement = replaceInput.text?.toString().orEmpty()
            editor.text?.replace(start, end, replacement)
            currentMatch = -1
            status.text = "已替换 1 处"
            selectMatch(true)
        }
        replaceAllButton.setOnClickListener {
            val query = searchInput.text?.toString().orEmpty()
            if (query.isEmpty()) {
                status.text = "请输入搜索内容"
                return@setOnClickListener
            }
            val replacement = replaceInput.text?.toString().orEmpty()
            val oldText = editor.text?.toString().orEmpty()
            val count = matchPositions().size
            status.text = "搜索结果: $count"
            if (count <= 0) {
                status.text = "未找到: $query"
                return@setOnClickListener
            }
            editor.setText(oldText.replace(query, replacement))
            editor.setSelection(0)
            currentMatch = -1
            status.text = "已替换 $count 处"
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(details.nativeClassName.ifBlank { "消息详情" })
            .setView(root)
            .setNeutralButton("编辑", null)
            .setNegativeButton("复制", null)
            .setPositiveButton("关闭", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnLongClickListener {
            if (editMode) showSearchPanel(searchPanel.visibility != View.VISIBLE) else enterEditMode()
            true
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            if (editMode) showSearchPanel(searchPanel.visibility != View.VISIBLE) else enterEditMode()
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
            if (editMode) {
                val allText = editor.text?.toString().orEmpty()
                val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
                val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtMost(allText.length)
                val hasSelection = end > start
                val selected = if (hasSelection) allText.substring(start, end) else editedPayload()
                copyMessageDetails(context, selected)
                if (hasSelection) {
                    status.text = "已复制选中内容"
                } else {
                    dialog.dismiss()
                }
            } else {
                val allText = viewText.text?.toString().orEmpty()
                val start = minOf(viewText.selectionStart, viewText.selectionEnd).coerceAtLeast(0)
                val end = maxOf(viewText.selectionEnd, viewText.selectionStart).coerceAtMost(allText.length)
                if (end > start) {
                    copyMessageDetails(context, allText.substring(start, end))
                    Toast.makeText(context, "已复制选中内容", Toast.LENGTH_SHORT).show()
                } else {
                    copyMessageDetails(context, raw)
                    dialog.dismiss()
                }
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (!editMode) {
                dialog.dismiss()
            } else {
                val sent = sendEditedMessageDetails(context, details, editedPayload())
                if (sent) dialog.dismiss()
            }
        }
        status.setOnClickListener {
            showSearchPanel(searchPanel.visibility != View.VISIBLE)
        }
        status.setOnLongClickListener {
            showSearchPanel(searchPanel.visibility != View.VISIBLE)
            true
        }
        closeSearchButton.setOnClickListener {
            showSearchPanel(false)
        }
        closeSearchButton.setOnLongClickListener {
            showSearchPanel(false)
            true
        }
    }

    private fun copyMessageDetails(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("消息详情", text))
    }

    private fun visibleTextOffset(textView: TextView, scrollY: Int): Int {
        val textLength = textView.text?.length ?: 0
        val layout = textView.layout ?: return 0
        if (layout.lineCount <= 0) return 0
        val line = layout.getLineForVertical(scrollY.coerceAtLeast(0))
            .coerceIn(0, layout.lineCount - 1)
        return layout.getLineStart(line).coerceIn(0, textLength)
    }

    private fun sendEditedMessageDetails(context: Context, details: MessageDetails, text: String): Boolean {
        val payload = text.trim()
        val talker = details.talker.ifBlank { WeChatApis.chatPage()?.currentTalker().orEmpty() }
        if (talker.isBlank() || payload.isBlank()) {
            Toast.makeText(context, "发送失败：会话或内容为空", Toast.LENGTH_SHORT).show()
            return false
        }
        val lower = payload.lowercase()
        if (isImageXml(lower)) {
            Toast.makeText(context, "图片 XML 不能直接发送，请下载后走图片发送", Toast.LENGTH_SHORT).show()
            return false
        }
        val sender = WeChatApis.message().sender()
        val ok = when {
            isAppMsgXml(lower) -> sender?.sendXml(talker, payload) == true
            looksLikeXml(payload) -> {
                Toast.makeText(context, "当前只支持 AppMsg 卡片 XML 发送", Toast.LENGTH_SHORT).show()
                return false
            }
            else -> sender?.sendText(talker, payload) == true
        }
        Toast.makeText(context, if (ok) "已发送" else "发送失败", Toast.LENGTH_SHORT).show()
        return ok
    }

    private fun isAppMsgXml(lower: String): Boolean {
        return lower.contains("<appmsg") && lower.contains("</appmsg>")
    }

    private fun isImageXml(lower: String): Boolean {
        return lower.contains("<img") && !isAppMsgXml(lower)
    }

    private fun looksLikeXml(text: String): Boolean {
        val value = text.trim()
        return value.startsWith("<") && value.endsWith(">") && value.indexOf('>') > 1
    }

    private fun formatMessageDetailsLabel(details: MessageDetails): String {
        val config = messageDetailsConfig
        val createTime = details.createTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        var formattedTime: String? = null
        fun formatTime(): String {
            return formattedTime ?: LocalDateTime
                .ofInstant(Instant.ofEpochMilli(createTime), ZoneId.systemDefault())
                .format(config.timeFormatter)
                .also { formattedTime = it }
        }
        if (config.format == HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_FORMAT) return formatTime()
        return replaceMessageDetailsTokens(config.format) { name ->
            when (name) {
                "time" -> formatTime()
                "relativeTime" -> relativeMessageTime(createTime)
                "type" -> MessageTypeLabels.label(details.type, details.content, details.body)
                "appType" -> MessageTypeLabels.subtype(details.type, details.content, details.body)?.toString().orEmpty()
                "baseType" -> WeChatMessageTypes.normalize(details.type).toString()
                "direction" -> if (details.isSelf) "发出" else "收到"
                "talker" -> details.talker
                "createTime" -> details.createTime.takeIf { it > 0L }?.toString().orEmpty()
                "typeDec" -> details.type.toString()
                "typeHex" -> "0x" + Integer.toUnsignedString(details.type, 16)
                "msgId" -> details.id.toString()
                "msgSvrId" -> details.serverId.toString()
                "atUserList" -> semanticAtUserList(details)
                "rawAtUserList" -> details.atUserList
                "mentionedUsers" -> mentionedUsersLabel(details)
                else -> null
            }
        }
    }

    private fun relativeMessageTime(createTime: Long): String {
        val zone = ZoneId.systemDefault()
        val days = LocalDate.now(zone).toEpochDay() -
            Instant.ofEpochMilli(createTime).atZone(zone).toLocalDate().toEpochDay()
        if (days > 1L) return "${days}天前"
        if (days == 1L) return "昨天"
        val diff = System.currentTimeMillis() - createTime
        if (diff <= 0L) return "刚刚"
        val minutes = diff / 60000L
        val hours = diff / 3600000L
        return when {
            minutes < 1L -> "刚刚"
            hours < 1L -> "${minutes}分钟前"
            else -> "${hours.coerceAtLeast(1L)}小时前"
        }
    }

    private fun mentionedUsersLabel(details: MessageDetails): String {
        return when (atMentionType(details)) {
            WeChatMessage.AtMentionType.AT_ALL -> "@所有人"
            WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL -> "群公告"
            WeChatMessage.AtMentionType.AT_ME -> "@我"
            WeChatMessage.AtMentionType.OTHERS -> "@${parseAtUserIds(details.atUserList).size}人"
            WeChatMessage.AtMentionType.NONE -> ""
        }
    }

    private fun semanticAtUserList(details: MessageDetails): String {
        return when (atMentionType(details)) {
            WeChatMessage.AtMentionType.AT_ALL -> "@所有人"
            WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL -> "群公告全体"
            else -> details.atUserList
        }
    }

    private fun mentionTargets(details: MessageDetails): List<String> {
        return when (atMentionType(details)) {
            WeChatMessage.AtMentionType.AT_ALL -> listOf("@所有人（全体群成员）")
            WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL -> listOf("群公告全体")
            else -> parseAtUserIds(details.atUserList)
                .filterNot { it == "announcement@all" || it == "notify@all" }
        }
    }

    private fun atMentionType(details: MessageDetails): WeChatMessage.AtMentionType {
        return WeChatMessage.classifyAtMention(
            details.msgSource,
            details.body,
            WeChatApis.account()?.selfWxId().orEmpty()
        )
    }

    private fun parseAtUserIds(atUserList: String): List<String> {
        return atUserList.split(',', ';', '|', ' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun atUserList(msgSource: String, content: String): String {
        val source = msgSource.ifBlank { content }
        val fromXml = AT_USER_LIST_PATTERN
            .find(source)
            ?.let { match -> match.groupValues.getOrNull(1).orEmpty().ifBlank { match.groupValues.getOrNull(2).orEmpty() } }
            ?.trim()
            .orEmpty()
        if (fromXml.isNotBlank()) return fromXml
        WeChatMessage.msgSourceValue(source, ".msgsource.atuserlist").takeIf { it.isNotBlank() }?.let { return it }
        WeChatMessage.msgSourceValue(source, "atuserlist").takeIf { it.isNotBlank() }?.let { return it }
        return when {
            source.contains("announcement@all") -> "announcement@all"
            source.contains("notify@all") -> "notify@all"
            else -> ""
        }
    }

    private fun replaceMessageDetailsTokens(source: String, valueFor: (String) -> String?): String {
        return MESSAGE_DETAILS_TOKEN_PATTERN.replace(source) { match ->
            val name = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
            valueFor(name) ?: match.value
        }
    }

    private fun readMessageSourceFromLvBuffer(message: Any): String {
        val buffer = readMessageValue(message, "getLvBuffer", "field_lvbuffer", "lvbuffer") as? ByteArray
            ?: return ""
        if (buffer.size < 9 || buffer.first() != '{'.code.toByte() || buffer.last() != '}'.code.toByte()) {
            return ""
        }
        return runCatching {
            val bytes = ByteBuffer.wrap(buffer).apply { position(1) }
            val firstLength = readLvBufferLength(bytes) ?: return@runCatching ""
            if (bytes.remaining() < firstLength) return@runCatching ""
            bytes.position(bytes.position() + firstLength)
            if (bytes.remaining() < 4) return@runCatching ""
            bytes.position(bytes.position() + 4)
            val sourceLength = readLvBufferLength(bytes) ?: return@runCatching ""
            if (sourceLength == 0 || bytes.remaining() < sourceLength) return@runCatching ""
            val sourceBytes = ByteArray(sourceLength)
            bytes.get(sourceBytes)
            sourceBytes.toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun readLvBufferLength(buffer: ByteBuffer): Int? {
        if (buffer.remaining() < 2) return null
        val length = buffer.short.toInt() and 0xffff
        return length.takeIf { it <= LV_BUFFER_STRING_MAX_LENGTH }
    }

    private fun configuredAvatarHidden(isSelf: Boolean): Boolean {
        return if (isSelf) hideSelfAvatar else hideOtherAvatar
    }

    private fun messageRowRoot(view: View): View {
        var current = view
        repeat(10) {
            val parent = current.parent as? ViewGroup ?: return view
            if (isScrollingContainer(parent)) return current
            current = parent
        }
        return view
    }

    private fun holderMainContentView(holder: Any, parent: ViewGroup): View? {
        val holderClass = holder.javaClass
        val method = holderMainContainerMethodCache[holderClass] ?: run {
            if (holderWithoutMainContainerMethod.contains(holderClass)) return null
            val resolved = methodsRecursive(holderClass).firstOrNull { candidate ->
                candidate.name == "getMainContainerView" &&
                    candidate.parameterTypes.isEmpty() &&
                    View::class.java.isAssignableFrom(candidate.returnType)
            }
            if (resolved == null) {
                holderWithoutMainContainerMethod += holderClass
                return null
            }
            holderMainContainerMethodCache[holderClass] = resolved
            resolved
        }
        val view = KavaReflector.invoke(method, holder) as? View ?: return null
        if (view.visibility == View.GONE || !isViewWithinRoot(view, parent)) return null
        val clickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache)
        if (view === clickArea && !hasMessageSizedBounds(view, parent)) return null
        return view
    }

    private fun hasMessageSizedBounds(view: View, parent: ViewGroup): Boolean {
        if (view.width <= 0 || parent.width <= 0 || !isViewWithinRoot(view, parent)) return false
        val bounds = Rect(0, 0, view.width, view.height)
        parent.offsetDescendantRectToMyCoords(view, bounds)
        val inset = dp(view.context, 8f)
        return bounds.width() < parent.width - parent.paddingLeft - parent.paddingRight - inset ||
            bounds.left > parent.paddingLeft + inset ||
            bounds.right < parent.width - parent.paddingRight - inset
    }

    private fun avatarDetailsContentView(holder: Any, parent: RelativeLayout): View? {
        holderMainContentView(holder, parent)?.let { return it }
        return messageContentView(holder, parent)
    }

    private fun messageContentView(holder: Any, parent: ViewGroup, excluded: View? = null): View? {
        val rootView = findRootView(holder)
        val clickArea = findHolderView(holder, "clickArea", holderClickAreaFieldCache)
        val fields = holderContentFieldCache.getOrPut(holder.javaClass) {
            fieldsRecursive(holder.javaClass)
                .filter { View::class.java.isAssignableFrom(it.type) }
                .filterNot { isAuxiliaryMessageViewField(it.name) }
        }
        return fields
            .asSequence()
            .mapNotNull { KavaReflector.readField(it, holder) as? View }
            .distinct()
            .filter {
                it !== excluded && it !== rootView && it !== clickArea &&
                    it.visibility != View.GONE && isViewWithinRoot(it, parent)
            }
            .maxByOrNull { viewScoreForBottomDetails(it) }
            ?.takeIf { viewScoreForBottomDetails(it) > 0 }
    }

    private fun isAuxiliaryMessageViewField(name: String): Boolean {
        val lower = name.lowercase()
        return lower == "timetv" ||
            lower == "avatariv" ||
            lower == "usertv" ||
            lower == "clickarea" ||
            lower.contains("time") ||
            lower.contains("avatar") ||
            lower.contains("click") ||
            lower.contains("history") ||
            lower.contains("nomore") ||
            lower.contains("mask") ||
            lower.contains("checkbox") ||
            lower.contains("check")
    }

    private fun viewScoreForBottomDetails(view: View): Int {
        val params = view.layoutParams
        val width = view.width.takeIf { it > 0 }
            ?: view.measuredWidth.takeIf { it > 0 }
            ?: params?.width?.takeIf { it > 0 }
            ?: 0
        val height = view.height.takeIf { it > 0 }
            ?: view.measuredHeight.takeIf { it > 0 }
            ?: params?.height?.takeIf { it > 0 }
            ?: 0
        var score = width.coerceAtMost(dp(view.context, 420f)) + height.coerceAtMost(dp(view.context, 420f))
        if (view is TextView) score += 80
        if (view is ViewGroup) score += view.childCount.coerceAtMost(8) * 20
        if (params?.width == ViewGroup.LayoutParams.WRAP_CONTENT || params?.width == ViewGroup.LayoutParams.MATCH_PARENT) score += 40
        if (view.contentDescription?.toString().orEmpty().contains("avatar", ignoreCase = true)) score -= 600
        if (width in 1..dp(view.context, 72f) && height in 1..dp(view.context, 72f)) score -= 500
        return score
    }

    private fun messageDetailsResolvedTextColor(context: Context): Int {
        val config = messageDetailsConfig
        return if (isNightMode(context)) config.darkTextColor else config.lightTextColor
    }

    private fun messageDetailsResolvedBgColor(context: Context): Int {
        val config = messageDetailsConfig
        return if (isNightMode(context)) config.darkBgColor else config.lightBgColor
    }

    private fun resolveMessageDetailsColor(
        currentValue: String,
        currentDefault: String,
        alternateValue: String,
        alternateDefault: String,
        fallback: Int
    ): Int {
        val currentColor = parseColorSettingOrNull(currentValue)
        val alternateColor = parseColorSettingOrNull(alternateValue)
        val currentIsDefault = isSameColor(currentValue, currentDefault)
        val alternateIsDefault = isSameColor(alternateValue, alternateDefault)
        return when {
            currentColor != null && !currentIsDefault -> currentColor
            alternateColor != null && !alternateIsDefault -> alternateColor
            currentColor != null -> currentColor
            alternateColor != null -> alternateColor
            else -> fallback
        }
    }

    private fun isSameColor(value: String, other: String): Boolean {
        val color = parseColorSettingOrNull(value)
        val otherColor = parseColorSettingOrNull(other)
        return color != null && otherColor != null && color == otherColor
    }

    private fun messageDetailsTextColor(night: Boolean): String {
        return if (night) {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_TEXT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_TEXT
            ).orEmpty()
        } else {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_TEXT,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_TEXT
            ).orEmpty()
        }
    }

    private fun messageDetailsBgColor(night: Boolean): String {
        return if (night) {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_BG,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_DARK_BG
            ).orEmpty()
        } else {
            prefs.getString(
                HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_BG,
                HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_LIGHT_BG
            ).orEmpty()
        }
    }

    private fun isNightMode(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun parseColorSettingOrNull(value: String): Int? {
        return runCatching { Color.parseColor(value.trim()) }.getOrNull()
    }

    private fun formatXmlLikeContent(raw: String): String {
        return raw.replace("><", ">\n<")
    }

    private fun unformatXmlLikeContent(text: String): String {
        return text.replace(Regex(">\\s+<"), "><")
    }

    private fun senderOf(talker: String, content: String): String {
        if (talker.endsWith("@chatroom")) {
            val idx = content.indexOf(":\n")
            if (idx > 0) return content.substring(0, idx)
        }
        return talker
    }

    private fun stripGroupSenderPrefix(content: String): String {
        val idx = content.indexOf(":\n")
        val prefix = if (idx > 0) content.substring(0, idx) else ""
        return if (prefix.isNotBlank() && !prefix.contains("<") && !prefix.contains("\n")) {
            content.substring(idx + 2)
        } else {
            content
        }
    }

    private fun readMessageString(source: Any, getter: String, fieldName: String, fallbackField: String): String {
        return readMessageValue(source, getter, fieldName, fallbackField) as? String ?: ""
    }

    private fun readMessageValue(source: Any, getter: String, fieldName: String, fallbackField: String): Any? {
        val key = MessageAccessorKey(source.javaClass, getter, fieldName, fallbackField)
        val accessor = messageAccessorCache[key] ?: MessageAccessor(
            getter = KavaReflector.findMethod(source.javaClass, getter),
            primaryField = KavaReflector.findFieldRecursive(source.javaClass, fieldName),
            fallbackField = KavaReflector.findFieldRecursive(source.javaClass, fallbackField)
        ).also { messageAccessorCache.putIfAbsent(key, it) }
        KavaReflector.invoke(accessor.getter, source)?.let { return it }
        KavaReflector.readField(accessor.primaryField, source)?.let { return it }
        return KavaReflector.readField(accessor.fallbackField, source)
    }

    private fun readLong(receiver: Any?, vararg names: String): Long {
        if (receiver == null) return 0L
        names.forEach { name ->
            val value = KavaReflector.readField(receiver, name)
            if (value is Number) return value.toLong()
        }
        return 0L
    }

    private fun readInt(receiver: Any?, vararg names: String): Int {
        if (receiver == null) return 0
        names.forEach { name ->
            val value = KavaReflector.readField(receiver, name)
            if (value is Number) return value.toInt()
        }
        return 0
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

    private fun dp(context: Context, value: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()
    }

    private fun preferenceKey(pref: Any?): String {
        if (pref == null) return ""
        for (name in arrayOf("r", "q")) {
            (KavaReflector.readField(pref, name) as? String)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        KavaReflector.declaredFields(pref.javaClass).forEach { field ->
            if (field.type == String::class.java) {
                val value = KavaReflector.readField(field, pref) as? String
                if (!value.isNullOrBlank() && value == PREF_GROUP_MEMBER_HISTORY) return value
            }
        }
        return KavaReflector.invokeMethod(pref, "getKey") as? String ?: ""
    }

    private fun findPreference(screen: Any, key: String): Any? {
        return methodsRecursive(screen.javaClass).firstNotNullOfOrNull { method ->
            if (method.parameterTypes.size == 1 &&
                method.parameterTypes[0] == String::class.java &&
                method.returnType.name.contains("Preference")
            ) {
                KavaReflector.invoke(method, screen, key)?.takeIf { preferenceKey(it) == key }
            } else null
        }
    }

    private fun setPreferenceKey(pref: Any, key: String) {
        KavaReflector.invokeMethod(pref, "setKey", key)
        KavaReflector.writeField(pref, "r", key)
        KavaReflector.writeField(pref, "q", key)
        KavaReflector.declaredFields(pref.javaClass)
            .firstOrNull { it.type == String::class.java && !Modifier.isFinal(it.modifiers) }
            ?.let { KavaReflector.writeField(it, pref, key) }
    }

    private fun setPreferenceTitle(pref: Any, title: String) {
        setPreferenceText(pref, title, preferSecond = true)
    }

    private fun setPreferenceSummary(pref: Any, summary: String) {
        setPreferenceText(pref, summary, preferSecond = false)
    }

    private fun setPreferenceText(pref: Any, text: String, preferSecond: Boolean) {
        if (preferSecond) {
            KavaReflector.writeField(pref, "i", text)
            KavaReflector.writeField(pref, "h", text)
        } else {
            KavaReflector.writeField(pref, "n", text)
            KavaReflector.writeField(pref, "m", text)
        }
        val methods = methodsRecursive(pref.javaClass)
            .filter {
                it.parameterTypes.size == 1 &&
                    (it.parameterTypes[0].isAssignableFrom(String::class.java) ||
                        it.parameterTypes[0].isAssignableFrom(CharSequence::class.java)) &&
                    it.returnType == java.lang.Void.TYPE
            }
        val method = if (preferSecond) methods.getOrNull(1) ?: methods.firstOrNull() else methods.firstOrNull()
        KavaReflector.invoke(method, pref, text)
    }

    private fun injectPreferenceByAdapter(activity: Activity, adapter: Any, key: String, title: String): Boolean {
        findAdapterPreference(adapter, key, title)?.let { pref ->
            setPreferenceKey(pref, key)
            setPreferenceTitle(pref, title)
            setPreferenceSummary(pref, "")
            setGroupHistoryPreferenceClick(pref, activity)
            ensureAdapterPreferenceIndex(adapter, pref, adapterInsertionIndex(adapter, pref))
            return true
        }
        if (injectingGroupHistory) return false
        injectingGroupHistory = true
        val inserted = try {
            val prefClass = KavaReflector.loadClass("com.tencent.mm.ui.base.preference.Preference", context.hostClassLoader())
                ?: return false
            val pref = KavaReflector.newInstance(KavaReflector.findConstructor(prefClass, Context::class.java), activity)
                ?: return false
            setPreferenceKey(pref, key)
            setPreferenceTitle(pref, title)
            setPreferenceSummary(pref, "")
            setGroupHistoryPreferenceClick(pref, activity)
            copyLayoutResource(firstAdapterPreference(adapter), pref)
            val index = adapterInsertionIndex(adapter, null)
            methodsRecursive(adapter.javaClass).any { method ->
                method.parameterTypes.size == 2 &&
                    method.parameterTypes[1] == Integer.TYPE &&
                    method.parameterTypes[0].isAssignableFrom(pref.javaClass) &&
                    KavaReflector.invokeSuccessfully(method, adapter, pref, index)
            }
        } finally {
            injectingGroupHistory = false
        }
        if (!inserted) return false
        notifyAdapterChanged(adapter)
        return true
    }

    private fun firstAdapterPreference(adapter: Any): Any? {
        val count = adapterCount(adapter)
        for (i in 0 until count) {
            adapterPreferenceAt(adapter, i)?.let { return it }
        }
        return null
    }

    private fun findAdapterPreference(adapter: Any, key: String, title: String? = null): Any? {
        val count = adapterCount(adapter)
        for (i in 0 until count) {
            val pref = adapterPreferenceAt(adapter, i) ?: continue
            if (preferenceKey(pref) == key || (!title.isNullOrBlank() && preferenceTitle(pref) == title)) return pref
        }
        return null
    }

    private fun isGroupHistoryPreference(pref: Any?): Boolean {
        if (pref == null) return false
        return preferenceKey(pref) == PREF_GROUP_MEMBER_HISTORY ||
            preferenceTitle(pref) == "历史发言记录"
    }

    private fun setGroupHistoryPreferenceClick(pref: Any, activity: Activity?) {
        if (activity == null) return
        methodsRecursive(pref.javaClass)
            .firstOrNull { method ->
                method.parameterTypes.size == 1 &&
                    View.OnClickListener::class.java.isAssignableFrom(method.parameterTypes[0]) &&
                    method.returnType == java.lang.Void.TYPE
            }
            ?.let { method ->
                KavaReflector.invoke(method, pref, View.OnClickListener {
                    openGroupMemberHistory(activity)
                })
            }
    }

    private fun preferenceTitle(pref: Any?): String {
        if (pref == null) return ""
        for (name in arrayOf("i", "h")) {
            (KavaReflector.readField(pref, name) as? CharSequence)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return methodsRecursive(pref.javaClass).firstNotNullOfOrNull { method ->
            if (method.parameterTypes.isEmpty() && CharSequence::class.java.isAssignableFrom(method.returnType)) {
                (KavaReflector.invoke(method, pref) as? CharSequence)?.toString()?.takeIf { it.isNotBlank() }
            } else null
        }.orEmpty()
    }

    private fun ensureAdapterPreferenceIndex(adapter: Any, pref: Any, targetIndex: Int): Boolean {
        val currentIndex = adapterPreferenceIndex(adapter, pref) ?: return false
        val boundedTarget = targetIndex.coerceIn(0, (adapterCount(adapter) - 1).coerceAtLeast(0))
        if (currentIndex == boundedTarget) return true
        if (!removePreference(adapter, pref)) return false
        return addPreference(adapter, pref, targetIndex.coerceIn(0, adapterCount(adapter).coerceAtLeast(0)))
    }

    private fun adapterPreferenceIndex(adapter: Any, target: Any): Int? {
        val count = adapterCount(adapter)
        for (i in 0 until count) {
            if (adapterPreferenceAt(adapter, i) === target) return i
        }
        return null
    }

    private fun removePreference(adapter: Any, pref: Any): Boolean {
        return methodsRecursive(adapter.javaClass).any { method ->
            if (method.parameterTypes.size != 1 ||
                !method.parameterTypes[0].isAssignableFrom(pref.javaClass) ||
                (method.returnType != java.lang.Boolean.TYPE && method.returnType != java.lang.Boolean::class.java)
            ) {
                false
            } else {
                KavaReflector.invoke(method, adapter, pref) == true &&
                    adapterPreferenceIndex(adapter, pref) == null
            }
        }
    }

    private fun adapterInsertionIndex(adapter: Any, ignored: Any?): Int {
        val count = adapterCount(adapter)
        var ignoredBefore = 0
        for (i in 0 until count) {
            val pref = adapterPreferenceAt(adapter, i)
            if (pref === ignored) {
                ignoredBefore++
                continue
            }
            val adjustedIndex = i - ignoredBefore
            if (isProfileIdPreference(pref)) return adjustedIndex + 1
            val key = preferenceKey(pref)
            if (isProfileBusinessPreference(pref, key)) return adjustedIndex
        }
        return (count - ignoredBefore).coerceAtLeast(0)
    }

    private fun screenInsertionIndex(screen: Any): Int {
        val count = adapterCount(screen)
        for (i in 0 until count) {
            if (isProfileIdPreference(adapterPreferenceAt(screen, i))) return i + 1
        }
        for (i in 0 until count) {
            val pref = adapterPreferenceAt(screen, i)
            if (isProfileBusinessPreference(pref, preferenceKey(pref))) return i
        }
        return count.coerceAtLeast(0)
    }

    private fun isProfileIdPreference(pref: Any?): Boolean {
        if (pref == null) return false
        if (preferenceKey(pref) == PREF_PROFILE_ID) return true
        return preferenceTitle(pref).trim().startsWith("ID:")
    }

    private fun isProfileBusinessPreference(pref: Any?, key: String): Boolean {
        if (key == "contact_info_sns" || key == "contact_info_more" || key.contains("permission")) return true
        val title = preferenceTitle(pref)
        return title.contains("设置备注") ||
            title.contains("标签") ||
            title.contains("朋友圈") ||
            title.contains("添加到通讯录")
    }

    private fun adapterPreferenceAt(adapter: Any, index: Int): Any? {
        return methodsRecursive(adapter.javaClass).firstNotNullOfOrNull { method ->
            if (method.name == "getItem" &&
                method.parameterTypes.size == 1 &&
                (method.parameterTypes[0] == Integer.TYPE || method.parameterTypes[0] == java.lang.Integer::class.java)
            ) {
                KavaReflector.invoke(method, adapter, index)
            } else null
        }
    }

    private fun adapterCount(adapter: Any): Int {
        return methodsRecursive(adapter.javaClass).firstNotNullOfOrNull { method ->
            if (method.name == "getCount" && method.parameterTypes.isEmpty()) {
                (KavaReflector.invoke(method, adapter) as? Number)?.toInt()
            } else null
        } ?: 0
    }

    private fun copyLayoutResource(from: Any?, to: Any) {
        if (from == null) return
        val layout = KavaReflector.invokeMethod(from, "getLayoutResource") as? Number ?: return
        if (layout.toInt() == 0) return
        KavaReflector.invokeMethod(to, "setLayoutResource", layout.toInt())
    }

    private fun notifyAdapterChanged(adapter: Any) {
        injectingGroupHistory = true
        try {
            KavaReflector.invokeMethod(adapter, "notifyDataSetChanged")
            methodsRecursive(adapter.javaClass)
                .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty() }
                ?.let { KavaReflector.invoke(it, adapter) }
        } finally {
            injectingGroupHistory = false
        }
    }

    private fun notifyPreferenceChanged(screen: Any) {
        KavaReflector.invokeMethod(screen, "notifyDataSetChanged")
        methodsRecursive(screen.javaClass)
            .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterTypes.isEmpty() }
            ?.let { KavaReflector.invoke(it, screen) }
    }

    private fun findListView(group: ViewGroup?): android.widget.ListView? {
        if (group == null) return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is android.widget.ListView) return child
            if (child is ViewGroup) findListView(child)?.let { return it }
        }
        return null
    }

    private fun addPreference(screen: Any, pref: Any, index: Int): Boolean {
        return methodsRecursive(screen.javaClass).any { method ->
            if (method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == Integer.TYPE &&
                method.parameterTypes[0].isAssignableFrom(pref.javaClass)
            ) {
                KavaReflector.invokeSuccessfully(method, screen, pref, index)
            } else false
        }
    }

    private fun methodsRecursive(clazz: Class<*>?): List<Method> {
        val methods = ArrayList<Method>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            methods += KavaReflector.declaredMethods(current)
            current = current.superclass
        }
        return methods
    }

    private fun fieldsRecursive(clazz: Class<*>?): List<java.lang.reflect.Field> {
        val fields = ArrayList<java.lang.reflect.Field>()
        var current = clazz
        while (current != null && current != Any::class.java) {
            fields += KavaReflector.declaredFields(current)
            current = current.superclass
        }
        return fields
    }

    companion object {
        private const val PREF_GROUP_MEMBER_HISTORY = "hchat_group_member_history"
        private const val TAG_MESSAGE_DETAILS_VIEW = "hchat_message_details_view"

        /** 记录上次布局参数签名，命中时跳过 setLayoutParams 与同步测量。 */
        private const val TAG_MESSAGE_DETAILS_LAYOUT = 0x4A484354
        private const val LV_BUFFER_STRING_MAX_LENGTH = 3072
        private val MESSAGE_DETAILS_TOKEN_PATTERN = Regex("\\$\\{([A-Za-z][A-Za-z0-9]*)\\}|\\$([A-Za-z][A-Za-z0-9]*)")
        private val AT_USER_LIST_PATTERN = Regex(
            "<atuserlist><!\\[CDATA\\[(.*?)]]></atuserlist>|<atuserlist>(.*?)</atuserlist>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val DEFAULT_MESSAGE_DETAILS_TIME_FORMATTER =
            DateTimeFormatter.ofPattern(HchatExtraSettings.DEFAULT_MESSAGE_DETAILS_TIME_FORMAT)
        private const val MASK_LAYOUT_CLASS = "com.tencent.mm.ui.base.MaskLayout"
        private const val CHATTING_AVATAR_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView"
        private const val RED_PACKET_RECORD_TIME_ID_NAME = "j6q"
        private val RED_PACKET_TIME_SECONDS_RANGE = 1_262_304_000L..4_102_444_800L
        private val RED_PACKET_TIME_MILLIS_RANGE = 1_262_304_000_000L..4_102_444_800_000L
        private const val PREF_PROFILE_ID = "hchat_profile_id"
        private const val MESSAGE_DETAILS_MAX_RETRY = 2

        /** 预留行高时允许少量视觉交叠，避免消息间距被时间标签撑得过大。 */
        private const val MESSAGE_DETAILS_PADDING_OVERLAP_DP = 4f
        private val MESSAGE_DETAILS_COLOR_KEYS = setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_BG,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_LIGHT_TEXT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_BG,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_DARK_TEXT
        )
        private val MESSAGE_DETAILS_LAYOUT_KEYS = setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS_TEXT_SIZE,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_LEFT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_TOP,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_RIGHT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_MARGIN_BOTTOM
        )
        private val MESSAGE_DETAILS_REBIND_KEYS = MESSAGE_DETAILS_LAYOUT_KEYS + setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_FORMAT,
            HchatExtraSettings.KEY_MESSAGE_DETAILS_TIME_FORMAT
        )
        private val MESSAGE_DETAILS_CONFIG_KEYS = MESSAGE_DETAILS_COLOR_KEYS + MESSAGE_DETAILS_REBIND_KEYS + setOf(
            HchatExtraSettings.KEY_MESSAGE_DETAILS
        )
    }
}

private data class MessageDetails(
    val type: Int,
    val id: Long,
    val serverId: Long,
    val talker: String,
    val sender: String,
    val content: String,
    val body: String,
    val msgSource: String,
    val atUserList: String,
    val nativeClassName: String,
    val createTime: Long,
    val isSelf: Boolean
)
