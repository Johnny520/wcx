package h.Hchat.hooks.items.momentsfake

import android.app.Activity
import android.content.ContentValues
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.sns.SnsCachedNativeLookup
import h.Hchat.hooks.api.sns.SnsContextMenuTarget
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal object MomentsFakeInteractionRuntimeRegistry {
    @Volatile private var runtime: MomentsFakeInteractionRuntime? = null

    fun attach(value: MomentsFakeInteractionRuntime) {
        runtime = value
    }

    fun detach(value: MomentsFakeInteractionRuntime) {
        if (runtime === value) runtime = null
    }

    fun restoreAll(onComplete: (Boolean) -> Unit): Boolean {
        val current = runtime ?: return false
        return current.restoreAll(onComplete)
    }

    fun restoreLikes(onComplete: (Boolean) -> Unit): Boolean {
        val current = runtime ?: return false
        return current.restoreLikes(onComplete)
    }

    fun restoreComments(onComplete: (Boolean) -> Unit): Boolean {
        val current = runtime ?: return false
        return current.restoreComments(onComplete)
    }

    fun reapplyAll() {
        runtime?.reapplyAll()
    }
}

internal class MomentsFakeInteractionRuntime(
    private val context: FeatureContext,
    private val store: MomentsFakeInteractionStore,
    private val codec: MomentsFakeInteractionCodec,
    private val logger: (String, Throwable?) -> Unit
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        MomentsFakeInteractionSettings.PREFS_NAME
    )
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val guardedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-MomentsFake").apply { isDaemon = true }
    }
    private val suppressAutoMerge = ThreadLocal<Boolean>()
    private val persistenceLock = Any()
    private val restoreRunning = AtomicBoolean(false)
    private val recyclerViewClass by lazy {
        KavaReflector.loadClass(RECYCLER_VIEW_CLASS, context.hostClassLoader())
    }
    @Volatile private var baseReady = false
    @Volatile private var commentGuardReady = false

    fun installRecordHooks(): Boolean {
        val snsInfoClass = KavaReflector.loadClass(SNS_INFO_CLASS, context.hostClassLoader())
            ?: return false
        val methods = KavaReflector.declaredMethods(snsInfoClass).filter(::isRecordLoadMethod)
        val hooked = methods.count { method ->
            if (!hookedMethods.add(method)) return@count true
            runCatching {
                HookRegistry.get().hook(
                    KavaReflector.accessible(method) ?: method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            mergeIntoNativeInfo(param.thisObject)
                        }
                    }
                )
                true
            }.getOrElse {
                hookedMethods.remove(method)
                logger("安装朋友圈伪互动记录Hook失败: ${method.toGenericString()}", it)
                false
            }
        }
        return hooked > 0
    }

    fun installInteractionGuard(): Boolean {
        val wasReady = commentGuardReady
        val methods = WeChatApis.snsApi()?.localCommentGuardMethods().orEmpty()
        if (methods.isEmpty()) {
            commentGuardReady = false
            return false
        }
        return methods.all(::installInteractionGuard).also { ready ->
            commentGuardReady = ready
            if (ready && !wasReady) reapplyAll()
        }
    }

    fun isCommentGuardReady(): Boolean = commentGuardReady

    fun setBaseReady(ready: Boolean) {
        val changed = baseReady != ready
        baseReady = ready
        if (ready && changed) reapplyAll()
    }

    fun isBaseReady(): Boolean = baseReady

    fun onPostStored(nativeInfo: Any) {
        val snsId = snsId(nativeInfo) ?: return
        if (!store.hasEntry(snsId)) return
        mergeIntoNativeInfo(nativeInfo)
        submit {
            val updated = synchronized(persistenceLock) {
                val lookup = queryNativeInfoLookup(snsId)
                val currentInfo = lookup.nativeInfo
                if (!lookup.querySucceeded || currentInfo == null) return@synchronized false
                val stored = store.entry(snsId)
                mergeAndPersist(snsId, currentInfo, effectiveEntry(stored), knownEntry(stored)).also {
                    if (it) store.acknowledgePending(snsId, stored)
                }
            }
            if (updated) currentActivity()?.let { refreshTimelineViews(it, null) }
        }
    }

    fun apply(
        activity: Activity,
        target: SnsContextMenuTarget,
        previousEntry: FakeSnsInteraction
    ) {
        val snsId = target.snsId ?: return
        submit {
            val updated = synchronized(persistenceLock) {
                val nativeInfo = queryNativeInfo(snsId)
                if (nativeInfo == null) {
                    logger("朋友圈伪互动未找到本地记录: $snsId", null)
                    return@synchronized false
                }
                val currentEntry = store.entry(snsId)
                val knownEntry = mergeKnownEntries(previousEntry, currentEntry)
                mergeAndPersist(snsId, nativeInfo, effectiveEntry(currentEntry), knownEntry).also {
                    if (it) store.acknowledgePending(snsId, currentEntry)
                }
            }
            if (updated) {
                refreshTimelineViews(activity, target.anchorView?.get())
            }
        }
    }

    fun restoreAll(onComplete: (Boolean) -> Unit): Boolean {
        return restoreTypes(clearLikes = true, clearComments = true, onComplete)
    }

    fun restoreLikes(onComplete: (Boolean) -> Unit): Boolean {
        return restoreTypes(clearLikes = true, clearComments = false, onComplete)
    }

    fun restoreComments(onComplete: (Boolean) -> Unit): Boolean {
        return restoreTypes(clearLikes = false, clearComments = true, onComplete)
    }

    private fun restoreTypes(
        clearLikes: Boolean,
        clearComments: Boolean,
        onComplete: (Boolean) -> Unit
    ): Boolean {
        if (!restoreRunning.compareAndSet(false, true)) return false
        if (!submit {
            val restored = try {
                runCatching { restoreTypesBlocking(clearLikes, clearComments) }.onFailure {
                    logger("恢复朋友圈伪互动缓存失败", it)
                }.getOrDefault(false)
            } finally {
                restoreRunning.set(false)
            }
            mainHandler.post { onComplete(restored) }
        }) {
            restoreRunning.set(false)
            return false
        }
        return true
    }

    fun reapplyAll() {
        submit {
            store.allIds().forEach { snsId ->
                val stored = store.entry(snsId)
                synchronized(persistenceLock) {
                    val nativeInfo = queryNativeInfo(snsId) ?: return@synchronized
                    val updated = mergeAndPersist(
                        snsId,
                        nativeInfo,
                        effectiveEntry(stored),
                        knownEntry(stored)
                    )
                    if (updated) store.acknowledgePending(snsId, stored)
                }
            }
            currentActivity()?.let { refreshTimelineViews(it, null) }
        }
    }

    fun processPendingRestore() {
        val clearAll = prefs.getBoolean(MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_ALL, false)
        val clearLikes = clearAll || prefs.getBoolean(
            MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_LIKES,
            false
        )
        val clearComments = clearAll || prefs.getBoolean(
            MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_COMMENTS,
            false
        )
        if (!clearLikes && !clearComments) return
        restoreTypes(clearLikes, clearComments) { success ->
            if (!success) logger("朋友圈伪互动待清理缓存恢复失败", null)
        }
    }

    fun destroy() {
        worker.shutdownNow()
    }

    private fun mergeIntoNativeInfo(nativeInfo: Any?) {
        if (suppressAutoMerge.get() == true ||
            nativeInfo == null ||
            nativeInfo.javaClass.name != SNS_INFO_CLASS
        ) return
        val snsId = snsId(nativeInfo) ?: return
        if (!store.hasEntry(snsId)) return
        val source = attrBuf(nativeInfo) ?: return
        val stored = store.entry(snsId)
        val merged = codec.merge(
            source,
            effectiveEntry(stored),
            knownEntry = knownEntry(stored),
            includeLikes = fakeLikeEnabled(),
            includeComments = fakeCommentEnabled()
        )
        if (merged.changed) setAttrBuf(nativeInfo, merged.bytes)
    }

    private fun mergeAndPersist(
        snsId: String,
        nativeInfo: Any,
        entry: FakeSnsInteraction,
        knownEntry: FakeSnsInteraction,
        includeLikes: Boolean = fakeLikeEnabled(),
        includeComments: Boolean = fakeCommentEnabled()
    ): Boolean {
        val source = attrBuf(nativeInfo) ?: return false
        val merged = codec.merge(
            source = source,
            entry = entry,
            knownEntry = knownEntry,
            includeLikes = includeLikes,
            includeComments = includeComments
        )
        if (!merged.changed) return true
        if (!setAttrBuf(nativeInfo, merged.bytes)) return false
        val updated = WeChatApis.snsApi()
            ?.updateCachedNativeSnsInfo(nativeInfo, notifyObservers = false) == true
        if (!updated) logger("写入朋友圈伪互动原生缓存失败: snsId=$snsId", null)
        return updated
    }

    private fun effectiveEntry(stored: FakeSnsInteraction): FakeSnsInteraction {
        return FakeSnsInteraction(
            likes = if (fakeLikeEnabled()) stored.likes else emptyList(),
            comments = if (fakeCommentEnabled()) stored.comments else emptyList()
        )
    }

    private fun knownEntry(stored: FakeSnsInteraction): FakeSnsInteraction {
        return FakeSnsInteraction(
            likes = (stored.likes + stored.pendingLikes).distinctBy { it.wxId },
            comments = (stored.comments + stored.pendingComments).distinctBy {
                Triple(it.id, it.authorWxId, it.content)
            }
        )
    }

    private fun installInteractionGuard(method: Method): Boolean {
        if (!guardedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(
                KavaReflector.accessible(method) ?: method,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val nativeInfo = param.args?.getOrNull(0) ?: return
                        val snsId = snsId(nativeInfo) ?: return
                        val comments = knownEntry(store.entry(snsId)).comments
                        if (comments.isEmpty()) return
                        val reply = param.args?.getOrNull(3) ?: return
                        val fakeReply = when (reply) {
                            is Number -> comments.any { comment ->
                                codec.commentNodeId(comment.id) == reply.toInt()
                            }
                            else -> codec.matchesFakeCommentNode(reply, comments)
                        }
                        if (!fakeReply) return
                        param.result = null
                        mainHandler.post {
                            Toast.makeText(
                                context.hostContext(),
                                "伪评论仅在本机显示，不能回复",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
            true
        }.getOrElse {
            guardedMethods.remove(method)
            logger("安装朋友圈伪评论交互保护失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun restoreTypesBlocking(clearLikes: Boolean, clearComments: Boolean): Boolean {
        var restored = true
        store.allIds().forEach { snsId ->
            val stored = store.entry(snsId)
            val effective = effectiveEntry(stored)
            val itemRestored = synchronized(persistenceLock) {
                val lookup = queryNativeInfoLookup(snsId)
                if (!lookup.querySucceeded) return@synchronized false
                val nativeInfo = lookup.nativeInfo ?: return@synchronized true
                mergeAndPersist(
                    snsId = snsId,
                    nativeInfo = nativeInfo,
                    entry = effective.copy(
                        likes = if (clearLikes) emptyList() else effective.likes,
                        comments = if (clearComments) emptyList() else effective.comments
                    ),
                    knownEntry = knownEntry(stored),
                    includeLikes = !clearLikes && fakeLikeEnabled(),
                    includeComments = !clearComments && fakeCommentEnabled()
                )
            }
            if (!itemRestored) {
                restored = false
            }
        }
        if (restored) {
            store.clearTypes(clearLikes, clearComments)
            prefs.edit().apply {
                if (clearLikes) remove(MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_LIKES)
                if (clearComments) remove(MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_COMMENTS)
                if (clearLikes && clearComments) {
                    remove(MomentsFakeInteractionSettings.KEY_PENDING_RESTORE_ALL)
                }
            }.commit()
            currentActivity()?.let { refreshTimelineViews(it, null) }
        }
        return restored
    }

    private fun queryNativeInfo(snsId: String): Any? {
        return queryNativeInfoLookup(snsId).nativeInfo
    }

    private fun queryNativeInfoLookup(snsId: String): SnsCachedNativeLookup {
        return withAutoMergeSuppressed {
            WeChatApis.snsApi()?.cachedNativeSnsInfoLookup(snsId)
                ?: SnsCachedNativeLookup(false, null)
        }
    }

    private fun mergeKnownEntries(
        first: FakeSnsInteraction,
        second: FakeSnsInteraction
    ): FakeSnsInteraction {
        val firstKnown = knownEntry(first)
        val secondKnown = knownEntry(second)
        return FakeSnsInteraction(
            likes = (firstKnown.likes + secondKnown.likes).distinctBy { it.wxId },
            comments = (firstKnown.comments + secondKnown.comments).distinctBy {
                Triple(it.id, it.authorWxId, it.content)
            }
        )
    }

    private fun submit(block: () -> Unit): Boolean {
        return try {
            worker.execute(Runnable(block))
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private inline fun <T> withAutoMergeSuppressed(block: () -> T): T {
        val previous = suppressAutoMerge.get()
        suppressAutoMerge.set(true)
        return try {
            block()
        } finally {
            if (previous == null) suppressAutoMerge.remove() else suppressAutoMerge.set(previous)
        }
    }

    private fun snsId(nativeInfo: Any): String? {
        val raw = KavaReflector.readField(nativeInfo, "field_snsId")
            ?: KavaReflector.readField(nativeInfo, "snsId")
            ?: return null
        val signed = when (raw) {
            is Number -> raw.toLong()
            else -> raw.toString().toLongOrNull() ?: return null
        }
        if (signed == 0L) return null
        return java.lang.Long.toUnsignedString(signed)
    }

    private fun attrBuf(nativeInfo: Any): ByteArray? {
        return KavaReflector.readField(nativeInfo, "field_attrBuf") as? ByteArray
            ?: KavaReflector.readField(nativeInfo, "attrBuf") as? ByteArray
    }

    private fun setAttrBuf(nativeInfo: Any, bytes: ByteArray): Boolean {
        val method = KavaReflector.findCompatibleMethod(nativeInfo.javaClass, "setAttrBuf", bytes)
            ?: return false
        return KavaReflector.invokeSuccessfully(method, nativeInfo, bytes)
    }

    private fun isRecordLoadMethod(method: Method): Boolean {
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        if (method.name != "convertFrom" || method.parameterTypes.size != 1) return false
        val type = method.parameterTypes[0]
        return Cursor::class.java.isAssignableFrom(type) || ContentValues::class.java.isAssignableFrom(type)
    }

    private fun fakeLikeEnabled(): Boolean {
        return baseReady && prefs.getBoolean(
            MomentsFakeInteractionSettings.KEY_FAKE_LIKE_ENABLE,
            MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_ENABLE
        )
    }

    private fun fakeCommentEnabled(): Boolean {
        return baseReady && commentGuardReady && prefs.getBoolean(
            MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_ENABLE,
            MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_ENABLE
        )
    }

    internal fun refreshTimelineViews(activity: Activity, anchor: View?) {
        val decor = activity.window?.decorView ?: return
        decor.post {
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (!notifyNearestAdapter(anchor)) notifyAdapters(decor)
            decor.requestLayout()
            decor.invalidate()
        }
    }

    private fun notifyNearestAdapter(anchor: View?): Boolean {
        var current: Any? = anchor
        while (current is View) {
            if (notifyRecyclerAdapter(current)) {
                return true
            }
            if (current is AbsListView) {
                MomentsFakeForwardRuntimeRegistry.reloadTimelineAdapter(current.adapter)
                current.invalidateViews()
                return true
            }
            current = current.parent
        }
        return false
    }

    private fun notifyAdapters(view: View) {
        if (!notifyRecyclerAdapter(view) && view is AbsListView) {
            MomentsFakeForwardRuntimeRegistry.reloadTimelineAdapter(view.adapter)
            view.invalidateViews()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) notifyAdapters(view.getChildAt(index))
        }
    }

    private fun notifyRecyclerAdapter(view: View): Boolean {
        val recyclerClass = recyclerViewClass ?: return false
        if (!recyclerClass.isInstance(view)) return false
        val adapter = KavaReflector.invokeMethod(view, "getAdapter")
        MomentsFakeForwardRuntimeRegistry.reloadTimelineAdapter(adapter)
        KavaReflector.invokeMethod(adapter, "notifyDataSetChanged")
        return true
    }

    private fun currentActivity(): Activity? {
        return (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    companion object {
        private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"
        private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    }
}
