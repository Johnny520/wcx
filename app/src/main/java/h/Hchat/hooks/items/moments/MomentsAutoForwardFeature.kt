package h.Hchat.hooks.items.moments

import android.content.SharedPreferences
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.model.DatabaseChange
import h.Hchat.hooks.api.runtime.WeChatDatabaseListenerApi
import h.Hchat.hooks.api.sns.SnsContentKind
import h.Hchat.hooks.api.sns.SnsContentTypes
import h.Hchat.hooks.api.sns.WeChatSnsApi
import h.Hchat.hooks.api.sns.WeChatSnsPostObserver
import h.Hchat.hooks.api.sns.PreparedSnsForward
import h.Hchat.hooks.api.sns.SnsForwardContentResolver
import h.Hchat.hooks.api.sns.SnsForwardSnapshot
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class MomentsAutoForwardFeature : BaseFeature() {
    private var runtime: MomentsAutoForwardRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈自动转发"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsAutoForwardSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = MomentsAutoForwardRuntime(context, ::logError).also { it.start() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime?.destroy()
        runtime = null
    }

    companion object {
        const val ID = "moments_auto_forward"
    }
}

private enum class MomentsForwardType(val label: String) {
    TEXT("文字"),
    IMAGE("图片"),
    VIDEO("视频"),
    LIVE_PHOTO("实况"),
    LINK("网页/链接"),
    MUSIC("音乐"),
    OTHER("其他卡片"),
    UNKNOWN("未知")
}

private class MomentsAutoForwardRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private class PendingForward(
        val post: MomentsPostRecord,
        val snapshot: SnsForwardSnapshot,
        val type: MomentsForwardType,
        val generation: Long
    ) {
        val canceled = AtomicBoolean(false)
        @Volatile var attempt = 0
        @Volatile var future: ScheduledFuture<*>? = null
    }

    private val prefs = HchatStorage.preferences(context.hostContext(), MomentsAutoForwardSettings.PREFS_NAME)
    private val resolver = SnsForwardContentResolver(context, logger)
    private val worker = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "Hchat-MomentsAutoForward").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private val lifecycleLock = Any()
    private val handledLock = Any()
    private val dailyLock = Any()
    private val handledIds = LinkedHashSet<String>()
    private val pending = ConcurrentHashMap<String, PendingForward>()
    @Volatile private var enabledState = prefs.getBoolean(
        MomentsAutoForwardSettings.KEY_ENABLE,
        MomentsAutoForwardSettings.DEFAULT_ENABLE
    )
    @Volatile private var generation = 0L
    @Volatile private var destroyed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MomentsAutoForwardSettings.KEY_ENABLE) {
            onEnabledChanged(
                prefs.getBoolean(
                    MomentsAutoForwardSettings.KEY_ENABLE,
                    MomentsAutoForwardSettings.DEFAULT_ENABLE
                )
            )
        }
    }
    private var postSubscription: WeChatSnsPostObserver.Subscription? = null
    private var deleteSubscription: WeChatDatabaseListenerApi.Subscription? = null

    fun start() {
        loadHandledIds()
        synchronized(lifecycleLock) {
            if (enabledState) {
                generation++
                if (prefs.getLong(
                        MomentsAutoForwardSettings.KEY_ENABLED_AT_SECONDS,
                        MomentsAutoForwardSettings.DEFAULT_ENABLED_AT_SECONDS
                    ) <= 0L ||
                    !prefs.contains(MomentsAutoForwardSettings.KEY_HANDLED_IDS)
                ) {
                    beginEnabledSessionLocked()
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        postSubscription = WeChatApis.snsApi()?.observePosts(::onPostStored)
        deleteSubscription = WeChatApis.databaseChanges()?.subscribe(::onDatabaseChange)
    }

    fun destroy() {
        destroyed = true
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        postSubscription?.unsubscribe()
        postSubscription = null
        deleteSubscription?.unsubscribe()
        deleteSubscription = null
        synchronized(lifecycleLock) {
            generation++
            cancelPendingLocked()
        }
        worker.shutdownNow()
    }

    private fun onEnabledChanged(enabled: Boolean) {
        synchronized(lifecycleLock) {
            if (destroyed || enabled == enabledState) return
            generation++
            enabledState = enabled
            cancelPendingLocked()
            if (enabled) {
                beginEnabledSessionLocked()
            } else if (!prefs.edit()
                    .putLong(
                        MomentsAutoForwardSettings.KEY_ENABLED_AT_SECONDS,
                        MomentsAutoForwardSettings.DEFAULT_ENABLED_AT_SECONDS
                    )
                    .commit()
            ) {
                logger("保存朋友圈自动转发关闭状态失败", null)
            }
        }
    }

    private fun beginEnabledSessionLocked() {
        synchronized(handledLock) { handledIds.clear() }
        if (!prefs.edit()
                .putLong(MomentsAutoForwardSettings.KEY_ENABLED_AT_SECONDS, nowSeconds())
                .putString(
                    MomentsAutoForwardSettings.KEY_HANDLED_IDS,
                    MomentsAutoForwardSettings.DEFAULT_HANDLED_IDS
                )
                .commit()
        ) {
            logger("初始化朋友圈自动转发启用状态失败", null)
        }
    }

    private fun onPostStored(nativeInfo: Any) {
        if (!isEnabled()) return
        val currentGeneration = generation
        runCatching {
            worker.execute { process(nativeInfo, currentGeneration) }
        }.onFailure {
            logger("提交朋友圈自动转发任务失败", it)
        }
    }

    private fun process(nativeInfo: Any, taskGeneration: Long) {
        if (!isSessionActive(taskGeneration)) return
        val api = WeChatApis.snsApi()
        if (api == null) {
            logger("朋友圈自动转发 API 未就绪", null)
            return
        }
        val post = runCatching { MomentsPostRecord.from(nativeInfo, api) }
            .onFailure { logger("解析朋友圈记录失败", it) }
            .getOrNull()
            ?: return
        if (isHandled(post.key) || pending.containsKey(post.key)) return
        val sourceReason = sourceRejectionReason(post)
        if (sourceReason != null && sourceReason != REASON_ACCOUNT_UNAVAILABLE) {
            log("忽略 ${displayName(post.userName)}：$sourceReason")
            return
        }
        val snapshot = resolver.snapshotFromSnsInfo(nativeInfo)
        if (snapshot == null) {
            logger("暂时无法解析朋友圈原生内容: snsId=${post.key}", null)
            return
        }
        val type = classify(snapshot)
        val reason = rejectionReason(post, snapshot, type)
        if (reason != null) {
            if (reason == REASON_ACCOUNT_UNAVAILABLE) {
                schedule(post, snapshot, type, taskGeneration, nextDelaySeconds())
                return
            }
            finishSkipped(post, taskGeneration, reason)
            return
        }
        schedule(post, snapshot, type, taskGeneration, nextDelaySeconds())
    }

    private fun schedule(
        post: MomentsPostRecord,
        snapshot: SnsForwardSnapshot,
        type: MomentsForwardType,
        taskGeneration: Long,
        delaySeconds: Long
    ) {
        val task = PendingForward(post, snapshot, type, taskGeneration)
        synchronized(lifecycleLock) {
            if (!isSessionActive(taskGeneration) || isHandled(post.key)) return
            if (pending.putIfAbsent(post.key, task) != null) return
            val future = runCatching {
                worker.schedule({ execute(task) }, delaySeconds, TimeUnit.SECONDS)
            }.onFailure {
                pending.remove(post.key, task)
                logger("调度朋友圈自动转发失败: snsId=${post.key}", it)
            }.getOrNull() ?: return
            task.future = future
            if (task.canceled.get() || pending[post.key] !== task) future.cancel(true)
        }
        log("已捕捉 ${displayName(post.userName)} 的${type.label}朋友圈，${delaySeconds}秒后转发")
    }

    private fun execute(task: PendingForward) {
        var retryScheduled = false
        try {
            if (!isTaskActive(task)) return
            val reason = rejectionReason(task.post, task.snapshot, task.type)
            if (reason != null) {
                if (reason == REASON_ACCOUNT_UNAVAILABLE) {
                    retryScheduled = handleAttemptFailure(task, reason, null)
                    return
                }
                finishSkipped(task.post, task.generation, reason)
                return
            }
            val content = formattedContent(task.post, task.snapshot, task.type)
            val prepared = resolver.prepare(task.snapshot, task.canceled)
            if (!isTaskActive(task)) return
            if (publish(task.type, content, prepared)) {
                recordDailySuccess()
                markHandledForSession(task.post.key, task.generation)
                log("已提交到微信发布队列 ${displayName(task.post.userName)} · ${task.type.label}")
            } else {
                retryScheduled = handleAttemptFailure(task, "静默发布失败", null)
            }
        } catch (throwable: InterruptedException) {
            if (!task.canceled.get() && isSessionActive(task.generation)) {
                retryScheduled = handleAttemptFailure(task, "媒体准备被中断", throwable)
            }
        } catch (throwable: Throwable) {
            retryScheduled = handleAttemptFailure(task, "准备或发布朋友圈失败", throwable)
        } finally {
            if (!retryScheduled) pending.remove(task.post.key, task)
        }
    }

    private fun handleAttemptFailure(
        task: PendingForward,
        stage: String,
        throwable: Throwable?
    ): Boolean {
        logger("$stage: snsId=${task.post.key} attempt=${task.attempt + 1}", throwable)
        if (task.attempt == 0 && isTaskActive(task)) {
            task.attempt = 1
            val future = runCatching {
                worker.schedule({ execute(task) }, RETRY_DELAY_SECONDS, TimeUnit.SECONDS)
            }.onFailure {
                logger("调度朋友圈自动转发重试失败: snsId=${task.post.key}", it)
            }.getOrNull()
            if (future != null) {
                task.future = future
                if (task.canceled.get() || pending[task.post.key] !== task) {
                    future.cancel(true)
                    return false
                }
                log("转发失败 ${displayName(task.post.userName)}，${RETRY_DELAY_SECONDS}秒后重试一次")
                return true
            }
        }
        markHandledForSession(task.post.key, task.generation)
        log("转发失败 ${displayName(task.post.userName)}，已停止重试")
        return false
    }

    private fun rejectionReason(
        post: MomentsPostRecord,
        snapshot: SnsForwardSnapshot?,
        type: MomentsForwardType
    ): String? {
        if (!isEnabled()) return "功能已关闭"
        if (isAd(post.nativeInfo)) return "广告内容"
        sourceRejectionReason(post)?.let { return it }
        val enabledAt = prefs.getLong(
            MomentsAutoForwardSettings.KEY_ENABLED_AT_SECONDS,
            MomentsAutoForwardSettings.DEFAULT_ENABLED_AT_SECONDS
        )
        if (post.createTimeSeconds <= 0L) return "无法确认发布时间"
        if (enabledAt <= 0L || post.createTimeSeconds < enabledAt) return "早于本次开启时间"
        if (snapshot == null) return "无法解析朋友圈内容"
        when (type) {
            MomentsForwardType.TEXT -> {
                if (snapshot.media.isNotEmpty()) return "文字类型包含未知媒体"
                if (!prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_TEXT,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_TEXT
                    )
                ) return "已过滤文字类型"
                if (formattedContent(post, snapshot, type).isBlank()) return "转发文字为空"
            }
            MomentsForwardType.IMAGE -> {
                if (snapshot.media.isEmpty()) return "未找到朋友圈图片"
                if (!prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_IMAGE,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_IMAGE
                    )
                ) return "已过滤图片类型"
            }
            MomentsForwardType.VIDEO -> {
                if (snapshot.media.isEmpty()) return "未找到朋友圈视频"
                if (!prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_VIDEO,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_VIDEO
                    )
                ) return "已过滤视频类型"
            }
            MomentsForwardType.LIVE_PHOTO -> {
                if (snapshot.media.isEmpty()) return "未找到朋友圈实况照片"
                if (!prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_LIVE_PHOTO,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_LIVE_PHOTO
                    )
                ) return "已过滤实况类型"
            }
            MomentsForwardType.LINK,
            MomentsForwardType.MUSIC,
            MomentsForwardType.OTHER,
            MomentsForwardType.UNKNOWN -> {
                val allowed = when (type) {
                    MomentsForwardType.LINK -> prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_LINK,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_LINK
                    )
                    MomentsForwardType.MUSIC -> prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_MUSIC,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_MUSIC
                    )
                    MomentsForwardType.OTHER -> prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_OTHER,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_OTHER
                    )
                    MomentsForwardType.UNKNOWN -> prefs.getBoolean(
                        MomentsAutoForwardSettings.KEY_ALLOW_UNKNOWN,
                        MomentsAutoForwardSettings.DEFAULT_ALLOW_UNKNOWN
                    )
                    else -> false
                }
                if (!allowed) return "已过滤${type.label}类型"
                if (formattedContent(post, snapshot, type).isBlank()) return "${type.label}类型没有可转发内容"
            }
        }
        val limit = prefs.getInt(
            MomentsAutoForwardSettings.KEY_DAILY_LIMIT,
            MomentsAutoForwardSettings.DEFAULT_DAILY_LIMIT
        ).coerceAtLeast(0)
        val lowerContent = snapshot.text.lowercase(Locale.ROOT)
        if (MomentsAutoForwardSettings.includeKeywordsEnabled(prefs)) {
            val includeKeywords = parseMomentsKeywords(
                prefs.getString(
                    MomentsAutoForwardSettings.KEY_INCLUDE_KEYWORDS,
                    MomentsAutoForwardSettings.DEFAULT_INCLUDE_KEYWORDS
                )
            )
            if (includeKeywords.isNotEmpty() && includeKeywords.none(lowerContent::contains)) {
                return "未命中包含关键词"
            }
        }
        if (MomentsAutoForwardSettings.excludeKeywordsEnabled(prefs) && lowerContent.isNotBlank()) {
            val keyword = parseMomentsKeywords(
                prefs.getString(
                    MomentsAutoForwardSettings.KEY_EXCLUDE_KEYWORDS,
                    MomentsAutoForwardSettings.DEFAULT_EXCLUDE_KEYWORDS
                )
            ).firstOrNull(lowerContent::contains)
            if (keyword != null) return "命中排除关键词“$keyword”"
        }
        if (limit > 0 && dailyCount() >= limit) return "已达到今日转发上限"
        return null
    }

    private fun sourceRejectionReason(post: MomentsPostRecord): String? {
        val targets = parseMomentsIds(
            prefs.getString(
                MomentsAutoForwardSettings.KEY_TARGETS,
                MomentsAutoForwardSettings.DEFAULT_TARGETS
            )
        )
        if (targets.isEmpty()) return "未指定好友"
        if (post.userName !in targets) return "不在指定好友中"
        val selfWxId = selfWxId()
        if (selfWxId.isBlank()) return REASON_ACCOUNT_UNAVAILABLE
        if (post.userName == selfWxId) return "自己的朋友圈"
        return null
    }

    private fun classify(snapshot: SnsForwardSnapshot?): MomentsForwardType {
        if (snapshot == null) return MomentsForwardType.UNKNOWN
        return when (SnsContentTypes.classify(snapshot.type)) {
            SnsContentKind.LIVE_PHOTO -> MomentsForwardType.LIVE_PHOTO
            SnsContentKind.IMAGE -> MomentsForwardType.IMAGE
            SnsContentKind.VIDEO,
            SnsContentKind.VIDEO_LINK -> MomentsForwardType.VIDEO
            SnsContentKind.TEXT -> if (snapshot.media.isEmpty()) {
                MomentsForwardType.TEXT
            } else {
                MomentsForwardType.UNKNOWN
            }
            SnsContentKind.LINK -> MomentsForwardType.LINK
            SnsContentKind.MUSIC -> MomentsForwardType.MUSIC
            SnsContentKind.OTHER -> MomentsForwardType.OTHER
            SnsContentKind.UNKNOWN -> MomentsForwardType.UNKNOWN
        }
    }

    private fun formattedContent(
        post: MomentsPostRecord,
        snapshot: SnsForwardSnapshot,
        type: MomentsForwardType
    ): String {
        val template = prefs.getString(
            MomentsAutoForwardSettings.KEY_CONTENT_TEMPLATE,
            MomentsAutoForwardSettings.DEFAULT_CONTENT_TEMPLATE
        ).orEmpty()
        val sourceContent = if (prefs.getBoolean(
                MomentsAutoForwardSettings.KEY_REPLACE_KEYWORDS_ENABLE,
                MomentsAutoForwardSettings.DEFAULT_REPLACE_KEYWORDS_ENABLE
            )
        ) {
            MomentsAutoForwardSettings.applyKeywordReplacements(
                snapshot.text,
                MomentsAutoForwardSettings.decodeKeywordReplacements(
                    prefs.getString(
                        MomentsAutoForwardSettings.KEY_KEYWORD_REPLACEMENTS,
                        MomentsAutoForwardSettings.DEFAULT_KEYWORD_REPLACEMENTS
                    )
                )
            )
        } else {
            snapshot.text
        }
        return template
            .replace(MomentsAutoForwardSettings.VARIABLE_SENDER, displayName(post.userName))
            .replace(MomentsAutoForwardSettings.VARIABLE_WXID, post.userName)
            .replace(MomentsAutoForwardSettings.VARIABLE_TYPE, type.label)
            .replace(MomentsAutoForwardSettings.VARIABLE_CONTENT, sourceContent)
            .replace(MomentsAutoForwardSettings.VARIABLE_SNS_ID, post.key)
            .trim()
    }

    private fun publish(
        type: MomentsForwardType,
        content: String,
        prepared: PreparedSnsForward
    ): Boolean {
        val api: WeChatSnsApi = WeChatApis.snsApi() ?: return false
        return when (type) {
            MomentsForwardType.TEXT,
            MomentsForwardType.LINK,
            MomentsForwardType.MUSIC,
            MomentsForwardType.OTHER,
            MomentsForwardType.UNKNOWN -> api.uploadText(content)
            MomentsForwardType.IMAGE -> api.uploadTextAndPicList(content, prepared.images)
            MomentsForwardType.VIDEO -> api.uploadTextAndVideo(content, prepared.video)
            MomentsForwardType.LIVE_PHOTO -> {
                if (prepared.imageItems.isEmpty()) return false
                api.uploadTextAndLivePhotoList(
                    content,
                    prepared.imageItems.map { item ->
                        JSONObject()
                            .put("imagePath", item.imagePath)
                            .put("videoPath", item.liveVideoPath)
                            .put("coverTimeMs", item.liveVideoCoverTimeMillis.coerceAtLeast(0L))
                    }
                )
            }
        }
    }

    private fun nextDelaySeconds(): Long {
        if (prefs.getInt(
                MomentsAutoForwardSettings.KEY_DELAY_MODE,
                MomentsAutoForwardSettings.DEFAULT_DELAY_MODE
            ) == MomentsAutoForwardSettings.DELAY_FIXED
        ) {
            return prefs.getInt(
                MomentsAutoForwardSettings.KEY_FIXED_DELAY_SECONDS,
                MomentsAutoForwardSettings.DEFAULT_FIXED_DELAY_SECONDS
            ).coerceAtLeast(0).toLong()
        }
        val min = prefs.getInt(
            MomentsAutoForwardSettings.KEY_RANDOM_MIN_SECONDS,
            MomentsAutoForwardSettings.DEFAULT_RANDOM_MIN_SECONDS
        ).coerceAtLeast(0).toLong()
        val max = prefs.getInt(
            MomentsAutoForwardSettings.KEY_RANDOM_MAX_SECONDS,
            MomentsAutoForwardSettings.DEFAULT_RANDOM_MAX_SECONDS
        ).toLong().coerceAtLeast(min)
        return if (min == max) min else Random.nextLong(min, max + 1L)
    }

    private fun onDatabaseChange(change: DatabaseChange?) {
        if (change == null || !change.table.equals("SnsInfo", ignoreCase = true) || !change.isDelete()) return
        val key = deletedSnsId(change) ?: return
        var canceled = false
        synchronized(lifecycleLock) {
            pending.remove(key)?.let { task ->
                task.canceled.set(true)
                task.future?.cancel(true)
                canceled = true
            }
            if (enabledState) markHandledLocked(key)
        }
        if (canceled) log("已取消被删除朋友圈的待转发任务")
    }

    private fun deletedSnsId(change: DatabaseChange): String? {
        val where = change.whereClause.orEmpty()
        if (!where.contains("snsId", ignoreCase = true)) return null
        val raw = change.whereArgs?.firstOrNull()
            ?: Regex("snsId\\s*=\\s*['\"]?([0-9-]+)", RegexOption.IGNORE_CASE)
                .find(where)
                ?.groupValues
                ?.getOrNull(1)
        return MomentsPostRecord.keyOf(raw)
    }

    private fun finishSkipped(post: MomentsPostRecord, taskGeneration: Long, reason: String) {
        if (markHandledForSession(post.key, taskGeneration)) {
            log("跳过 ${displayName(post.userName)}：$reason")
        }
    }

    private fun loadHandledIds() {
        val raw = prefs.getString(
            MomentsAutoForwardSettings.KEY_HANDLED_IDS,
            MomentsAutoForwardSettings.DEFAULT_HANDLED_IDS
        ).orEmpty()
        runCatching {
            val array = JSONArray(raw)
            synchronized(handledLock) {
                handledIds.clear()
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotBlank() }?.let(handledIds::add)
                }
            }
        }.onFailure {
            synchronized(handledLock) { handledIds.clear() }
            logger("读取朋友圈自动转发去重记录失败", it)
        }
    }

    private fun isHandled(key: String): Boolean = synchronized(handledLock) { key in handledIds }

    private fun markHandledForSession(key: String, taskGeneration: Long): Boolean {
        return synchronized(lifecycleLock) {
            if (!isSessionActive(taskGeneration)) return@synchronized false
            markHandledLocked(key)
        }
    }

    private fun markHandledLocked(key: String): Boolean {
        val snapshot = synchronized(handledLock) {
            if (!handledIds.add(key)) return false
            handledIds.toList()
        }
        val array = JSONArray()
        snapshot.forEach(array::put)
        if (!prefs.edit().putString(MomentsAutoForwardSettings.KEY_HANDLED_IDS, array.toString()).commit()) {
            logger("保存朋友圈自动转发去重记录失败", null)
        }
        return true
    }

    private fun dailyCount(): Int = synchronized(dailyLock) {
        val today = currentDate()
        if (prefs.getString(
                MomentsAutoForwardSettings.KEY_DAILY_DATE,
                MomentsAutoForwardSettings.DEFAULT_DAILY_DATE
            ) != today
        ) {
            resetDailyCountLocked(today)
            return@synchronized 0
        }
        prefs.getInt(
            MomentsAutoForwardSettings.KEY_DAILY_COUNT,
            MomentsAutoForwardSettings.DEFAULT_DAILY_COUNT
        ).coerceAtLeast(0)
    }

    private fun recordDailySuccess() = synchronized(dailyLock) {
        val today = currentDate()
        val count = if (prefs.getString(
                MomentsAutoForwardSettings.KEY_DAILY_DATE,
                MomentsAutoForwardSettings.DEFAULT_DAILY_DATE
            ) == today
        ) {
            prefs.getInt(
                MomentsAutoForwardSettings.KEY_DAILY_COUNT,
                MomentsAutoForwardSettings.DEFAULT_DAILY_COUNT
            ).coerceAtLeast(0)
        } else {
            0
        }
        if (!prefs.edit()
                .putString(MomentsAutoForwardSettings.KEY_DAILY_DATE, today)
                .putInt(MomentsAutoForwardSettings.KEY_DAILY_COUNT, count + 1)
                .commit()
        ) {
            logger("保存朋友圈自动转发每日计数失败", null)
        }
    }

    private fun resetDailyCountLocked(today: String) {
        if (!prefs.edit()
                .putString(MomentsAutoForwardSettings.KEY_DAILY_DATE, today)
                .putInt(
                    MomentsAutoForwardSettings.KEY_DAILY_COUNT,
                    MomentsAutoForwardSettings.DEFAULT_DAILY_COUNT
                )
                .commit()
        ) {
            logger("重置朋友圈自动转发每日计数失败", null)
        }
    }

    private fun cancelPendingLocked() {
        pending.values.forEach { task ->
            task.canceled.set(true)
            task.future?.cancel(true)
        }
        pending.clear()
    }

    private fun isTaskActive(task: PendingForward): Boolean {
        return pending[task.post.key] === task && !task.canceled.get() && isSessionActive(task.generation)
    }

    private fun isSessionActive(taskGeneration: Long): Boolean {
        return !destroyed && enabledState && generation == taskGeneration && isEnabled()
    }

    private fun isEnabled(): Boolean {
        return prefs.getBoolean(
            MomentsAutoForwardSettings.KEY_ENABLE,
            MomentsAutoForwardSettings.DEFAULT_ENABLE
        )
    }

    private fun selfWxId(): String {
        return runCatching { WeChatApis.account()?.selfWxId().orEmpty().trim() }
            .onFailure { logger("读取当前微信账号失败", it) }
            .getOrDefault("")
    }

    private fun isAd(nativeInfo: Any): Boolean {
        return runCatching { KavaReflector.invokeMethod(nativeInfo, "isAd") == true }
            .onFailure { logger("判断朋友圈广告状态失败", it) }
            .getOrDefault(false)
    }

    private fun displayName(wxid: String): String {
        return runCatching {
            WeChatApis.contact().contacts()?.getContact(wxid)?.displayName()?.ifBlank { wxid } ?: wxid
        }.onFailure {
            logger("读取朋友圈发布者名称失败: $wxid", it)
        }.getOrDefault(wxid)
    }

    private fun log(message: String) {
        if (!prefs.getBoolean(
                MomentsAutoForwardSettings.KEY_LOG_ENABLE,
                MomentsAutoForwardSettings.DEFAULT_LOG_ENABLE
            )
        ) return
        val line = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date()) + "  " + message
        synchronized(LOG_LOCK) {
            val next = (listOf(line) + prefs.getString(
                MomentsAutoForwardSettings.KEY_LOGS,
                MomentsAutoForwardSettings.DEFAULT_LOGS
            ).orEmpty().lineSequence().filter { it.isNotBlank() }.toList())
                .take(MAX_LOG_COUNT)
                .joinToString("\n")
            prefs.edit().putString(MomentsAutoForwardSettings.KEY_LOGS, next).apply()
        }
    }

    private fun currentDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private val LOG_LOCK = Any()
        private const val MAX_LOG_COUNT = 200
        private const val RETRY_DELAY_SECONDS = 300L
        private const val REASON_ACCOUNT_UNAVAILABLE = "暂时无法确认当前账号"
    }
}
