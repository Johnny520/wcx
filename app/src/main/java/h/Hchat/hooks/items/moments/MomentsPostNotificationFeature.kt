package h.Hchat.hooks.items.moments

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.sns.WeChatSnsPostObserver
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarSettings
import h.Hchat.hooks.items.customfriendavatar.CustomFriendAvatarStore
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.Executors

class MomentsPostNotificationFeature : BaseFeature() {
    private var runtime: MomentsPostNotificationRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈发布通知"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsPostNotificationSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MomentsPostNotificationRuntime(context, ::logError).also { it.start() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    companion object {
        const val ID = "moments_post_notification"
    }
}

private class MomentsPostNotificationRuntime(
    private val featureContext: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val context = featureContext.hostContext()
    private val prefs = HchatStorage.preferences(context, MomentsPostNotificationSettings.PREFS_NAME)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-MomentsPostNotify").apply { isDaemon = true }
    }
    private val observed = LinkedHashSet<String>()
    private val avatarCache = LinkedHashMap<String, Bitmap>()
    @Volatile private var avatarRoot: String? = null
    private var enabledState = prefs.getBoolean(MomentsPostNotificationSettings.KEY_ENABLE, false)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MomentsPostNotificationSettings.KEY_ENABLE) {
            val enabled = prefs.getBoolean(MomentsPostNotificationSettings.KEY_ENABLE, false)
            if (enabled && !enabledState) {
                synchronized(observed) { observed.clear() }
                prefs.edit()
                    .putLong(MomentsPostNotificationSettings.KEY_ENABLED_AT_SECONDS, nowSeconds())
                    .putString(MomentsPostNotificationSettings.KEY_NOTIFIED_IDS, EMPTY_OBSERVED)
                    .apply()
            } else if (!enabled) {
                synchronized(observed) { observed.clear() }
                prefs.edit()
                    .putLong(MomentsPostNotificationSettings.KEY_ENABLED_AT_SECONDS, 0L)
                    .remove(MomentsPostNotificationSettings.KEY_NOTIFIED_IDS)
                    .apply()
            }
            enabledState = enabled
        }
    }
    private var subscription: WeChatSnsPostObserver.Subscription? = null

    fun start() {
        val enabledAtSeconds = prefs.getLong(MomentsPostNotificationSettings.KEY_ENABLED_AT_SECONDS, 0L)
        val observedInitialized = prefs.contains(MomentsPostNotificationSettings.KEY_NOTIFIED_IDS)
        if (enabledState && (enabledAtSeconds <= 0L || !observedInitialized)) {
            synchronized(observed) { observed.clear() }
            prefs.edit()
                .putLong(MomentsPostNotificationSettings.KEY_ENABLED_AT_SECONDS, nowSeconds())
                .putString(MomentsPostNotificationSettings.KEY_NOTIFIED_IDS, EMPTY_OBSERVED)
                .apply()
        } else if (enabledState) {
            loadObserved()
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        subscription = WeChatApis.snsApi()?.observePosts(::onPostStored)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        subscription?.unsubscribe()
        subscription = null
        worker.shutdownNow()
    }

    private fun onPostStored(nativeInfo: Any) {
        if (!prefs.getBoolean(MomentsPostNotificationSettings.KEY_ENABLE, false)) return
        if (!prefs.getBoolean(
                MomentsPostNotificationSettings.KEY_SYSTEM_NOTIFICATION,
                MomentsPostNotificationSettings.DEFAULT_SYSTEM_NOTIFICATION
            ) && !prefs.getBoolean(
                MomentsPostNotificationSettings.KEY_TOAST,
                MomentsPostNotificationSettings.DEFAULT_TOAST
            )
        ) return
        runCatching { worker.execute { process(nativeInfo) } }
    }

    private fun process(nativeInfo: Any) {
        val post = MomentsPostRecord.from(nativeInfo, WeChatApis.snsApi() ?: return) ?: return
        val enabledAtSeconds = prefs.getLong(MomentsPostNotificationSettings.KEY_ENABLED_AT_SECONDS, 0L)
        if (post.createTimeSeconds <= 0L || enabledAtSeconds <= 0L || post.createTimeSeconds < enabledAtSeconds) return
        if (post.userName !in parseMomentsIds(prefs.getString(MomentsPostNotificationSettings.KEY_TARGETS, ""))) return
        if (!rememberObserved(post.key)) return
        val sender = displayName(post.userName)
        val title = formatTemplate(
            prefs.getString(
                MomentsPostNotificationSettings.KEY_TITLE_TEMPLATE,
                MomentsPostNotificationSettings.DEFAULT_TITLE_TEMPLATE
            ).orEmpty(),
            post,
            sender
        ).ifBlank { MomentsPostNotificationSettings.FALLBACK_TITLE }
        val body = formatTemplate(
            prefs.getString(
                MomentsPostNotificationSettings.KEY_BODY_TEMPLATE,
                MomentsPostNotificationSettings.DEFAULT_BODY_TEMPLATE
            ).orEmpty(),
            post,
            sender
        ).ifBlank { defaultBody(sender, post) }
        if (prefs.getBoolean(
                MomentsPostNotificationSettings.KEY_SYSTEM_NOTIFICATION,
                MomentsPostNotificationSettings.DEFAULT_SYSTEM_NOTIFICATION
            )
        ) {
            runCatching { sendNotification(post, title, body) }
                .onFailure { logger("发送朋友圈发布通知失败", it) }
        }
        if (prefs.getBoolean(MomentsPostNotificationSettings.KEY_TOAST, MomentsPostNotificationSettings.DEFAULT_TOAST)) {
            val toast = formatTemplate(
                prefs.getString(
                    MomentsPostNotificationSettings.KEY_TOAST_TEMPLATE,
                    MomentsPostNotificationSettings.DEFAULT_TOAST_TEMPLATE
                ).orEmpty(),
                post,
                sender
            ).ifBlank { "📣 $sender 发布了${post.type.label}朋友圈" }
            main.post { Toast.makeText(context, toast, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun sendNotification(post: MomentsPostRecord, title: String, body: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "朋友圈发布通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 250L, 180L, 250L)
            }
            manager.createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        builder.setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(body)
                    .setBigContentTitle(title)
                    .setSummaryText("朋友圈通知")
            )
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setDefaults(Notification.DEFAULT_ALL)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(openMomentsIntent(post.key.hashCode()))
        loadAvatarBitmap(post.userName)?.let { builder.setLargeIcon(it) }
        manager.notify(("hchat_sns_" + post.key).hashCode(), builder.build())
    }

    private fun openMomentsIntent(requestCode: Int): PendingIntent? {
        val home = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.ui.LauncherUI")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val timeline = Intent().apply {
            component = ComponentName(context.packageName, "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivities(context, requestCode, arrayOf(home, timeline), flags)
    }

    private fun formatTemplate(template: String, post: MomentsPostRecord, sender: String): String {
        return template
            .replace("%sender%", sender)
            .replace("%wxid%", post.userName)
            .replace("%type%", post.type.label)
            .replace("%content%", post.text)
            .replace("%snsid%", post.key)
            .trim()
    }

    private fun defaultBody(sender: String, post: MomentsPostRecord): String {
        val prefix = "$sender 发布了${post.type.label}朋友圈"
        return if (post.text.isBlank()) prefix else "$prefix：${post.text}"
    }

    private fun loadAvatarBitmap(wxId: String): Bitmap? {
        if (wxId.isBlank()) return null
        if (CustomFriendAvatarSettings.momentsNotificationsEnabled(context)) {
            CustomFriendAvatarStore.loadBitmap(context, wxId)?.let { return it }
        }
        synchronized(avatarCache) { avatarCache[wxId] }?.let { return it }
        val contacts = WeChatApis.contact().contacts()
        val sources = LinkedHashSet<String>().apply {
            localAvatarPath(wxId, hd = false)?.let(::add)
            localAvatarPath(wxId, hd = true)?.let(::add)
            contacts?.getAvatarUrl(wxId, true).orEmpty().takeIf { it.isNotBlank() }?.let(::add)
            contacts?.getAvatarUrl(wxId, false).orEmpty().takeIf { it.isNotBlank() }?.let(::add)
        }
        val bitmap = sources.firstNotNullOfOrNull(::loadBitmap) ?: return null
        synchronized(avatarCache) {
            avatarCache[wxId] = bitmap
            while (avatarCache.size > MAX_AVATAR_CACHE) {
                avatarCache.remove(avatarCache.entries.first().key)
            }
        }
        return bitmap
    }

    private fun localAvatarPath(wxId: String, hd: Boolean): String? {
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
        }.takeIf { File(it).isFile }
    }

    private fun resolveAvatarRoot(): String? {
        val rows = runCatching {
            WeChatApis.database()?.query("PRAGMA database_list", null).orEmpty()
        }.getOrDefault(emptyList())
        for (row in rows) {
            val parent = File(row["file"]?.toString().orEmpty()).parentFile ?: continue
            val avatarDir = File(parent, "avatar")
            if (avatarDir.isDirectory) return avatarDir.absolutePath
        }
        return null
    }

    private fun loadBitmap(source: String): Bitmap? {
        return runCatching {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                val connection = URL(source).openConnection().apply {
                    connectTimeout = AVATAR_TIMEOUT_MS
                    readTimeout = AVATAR_TIMEOUT_MS
                }
                connection.getInputStream().use { BitmapFactory.decodeStream(it) }
            } else {
                BitmapFactory.decodeFile(source)
            }
        }.getOrNull()
    }

    private fun md5Lower(value: String): String? {
        return runCatching {
            val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
            buildString(digest.size * 2) {
                for (byte in digest) {
                    append(((byte.toInt() ushr 4) and 0xF).toString(16))
                    append((byte.toInt() and 0xF).toString(16))
                }
            }
        }.getOrNull()
    }

    private fun displayName(wxid: String): String {
        return WeChatApis.contact().contacts()?.getContact(wxid)?.displayName()?.ifBlank { wxid } ?: wxid
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun loadObserved() {
        val raw = prefs.getString(MomentsPostNotificationSettings.KEY_NOTIFIED_IDS, "").orEmpty()
        val restored = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        synchronized(observed) {
            observed.clear()
            restored.forEach(observed::add)
        }
    }

    private fun rememberObserved(key: String): Boolean {
        val snapshot = synchronized(observed) {
            if (!observed.add(key)) return false
            observed.toList()
        }
        val array = JSONArray()
        snapshot.forEach(array::put)
        prefs.edit()
            .putString(MomentsPostNotificationSettings.KEY_NOTIFIED_IDS, array.toString())
            .commit()
        return true
    }

    companion object {
        private const val CHANNEL_ID = "hchat_moments_post_v2"
        private const val EMPTY_OBSERVED = "[]"
        private const val MAX_AVATAR_CACHE = 128
        private const val AVATAR_TIMEOUT_MS = 3000
    }
}
