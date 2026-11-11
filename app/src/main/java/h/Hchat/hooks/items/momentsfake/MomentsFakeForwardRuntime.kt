package h.Hchat.hooks.items.momentsfake

import android.content.ContentValues
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.sns.SnsContextMenuTarget
import h.Hchat.hooks.api.sns.SnsLocalPostIdentity
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

internal object MomentsFakeForwardRuntimeRegistry {
    @Volatile private var runtime: MomentsFakeForwardRuntime? = null

    fun attach(value: MomentsFakeForwardRuntime) {
        runtime = value
    }

    fun detach(value: MomentsFakeForwardRuntime) {
        if (runtime === value) runtime = null
    }

    fun clearAll(onComplete: (Boolean) -> Unit): Boolean {
        return runtime?.clearAll(onComplete) == true
    }

    fun reloadTimelineAdapter(adapter: Any?): Boolean {
        return runtime?.reloadTimelineAdapter(adapter) == true
    }
}

private data class FakeForwardCursorDiagnostic(
    val count: Int,
    val scanned: Int,
    val hits: Int
)

private data class TimelineIdPlacement(
    val id: Long,
    val olderOrEqualId: Long?,
    val newerId: Long?
)

internal class MomentsFakeForwardRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(context.hostContext(), MomentsFakeInteractionSettings.PREFS_NAME)
    private val cachePrefs = DexMethodCache.prefs(context.hostContext(), CACHE_PREFS)
    @Volatile private var ready = false
    @Volatile private var deleteGuardInstalled = false
    @Volatile private var sourceTypeGuardInstalled = false
    @Volatile private var queryDiagnosticInstalled = false
    @Volatile private var userQueryDiagnosticInstalled = false
    @Volatile private var timelineQueryDiagnosticInstalled = false
    @Volatile private var timelineLocalReloadMethod: Method? = null
    @Volatile private var improveTimelineRefreshMethod: Method? = null
    @Volatile private var improveTimelineActive = false
    @Volatile private var improvePageDiagnosticInstalled = false
    @Volatile private var diagnosticUntilElapsed = 0L
    @Volatile private var recentlyClearedIds: Set<String> = emptySet()
    @Volatile private var timelineAdapterTrackerInstalled = false
    @Volatile private var timelineAdapterRef = WeakReference<Any>(null)
    @Volatile private var queryPredicateMethod: Method? = null
    private val loggedQueryDiagnostics = ConcurrentHashMap.newKeySet<String>()
    private val reloadDiagnosticSequence = AtomicLong(0L)

    fun warmup(): Boolean {
        ready = WeChatApis.snsApi()?.warmupCachedPostLocalWrite() == true &&
            installDeleteGuard() && installSourceTypeGuard()
        locateTimelineLocalReloadMethod()?.let(::installTimelineAdapterTracker)
        installImprovePageDiagnostic()
        if (ready) {
            repairRegisteredSourceTypes()
        }
        return ready
    }

    fun isReady(): Boolean = ready

    fun clearAll(onComplete: (Boolean) -> Unit): Boolean {
        val ids = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty().toSet()
        if (ids.isEmpty()) {
            trace("清空伪转发: registered=0，无需清理")
            Handler(Looper.getMainLooper()).post { onComplete(true) }
            return true
        }
        Thread({
            openDiagnosticWindow(ids)
            trace("清空伪转发开始: registered=${ids.size}")
            val snsApi = WeChatApis.snsApi()
            val remaining = linkedSetOf<String>()
            var changed = false
            ids.forEach { id ->
                val numericId = runCatching { java.lang.Long.parseUnsignedLong(id) }.getOrNull()
                if (numericId == null || snsApi == null) {
                    remaining += id
                    return@forEach
                }
                val exists = snsApi.cachedNativeSnsInfoLookup(id).nativeInfo != null
                if (!exists || snsApi.deleteCachedNativeSnsInfo(numericId)) {
                    changed = true
                } else {
                    remaining += id
                }
            }
            val saved = prefs.edit().putStringSet(
                MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
                remaining
            ).commit()
            trace(
                "清空伪转发完成: removed=${ids.size - remaining.size} " +
                    "remaining=${remaining.size} prefsSaved=$saved localReloadPending=$changed"
            )
            Handler(Looper.getMainLooper()).post {
                val reloaded = !changed || reloadCurrentTimeline()
                trace("清空伪转发发现页本地重载=$reloaded")
                onComplete(saved && remaining.isEmpty() && reloaded)
            }
        }, "Hchat-MomentsFakeForwardClear").apply { isDaemon = true }.start()
        return true
    }

    fun create(target: SnsContextMenuTarget, textOverride: String?, timeMillis: Long): Boolean {
        val text = textOverride.orEmpty()
        val sourceId = target.snsId?.takeLast(6).orEmpty().ifBlank { "unknown" }
        trace(
            "创建开始: source=***$sourceId type=${target.snapshot.type} " +
                "media=${target.snapshot.media.size} textLen=${text.length} " +
                "textIsUrl=${text.trim().startsWith("http://") || text.trim().startsWith("https://")}"
        )
        return runCatching {
            val source = target.nativeInfo ?: return fail("读取源 SnsInfo", "nativeInfo 为空")
            trace("读取源 SnsInfo 成功: class=${source.javaClass.name}")
            val self = WeChatApis.users()?.selfWxId().orEmpty()
            if (self.isBlank()) return fail("读取当前账号", "selfWxId 为空")
            trace("读取当前账号成功")
            val maxTimeMillis = Int.MAX_VALUE.toLong() * 1000L
            if (timeMillis !in 1000L..maxTimeMillis) {
                return fail("校验显示时间", "timeMillis 超出朋友圈秒级时间范围")
            }
            val seconds = (timeMillis / 1000L).toInt()
            trace("校验显示时间成功: seconds=$seconds")
            val native = cloneInfo(source) ?: return false
            trace("克隆 SnsInfo 成功")
            val sourceNumericId = target.snsId?.let { raw ->
                raw.toLongOrNull() ?: runCatching { java.lang.Long.parseUnsignedLong(raw) }.getOrNull()
            } ?: (KavaReflector.readField(source, "field_snsId") as? Number)?.toLong()
                ?: return fail("读取源 SNS ID", "源记录没有有效 snsId")
            val placement = nextId(seconds)
                ?: return fail("生成本地专用 ID", "无法取得与目标时间匹配且明确未占用的分页序列 ID")
            val id = placement.id
            val idText = java.lang.Long.toUnsignedString(id)
            val sourceCreateTime = (KavaReflector.readField(source, "field_createTime") as? Number)?.toLong()
            val sourceHighSeconds = sourceNumericId.ushr(32)
            trace(
                "生成本地专用 ID 成功: id=***${idText.takeLast(6)} " +
                "sourceHighTimeDelta=${if (sourceCreateTime != null) kotlin.math.abs(sourceHighSeconds - sourceCreateTime) else "unknown"} " +
                    "fakeHighTimeDelta=${kotlin.math.abs((id ushr 32) - seconds.toLong())}"
            )
            val sourceTimeline = KavaReflector.invokeMethod(source, "getTimeLine")
                ?: return fail("读取 TimeLineObject", "getTimeLine 返回空")
            val timeline = cloneProto(sourceTimeline, "TimeLineObject") ?: return false
            trace("克隆 TimeLineObject 成功")
            if (!KavaReflector.writeField(timeline, "Id", idText)) return fail("改写 TimeLineObject.Id")
            if (!KavaReflector.writeField(timeline, "UserName", self)) return fail("改写 TimeLineObject.UserName")
            if (!KavaReflector.writeField(timeline, "CreateTime", seconds)) return fail("改写 TimeLineObject.CreateTime")
            if (textOverride != null && !KavaReflector.writeField(timeline, "ContentDesc", text)) {
                return fail("改写 TimeLineObject.ContentDesc")
            }
            trace("改写 TimeLineObject 成功: textOverridden=${textOverride != null}")
            val setTimeline = KavaReflector.findCompatibleMethod(native.javaClass, "setTimeLine", timeline)
                ?: return fail("写回 TimeLineObject", "setTimeLine 方法未找到")
            if (!KavaReflector.invokeSuccessfully(setTimeline, native, timeline)) return fail("写回 TimeLineObject", "调用失败")
            trace("写回 TimeLineObject 成功")
            if (!invokeRequired(native, "setSnsId", id)) return fail("改写 SnsInfo.snsId")
            val rewrittenSeq = KavaReflector.readField(native, "field_stringSeq")?.toString().orEmpty()
            val seqMatchesId = rewrittenSeq.toBigIntegerOrNull() == BigInteger(idText)
            val aboveOlder = placement.olderOrEqualId == null ||
                java.lang.Long.compareUnsigned(id, placement.olderOrEqualId) > 0
            val belowNewer = placement.newerId == null ||
                java.lang.Long.compareUnsigned(id, placement.newerId) < 0
            trace(
                "改写 SnsInfo.snsId 成功: stringSeqLen=${rewrittenSeq.length} " +
                    "seqMatchesId=$seqMatchesId aboveOlder=$aboveOlder belowNewer=$belowNewer"
            )
            if (!seqMatchesId || !aboveOlder || !belowNewer) {
                return fail("校验分页序列", "stringSeq 与目标时间区间不一致")
            }
            if (!invokeRequired(native, "setUserName", self)) return fail("改写 SnsInfo.userName")
            if (!KavaReflector.writeField(native, "field_createTime", seconds)) return fail("改写 SnsInfo.createTime")
            val sourceType = (KavaReflector.readField(native, "field_sourceType") as? Number)?.toInt()
            trace("读取源 SnsInfo.sourceType 成功: value=${sourceType ?: "unknown"}")
            if (!KavaReflector.writeField(native, "field_sourceType", TIMELINE_SOURCE_TYPE)) {
                return fail("改写 SnsInfo.sourceType")
            }
            val rewrittenSourceType = (KavaReflector.readField(native, "field_sourceType") as? Number)?.toInt()
            if (rewrittenSourceType != TIMELINE_SOURCE_TYPE) {
                return fail("校验 SnsInfo.sourceType", "期望=$TIMELINE_SOURCE_TYPE 实际=${rewrittenSourceType ?: "unknown"}")
            }
            val dateHead = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(seconds.toLong() * 1000L)).toInt()
            if (!invokeRequired(native, "setHead", dateHead)) return fail("改写 SnsInfo.head")
            trace("改写 SnsInfo 身份、时间和 sourceType 成功: sourceType=$rewrittenSourceType")
            if (!invokeRequired(native, "setLocalFlag", 0)) return fail("清理 SnsInfo.localFlag")
            if (!invokeRequired(native, "setLikeFlag", 0)) return fail("清理 SnsInfo.likeFlag")
            if (!invokeRequired(native, "setPravited", 0)) return fail("清理 SnsInfo.privateFlag")
            if (!invokeRequired(native, "unLocalPrivate")) return fail("清理 SnsInfo.localPrivate")
            trace("清理 SnsInfo 本地、点赞和隐私状态成功")
            if (!rewriteSnsObject(native, id, self, seconds)) return false
            trace("重写 SnsObject/attrBuf 成功")
            val ids = prefs.getStringSet(MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS, emptySet())
                .orEmpty().toMutableSet()
            ids += idText
            if (!prefs.edit().putStringSet(MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS, ids).commit()) {
                return fail("登记本地专用 ID", "SharedPreferences.commit 返回 false")
            }
            trace("登记本地专用 ID 成功")
            val snsApi = WeChatApis.snsApi() ?: run {
                removeFakeId(idText)
                return fail("取得朋友圈 API", "snsApi 为空，已撤销 ID 登记")
            }
            trace("取得朋友圈 API 成功")
            if (!snsApi.insertCachedNativeSnsInfo(native)) {
                removeFakeId(idText)
                return fail("写入朋友圈本地数据库", "insertCachedNativeSnsInfo 返回 false，已撤销 ID 登记")
            }
            trace("写入朋友圈本地数据库成功")
            openDiagnosticWindow()
            val idLookupHit = snsApi.cachedNativeSnsInfoLookup(idText).nativeInfo != null
            val storedSeq = snsApi.cachedNativeSnsInfo(idText)?.let {
                KavaReflector.readField(it, "field_stringSeq")?.toString().orEmpty()
            }.orEmpty()
            trace(
                "写入后分页序列回查: readable=$idLookupHit seqLen=${storedSeq.length} " +
                    "seqMatchesId=${storedSeq.toBigIntegerOrNull() == BigInteger(idText)}"
            )
            val selfQueryHit = snsApi.getSnsPostList(self, VERIFY_QUERY_LIMIT).any { it.getSnsId() == idText }
            val timelineQueryHit = snsApi.getSnsPostList(VERIFY_QUERY_LIMIT).any { it.getSnsId() == idText }
            trace("插入后回查: id命中=$idLookupHit 自己主页条件命中=$selfQueryHit 发现页条件命中=$timelineQueryHit")
            if (!idLookupHit) {
                return fail("校验朋友圈本地查询", "按唯一 ID 回查未命中")
            }
            logRegisteredState("create")
            trace("本地创建完成: networkRefreshSubmitted=false")
            true
        }.getOrElse {
            logger("朋友圈伪转发创建异常: ${it.javaClass.simpleName}: ${it.message}", it)
            false
        }
    }

    private fun fail(stage: String, detail: String = "反射字段或方法不可用"): Boolean {
        logger("朋友圈伪转发创建失败 [$stage]: $detail", null)
        return false
    }

    private fun trace(message: String) {
        if (!debugLoggingEnabled()) return
        XposedBridge.log("$TAG $message")
    }

    private fun debugLoggingEnabled(): Boolean = prefs.getBoolean(
        MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_DEBUG_LOG,
        MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_DEBUG_LOG
    )

    private fun invokeRequired(receiver: Any, methodName: String, vararg args: Any?): Boolean {
        val method = KavaReflector.findCompatibleMethod(receiver.javaClass, methodName, *args) ?: return false
        return KavaReflector.invokeSuccessfully(method, receiver, *args)
    }

    private fun cloneInfo(source: Any): Any? {
        val sourceValues = KavaReflector.invokeMethod(source, "convertTo")
            ?: return null.also { fail("克隆 SnsInfo.convertTo", "调用返回空") }
        val values = (sourceValues as? ContentValues)?.let(::ContentValues)
            ?: return null.also { fail("克隆 SnsInfo.convertTo", "返回值不是 ContentValues: ${sourceValues.javaClass.name}") }
        val removedSourceRowId = values.containsKey("rowid")
        values.remove("rowid")
        trace("克隆 SnsInfo.convertTo 成功: columns=${values.size()} clearedSourceRowId=$removedSourceRowId")
        val ctor = KavaReflector.findConstructor(source.javaClass)
            ?: return null.also { fail("克隆 SnsInfo 构造", "无参构造方法未找到") }
        val copy = KavaReflector.newInstance(ctor)
            ?: return null.also { fail("克隆 SnsInfo 构造", "创建实例失败") }
        trace("克隆 SnsInfo 构造成功")
        val convert = KavaReflector.findMethodRecursive(source.javaClass, "convertFrom", ContentValues::class.java)
            ?: return null.also { fail("克隆 SnsInfo.convertFrom", "convertFrom(ContentValues) 未找到") }
        return runCatching {
            KavaReflector.invokeOrThrow(convert, copy, values)
            trace("克隆 SnsInfo.convertFrom 成功")
            copy
        }.onFailure { logger("朋友圈伪转发创建失败 [克隆 SnsInfo.convertFrom]: ${it.message}", it) }.getOrNull()
    }

    private fun cloneProto(source: Any?, label: String): Any? {
        source ?: return null.also { fail("克隆 $label", "源对象为空") }
        val rawBytes = KavaReflector.invokeMethod(source, "toByteArray")
            ?: return null.also { fail("克隆 $label.toByteArray", "调用返回空") }
        val bytes = rawBytes as? ByteArray
            ?: return null.also { fail("克隆 $label.toByteArray", "返回值不是 ByteArray: ${rawBytes.javaClass.name}") }
        trace("克隆 $label.toByteArray 成功: bytes=${bytes.size}")
        val ctor = KavaReflector.findConstructor(source.javaClass)
            ?: return null.also { fail("克隆 $label 构造", "无参构造方法未找到") }
        val copy = KavaReflector.newInstance(ctor)
            ?: return null.also { fail("克隆 $label 构造", "创建实例失败") }
        trace("克隆 $label 构造成功")
        val parse = KavaReflector.findCompatibleMethod(copy.javaClass, "parseFrom", bytes)
            ?: return null.also { fail("克隆 $label.parseFrom", "parseFrom(ByteArray) 未找到") }
        if (!KavaReflector.invokeSuccessfully(parse, copy, bytes)) {
            return null.also { fail("克隆 $label.parseFrom", "调用失败") }
        }
        trace("克隆 $label.parseFrom 成功")
        return copy
    }

    private fun rewriteSnsObject(native: Any, id: Long, self: String, seconds: Int): Boolean {
        val rawAttr = KavaReflector.readField(native, "field_attrBuf")
            ?: return fail("读取 SnsInfo.field_attrBuf", "字段为空")
        val bytes = rawAttr as? ByteArray
            ?: return fail("读取 SnsInfo.field_attrBuf", "字段不是 ByteArray: ${rawAttr.javaClass.name}")
        if (bytes.isEmpty()) return fail("读取 SnsInfo.field_attrBuf", "字节数组为空")
        trace("读取 SnsInfo.field_attrBuf 成功: bytes=${bytes.size}")
        val clazz = KavaReflector.loadClass("com.tencent.mm.protocal.protobuf.SnsObject", context.hostClassLoader())
            ?: return fail("加载 SnsObject", "类未找到")
        trace("加载 SnsObject 成功")
        val ctor = KavaReflector.findConstructor(clazz) ?: return fail("构造 SnsObject", "无参构造方法未找到")
        val obj = KavaReflector.newInstance(ctor) ?: return fail("构造 SnsObject", "创建实例失败")
        trace("构造 SnsObject 成功")
        val parse = KavaReflector.findCompatibleMethod(obj.javaClass, "parseFrom", bytes)
            ?: return fail("解析 SnsObject.attrBuf", "parseFrom(ByteArray) 未找到")
        if (!KavaReflector.invokeSuccessfully(parse, obj, bytes)) return fail("解析 SnsObject.attrBuf", "调用失败")
        trace("解析 SnsObject.attrBuf 成功")
        if (!KavaReflector.writeField(obj, "Id", id)) return fail("改写 SnsObject.Id")
        if (!KavaReflector.writeField(obj, "Username", self)) return fail("改写 SnsObject.Username")
        val nickname = WeChatApis.users()?.displayName(self).orEmpty()
        if (!KavaReflector.writeField(obj, "Nickname", nickname)) return fail("改写 SnsObject.Nickname")
        if (!KavaReflector.writeField(obj, "CreateTime", seconds)) return fail("改写 SnsObject.CreateTime")
        trace("改写 SnsObject 身份和时间成功")
        for (name in arrayOf(
            "LikeFlag", "LikeCount", "LikeUserListCount", "CommentCount", "CommentUserListCount",
            "WithUserCount", "WithUserListCount", "GroupCount", "BlackListCount", "GroupUserCount",
            "DeleteFlag"
        )) {
            if (!KavaReflector.writeField(obj, name, 0)) return fail("清理 SnsObject.$name")
        }
        trace("清理 SnsObject 互动计数和标志成功")
        for (name in arrayOf("LikeUserList", "CommentUserList", "WithUserList", "GroupList", "BlackList", "GroupUser")) {
            if (!KavaReflector.writeField(obj, name, LinkedList<Any>())) return fail("清理 SnsObject.$name")
        }
        trace("清理 SnsObject 互动和可见范围列表成功")
        val rawRewritten = KavaReflector.invokeMethod(obj, "toByteArray")
            ?: return fail("序列化 SnsObject", "toByteArray 返回空")
        val rewritten = rawRewritten as? ByteArray
            ?: return fail("序列化 SnsObject", "返回值不是 ByteArray: ${rawRewritten.javaClass.name}")
        trace("序列化 SnsObject 成功: bytes=${rewritten.size}")
        if (!invokeRequired(native, "setAttrBuf", rewritten)) return fail("写回 SnsInfo.attrBuf")
        trace("写回 SnsInfo.attrBuf 成功")
        return true
    }

    private fun nextId(seconds: Int): TimelineIdPlacement? {
        val snsApi = WeChatApis.snsApi() ?: return null
        val timelinePosts = snsApi.getSnsPostList(ID_POSITION_QUERY_LIMIT)
        if (timelinePosts.isEmpty()) {
            trace("计算分页序列上界失败: 发现页无边界查询没有返回记录")
            return null
        }
        val registered = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty()
        val realPosts = timelinePosts.mapNotNull { post ->
            val idText = post.getSnsId()
            if (registered.contains(idText)) return@mapNotNull null
            parseUnsignedId(idText)?.takeUnless(SnsLocalPostIdentity::isLegacyLocalOnly)
                ?.let { it to post.getCreateTimeSeconds() }
        }
        if (realPosts.isEmpty()) return null
        val olderOrEqualId = realPosts.asSequence()
            .filter { it.second <= seconds.toLong() }
            .map { it.first }
            .maxWithOrNull { left, right -> java.lang.Long.compareUnsigned(left, right) }
        val newerId = realPosts.asSequence()
            .filter { it.second > seconds.toLong() }
            .map { it.first }
            .minWithOrNull { left, right -> java.lang.Long.compareUnsigned(left, right) }
        if (olderOrEqualId != null && newerId != null &&
            java.lang.Long.compareUnsigned(olderOrEqualId, newerId) >= 0
        ) {
            trace("计算分页序列区间失败: 真实记录时间与序列顺序不一致")
            return null
        }
        trace(
            "计算分页序列区间成功: timeline=${timelinePosts.size} real=${realPosts.size} " +
                "registered=${registered.size} hasOlder=${olderOrEqualId != null} hasNewer=${newerId != null}"
        )
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val offset = ThreadLocalRandom.current().nextLong(1L, ID_OFFSET_BOUND)
            val value = SnsLocalPostIdentity.createInTimelineRange(olderOrEqualId, newerId, offset)
                ?: return@repeat
            val key = java.lang.Long.toUnsignedString(value)
            if (registered.contains(key)) return@repeat
            val lookup = snsApi.cachedNativeSnsInfoLookup(key)
            if (!lookup.querySucceeded) {
                trace("校验候选 ID 失败: 数据库查询未成功")
                return null
            }
            if (lookup.nativeInfo == null) {
                trace("校验候选 ID 成功: timelineRange=true databaseUnused=true")
                return TimelineIdPlacement(value, olderOrEqualId, newerId)
            }
        }
        return null
    }

    private fun openDiagnosticWindow(clearedIds: Set<String> = emptySet()) {
        if (!debugLoggingEnabled()) {
            diagnosticUntilElapsed = 0L
            recentlyClearedIds = emptySet()
            return
        }
        if (clearedIds.isNotEmpty()) recentlyClearedIds = clearedIds.take(MAX_DIAGNOSTIC_REGISTERED_ITEMS).toSet()
        diagnosticUntilElapsed = SystemClock.elapsedRealtime() + DIAGNOSTIC_WINDOW_MS
    }

    @Synchronized
    private fun installImprovePageDiagnostic(): Boolean {
        if (improvePageDiagnosticInstalled) return true
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.load(
            cachePrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_IMPROVE_PAGE_QUERY
        )
        val method = cached?.takeIf(::isImprovePageQueryMethod) ?: runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().usingStrings("getPageList key:", "getSeqDownLimit", "getSeqUpLimit"))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isImprovePageQueryMethod)
                .singleOrNull()
        }.onFailure { logger("朋友圈 Improve 分页诊断入口定位失败", it) }.getOrNull() ?: return false
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_IMPROVE_PAGE_QUERY, method)
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    logImprovePageDiagnostic(param)
                }
            })
            improvePageDiagnosticInstalled = true
            true
        }.onFailure { logger("朋友圈 Improve 分页诊断安装失败", it) }.getOrDefault(false)
    }

    private fun isImprovePageQueryMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.parameterTypes.size == 1 && List::class.java.isAssignableFrom(method.returnType)
    }

    private fun logImprovePageDiagnostic(param: XC_MethodHook.MethodHookParam) {
        if (!debugLoggingEnabled()) return
        if (SystemClock.elapsedRealtime() > diagnosticUntilElapsed) return
        val registered = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty().take(MAX_DIAGNOSTIC_REGISTERED_ITEMS).toSet()
        val watched = registered + recentlyClearedIds
        if (watched.isEmpty()) return
        val requestStrings = collectNestedStrings(param.args.firstOrNull(), 2)
        val result = param.result as? List<*> ?: emptyList<Any>()
        var registeredHits = 0
        var clearedHits = 0
        result.take(MAX_DIAGNOSTIC_PAGE_ITEMS).forEach { item ->
            val rawId = item?.let { KavaReflector.readField(it, "field_snsId") } ?: return@forEach
            val id = normalizeId(rawId.toString()) ?: return@forEach
            if (registered.contains(id)) registeredHits++
            if (recentlyClearedIds.contains(id)) clearedHits++
        }
        var readable = 0
        var insideBoundaryRange = 0
        val orderedBoundaries = requestStrings.sorted()
        watched.forEach { id ->
            val seq = WeChatApis.snsApi()?.cachedNativeSnsInfo(id)?.let {
                KavaReflector.readField(it, "field_stringSeq")?.toString().orEmpty()
            }.orEmpty()
            if (seq.isEmpty()) return@forEach
            readable++
            if (orderedBoundaries.isEmpty() ||
                seq >= orderedBoundaries.first() && seq <= orderedBoundaries.last()
            ) {
                insideBoundaryRange++
            }
        }
        trace(
            "Improve 真实分页: caller=${diagnosticCaller()} boundaryCount=${requestStrings.size} " +
                "boundaryLens=${requestStrings.map(String::length).sorted()} resultSize=${result.size} " +
                "scanned=${minOf(result.size, MAX_DIAGNOSTIC_PAGE_ITEMS)} watched=${watched.size} " +
                "readable=$readable insideBoundaryRange=$insideBoundaryRange " +
                "registeredHits=$registeredHits clearedHits=$clearedHits"
        )
    }

    private fun collectNestedStrings(root: Any?, maxDepth: Int): List<String> {
        val result = linkedSetOf<String>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?, depth: Int) {
            value ?: return
            if (value is String) {
                if (value.isNotEmpty()) result += value
                return
            }
            if (depth >= maxDepth || !visited.add(value)) return
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                KavaReflector.declaredFields(type).forEach { field ->
                    if (!Modifier.isStatic(field.modifiers)) visit(KavaReflector.readField(field, value), depth + 1)
                }
                type = type.superclass
            }
        }
        visit(root, 0)
        return result.take(MAX_DIAGNOSTIC_BOUNDARIES)
    }

    private fun reloadCurrentTimeline(): Boolean {
        val mvvmListClass = KavaReflector.loadClass(IMPROVE_MVVM_LIST_CLASS, context.hostClassLoader())
        if (mvvmListClass != null) {
            val mvvmList = findCurrentTimelineAdapter(mvvmListClass)
            if (mvvmList != null && refreshImproveMvvmList(mvvmList, "currentActivity")) return true
        }
        if (improveTimelineActive) return true
        val method = timelineLocalReloadMethod ?: locateTimelineLocalReloadMethod()
            ?: return true.also { trace("清空伪转发发现页本地重载: 当前无时间线实例") }
        installTimelineAdapterTracker(method)
        val adapter = timelineAdapterRef.get() ?: findCurrentTimelineAdapter(method.declaringClass)
            ?: return true.also { trace("清空伪转发发现页本地重载: 当前无活动 Adapter") }
        return KavaReflector.invokeSuccessfully(method, adapter, "")
    }

    private fun parseUnsignedId(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        return value.toLongOrNull() ?: runCatching { java.lang.Long.parseUnsignedLong(value) }.getOrNull()
    }

    fun reloadTimelineAdapter(adapter: Any?): Boolean {
        if (reloadImproveTimeline(adapter)) return true
        if (improveTimelineActive) return true
        val method = timelineLocalReloadMethod ?: locateTimelineLocalReloadMethod() ?: return false
        installTimelineAdapterTracker(method)
        val tracked = timelineAdapterRef.get()
        val target = when {
            method.declaringClass.isInstance(adapter) -> adapter
            method.declaringClass.isInstance(tracked) -> tracked
            else -> findNestedTimelineAdapter(adapter, method.declaringClass)
                ?: findCurrentTimelineAdapter(method.declaringClass)
        } ?: return false.also {
            trace(
                "朋友圈本地 Cursor 重载=false reason=adapter-not-found " +
                    "supplied=${adapter?.javaClass?.name ?: "null"} expected=${method.declaringClass.name}"
            )
        }
        val reloaded = KavaReflector.invokeSuccessfully(method, target, "")
        trace("朋友圈本地 Cursor 重载=$reloaded adapter=${target.javaClass.name}")
        if (reloaded) scheduleReloadDiagnostics()
        return reloaded
    }

    private fun reloadImproveTimeline(adapter: Any?): Boolean {
        adapter ?: return false
        val mvvmListClass = KavaReflector.loadClass(IMPROVE_MVVM_LIST_CLASS, context.hostClassLoader())
            ?: return false
        val mvvmList = findNestedTimelineAdapter(adapter, mvvmListClass) ?: return false
        return refreshImproveMvvmList(mvvmList, adapter.javaClass.name)
    }

    private fun refreshImproveMvvmList(mvvmList: Any, ownerName: String): Boolean {
        val mvvmListClass = mvvmList.javaClass
        val method = improveTimelineRefreshMethod?.takeIf { isImproveTimelineRefreshMethod(it, mvvmListClass) }
            ?: locateImproveTimelineRefreshMethod(mvvmListClass)
            ?: return false
        val refreshed = if (Modifier.isStatic(method.modifiers)) {
            KavaReflector.invokeSuccessfully(method, null, mvvmList, null, 1, null)
        } else {
            KavaReflector.invokeSuccessfully(method, mvvmList)
        }
        trace(
            "朋友圈 Improve 本地数据重载=$refreshed adapter=$ownerName " +
                "method=${method.declaringClass.name}#${method.name}"
        )
        if (refreshed) {
            improveTimelineActive = true
            scheduleReloadDiagnostics()
        }
        return refreshed
    }

    @Synchronized
    private fun locateImproveTimelineRefreshMethod(mvvmListClass: Class<*>): Method? {
        improveTimelineRefreshMethod?.takeIf { isImproveTimelineRefreshMethod(it, mvvmListClass) }
            ?.let { return it }
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(cachePrefs, runtimeKey, context.hostClassLoader(), CACHE_IMPROVE_TIMELINE_REFRESH)
            ?.takeIf { isImproveTimelineRefreshMethod(it, mvvmListClass) }
            ?.let {
                improveTimelineRefreshMethod = it
                return it
            }
        val method = runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().declaredClass(IMPROVE_MVVM_LIST_CLASS).usingStrings("submitRefreshAll"))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter { isImproveTimelineRefreshMethod(it, mvvmListClass) }
                .singleOrNull()
        }.onFailure { logger("朋友圈 Improve 本地数据重载入口定位失败", it) }.getOrNull() ?: return null
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_IMPROVE_TIMELINE_REFRESH, method)
        improveTimelineRefreshMethod = method
        return method
    }

    private fun isImproveTimelineRefreshMethod(method: Method, mvvmListClass: Class<*>): Boolean {
        if (method.declaringClass != mvvmListClass || method.returnType != Void.TYPE) return false
        if (!Modifier.isStatic(method.modifiers)) return method.parameterTypes.isEmpty()
        val types = method.parameterTypes
        return types.size == 4 && types[0] == mvvmListClass &&
            types[2] == Integer.TYPE && types[3] == Any::class.java
    }

    private fun findCurrentTimelineAdapter(expectedClass: Class<*>): Any? {
        val activity = WeChatApis.currentActivity()?.currentActivity() ?: return null
        findNestedTimelineAdapter(activity, expectedClass)?.let { return it }
        var type: Class<*>? = activity.javaClass
        while (type != null && type != Any::class.java) {
            KavaReflector.declaredFields(type).forEach { field ->
                val value = KavaReflector.readField(field, activity)
                if (expectedClass.isInstance(value)) return value
            }
            type = type.superclass
        }
        return null
    }

    private fun findNestedTimelineAdapter(root: Any?, expectedClass: Class<*>): Any? {
        root ?: return null
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun visit(value: Any?, depth: Int): Any? {
            value ?: return null
            if (expectedClass.isInstance(value)) return value
            if (depth >= MAX_ADAPTER_SEARCH_DEPTH || !visited.add(value)) return null
            val name = value.javaClass.name
            if (!name.startsWith("com.tencent.mm.") &&
                !name.startsWith("androidx.recyclerview.") &&
                !name.startsWith("android.widget.")) {
                return null
            }
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                for (field in KavaReflector.declaredFields(type)) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    val nested = KavaReflector.readField(field, value)
                    if (expectedClass.isInstance(nested)) return nested
                    visit(nested, depth + 1)?.let { return it }
                }
                type = type.superclass
            }
            return null
        }
        return visit(root, 0)
    }

    private fun scheduleReloadDiagnostics() {
        if (!debugLoggingEnabled()) return
        val sequence = reloadDiagnosticSequence.incrementAndGet()
        val handler = Handler(Looper.getMainLooper())
        listOf(0L, 2_000L, 8_000L).forEach { delay ->
            handler.postDelayed({
                if (reloadDiagnosticSequence.get() == sequence) {
                    logRegisteredState("reload+${delay}ms")
                }
            }, delay)
        }
    }

    private fun logRegisteredState(stage: String) {
        if (!debugLoggingEnabled()) return
        val ids = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty().toList().sorted()
        if (ids.isEmpty()) {
            trace("伪转发状态: stage=$stage registered=0")
            return
        }
        val snsApi = WeChatApis.snsApi()
        val self = WeChatApis.users()?.selfWxId().orEmpty()
        val selfHits = snsApi?.getSnsPostList(self, VERIFY_QUERY_LIMIT)
            .orEmpty().mapTo(hashSetOf()) { it.getSnsId() }
        val timelineHits = snsApi?.getSnsPostList(VERIFY_QUERY_LIMIT)
            .orEmpty().mapTo(hashSetOf()) { it.getSnsId() }
        var readable = 0
        ids.take(MAX_DIAGNOSTIC_REGISTERED_ITEMS).forEachIndexed { index, id ->
            val info = snsApi?.cachedNativeSnsInfo(id)
            if (info == null) {
                trace("伪转发字段: stage=$stage item#${index + 1} readable=false")
                return@forEachIndexed
            }
            readable++
            val userName = KavaReflector.readField(info, "field_userName")?.toString().orEmpty()
            trace(
                "伪转发字段: stage=$stage item#${index + 1} readable=true " +
                    "selfUser=${self.isNotBlank() && userName == self} " +
                    "type=${fieldNumber(info, "field_type")} sourceType=${fieldNumber(info, "field_sourceType")} " +
                    "localFlag=${fieldNumber(info, "field_localFlag")} localPrivate=${fieldNumber(info, "field_localPrivate")} " +
                    "private=${fieldNumber(info, "field_pravited")} likeFlag=${fieldNumber(info, "field_likeFlag")} " +
                    "head=${fieldNumber(info, "field_head")} createTime=${fieldNumber(info, "field_createTime")} " +
                    "seqLen=${KavaReflector.readField(info, "field_stringSeq")?.toString().orEmpty().length} " +
                    "selfListHit=${selfHits.contains(id)} timelineHit=${timelineHits.contains(id)}"
            )
        }
        trace(
            "伪转发状态汇总: stage=$stage registered=${ids.size} readable=$readable " +
                "logged=${minOf(ids.size, MAX_DIAGNOSTIC_REGISTERED_ITEMS)} " +
                "truncated=${ids.size > MAX_DIAGNOSTIC_REGISTERED_ITEMS}"
        )
    }

    private fun fieldNumber(info: Any, name: String): String {
        return (KavaReflector.readField(info, name) as? Number)?.toString() ?: "unknown"
    }

    @Synchronized
    private fun locateTimelineLocalReloadMethod(): Method? {
        timelineLocalReloadMethod?.takeIf(::isTimelineLocalReloadMethod)?.let { return it }
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        DexMethodCache.load(cachePrefs, runtimeKey, context.hostClassLoader(), CACHE_TIMELINE_LOCAL_RELOAD)
            ?.takeIf(::isTimelineLocalReloadMethod)
            ?.let {
                timelineLocalReloadMethod = it
                return it
            }
        val method = runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().usingStrings("onNotifyChange", "SnsTimeLineVendingAdapter"))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isTimelineLocalReloadMethod)
                .singleOrNull()
        }.onFailure { logger("朋友圈本地 Cursor 重载入口定位失败", it) }.getOrNull() ?: return null
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_TIMELINE_LOCAL_RELOAD, method)
        timelineLocalReloadMethod = method
        return method
    }

    private fun isTimelineLocalReloadMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == Void.TYPE &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
            android.widget.BaseAdapter::class.java.isAssignableFrom(method.declaringClass)
    }

    @Synchronized
    private fun installTimelineAdapterTracker(method: Method): Boolean {
        if (timelineAdapterTrackerInstalled) return true
        if (!isTimelineLocalReloadMethod(method)) return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val adapter = param.thisObject ?: return
                    if (method.declaringClass.isInstance(adapter)) {
                        timelineAdapterRef = WeakReference(adapter)
                    }
                }
            })
            timelineAdapterTrackerInstalled = true
            trace("朋友圈本地 Cursor Adapter 跟踪已安装: ${method.declaringClass.name}#${method.name}")
            true
        }.onFailure { logger("朋友圈本地 Cursor Adapter 跟踪安装失败", it) }.getOrDefault(false)
    }

    @Synchronized
    private fun installSourceTypeGuard(): Boolean {
        if (sourceTypeGuardInstalled) return true
        val snsInfoClass = KavaReflector.loadClass(
            "com.tencent.mm.plugin.sns.storage.SnsInfo",
            context.hostClassLoader()
        ) ?: return false
        val method = KavaReflector.findMethodRecursive(
            snsInfoClass,
            "removeSourceFlag",
            Integer.TYPE
        ) ?: return false
        if (method.returnType != Void.TYPE || Modifier.isStatic(method.modifiers)) return false
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val rawId = KavaReflector.readField(param.thisObject, "field_snsId") ?: return
                    val id = normalizeId(rawId.toString()) ?: return
                    if (!isFakeId(id)) return
                    val requested = (param.args.getOrNull(0) as? Number)?.toInt() ?: return
                    val effective = requested and TIMELINE_SOURCE_TYPE.inv()
                    trace(
                        "拦截伪转发 sourceType 清除: requested=$requested effective=$effective " +
                            "protectedBits=$TIMELINE_SOURCE_TYPE"
                    )
                    if (effective == 0) {
                        param.result = null
                    } else {
                        param.args[0] = effective
                    }
                }
            })
            sourceTypeGuardInstalled = true
            true
        }.onFailure { logger("朋友圈伪转发 sourceType 保护安装失败", it) }.getOrDefault(false)
    }

    private fun repairRegisteredSourceTypes() {
        val ids = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty()
        if (ids.isEmpty()) return
        val snsApi = WeChatApis.snsApi() ?: return
        var repaired = 0
        var failed = 0
        ids.forEach { id ->
            val info = snsApi.cachedNativeSnsInfo(id) ?: return@forEach
            val current = (KavaReflector.readField(info, "field_sourceType") as? Number)?.toInt()
                ?: return@forEach
            val expected = current or TIMELINE_SOURCE_TYPE
            if (current == expected) return@forEach
            if (KavaReflector.writeField(info, "field_sourceType", expected) &&
                snsApi.updateCachedNativeSnsInfo(info, notifyObservers = false)
            ) {
                repaired++
            } else {
                failed++
            }
        }
        trace("修复历史伪转发 sourceType: registered=${ids.size} repaired=$repaired failed=$failed")
    }

    @Synchronized
    private fun installDeleteGuard(): Boolean {
        if (deleteGuardInstalled) return true
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.load(cachePrefs, runtimeKey, context.hostClassLoader(), CACHE_DELETE_CALLBACK)
        val method = cached?.takeIf(::isDeleteCallback) ?: runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().usingStrings("delete by server", "onItemDelClick:"))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isDeleteCallback).firstOrNull()
        }.getOrNull() ?: return false
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_DELETE_CALLBACK, method)
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val id = findTaggedId(param.thisObject) ?: return
                    if (!isFakeId(id)) return
                    val info = WeChatApis.snsApi()?.cachedNativeSnsInfo(id) ?: return
                    KavaReflector.invokeMethod(info, "setItemDie")
                    if (WeChatApis.snsApi()?.updateCachedNativeSnsInfo(info) == true) {
                        removeFakeId(id)
                        return
                    }
                    // Never let a failed local mark fall through to WeChat's server-delete branch.
                    param.result = null
                    val numericId = runCatching { java.lang.Long.parseUnsignedLong(id) }.getOrNull()
                    if (numericId != null && WeChatApis.snsApi()?.deleteCachedNativeSnsInfo(numericId) == true) {
                        removeFakeId(id)
                        WeChatApis.snsApi()?.refreshTimeline()
                    } else {
                        logger("朋友圈伪转发本地删除失败，已阻止服务端删除", null)
                    }
                }
            })
            deleteGuardInstalled = true
            true
        }.onFailure { logger("朋友圈伪转发本地删除保护安装失败", it) }.getOrDefault(false)
    }

    private fun isDeleteCallback(method: Method): Boolean = method.parameterTypes.contentEquals(
        arrayOf(java.lang.Boolean.TYPE, String::class.java)
    ) && method.returnType == Void.TYPE &&
        method.declaringClass.name.startsWith("com.tencent.mm.plugin.sns.ui.listener.")

    @Synchronized
    private fun installQueryDiagnostic(): Boolean {
        if (queryDiagnosticInstalled) return true
        val runtimeKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.load(cachePrefs, runtimeKey, context.hostClassLoader(), CACHE_USER_QUERY)
        val method = cached?.takeIf(::isUserQueryMethod) ?: runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().usingStrings("getCursorByUserName", "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isUserQueryMethod).singleOrNull()
        }.getOrNull() ?: return false
        val cachedPredicate = DexMethodCache.load(
            cachePrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_USER_QUERY_PREDICATE
        )
        val predicate = cachedPredicate?.takeIf(::isUserQueryPredicateMethod) ?: runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().usingStrings("getCDAboveIncludeSeq"))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isUserQueryPredicateMethod).singleOrNull()
        }.getOrNull() ?: return false
        val cachedTimeline = DexMethodCache.load(
            cachePrefs,
            runtimeKey,
            context.hostClassLoader(),
            CACHE_TIMELINE_QUERY
        )
        val timeline = cachedTimeline?.takeIf(::isTimelineQueryCallback) ?: runCatching {
            context.dexKitBridge().findMethod(FindMethod().apply {
                matcher(MethodMatcher().usingStrings(
                    "getCursorForTimeLine",
                    "select *,rowid from SnsInfo  where  (sourceType & 2 != 0 ) "
                ))
            }).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
                .filter(::isTimelineQueryCallback).singleOrNull()
        }.getOrNull() ?: return false
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_USER_QUERY, method)
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_USER_QUERY_PREDICATE, predicate)
        DexMethodCache.save(cachePrefs, runtimeKey, CACHE_TIMELINE_QUERY, timeline)
        queryPredicateMethod = predicate
        if (!userQueryDiagnosticInstalled) {
            userQueryDiagnosticInstalled = runCatching {
                HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logUserQueryDiagnostic(param)
                    }
                })
                true
            }.onFailure { logger("朋友圈伪转发个人页查询诊断安装失败", it) }.getOrDefault(false)
        }
        if (!timelineQueryDiagnosticInstalled) {
            timelineQueryDiagnosticInstalled = runCatching {
                HookRegistry.get().hook(KavaReflector.accessible(timeline) ?: timeline, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        logTimelineQueryDiagnostic(param)
                    }
                })
                true
            }.onFailure { logger("朋友圈伪转发发现页查询诊断安装失败", it) }.getOrDefault(false)
        }
        queryDiagnosticInstalled = userQueryDiagnosticInstalled && timelineQueryDiagnosticInstalled
        return queryDiagnosticInstalled
    }

    private fun isUserQueryMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            Cursor::class.java.isAssignableFrom(method.returnType) &&
            method.parameterTypes.contentEquals(
                arrayOf(
                    java.lang.Boolean.TYPE,
                    String::class.java,
                    Integer.TYPE,
                    java.lang.Boolean.TYPE,
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE
                )
            )
    }

    private fun isUserQueryPredicateMethod(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == String::class.java &&
            method.parameterTypes.contentEquals(arrayOf(String::class.java))
    }

    private fun isTimelineQueryCallback(method: Method): Boolean {
        return !Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers) &&
            method.returnType == java.lang.Boolean.TYPE && method.parameterTypes.size == 1 &&
            method.parameterTypes[0].name == "com.tencent.mm.sdk.event.IEvent"
    }

    private fun logUserQueryDiagnostic(param: XC_MethodHook.MethodHookParam) {
        if (!debugLoggingEnabled()) return
        val limit = (param.args.getOrNull(2) as? Number)?.toInt() ?: return
        if (limit == VERIFY_QUERY_LIMIT) return
        val boundary = (param.args.getOrNull(4) as? String).orEmpty()
        val ids = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty()
        if (ids.isEmpty()) return
        val userName = (param.args.getOrNull(1) as? String).orEmpty()
        val self = WeChatApis.users()?.selfWxId().orEmpty()
        val page = when {
            param.args.getOrNull(3) == true -> "self"
            userName.isBlank() -> "discover"
            self.isNotBlank() && userName == self -> "self-nonflag"
            else -> "other"
        }
        val firstFlag = param.args.getOrNull(0) == true
        val selfFlag = param.args.getOrNull(3) == true
        val selfUser = self.isNotBlank() && userName == self
        val caller = diagnosticCaller()
        val queryKey = listOf(
            page,
            firstFlag,
            selfFlag,
            selfUser,
            caller,
            boundary.hashCode(),
            limit,
            param.args.getOrNull(5),
            param.args.getOrNull(6),
            ids.hashCode()
        ).joinToString(":")
        if (!loggedQueryDiagnostics.add(queryKey)) return
        val predicateMethod = queryPredicateMethod
        val predicate = if (boundary.isBlank() || predicateMethod == null ||
            !predicateMethod.declaringClass.isInstance(param.thisObject)
        ) {
            ""
        } else {
            runCatching {
                KavaReflector.invokeOrThrow(predicateMethod, param.thisObject, boundary) as? String
            }.getOrNull().orEmpty()
        }
        val stringMode = predicate.contains("stringSeq >=")
        val boundaryNumber = boundary.toBigIntegerOrNull()
        var readable = 0
        var passed = 0
        var minSeqLength = Int.MAX_VALUE
        var maxSeqLength = 0
        ids.forEach { id ->
            val info = WeChatApis.snsApi()?.cachedNativeSnsInfo(id) ?: return@forEach
            val seq = KavaReflector.readField(info, "field_stringSeq")?.toString().orEmpty()
            if (seq.isEmpty()) return@forEach
            readable++
            minSeqLength = minOf(minSeqLength, seq.length)
            maxSeqLength = maxOf(maxSeqLength, seq.length)
            val matches = if (boundary.isBlank()) {
                true
            } else if (stringMode) {
                seq >= boundary
            } else {
                val seqNumber = seq.toBigIntegerOrNull()
                    ?: runCatching { BigInteger(java.lang.Long.toUnsignedString(java.lang.Long.parseUnsignedLong(id))) }.getOrNull()
                seqNumber != null && boundaryNumber != null && seqNumber >= boundaryNumber
            }
            if (matches) passed++
        }
        val cursorResult = scanDiagnosticCursor(param.result as? Cursor, ids)
        trace(
            "真实列表查询: page=$page firstFlag=$firstFlag selfFlag=$selfFlag selfUser=$selfUser caller=$caller " +
                "mode=${if (boundary.isBlank()) "none" else if (stringMode) "stringSeq" else "snsId"} " +
                "boundaryLen=${boundary.length} limit=$limit " +
                "timeRange=${param.args.getOrNull(5)}..${param.args.getOrNull(6)} " +
                "registered=${ids.size} readable=$readable predicatePassed=$passed " +
                "cursorCount=${cursorResult.count} cursorScanned=${cursorResult.scanned} cursorHits=${cursorResult.hits} " +
                "cursorTruncated=${cursorResult.count > cursorResult.scanned && cursorResult.scanned >= 0} " +
                "seqLen=${if (readable == 0) "none" else "$minSeqLength..$maxSeqLength"}"
        )
        logRegisteredState("query:$page:$caller")
    }

    private fun logTimelineQueryDiagnostic(param: XC_MethodHook.MethodHookParam) {
        if (!debugLoggingEnabled()) return
        val ids = prefs.getStringSet(
            MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS,
            emptySet()
        ).orEmpty()
        if (ids.isEmpty()) return
        val event = param.args.firstOrNull() ?: return
        val boundary = findNestedDiagnosticValue(event, String::class.java).orEmpty()
        val cursor = findNestedDiagnosticValue(event, Cursor::class.java)
        val queryKey = "discover:${boundary.hashCode()}:${ids.hashCode()}"
        if (!loggedQueryDiagnostics.add(queryKey)) return
        val boundaryNumber = boundary.toBigIntegerOrNull()
        var readable = 0
        var stringPassed = 0
        var numericPassed = 0
        ids.forEach { id ->
            val info = WeChatApis.snsApi()?.cachedNativeSnsInfo(id) ?: return@forEach
            val seq = KavaReflector.readField(info, "field_stringSeq")?.toString().orEmpty()
            if (seq.isEmpty()) return@forEach
            readable++
            if (boundary.isBlank() || seq >= boundary) stringPassed++
            val seqNumber = seq.toBigIntegerOrNull()
                ?: runCatching { BigInteger(id) }.getOrNull()
            if (boundary.isBlank() || seqNumber != null && boundaryNumber != null && seqNumber >= boundaryNumber) {
                numericPassed++
            }
        }
        val cursorResult = scanDiagnosticCursor(cursor, ids)
        trace(
            "发现页真实查询: caller=${diagnosticCaller()} boundaryLen=${boundary.length} registered=${ids.size} readable=$readable " +
                "stringPredicatePassed=$stringPassed numericPredicatePassed=$numericPassed " +
                "cursorCount=${cursorResult.count} cursorScanned=${cursorResult.scanned} cursorHits=${cursorResult.hits} " +
                "cursorTruncated=${cursorResult.count > cursorResult.scanned && cursorResult.scanned >= 0}"
        )
        logRegisteredState("query:discover-event")
    }

    private fun diagnosticCaller(): String {
        return Thread.currentThread().stackTrace.firstOrNull { frame ->
            frame.className.startsWith("com.tencent.mm.") &&
                !frame.className.startsWith("com.tencent.mm.plugin.sns.storage.")
        }?.let { "${it.className}#${it.methodName}" } ?: "unknown"
    }

    private fun scanDiagnosticCursor(cursor: Cursor?, ids: Set<String>): FakeForwardCursorDiagnostic {
        if (cursor == null || cursor.isClosed) return FakeForwardCursorDiagnostic(-1, -1, -1)
        return runCatching {
            val originalPosition = cursor.position
            try {
                val count = cursor.count
                var scanned = 0
                var hits = 0
                val snsIdIndex = cursor.getColumnIndex("snsId")
                if (snsIdIndex >= 0) {
                    cursor.moveToPosition(-1)
                    while (scanned < MAX_DIAGNOSTIC_CURSOR_SCAN && cursor.moveToNext()) {
                        scanned++
                        val value = java.lang.Long.toUnsignedString(cursor.getLong(snsIdIndex))
                        if (ids.contains(value)) hits++
                    }
                }
                FakeForwardCursorDiagnostic(count, scanned, hits)
            } finally {
                runCatching { cursor.moveToPosition(originalPosition) }
            }
        }.getOrDefault(FakeForwardCursorDiagnostic(-2, -2, -2))
    }

    private fun <T> findNestedDiagnosticValue(root: Any, expected: Class<T>): T? {
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun find(value: Any?, depth: Int): T? {
            value ?: return null
            if (expected.isInstance(value)) return expected.cast(value)
            if (depth >= 2 || !visited.add(value)) return null
            var type: Class<*>? = value.javaClass
            while (type != null && type != Any::class.java) {
                KavaReflector.declaredFields(type).forEach { field ->
                    if (Modifier.isStatic(field.modifiers)) return@forEach
                    find(KavaReflector.readField(field, value), depth + 1)?.let { return it }
                }
                type = type.superclass
            }
            return null
        }
        return find(root, 0)
    }

    private fun findTaggedId(owner: Any?): String? {
        var type = owner?.javaClass
        while (owner != null && type != null && type != Any::class.java) {
            for (field in KavaReflector.declaredFields(type)) {
                val view = KavaReflector.readField(field, owner) as? View ?: continue
                val tag = view.tag as? String ?: continue
                return normalizeId(tag)
            }
            type = type.superclass
        }
        return null
    }

    private fun normalizeId(raw: String): String? {
        val value = raw.trim().removePrefix("sns_table_").removePrefix("ad_table_")
        value.toLongOrNull()?.let { return java.lang.Long.toUnsignedString(it) }
        return runCatching { java.lang.Long.parseUnsignedLong(value) }.getOrNull()?.let(java.lang.Long::toUnsignedString)
    }

    private fun isFakeId(id: String): Boolean = prefs.getStringSet(
        MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS, emptySet()
    ).orEmpty().contains(id)

    private fun removeFakeId(id: String) {
        val ids = prefs.getStringSet(MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS, emptySet()).orEmpty().toMutableSet()
        if (ids.remove(id)) prefs.edit().putStringSet(MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_IDS, ids).commit()
    }

    companion object {
        private const val TIMELINE_SOURCE_TYPE = 6
        private const val VERIFY_QUERY_LIMIT = 200
        private const val ID_POSITION_QUERY_LIMIT = 2000
        private const val MAX_DIAGNOSTIC_CURSOR_SCAN = 1000
        private const val MAX_DIAGNOSTIC_REGISTERED_ITEMS = 20
        private const val MAX_DIAGNOSTIC_PAGE_ITEMS = 200
        private const val MAX_DIAGNOSTIC_BOUNDARIES = 8
        private const val DIAGNOSTIC_WINDOW_MS = 120_000L
        private const val MAX_ADAPTER_SEARCH_DEPTH = 4
        private const val MAX_ID_GENERATION_ATTEMPTS = 64
        private const val ID_OFFSET_BOUND = 0x10000L
        private const val TAG = "[Hchat:moments_fake_interaction:fake_forward]"
        private const val CACHE_PREFS = "Hchat_moments_fake_forward_cache"
        private const val CACHE_DELETE_CALLBACK = "fake_forward_delete_callback_v1"
        private const val CACHE_USER_QUERY = "fake_forward_user_query_v1"
        private const val CACHE_USER_QUERY_PREDICATE = "fake_forward_user_query_predicate_v1"
        private const val CACHE_TIMELINE_QUERY = "fake_forward_timeline_query_v1"
        private const val CACHE_TIMELINE_LOCAL_RELOAD = "fake_forward_timeline_local_reload_v1"
        private const val CACHE_IMPROVE_TIMELINE_REFRESH = "fake_forward_improve_timeline_refresh_v1"
        private const val CACHE_IMPROVE_PAGE_QUERY = "fake_forward_improve_page_query_v1"
        private const val IMPROVE_MVVM_LIST_CLASS = "com.tencent.mm.plugin.mvvmlist.MvvmList"
    }
}
