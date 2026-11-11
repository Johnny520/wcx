package h.Hchat.hooks.items.linkparse

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatModernVideoSender
import h.Hchat.hooks.api.message.WeChatMessageChangeApi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.utils.KavaReflector
import h.Hchat.utils.HLog
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.preferences.HchatStorage
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal class LinkParseRuntime(private val context: FeatureContext) {
    private val host = context.hostContext().applicationContext ?: context.hostContext()
    private val settings = LinkParseSettings(host)
    private val claims = MessageClaims(HchatStorage.preferences(host, HISTORY_PREFS))
    private val methodPrefs = DexMethodCache.prefs(host, METHOD_CACHE_PREFS)
    private val client = WorkVideoClient()
    private val imageClient = WorkImageClient()
    private val pending = HashSet<String>()
    private val pendingUploads = ConcurrentHashMap<String, ConcurrentLinkedDeque<PendingUpload>>()
    private val nextTaskId = AtomicLong(0)
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(3), { r ->
        Thread(r, "HchatLinkParse").apply { isDaemon = true }
    }, ThreadPoolExecutor.AbortPolicy())
    private val cleaner = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "HchatLinkParseCleanup").apply { isDaemon = true }
    }
    @Volatile private var stopped = false
    @Volatile private var messageWriteHookInstalled = false
    private var modern: WeChatModernVideoSender? = null

    private data class VideoMetadata(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val codec: String
    )

    private enum class UploadKind(val label: String) { VIDEO("video"), IMAGE("image") }

    private data class PendingUpload(
        val taskId: Long,
        val kind: UploadKind,
        val enqueuedAt: Long,
        val createdAt: Long,
        val file: File
    )

    fun install(track: (Any) -> Unit) {
        cleaner.scheduleWithFixedDelay({
            runCatching {
                cleanup(File(host.cacheDir, CACHE_DIR))
                cleanupPendingUploads()
            }
        }, 0, 30, TimeUnit.MINUTES)
        DexInstallScheduler.schedule("link_parse_message_write", "链接解析消息写入", DexInstallScheduler.Stage.WARMUP) { installMessageWriteHook() }
        val changes = WeChatApis.message().changes()
        if (changes == null) HLog.e("[Hchat:LinkParse] 数据库监听不可用") else {
            runCatching { changes.install() }.onFailure { HLog.e("[Hchat:LinkParse] 数据库监听安装失败", it) }
            if (!changes.isInstalled) HLog.e("[Hchat:LinkParse] 数据库监听未就绪")
            val subscription = changes.subscribe(::onChange)
            if (subscription == null) HLog.e("[Hchat:LinkParse] 数据库订阅失败") else {
                track(subscription)
                info("[Hchat:LinkParse] 数据库订阅已注册")
            }
        }
    }

    fun destroy() {
        stopped = true
        executor.shutdownNow()
        cleaner.shutdownNow()
        synchronized(pending) { pending.clear() }
        pendingUploads.clear()
    }

    private fun onChange(change: WeChatMessageChangeApi.MessageChange) {
        if ((!change.isInsert() && !change.isUpdate()) ||
            (!settings.enabled() && pendingUploads.isEmpty())
        ) return
        observeUploadCompletion(change)
        val values = change.databaseChange?.values
        val status = if (values?.containsKey("status") == true) runCatching { values.getAsInteger("status") }.getOrNull() else null
        val message = change.message ?: run {
            if (status == 2) HLog.e("[Hchat:LinkParse] 消息字段不可用")
            return
        }
        if (!LinkParseEntryPolicy.acceptDatabase(change.isInsert, change.isUpdate, message.isOutgoing(), message.status, status)) return
        handleMessage(message)
    }

    @Synchronized
    private fun installMessageWriteHook(): Boolean {
        if (stopped) return false
        if (messageWriteHookInstalled) return true
        val runtimeKey = DexMethodCache.runtimeKey(host, context.hostClassLoader())
        if (runtimeKey.isBlank()) { HLog.e("[Hchat:LinkParse] 消息写入定位缓存不可用"); return false }
        val cached = DexMethodCache.load(methodPrefs, runtimeKey, context.hostClassLoader(), CACHE_MESSAGE_WRITE)
            ?.takeIf(::isMessageWriteMethod)
        val method = cached ?: findMessageWriteMethod()
        if (method == null) {
            DexMethodCache.clear(methodPrefs, runtimeKey, CACHE_MESSAGE_WRITE)
            HLog.e("[Hchat:LinkParse] 消息写入定位不可用")
            return false
        }
        if (cached == null) DexMethodCache.save(methodPrefs, runtimeKey, CACHE_MESSAGE_WRITE, method)
        HookRegistry.get().hook(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (stopped) return
                runCatching {
                    LinkParseWriteGate.capture(settings.enabled(), param.args)?.let {
                        param.setObjectExtra(WRITE_CAPTURE_KEY, it)
                    }
                }.onFailure { HLog.e("[Hchat:LinkParse] 消息写入捕获失败", it) }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || stopped) return
                runCatching {
                    val capture = param.getObjectExtra(WRITE_CAPTURE_KEY) as? LinkParseWriteGate.Capture ?: return
                    LinkParseWriteGate.observe(
                        result = (param.result as? Number)?.toInt(),
                        capture = capture,
                        lookup = { id -> WeChatApis.message().store()?.getMessageById(id) },
                        accept = ::handleMessage,
                        onError = { HLog.e("[Hchat:LinkParse] 消息写入观察失败") }
                    )
                }.onFailure { HLog.e("[Hchat:LinkParse] 消息写入观察失败", it) }
            }
        })
        messageWriteHookInstalled = true
        info("[Hchat:LinkParse] 消息写入观察已安装")
        return true
    }

    private fun findMessageWriteMethod(): Method? {
        val classes = context.dexBridgeHolder().findClassesByStrings(
            "CREATE INDEX IF NOT EXISTS messageSendCreateTimeIndex ON message ( status,isSend,createTime )"
        ).mapNotNull { runCatching { context.hostClassLoader().loadClass(it) }.getOrNull() }.distinct()
        val candidates = classes.flatMap { cls -> cls.declaredMethods.filter(::isMessageWriteMethod) }
        return candidates.singleOrNull()
    }

    private fun isMessageWriteMethod(method: Method): Boolean {
        val p = method.parameterTypes
        return p.size == 3 && p[0] == Long::class.javaPrimitiveType && p[2] == Boolean::class.javaPrimitiveType &&
            method.returnType == Int::class.javaPrimitiveType &&
            KavaReflector.findFieldRecursive(p[1], "field_status") != null &&
            KavaReflector.findFieldRecursive(p[1], "field_isSend") != null
    }

    private fun handleMessage(message: WeChatMessage) {
        val timestamp = LinkParseMessageGate.eligibleTime(message, System.currentTimeMillis()) ?: return
        val group = message.isGroupChat()
        val outgoing = message.isOutgoing()
        val self = if (group && !outgoing && settings.groupAtMeOnly()) {
            WeChatApis.contact().account()?.selfWxId().orEmpty()
        } else ""
        val atMe = self.isNotEmpty() && message.isAtMe(self)
        val content = message.bodyContent()
        info("[Hchat:LinkParse] 收到数据库文本")
        val match = WorkLinkMatcher.find(content) ?: run {
            info(
                "[Hchat:LinkParse] 未识别作品链接 chars=${content.length} " +
                    "has_http=${content.contains("http", true)} has_douyin=${content.contains("douyin", true)}"
            )
            return
        }
        if (!settings.allows(match.platform, message.talker, group, outgoing, atMe)) {
            info("[Hchat:LinkParse] 设置拒绝触发")
            return
        }
        val key = key(message.talker, message.msgId)
        submit(
            message.talker,
            match,
            group,
            outgoing,
            atMe,
            key,
            timestamp + LinkParseMessageGate.MAX_AGE + LinkParseMessageGate.FUTURE_TOLERANCE,
            SystemClock.elapsedRealtime()
        )
    }

    private fun submit(
        talker: String,
        match: WorkLinkMatcher.Match,
        group: Boolean,
        outgoing: Boolean,
        atMe: Boolean,
        key: String?,
        expiry: Long,
        enqueuedAt: Long
    ) {
        if (key != null) synchronized(pending) {
            if (stopped || !pending.add(key)) return
        }
        try {
            executor.execute {
                try {
                    if (!stopped && (key == null || claims.claim(key, expiry, System.currentTimeMillis()))) {
                        val queueWaitMs = (SystemClock.elapsedRealtime() - enqueuedAt).coerceAtLeast(0L)
                        process(talker, match, group, outgoing, atMe, enqueuedAt, queueWaitMs)
                    }
                } catch (error: Throwable) {
                    HLog.e("[Hchat:LinkParse] 处理异常", error)
                } finally {
                    if (key != null) synchronized(pending) { pending.remove(key) }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (key != null) synchronized(pending) { pending.remove(key) }
            HLog.e("[Hchat:LinkParse] 队列已满")
        }
    }

    private fun process(
        talker: String,
        match: WorkLinkMatcher.Match,
        group: Boolean,
        outgoing: Boolean,
        atMe: Boolean,
        enqueuedAt: Long,
        queueWaitMs: Long
    ) {
        if (stopped || !settings.allows(match.platform, talker, group, outgoing, atMe)) return
        val dir = File(host.cacheDir, CACHE_DIR)
        if ((!dir.isDirectory && !dir.mkdirs()) || !dir.canWrite()) return
        cleanup(dir)
        if ((dir.listFiles()?.sumOf { it.length().coerceAtLeast(0) } ?: 0L) > MAX_CACHE_BYTES - MAX_VIDEO_BYTES) return

        val resolveStart = SystemClock.elapsedRealtime()
        val resolved = client.resolve(match)
        val resolveMs = (SystemClock.elapsedRealtime() - resolveStart).coerceAtLeast(0L)
        if (resolved == null) {
            HLog.e("[Hchat:LinkParse] 解析失败 platform=${match.platform} queue_ms=$queueWaitMs resolve_ms=$resolveMs")
            return
        }
        when (resolved) {
            is WorkResolution.Video -> processVideo(talker, match, group, outgoing, atMe, enqueuedAt, queueWaitMs, resolveMs, resolved.candidates, dir)
            is WorkResolution.Gallery -> processGallery(talker, match, group, outgoing, atMe, enqueuedAt, queueWaitMs, resolveMs, resolved.images, dir)
        }
    }

    private fun processVideo(
        talker: String,
        match: WorkLinkMatcher.Match,
        group: Boolean,
        outgoing: Boolean,
        atMe: Boolean,
        enqueuedAt: Long,
        queueWaitMs: Long,
        resolveMs: Long,
        urls: List<String>,
        dir: File
    ) {
        val target = File(dir, "video_${System.currentTimeMillis()}_${System.nanoTime()}.mp4")
        var submitted = false
        var tracked: PendingUpload? = null
        try {
            val downloadStart = SystemClock.elapsedRealtime()
            for (url in urls) {
                if (!stopped && client.download(url, match.platform, target)) break
            }
            val downloadMs = (SystemClock.elapsedRealtime() - downloadStart).coerceAtLeast(0L)
            if (!target.isFile) {
                HLog.e("[Hchat:LinkParse] 下载失败 platform=${match.platform} queue_ms=$queueWaitMs resolve_ms=$resolveMs download_ms=$downloadMs")
                return
            }
            if (stopped || !settings.allows(match.platform, talker, group, outgoing, atMe)) return

            val bytes = target.length()
            val metadata = readVideoMetadata(target)
            val rate = if (downloadMs > 0) bytes.toDouble() / 1024.0 / (downloadMs / 1000.0) else 0.0
            info(
                "[Hchat:LinkParse] timing media=video platform=${match.platform} queue_ms=$queueWaitMs " +
                    "resolve_ms=$resolveMs download_ms=$downloadMs bytes=$bytes avg_kib_s=${String.format(Locale.US, "%.1f", rate)} " +
                    "width=${metadata.width} height=${metadata.height} rotation=${metadata.rotation} codec=${metadata.codec} duration_ms=${metadata.durationMs}"
            )

            val videos = WeChatApis.media()?.videos() ?: run { HLog.e("[Hchat:LinkParse] 发送接口不可用"); return }
            val digestStart = SystemClock.elapsedRealtime()
            val fileMd5 = md5File(target)
            val digestMs = (SystemClock.elapsedRealtime() - digestStart).coerceAtLeast(0L)
            val taskId = nextTaskId.incrementAndGet()
            tracked = fileMd5?.let { registerUpload(talker, UploadKind.VIDEO, it, taskId, enqueuedAt, target) }
            val submitStart = SystemClock.elapsedRealtime()
            submitted = if (videos.canSendSilently()) videos.send(talker, target.absolutePath) else {
                val sender = modern ?: WeChatModernVideoSender(context, videos).also { modern = it }
                sender.send(talker, target.absolutePath)
            }
            val submitMs = (SystemClock.elapsedRealtime() - submitStart).coerceAtLeast(0L)
            info(
                "[Hchat:LinkParse] timing send_submit media=video platform=${match.platform} " +
                    "submit_ms=$submitMs digest_ms=$digestMs accepted=$submitted bytes=$bytes task=$taskId"
            )
            if (!submitted) {
                tracked?.let(::removeUpload)
                HLog.e("[Hchat:LinkParse] 发送失败")
            }
        } finally {
            // Host sending is asynchronous; accepted files stay until a completion event or age cleanup.
            if (!submitted && target.exists()) target.delete()
        }
    }

    private fun processGallery(
        talker: String,
        match: WorkLinkMatcher.Match,
        group: Boolean,
        outgoing: Boolean,
        atMe: Boolean,
        enqueuedAt: Long,
        queueWaitMs: Long,
        resolveMs: Long,
        sources: List<WorkImageSource>,
        dir: File
    ) {
        if (!LinkParseGallerySupport.isVerified(match.platform)) {
            info("[Hchat:LinkParse] 图集发送未验收 platform=${match.platform}")
            return
        }
        if (sources.isEmpty() || sources.size > WorkImageExtractor.MAX_IMAGES) return
        val images = ArrayList<DownloadedWorkImage>(sources.size)
        var totalBytes = 0L
        var submittedCount = 0
        try {
            val downloadStart = SystemClock.elapsedRealtime()
            for ((index, source) in sources.withIndex()) {
                if (stopped || !settings.allows(match.platform, talker, group, outgoing, atMe)) return
                val base = File(dir, "image_${System.currentTimeMillis()}_${System.nanoTime()}_$index")
                val downloaded = imageClient.download(source, match.platform, base) ?: run {
                    HLog.e("[Hchat:LinkParse] 图集下载失败 platform=${match.platform} index=${index + 1}")
                    return
                }
                totalBytes += downloaded.bytes
                if (totalBytes > MAX_GALLERY_BYTES) {
                    downloaded.file.delete()
                    HLog.e("[Hchat:LinkParse] 图集超出字节上限 platform=${match.platform}")
                    return
                }
                images += downloaded
            }
            val downloadMs = (SystemClock.elapsedRealtime() - downloadStart).coerceAtLeast(0L)
            val rate = if (downloadMs > 0) totalBytes.toDouble() / 1024.0 / (downloadMs / 1000.0) else 0.0
            info(
                "[Hchat:LinkParse] timing media=gallery platform=${match.platform} queue_ms=$queueWaitMs " +
                    "resolve_ms=$resolveMs download_ms=$downloadMs images=${images.size} bytes=$totalBytes " +
                    "avg_kib_s=${String.format(Locale.US, "%.1f", rate)}"
            )
            if (stopped || !settings.allows(match.platform, talker, group, outgoing, atMe)) return
            val imageApi = WeChatApis.media()?.images() ?: run { HLog.e("[Hchat:LinkParse] 图片发送接口不可用"); return }
            if (!imageApi.canSendSilently()) { HLog.e("[Hchat:LinkParse] 图片发送接口未就绪"); return }

            for ((index, image) in images.withIndex()) {
                if (stopped || !settings.allows(match.platform, talker, group, outgoing, atMe)) return
                val digestStart = SystemClock.elapsedRealtime()
                val fileMd5 = md5File(image.file)
                val digestMs = (SystemClock.elapsedRealtime() - digestStart).coerceAtLeast(0L)
                val taskId = nextTaskId.incrementAndGet()
                val tracked = fileMd5?.let { registerUpload(talker, UploadKind.IMAGE, it, taskId, enqueuedAt, image.file) }
                val submitStart = SystemClock.elapsedRealtime()
                val accepted = imageApi.sendOriginal(talker, image.file.absolutePath)
                val submitMs = (SystemClock.elapsedRealtime() - submitStart).coerceAtLeast(0L)
                info(
                    "[Hchat:LinkParse] timing send_submit media=image platform=${match.platform} " +
                        "index=${index + 1}/${images.size} format=${image.format} bytes=${image.bytes} " +
                        "submit_ms=$submitMs digest_ms=$digestMs accepted=$accepted task=$taskId"
                )
                if (!accepted) {
                    tracked?.let(::removeUpload)
                    HLog.e("[Hchat:LinkParse] 图片发送失败 platform=${match.platform} index=${index + 1}")
                    return
                }
                submittedCount++
                if (index < images.lastIndex) Thread.sleep(300)
            }
        } finally {
            // Accepted images remain available to WeChat's asynchronous sender; unsent files are removed now.
            images.drop(submittedCount).forEach { image -> if (image.file.exists()) image.file.delete() }
        }
    }

    private fun readVideoMetadata(file: File): VideoMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            VideoMetadata(
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                codec = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().ifBlank { "unknown" }
            )
        } catch (_: Throwable) {
            VideoMetadata(0L, 0, 0, 0, "unknown")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun registerUpload(
        talker: String,
        kind: UploadKind,
        fileMd5: String,
        taskId: Long,
        enqueuedAt: Long,
        file: File
    ): PendingUpload? {
        val digest = fileMd5.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return null
        val key = uploadKey(talker, kind, digest)
        val upload = PendingUpload(taskId, kind, enqueuedAt, SystemClock.elapsedRealtime(), file)
        pendingUploads.computeIfAbsent(key) { ConcurrentLinkedDeque() }.addLast(upload)
        return upload
    }

    private fun removeUpload(upload: PendingUpload) {
        for ((key, queue) in pendingUploads.entries) {
            queue.remove(upload)
            if (queue.isEmpty()) pendingUploads.remove(key, queue)
        }
    }

    private fun observeUploadCompletion(change: WeChatMessageChangeApi.MessageChange) {
        if (!change.isUpdate()) return
        val message = change.message ?: return
        if (!message.isOutgoing()) return
        val statusFromChange = change.databaseChange?.values?.let { values ->
            if (values.containsKey("status")) runCatching { values.getAsInteger("status") }.getOrNull() else null
        }
        val status = statusFromChange ?: message.status
        if (status != 2 && status != 5) return
        val kind: UploadKind
        val digests: List<String>
        when {
            message.isVideo() || message.type == 62 -> {
                kind = UploadKind.VIDEO
                val video = message.getVideoMsg() ?: return
                digests = listOf(video.md5, video.newMd5)
            }
            message.isImage() -> {
                kind = UploadKind.IMAGE
                digests = listOf(message.getImageMsg()?.md5.orEmpty())
            }
            else -> return
        }
        val upload = digests.asSequence()
            .filter { it.isNotBlank() }
            .map { uploadKey(message.talker, kind, it.lowercase(Locale.ROOT)) }
            .mapNotNull { key ->
                val queue = pendingUploads[key] ?: return@mapNotNull null
                val first = queue.pollFirst() ?: return@mapNotNull null
                if (queue.isEmpty()) pendingUploads.remove(key, queue)
                first
            }
            .firstOrNull() ?: return
        val result = if (status == 2) "success" else "failed"
        val elapsed = (SystemClock.elapsedRealtime() - upload.createdAt).coerceAtLeast(0L)
        val totalElapsed = (SystemClock.elapsedRealtime() - upload.enqueuedAt).coerceAtLeast(0L)
        info(
            "[Hchat:LinkParse] timing send_complete media=${kind.label} result=$result " +
                "after_submit_ms=$elapsed total_ms=$totalElapsed task=${upload.taskId}"
        )
        if (status == 5 && upload.file.exists()) upload.file.delete()
    }

    private fun cleanupPendingUploads() {
        val now = SystemClock.elapsedRealtime()
        for ((key, queue) in pendingUploads.entries) {
            queue.removeIf { now - it.createdAt > CACHE_TTL }
            if (queue.isEmpty()) pendingUploads.remove(key, queue)
        }
    }

    private fun cleanup(dir: File) {
        val cutoff = System.currentTimeMillis() - CACHE_TTL
        dir.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.startsWith("video_") || file.name.startsWith("image_")) &&
                file.lastModified() in 1 until cutoff) file.delete()
        }
    }

    private fun uploadKey(talker: String, kind: UploadKind, digest: String): String =
        sha256("${kind.label}\u0000$talker\u0000$digest")

    private fun md5File(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun info(message: String) {
        Log.i("Hchat", message)
    }

    private fun key(talker: String, id: Long): String = MessageDigest.getInstance("SHA-256")
        .digest("$talker\u0000$id".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private const val HISTORY_PREFS = "Hchat_link_parse_history"
        private const val METHOD_CACHE_PREFS = "Hchat_link_parse_method_cache"
        private const val CACHE_MESSAGE_WRITE = "message_write_v1"
        private const val WRITE_CAPTURE_KEY = "h.Hchat.link_parse.message_write_capture"
        private const val CACHE_DIR = "Hchat_link_parse"
        private const val CACHE_TTL = 6 * 60 * 60 * 1000L
        private const val MAX_CACHE_BYTES = 256L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 50L * 1024 * 1024
        private const val MAX_GALLERY_BYTES = 40L * 1024 * 1024
    }
}
