package h.Hchat.hooks.items.moments

import android.content.SharedPreferences
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.DatabaseChange
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi
import h.Hchat.hooks.api.sns.SnsContentKind
import h.Hchat.hooks.api.sns.WeChatSnsApi
import h.Hchat.hooks.api.sns.WeChatSnsPostObserver
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class MomentsAutoCommentFeature : BaseFeature() {
    private var runtime: MomentsAutoCommentRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈自动评论"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsAutoCommentSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MomentsAutoCommentRuntime(context, ::logError).also { it.start() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    companion object {
        const val ID = "moments_auto_comment"
    }
}

private class MomentsAutoCommentRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private data class State(val until: Long)

    private val prefs = HchatStorage.preferences(context.hostContext(), MomentsAutoCommentSettings.PREFS_NAME)
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "Hchat-MomentsAutoComment").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val stateLock = Any()
    private val dailyCommentLock = Any()
    private val states = LinkedHashMap<String, State>()
    private val successfulCommentIds = LinkedHashSet<String>()
    private val pendingComments = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val snsApi: WeChatSnsApi? get() = WeChatApis.snsApi()
    private var enabledState = prefs.getBoolean(MomentsAutoCommentSettings.KEY_ENABLE, false)
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MomentsAutoCommentSettings.KEY_ENABLE) {
            val enabled = prefs.getBoolean(MomentsAutoCommentSettings.KEY_ENABLE, false)
            if (enabled && !enabledState) {
                prefs.edit()
                    .putLong(MomentsAutoCommentSettings.KEY_ENABLED_AT_SECONDS, nowSeconds())
                    .apply()
            } else if (!enabled) {
                prefs.edit().putLong(MomentsAutoCommentSettings.KEY_ENABLED_AT_SECONDS, 0L).apply()
                cancelPendingComments()
            }
            enabledState = enabled
        }
    }
    private var postSubscription: WeChatSnsPostObserver.Subscription? = null
    private var deleteSubscription: WeChatDatabaseListenerApi.Subscription? = null

    fun start() {
        loadSuccessfulCommentRecords()
        if (enabledState && prefs.getLong(MomentsAutoCommentSettings.KEY_ENABLED_AT_SECONDS, 0L) <= 0L) {
            prefs.edit()
                .putLong(MomentsAutoCommentSettings.KEY_ENABLED_AT_SECONDS, nowSeconds())
                .apply()
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        postSubscription = snsApi?.observePosts(::onPostStored)
        deleteSubscription = WeChatApis.databaseChanges()?.subscribe(::onDatabaseChange)
    }

    fun destroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        postSubscription?.unsubscribe()
        postSubscription = null
        deleteSubscription?.unsubscribe()
        deleteSubscription = null
        cancelPendingComments()
        worker.shutdownNow()
    }

    private fun onDatabaseChange(change: DatabaseChange?) {
        if (change == null || !change.table.equals("SnsInfo", ignoreCase = true)) return
        if (change.isDelete()) cancelDeletedComment(change)
    }

    private fun onPostStored(nativeInfo: Any) {
        if (!prefs.getBoolean(MomentsAutoCommentSettings.KEY_ENABLE, MomentsAutoCommentSettings.DEFAULT_ENABLE)) return
        runCatching { worker.execute { process(nativeInfo) } }
    }

    private fun process(nativeInfo: Any) {
        val api = snsApi ?: return
        val post = MomentsPostRecord.from(nativeInfo, api) ?: return
        if (commentTemplate().isEmpty()) {
            log("跳过 ${displayName(post.userName)}：评论内容为空")
            return
        }
        if (isHandled(post.key)) return
        val reason = rejectionReason(post)
        if (reason != null) {
            remember(post.key, System.currentTimeMillis() + SEEN_TTL_MS)
            log("跳过 ${displayName(post.userName)}：$reason")
            return
        }
        if (!claim(post.key)) return
        val delaySeconds = nextDelaySeconds()
        log("已捕捉 ${displayName(post.userName)} 的${post.type.label}朋友圈，${delaySeconds}秒后评论")
        val future = worker.schedule({ executeComment(post) }, delaySeconds, TimeUnit.SECONDS)
        pendingComments[post.key] = future
        if (future.isDone) pendingComments.remove(post.key, future)
    }

    private fun executeComment(post: MomentsPostRecord) {
        try {
            val reason = rejectionReason(post)
            if (reason != null) {
                remember(post.key, System.currentTimeMillis() + SEEN_TTL_MS)
                log("取消 ${displayName(post.userName)}：$reason")
                return
            }
            val commentContent = renderedCommentContent()
            if (commentContent.isEmpty()) {
                remember(post.key, System.currentTimeMillis() + SEEN_TTL_MS)
                log("取消 ${displayName(post.userName)}：评论内容为空")
                return
            }
            if (snsApi?.comment(post.nativeInfo, commentContent, 1) == true) {
                recordDailyComment(post.userName)
                rememberSuccessfulComment(post.key)
                saveSuccessfulCommentRecords()
                log("评论已提交 ${displayName(post.userName)} · ${post.type.label}")
            } else {
                remember(post.key, System.currentTimeMillis() + RETRY_COOLDOWN_MS)
                log("评论失败 ${displayName(post.userName)}，稍后允许重试")
            }
        } catch (throwable: Throwable) {
            remember(post.key, System.currentTimeMillis() + RETRY_COOLDOWN_MS)
            logger("执行朋友圈评论失败", throwable)
        } finally {
            pendingComments.remove(post.key)
        }
    }

    private fun rejectionReason(post: MomentsPostRecord): String? {
        if (!prefs.getBoolean(MomentsAutoCommentSettings.KEY_ENABLE, false)) return "功能已关闭"
        if (commentTemplate().isEmpty()) return "评论内容为空"
        val self = WeChatApis.account()?.selfWxId().orEmpty()
        val isSelf = self.isNotBlank() && self == post.userName
        if (isSelf && !prefs.getBoolean(
                MomentsAutoCommentSettings.KEY_COMMENT_SELF,
                MomentsAutoCommentSettings.DEFAULT_COMMENT_SELF
            )
        ) return "未开启评论自己的朋友圈"
        val mode = prefs.getInt(MomentsAutoCommentSettings.KEY_LIST_MODE, MomentsAutoCommentSettings.DEFAULT_LIST_MODE)
        val targets = if (mode == MomentsAutoCommentSettings.LIST_BLACKLIST) {
            parseMomentsIds(prefs.getString(MomentsAutoCommentSettings.KEY_BLACKLIST, ""))
        } else {
            parseMomentsIds(prefs.getString(MomentsAutoCommentSettings.KEY_WHITELIST, ""))
        }
        if (!isSelf && mode == MomentsAutoCommentSettings.LIST_WHITELIST && post.userName !in targets) return "不在白名单"
        if (!isSelf && mode == MomentsAutoCommentSettings.LIST_BLACKLIST && post.userName in targets) return "命中黑名单"
        val dailyCommentLimit = prefs.getInt(
            MomentsAutoCommentSettings.KEY_DAILY_COMMENT_LIMIT,
            MomentsAutoCommentSettings.DEFAULT_DAILY_COMMENT_LIMIT
        ).coerceAtLeast(0)
        if (dailyCommentLimit > 0 && dailyCommentCount(post.userName) >= dailyCommentLimit) {
            return "已达到同一人当天评论上限"
        }
        if (prefs.getBoolean(MomentsAutoCommentSettings.KEY_TIME_WINDOW_ENABLE, false) &&
            !isInMomentsTimeWindow(
                prefs.getString(MomentsAutoCommentSettings.KEY_START_TIME, MomentsAutoCommentSettings.DEFAULT_START_TIME).orEmpty(),
                prefs.getString(MomentsAutoCommentSettings.KEY_END_TIME, MomentsAutoCommentSettings.DEFAULT_END_TIME).orEmpty()
            )
        ) return "当前不在运行时段"
        val maxAge = prefs.getInt(
            MomentsAutoCommentSettings.KEY_MAX_AGE_HOURS,
            MomentsAutoCommentSettings.DEFAULT_MAX_AGE_HOURS
        ).coerceAtLeast(1)
        if (post.createTimeSeconds <= 0L) return "无法确认发布时间"
        val enabledAtSeconds = prefs.getLong(MomentsAutoCommentSettings.KEY_ENABLED_AT_SECONDS, 0L)
        if (enabledAtSeconds <= 0L || post.createTimeSeconds < enabledAtSeconds) return "早于本次开启时间"
        val ageSeconds = nowSeconds() - post.createTimeSeconds
        if (ageSeconds !in 0..maxAge * 3600L) return "超过${maxAge}小时"
        val typeAllowed = when (post.type) {
            SnsContentKind.TEXT -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_TEXT, true)
            SnsContentKind.IMAGE,
            SnsContentKind.LIVE_PHOTO -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_IMAGE, true)
            SnsContentKind.VIDEO -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_VIDEO, true)
            SnsContentKind.LINK,
            SnsContentKind.VIDEO_LINK -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_LINK, false)
            SnsContentKind.MUSIC -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_MUSIC, false)
            SnsContentKind.OTHER -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_OTHER, false)
            SnsContentKind.UNKNOWN -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_ALLOW_UNKNOWN, false)
        }
        if (!typeAllowed) return "已过滤${post.type.label}类型"
        val keywordEnabled = when (post.type) {
            SnsContentKind.TEXT -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_TEXT, true)
            SnsContentKind.IMAGE,
            SnsContentKind.LIVE_PHOTO -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_IMAGE, true)
            SnsContentKind.VIDEO -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_VIDEO, true)
            SnsContentKind.LINK,
            SnsContentKind.MUSIC,
            SnsContentKind.VIDEO_LINK,
            SnsContentKind.OTHER,
            SnsContentKind.UNKNOWN -> prefs.getBoolean(MomentsAutoCommentSettings.KEY_KEYWORD_CARD, true)
        }
        val keywordRaw = when (post.type) {
            SnsContentKind.TEXT -> prefs.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_TEXT, "")
            SnsContentKind.IMAGE,
            SnsContentKind.LIVE_PHOTO -> prefs.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_IMAGE_TEXT, "")
            SnsContentKind.VIDEO -> prefs.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_VIDEO_TEXT, "")
            SnsContentKind.LINK,
            SnsContentKind.MUSIC,
            SnsContentKind.VIDEO_LINK,
            SnsContentKind.OTHER,
            SnsContentKind.UNKNOWN -> prefs.getString(MomentsAutoCommentSettings.KEY_KEYWORDS_CARD_TEXT, "")
        }
        if (keywordEnabled && post.text.isNotBlank()) {
            val lower = post.text.lowercase(Locale.ROOT)
            val matched = parseMomentsKeywords(keywordRaw)
                .firstOrNull(lower::contains)
            if (matched != null) return "命中排除关键词“$matched”"
        }
        return null
    }

    private fun commentTemplate(): String {
        return prefs.getString(
            MomentsAutoCommentSettings.KEY_COMMENT_CONTENT,
            MomentsAutoCommentSettings.DEFAULT_COMMENT_CONTENT
        ).orEmpty().trim()
    }

    private fun renderedCommentContent(): String {
        return MomentsAutoCommentSettings.renderCommentContent(
            commentTemplate(),
            prefs.getString(
                MomentsAutoCommentSettings.KEY_TIME_FORMAT,
                MomentsAutoCommentSettings.DEFAULT_TIME_FORMAT
            ),
            System.currentTimeMillis()
        )
    }

    private fun nextDelaySeconds(): Long {
        if (prefs.getInt(MomentsAutoCommentSettings.KEY_DELAY_MODE, MomentsAutoCommentSettings.DEFAULT_DELAY_MODE) ==
            MomentsAutoCommentSettings.DELAY_FIXED
        ) {
            return prefs.getInt(
                MomentsAutoCommentSettings.KEY_FIXED_DELAY_SECONDS,
                MomentsAutoCommentSettings.DEFAULT_FIXED_DELAY_SECONDS
            ).coerceAtLeast(0).toLong()
        }
        val min = prefs.getInt(
            MomentsAutoCommentSettings.KEY_RANDOM_MIN_SECONDS,
            MomentsAutoCommentSettings.DEFAULT_RANDOM_MIN_SECONDS
        ).coerceAtLeast(0).toLong()
        val max = prefs.getInt(
            MomentsAutoCommentSettings.KEY_RANDOM_MAX_SECONDS,
            MomentsAutoCommentSettings.DEFAULT_RANDOM_MAX_SECONDS
        ).toLong().coerceAtLeast(min)
        return if (min == max) min else Random.nextLong(min, max + 1L)
    }

    private fun claim(key: String): Boolean = synchronized(stateLock) {
        pruneLocked()
        if (key in successfulCommentIds) return@synchronized false
        if ((states[key]?.until ?: 0L) > System.currentTimeMillis()) return@synchronized false
        states[key] = State(Long.MAX_VALUE)
        true
    }

    private fun isHandled(key: String): Boolean = synchronized(stateLock) {
        pruneLocked()
        key in successfulCommentIds || (states[key]?.until ?: 0L) > System.currentTimeMillis()
    }

    private fun remember(key: String, until: Long) = synchronized(stateLock) {
        states.remove(key)
        states[key] = State(until)
        pruneLocked()
    }

    private fun rememberSuccessfulComment(key: String) = synchronized(stateLock) {
        states.remove(key)
        successfulCommentIds.add(key)
    }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        states.entries.removeAll { it.value.until <= now }
        while (states.size > MAX_STATE_COUNT) {
            states.remove(states.entries.first().key)
        }
    }

    private fun loadSuccessfulCommentRecords() {
        val raw = prefs.getString(MomentsAutoCommentSettings.KEY_SUCCESS_RECORDS, "").orEmpty()
        runCatching {
            val array = JSONArray(raw)
            synchronized(stateLock) {
                for (index in 0 until array.length()) {
                    val key = (array.optJSONObject(index)?.optString("id")
                        ?: array.optString(index)).trim()
                    if (key.isNotBlank()) successfulCommentIds.add(key)
                }
            }
        }
    }

    private fun saveSuccessfulCommentRecords() {
        val array = JSONArray()
        synchronized(stateLock) {
            successfulCommentIds.forEach { key -> array.put(key) }
        }
        prefs.edit().putString(MomentsAutoCommentSettings.KEY_SUCCESS_RECORDS, array.toString()).commit()
    }

    private fun dailyCommentCount(userName: String): Int = synchronized(dailyCommentLock) {
        val today = currentDate()
        if (prefs.getString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_DATE, "") != today) {
            resetDailyComments(today)
            return@synchronized 0
        }
        val counts = runCatching {
            JSONObject(prefs.getString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_COUNTS, "{}").orEmpty())
        }.getOrElse { JSONObject() }
        counts.optInt(userName, 0).coerceAtLeast(0)
    }

    private fun recordDailyComment(userName: String) = synchronized(dailyCommentLock) {
        val today = currentDate()
        val counts = if (prefs.getString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_DATE, "") == today) {
            runCatching {
                JSONObject(prefs.getString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_COUNTS, "{}").orEmpty())
            }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        counts.put(userName, counts.optInt(userName, 0).coerceAtLeast(0) + 1)
        prefs.edit()
            .putString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_DATE, today)
            .putString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_COUNTS, counts.toString())
            .commit()
    }

    private fun resetDailyComments(today: String) {
        prefs.edit()
            .putString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_DATE, today)
            .putString(MomentsAutoCommentSettings.KEY_DAILY_COMMENT_COUNTS, "{}")
            .commit()
    }

    private fun currentDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    private fun cancelDeletedComment(change: DatabaseChange) {
        val where = change.whereClause.orEmpty()
        if (!where.contains("snsId", ignoreCase = true)) return
        val raw = change.whereArgs?.firstOrNull()
            ?: Regex("snsId\\s*=\\s*['\"]?([0-9]+)", RegexOption.IGNORE_CASE)
                .find(where)
                ?.groupValues
                ?.getOrNull(1)
        val key = MomentsPostRecord.keyOf(raw) ?: return
        pendingComments.remove(key)?.cancel(false)
        synchronized(stateLock) { states.remove(key) }
        log("已取消被删除朋友圈的待评论任务")
    }

    private fun cancelPendingComments() {
        val keys = pendingComments.keys.toList()
        pendingComments.values.forEach { it.cancel(false) }
        pendingComments.clear()
        synchronized(stateLock) {
            keys.forEach { key ->
                if (states[key]?.until == Long.MAX_VALUE) states.remove(key)
            }
        }
    }

    private fun displayName(wxid: String): String {
        return WeChatApis.contact().contacts()?.getContact(wxid)?.displayName()?.ifBlank { wxid } ?: wxid
    }

    private fun log(message: String) {
        if (!prefs.getBoolean(MomentsAutoCommentSettings.KEY_LOG_ENABLE, false)) return
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date()) + "  " + message
        synchronized(COMMENT_LOG_LOCK) {
            val next = (listOf(line) + prefs.getString(MomentsAutoCommentSettings.KEY_LOGS, "")
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList())
                .take(MAX_COMMENT_LOG_COUNT)
                .joinToString("\n")
            prefs.edit().putString(MomentsAutoCommentSettings.KEY_LOGS, next).apply()
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private val COMMENT_LOG_LOCK = Any()
        private const val MAX_STATE_COUNT = 4096
        private const val MAX_COMMENT_LOG_COUNT = 200
        private const val SEEN_TTL_MS = 60 * 60 * 1000L
        private const val RETRY_COOLDOWN_MS = 10 * 60 * 1000L
    }
}
