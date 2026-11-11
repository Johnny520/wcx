package h.Hchat.hooks.items.quickread

import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.api.runtime.WeChatDatabaseApi
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

object QuickMarkReadRuntime {
    private const val TAG = "[Hchat:QuickRead]"
    private const val CACHE_PREFS = "Hchat_quick_mark_read_method_cache"
    private const val CACHE_SCHEMA = "quick_mark_read_native_v1"
    private const val CACHE_NATIVE_MARK_READ = "native_mark_read"
    private const val AT_ALERT_FLAG = 0x00100000
    private const val GROUP_COLLECTION_ALERT_FLAG = 0x01000000
    private const val VOICE_MESSAGE_TYPE = 34
    private const val VOICE_LOOKUP_LIMIT = 8
    private const val UNREAD_WHERE =
            "IFNULL(unReadCount,0)>0 OR IFNULL(unReadMuteCount,0)>0 OR " +
            "IFNULL(atCount,0)>0 OR IFNULL(hbMarkRed,0)>0 OR " +
            "(IFNULL(attrflag,0)&$AT_ALERT_FLAG)!=0 OR " +
            "(IFNULL(attrflag,0)&$GROUP_COLLECTION_ALERT_FLAG)!=0 OR " +
            "((CAST(IFNULL(msgType,'0') AS INTEGER)&65535)=$VOICE_MESSAGE_TYPE " +
            "AND IFNULL(isSend,0)=0)"

    private data class PendingVoice(
        val msgId: Long,
        val originalContent: String,
        val playedContent: String
    )

    private data class ReadTarget(
        val username: String,
        val hasNativeUnread: Boolean,
        val clearRedPacketAlert: Boolean,
        val clearGroupCollectionAlert: Boolean,
        val hasUnreadVoice: Boolean,
        val pendingVoice: PendingVoice?
    ) {
        val hasConversationAlert: Boolean
            get() = clearRedPacketAlert || clearGroupCollectionAlert
    }

    private data class MarkResult(
        val changed: Boolean,
        val complete: Boolean
    )

    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var featureContext: FeatureContext? = null
    @Volatile private var nativeMarkReadMethod: Method? = null
    @Volatile private var nativeMarkReadStorage: Any? = null

    fun install(context: FeatureContext) {
        featureContext = context
        if (!installed.compareAndSet(false, true)) return
        prepareNativeMarkRead(context, allowDexSearch = false)
        DexInstallScheduler.schedule(
            "${QuickMarkReadFeature.ID}:native_mark_read",
            "快捷已读原生已读入口",
            stage = DexInstallScheduler.Stage.WARMUP
        ) {
            prepareNativeMarkRead(context, allowDexSearch = true)
        }
    }

    @JvmStatic
    fun isDragEnabled(context: Context?): Boolean {
        if (context == null) return false
        val prefs = HchatStorage.preferences(context, QuickMarkReadSettings.PREFS_NAME)
        return prefs.getBoolean(
            QuickMarkReadSettings.KEY_DRAG_READ,
            QuickMarkReadSettings.DEFAULT_DRAG_READ
        )
    }

    @JvmStatic
    fun isPlusMenuEnabled(context: Context?): Boolean {
        if (context == null) return false
        val prefs = HchatStorage.preferences(context, QuickMarkReadSettings.PREFS_NAME)
        return prefs.getBoolean(
            QuickMarkReadSettings.KEY_PLUS_MENU_READ,
            QuickMarkReadSettings.DEFAULT_PLUS_MENU_READ
        )
    }

    @JvmStatic
    fun markAllRead(context: Context?, showToast: Boolean): Int {
        val appContext = context?.applicationContext ?: context
        val database = WeChatApis.database()
        if (database == null) {
            if (showToast) toast(appContext, "数据库未就绪")
            return -1
        }
        val targets = unreadConversationTargets(database)
        if (!database.isReady) {
            if (showToast) toast(appContext, "数据库未就绪")
            return -1
        }
        if (targets.isEmpty()) {
            if (showToast) toast(appContext, "没有未读会话")
            return 0
        }
        val method = nativeMarkReadMethod()
        if (method == null) {
            if (showToast) toast(appContext, "原生已读入口未就绪")
            return -1
        }
        val storage = nativeMarkReadStorage(database, method)
        if (storage == null) {
            if (showToast) toast(appContext, "原生会话存储未就绪")
            return -1
        }
        var changed = 0
        var failed = 0
        for (target in targets) {
            val result = markTargetRead(database, method, storage, target)
            if (result.changed) changed++
            if (!result.complete) failed++
        }
        if (changed <= 0) {
            if (showToast) toast(appContext, "全部已读失败")
            HLog.e("$TAG 原生已读未成功处理会话")
            return -1
        }
        cancelWechatNotifications(appContext)
        if (showToast) {
            val message = if (failed > 0) {
                "已读 ${changed} 个会话，${failed} 个提醒处理失败"
            } else {
                "已读 ${changed} 个会话"
            }
            toast(appContext, message)
        }
        return changed
    }

    @JvmStatic
    fun markConversationRead(context: Context?, username: String?, showToast: Boolean): Boolean {
        val appContext = context?.applicationContext ?: context
        val talker = username?.trim().orEmpty()
        if (talker.isEmpty()) {
            if (showToast) toast(appContext, "会话无效")
            return false
        }
        val database = WeChatApis.database()
        if (database == null || !database.isReady) {
            if (showToast) toast(appContext, "数据库未就绪")
            return false
        }
        val method = nativeMarkReadMethod()
        if (method == null) {
            if (showToast) toast(appContext, "原生已读入口未就绪")
            return false
        }
        val storage = nativeMarkReadStorage(database, method)
        if (storage == null) {
            if (showToast) toast(appContext, "原生会话存储未就绪")
            return false
        }
        val target = conversationReadTarget(database, talker)
        val marked = if (target != null) {
            markTargetRead(database, method, storage, target).complete
        } else {
            invokeNativeMarkRead(method, storage, talker)
        }
        if (!marked && showToast) toast(appContext, "会话已读失败")
        return marked
    }

    private fun markTargetRead(
        database: WeChatDatabaseApi,
        method: Method,
        storage: Any,
        target: ReadTarget
    ): MarkResult {
        val conversationAlertCleared = clearConversationAlerts(database, target)
        val voiceMarked = markLatestVoicePlayed(database, target)
        val nativeMarked = invokeNativeMarkRead(method, storage, target.username)

        val nativeChanged = target.hasNativeUnread && nativeMarked
        val conversationAlertChanged = target.hasConversationAlert &&
            conversationAlertCleared && nativeMarked
        val voiceChanged = target.hasUnreadVoice && voiceMarked
        val complete = (!target.hasNativeUnread || nativeMarked) &&
            (!target.hasConversationAlert || conversationAlertCleared && nativeMarked) &&
            (!target.hasUnreadVoice || voiceMarked)
        return MarkResult(
            changed = nativeChanged || conversationAlertChanged || voiceChanged,
            complete = complete
        )
    }

    private fun invokeNativeMarkRead(method: Method, storage: Any, username: String): Boolean {
        return runCatching {
            KavaReflector.invokeOrThrow(method, storage, username) as? Boolean == true
        }.onFailure {
            HLog.e("$TAG 调用原生已读失败: $username", it)
        }.getOrDefault(false)
    }

    private fun clearConversationAlerts(
        database: WeChatDatabaseApi,
        target: ReadTarget
    ): Boolean {
        if (!target.hasConversationAlert) return true
        val values = ContentValues()
        if (target.clearRedPacketAlert) {
            values.put("hbMarkRed", 0)
        }
        if (target.clearGroupCollectionAlert) {
            val attrFlag = conversationAttrFlag(database, target.username)
            if (attrFlag == null) {
                HLog.e("$TAG 读取群收款提醒状态失败: ${target.username}")
                return false
            }
            values.put("attrflag", attrFlag and GROUP_COLLECTION_ALERT_FLAG.inv())
        }
        val rows = database.update(
            "rconversation",
            values,
            "username=?",
            arrayOf(target.username)
        )
        if (rows <= 0) {
            HLog.e("$TAG 清理会话红字提醒失败: ${target.username}")
            return false
        }
        return true
    }

    private fun conversationAttrFlag(database: WeChatDatabaseApi, username: String): Int? {
        val rows = database.query(
            "SELECT attrflag FROM rconversation WHERE username=? LIMIT 1",
            arrayOf(username)
        )
        val value = rows.firstOrNull()?.get("attrflag") ?: return null
        return intValue(value)
    }

    private fun markLatestVoicePlayed(
        database: WeChatDatabaseApi,
        target: ReadTarget
    ): Boolean {
        if (!target.hasUnreadVoice) return true
        val voice = target.pendingVoice
        if (voice == null) {
            HLog.e("$TAG 未找到待标记的语音消息: ${target.username}")
            return false
        }
        val nativeMessage = database.nativeMessageById(voice.msgId)
        val nativeUpdated = nativeMessage != null && database.updateNativeMessageContent(
            voice.msgId,
            voice.playedContent,
            nativeMessage
        )
        val fallbackAttempts = arrayListOf<String>()
        val messageUpdated = nativeUpdated || updateVoiceMessageContentFallback(
            database,
            target.username,
            voice,
            fallbackAttempts
        )
        if (!messageUpdated) {
            HLog.e(
                "$TAG 更新语音已播放状态失败: ${target.username} " +
                    "msgId=${voice.msgId} stage=message native=${nativeMessage != null} " +
                    "fallback=${fallbackAttempts.joinToString().ifBlank { "none" }}"
            )
            return false
        }

        val conversationValues = ContentValues().apply {
            put("content", voice.playedContent)
        }
        val conversationRows = database.update(
            "rconversation",
            conversationValues,
            "username=? AND content=?",
            arrayOf(target.username, voice.originalContent)
        )
        if (conversationRows > 0) return true

        val currentContent = database.queryFirstString(
            "SELECT content FROM rconversation WHERE username=? LIMIT 1",
            arrayOf(target.username),
            "content"
        )
        if (currentContent == voice.playedContent ||
            (currentContent.isNotEmpty() && currentContent != voice.originalContent)
        ) {
            return true
        }
        HLog.e(
            "$TAG 更新语音已播放状态失败: ${target.username} " +
                "msgId=${voice.msgId} stage=conversation rows=$conversationRows"
        )
        return false
    }

    private fun updateVoiceMessageContentFallback(
        database: WeChatDatabaseApi,
        talker: String,
        voice: PendingVoice,
        attempts: MutableList<String>
    ): Boolean {
        val values = ContentValues().apply {
            put("content", voice.playedContent)
        }
        val tables = linkedSetOf<String>()
        database.messageTableForTalker(talker)
            .takeIf(String::isNotBlank)
            ?.let(tables::add)
        tables += "message"
        tables.forEach { table ->
            val rows = database.update(
                table,
                values,
                "msgId=? AND content=?",
                arrayOf(voice.msgId.toString(), voice.originalContent)
            )
            attempts += "$table:$rows"
            if (rows > 0) return true
        }
        return WeChatApis.messageStore()?.getMessageById(voice.msgId)?.content ==
            voice.playedContent
    }

    private fun nativeMarkReadMethod(): Method? {
        nativeMarkReadMethod?.let { return it }
        val context = featureContext ?: return null
        return locateNativeMarkReadMethod(context, allowDexSearch = false)
    }

    private fun prepareNativeMarkRead(context: FeatureContext, allowDexSearch: Boolean): Boolean {
        val method = locateNativeMarkReadMethod(context, allowDexSearch) ?: return false
        val database = WeChatApis.database() ?: return false
        return nativeMarkReadStorage(database, method) != null
    }

    private fun nativeMarkReadStorage(database: WeChatDatabaseApi, method: Method): Any? {
        nativeMarkReadStorage?.let { cached ->
            if (method.declaringClass.isInstance(cached)) return cached
            nativeMarkReadStorage = null
        }
        return database.storageObjectForMethod(method)?.also {
            nativeMarkReadStorage = it
        }
    }

    private fun locateNativeMarkReadMethod(context: FeatureContext, allowDexSearch: Boolean): Method? {
        val prefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
        val methodCacheKey = methodCacheKey(context)
        val cached = DexMethodCache.load(
            prefs,
            methodCacheKey,
            context.hostClassLoader(),
            CACHE_NATIVE_MARK_READ
        )
        if (cached != null && isNativeMarkReadMethod(cached)) {
            nativeMarkReadMethod = cached
            return cached
        }
        if (cached != null) {
            DexMethodCache.clear(prefs, methodCacheKey, CACHE_NATIVE_MARK_READ)
            nativeMarkReadMethod = null
            nativeMarkReadStorage = null
        }
        if (!allowDexSearch) return null

        val located = findNativeMarkReadMethods(context)
            .filter { isNativeMarkReadMethod(it) }
            .distinctBy { it.toGenericString() }
        val method = located.singleOrNull()
        if (method != null) {
            nativeMarkReadMethod = method
            nativeMarkReadStorage = null
            DexMethodCache.save(prefs, methodCacheKey, CACHE_NATIVE_MARK_READ, method)
            return method
        }
        DexMethodCache.clear(prefs, methodCacheKey, CACHE_NATIVE_MARK_READ)
        nativeMarkReadMethod = null
        nativeMarkReadStorage = null
        HLog.e("$TAG 原生已读入口定位失败，候选数=${located.size}")
        return null
    }

    private fun methodCacheKey(context: FeatureContext): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
            .takeIf { it.isNotBlank() }
            ?.let { "$it|$CACHE_SCHEMA" }
            .orEmpty()
    }

    private fun findNativeMarkReadMethods(context: FeatureContext): List<Method> {
        return runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(
                        MethodMatcher().apply {
                            usingEqStrings("updateUnreadByTalker %s", "unReadMuteCount", "atCount")
                        }
                    )
                }
            ).mapNotNull { it.getMethodInstance(context.hostClassLoader()) }
        }.getOrElse {
            HLog.e("$TAG DexKit 定位原生已读入口失败: ${it.message}", it)
            emptyList()
        }
    }

    private fun isNativeMarkReadMethod(method: Method): Boolean {
        if (KavaReflector.isStatic(method)) return false
        val returnType = method.returnType
        val params = method.parameterTypes
        return (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.javaObjectType) &&
            params.size == 1 &&
            params[0] == String::class.java
    }

    private fun unreadConversationTargets(database: WeChatDatabaseApi): List<ReadTarget> {
        return database.query(
            "SELECT username,unReadCount,unReadMuteCount,atCount,hbMarkRed,attrflag," +
                "msgType,isSend,content FROM rconversation WHERE $UNREAD_WHERE",
            null
        ).mapNotNull { row -> readTarget(row) }
            .distinctBy { it.username }
    }

    private fun conversationReadTarget(
        database: WeChatDatabaseApi,
        username: String
    ): ReadTarget? {
        return database.query(
            "SELECT username,unReadCount,unReadMuteCount,atCount,hbMarkRed,attrflag," +
                "msgType,isSend,content FROM rconversation WHERE username=? LIMIT 1",
            arrayOf(username)
        ).firstOrNull()?.let { readTarget(it, requireAlert = false) }
    }

    private fun readTarget(
        row: Map<String, *>,
        requireAlert: Boolean = true
    ): ReadTarget? {
        val username = row["username"]?.toString()?.trim().orEmpty()
        if (username.isEmpty()) return null
        val hasNativeUnread = intValue(row["unReadCount"]) > 0 ||
            intValue(row["unReadMuteCount"]) > 0 ||
            intValue(row["atCount"]) > 0 ||
            (intValue(row["attrflag"]) and AT_ALERT_FLAG) != 0
        val clearRedPacketAlert = intValue(row["hbMarkRed"]) > 0
        val clearGroupCollectionAlert =
            (intValue(row["attrflag"]) and GROUP_COLLECTION_ALERT_FLAG) != 0
        val hasUnreadVoice = WeChatMessageTypes.isVoice(intValue(row["msgType"])) &&
            intValue(row["isSend"]) == 0 &&
            playedVoiceContent(row["content"]?.toString().orEmpty()) != null
        val pendingVoice = if (hasUnreadVoice) {
            resolvePendingVoice(username, row["content"]?.toString().orEmpty())
        } else {
            null
        }
        if (requireAlert && !hasNativeUnread && !clearRedPacketAlert &&
            !clearGroupCollectionAlert && !hasUnreadVoice
        ) {
            return null
        }
        return ReadTarget(
            username = username,
            hasNativeUnread = hasNativeUnread,
            clearRedPacketAlert = clearRedPacketAlert,
            clearGroupCollectionAlert = clearGroupCollectionAlert,
            hasUnreadVoice = hasUnreadVoice,
            pendingVoice = pendingVoice
        )
    }

    private fun resolvePendingVoice(username: String, conversationContent: String): PendingVoice? {
        val messages = WeChatApis.messageStore()
            ?.getRecentMessages(username, VOICE_LOOKUP_LIMIT)
            .orEmpty()
        val exact = messages.firstNotNullOfOrNull { message ->
            pendingVoice(message)?.takeIf { message.content == conversationContent }
        }
        return exact ?: messages.firstOrNull()?.let(::pendingVoice)
    }

    private fun pendingVoice(message: WeChatMessage): PendingVoice? {
        if (!message.isVoice() || !message.isIncoming()) return null
        val playedContent = playedVoiceContent(message.content) ?: return null
        return PendingVoice(message.msgId, message.content, playedContent)
    }

    internal fun playedVoiceContent(content: String): String? {
        if (content.isEmpty()) return null
        val lineEnding = when {
            content.endsWith("\r\n") -> "\r\n"
            content.endsWith("\n") -> "\n"
            else -> ""
        }
        val body = if (lineEnding.isNotEmpty()) {
            content.dropLast(lineEnding.length)
        } else {
            content
        }
        val markerEnd = if (body.endsWith(":")) body.length - 1 else body.length
        val markerStart = body.lastIndexOf(':', markerEnd - 1) + 1
        if (markerStart <= 0 || body.substring(markerStart, markerEnd) != "0") return null
        val durationEnd = markerStart - 1
        val durationStart = body.lastIndexOf(':', durationEnd - 1) + 1
        if (durationStart >= durationEnd ||
            body.substring(durationStart, durationEnd).toLongOrNull() == null
        ) {
            return null
        }
        return body.substring(0, markerStart) + "1" + body.substring(markerEnd) + lineEnding
    }

    private fun intValue(value: Any?): Int {
        if (value is Number) return value.toInt()
        return value?.toString()?.toIntOrNull() ?: 0
    }

    private fun cancelWechatNotifications(context: Context?) {
        if (context == null) return
        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancelAll()
        }.onFailure {
            HLog.e("$TAG 清理微信通知失败", it)
        }
    }

    private fun toast(context: Context?, text: String) {
        if (context == null) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
