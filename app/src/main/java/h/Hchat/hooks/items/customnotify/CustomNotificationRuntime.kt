package h.Hchat.hooks.items.customnotify

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.atallnotify.AtAllNotificationBlockRuntime
import h.Hchat.hooks.items.atallnotify.AtAllNotificationBlockSettings
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarSettings
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
import h.Hchat.hooks.items.notification.NotificationNameResolver
import h.Hchat.hooks.items.quickread.QuickMarkReadRuntime
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object CustomNotificationRuntime {
    private const val TAG = "[Hchat:CustomNotification]"
    private const val MARK_HCHAT = "hchat_custom_notification"
    private const val MARK_KEYWORD_NOTIFICATION = "hchat_keyword_notification"
    private const val EXTRA_TALKER = "hchat_custom_notification_talker"
    private const val EXTRA_NATIVE_TITLE = "hchat_custom_notification_native_title"
    private const val EXTRA_NATIVE_TEXT = "hchat_custom_notification_native_text"
    private const val EXTRA_NATIVE_SUMMARY = "hchat_custom_notification_native_summary"
    private const val EXTRA_NATIVE_MSG_SVR_ID = "hchat_custom_notification_native_msg_svr_id"
    private const val EXTRA_UNREAD_COUNT = "hchat_custom_notification_unread_count"
    private const val EXTRA_MERGED_LINES = "hchat_custom_notification_merged_lines"
    private const val EXTRA_NOTIFY_ID = "hchat_custom_notification_id"
    private const val EXTRA_REPLY_MSG_ID = "hchat_custom_notification_reply_msg_id"
    private const val EXTRA_QUOTE_QUICK_REPLY = "hchat_custom_notification_quote_quick_reply"
    private const val EXTRA_NOTIFICATION_TOKEN = "hchat_custom_notification_token"
    private const val ACTION_QUICK_REPLY = "h.Hchat.action.CUSTOM_NOTIFICATION_REPLY"
    private const val ACTION_MARK_READ = "h.Hchat.action.CUSTOM_NOTIFICATION_MARK_READ"
    private const val REMOTE_INPUT_KEY = "hchat_reply_text"
    private const val CHANNEL_PREFIX = "Hchat_custom_notification_"
    private const val KEYWORD_CHANNEL_PREFIX = "Hchat_keyword_notification_"
    private const val NOTIFICATION_ITEM = "com.tencent.mm.booter.notification.NotificationItem"
    private const val CACHE_PREFS = "Hchat_custom_notification_method_cache"
    private const val CACHE_NATIVE_BUILDER = "native_notification_builder"
    private const val CACHE_NATIVE_PROCESSOR = "native_notification_processor"
    private const val CACHE_NATIVE_CLEANUP = "native_notification_cleanup"
    private const val CACHE_NATIVE_FOREGROUND_PROCESSOR = "native_foreground_processor"
    private const val CACHE_NATIVE_SOUND_PLAYER = "native_notification_sound_player"
    private const val NATIVE_PROCESSOR_ANCHOR =
        "in sample Notify: needSound: %B, needShake: %B, msgContent: ==, msgType: %d, talker: %s, customNotify: %s, isRevokeMessage:%b"
    private const val NATIVE_CLEANUP_ANCHOR = "needRemoveNotificationId:%s"
    private const val NATIVE_FOREGROUND_PROCESSOR_ANCHOR =
        "notification.playSound: is mainUItalker: %s"
    private const val NATIVE_SOUND_PLAYER_ANCHOR = "playSound playHandler == null"
    private const val NATIVE_WAIT_MS = 900L
    private const val NATIVE_OWNERSHIP_TTL_MS = 60_000L
    private const val NATIVE_FALLBACK_BEFORE_MS = 2_000L
    private const val NATIVE_FALLBACK_AFTER_MS = 5_000L
    private const val NATIVE_MENTION_TTL_MS = 15_000L
    private const val NATIVE_MENTION_CLEANUP_THRESHOLD = 128
    private const val NOTIFICATION_DEDUP_TTL_MS = 60_000L
    private const val NOTIFICATION_DEDUP_CLEANUP_THRESHOLD = 256
    private const val QUICK_REPLY_CANCEL_DELAY_MS = 1_200L
    private const val EXTRA_REPLY_COMPLETION_TOKEN = "hchat_custom_notification_reply_completion_token"
    private const val MAX_MERGED_NOTIFICATION_LINES = 7

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val receiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
    private val notifySeq = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-CustomNotify").apply { isDaemon = true }
    }
    private val quickReplyExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-QuickReply").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val avatarCache = ConcurrentHashMap<String, Bitmap?>()
    private val nativeNotifyAt = ConcurrentHashMap<String, Long>()
    private val nativeHandledMessages = ConcurrentHashMap<NativeMessageKey, Long>()
    private val notificationDedupAt = ConcurrentHashMap<NotificationDedupKey, Long>()
    private val nativeMentions = ConcurrentHashMap<NativeMessageKey, NativeMentionRecord>()
    private val nativeInstanceFields = ConcurrentHashMap<Class<*>, List<Field>>()
    private val nativeMessageClasses = ConcurrentHashMap<Class<*>, Boolean>()
    private val nativeNotificationBuildStack = ThreadLocal<ArrayDeque<Boolean>>()
    private val nativeForegroundSoundBlockStack = ThreadLocal<ArrayDeque<Boolean>>()
    @Volatile private var avatarRoot: String? = null
    @Volatile private var notificationActionReceiver: BroadcastReceiver? = null
    @Volatile private var lastManualSoundAt: Long = 0L
    @Volatile private var methodCachePrefs: android.content.SharedPreferences? = null

    fun notificationTalker(notification: Notification): String {
        return notification.extras?.getString(EXTRA_TALKER).orEmpty()
    }

    fun install(context: FeatureContext) {
        if (installed.compareAndSet(false, true)) {
            QuickMarkReadRuntime.install(context)
            installNotificationHooks(context)
            registerNotificationActionReceiver(context.hostContext())
        }
    }

    fun handleMessage(context: Context, message: WeChatMessageObserveApi.ObservedMessage) {
        if (AtAllNotificationBlockRuntime.shouldSuppress(context, message)) return
        val settings = CustomNotificationSettings(context)
        if (!settings.isEnabled()) return
        if (message.outgoing || message.isSend()) return
        val talker = message.talker.ifBlank { message.getTalker() }
        if (talker.isBlank()) return
        if (isFriendRequestMessage(message)) return
        val rule = settings.effectiveRule(talker) ?: return
        if (!rule.enabled || rule.mode == CustomNotificationSettings.MODE_DND) return
        if (message.isSystem || message.isPat() || message.isRecalled()) return
        if (isCurrentChatVisible(context, talker)) return
        if (isInMuteWindow(rule)) return
        if (shouldSuppressByRule(rule, message)) return
        val observedAt = SystemClock.elapsedRealtime()
        val msgSvrId = message.message?.msgSvrId ?: 0L

        mainHandler.postDelayed({
            if (isHandledByNative(talker, msgSvrId, observedAt)) return@postDelayed
            executor.execute {
                if (isHandledByNative(talker, msgSvrId, observedAt)) return@execute
                if (wechatDoNotDisturbState(rule, talker) != false) return@execute
                runCatching {
                    val payload = buildPayload(context, rule, message)
                    sendNotification(context, rule, payload)
                }.onFailure {
                    HLog.e("$TAG 发送自定义通知失败: ${it.message}", it)
                }
            }
        }, NATIVE_WAIT_MS)
    }

    private fun installNotificationHooks(context: FeatureContext) {
        methodCachePrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        installNativeForegroundFeedbackGuard(context)
        installNativeNotificationCleanupGuard(context)
        installNativeNotificationPayloadHook(context)
        installNativeNotificationMarker(context)
        for (method in NotificationManager::class.java.declaredMethods) {
            val types = method.parameterTypes ?: continue
            if (method.name != "notify" || types.isEmpty() || types.last() != Notification::class.java) continue
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val notification = param.args?.lastOrNull() as? Notification ?: return
                    val hostContext = context.hostContext()
                    val hchatNotification = isHchatNotification(notification)
                    val talker = notification.extras?.getString(EXTRA_TALKER).orEmpty()
                    val msgSvrId = notification.extras?.getLong(EXTRA_NATIVE_MSG_SVR_ID, 0L) ?: 0L
                    if (hchatNotification) return
                    if (talker.isBlank()) return
                    if (AtAllNotificationBlockRuntime.shouldSuppress(
                            hostContext,
                            talker,
                            msgSvrId
                        )
                    ) {
                        param.result = null
                        return
                    }
                    val settings = CustomNotificationSettings(hostContext)
                    if (!settings.isEnabled()) return
                    val rule = settings.effectiveRule(talker) ?: return
                    if (!rule.enabled) return
                    // A native notification without a reliable message id must stay untouched, but it
                    // still owns this notification cycle and must suppress the observed-message fallback.
                    markNativeHandled(talker, msgSvrId)
                    if (msgSvrId <= 0L) return
                    if (rule.mode == CustomNotificationSettings.MODE_DND || isInMuteWindow(rule)) {
                        param.result = null
                        return
                    }
                    when (wechatDoNotDisturbState(rule, talker)) {
                        true -> {
                            param.result = null
                            return
                        }
                        null -> return
                        false -> Unit
                    }
                    if (shouldSuppressNativeByRule(rule, talker, msgSvrId)) {
                        param.result = null
                        return
                    }
                    param.result = null
                    val payload = buildNativePayload(hostContext, rule, notification)
                    executor.execute {
                        runCatching { sendNotification(hostContext, rule, payload) }
                            .onFailure { HLog.e("$TAG 转发微信原生通知失败: ${it.message}", it) }
                    }
                }
            })
        }
    }

    private fun installNativeForegroundFeedbackGuard(context: FeatureContext) {
        val processors = locateNativeNotificationMethods(
            context,
            CACHE_NATIVE_FOREGROUND_PROCESSOR,
            NATIVE_FOREGROUND_PROCESSOR_ANCHOR,
            ::isNativeForegroundProcessor
        )
        val soundPlayers = locateNativeNotificationMethods(
            context,
            CACHE_NATIVE_SOUND_PLAYER,
            NATIVE_SOUND_PLAYER_ANCHOR,
            ::isNativeNotificationSoundPlayer
        )
        if (processors.isEmpty()) {
            HLog.e("$TAG 未定位微信前台通知处理器")
            return
        }
        if (soundPlayers.isEmpty()) {
            HLog.e("$TAG 未定位微信前台通知提示音方法，仅免打扰规则可提前拦截")
        }
        processors.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val stack = nativeForegroundSoundBlockStack.get()
                        ?: ArrayDeque<Boolean>().also(nativeForegroundSoundBlockStack::set)
                    val rule = nativeForegroundRule(
                        context.hostContext(),
                        param.args ?: emptyArray()
                    )
                    val suppressAll = rule != null && (
                        rule.mode == CustomNotificationSettings.MODE_DND || isInMuteWindow(rule)
                    )
                    stack.addLast(soundPlayers.isNotEmpty() && rule?.sound == false)
                    if (suppressAll) param.result = null
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = nativeForegroundSoundBlockStack.get() ?: return
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isEmpty()) nativeForegroundSoundBlockStack.remove()
                }
            })
        }
        soundPlayers.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (nativeForegroundSoundBlockStack.get()?.peekLast() == true) {
                        param.result = null
                    }
                }
            })
        }
    }

    private fun nativeForegroundRule(
        context: Context,
        args: Array<Any?>
    ): CustomNotificationRule? {
        val settings = CustomNotificationSettings(context)
        if (!settings.isEnabled()) return null
        val talker = (args.getOrNull(1) as? String)?.trim().orEmpty()
        if (talker.isBlank()) return null
        return settings.effectiveRule(talker)?.takeIf { it.enabled }
    }

    private fun isNativeForegroundProcessor(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 6 &&
            types[0] == method.declaringClass &&
            types[1] == String::class.java &&
            types[2] == String::class.java &&
            types[3] == Integer.TYPE &&
            types[4] == Integer.TYPE &&
            types[5] == java.lang.Boolean.TYPE
    }

    private fun isNativeNotificationSoundPlayer(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 3 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Boolean.TYPE &&
            types[2] == java.lang.Boolean.TYPE
    }

    private fun installNativeNotificationCleanupGuard(context: FeatureContext) {
        val processors = locateNativeNotificationMethods(
            context,
            CACHE_NATIVE_PROCESSOR,
            NATIVE_PROCESSOR_ANCHOR
        ) { method -> method.returnType == Void.TYPE }
        installNativeMentionRecorder(context, processors)
        val cleanups = locateNativeNotificationMethods(
            context,
            CACHE_NATIVE_CLEANUP,
            NATIVE_CLEANUP_ANCHOR
        ) { method -> method.returnType == Void.TYPE && method.parameterTypes.isEmpty() }
        if (processors.isEmpty() || cleanups.isEmpty()) {
            HLog.e("$TAG 未定位微信隐藏内容通知清理链路")
            return
        }
        processors.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val stack = nativeNotificationBuildStack.get()
                        ?: ArrayDeque<Boolean>().also(nativeNotificationBuildStack::set)
                    stack.addLast(shouldPreserveNativeNotificationCleanup(
                        context.hostContext(),
                        param.args ?: emptyArray()
                    ))
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val stack = nativeNotificationBuildStack.get() ?: return
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isEmpty()) nativeNotificationBuildStack.remove()
                }
            })
        }
        cleanups.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (nativeNotificationBuildStack.get()?.peekLast() != true) return
                    param.result = null
                }
            })
        }
    }

    private fun shouldPreserveNativeNotificationCleanup(
        context: Context,
        args: Array<Any?>
    ): Boolean {
        val settings = CustomNotificationSettings(context)
        if (!settings.isEnabled()) return false
        val nativeMessage = findNativeMessage(args) ?: return false
        val talker = readFirstStringField(nativeMessage, "field_talker", "talker")
        val msgSvrId = readFirstLongField(nativeMessage, "field_msgSvrId", "msgSvrId")
        val type = readFirstIntField(nativeMessage, "field_type", "type")
        val outgoing = readFirstIntField(nativeMessage, "field_isSend", "isSend") == 1
        if (talker.isBlank() || msgSvrId <= 0L || outgoing || WeChatMessageTypes.isSystem(type)) {
            return false
        }
        val rule = settings.effectiveRule(talker) ?: return false
        return rule.enabled &&
            rule.mode != CustomNotificationSettings.MODE_DND &&
            !isInMuteWindow(rule) &&
            wechatDoNotDisturbState(rule, talker) == false
    }

    private fun installNativeMentionRecorder(
        context: FeatureContext,
        processors: List<Method>
    ) {
        processors.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    recordNativeMention(context.hostContext(), param.args ?: emptyArray())
                }
            })
        }
    }

    private fun recordNativeMention(context: Context, args: Array<Any?>) {
        val blockAtAllEnabled = AtAllNotificationBlockSettings.isEnabled(context)
        val settings = CustomNotificationSettings(context)
        val customNotificationEnabled = settings.isEnabled()
        if (!blockAtAllEnabled && !customNotificationEnabled) return
        val nativeMessage = findNativeMessage(args) ?: return
        val talker = args.asSequence()
            .filterIsInstance<String>()
            .map { it.trim() }
            .firstOrNull(::isGroupTalker)
            ?: readFirstStringField(nativeMessage, "field_talker", "talker")
        if (!isGroupTalker(talker)) return
        val selectedForGlobalBlock = blockAtAllEnabled && AtAllNotificationBlockSettings.blocksGroup(context, talker)
        val customRule = if (customNotificationEnabled) settings.effectiveRule(talker) else null
        val needsCustomMention = customRule?.let {
            it.enabled && it.group && (it.blockAtAll || it.blockAtMe)
        } == true
        if (!selectedForGlobalBlock && !needsCustomMention) return
        val nativeStrings = instanceFields(nativeMessage.javaClass)
            .asSequence()
            .filter { it.type == String::class.java }
            .mapNotNull { KavaReflector.readField(it, nativeMessage) as? String }
            .toList()
        val nativeSource = nativeStrings.firstOrNull {
            it.contains("<msgsource", ignoreCase = true) ||
                it.contains("atuserlist", ignoreCase = true)
        }.orEmpty()
        val nativeContent = readFirstRawStringField(nativeMessage, "field_content", "content")
        val nativeTextValues = (args.asSequence().filterIsInstance<String>() + nativeStrings.asSequence()).toList()
        val announcementAll = nativeTextValues.any {
            it.contains("announcement@all", ignoreCase = true)
        }
        val explicitNotifyAll = nativeTextValues
            .any { it.contains("notify@all", ignoreCase = true) }
        val expandedNotifyAll = AtAllNotificationBlockRuntime.isExpandedNotifyAll(
            nativeContent,
            nativeSource,
            WeChatApis.account()?.selfWxId().orEmpty()
        )
        val mentionType = when {
            announcementAll -> WeChatMessage.AtMentionType.ANNOUNCEMENT_ALL
            explicitNotifyAll || expandedNotifyAll -> WeChatMessage.AtMentionType.AT_ALL
            else -> WeChatMessage.classifyAtMention(
                nativeSource,
                nativeContent,
                WeChatApis.account()?.selfWxId().orEmpty()
            )
        }
        val msgSvrId = args.asSequence()
            .filterIsInstance<Long>()
            .firstOrNull { it > 0L }
            ?: readFirstLongField(nativeMessage, "field_msgSvrId", "msgSvrId")
        if (needsCustomMention) recordNativeMention(talker, msgSvrId, mentionType)
        if (selectedForGlobalBlock && mentionType == WeChatMessage.AtMentionType.AT_ALL) {
            AtAllNotificationBlockRuntime.recordNative(context, talker, msgSvrId)
        }
    }

    private fun findNativeMessage(args: Array<Any?>): Any? {
        args.firstOrNull(::isNativeMessage)?.let { return it }
        for (owner in args) {
            if (owner == null) continue
            for (field in instanceFields(owner.javaClass)) {
                val type = field.type
                if (type.isPrimitive || type.isEnum || type == String::class.java ||
                    Number::class.java.isAssignableFrom(type) || type == Boolean::class.java
                ) {
                    continue
                }
                val value = KavaReflector.readField(field, owner) ?: continue
                if (isNativeMessage(value)) return value
            }
        }
        return null
    }

    private fun isNativeMessage(value: Any?): Boolean {
        if (value == null) return false
        return nativeMessageClasses.getOrPut(value.javaClass) {
            KavaReflector.findFieldRecursive(value.javaClass, "field_msgSvrId") != null &&
                KavaReflector.findFieldRecursive(value.javaClass, "field_talker") != null &&
                KavaReflector.findFieldRecursive(value.javaClass, "field_content") != null
        }
    }

    private fun instanceFields(clazz: Class<*>): List<Field> {
        return nativeInstanceFields.getOrPut(clazz) {
            val result = mutableListOf<Field>()
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                result += KavaReflector.declaredFields(current).filterNot { KavaReflector.isStatic(it) }
                current = current.superclass
            }
            result
        }
    }

    private fun isGroupTalker(value: String): Boolean {
        return value.endsWith("@chatroom") || value.endsWith("@im.chatroom")
    }

    private fun locateNativeNotificationMethods(
        context: FeatureContext,
        cacheKey: String,
        anchor: String,
        predicate: (Method) -> Boolean
    ): List<Method> {
        val prefs = methodCachePrefs ?: DexMethodCache.prefs(context.hostContext(), CACHE_PREFS).also {
            methodCachePrefs = it
        }
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.loadList(prefs, runtimeKey, context.hostClassLoader(), cacheKey)
            .filter(predicate)
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        val located = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply { usingStrings(listOf(anchor)) })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter(predicate)
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            HLog.e("$TAG 定位微信通知方法失败 anchor=$anchor: ${it.message}", it)
            emptyList()
        }
        if (located.isNotEmpty()) {
            DexMethodCache.saveList(prefs, runtimeKey, cacheKey, located)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, cacheKey)
        }
        return located
    }

    private fun installNativeNotificationPayloadHook(context: FeatureContext) {
        val methods = locateNativeNotificationBuilderMethods(context)
        methods.forEach { method ->
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val notification = param.result as? Notification ?: return
                    val payload = nativePayloadFromBuilder(method, param.args ?: emptyArray()) ?: return
                    writeNativePayloadExtras(notification, payload)
                }
            })
        }
    }

    private fun locateNativeNotificationBuilderMethods(context: FeatureContext): List<Method> {
        val prefs = methodCachePrefs ?: DexMethodCache.prefs(context.hostContext(), CACHE_PREFS).also {
            methodCachePrefs = it
        }
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.loadList(prefs, runtimeKey, context.hostClassLoader(), CACHE_NATIVE_BUILDER)
            .filter { isNativeNotificationBuilderCandidate(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        val fixed = fixedNativeNotificationBuilderMethods(context)
        if (fixed.isNotEmpty()) {
            DexMethodCache.saveList(prefs, runtimeKey, CACHE_NATIVE_BUILDER, fixed)
            return fixed
        }

        val located = findNativeNotificationBuilderMethods(context)
        if (located.isNotEmpty()) {
            DexMethodCache.saveList(prefs, runtimeKey, CACHE_NATIVE_BUILDER, located)
        } else {
            DexMethodCache.clear(prefs, runtimeKey, CACHE_NATIVE_BUILDER)
        }
        return located
    }

    private fun fixedNativeNotificationBuilderMethods(context: FeatureContext): List<Method> {
        return listOf(
            "com.tencent.mm.booter.notification.e0",
            "com.tencent.mm.booter.notification.w"
        ).mapNotNull { KavaReflector.loadClass(it, context.hostClassLoader()) }
            .flatMap { clazz -> KavaReflector.declaredMethods(clazz).filter { isNativeNotificationBuilderCandidate(it) } }
            .distinctBy { it.toGenericString() }
    }

    private fun findNativeNotificationBuilderMethods(context: FeatureContext): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(listOf("MicroMsg.Notification.AppMsg.Handle"))
                    })
                }
            ).mapNotNull { data ->
                runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
            }.filter { isNativeNotificationBuilderCandidate(it) }
                .distinctBy { it.toGenericString() }
        }.getOrElse {
            HLog.e("$TAG 定位微信原生通知构建方法失败: ${it.message}", it)
            emptyList()
        }
    }

    private fun isNativeNotificationBuilderCandidate(method: Method): Boolean {
        if (method.returnType != Notification::class.java) return false
        val types = method.parameterTypes
        return isLegacyNativeNotificationBuilder(types) || isObjectNativeNotificationBuilder(types)
    }

    private fun isLegacyNativeNotificationBuilder(types: Array<Class<*>>): Boolean {
        return types.size >= 15 &&
            Notification::class.java.isAssignableFrom(types[0]) &&
            types[1] == Integer.TYPE &&
            types[2] == Integer.TYPE &&
            PendingIntent::class.java.isAssignableFrom(types[3]) &&
            types[4] == String::class.java &&
            types[5] == String::class.java &&
            types[6] == String::class.java &&
            Bitmap::class.java.isAssignableFrom(types[7]) &&
            types[14] == String::class.java
    }

    private fun isObjectNativeNotificationBuilder(types: Array<Class<*>>): Boolean {
        if (types.size != 1 || Notification::class.java.isAssignableFrom(types[0])) return false
        val fields = KavaReflector.declaredFields(types[0])
        val stringNames = fields.filter { it.type == String::class.java }.map { it.name }.toSet()
        return fields.any { Notification::class.java.isAssignableFrom(it.type) } &&
            setOf("e", "f", "g", "o").all { it in stringNames }
    }

    private fun nativePayloadFromBuilder(method: Method, args: Array<Any?>): NativeNotifyPayload? {
        val types = method.parameterTypes
        if (isLegacyNativeNotificationBuilder(types)) {
            return NativeNotifyPayload(
                title = args.getOrNull(4)?.toString().orEmpty(),
                text = args.getOrNull(5)?.toString().orEmpty(),
                summary = args.getOrNull(6)?.toString().orEmpty(),
                talker = args.getOrNull(14)?.toString().orEmpty()
            )
        }
        if (isObjectNativeNotificationBuilder(types)) {
            val source = args.firstOrNull() ?: return null
            return NativeNotifyPayload(
                title = readStringField(source, "e"),
                text = readStringField(source, "f"),
                summary = readStringField(source, "g"),
                talker = readStringField(source, "o")
            )
        }
        return null
    }

    private fun writeNativePayloadExtras(notification: Notification, payload: NativeNotifyPayload) {
        if (notification.extras == null) notification.extras = Bundle()
        if (payload.title.isNotBlank()) notification.extras.putString(EXTRA_NATIVE_TITLE, payload.title)
        if (payload.text.isNotBlank()) notification.extras.putString(EXTRA_NATIVE_TEXT, payload.text)
        if (payload.summary.isNotBlank()) notification.extras.putString(EXTRA_NATIVE_SUMMARY, payload.summary)
        if (payload.talker.isNotBlank()) notification.extras.putString(EXTRA_TALKER, payload.talker)
    }

    private fun installNativeNotificationMarker(context: FeatureContext) {
        val clazz = findNotificationItemClass(context) ?: return
        val constructorHooks = XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                markNotificationItem(param.thisObject, context.hostContext())
            }
        })
        constructorHooks?.forEach { HookRegistry.get().add(it) }
        for (method in KavaReflector.declaredMethods(clazz)) {
            val types = method.parameterTypes ?: continue
            if (types.size == 1 && Context::class.java.isAssignableFrom(types[0])) {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        markNotificationItem(param.thisObject, context.hostContext())
                    }
                })
            }
        }
    }

    private fun findNotificationItemClass(context: FeatureContext): Class<*>? {
        KavaReflector.loadClass(NOTIFICATION_ITEM, context.hostClassLoader())?.let { return it }
        val names = runCatching {
            context.dexBridgeHolder().findClassesByStrings("id: ", "userName: ", "unreadCount:")
        }.getOrDefault(emptyList())
        for (name in names) {
            if (name == NOTIFICATION_ITEM ||
                name.endsWith(".NotificationItem") ||
                name.contains("com.tencent.mm.booter.notification")
            ) {
                KavaReflector.loadClass(name, context.hostClassLoader())?.let { return it }
            }
        }
        return null
    }

    private fun markNotificationItem(item: Any?, context: Context) {
        if (item == null) return
        val settings = CustomNotificationSettings(context)
        val customNotificationEnabled = settings.isEnabled()
        val customAvatarEnabled = CustomFriendAvatarSettings.notificationsEnabled(context)
        val blockAtAllEnabled = AtAllNotificationBlockSettings.isEnabled(context)
        if (!customNotificationEnabled && !customAvatarEnabled && !blockAtAllEnabled) return
        val talker = readFirstStringField(item, "h", "userName", "username", "talker", "talkerUserName")
        val notification = readFirstNotification(item, "f", "notification", "mNotification")
        val needsMessageIdentity = customNotificationEnabled || blockAtAllEnabled
        val msgSvrId = if (needsMessageIdentity) readFirstLongField(item, "i", "msgId") else 0L
        if (talker.isBlank() || notification == null) return
        if (notification.extras == null) notification.extras = Bundle()
        if (customNotificationEnabled || customAvatarEnabled || blockAtAllEnabled) {
            notification.extras.putString(EXTRA_TALKER, talker)
        }
        if (needsMessageIdentity) {
            if (msgSvrId > 0L) notification.extras.putLong(EXTRA_NATIVE_MSG_SVR_ID, msgSvrId)
        }
        if (!customNotificationEnabled) return
        val rule = settings.effectiveRule(talker) ?: return
        if (!rule.enabled) return
        notification.extras.putString(EXTRA_TALKER, talker)
        val unreadCount = readFirstIntField(item, "m", "j")
        if (unreadCount > 0) notification.extras.putInt(EXTRA_UNREAD_COUNT, unreadCount)
    }

    private fun readStringField(receiver: Any, name: String): String {
        return (KavaReflector.readField(receiver, name) as? String)?.trim().orEmpty()
    }

    private fun readFirstStringField(receiver: Any, vararg names: String): String {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name)?.toString()?.trim().orEmpty()
            if (value.isNotBlank()) return value
        }
        return ""
    }

    private fun readFirstRawStringField(receiver: Any, vararg names: String): String {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name) as? String ?: continue
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun readFirstIntField(receiver: Any, vararg names: String): Int {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name) as? Number ?: continue
            return value.toInt()
        }
        return 0
    }

    private fun readFirstLongField(receiver: Any, vararg names: String): Long {
        for (name in names) {
            val value = KavaReflector.readField(receiver, name) as? Number ?: continue
            return value.toLong()
        }
        return 0L
    }

    private fun readFirstNotification(receiver: Any, vararg names: String): Notification? {
        for (name in names) {
            (KavaReflector.readField(receiver, name) as? Notification)?.let { return it }
        }
        return KavaReflector.declaredFields(receiver.javaClass).firstNotNullOfOrNull { field ->
            if (Notification::class.java.isAssignableFrom(field.type)) {
                KavaReflector.readField(field, receiver) as? Notification
            } else {
                null
            }
        }
    }

    private fun shouldSuppressByRule(
        rule: CustomNotificationRule,
        message: WeChatMessageObserveApi.ObservedMessage
    ): Boolean {
        if (!rule.group) return false
        val talker = message.talker.ifBlank { message.getTalker() }
        val senderId = message.sender.ifBlank { message.getSendTalker() }
        val senderName = if (senderId.isNotBlank()) {
            WeChatApis.contact().contacts()?.getGroupMemberDisplayName(rule.talker, senderId).orEmpty()
        } else {
            ""
        }
        val pureContent = message.message?.bodyContent().orEmpty().ifBlank { message.content }
        if (rule.onlyMembers.isNotBlank() &&
            !CustomNotificationSettings.memberRuleMatches(rule.onlyMembers, senderId, senderName, pureContent)
        ) return true
        if (rule.blockMembers.isNotBlank() &&
            CustomNotificationSettings.memberRuleMatches(rule.blockMembers, senderId, senderName, pureContent)
        ) return true
        if (rule.blockAtAll && AtAllNotificationBlockRuntime.isAtAllMessage(message, talker)) return true
        if (rule.blockAtMe && message.isAtMe) return true
        return false
    }

    private fun recordNativeMention(
        talker: String,
        msgSvrId: Long,
        mentionType: WeChatMessage.AtMentionType
    ) {
        if (msgSvrId <= 0L || mentionType == WeChatMessage.AtMentionType.NONE ||
            mentionType == WeChatMessage.AtMentionType.OTHERS
        ) return
        val now = System.currentTimeMillis()
        nativeMentions[NativeMessageKey(talker, msgSvrId)] = NativeMentionRecord(mentionType, now)
        if (nativeMentions.size >= NATIVE_MENTION_CLEANUP_THRESHOLD) cleanupNativeMentions(now)
    }

    private fun shouldSuppressNativeByRule(
        rule: CustomNotificationRule,
        talker: String,
        msgSvrId: Long
    ): Boolean {
        if (!rule.group || msgSvrId <= 0L) return false
        val key = NativeMessageKey(talker, msgSvrId)
        val record = nativeMentions[key] ?: return false
        val now = System.currentTimeMillis()
        if (now - record.createdAt > NATIVE_MENTION_TTL_MS) {
            nativeMentions.remove(key, record)
            return false
        }
        return when (record.type) {
            WeChatMessage.AtMentionType.AT_ALL -> rule.blockAtAll
            WeChatMessage.AtMentionType.AT_ME -> rule.blockAtMe
            else -> false
        }
    }

    private fun cleanupNativeMentions(now: Long) {
        nativeMentions.entries.removeIf { now - it.value.createdAt > NATIVE_MENTION_TTL_MS }
    }

    private fun buildPayload(
        context: Context,
        rule: CustomNotificationRule,
        message: WeChatMessageObserveApi.ObservedMessage
    ): NotifyPayload {
        var title = displayName(rule.talker).ifBlank { rule.label.ifBlank { rule.talker } }
        var text = "[收到一条新消息]"
        if (rule.showDetail) {
            val detailText = messageDetailText(message).ifBlank { text }
            if (rule.group) {
                val senderId = message.sender.ifBlank { message.getSendTalker() }
                val senderName = if (senderId.isNotBlank()) {
                    WeChatApis.contact().contacts()?.getGroupMemberDisplayName(rule.talker, senderId).orEmpty()
                } else {
                    ""
                }
                title = displayName(rule.talker).ifBlank { rule.label.ifBlank { rule.talker } }
                text = if (senderName.isNotBlank()) "$senderName: $detailText" else detailText
            } else {
                text = detailText
            }
        }
        return NotifyPayload(
            title = title,
            text = text,
            unreadCount = activeCustomNotificationCount(context, rule.talker) + 1,
            largeIcon = loadAvatarBitmap(context, rule.talker),
            whenMillis = message.createTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
            msgId = message.getMsgId(),
            msgSvrId = message.message?.msgSvrId ?: 0L
        )
    }

    private fun buildNativePayload(context: Context, rule: CustomNotificationRule, notification: Notification): NotifyPayload {
        val fallbackTitle = displayName(rule.talker).ifBlank { rule.label.ifBlank { rule.talker } }
        val nativeText = firstNotBlank(
            notificationText(notification, EXTRA_NATIVE_TEXT),
            notificationText(notification, EXTRA_NATIVE_SUMMARY),
            notificationText(notification, Notification.EXTRA_BIG_TEXT),
            notificationText(notification, Notification.EXTRA_TEXT),
            notificationTextLine(notification),
            notificationText(notification, Notification.EXTRA_SUMMARY_TEXT),
            notification.tickerText?.toString()
        )
        return NotifyPayload(
            title = fallbackTitle,
            text = if (rule.showDetail) sanitizeDetail(nativeText).ifBlank { "[收到一条新消息]" } else "[收到一条新消息]",
            unreadCount = notification.extras?.getInt(EXTRA_UNREAD_COUNT, 0)
                ?.takeIf { it > 0 }
                ?: (activeCustomNotificationCount(context, rule.talker) + 1),
            largeIcon = loadAvatarBitmap(context, rule.talker),
            whenMillis = notification.`when`.takeIf { it > 0L } ?: System.currentTimeMillis(),
            msgSvrId = notification.extras?.getLong(EXTRA_NATIVE_MSG_SVR_ID, 0L) ?: 0L,
            contentIntent = notification.contentIntent
        )
    }

    private fun notificationText(notification: Notification, key: String): String {
        return notification.extras?.getCharSequence(key)?.toString().orEmpty()
    }

    private fun notificationTextLine(notification: Notification): String {
        val lines = notification.extras?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
        return lines.lastOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
    }

    private fun messageDetailText(message: WeChatMessageObserveApi.ObservedMessage): String {
        val body = message.message?.bodyContent().orEmpty().ifBlank { message.content }.trim()
        if (message.isText) return sanitizeDetail(stripGroupSenderPrefix(body))
        if (message.isImage) return "[图片]"
        if (message.isVoice) return "[语音]"
        if (message.isVideo) return "[视频]"
        if (message.isEmoji) return "[表情]"
        if (message.isLocation) return nativeLikeDetail(body).ifBlank { "[位置]" }
        if (message.isRedPacket) return "[红包]"
        if (message.isTransfer) {
            val transfer = message.getTransferMsg()
            val desc = sanitizeDetail(transfer?.description.orEmpty())
            return if (desc.isNotBlank()) "[转账] $desc" else "[转账]"
        }
        if (message.isQuote) {
            val quote = message.getQuoteMsg()
            val title = sanitizeDetail(quote?.title.orEmpty())
            val content = sanitizeDetail(quote?.content.orEmpty())
            return when {
                title.isNotBlank() && content.isNotBlank() -> "[引用] $title | $content"
                title.isNotBlank() -> "[引用] $title"
                content.isNotBlank() -> "[引用] $content"
                else -> nativeLikeDetail(body).ifBlank { "[引用]" }
            }
        }
        if (message.isFile) {
            val file = message.getFileMsg()
            val name = sanitizeDetail(file?.title.orEmpty().ifBlank { file?.fileName.orEmpty() })
            return if (name.isNotBlank()) "[文件] $name" else "[文件]"
        }
        if (message.isLink) return nativeLikeDetail(body)
        if (message.isMusic) return nativeLikeDetail(body)
        if (message.isNote) return nativeLikeDetail(body)
        if (message.isShareCard) return nativeLikeDetail(body)
        if (message.isVideoNumberVideo) return nativeLikeDetail(body)
        if (message.isVoipVideo) return "[视频通话]"
        if (message.isVoipVoice) return "[语音通话]"
        if (message.isVoip) return "[通话]"
        if (message.isApp) return nativeLikeDetail(body)
        return nativeLikeDetail(body)
    }

    private fun isFriendRequestMessage(message: WeChatMessageObserveApi.ObservedMessage): Boolean {
        val body = message.message?.bodyContent().orEmpty().ifBlank { message.content }
        return message.type == 37 || looksLikeFriendRequest(body)
    }

    private fun looksLikeFriendRequest(raw: String): Boolean {
        return raw.contains("antispamticket", ignoreCase = true) ||
            raw.contains("verify_ticket", ignoreCase = true) ||
            raw.contains("encryptusername", ignoreCase = true) ||
            raw.contains("fmessage", ignoreCase = true)
    }

    private fun nativeLikeDetail(raw: String): String {
        val body = stripGroupSenderPrefix(raw).trim()
        val title = sanitizeDetail(
            firstNotBlank(
                WeChatMessage.xmlTag(body, "title"),
                WeChatMessage.xmlTag(body, "des"),
                WeChatMessage.xmlTag(body, "description"),
                WeChatMessage.xmlTag(body, "content")
            )
        )
        if (title.isNotBlank()) return title
        if (body.startsWith("<")) return "[收到一条新消息]"
        return sanitizeDetail(body)
    }

    private fun stripGroupSenderPrefix(value: String): String {
        val prefixEnd = value.indexOf(":\n")
        return if (prefixEnd > 0) value.substring(prefixEnd + 2) else value
    }

    private fun sanitizeDetail(value: String): String {
        return value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun markNativeHandled(talker: String, msgSvrId: Long) {
        val now = SystemClock.elapsedRealtime()
        if (msgSvrId > 0L) {
            nativeHandledMessages[NativeMessageKey(talker, msgSvrId)] = now
        } else {
            nativeNotifyAt[talker] = now
        }
        cleanupNativeOwnership(now)
    }

    private fun isHandledByNative(talker: String, msgSvrId: Long, observedAt: Long): Boolean {
        if (msgSvrId > 0L && nativeHandledMessages.containsKey(NativeMessageKey(talker, msgSvrId))) {
            return true
        }
        val at = nativeNotifyAt[talker] ?: return false
        val offset = at - observedAt
        return offset in -NATIVE_FALLBACK_BEFORE_MS..NATIVE_FALLBACK_AFTER_MS
    }

    private fun cleanupNativeOwnership(now: Long) {
        if (nativeNotifyAt.size + nativeHandledMessages.size < 128) return
        nativeNotifyAt.entries.removeIf { now - it.value > NATIVE_OWNERSHIP_TTL_MS }
        nativeHandledMessages.entries.removeIf { now - it.value > NATIVE_OWNERSHIP_TTL_MS }
    }

    private fun sendNotification(context: Context, rule: CustomNotificationRule, payload: NotifyPayload) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val dedupKey = notificationDedupKey(rule.talker, payload)
        val claimedAt = if (dedupKey != null) claimNotification(dedupKey) ?: return else 0L
        try {
            publishNotification(context, manager, rule, payload)
        } catch (throwable: Throwable) {
            if (dedupKey != null) notificationDedupAt.remove(dedupKey, claimedAt)
            throw throwable
        }
    }

    private fun publishNotification(
        context: Context,
        manager: NotificationManager,
        rule: CustomNotificationRule,
        payload: NotifyPayload
    ) {
        val channelId = CHANNEL_PREFIX + "s${if (rule.sound) "1" else "0"}_v${if (rule.vibrate) "1" else "0"}_${rule.ringtone.hashCode()}"
        if (Build.VERSION.SDK_INT >= 26) {
            trimOldChannels(manager)
            val channel = NotificationChannel(channelId, "Hchat 自定义通知", NotificationManager.IMPORTANCE_HIGH)
            channel.enableVibration(rule.vibrate)
            channel.vibrationPattern = if (rule.vibrate) longArrayOf(0, 250, 250, 250) else longArrayOf(0)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }
        val notificationId = if (rule.mergeByTalker) mergedNotifyId(rule.talker) else nextNotifyId(rule.talker)
        val notificationToken = System.nanoTime()
        val unreadCount = if (rule.mergeByTalker) {
            maxOf(payload.unreadCount, mergedUnreadCount(manager, rule.talker) + 1)
        } else {
            payload.unreadCount
        }
        val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
        val displayText = unreadText(payload.text, unreadCount)
        val contentIntent = payload.contentIntent ?: chatPendingIntent(context, rule.talker, notificationId)
        val notificationWhen = normalizeNotificationWhen(payload.whenMillis)
        val mergedLines = if (rule.mergeByTalker) {
            mergedNotificationLines(manager, rule.talker, payload.text)
        } else {
            emptyList()
        }
        builder.setSmallIcon(icon)
            .setContentTitle(payload.title)
            .setContentText(displayText)
            .setTicker(displayText)
            .setNumber(unreadCount)
            .setWhen(notificationWhen)
            .setShowWhen(true)
            .setUsesChronometer(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
            .setExtras(Bundle().apply {
                putBoolean(MARK_HCHAT, true)
                putString(EXTRA_TALKER, rule.talker)
                putInt(EXTRA_UNREAD_COUNT, unreadCount)
                putLong(EXTRA_NOTIFICATION_TOKEN, notificationToken)
                if (rule.mergeByTalker) {
                    putCharSequenceArray(EXTRA_MERGED_LINES, mergedLines.toTypedArray())
                }
            })
        if (rule.mergeByTalker && mergedLines.size > 1) {
            val style = Notification.InboxStyle()
                .setBigContentTitle(payload.title)
                .setSummaryText("${unreadCount}条新消息")
            mergedLines.forEach { style.addLine(it) }
            builder.setStyle(style)
        }
        payload.largeIcon?.let { builder.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT < 26) {
            var defaults = 0
            if (rule.vibrate) defaults = defaults or Notification.DEFAULT_VIBRATE
            if (rule.sound && rule.ringtone.isBlank()) defaults = defaults or Notification.DEFAULT_SOUND
            builder.setDefaults(defaults)
            if (rule.sound && rule.ringtone.isNotBlank()) builder.setSound(soundUri(rule.ringtone))
            if (rule.vibrate) builder.setVibrate(longArrayOf(0, 250, 250, 250))
        }
        if (rule.markRead) {
            markReadAction(context, rule.talker, notificationId)?.let { builder.addAction(it) }
        }
        if (rule.quickReply) {
            quickReplyAction(
                context,
                rule.talker,
                notificationId,
                payload.msgId,
                payload.msgSvrId,
                rule.quoteQuickReply,
                notificationToken
            )?.let { builder.addAction(it) }
        }
        val notification = builder.build().apply {
            `when` = notificationWhen
            extras?.putBoolean(Notification.EXTRA_SHOW_WHEN, true)
            extras?.putBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            if (rule.mergeByTalker) {
                extras?.putCharSequenceArray(Notification.EXTRA_TEXT_LINES, mergedLines.toTypedArray())
            }
        }
        manager.notify(notificationId, notification)
        if (rule.mergeByTalker) {
            cancelOtherCustomNotificationsForTalker(manager, rule.talker, notificationId)
        }
        if (Build.VERSION.SDK_INT >= 26 && rule.sound) {
            playManualNotificationSound(context, soundUri(rule.ringtone))
        }
    }

    private fun notificationDedupKey(talker: String, payload: NotifyPayload): NotificationDedupKey? {
        return when {
            payload.msgSvrId > 0L -> NotificationDedupKey(talker, true, payload.msgSvrId)
            payload.msgId > 0L -> NotificationDedupKey(talker, false, payload.msgId)
            else -> null
        }
    }

    private fun normalizeNotificationWhen(value: Long): Long {
        return when {
            value <= 0L -> System.currentTimeMillis()
            value < 100_000_000_000L -> value * 1000L
            else -> value
        }
    }

    private fun mergedNotificationLines(
        manager: NotificationManager,
        talker: String,
        latestText: String
    ): List<String> {
        val previous = if (Build.VERSION.SDK_INT >= 23) {
            runCatching {
                manager.activeNotifications
                    .filter { status ->
                        val notification = status.notification ?: return@filter false
                        val extras = notification.extras ?: return@filter false
                        extras.getBoolean(MARK_HCHAT, false) &&
                            extras.getString(EXTRA_TALKER) == talker
                    }
                    .sortedBy { it.postTime }
                    .flatMap { status ->
                        val notification = status.notification ?: return@flatMap emptyList()
                        notificationHistoryLines(notification)
                    }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return (previous + latestText)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .takeLast(MAX_MERGED_NOTIFICATION_LINES)
    }

    private fun notificationHistoryLines(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()
        val stored = extras.getCharSequenceArray(EXTRA_MERGED_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
        if (!stored.isNullOrEmpty()) return stored
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { value -> value.isNotBlank() } }
        if (!lines.isNullOrEmpty()) return lines
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        return text.takeIf { it.isNotBlank() }?.let { listOf(removeUnreadPrefix(it)) }.orEmpty()
    }

    private fun removeUnreadPrefix(text: String): String {
        return text.replace(Regex("^\\[\\d+条]"), "").trim()
    }

    private fun claimNotification(key: NotificationDedupKey): Long? {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val previous = notificationDedupAt.putIfAbsent(key, now)
            if (previous == null) {
                cleanupNotificationDedup(now)
                return now
            }
            if (now - previous < NOTIFICATION_DEDUP_TTL_MS) return null
            if (notificationDedupAt.replace(key, previous, now)) {
                cleanupNotificationDedup(now)
                return now
            }
        }
    }

    private fun cleanupNotificationDedup(now: Long) {
        if (notificationDedupAt.size < NOTIFICATION_DEDUP_CLEANUP_THRESHOLD) return
        notificationDedupAt.entries.removeIf { now - it.value >= NOTIFICATION_DEDUP_TTL_MS }
    }

    private fun activeCustomNotificationCount(context: Context, talker: String): Int {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return 0
        return runCatching {
            manager.activeNotifications.count { status ->
                val notification = status.notification ?: return@count false
                notification.extras?.getBoolean(MARK_HCHAT, false) == true &&
                    notification.extras?.getString(EXTRA_TALKER) == talker
            }
        }.getOrDefault(0)
    }

    private fun mergedUnreadCount(manager: NotificationManager, talker: String): Int {
        if (Build.VERSION.SDK_INT < 23) return 0
        return runCatching {
            val counts = manager.activeNotifications.mapNotNull { status ->
                val extras = status.notification?.extras ?: return@mapNotNull null
                if (extras.getBoolean(MARK_HCHAT, false) && extras.getString(EXTRA_TALKER) == talker) {
                    extras.getInt(EXTRA_UNREAD_COUNT, 0).coerceAtLeast(1)
                } else {
                    null
                }
            }
            maxOf(counts.size, counts.maxOrNull() ?: 0)
        }.getOrDefault(0)
    }

    private fun cancelOtherCustomNotificationsForTalker(
        manager: NotificationManager,
        talker: String,
        keepNotificationId: Int
    ) {
        if (Build.VERSION.SDK_INT < 23) return
        runCatching {
            manager.activeNotifications.forEach { status ->
                if (status.id == keepNotificationId && status.tag == null) return@forEach
                val extras = status.notification?.extras ?: return@forEach
                if (extras.getBoolean(MARK_HCHAT, false) && extras.getString(EXTRA_TALKER) == talker) {
                    if (status.tag == null) manager.cancel(status.id) else manager.cancel(status.tag, status.id)
                }
            }
        }.onFailure {
            HLog.e("$TAG 合并会话通知清理失败: $talker", it)
        }
    }

    private fun unreadText(text: String, unreadCount: Int): String {
        if (unreadCount <= 1 || text.matches(Regex("^\\[\\d+条].*"))) return text
        return "[$unreadCount\u6761]$text"
    }

    private fun trimOldChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val channels = manager.notificationChannels ?: return@runCatching
            val custom = channels.mapNotNull { it.id?.takeIf { id -> id.startsWith(CHANNEL_PREFIX) } }
            if (custom.size <= 100) return@runCatching
            custom.take(custom.size - 100).forEach { manager.deleteNotificationChannel(it) }
        }
    }

    private fun quickReplyAction(
        context: Context,
        talker: String,
        notificationId: Int,
        msgId: Long,
        msgSvrId: Long,
        quoteQuickReply: Boolean,
        notificationToken: Long
    ): Notification.Action? {
        return runCatching {
            val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
                .setLabel("输入回复内容...")
                .setAllowFreeFormInput(true)
                .build()
            val intent = Intent(ACTION_QUICK_REPLY).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(EXTRA_TALKER, talker)
                putExtra(EXTRA_NOTIFY_ID, notificationId)
                putExtra(EXTRA_REPLY_MSG_ID, msgId)
                putExtra(EXTRA_NATIVE_MSG_SVR_ID, msgSvrId)
                putExtra(EXTRA_QUOTE_QUICK_REPLY, quoteQuickReply)
                putExtra(EXTRA_NOTIFICATION_TOKEN, notificationToken)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, notificationId, intent, flags)
            val builder = Notification.Action.Builder(android.R.drawable.ic_menu_send, "快捷回复", pendingIntent)
                .addRemoteInput(input)
            if (Build.VERSION.SDK_INT >= 24) {
                builder.setAllowGeneratedReplies(true)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                builder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            }
            builder.build()
        }.getOrNull()
    }

    private fun markReadAction(context: Context, talker: String, notificationId: Int): Notification.Action? {
        return runCatching {
            val intent = Intent(ACTION_MARK_READ).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_TALKER, talker)
                putExtra(EXTRA_NOTIFY_ID, notificationId)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getBroadcast(context, notificationId, intent, flags)
            Notification.Action.Builder(android.R.drawable.ic_menu_view, "已读", pendingIntent).build()
        }.getOrNull()
    }

    private fun registerNotificationActionReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val action = intent?.action ?: return
                val talker = intent.getStringExtra(EXTRA_TALKER).orEmpty()
                if (talker.isBlank()) return
                val notifyId = intent.getIntExtra(EXTRA_NOTIFY_ID, talker.hashCode())
                val msgId = intent.getLongExtra(EXTRA_REPLY_MSG_ID, 0L)
                val msgSvrId = intent.getLongExtra(EXTRA_NATIVE_MSG_SVR_ID, 0L)
                val quoteQuickReply = intent.getBooleanExtra(EXTRA_QUOTE_QUICK_REPLY, false)
                val notificationToken = intent.getLongExtra(EXTRA_NOTIFICATION_TOKEN, 0L)
                when (action) {
                    ACTION_QUICK_REPLY -> {
                        val reply = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(REMOTE_INPUT_KEY)
                            ?.toString()
                            ?.trim()
                            .orEmpty()
                        if (reply.isBlank()) return
                        val pendingResult = goAsync()
                        runCatching {
                            quickReplyExecutor.execute {
                                try {
                                    val sent = runCatching {
                                        sendQuickReply(talker, msgId, msgSvrId, reply, quoteQuickReply)
                                    }.onFailure {
                                        HLog.e("$TAG 快捷回复发送异常: $talker", it)
                                    }.getOrDefault(false)
                                    finishQuickReply(context, notifyId, notificationToken, reply, sent)
                                    if (!sent) HLog.e("$TAG 快捷回复发送失败: $talker")
                                } finally {
                                    pendingResult.finish()
                                }
                            }
                        }.onFailure {
                            pendingResult.finish()
                            HLog.e("$TAG 快捷回复任务提交失败: $talker", it)
                        }
                    }
                    ACTION_MARK_READ -> executor.execute {
                        if (QuickMarkReadRuntime.markConversationRead(context, talker, true)) {
                            cancelCustomNotificationsForTalker(context, talker, notifyId)
                        }
                    }
                }
            }
        }
        notificationActionReceiver = receiver
        val filter = IntentFilter(ACTION_QUICK_REPLY).apply { addAction(ACTION_MARK_READ) }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (t: Throwable) {
            receiverRegistered.set(false)
            notificationActionReceiver = null
            HLog.e("$TAG 注册通知动作广播失败: ${t.message}", t)
        }
    }

    private fun sendQuickReply(
        talker: String,
        msgId: Long,
        msgSvrId: Long,
        reply: String,
        quoteQuickReply: Boolean
    ): Boolean {
        val sender = WeChatApis.message().sender() ?: return false
        if (quoteQuickReply) {
            val sourceId = msgId.takeIf { it > 0L } ?: msgSvrId.takeIf { it > 0L }?.let { serverId ->
                runCatching {
                    WeChatApis.messageStore()?.getMessageBySvrId(talker, serverId)?.msgId
                }.getOrNull()?.takeIf { it > 0L } ?: serverId
            }
            if (sourceId != null && sender.sendQuote(talker, sourceId, reply)) return true
        }
        return sender.sendText(talker, reply)
    }

    private fun finishQuickReply(
        context: Context,
        notificationId: Int,
        notificationToken: Long,
        reply: String,
        sent: Boolean
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val current = if (Build.VERSION.SDK_INT >= 23) {
            runCatching {
                manager.activeNotifications.firstOrNull { it.tag == null && it.id == notificationId }?.notification
            }.getOrNull()
        } else {
            null
        }
        if (current == null || Build.VERSION.SDK_INT < 24) {
            if (sent) manager.cancel(notificationId)
            return
        }
        if (notificationToken == 0L ||
            current.extras?.getLong(EXTRA_NOTIFICATION_TOKEN, 0L) != notificationToken
        ) {
            return
        }
        val completionToken = System.nanoTime()
        runCatching {
            val builder = Notification.Builder.recoverBuilder(context, current)
                .setOnlyAlertOnce(true)
                .setContentText(if (sent) "已回复：$reply" else "发送失败，请重试")
                .setExtras(Bundle(current.extras).apply {
                    putLong(EXTRA_REPLY_COMPLETION_TOKEN, completionToken)
                })
            if (sent) builder.setRemoteInputHistory(arrayOf<CharSequence>(reply))
            manager.notify(notificationId, builder.build())
        }.onFailure {
            HLog.e("$TAG 快捷回复通知状态更新失败: $notificationId", it)
            if (sent) manager.cancel(notificationId)
            return
        }
        if (!sent) return
        mainHandler.postDelayed({
            val unchanged = if (Build.VERSION.SDK_INT >= 23) {
                runCatching {
                    manager.activeNotifications
                        .firstOrNull { it.tag == null && it.id == notificationId }
                        ?.notification
                        ?.extras
                        ?.getLong(EXTRA_REPLY_COMPLETION_TOKEN) == completionToken
                }.getOrDefault(false)
            } else {
                true
            }
            if (unchanged) manager.cancel(notificationId)
        }, QUICK_REPLY_CANCEL_DELAY_MS)
    }

    private fun cancelCustomNotificationsForTalker(
        context: Context,
        talker: String,
        fallbackNotificationId: Int
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            manager.cancel(fallbackNotificationId)
            manager.activeNotifications.forEach { status ->
                val extras = status.notification?.extras ?: return@forEach
                if (extras.getBoolean(MARK_HCHAT, false) && extras.getString(EXTRA_TALKER) == talker) {
                    if (status.tag == null) manager.cancel(status.id) else manager.cancel(status.tag, status.id)
                }
            }
        }.onFailure {
            HLog.e("$TAG 清理会话通知失败: $talker", it)
        }
    }

    private fun isCurrentChatVisible(context: Context, talker: String): Boolean {
        val chatPage = WeChatApis.interaction().chatPage()
        val current = chatPage?.currentTalker().orEmpty()
        if (current != talker || chatPage?.isInChatPage() != true) return false
        return isWechatProcessVisible(context)
    }

    private fun isWechatProcessVisible(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
        val pid = android.os.Process.myPid()
        return runCatching {
            manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.let {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            } ?: true
        }.getOrDefault(true)
    }

    private fun isInMuteWindow(rule: CustomNotificationRule): Boolean {
        if (!rule.muteEnable) return false
        val start = CustomNotificationSettings.parseTimeToSecond(rule.muteStart)
        val end = CustomNotificationSettings.parseTimeToSecond(rule.muteEnd)
        if (start < 0 || end < 0) return false
        if (start == end) return true
        val calendar = Calendar.getInstance()
        val now = calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)
        return if (start < end) now in start until end else now >= start || now < end
    }

    private fun wechatDoNotDisturbState(
        rule: CustomNotificationRule,
        talker: String
    ): Boolean? {
        if (rule.ignoreWechatDnd) return false
        return runCatching {
            WeChatApis.conversations()?.getWechatDoNotDisturbState(talker)
        }.getOrNull()
    }

    private fun isHchatNotification(notification: Notification): Boolean {
        val extras = notification.extras
        return extras?.getBoolean(MARK_HCHAT, false) == true ||
            extras?.getBoolean(MARK_KEYWORD_NOTIFICATION, false) == true ||
            (Build.VERSION.SDK_INT >= 26 &&
                (notification.channelId?.startsWith(CHANNEL_PREFIX) == true ||
                    notification.channelId?.startsWith(KEYWORD_CHANNEL_PREFIX) == true))
    }

    private fun displayName(talker: String): String {
        return NotificationNameResolver.displayName(talker)
    }

    private fun soundUri(value: String): Uri? {
        return runCatching {
            if (value.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else Uri.parse(value)
        }.getOrNull()
    }

    fun freezeRingtoneUri(context: Context, rawUri: String): String {
        if (rawUri.isBlank()) return ""
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return rawUri
        val scheme = uri.scheme.orEmpty()
        if (!scheme.equals("content", ignoreCase = true)) return rawUri
        return runCatching {
            val dir = File(context.getExternalFilesDir(null), "custom_notification_ringtones").apply { mkdirs() }
            val base = uri.lastPathSegment
                ?.let { Uri.decode(it) }
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
                ?.takeIf { it.isNotBlank() }
                ?: "ringtone_${System.currentTimeMillis()}"
            val name = if (base.contains('.')) base else "$base.mp3"
            val dst = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst, false).use { output -> input.copyTo(output) }
            } ?: return@runCatching rawUri
            Uri.fromFile(dst).toString()
        }.getOrDefault(rawUri)
    }

    private fun playManualNotificationSound(context: Context, uri: Uri?) {
        if (uri == null) return
        val now = System.currentTimeMillis()
        if (now - lastManualSoundAt < 1200L) return
        lastManualSoundAt = now
        mainHandler.post {
            val ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return@post
            runCatching {
                @Suppress("DEPRECATION")
                ringtone.setStreamType(android.media.AudioManager.STREAM_NOTIFICATION)
            }
            runCatching { ringtone.play() }
            mainHandler.postDelayed({ stopRingtone(ringtone) }, 3500L)
        }
    }

    private fun stopRingtone(ringtone: Ringtone) {
        runCatching {
            if (ringtone.isPlaying) ringtone.stop()
        }
    }

    private fun chatPendingIntent(context: Context, talker: String, id: Int): PendingIntent? {
        val intents = buildChatOpenIntents(context, talker)
        if (intents.isEmpty()) return null
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivities(context, id, intents.toTypedArray(), flags)
    }

    private fun buildChatOpenIntents(context: Context, talker: String): List<Intent> {
        val result = ArrayList<Intent>()
        val home = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.LauncherUI")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        result += home
        if (talker.isNotBlank()) {
            result += Intent().apply {
                component = ComponentName(context.packageName, "com.tencent.mm.ui.chatting.ChattingUI")
                putExtra("Chat_User", talker)
                putExtra("Chat_Mode", 1)
                putExtra("finish_direct", true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        }
        return result
    }

    private fun nextNotifyId(talker: String): Int {
        val seq = notifySeq.updateAndGet { if (it >= 999999) 1 else it + 1 }
        val raw = 0x4A000000L or ((talker.hashCode().toLong() and 0x3ffL) shl 20) or (seq.toLong() and 0xfffffL)
        return (raw and 0x7fffffffL).toInt()
    }

    private fun mergedNotifyId(talker: String): Int {
        return 0x4B000000 or (talker.hashCode() and 0x00ffffff)
    }


    private fun loadAvatarBitmap(context: Context, talker: String): Bitmap? {
        if (talker.isBlank()) return null
        if (CustomFriendAvatarSettings.notificationsEnabled(context)) {
            CustomFriendAvatarStore.loadBitmap(context, talker)?.let { return it }
        }
        if (avatarCache.containsKey(talker)) return avatarCache[talker]
        val contacts = WeChatApis.contact().contacts()
        val primary = contacts?.getAvatarUrl(talker, true).orEmpty()
        val backup = contacts?.getAvatarUrl(talker, false).orEmpty()
        val bitmap = avatarSources(context, talker, primary, backup).firstNotNullOfOrNull { source ->
            loadBitmap(source)
        }
        avatarCache[talker] = bitmap
        return bitmap
    }

    private fun avatarSources(context: Context, wxId: String, primary: String, backup: String): List<String> {
        val result = LinkedHashSet<String>()
        if (primary.isNotBlank()) result += primary
        if (backup.isNotBlank()) result += backup
        localAvatarPath(context, wxId, hd = false)?.let { result += it }
        localAvatarPath(context, wxId, hd = true)?.let { result += it }
        return result.toList()
    }

    private fun localAvatarPath(context: Context, wxId: String, hd: Boolean): String? {
        val root = avatarRoot ?: resolveAvatarRoot().also { avatarRoot = it } ?: return null
        val hash = md5Lower(wxId) ?: return null
        return buildString {
            append(root.trimEnd('/'))
            append('/')
            append(hash.substring(0, 2))
            append('/')
            append(hash.substring(2, 4))
            append("/user_")
            if (hd) append("hd_")
            append(hash)
            append(".png")
        }.takeIf { File(it).exists() }
    }

    private fun resolveAvatarRoot(): String? {
        val database = WeChatApis.database() ?: return null
        val rows = runCatching { database.query("PRAGMA database_list", null) }.getOrDefault(emptyList())
        for (row in rows) {
            val filePath = row["file"]?.toString().orEmpty()
            if (filePath.isBlank()) continue
            val parent = File(filePath).parentFile ?: continue
            val avatarDir = File(parent, "avatar")
            if (avatarDir.isDirectory || parent.isDirectory) return avatarDir.absolutePath
        }
        return null
    }

    private fun loadBitmap(value: String): Bitmap? {
        return runCatching {
            if (value.startsWith("http://") || value.startsWith("https://")) {
                val connection = URL(value).openConnection()
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.getInputStream().use { BitmapFactory.decodeStream(it) }
            } else {
                File(value).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
        }.getOrNull()
    }

    private fun md5Lower(value: String): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            buildString(digest.size * 2) {
                for (b in digest) {
                    append(((b.toInt() ushr 4) and 0xF).toString(16))
                    append((b.toInt() and 0xF).toString(16))
                }
            }
        }.getOrNull()
    }

    private data class NotifyPayload(
        val title: String,
        val text: String,
        val unreadCount: Int,
        val largeIcon: Bitmap?,
        val whenMillis: Long,
        val msgId: Long = 0L,
        val msgSvrId: Long = 0L,
        val contentIntent: PendingIntent? = null
    )

    private data class NativeNotifyPayload(
        val title: String,
        val text: String,
        val summary: String,
        val talker: String
    )

    private data class NativeMessageKey(
        val talker: String,
        val msgSvrId: Long
    )

    private data class NotificationDedupKey(
        val talker: String,
        val serverMessage: Boolean,
        val messageId: Long
    )

    private data class NativeMentionRecord(
        val type: WeChatMessage.AtMentionType,
        val createdAt: Long
    )
}
