package h.Hchat.hooks.items.keywordnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.api.model.WeChatParsedMessage
import h.Hchat.hooks.api.model.WeChatMessageTypes
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarSettings
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
import h.Hchat.hooks.items.notification.NotificationNameResolver
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

object KeywordNotificationRuntime {
    private const val TAG = "[Hchat:KeywordNotification]"
    private const val CHANNEL_PREFIX = "Hchat_keyword_notification_"
    private const val CHANNEL_NAME = "Hchat 关键词通知"
    private const val EXTRA_KEYWORD_NOTIFY = "hchat_keyword_notification"
    private const val AVATAR_CACHE_MAX_ENTRIES = 64
    private val notifySeq = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-KeywordNotify").apply { isDaemon = true }
    }
    private val avatarCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap?>(AVATAR_CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?): Boolean {
                return size > AVATAR_CACHE_MAX_ENTRIES
            }
        }
    )
    @Volatile private var avatarRoot: String? = null
    @Volatile private var lastManualSoundAt: Long = 0L

    fun handleMessage(context: Context, message: WeChatMessageObserveApi.ObservedMessage) {
        val settings = KeywordNotificationSettings(context)
        if (!settings.isEnabled()) return
        if (message.isSend()) return
        val talker = message.talker.ifBlank { message.getTalker() }
        if (talker.isBlank() || !settings.shouldHandleTalker(talker)) return
        if (isInQuietWindow(settings)) return

        executor.execute {
            runCatching {
                handleMessageInternal(context, settings, message, talker)
            }.onFailure {
                HLog.e("$TAG 处理消息失败: ${it.message}", it)
            }
        }
    }

    fun handleBlockedMessage(context: Context, event: Events.MessageBlocked) {
        val talker = event.talker.orEmpty()
        val sender = event.sender.orEmpty()
        val content = event.content.orEmpty()
        if (talker.isBlank() || sender.isBlank() || content.isBlank()) return
        val type = event.msgType?.toIntOrNull() ?: WeChatMessage.inferType(content)
        val message = WeChatMessage.fromTransient(
            talker,
            sender,
            content,
            if (event.createTimeSeconds > 0L) event.createTimeSeconds * 1000L else System.currentTimeMillis(),
            false,
            type,
            event.msgSvrId,
            event.msgSource.orEmpty(),
            event.selfWxId.orEmpty()
        )
        handleMessage(
            context,
            WeChatMessageObserveApi.ObservedMessage.transientMessage(
                "message_block",
                kindOf(message),
                talker,
                sender,
                content,
                event.xml.orEmpty(),
                event.nativeUrl.orEmpty(),
                message.isGroupChat() || talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"),
                false,
                message
            )
        )
    }

    fun shouldAllowBlockedMessageIntoDatabase(context: Context, parsed: WeChatParsedMessage): Boolean {
        val settings = KeywordNotificationSettings(context)
        if (!settings.isEnabled()) return false
        val talker = parsed.talker
        val sender = parsed.sender
        val content = parsed.content
        if (talker.isBlank() || sender.isBlank() || content.isBlank()) return false
        if (!settings.shouldHandleTalker(talker)) return false
        val message = WeChatMessage.fromTransient(
            talker,
            sender,
            content,
            if (parsed.createTimeSeconds > 0L) parsed.createTimeSeconds * 1000L else System.currentTimeMillis(),
            false,
            parsed.type,
            parsed.msgSvrId,
            parsed.msgSource,
            parsed.selfWxId
        )
        val observed = WeChatMessageObserveApi.ObservedMessage.transientMessage(
            "message_block_precheck",
            kindOf(message),
            talker,
            sender,
            content,
            parsed.xml,
            parsed.nativeUrl,
            message.isGroupChat() || talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom"),
            false,
            message
        )
        return matchesDatabaseTrigger(settings, observed, talker)
    }

    private fun kindOf(message: WeChatMessage): String {
        return when {
            message.isQuote() -> WeChatMessageObserveApi.Kind.QUOTE
            message.isImage() -> WeChatMessageObserveApi.Kind.IMAGE
            message.isVoice() -> WeChatMessageObserveApi.Kind.VOICE
            message.isVideo() -> WeChatMessageObserveApi.Kind.VIDEO
            message.isEmoji() -> WeChatMessageObserveApi.Kind.EMOJI
            message.isFile() -> WeChatMessageObserveApi.Kind.FILE
            message.isLink() -> WeChatMessageObserveApi.Kind.LINK
            message.isPat() -> WeChatMessageObserveApi.Kind.PAT
            message.isSystem() -> WeChatMessageObserveApi.Kind.SYSTEM
            message.isVoip() -> WeChatMessageObserveApi.Kind.VOIP
            message.type == WeChatMessageTypes.TEXT -> WeChatMessageObserveApi.Kind.TEXT
            message.type == WeChatMessageTypes.APP -> WeChatMessageObserveApi.Kind.APP
            else -> WeChatMessageObserveApi.Kind.UNKNOWN
        }
    }

    private fun handleMessageInternal(
        context: Context,
        settings: KeywordNotificationSettings,
        message: WeChatMessageObserveApi.ObservedMessage,
        talker: String
    ) {
        val group = message.group || message.isGroupChat() || talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
        val rawContent = message.content.ifBlank { message.getContent() }
        val content = normalizedContent(message, rawContent)
        if (content.isBlank()) return

        val triggers = ArrayList<String>()
        keywordTrigger(settings, message, group, rawContent, content)?.let { triggers += it }
        if (group &&
            settings.getBoolean(KeywordNotificationSettings.KEY_AT_ALL, KeywordNotificationSettings.DEFAULT_AT_ALL) &&
            message.isNotifyAll
        ) {
            triggers += "@所有人"
        }
        if (group &&
            settings.getBoolean(KeywordNotificationSettings.KEY_AT_ALL, KeywordNotificationSettings.DEFAULT_AT_ALL) &&
            message.isAnnounceAll
        ) {
            triggers += "群公告"
        }
        if (group &&
            settings.getBoolean(KeywordNotificationSettings.KEY_AT_ME, KeywordNotificationSettings.DEFAULT_AT_ME) &&
            message.isAtMe
        ) {
            triggers += "@我"
        }
        if (triggers.isEmpty()) return

        val senderId = resolveSender(message, talker, group)
        val senderInfo = senderInfo(talker, senderId, group)
        val wxidDisplay = if (group && senderId.isNotBlank()) "$talker|$senderId" else senderId.ifBlank { talker }
        for (keyword in triggers.distinct()) {
            trigger(context, settings, keyword, content, senderInfo, wxidDisplay, talker, group)
            rememberLastMatch(context, keyword)
        }
    }

    private fun matchesDatabaseTrigger(
        settings: KeywordNotificationSettings,
        message: WeChatMessageObserveApi.ObservedMessage,
        talker: String
    ): Boolean {
        val group = message.group || message.isGroupChat() || talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
        if (keywordMatch(settings, message, talker) != null) return true
        if (!group) return false
        if (settings.getBoolean(KeywordNotificationSettings.KEY_AT_ALL, KeywordNotificationSettings.DEFAULT_AT_ALL) &&
            (message.isNotifyAll || message.isAnnounceAll)
        ) {
            return true
        }
        return settings.getBoolean(KeywordNotificationSettings.KEY_AT_ME, KeywordNotificationSettings.DEFAULT_AT_ME) &&
            message.isAtMe
    }

    private fun keywordMatch(
        settings: KeywordNotificationSettings,
        message: WeChatMessageObserveApi.ObservedMessage,
        talker: String
    ): KeywordMatch? {
        val group = message.group || message.isGroupChat() || talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
        val rawContent = message.content.ifBlank { message.getContent() }
        val content = normalizedContent(message, rawContent)
        if (content.isBlank()) return null
        val keyword = keywordTrigger(settings, message, group, rawContent, content) ?: return null
        return KeywordMatch(keyword, content, group)
    }

    private fun keywordTrigger(
        settings: KeywordNotificationSettings,
        message: WeChatMessageObserveApi.ObservedMessage,
        group: Boolean,
        rawContent: String,
        content: String
    ): String? {
        val anyKeywordEnabled = if (group) {
            settings.getBoolean(KeywordNotificationSettings.KEY_ANY_GROUP, KeywordNotificationSettings.DEFAULT_ANY_GROUP)
        } else {
            settings.getBoolean(KeywordNotificationSettings.KEY_ANY_PRIVATE, KeywordNotificationSettings.DEFAULT_ANY_PRIVATE)
        }
        return if (anyKeywordEnabled && isEligibleForAnyKeyword(message, rawContent)) {
            "任意关键词"
        } else {
            matchKeyword(settings.keywords(), content)
        }
    }

    private fun normalizedContent(message: WeChatMessageObserveApi.ObservedMessage, raw: String): String {
        if (message.isImage) return "[图片]"
        val quote = message.getQuoteMsg()
        if (quote != null) {
            val title = sanitize(quote.title)
            val refer = sanitize(quote.content)
            return when {
                title.isNotBlank() && refer.isNotBlank() -> "$title | 引用: $refer"
                title.isNotBlank() -> title
                refer.isNotBlank() -> refer
                else -> ""
            }
        }
        val body = message.message?.bodyContent().orEmpty().ifBlank { raw }.trim()
        if (body.isBlank()) return ""
        if (!(body.startsWith("<?xml") || body.contains("<msg", ignoreCase = true) || body.contains("<appmsg", ignoreCase = true))) {
            return stripGroupSenderPrefix(body)
        }
        val appType = appMsgType(body)
        if (appType == "57") {
            val title = sanitize(xmlTag(body, "title"))
            val referBlock = regexGroup(body, "(?is)<refermsg>(.*?)</refermsg>")
            val refer = sanitize(xmlTag(referBlock, "content"))
            return when {
                title.isNotBlank() && refer.isNotBlank() -> "$title | 引用: $refer"
                title.isNotBlank() -> title
                refer.isNotBlank() -> refer
                else -> ""
            }
        }
        return sanitize(xmlTag(body, "title")).ifBlank { sanitize(xmlTag(body, "content")) }
    }

    private fun isEligibleForAnyKeyword(message: WeChatMessageObserveApi.ObservedMessage, rawContent: String): Boolean {
        if (message.isEmoji) return false
        val nonText = message.isImage || message.isVoice || message.isVideo || message.isApp ||
            message.isFile || message.isLink || message.isLocation || message.isShareCard ||
            message.isPat || message.isSystem || message.isVoip || message.isVoipVoice || message.isVoipVideo
        if (nonText && !message.isQuote) return false
        if (message.isQuote) return true
        val raw = rawContent.trim()
        if (message.isText && isLikelyPlainText(raw)) return true
        if (message.getType() == 1 && isLikelyPlainText(raw)) return true
        return appMsgType(raw) == "57"
    }

    private fun isLikelyPlainText(value: String): Boolean {
        if (value.isBlank()) return false
        val text = value.trim()
        if (text.startsWith("<?xml") || text.startsWith("<") || text.contains("<msg") || text.contains("<appmsg")) return false
        if (text in setOf("[动画表情]", "[表情]", "[图片]", "[语音]", "[视频]", "[文件]", "[链接]")) return false
        return !Regex("^(\\[[^\\[\\]\\s]{1,20}\\])+$").matches(text)
    }

    private fun matchKeyword(rules: List<KeywordRule>, content: String): String? {
        for (rule in rules) {
            val keyword = rule.keyword
            if (keyword.isBlank()) continue
            val matched = if (rule.wholeWord) {
                runCatching {
                    Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
                        .matcher(content)
                        .find()
                }.getOrDefault(content.contains(keyword))
            } else {
                content.contains(keyword)
            }
            if (matched) return keyword
        }
        return null
    }

    private fun trigger(
        context: Context,
        settings: KeywordNotificationSettings,
        keyword: String,
        content: String,
        senderInfo: String,
        wxidDisplay: String,
        talker: String,
        group: Boolean
    ) {
        val type = if (group) "群消息" else "好友"
        val scene = when (keyword) {
            "@我" -> Scene.AT_ME
            "@所有人", "群公告" -> Scene.AT_ALL
            else -> Scene.KEYWORD
        }
        val title = template(settings, scene.titleKey).ifBlank { scene.defaultTitle }
            .formatKeyword(keyword, senderInfo, wxidDisplay, content, type)
        val body = template(settings, scene.contentKey).ifBlank { scene.defaultContent }
            .formatKeyword(keyword, senderInfo, wxidDisplay, content, type)
        val toastText = template(settings, scene.toastKey).ifBlank { scene.defaultToast }
            .formatKeyword(keyword, senderInfo, wxidDisplay, content, type)

        if (settings.getBoolean(KeywordNotificationSettings.KEY_NOTIFY, KeywordNotificationSettings.DEFAULT_NOTIFY)) {
            mainHandler.post { sendNotification(context, settings, scene, talker, title, body, keyword, group) }
        }
        if (settings.getBoolean(KeywordNotificationSettings.KEY_TOAST, KeywordNotificationSettings.DEFAULT_TOAST)) {
            mainHandler.post {
                runCatching { Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show() }
                    .onFailure { HLog.e("$TAG Toast失败: ${it.message}", it) }
            }
        }
    }

    private fun sendNotification(
        context: Context,
        settings: KeywordNotificationSettings,
        scene: Scene,
        talker: String,
        title: String,
        body: String,
        keyword: String,
        group: Boolean
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val sound = settings.getBoolean(
            scene.soundKey,
            settings.getBoolean(KeywordNotificationSettings.KEY_NOTIFY_SOUND, KeywordNotificationSettings.DEFAULT_NOTIFY_SOUND)
        )
        val vibrate = settings.getBoolean(
            scene.vibrateKey,
            settings.getBoolean(KeywordNotificationSettings.KEY_NOTIFY_VIBRATE, KeywordNotificationSettings.DEFAULT_NOTIFY_VIBRATE)
        )
        val ringtone = settings.getString(
            scene.ringtoneKey,
            settings.getString(KeywordNotificationSettings.KEY_NOTIFY_RINGTONE, "")
        )
        val channelId = CHANNEL_PREFIX + "s${if (sound) "1" else "0"}_v${if (vibrate) "1" else "0"}_${ringtone.hashCode()}"
        if (Build.VERSION.SDK_INT >= 26) {
            trimOldChannels(manager)
            val channel = NotificationChannel(channelId, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            channel.enableVibration(vibrate)
            channel.vibrationPattern = if (vibrate) longArrayOf(0, 250, 250, 250) else longArrayOf(0)
            channel.setSound(null, null)
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId) else Notification.Builder(context)
        val id = nextNotifyId(talker)
        val icon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.stat_notify_chat
        val avatarId = talker
        builder.setSmallIcon(icon)
            .setContentTitle(highlight(title, keyword))
            .setContentText(highlight(body, keyword))
            .setTicker(body)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setSubText(if (group) "群消息" else "好友消息")
            .setStyle(Notification.BigTextStyle().bigText(highlight(body, keyword)).setBigContentTitle(highlight(title, keyword)))
            .setContentIntent(chatPendingIntent(context, talker, id))
            .setExtras(Bundle().apply {
                putBoolean(EXTRA_KEYWORD_NOTIFY, true)
                putString("talker", talker)
            })
        loadAvatarBitmap(context, avatarId)?.let { builder.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT < 26) {
            var defaults = 0
            if (sound && ringtone.isBlank()) defaults = defaults or Notification.DEFAULT_SOUND
            if (vibrate) defaults = defaults or Notification.DEFAULT_VIBRATE
            builder.setDefaults(defaults)
            if (sound && ringtone.isNotBlank()) builder.setSound(soundUri(ringtone))
            if (vibrate) builder.setVibrate(longArrayOf(0, 250, 250, 250))
        }
        manager.notify(id, builder.build())
        if (Build.VERSION.SDK_INT >= 26 && sound) {
            playManualNotificationSound(context, soundUri(ringtone))
        }
    }

    private fun trimOldChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching {
            val channels = manager.notificationChannels ?: return@runCatching
            val custom = channels.mapNotNull { it.id?.takeIf { id -> id.startsWith(CHANNEL_PREFIX) } }
            if (custom.size <= 60) return@runCatching
            custom.take(custom.size - 60).forEach { manager.deleteNotificationChannel(it) }
        }
    }

    private fun soundUri(value: String): Uri? {
        return runCatching {
            if (value.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else Uri.parse(value)
        }.getOrNull()
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

    private fun loadAvatarBitmap(context: Context, wxId: String): Bitmap? {
        if (wxId.isBlank()) return null
        if (CustomFriendAvatarSettings.notificationsEnabled(context)) {
            CustomFriendAvatarStore.loadBitmap(context, wxId)?.let { return it }
        }
        if (avatarCache.containsKey(wxId)) return avatarCache[wxId]
        val contacts = WeChatApis.contact().contacts()
        val primary = contacts?.getAvatarUrl(wxId, true).orEmpty()
        val backup = contacts?.getAvatarUrl(wxId, false).orEmpty()
        val bitmap = avatarSources(context, wxId, primary, backup).firstNotNullOfOrNull { source ->
            loadBitmap(source)
        }
        avatarCache[wxId] = bitmap
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

    private fun chatPendingIntent(context: Context, talker: String, id: Int): PendingIntent? {
        val intents = buildChatOpenIntents(context, talker)
        if (intents.isEmpty()) return null
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivities(context, id, intents.toTypedArray(), flags)
    }

    private fun buildChatOpenIntents(context: Context, talker: String): List<Intent> {
        val result = ArrayList<Intent>()
        result += Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.LauncherUI")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
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

    private fun resolveSender(message: WeChatMessageObserveApi.ObservedMessage, talker: String, group: Boolean): String {
        val sender = message.sender.ifBlank { message.getSendTalker() }
        if (!group) return sender.ifBlank { talker }
        if (sender.isNotBlank() && !sender.endsWith("@chatroom") && !sender.endsWith("@im.chatroom")) return sender
        return extractGroupSender(message.content).ifBlank { talker }
    }

    private fun senderInfo(talker: String, sender: String, group: Boolean): String {
        val contacts = WeChatApis.contact().contacts()
        if (group) {
            val groupName = NotificationNameResolver.displayName(talker)
                .ifBlank { talker }
            val memberName = if (sender.isNotBlank() && sender != talker) {
                contacts?.getGroupMemberDisplayName(talker, sender).orEmpty()
                    .ifBlank { contacts?.getDisplayName(sender).orEmpty() }
                    .ifBlank { sender }
            } else {
                "未知成员"
            }
            return "$groupName | $memberName"
        }
        return NotificationNameResolver.displayName(talker)
            .ifBlank { NotificationNameResolver.displayName(sender) }
            .ifBlank { sender.ifBlank { talker.ifBlank { "未知来源" } } }
    }

    private fun template(settings: KeywordNotificationSettings, key: String): String {
        return settings.getString(key, "")
    }

    private fun String.formatKeyword(keyword: String, sender: String, wxid: String, content: String, type: String): String {
        return replace("%keyword%", keyword)
            .replace("%sender%", sender)
            .replace("%wxid%", wxid)
            .replace("%content%", content)
            .replace("%type%", type)
    }

    private fun rememberLastMatch(context: Context, keyword: String) {
        runCatching {
            HchatStorage.preferences(context, KeywordNotificationSettings.PREFS_NAME)
                .edit()
                .putLong(KeywordNotificationSettings.KEY_LAST_TIME, System.currentTimeMillis())
                .putString(KeywordNotificationSettings.KEY_LAST_KEYWORD, keyword)
                .apply()
        }
    }

    private fun isInQuietWindow(settings: KeywordNotificationSettings): Boolean {
        if (!settings.getBoolean(KeywordNotificationSettings.KEY_QUIET, KeywordNotificationSettings.DEFAULT_QUIET)) return false
        val start = parseTime(settings.getString(KeywordNotificationSettings.KEY_QUIET_START, KeywordNotificationSettings.DEFAULT_QUIET_START))
        val end = parseTime(settings.getString(KeywordNotificationSettings.KEY_QUIET_END, KeywordNotificationSettings.DEFAULT_QUIET_END))
        if (start < 0 || end < 0) return false
        if (start == end) return true
        val calendar = Calendar.getInstance()
        val now = calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 + calendar.get(Calendar.SECOND)
        return if (start < end) now in start until end else now >= start || now < end
    }

    private fun parseTime(value: String): Int {
        val parts = value.trim().split(":")
        if (parts.size !in 2..3) return -1
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return -1
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val second = parts.getOrNull(2)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return -1
        return hour * 3600 + minute * 60 + second
    }

    private fun nextNotifyId(talker: String): Int {
        val seq = notifySeq.updateAndGet { if (it >= 999999) 1 else it + 1 }
        val raw = 0x51000000L or ((talker.hashCode().toLong() and 0xffL) shl 20) or (seq.toLong() and 0xfffffL)
        return (raw and 0x7fffffffL).toInt()
    }

    private fun highlight(text: String, keyword: String): CharSequence {
        if (text.isBlank() || keyword.isBlank()) return text
        return runCatching {
            val builder = SpannableStringBuilder(text)
            var from = 0
            while (true) {
                val index = text.indexOf(keyword, from)
                if (index < 0) break
                builder.setSpan(ForegroundColorSpan(Color.parseColor("#FF9800")), index, index + keyword.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                from = index + keyword.length
            }
            builder
        }.getOrDefault(text)
    }

    private fun appMsgType(xml: String): String {
        if (xml.isBlank()) return ""
        val appMsg = regexGroup(xml, "(?is)<appmsg\\b[^>]*>(.*?)</appmsg>")
        return sanitize(xmlTag(appMsg, "type")).ifBlank { sanitize(xmlTag(xml, "type")) }
    }

    private fun xmlTag(xml: String, tag: String): String {
        if (xml.isBlank() || tag.isBlank()) return ""
        val regex = "(?is)<${Pattern.quote(tag)}>(?:<!\\[CDATA\\[(.*?)\\]\\]>|(.*?))</${Pattern.quote(tag)}>"
        return regexGroup(xml, regex, 1).ifBlank { regexGroup(xml, regex, 2) }
    }

    private fun regexGroup(text: String, regex: String, group: Int = 1): String {
        if (text.isBlank() || regex.isBlank()) return ""
        return runCatching {
            val matcher = Pattern.compile(regex, Pattern.DOTALL or Pattern.CASE_INSENSITIVE).matcher(text)
            if (matcher.find() && matcher.groupCount() >= group) matcher.group(group).orEmpty() else ""
        }.getOrDefault("")
    }

    private fun sanitize(value: String): String {
        if (value.isBlank()) return ""
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#10;", "\n")
            .replace("&#13;", "\r")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stripGroupSenderPrefix(value: String): String {
        return value.replace(Regex("^[A-Za-z0-9_\\-]+:\\n"), "").trim()
    }

    private fun extractGroupSender(value: String): String {
        return Regex("^([A-Za-z0-9_\\-]+?):\\n").find(value.trim())?.groupValues?.getOrNull(1).orEmpty()
    }

    private enum class Scene(
        val titleKey: String,
        val contentKey: String,
        val toastKey: String,
        val soundKey: String,
        val vibrateKey: String,
        val ringtoneKey: String,
        val defaultTitle: String,
        val defaultContent: String,
        val defaultToast: String
    ) {
        KEYWORD(
            KeywordNotificationSettings.KEY_KEYWORD_TITLE,
            KeywordNotificationSettings.KEY_KEYWORD_CONTENT,
            KeywordNotificationSettings.KEY_KEYWORD_TOAST,
            KeywordNotificationSettings.KEY_KEYWORD_NOTIFY_SOUND,
            KeywordNotificationSettings.KEY_KEYWORD_NOTIFY_VIBRATE,
            KeywordNotificationSettings.KEY_KEYWORD_NOTIFY_RINGTONE,
            KeywordNotificationSettings.DEFAULT_KEYWORD_TITLE,
            KeywordNotificationSettings.DEFAULT_KEYWORD_CONTENT,
            KeywordNotificationSettings.DEFAULT_KEYWORD_TOAST
        ),
        AT_ME(
            KeywordNotificationSettings.KEY_AT_ME_TITLE,
            KeywordNotificationSettings.KEY_AT_ME_CONTENT,
            KeywordNotificationSettings.KEY_AT_ME_TOAST,
            KeywordNotificationSettings.KEY_AT_ME_NOTIFY_SOUND,
            KeywordNotificationSettings.KEY_AT_ME_NOTIFY_VIBRATE,
            KeywordNotificationSettings.KEY_AT_ME_NOTIFY_RINGTONE,
            KeywordNotificationSettings.DEFAULT_AT_ME_TITLE,
            KeywordNotificationSettings.DEFAULT_AT_ME_CONTENT,
            KeywordNotificationSettings.DEFAULT_AT_ME_TOAST
        ),
        AT_ALL(
            KeywordNotificationSettings.KEY_AT_ALL_TITLE,
            KeywordNotificationSettings.KEY_AT_ALL_CONTENT,
            KeywordNotificationSettings.KEY_AT_ALL_TOAST,
            KeywordNotificationSettings.KEY_AT_ALL_NOTIFY_SOUND,
            KeywordNotificationSettings.KEY_AT_ALL_NOTIFY_VIBRATE,
            KeywordNotificationSettings.KEY_AT_ALL_NOTIFY_RINGTONE,
            KeywordNotificationSettings.DEFAULT_AT_ALL_TITLE,
            KeywordNotificationSettings.DEFAULT_AT_ALL_CONTENT,
            KeywordNotificationSettings.DEFAULT_AT_ALL_TOAST
        )
    }

    fun formatLastTime(time: Long): String {
        if (time <= 0L) return "暂无匹配记录"
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    private data class KeywordMatch(
        val keyword: String,
        val content: String,
        val group: Boolean
    )
}
