package h.Hchat.hooks.items.antirecall

import android.content.ContentValues
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatRecalledMessage
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.luckypray.dexkit.result.MethodData

class AntiRecallFeature : BaseFeature() {
    private val handledRecalls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val publishedRecallEvents = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val messageCache = ConcurrentHashMap<String, WeChatMessage>()
    private val selfRecallMsgIds = Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val rawMessageRows = ConcurrentHashMap<Long, Map<String, Any>>()
    private var methodCachePrefs: android.content.SharedPreferences? = null
    @Volatile private var revokeEntryInstalled = false
    @Volatile private var messageStorageHookInstalled = false
    @Volatile private var msgProcessingHookInstalled = false
    @Volatile private var legacyCleanupHookInstalled = false

    override fun featureId(): String = ID

    override fun name(): String = "防撤回"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AntiRecallSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        methodCachePrefs = DexMethodCache.prefs(context.hostContext(), "Hchat_anti_recall_method_cache")
        installMessageCache()
        installSelfRecallUpdateHook(context)
        scheduleDexHooks(context)
        subscribe(Events.DexReady::class.java) {
            scheduleDexHooks(context)
        }
    }

    private fun scheduleDexHooks(context: FeatureContext) {
        DexInstallScheduler.schedule(ID, name()) {
            installDexHooks(context)
        }
    }

    @Synchronized
    private fun installDexHooks(context: FeatureContext): Boolean {
        val revokeEntries = locateRevokeEntries(context)
        installMsgProcessingClearHook(context)
        installLegacySelfRecallMediaCleanupHook(context)
        installMessageStorageRevokeHook(context, revokeEntries)
        if (!revokeEntryInstalled) {
            var installed = false
            revokeEntries.map { it.method }.forEach { method ->
                try {
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val args = param.args ?: return
                            val talker = args.getOrNull(0) as? String ?: ""
                            val svrId = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
                            val replaceMsg = args.getOrNull(3) as? String ?: ""
                            val payload = args.getOrNull(2)
                            val recallTime = resolveRecallEventTime(payload)
                            val payloadInfo = parseRevokePayload(talker, svrId, payload, replaceMsg)
                            val recalledInfo = resolveRecalledInfo(talker, svrId, payloadInfo)
                            publishRecallEvent(context, talker, svrId, recalledInfo)
                            if (!isAntiRecallEnabled(context)) return
                            val handledKey = recallHandledKey(talker, svrId, recalledInfo)
                            if (!isHandled(handledKey)) {
                                if (!handleRecall(context, talker, svrId, recalledInfo, recallTime)) return
                                markHandled(handledKey)
                            }
                            param.setResult(null)
                        }
                    })
                    installed = true
                } catch (e: Throwable) {
                    logError("防撤回入口Hook安装失败", e)
                }
            }
            revokeEntryInstalled = installed
            if (revokeEntries.isEmpty()) {
                logError("防撤回入口未找到", null)
            }
        }
        return revokeEntryInstalled &&
            messageStorageHookInstalled &&
            (msgProcessingHookInstalled || legacyCleanupHookInstalled)
    }

    private fun publishRecallEvent(
        context: FeatureContext,
        talker: String,
        svrId: Long,
        info: WeChatRecalledMessage?
    ) {
        if (talker.isBlank()) return
        val lookupSvrIds = linkedSetOf(
            info?.originSvrId ?: 0L,
            info?.newMsgId ?: 0L,
            svrId
        ).filterTo(linkedSetOf()) { it > 0L }
        val sourceMessage = info?.message?.takeIf {
            it.talker == talker &&
                !it.isRecalled() &&
                it.msgSvrId > 0L &&
                it.msgSvrId in lookupSvrIds
        }
        sourceMessage?.msgSvrId?.takeIf { it > 0L }?.let(lookupSvrIds::add)
        if ((sourceMessage?.msgId ?: 0L) <= 0L && lookupSvrIds.isEmpty()) return
        val eventId = sourceMessage?.msgSvrId?.takeIf { it > 0L }
            ?: lookupSvrIds.firstOrNull()
            ?: sourceMessage?.msgId
            ?: return
        val key = "$talker:$eventId"
        if (!publishedRecallEvents.add(key)) return
        context.eventBus().post(
            Events.MessageRecalled(
                talker = talker,
                sourceMsgId = sourceMessage?.msgId ?: 0L,
                sourceMsgSvrId = sourceMessage?.msgSvrId ?: 0L,
                lookupSvrIds = lookupSvrIds
            )
        )
        if (publishedRecallEvents.size > MAX_CACHED_MESSAGES) {
            publishedRecallEvents.take((publishedRecallEvents.size - MAX_CACHED_MESSAGES).coerceAtLeast(0))
                .forEach { publishedRecallEvents.remove(it) }
        }
    }

    private fun methodCacheKey(context: FeatureContext): String {
        return DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
    }

    private fun handleRecall(
        context: FeatureContext,
        talker: String,
        svrId: Long,
        payloadInfo: WeChatRecalledMessage?,
        recallTime: Long
    ): Boolean {
        val recalledInfo = resolveRecalledInfo(talker, svrId, payloadInfo)
        val payloadSelfRecall = isSelfRecall(payloadInfo)
        val resolvedSelfRecall = isSelfRecall(recalledInfo)
        val selfRecall = payloadSelfRecall || resolvedSelfRecall
        if (selfRecall && !keepSelfRecallEnabled(context)) {
            return false
        }
        if (selfRecall) markSelfRecallMessage(recalledInfo)
        if (selfRecall) {
            if (showNoticeEnabled(context)) {
                insertLocalNotice(context, talker, svrId, recalledInfo, recallTime, true)
            }
            return true
        }
        if (!showNoticeEnabled(context)) return true
        insertLocalNotice(context, talker, svrId, recalledInfo, recallTime, selfRecall)
        return true
    }

    private fun installMessageCache() {
        trackSubscription(WeChatApis.messageEvents()?.subscribeMessage { event ->
            rememberMessage(event)
        })
        trackSubscription(WeChatApis.messageChanges()?.subscribe { change ->
            rememberMessage(change?.message)
        })
    }

    private fun rememberMessage(event: Events.MessageReceived?) {
        if (event == null || event.msgSvrId <= 0L || event.talker.isNullOrBlank()) return
        rememberMessage(
            WeChatMessage.transient(
                event.talker,
                event.sender,
                event.content,
                if (event.createTimeSeconds > 0L) event.createTimeSeconds * 1000L else 0L,
                event.outgoing,
                event.msgType?.toIntOrNull() ?: 0,
                event.msgSvrId,
                event.msgSource,
                event.selfWxId
            )
        )
    }

    private fun rememberMessage(message: WeChatMessage?) {
        if (message == null || message.talker.isBlank() || message.msgSvrId <= 0L || message.isRecalled()) return
        messageCache[cacheKey(message.talker, message.msgSvrId)] = message
        if (messageCache.size > MAX_CACHED_MESSAGES) {
            messageCache.keys.take((messageCache.size - MAX_CACHED_MESSAGES).coerceAtLeast(0)).forEach { messageCache.remove(it) }
        }
    }

    private fun installMessageStorageRevokeHook(context: FeatureContext, revokeEntries: List<RevokeEntry>) {
        if (messageStorageHookInstalled) return
        val methods = locateMessageStorageUpdateMethods(context, revokeEntries)
        var installed = false
        methods.forEach { method ->
            try {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isAntiRecallEnabled(context) || !keepSelfRecallEnabled(context)) return
                        val msgId = (param.args?.getOrNull(0) as? Number)?.toLong() ?: return
                        val msg = param.args?.getOrNull(1) ?: return
                        val type = messageObjectType(msg)
                        if (!isWechatRecallType(type)) return
                        val outgoing = selfRecallMsgIds.contains(msgId) || messageObjectIsSend(msg)
                        if (!outgoing) return
                        markSelfRecallMsgId(msgId)
                        param.setResult(blockedReturnValue(method))
                    }
                })
                installed = true
            } catch (e: Throwable) {
                logError("自己撤回消息存储Hook安装失败", e)
            }
        }
        messageStorageHookInstalled = installed
        if (methods.isEmpty()) {
            logError("自己撤回消息存储Hook未找到", null)
        }
    }

    private fun installMsgProcessingClearHook(context: FeatureContext) {
        if (msgProcessingHookInstalled) return
        val methods = locateMsgProcessingClearMethods(context)
        var installed = false
        methods.forEach { method ->
            try {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isAntiRecallEnabled(context) || !keepSelfRecallEnabled(context)) return
                        if (!shouldBlockMsgProcessingClear(param.args)) return
                        param.setResult(null)
                    }
                })
                installed = true
            } catch (e: Throwable) {
                logError("媒体处理信息清理Hook安装失败", e)
            }
        }
        msgProcessingHookInstalled = installed
    }

    private fun installLegacySelfRecallMediaCleanupHook(context: FeatureContext) {
        if (legacyCleanupHookInstalled) return
        val methods = locateLegacySelfRecallMediaCleanupMethods(context)
        var installed = false
        methods.forEach { method ->
            try {
                HookRegistry.get().hook(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isAntiRecallEnabled(context) || !keepSelfRecallEnabled(context)) return
                        if (!shouldBlockLegacySelfRecallMediaCleanup(param.args)) return
                        param.setResult(null)
                    }
                })
                installed = true
            } catch (e: Throwable) {
                logError("旧版自己撤回媒体清理Hook安装失败", e)
            }
        }
        legacyCleanupHookInstalled = installed
    }

    private fun locateMessageStorageUpdateMethods(
        context: FeatureContext,
        revokeEntries: List<RevokeEntry>
    ): List<Method> {
        loadCachedMethods(context, "message_storage_update")
            .filter { isMessageStorageUpdateMethod(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        val result = LinkedHashSet<Method>()
        for (entry in revokeEntries) {
            val data = entry.data ?: continue
            val invokes = runCatching { data.invokes }.getOrDefault(emptyList())
            for (invoke in invokes) {
                val method = runCatching { invoke.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    ?: continue
                if (isMessageStorageUpdateMethod(method)) result.add(method)
            }
        }
        val methods = if (result.isNotEmpty()) {
            result.toList()
        } else {
            locateLegacyMessageStorageUpdateMethod(context, revokeEntries.map { it.method })
        }
        saveCachedMethods(context, "message_storage_update", methods)
        return methods
    }

    private fun locateLegacyMessageStorageUpdateMethod(context: FeatureContext, revokeMethods: List<Method>): List<Method> {
        val loader = revokeMethods.firstOrNull()?.declaringClass?.classLoader ?: context.hostClassLoader()
        val storageClass = runCatching { loader.loadClass("com.tencent.mm.storage.m9") }.getOrNull()
            ?: return emptyList()
        val msgClass = runCatching { loader.loadClass("com.tencent.mm.storage.k9") }.getOrNull()
            ?: return emptyList()
        return KavaReflector.declaredMethods(storageClass).filter { method ->
            method.parameterTypes.size == 2 &&
                method.parameterTypes[0] == java.lang.Long.TYPE &&
                method.parameterTypes[1] == msgClass
        }
    }

    private fun isMessageStorageUpdateMethod(method: Method): Boolean {
        val types = method.parameterTypes
        if (types.size != 2 && types.size != 3) return false
        if (types[0] != java.lang.Long.TYPE) return false
        if (!isWechatMessageClass(types[1])) return false
        if (types.size == 3 && types[2] != java.lang.Boolean.TYPE && types[2] != java.lang.Boolean::class.java) return false
        return method.returnType == java.lang.Integer.TYPE ||
            method.returnType == java.lang.Boolean.TYPE ||
            method.returnType == Void.TYPE
    }

    private fun blockedReturnValue(method: Method): Any? {
        return when (method.returnType) {
            java.lang.Integer.TYPE -> 0
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    }

    private fun isWechatMessageClass(clazz: Class<*>): Boolean {
        return KavaReflector.findMethod(clazz, "getMsgId") != null &&
            KavaReflector.findMethod(clazz, "getType") != null &&
            KavaReflector.findMethod(clazz, "setType", java.lang.Integer.TYPE) != null
    }

    private fun messageObjectType(msg: Any): Int {
        return runCatching {
            KavaReflector.invoke(KavaReflector.findMethod(msg.javaClass, "getType"), msg) as? Number
        }.getOrNull()?.toInt() ?: 0
    }

    private fun messageObjectIsSend(msg: Any): Boolean {
        for (name in arrayOf("isSend", "field_isSend")) {
            val value = KavaReflector.readField(msg, name)
            if ((value as? Number)?.toInt() == 1) return true
        }
        return false
    }

    private fun messageObjectMsgId(msg: Any): Long {
        runCatching {
            val method = KavaReflector.findMethod(msg.javaClass, "getMsgId")
            val value = KavaReflector.invoke(method, msg)
            if (value is Number) return value.toLong()
        }
        for (name in arrayOf("msgId", "field_msgId")) {
            val value = KavaReflector.readField(msg, name)
            if (value is Number) return value.toLong()
        }
        return 0L
    }

    private fun shouldBlockMsgProcessingClear(args: Array<Any?>?): Boolean {
        if (args.isNullOrEmpty()) return false
        val first = args[0]
        if (first != null && isWechatMessageClass(first.javaClass)) {
            val msgId = messageObjectMsgId(first)
            val type = messageObjectType(first)
            if (selfRecallMsgIds.contains(msgId) && isMediaMessageType(type)) {
                return true
            }
        }
        if (args.size >= 2 && first is String) {
            val msgId = (args[1] as? Number)?.toLong() ?: 0L
            return msgId > 0L && selfRecallMsgIds.contains(msgId)
        }
        return false
    }

    private fun shouldBlockLegacySelfRecallMediaCleanup(args: Array<Any?>?): Boolean {
        if (args == null || args.size < 2) return false
        val msg = args[0] ?: return false
        if (!isWechatMessageClass(msg.javaClass)) return false
        if (args[1] != false) return false
        if (!isInNetSceneRevokeMsgCallback()) return false
        val type = messageObjectType(msg)
        if (!isMediaMessageType(type)) return false
        val msgId = messageObjectMsgId(msg)
        val outgoing = selfRecallMsgIds.contains(msgId) || messageObjectIsSend(msg)
        if (!outgoing) return false
        markSelfRecallMsgId(msgId)
        return true
    }

    private fun isInNetSceneRevokeMsgCallback(): Boolean {
        return Throwable().stackTrace.any { element ->
            element.methodName == "onGYNetEnd" &&
                element.className.startsWith("com.tencent.mm.modelsimple.")
        }
    }

    private fun installSelfRecallUpdateHook(context: FeatureContext) {
        var count = 0
        count += hookDatabaseUpdates("com.tencent.wcdb.database.SQLiteDatabase", context)
        count += hookDatabaseUpdates("android.database.sqlite.SQLiteDatabase", context)
    }

    private fun hookDatabaseUpdates(className: String, context: FeatureContext): Int {
        val dbClass = KavaReflector.loadClass(className, context.hostClassLoader())
            ?: return 0
        var count = 0
        for (method in KavaReflector.declaredMethods(dbClass)) {
            if (!isUpdateMethod(method)) continue
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isAntiRecallEnabled(context) || !keepSelfRecallEnabled(context)) return
                    if (!fixSelfRecallUpdateValues(param.args)) return
                }
            })
            count++
        }
        return count
    }

    private fun isUpdateMethod(method: Method): Boolean {
        if (method.returnType != Integer.TYPE) return false
        if (method.name != "update" && method.name != "updateWithOnConflict") return false
        return method.parameterTypes.any { ContentValues::class.java.isAssignableFrom(it) }
    }

    private fun fixSelfRecallUpdateValues(args: Array<Any?>?): Boolean {
        if (args == null) return false
        val table = tableArg(args)
        if (!isMessageTable(table)) return false
        val values = contentValuesArg(args) ?: return false
        val content = values.getAsString("content").orEmpty()
        val type = runCatching { values.getAsInteger("type") ?: 0 }.getOrDefault(0)
        if (!isWechatRecallType(type) && !content.contains("撤回") && !content.contains("revokemsg", ignoreCase = true)) {
            return false
        }
        val msgId = msgIdFromWhere(whereClauseArg(args), stringArrayArg(args))
        if (msgId <= 0L) return false
        val rawRow = rawMessageRows[msgId] ?: queryRawMessageRow(table, msgId)?.also {
            rememberRawMessageRow(msgId, it)
        }
        val original = WeChatApis.messageStore()?.getMessageById(msgId)
        val self = selfRecallMsgIds.contains(msgId) || original?.isSend() == true
        if (self) {
            markSelfRecallMsgId(msgId)
            restoreRawRow(values, rawRow)
            values.put("type", original?.type?.takeIf { it > 0 && !isWechatRecallType(it) } ?: 1)
            values.put("isSend", original?.isSend ?: 1)
            if (original != null && original.status > 0) values.put("status", original.status)
            if (original != null) {
                if (original.imagePath.isNotBlank()) values.put("imgPath", original.imagePath)
                if (original.reserved.isNotBlank()) values.put("reserved", original.reserved)
                if (original.translatedContent.isNotBlank()) values.put("transContent", original.translatedContent)
                if (original.msgSource.isNotBlank()) values.put("msgSource", original.msgSource)
                if (original.flag != 0) values.put("flag", original.flag)
            }
            if (content.contains("撤回") || content.contains("revokemsg", ignoreCase = true)) {
                val originalContent = original?.content.orEmpty()
                if (originalContent.isNotBlank()) values.put("content", originalContent)
            }
        }
        return self
    }

    private fun queryRawMessageRow(table: String, msgId: Long): Map<String, Any>? {
        val db = WeChatApis.database() ?: return null
        queryRawMessageRowInTable(table, msgId)?.let { return it }
        for (candidate in db.messageTables()) {
            queryRawMessageRowInTable(candidate, msgId)?.let { return it }
        }
        return queryRawMessageRowInTable("message", msgId)
    }

    private fun queryRawMessageRowInTable(table: String, msgId: Long): Map<String, Any>? {
        if (table.isBlank()) return null
        val db = WeChatApis.database() ?: return null
        return runCatching {
            db.query(
                "SELECT * FROM ${quoteSqlName(table)} WHERE msgId=? LIMIT 1",
                arrayOf(msgId.toString())
            ).firstOrNull()
        }.getOrNull()
    }

    private fun restoreRawRow(values: ContentValues, rawRow: Map<String, Any>?) {
        if (rawRow == null) return
        for ((key, value) in rawRow) {
            if (key.isBlank() || NEVER_RESTORE_COLUMNS.contains(key.lowercase(Locale.US))) continue
            putContentValue(values, key, value)
        }
    }

    private fun putContentValue(values: ContentValues, key: String, value: Any) {
        when (value) {
            is ByteArray -> values.put(key, value)
            is String -> values.put(key, value)
            is Int -> values.put(key, value)
            is Long -> values.put(key, value)
            is Short -> values.put(key, value)
            is Float -> values.put(key, value)
            is Double -> values.put(key, value)
            is Boolean -> values.put(key, value)
            else -> values.put(key, value.toString())
        }
    }

    private fun markSelfRecallMessage(info: WeChatRecalledMessage?) {
        val msgId = info?.message?.msgId ?: 0L
        if (msgId > 0L) markSelfRecallMsgId(msgId)
    }

    private fun markSelfRecallMsgId(msgId: Long) {
        if (msgId <= 0L) return
        selfRecallMsgIds.add(msgId)
        if (selfRecallMsgIds.size > MAX_CACHED_MESSAGES) {
            selfRecallMsgIds.take((selfRecallMsgIds.size - MAX_CACHED_MESSAGES).coerceAtLeast(0)).forEach { selfRecallMsgIds.remove(it) }
        }
    }

    private fun rememberRawMessageRow(msgId: Long, row: Map<String, Any>) {
        synchronized(rawMessageRows) {
            rawMessageRows[msgId] = row
            val keys = rawMessageRows.keys.iterator()
            while (rawMessageRows.size > MAX_CACHED_MESSAGES && keys.hasNext()) {
                rawMessageRows.remove(keys.next())
            }
        }
    }

    private fun insertLocalNotice(
        context: FeatureContext,
        talker: String,
        svrId: Long,
        recalledInfo: WeChatRecalledMessage?,
        recallTime: Long,
        selfRecall: Boolean
    ) {
        if (talker.isBlank() || svrId <= 0L) {
            h.Hchat.utils.HLog.e("$TAG 插入提示失败: talker/newmsgid为空 talker=$talker newmsgid=$svrId")
            return
        }
        val localMessages = WeChatApis.localMessages()
        if (localMessages == null) {
            h.Hchat.utils.HLog.e("$TAG 插入提示失败: LocalMessage API为空")
            return
        }
        localMessages.ensureReady()
        val targetCreateTime = resolveRecalledCreateTime(svrId, recalledInfo)
        val notice = if (selfRecall) {
            AntiRecallSettings.SELF_NOTICE_TEXT
        } else {
            noticeText(context, talker, recalledInfo, targetCreateTime, recallTime)
        }
        if (targetCreateTime <= 0L) {
            h.Hchat.utils.HLog.e(
                "$TAG 插入提示失败: 未定位原消息时间 talker=$talker id=$svrId " +
                    "origin=${recalledInfo?.originSvrId ?: 0L} new=${recalledInfo?.newMsgId ?: 0L}"
            )
            return
        }
        val result = localMessages.insertSystemMessageAt(talker, notice, targetCreateTime + 1L)
        if (result <= 0L) {
            h.Hchat.utils.HLog.e("$TAG 插入提示失败: talker=$talker newmsgid=$svrId")
        }
    }

    private fun resolveRecalledInfo(
        talker: String,
        svrId: Long,
        payloadInfo: WeChatRecalledMessage?
    ): WeChatRecalledMessage? {
        var merged = payloadInfo
        val message = resolveRecalledMessage(talker, svrId, payloadInfo)
        if (message != null) {
            val fromMessage = WeChatRecalledMessage.fromMessage(message)
            merged = merged?.merge(fromMessage) ?: fromMessage
        }
        return merged
    }

    private fun resolveRecalledMessage(
        talker: String,
        svrId: Long,
        payloadInfo: WeChatRecalledMessage?
    ): WeChatMessage? {
        for (id in recallLookupIds(svrId, payloadInfo)) {
            messageCache[cacheKey(talker, id)]?.let { return it }
            val message = WeChatApis.messageStore()?.getMessageById(id)
                ?: WeChatApis.messageStore()?.getMessageBySvrId(talker, id)
                ?: WeChatApis.messageStore()?.getMessageBySvrId(id)
            if (message != null) {
                rememberMessage(message)
                return message
            }
        }
        return null
    }

    private fun resolveRecalledCreateTime(svrId: Long, info: WeChatRecalledMessage?): Long {
        for (id in recallLookupIds(svrId, info)) {
            val byLocalId = normalizeMillis(WeChatApis.messageStore()?.getMessageById(id)?.createTime ?: 0L)
            if (byLocalId > 0L) return byLocalId
        }
        val newMsgCreateTime = normalizeMillis(WeChatApis.messageStore()?.getCreateTimeBySvrId(info?.newMsgId ?: 0L) ?: 0L)
        if (newMsgCreateTime > 0L) return newMsgCreateTime
        val createTime = normalizeMillis(info?.bestCreateTime() ?: 0L)
        if (createTime > 0L) return createTime
        return normalizeMillis(WeChatApis.messageStore()?.getCreateTimeBySvrId(svrId) ?: 0L)
    }

    private fun normalizeMillis(createTime: Long): Long {
        return if (createTime > 0L && createTime < 10_000_000_000L) createTime * 1000L else createTime
    }

    private fun recallLookupIds(svrId: Long, payloadInfo: WeChatRecalledMessage?): List<Long> {
        return linkedSetOf(
            payloadInfo?.originSvrId ?: 0L,
            svrId,
            payloadInfo?.newMsgId ?: 0L
        ).filter { it > 0L }
    }

    private fun noticeText(
        context: FeatureContext,
        talker: String,
        recalledInfo: WeChatRecalledMessage?,
        sendTime: Long,
        recallTime: Long
    ): String {
        val savedTemplate = HchatStorage.preferences(context.hostContext(), AntiRecallSettings.PREFS_NAME)
            .getString(AntiRecallSettings.KEY_NOTICE_TEXT, AntiRecallSettings.DEFAULT_NOTICE_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: AntiRecallSettings.DEFAULT_NOTICE_TEXT
        val template = normalizeNoticeTemplate(savedTemplate)
        val timeFormat = noticeTimeFormat(context)
        val name = recallName(talker, recalledInfo)
        val content = recallText(recalledInfo)
        val result = template
            .replace(AntiRecallSettings.VAR_RECALLER_NAME, name)
            .replace(AntiRecallSettings.VAR_RECALL_TEXT, content)
            .replace(AntiRecallSettings.VAR_SEND_TIME, formatNoticeTime(sendTime, timeFormat))
            .replace(AntiRecallSettings.VAR_RECALL_TIME, formatNoticeTime(recallTime, timeFormat))
        return result
    }

    private fun resolveRecallEventTime(payload: Any?): Long {
        val addMsg = KavaReflector.readField(payload, "a")
        val selfWxId = WeChatApis.account()?.selfWxId().orEmpty()
        val createTimeSeconds = runCatching {
            WeChatApis.messageParser()?.parseAddMsg(addMsg, selfWxId)?.createTimeSeconds ?: 0L
        }.getOrDefault(0L)
        return normalizeMillis(createTimeSeconds).takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    private fun noticeTimeFormat(context: FeatureContext): String {
        return HchatStorage.preferences(context.hostContext(), AntiRecallSettings.PREFS_NAME)
            .getString(
                AntiRecallSettings.KEY_NOTICE_TIME_FORMAT,
                AntiRecallSettings.DEFAULT_NOTICE_TIME_FORMAT
            )
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: AntiRecallSettings.DEFAULT_NOTICE_TIME_FORMAT
    }

    private fun formatNoticeTime(time: Long, pattern: String): String {
        if (time <= 0L) return ""
        return runCatching {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
        }.getOrElse {
            SimpleDateFormat(AntiRecallSettings.DEFAULT_NOTICE_TIME_FORMAT, Locale.getDefault())
                .format(Date(time))
        }
    }

    private fun normalizeNoticeTemplate(template: String): String {
        return if (template == AntiRecallSettings.LEGACY_NOTICE_TEXT) {
            AntiRecallSettings.DEFAULT_NOTICE_TEXT
        } else {
            template
        }
    }

    private fun recallName(talker: String, info: WeChatRecalledMessage?): String {
        val sender = cleanWxId(
            recalledSender(talker, info)
        )
        if (sender.isBlank()) return ""
        val users = WeChatApis.users()
        val name = if (talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")) {
            users?.displayNameInGroup(talker, sender)
        } else {
            privateRecallDisplayName(sender)
        }
        return name?.takeIf { it.isNotBlank() } ?: sender
    }

    private fun privateRecallDisplayName(wxId: String): String {
        val contact = WeChatApis.contacts()?.getContact(wxId)
        return firstNotBlank(contact?.remarkName, contact?.nickname, WeChatApis.users()?.displayName(wxId))
    }

    private fun recalledSender(talker: String, info: WeChatRecalledMessage?): String {
        val infoSender = info?.bestSender()?.takeIf { it.isNotBlank() && isUsefulSender(talker, it) }
        if (infoSender != null) return infoSender
        val message = info?.message
        val direct = message?.sendTalker()?.takeIf { it.isNotBlank() && isUsefulSender(talker, it) }
        if (direct != null) return direct
        val parsed = message?.let { messageSenderFromPayload(talker, it) }
        if (!parsed.isNullOrBlank()) return parsed
        val content = info?.bestContent().orEmpty()
        val prefixEnd = content.indexOf(":\n")
        if ((talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")) && prefixEnd > 0) {
            return content.substring(0, prefixEnd)
        }
        return ""
    }

    private fun messageSenderFromPayload(talker: String, message: WeChatMessage): String {
        val source = message.getMsgSource()
        val body = message.bodyContent()
        val reserved = message.reserved
        val candidates = arrayOf(
            WeChatMessage.xmlTag(body, "fromusername"),
            WeChatMessage.xmlTag(body, "fromusr"),
            WeChatMessage.xmlTag(body, "sender"),
            WeChatMessage.xmlTag(source, "fromusername"),
            WeChatMessage.xmlTag(source, "fromusr"),
            WeChatMessage.xmlTag(reserved, "fromusername"),
            WeChatMessage.xmlTag(reserved, "fromusr"),
            WeChatMessage.msgSourceValue(source, ".msgsource.fromusername"),
            WeChatMessage.msgSourceValue(source, "fromusername"),
            WeChatMessage.msgSourceValue(source, ".msgsource.fromusr"),
            WeChatMessage.msgSourceValue(source, "fromusr"),
            WeChatMessage.msgSourceValue(source, ".msgsource.sender"),
            WeChatMessage.msgSourceValue(source, "sender")
        )
        for (candidate in candidates) {
            val normalized = cleanWxId(candidate)
            if (normalized.isNotBlank() && isUsefulSender(talker, normalized)) {
                return normalized
            }
        }
        return ""
    }

    private fun recallText(info: WeChatRecalledMessage?): String {
        val message = info?.message ?: return ""
        val raw = when {
            message.isText() -> message.bodyContent()
            isQuoteRecallMessage(message) -> quoteRecallText(message)
            else -> ""
        }
        return raw
            .takeIf { it.isNotBlank() }
            ?.replace('\n', ' ')
            ?.trim()
            .orEmpty()
    }

    private fun isQuoteRecallMessage(message: WeChatMessage): Boolean {
        val raw = message.bodyContent()
        return message.isQuote() ||
            WeChatMessage.xmlTag(raw, "type") == "57" ||
            raw.contains("<refermsg>", ignoreCase = true)
    }

    private fun quoteRecallText(message: WeChatMessage): String {
        return WeChatMessage.xmlTag(message.bodyContent(), "title")
    }

    private fun cleanWxId(value: String): String {
        return value.substringBefore(":").trim()
    }

    private fun firstNotBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }

    private fun recallHandledKey(talker: String, svrId: Long, payloadInfo: WeChatRecalledMessage?): String {
        val id = if (isSelfRecall(payloadInfo)) {
            svrId
        } else {
            payloadInfo?.originSvrId?.takeIf { it > 0L }
                ?: payloadInfo?.newMsgId?.takeIf { it > 0L }
                ?: svrId
        }
        return "$talker:$id"
    }

    private fun isHandled(key: String): Boolean {
        if (key.isBlank()) return false
        return handledRecalls.contains(key)
    }

    private fun markHandled(key: String) {
        if (key.isBlank()) return
        handledRecalls.add(key)
        if (handledRecalls.size > MAX_CACHED_MESSAGES) {
            handledRecalls.take((handledRecalls.size - MAX_CACHED_MESSAGES).coerceAtLeast(0)).forEach { handledRecalls.remove(it) }
        }
    }

    private fun cacheKey(talker: String, svrId: Long): String = "$talker:$svrId"

    private fun parseRevokePayload(
        talker: String,
        originSvrId: Long,
        payload: Any?,
        replaceMsg: String
    ): WeChatRecalledMessage? {
        val addMsg = KavaReflector.readField(payload, "a")
        val commandContent = decodeStringField(addMsg, "h")
        val rawFrom = decodeStringField(addMsg, "e")
        val from = when {
            isSelfRevokeText(replaceMsg) -> SELF_REVOKE_SENDER
            isUsefulSender(talker, rawFrom) -> rawFrom
            else -> revokeNameFromText(replaceMsg)
        }
        val to = decodeStringField(addMsg, "f")
        val originFromXml = xmlLong(commandContent, "msgid")
        val newMsgId = (KavaReflector.readField(addMsg, "r") as? Number)?.toLong()
            ?: xmlLong(commandContent, "newmsgid")
            ?: 0L
        val flag = payloadFlag(payload)
        val resolvedOrigin = originFromXml ?: originSvrId
        if (from.isBlank() && to.isBlank() && resolvedOrigin <= 0L && newMsgId <= 0L) {
            return null
        }
        return WeChatRecalledMessage(
            resolvedOrigin,
            newMsgId,
            talker,
            from,
            to,
            0L,
            "",
            "",
            flag,
            null
        )
    }

    private fun revokeNameFromText(text: String): String {
        if (text.isBlank()) return ""
        val quoted = Regex("[\"“](.*?)[\"”]\\s*撤回").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!quoted.isNullOrBlank()) return quoted
        return text.substringBefore("撤回").trim().trim('"', '“', '”')
    }

    private fun isUsefulSender(talker: String, value: String): Boolean {
        if (value.isBlank()) return false
        if (isGroupTalker(talker) && value == talker) return false
        return !value.endsWith("@chatroom") && !value.endsWith("@im.chatroom")
    }

    private fun isSelfRecall(info: WeChatRecalledMessage?): Boolean {
        val message = info?.message
        if (message?.isSend() == true) return true
        val self = WeChatApis.account()?.selfWxId().orEmpty()
        val sender = cleanWxId(info?.bestSender().orEmpty())
        if (sender == SELF_REVOKE_SENDER || sender.startsWith("你撤回")) return true
        return self.isNotBlank() && sender == self
    }

    private fun isSelfRevokeText(text: String): Boolean {
        val raw = text.trim()
        return raw.startsWith("你撤回") || raw.startsWith("\"你\"撤回") || raw.startsWith("“你”撤回")
    }

    private fun isGroupTalker(talker: String): Boolean {
        return talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
    }

    private fun isWechatRecallType(type: Int): Boolean {
        return type == RECALLED_MESSAGE_TYPE ||
            type == SELF_RECALLED_MESSAGE_TYPE ||
            type == MEDIA_RECALLED_MESSAGE_TYPE
    }

    private fun isMediaMessageType(type: Int): Boolean {
        return type == 3 || type == 34 || type == 43 || type == 47
    }

    private fun payloadFlag(payload: Any?): Int {
        val value = KavaReflector.readField(payload, "c") ?: KavaReflector.readField(payload, "d")
        return (value as? Number)?.toInt() ?: 0
    }

    private fun tableArg(args: Array<Any?>): String {
        val first = args.firstOrNull() as? String
        if (!first.isNullOrBlank()) return first
        return args.filterIsInstance<String>().firstOrNull { isMessageTable(it) }.orEmpty()
    }

    private fun isMessageTable(value: String): Boolean {
        val table = value.trim('`', '"', '[', ']')
        return table.equals("message", ignoreCase = true) ||
            table.startsWith("message", ignoreCase = true) ||
            table.contains("message", ignoreCase = true)
    }

    private fun quoteSqlName(value: String): String {
        return "`${value.replace("`", "``")}`"
    }

    private fun contentValuesArg(args: Array<Any?>): ContentValues? {
        return args.firstOrNull { it is ContentValues } as? ContentValues
    }

    private fun whereClauseArg(args: Array<Any?>): String {
        var afterValues = false
        for (arg in args) {
            if (arg is ContentValues) {
                afterValues = true
                continue
            }
            if (afterValues && arg is String) return arg
        }
        return ""
    }

    private fun stringArrayArg(args: Array<Any?>): Array<String>? {
        return args.firstOrNull { it is Array<*> && it.javaClass.componentType == String::class.java } as? Array<String>
    }

    private fun msgIdFromWhere(whereClause: String, whereArgs: Array<String>?): Long {
        if (whereClause.isBlank() || whereArgs.isNullOrEmpty()) return 0L
        if (!whereClause.contains("msgId", ignoreCase = true)) return 0L
        return whereArgs.firstNotNullOfOrNull { it.toLongOrNull()?.takeIf { value -> value > 0L } } ?: 0L
    }

    private fun decodeStringField(target: Any?, fieldName: String): String {
        val value = KavaReflector.readField(target, fieldName) ?: return ""
        if (value is String) return value
        for (name in arrayOf("d", "e", "f", "g", "a")) {
            val inner = KavaReflector.readField(value, name)
            if (inner is String && inner.isNotBlank()) return inner
        }
        return ""
    }

    private fun xmlLong(xml: String, tag: String): Long? {
        if (xml.isBlank()) return null
        val value = Regex("<$tag\\b[^>]*>\\s*(.*?)\\s*</$tag>", RegexOption.IGNORE_CASE)
            .find(xml)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("<![CDATA[", "")
            ?.replace("]]>", "")
            ?.trim()
        return value?.toLongOrNull()
    }

    private fun locateRevokeEntries(context: FeatureContext): List<RevokeEntry> {
        loadCachedMethods(context, "revoke_entries")
            .filter { isRevokeEntry(it) }
            .takeIf { it.isNotEmpty() }
            ?.map { RevokeEntry(it, null) }
            ?.let { return it }
        val anchors = arrayOf(
            arrayOf("doRevokeMsg xmlSrvMsgId", "summerbadcr get a revoke"),
            arrayOf("MM_DATA_SYSCMD_NEWXML_SUBTYPE_REVOKE", ".sysmsg.revokemsg.newmsgid"),
            arrayOf("ashutest::[oneliang][xml parse]", ".sysmsg.revokemsg.replacemsg")
        )
        val result = LinkedHashMap<String, RevokeEntry>()
        for (strings in anchors) {
            for (entry in findRevokeEntriesByStrings(context, *strings)) {
                result[entry.method.toGenericString()] = entry
            }
            if (result.isNotEmpty()) break
        }
        saveCachedMethods(context, "revoke_entries", result.values.map { it.method })
        return result.values.toList()
    }

    private fun locateMsgProcessingClearMethods(context: FeatureContext): List<Method> {
        loadCachedMethods(context, "msg_processing_clear")
            .filter { isMsgProcessingClearByMessage(it) || isMsgProcessingClearByKey(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return runCatching {
            val methods = context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        usingStrings(
                            listOf(
                                "MicroMsg.MsgProcessingManager",
                                "chris: can not parse from mmkv data!"
                            )
                        )
                    })
                }
            ).flatMap { data ->
                val owner = data.getMethodInstance(context.hostClassLoader()).declaringClass
                KavaReflector.declaredMethods(owner).filter { method ->
                    isMsgProcessingClearByMessage(method) || isMsgProcessingClearByKey(method)
                }
            }.distinctBy { it.toGenericString() }
            saveCachedMethods(context, "msg_processing_clear", methods)
            methods
        }.getOrElse {
            logError("定位媒体处理信息清理方法失败", it)
            emptyList()
        }
    }

    private fun locateLegacySelfRecallMediaCleanupMethods(context: FeatureContext): List<Method> {
        loadCachedMethods(context, "legacy_self_recall_media_cleanup")
            .filter { isLegacySelfRecallMediaCleanupMethod(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return runCatching {
            val methods = context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        usingStrings(
                            listOf(
                                "MicroMsg.NetSceneRevokeMsg",
                                "[oneliang][doSceneEnd.revokeMsg]",
                                "cannot find the msg:%d after revoke."
                            )
                        )
                    })
                }
            ).flatMap { data ->
                data.invokes.mapNotNull { invoke ->
                    runCatching { invoke.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                }
            }.filter { method ->
                isLegacySelfRecallMediaCleanupMethod(method)
            }.distinctBy { it.toGenericString() }
            saveCachedMethods(context, "legacy_self_recall_media_cleanup", methods)
            methods
        }.getOrElse {
            logError("定位旧版自己撤回媒体清理方法失败", it)
            emptyList()
        }
    }

    private fun isLegacySelfRecallMediaCleanupMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 2 &&
            isWechatMessageClass(types[0]) &&
            types[1] == java.lang.Boolean.TYPE
    }

    private fun isMsgProcessingClearByMessage(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 1 &&
            isWechatMessageClass(types[0])
    }

    private fun isMsgProcessingClearByKey(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 2 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Long.TYPE
    }

    private fun findRevokeEntriesByStrings(context: FeatureContext, vararg strings: String): List<RevokeEntry> {
        return runCatching {
            context.dexKitBridge().findMethod(
                org.luckypray.dexkit.query.FindMethod().apply {
                    matcher(org.luckypray.dexkit.query.matchers.MethodMatcher().apply {
                        usingStrings(strings.toList())
                    })
                }
            ).mapNotNull { data ->
                val method = runCatching { data.getMethodInstance(context.hostClassLoader()) }.getOrNull()
                    ?: return@mapNotNull null
                if (isRevokeEntry(method)) RevokeEntry(method, data) else null
            }
        }.getOrElse {
            logError("防撤回入口定位失败", it)
            emptyList()
        }
    }

    private fun loadCachedMethods(context: FeatureContext, name: String): List<Method> {
        val prefs = methodCachePrefs ?: return emptyList()
        return DexMethodCache.loadList(prefs, methodCacheKey(context), context.hostClassLoader(), name)
    }

    private fun saveCachedMethods(context: FeatureContext, name: String, methods: List<Method>) {
        val prefs = methodCachePrefs ?: return
        if (methods.isEmpty()) {
            DexMethodCache.clear(prefs, methodCacheKey(context), name)
        } else {
            DexMethodCache.saveList(prefs, methodCacheKey(context), name, methods)
        }
    }

    private fun isRevokeEntry(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == Void.TYPE &&
            types.size == 6 &&
            types[0] == String::class.java &&
            types[1] == java.lang.Long.TYPE &&
            types[3] == String::class.java &&
            types[4] == String::class.java &&
            types[5] == String::class.java
    }

    private fun isAntiRecallEnabled(context: FeatureContext): Boolean {
        return HchatStorage.preferences(context.hostContext(), AntiRecallSettings.PREFS_NAME)
            .getBoolean(AntiRecallSettings.KEY_ENABLE, AntiRecallSettings.DEFAULT_ENABLE)
    }

    private fun keepSelfRecallEnabled(context: FeatureContext): Boolean {
        return HchatStorage.preferences(context.hostContext(), AntiRecallSettings.PREFS_NAME)
            .getBoolean(AntiRecallSettings.KEY_KEEP_SELF_RECALL, AntiRecallSettings.DEFAULT_KEEP_SELF_RECALL)
    }

    private fun showNoticeEnabled(context: FeatureContext): Boolean {
        return HchatStorage.preferences(context.hostContext(), AntiRecallSettings.PREFS_NAME)
            .getBoolean(AntiRecallSettings.KEY_SHOW_NOTICE, AntiRecallSettings.DEFAULT_SHOW_NOTICE)
    }

    private fun short(value: String, max: Int = 120): String {
        val raw = value.replace('\n', ' ')
        return if (raw.length <= max) raw else raw.take(max) + "..."
    }

    companion object {
        const val ID = "anti_recall"
        private const val TAG = "[Hchat:AntiRecall]"
        private const val MAX_CACHED_MESSAGES = 1000
        private const val SELF_REVOKE_SENDER = "你"
        private const val RECALLED_MESSAGE_TYPE = 268445456
        private const val SELF_RECALLED_MESSAGE_TYPE = 268445458
        private const val MEDIA_RECALLED_MESSAGE_TYPE = 285222674
        private val NEVER_RESTORE_COLUMNS = setOf("rowid")
    }

    private data class RevokeEntry(
        val method: Method,
        val data: MethodData?
    )
}
