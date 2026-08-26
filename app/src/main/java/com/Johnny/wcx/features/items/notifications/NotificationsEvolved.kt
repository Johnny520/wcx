package com.Johnny.wcx.features.items.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.hookBeforeDirectly
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.getSystemService
import com.Johnny.wcx.utils.collections.LruCache
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import com.Johnny.wcx.utils.strings.replaceEmojis
import com.Johnny.wcx.utils.strings.replaceRichContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.pathString
import kotlin.io.path.writeBytes
import kotlin.time.Duration.Companion.milliseconds

@Feature(
    name = "通知进化",
    categories = ["通知"],
    description = "让微信的新消息通知更易用\n1. 「快速回复」按钮\n2. 「标记为已读」按钮\n3. 使用原生对话样式 (MessagingStyle)"
)
object NotificationsEvolved : SwitchFeature(), IResolveDex {

    private const val TAG = "NotificationsEvolved"

    // com.tencent.mm.booter.notification.x.d(x, String talker, String content, int, int, boolean)
    // args[1] is the talker wxid. Anchored on a log string unique to that method.
    private val methodDealNotify by dexMethod {
        searchPackages("com.tencent.mm.booter.notification")
        matcher {
            paramCount(6)
            usingEqStrings("jacks dealNotify, talker:%s, msgtype:%d, tipsFlag:%d, isRevokeMesasge:%B content:%s")
        }
    }

    // talker wxid captured from x.d, read back in the synchronous Notification.Builder.build() hook
    private val currentTalker = ThreadLocal<String?>()

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_PUSH

    private val lastGroupChatSender = LruCache<String, String>()

    // sender 头像缓存：senderKey(会话|发送者) -> Icon，异步预取，下一条通知构建时生效
    private val senderAvatarCache = LruCache<String, Icon>(maxLimit = 64)

    private data class HistoryEntry(val senderName: String, val text: String, val timestamp: Long, val senderKey: String)

    // Per-conversation message history rebuilt into MessagingStyle on each notification update.
    // Cleared when the user replies or marks as read; bounded to avoid unbounded growth.
    private val messageHistory = LinkedHashMap<String, ArrayDeque<HistoryEntry>>()
    private const val MAX_HISTORY = 7

    private const val ACTION_REPLY = "${PackageNames.WECHAT}.ACTION_WEKIT_REPLY"
    private const val ACTION_MARK_READ = "${PackageNames.WECHAT}.ACTION_WEKIT_MARK_READ"
    private const val ACTION_NOTIFICATION_OPENED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_OPENED"
    private const val ACTION_NOTIFICATION_DISMISSED = "${PackageNames.WECHAT}.ACTION_WEKIT_NOTIFICATION_DISMISSED"

    // WeChat's original contentIntent per convWxId, stored so we can fire it after clearing history.
    private val pendingContentIntents = HashMap<String, PendingIntent>()

    private lateinit var meAvatarIcon: Icon

    private val meAvatarPath by lazy { KnownPaths.moduleData / "me_avatar" }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val targetWxId = intent.getStringExtra("extra_target_wxid") ?: return
            val notificationManager =
                context.getSystemService<NotificationManager>()

            when (intent.action) {
                ACTION_REPLY -> {
                    val results = RemoteInput.getResultsFromIntent(intent) ?: return
                    val replyContent = results.getCharSequence("key_reply_content")?.toString()

                    if (replyContent.isNullOrEmpty())
                        return

                    WeLogger.i(TAG, "quick replying '$replyContent' to $targetWxId")
                    WeMessageApi.sendText(targetWxId, replyContent)
                    WeConversationApi.markAsRead(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())
                }

                ACTION_MARK_READ -> {
                    WeLogger.i(TAG, "marking chat as read for $targetWxId")
                    WeConversationApi.markAsRead(targetWxId)
                    messageHistory.remove(targetWxId)
                    pendingContentIntents.remove(targetWxId)
                    notificationManager.cancel(targetWxId.hashCode())
                }

                ACTION_NOTIFICATION_OPENED -> {
                    // Notification was tapped — clear history, then hand off to WeChat's own intent.
                    messageHistory.remove(targetWxId)
                    pendingContentIntents.remove(targetWxId)?.send()
                }

                ACTION_NOTIFICATION_DISMISSED -> {
                    // Notification was swiped away — just clear history.
                    messageHistory.remove(targetWxId)
                    pendingContentIntents.remove(targetWxId)
                }
            }
        }
    }

    private val MESSAGE_REGEX = Regex("""^(\[\d+条])?(.+?)?: (.*)$""", RegexOption.DOT_MATCHES_ALL)

    override fun onEnable() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val bitmap: Bitmap
                if (meAvatarPath.exists()) {
                    bitmap = BitmapFactory.decodeFile(meAvatarPath.pathString)
                } else {
                    while (runCatching { WeApi.selfWxId.isEmpty() }
                            .getOrDefault(true)) {
                        delay(2000.milliseconds)
                    }

                    val urlString = WeDatabaseApi.getAvatarUrl(WeApi.selfWxId)
                    val connection = URL(urlString).openConnection()
                            as HttpURLConnection
                    connection.doInput = true

                    connection.inputStream.use { input ->
                        val bytes = input.readBytes()
                        meAvatarPath.writeBytes(bytes)
                        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                return@runCatching Icon.createWithBitmap(bitmap)
            }.onFailure { e ->
                WeLogger.e(TAG, "failed to fetch me avatar", e)
            }.onSuccess { meAvatarIcon = it }
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_REPLY)
            addAction(ACTION_MARK_READ)
            addAction(ACTION_NOTIFICATION_OPENED)
            addAction(ACTION_NOTIFICATION_DISMISSED)
        }
        ContextCompat.registerReceiver(
            HostInfo.application, notificationReceiver, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Capture the exact talker wxid before WeChat builds the notification.
        // x.d → m0.a → e0.b → Notification.Builder.build() all run synchronously on
        // this thread, so the build() hook below reads it back via the ThreadLocal.
        methodDealNotify.hookBefore {
            currentTalker.set(args[1] as? String)
        }

        Notification.Builder::class.reflekt()
            .firstMethod { name = "build" }
            .hookBefore {
                val context = HostInfo.application

                val builder = thisObject as Notification.Builder
                val notif = builder.reflekt().firstField { type = Notification::class }
                    .get() as Notification
                val channelId = notif.channelId

                if (channelId != "message_channel_new_id") {
                    return@hookBefore
                }

                val notifTitle = notif.extras.getString(Notification.EXTRA_TITLE)
                    ?: "未知对话 (请向模块开发者报告错误)"
                val notifText =
                    notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: "未知内容 (请向模块开发者报告错误)"

                // 1. Resolve exact WXID from the talker captured in the x.d hook
                val convWxId = currentTalker.get()
                if (convWxId == null) {
                    WeLogger.w(TAG, "no talker captured for $notifTitle, skipping enhancements")
                    return@hookBefore
                }

                val match = MESSAGE_REGEX.find(notifText)

                var senderName: String
                var text: String
                if (match == null) {
                    WeLogger.w(
                        TAG,
                        "failed to match message regex, using raw sender name & text content"
                    )
                    senderName = notifTitle
                    text = notifText
                } else {
                    senderName = match.groupValues[2].takeIf { it.isNotEmpty() }
                        ?.also { lastGroupChatSender[convWxId] = it }
                        ?: lastGroupChatSender[convWxId] ?: run {
                            WeLogger.w(
                                TAG,
                                "couldn't find sender name in either notification or cache"
                            )
                            notifTitle
                        }
                    text = match.groupValues[3]
                }

                text = text
                    .replaceRichContent()
                    .replaceEmojis()

                WeLogger.i(TAG, "enhancing notification for $notifTitle ($convWxId)")

                // 2. Build the MessagingStyle, accumulating messages so that "2" doesn't
                //    erase "1" when the user hasn't acted on the notification yet.
                // TODO: add cropping
                val mePerson = Person.Builder().setName("我")
                    .apply {
                        if (::meAvatarIcon.isInitialized)
                            setIcon(meAvatarIcon)
                    }
                    .build()
                val messagingStyle = Notification.MessagingStyle(mePerson)

                if (convWxId.isGroupChatWxId) {
                    messagingStyle.isGroupConversation = true
                    messagingStyle.conversationTitle = notifTitle
                } else {
                    senderName = notifTitle
                }

                // Append the new message to this conversation's history, then replay
                // the whole history into the style so previous messages are not lost.
                val history = messageHistory.getOrPut(convWxId) { ArrayDeque() }
                history.addLast(HistoryEntry(senderName, text, System.currentTimeMillis(), senderCacheKey(convWxId, senderName)))
                while (history.size > MAX_HISTORY) history.removeFirst()

                for (entry in history) {
                    val personBuilder = Person.Builder().setName(entry.senderName)
                    senderAvatarCache[entry.senderKey]?.let { personBuilder.setIcon(it) }
                    messagingStyle.addMessage(entry.text, entry.timestamp, personBuilder.build())
                }

                builder.style = messagingStyle

                // 2.5. Wrap WeChat's contentIntent so tapping the notification clears
                //      history before handing off to WeChat's own chat-open flow.
                //      Also attach a deleteIntent to catch swipe-dismiss.
                val originalContentIntent = notif.contentIntent
                if (originalContentIntent != null) {
                    pendingContentIntents[convWxId] = originalContentIntent
                    val openIntent = Intent(ACTION_NOTIFICATION_OPENED).apply {
                        setPackage(PackageNames.WECHAT)
                        putExtra("extra_target_wxid", convWxId)
                    }
                    builder.setContentIntent(
                        PendingIntent.getBroadcast(
                            context, convWxId.hashCode(), openIntent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )
                    )
                }
                val dismissIntent = Intent(ACTION_NOTIFICATION_DISMISSED).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                builder.setDeleteIntent(
                    PendingIntent.getBroadcast(
                        context, convWxId.hashCode(), dismissIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

                // 3. Quick Reply Action
                val remoteInput = RemoteInput.Builder("key_reply_content")
                    .setLabel("输入回复内容...")
                    .build()

                val replyIntent = Intent(ACTION_REPLY).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                val replyPendingIntent = PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), replyIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                val replyAction = Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                    "回复", replyPendingIntent
                ).addRemoteInput(remoteInput).build()

                // 4. Mark as Read Action
                val readIntent = Intent(ACTION_MARK_READ).apply {
                    setPackage(PackageNames.WECHAT)
                    putExtra("extra_target_wxid", convWxId)
                }
                val readPendingIntent = PendingIntent.getBroadcast(
                    context, convWxId.hashCode(), readIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val readAction = Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "标为已读", readPendingIntent
                ).build()

                // Apply actions directly to the builder
                builder.addAction(replyAction)
                builder.addAction(readAction)

                // 异步预取发送者头像，供下一条通知的 MessagingStyle 使用
                prefetchSenderAvatar(convWxId, senderName)
            }

        // 合并同一会话的多条通知（通知栏同一会话只保留最新一条）
        hookNotifyIdMerge()
    }

    // ==================== 通知合并：同一会话的通知 id 统一为会话哈希 ====================
    // 微信原始 notify id -> convWxId 映射：微信已读消息时用原始 id 调 NotificationManager.cancel()，
    // 需经该映射转换成合并后的 id 才能真正取消，并同步清空该会话的 MessagingStyle history，
    // 否则已读消息会在下一条通知里被「带出」。
    private val notifyIdMap = HashMap<Int, String>()

    private fun hookNotifyIdMerge() {
        val notifCls = Notification::class.java
        val nmCls = android.app.NotificationManager::class.java
        runCatching {
            nmCls.getMethod("notify", Int::class.javaPrimitiveType, notifCls)
                .hookBeforeDirectly {
                    val convWxId = currentTalker.get()
                    if (convWxId != null) {
                        val n = args[1] as? Notification
                        if (n != null && n.channelId == "message_channel_new_id") {
                            val origId = args[0] as Int
                            args[0] = convWxId.hashCode()
                            recordNotifyId(origId, convWxId)
                        }
                        currentTalker.remove()
                    }
                }
            WeLogger.i(TAG, "notify(int,Notification) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook notify(int) failed", it) }
        runCatching {
            nmCls.getMethod("notify", String::class.java, Int::class.javaPrimitiveType, notifCls)
                .hookBeforeDirectly {
                    val convWxId = currentTalker.get()
                    if (convWxId != null) {
                        val n = args[2] as? Notification
                        if (n != null && n.channelId == "message_channel_new_id") {
                            val origId = args[1] as Int
                            args[1] = convWxId.hashCode()
                            recordNotifyId(origId, convWxId)
                        }
                        currentTalker.remove()
                    }
                }
            WeLogger.i(TAG, "notify(tag,int,Notification) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook notify(tag,int) failed", it) }

        // 微信已读/清理通知：把原始 id 转换回合并后的 id 才能真正取消，
        // 并清空该会话 history，避免已读消息在下一条通知里被带出。
        runCatching {
            nmCls.getMethod("cancel", Int::class.javaPrimitiveType)
                .hookBeforeDirectly {
                    val origId = args[0] as Int
                    synchronized(notifyIdMap) {
                        val convWxId = notifyIdMap.remove(origId)
                        if (convWxId != null) {
                            messageHistory.remove(convWxId)
                            pendingContentIntents.remove(convWxId)
                            args[0] = convWxId.hashCode()
                            WeLogger.i(TAG, "wechat cancelled notif for $convWxId (merged id)")
                        }
                    }
                }
            WeLogger.i(TAG, "cancel(int) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook cancel(int) failed", it) }
        runCatching {
            nmCls.getMethod("cancel", String::class.java, Int::class.javaPrimitiveType)
                .hookBeforeDirectly {
                    val origId = args[1] as Int
                    synchronized(notifyIdMap) {
                        val convWxId = notifyIdMap.remove(origId)
                        if (convWxId != null) {
                            messageHistory.remove(convWxId)
                            pendingContentIntents.remove(convWxId)
                            args[1] = convWxId.hashCode()
                            WeLogger.i(TAG, "wechat cancelled notif(tag) for $convWxId (merged id)")
                        }
                    }
                }
            WeLogger.i(TAG, "cancel(tag,int) hook registered")
        }.onFailure { WeLogger.w(TAG, "hook cancel(tag,int) failed", it) }
    }

    private fun recordNotifyId(origId: Int, convWxId: String) {
        synchronized(notifyIdMap) {
            if (notifyIdMap.size >= 128) notifyIdMap.clear()
            notifyIdMap[origId] = convWxId
        }
    }

    // ==================== 发送者头像：异步预取 + 缓存 ====================
    private fun senderCacheKey(convWxId: String, senderName: String): String =
        if (convWxId.isGroupChatWxId) "$convWxId|$senderName" else convWxId

    private fun resolveSenderWxid(convWxId: String, senderName: String): String? {
        if (senderName.isBlank()) return null
        if (!convWxId.isGroupChatWxId) return convWxId
        // 群聊：按昵称/备注在联系人表里最佳匹配（同名时取第一个）
        return runCatching {
            val esc = senderName.replace("'", "''")
            WeDatabaseApi.executeQuery(
                "SELECT username FROM rcontact WHERE nickname = '$esc' OR conRemark = '$esc' LIMIT 1"
            ).firstOrNull()?.get("username")?.toString()
        }.getOrNull()
    }

    private fun prefetchSenderAvatar(convWxId: String, senderName: String) {
        val key = senderCacheKey(convWxId, senderName)
        if (senderAvatarCache[key] != null) return
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val wxid = resolveSenderWxid(convWxId, senderName) ?: return@runCatching
                val url = WeDatabaseApi.getAvatarUrl(wxid)
                if (url.isBlank()) return@runCatching
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.doInput = true
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isEmpty()) return@runCatching
                var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching
                val maxSize = 192
                if (bmp.width > maxSize || bmp.height > maxSize) {
                    val scale = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
                    bmp = Bitmap.createScaledBitmap(
                        bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                    )
                }
                senderAvatarCache[key] = Icon.createWithBitmap(bmp)
            }.onFailure { /* 头像失败静默，不影响通知本身 */ }
        }
    }
}
