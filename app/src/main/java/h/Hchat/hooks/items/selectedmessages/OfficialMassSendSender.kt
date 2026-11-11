package h.Hchat.hooks.items.selectedmessages

import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexMethodCache
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.WeChatEmojiApi
import h.Hchat.hooks.api.media.WeChatVoiceApi
import h.Hchat.hooks.api.message.WeChatRetransmitPayload
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskContentItem
import h.Hchat.hooks.items.scheduledtask.ScheduledTaskSettings
import h.Hchat.utils.KavaReflector
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.MethodData
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import java.util.UUID

class OfficialMassSendSender(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val methodPrefs = DexMethodCache.prefs(context.hostContext(), PREFS_NAME)
    private val batches = ArrayDeque<SendBatch>()
    @Volatile private var runtime: MassSendRuntime? = null
    private var activeBatch: SendBatch? = null
    private var activeScene: Any? = null
    private var activeInfo: Any? = null
    private var timeoutRunnable: Runnable? = null
    @Volatile private var callbackHookInstalled = false

    fun install(): Boolean {
        val resolved = locateRuntime() ?: return false
        runtime = resolved
        if (callbackHookInstalled) return true
        return runCatching {
            HookRegistry.get().hook(resolved.sceneEndMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleSceneEnd(param.thisObject, param.args)
                }
            })
            callbackHookInstalled = true
            true
        }.onFailure {
            logger("微信原生群发助手完成回调Hook失败: ${resolved.sceneEndMethod.toGenericString()}", it)
        }.getOrDefault(false)
    }

    fun enqueue(
        snapshots: List<SelectedMessageSnapshot>,
        targetIds: List<String>,
        targetIntervalSeconds: Int = 0,
        itemIntervalSeconds: Int = 0,
        onComplete: ((success: Int, total: Int, canceled: Boolean) -> Unit)? = null
    ): SelectedMessageSendHandle? {
        val resolved = runtime ?: locateRuntime()?.also { runtime = it } ?: return null
        val targets = targetIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (snapshots.isEmpty() || targets.isEmpty()) return null
        if (snapshots.any { !isSupported(it, resolved) }) return null
        val prepared = snapshots.map { snapshot ->
            val type = snapshot.type and 0xffff
            val emoji = if (type == 47) prepareEmojiPayload(snapshot) else null
            val voice = if (type == 34) prepareVoicePayload(snapshot) else null
            if (type == 47 && emoji == null) return null
            if (type == 34 && voice == null) return null
            PreparedSnapshot(snapshot, emoji, voice)
        }
        val jobs = targets.chunked(contactLimit(resolved)).flatMapIndexed { targetBatchIndex, contactBatch ->
            prepared.map { item ->
                SendJob(
                    item.snapshot,
                    contactBatch,
                    item.emojiPayload,
                    item.voicePayload,
                    targetBatchIndex
                )
            }
        }
        if (jobs.isEmpty()) return null
        val globalTargetIntervalSeconds =
            SelectedMessagesSettings.officialIntervalMinutes(context.hostContext()) * 60L
        val globalItemIntervalSeconds =
            SelectedMessagesSettings.sendIntervalSeconds(context.hostContext()).toLong()
        val batch = SendBatch(
            id = UUID.randomUUID().toString(),
            jobs = jobs,
            total = snapshots.size * targets.size,
            onComplete = onComplete,
            targetBatchDelayMillis = maxOf(
                targetIntervalSeconds.coerceIn(0, 3600).toLong(),
                globalTargetIntervalSeconds
            ) * 1000L,
            itemDelayMillis = maxOf(
                itemIntervalSeconds.coerceIn(0, 3600).toLong(),
                globalItemIntervalSeconds
            ) * 1000L
        )
        main.post {
            batches.addLast(batch)
            if (activeBatch == null) startNextBatch()
        }
        return SelectedMessageSendHandle { cancel(batch.id) }
    }

    fun isAvailable(): Boolean = runtime != null && callbackHookInstalled

    fun enqueueCustom(
        items: List<ScheduledTaskContentItem>,
        targetIds: List<String>,
        targetIntervalSeconds: Int = 0,
        itemIntervalSeconds: Int = 0,
        onComplete: ((success: Int, total: Int, canceled: Boolean) -> Unit)? = null
    ): SelectedMessageSendHandle? {
        val snapshots = customSnapshots(items) ?: return null
        return enqueue(
            snapshots,
            targetIds,
            targetIntervalSeconds,
            itemIntervalSeconds,
            onComplete
        )
    }

    fun unsupportedCustomLabels(items: List<ScheduledTaskContentItem>): List<String> {
        val resolved = runtime ?: return items.map { MassSendContentPolicy.customTypeLabel(it.type) }.distinct()
        return items.mapNotNull { item ->
            val snapshot = customSnapshot(item, 0)
            if (snapshot == null || !isSupported(snapshot, resolved)) {
                MassSendContentPolicy.customTypeLabel(item.type)
            } else null
        }.distinct()
    }

    fun customPreparationError(items: List<ScheduledTaskContentItem>): String? {
        val snapshots = customSnapshots(items) ?: return "原生群发内容无效"
        return preparationError(snapshots)
    }

    fun unsupportedLabels(snapshots: List<SelectedMessageSnapshot>): List<String> {
        val resolved = runtime ?: return snapshots.map { it.label() }.distinct()
        return snapshots.filterNot { isSupported(it, resolved) }.map { it.label() }.distinct()
    }

    fun preparationError(snapshots: List<SelectedMessageSnapshot>): String? {
        if (snapshots.any { (it.type and 0xffff) == 47 && prepareEmojiPayload(it) == null }) {
            return "无法读取原生群发表情"
        }
        return null
    }

    private fun customSnapshots(items: List<ScheduledTaskContentItem>): List<SelectedMessageSnapshot>? {
        val result = ArrayList<SelectedMessageSnapshot>(items.size)
        items.forEachIndexed { index, item ->
            result += customSnapshot(item, index) ?: return null
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun customSnapshot(item: ScheduledTaskContentItem, index: Int): SelectedMessageSnapshot? {
        val nativeType = when (item.type) {
            ScheduledTaskSettings.TYPE_TEXT -> 1
            ScheduledTaskSettings.TYPE_IMAGE -> 3
            ScheduledTaskSettings.TYPE_VIDEO -> 43
            ScheduledTaskSettings.TYPE_EMOJI -> 47
            ScheduledTaskSettings.TYPE_VOICE -> 34
            ScheduledTaskSettings.TYPE_XML -> 49
            else -> return null
        }
        val value = item.value.trim()
        if (value.isBlank()) return null
        val now = System.currentTimeMillis()
        val retransmitMedia = nativeType == 3 || nativeType == 43 || nativeType == 47
        val payload = if (retransmitMedia) {
            val file = File(value)
            WeChatRetransmitPayload(
                msgId = now + index,
                sourceTalker = "",
                content = "",
                retrType = nativeType,
                msgFromScene = 2,
                fileName = value,
                length = file.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            )
        } else {
            null
        }
        return SelectedMessageSnapshot(
            msgId = now + index,
            type = nativeType,
            sourceTalker = "",
            content = if (nativeType == 1 || nativeType == 49) value else "",
            imagePath = if (nativeType == 43) value else "",
            createTime = now,
            retransmit = payload,
            voicePath = if (nativeType == 34) value else "",
            voiceDurationMillis = 0
        )
    }

    private fun startNextBatch() {
        if (activeBatch != null) return
        activeBatch = batches.pollFirst()
        activeBatch?.let(::sendCurrent)
    }

    private fun sendCurrent(batch: SendBatch) {
        if (activeBatch !== batch) return
        if (batch.index >= batch.jobs.size) {
            val success = batch.success
            val total = batch.total
            val callback = batch.onComplete
            activeBatch = null
            activeScene = null
            activeInfo = null
            callback?.invoke(success, total, false)
            startNextBatch()
            return
        }
        val resolved = runtime
        if (resolved == null) {
            completeCurrent(batch, false)
            return
        }
        val job = batch.jobs[batch.index]
        val contacts = job.targets.joinToString(";")
        val info = createInfo(
            resolved,
            job.snapshot,
            contacts,
            job.targets.size,
            job.emojiPayload,
            job.voicePayload
        )
        if (info == null) {
            completeCurrent(batch, false)
            return
        }
        val sceneMode = if ((job.snapshot.type and 0xffff) == 3) IMAGE_ORIGINAL_MODE else 0
        val scene = KavaReflector.newInstance(resolved.sceneConstructor, info, false, sceneMode)
        if (scene == null) {
            completeCurrent(batch, false)
            return
        }
        activeScene = scene
        activeInfo = info
        val sent = runCatching { submitScene(resolved, scene) }
            .onFailure { logger("微信原生群发助手派发失败", it) }
            .getOrDefault(false)
        if (!sent) {
            activeScene = null
            activeInfo = null
            completeCurrent(batch, false)
            return
        }
        timeoutRunnable = Runnable {
            if (activeScene === scene) {
                logger("微信原生群发助手等待发送完成超时: type=${job.snapshot.type}", null)
                activeScene = null
                activeInfo = null
                completeCurrent(batch, false)
            }
        }.also { main.postDelayed(it, timeoutFor(job.snapshot)) }
    }

    private fun handleSceneEnd(scene: Any?, args: Array<Any?>?) {
        if (scene == null) return
        val errType = (args?.getOrNull(1) as? Number)?.toInt() ?: -1
        val errCode = (args?.getOrNull(2) as? Number)?.toInt() ?: -1
        main.post {
            if (scene !== activeScene) return@post
            val batch = activeBatch ?: return@post
            if (errType == 0 && errCode == 0) {
                val status = activeInfo?.let { info ->
                    KavaReflector.readField(runtime?.statusField, info) as? Number
                }?.toInt()
                if (status != MASS_SEND_STATUS_DONE) return@post
            }
            timeoutRunnable?.let(main::removeCallbacks)
            timeoutRunnable = null
            activeScene = null
            activeInfo = null
            completeCurrent(batch, errType == 0 && errCode == 0)
        }
    }

    private fun completeCurrent(batch: SendBatch, success: Boolean) {
        timeoutRunnable?.let(main::removeCallbacks)
        timeoutRunnable = null
        activeScene = null
        activeInfo = null
        val job = batch.jobs.getOrNull(batch.index)
        if (success && job != null) batch.success += job.targets.size
        batch.index++
        val next = batch.jobs.getOrNull(batch.index)
        if (job == null || next == null) {
            main.post { sendCurrent(batch) }
            return
        }
        val configuredDelay = if (next.targetBatchIndex != job.targetBatchIndex) {
            batch.targetBatchDelayMillis
        } else {
            batch.itemDelayMillis
        }
        main.postDelayed({ sendCurrent(batch) }, maxOf(NEXT_SEND_DELAY_MS, configuredDelay))
    }

    private fun cancel(batchId: String) {
        main.post {
            val active = activeBatch
            if (active?.id == batchId) {
                timeoutRunnable?.let(main::removeCallbacks)
                timeoutRunnable = null
                activeScene?.let { scene -> KavaReflector.invokeMethod(scene, "cancel") }
                activeScene = null
                activeInfo = null
                activeBatch = null
                active.onComplete?.invoke(active.success, active.total, true)
                startNextBatch()
                return@post
            }
            val iterator = batches.iterator()
            while (iterator.hasNext()) {
                val pending = iterator.next()
                if (pending.id != batchId) continue
                iterator.remove()
                pending.onComplete?.invoke(0, pending.total, true)
                break
            }
        }
    }

    private fun isSupported(snapshot: SelectedMessageSnapshot, runtime: MassSendRuntime): Boolean {
        if (!MassSendContentPolicy.supportsOfficial(snapshot)) return false
        return when (snapshot.type and 0xffff) {
            1 -> snapshot.content.isNotBlank()
            3 -> File(snapshot.retransmit?.fileName.orEmpty()).isFile &&
                runtime.imageBuilder != null && runtime.storage != null
            34 -> File(snapshot.voicePath).isFile &&
                WeChatApis.media()?.voices()?.canPrepareMassSend() == true
            43, 62 -> nativeVideoToken(snapshot).isNotBlank() && videoLocalPath(snapshot).isNotBlank()
            47 -> runtime.emojiRuntime != null && emojiSource(snapshot).isNotBlank()
            49 -> snapshot.content.isNotBlank()
            else -> false
        }
    }

    private fun createInfo(
        runtime: MassSendRuntime,
        snapshot: SelectedMessageSnapshot,
        contacts: String,
        count: Int,
        emojiPayload: WeChatEmojiApi.MassSendPayload?,
        voicePayload: WeChatVoiceApi.MassSendPayload?
    ): Any? {
        val type = snapshot.type and 0xffff
        if (type == 3) {
            val storage = runtime.storage ?: return null
            val path = snapshot.retransmit?.fileName.orEmpty()
            return KavaReflector.invoke(
                runtime.imageBuilder,
                storage,
                path,
                contacts,
                count,
                IMAGE_ORIGINAL_MODE
            )
        }
        val info = KavaReflector.newInstance(runtime.infoConstructor) ?: return null
        val emojiBytes = if (type == 47) {
            val emojiRuntime = runtime.emojiRuntime ?: return null
            val payload = emojiPayload ?: return null
            buildEmojiBytes(emojiRuntime, payload) ?: return null
        } else {
            null
        }
        val content = when (type) {
            34 -> voicePayload?.fileName.orEmpty()
            43, 62 -> nativeVideoToken(snapshot)
            47 -> emojiPayload?.md5.orEmpty()
            else -> snapshot.retransmit?.content.orEmpty().ifBlank { snapshot.content }
        }
        write(info, "h", content)
        write(info, runtime.contactField, contacts)
        write(info, "n", count)
        write(info, "o", if (type == 62) 43 else type)
        write(info, "p", when (type) {
            34 -> voicePayload?.durationMillis ?: 0
            43, 62 -> videoDurationSeconds(snapshot)
            else -> 0
        })
        if (type == 43 || type == 62) write(info, "u", MASS_SEND_VIDEO_RESERVED)
        if (emojiBytes != null) {
            write(info, "r", emojiBytes.size)
            write(info, "y", emojiBytes)
        }
        return info
    }

    private fun prepareEmojiPayload(snapshot: SelectedMessageSnapshot): WeChatEmojiApi.MassSendPayload? {
        val source = emojiSource(snapshot)
        if (source.isBlank()) return null
        return WeChatApis.media()?.emojis()?.prepareMassSendPayload(source)
    }

    private fun prepareVoicePayload(snapshot: SelectedMessageSnapshot): WeChatVoiceApi.MassSendPayload? {
        if (!File(snapshot.voicePath).isFile) return null
        val voices = WeChatApis.media()?.voices() ?: return null
        voices.existingMassSendPayload(
            snapshot.voiceFileName,
            snapshot.voiceDurationMillis
        )?.let { return it }
        return voices.prepareMassSendPayload(
            snapshot.voicePath,
            snapshot.voiceDurationMillis
        )
    }

    private fun emojiSource(snapshot: SelectedMessageSnapshot): String {
        val fileName = snapshot.retransmit?.fileName.orEmpty().trim()
        if (File(fileName).isFile || fileName.matches(Regex("[0-9a-fA-F]{32}"))) return fileName
        val content = snapshot.retransmit?.content.orEmpty().ifBlank { snapshot.content }
        return h.Hchat.hooks.api.model.WeChatMessage.xmlAttr(content, "md5")
            .ifBlank { h.Hchat.hooks.api.model.WeChatMessage.xmlTag(content, "md5") }
    }

    private fun nativeVideoToken(snapshot: SelectedMessageSnapshot): String {
        if (snapshot.sourceTalker.isBlank()) return ""
        return snapshot.imagePath.trim()
    }

    private fun videoLocalPath(snapshot: SelectedMessageSnapshot): String {
        val candidates = listOf(snapshot.retransmit?.fileName.orEmpty(), snapshot.imagePath)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        candidates.firstOrNull { File(it).isFile }?.let { return File(it).absolutePath }
        val videos = WeChatApis.media()?.videos()
        return candidates.firstNotNullOfOrNull { token ->
            videos?.resolvePathToken(token)?.takeIf { it.isNotBlank() }
        } ?: candidates.firstOrNull().orEmpty()
    }

    private fun videoDurationSeconds(snapshot: SelectedMessageSnapshot): Int {
        snapshot.videoDurationSeconds.takeIf { it > 0 }?.let { return it }
        val raw = snapshot.retransmit?.content.orEmpty().ifBlank { snapshot.content }
        WeChatMessage.xmlAttr(raw, "playlength").toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        WeChatMessage.xmlTag(raw, "playlength").toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val path = videoLocalPath(snapshot)
        if (path.isBlank()) return 0
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            ((millis + 999L) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun timeoutFor(snapshot: SelectedMessageSnapshot): Long {
        return when (snapshot.type and 0xffff) {
            3 -> 120_000L
            34 -> 90_000L
            43, 62 -> 300_000L
            else -> 30_000L
        }
    }

    private fun submitScene(runtime: MassSendRuntime, scene: Any): Boolean {
        runtime.nativeSubmitRuntime?.let { native ->
            val result = runCatching {
                val dispatcher = KavaReflector.invokeOrThrow(native.dispatcherGetter, null)
                    ?: error("官方群发网络队列为空")
                KavaReflector.invokeOrThrow(native.submitMethod, dispatcher, scene)
            }
            if (result.isSuccess) {
                return when (val value = result.getOrNull()) {
                    is Boolean -> value
                    is Number -> value.toInt() >= 0
                    else -> true
                }
            }
            logger("微信原生群发助手精确派发失败，已回退通用网络入口", result.exceptionOrNull())
        }
        return WeChatApis.network()?.sendRequest(scene) == true
    }

    private fun contactLimit(runtime: MassSendRuntime): Int {
        val service = runtime.limitServiceGetter?.let { KavaReflector.invoke(it, null) }
        val configured = if (service != null && runtime.limitMethod != null) {
            (KavaReflector.invoke(runtime.limitMethod, service) as? Number)?.toInt()
        } else null
        return configured?.takeIf { it > 0 } ?: DEFAULT_CONTACT_LIMIT
    }

    private fun locateRuntime(): MassSendRuntime? {
        val cacheKey = DexMethodCache.runtimeKey(context.hostContext(), context.hostClassLoader())
        val cached = DexMethodCache.load(methodPrefs, cacheKey, context.hostClassLoader(), CACHE_ANCHOR)
        val baseRuntime = cached?.let(::runtimeFromAnchor) ?: run {
            val anchors = runCatching {
                context.dexKitBridge().findMethod(
                    FindMethod().apply {
                        matcher(MethodMatcher().apply {
                            usingStrings(listOf("MicroMsg.NetSceneMasSend"))
                        })
                    }
                ).mapNotNull { runCatching { it.getMethodInstance(context.hostClassLoader()) }.getOrNull() }
            }.onFailure { logger("定位微信原生群发助手网络类失败", it) }.getOrDefault(emptyList())
            val grouped = anchors.groupBy { it.declaringClass }.mapNotNull { (_, methods) ->
                methods.firstNotNullOfOrNull(::runtimeFromAnchor)?.let { methods.first() to it }
            }
            val single = grouped.singleOrNull()
            if (single == null) {
                DexMethodCache.clear(methodPrefs, cacheKey, CACHE_ANCHOR)
                if (grouped.size > 1) logger("微信原生群发助手网络类候选不唯一", null)
                return null
            }
            DexMethodCache.save(methodPrefs, cacheKey, CACHE_ANCHOR, single.first)
            single.second
        }
        return baseRuntime.copy(
            emojiRuntime = locateEmojiRuntime(cacheKey),
            nativeSubmitRuntime = locateNativeSubmitRuntime(
                cacheKey,
                baseRuntime.sceneConstructor.declaringClass
            )
        )
    }

    private fun locateNativeSubmitRuntime(
        cacheKey: String,
        sceneClass: Class<*>
    ): NativeSubmitRuntime? {
        val cachedGetter = DexMethodCache.load(
            methodPrefs,
            cacheKey,
            context.hostClassLoader(),
            CACHE_NATIVE_DISPATCHER_GETTER
        )
        val cachedSubmit = DexMethodCache.load(
            methodPrefs,
            cacheKey,
            context.hostClassLoader(),
            CACHE_NATIVE_SUBMIT
        )
        if (cachedGetter != null && cachedSubmit != null &&
            isNativeDispatcherGetter(cachedGetter, cachedSubmit.declaringClass) &&
            isNativeSubmitMethod(cachedSubmit, sceneClass)
        ) {
            return NativeSubmitRuntime(cachedGetter, cachedSubmit)
        }
        DexMethodCache.clear(methodPrefs, cacheKey, CACHE_NATIVE_DISPATCHER_GETTER)
        DexMethodCache.clear(methodPrefs, cacheKey, CACHE_NATIVE_SUBMIT)
        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(listOf("MicroMsg.MassSendFooterEventImpl"))
                    })
                }
            ).mapNotNull { methodData ->
                val invokes = methodData.invokes.mapNotNull { invokeData ->
                    runCatching {
                        invokeData.getMethodInstance(context.hostClassLoader())
                    }.getOrNull()
                }
                val submit = invokes.singleOrNull { isNativeSubmitMethod(it, sceneClass) }
                    ?: return@mapNotNull null
                val getter = invokes.singleOrNull {
                    isNativeDispatcherGetter(it, submit.declaringClass)
                } ?: return@mapNotNull null
                NativeSubmitRuntime(getter, submit)
            }.distinctBy {
                it.dispatcherGetter.toGenericString() + "\n" + it.submitMethod.toGenericString()
            }
        }.onFailure {
            logger("定位微信原生群发助手精确派发入口失败", it)
        }.getOrDefault(emptyList())
        val single = candidates.singleOrNull() ?: return null
        DexMethodCache.save(
            methodPrefs,
            cacheKey,
            CACHE_NATIVE_DISPATCHER_GETTER,
            single.dispatcherGetter
        )
        DexMethodCache.save(methodPrefs, cacheKey, CACHE_NATIVE_SUBMIT, single.submitMethod)
        return single
    }

    private fun isNativeSubmitMethod(method: Method, sceneClass: Class<*>): Boolean {
        val types = method.parameterTypes
        return !Modifier.isStatic(method.modifiers) &&
            (method.returnType == Boolean::class.javaPrimitiveType ||
                method.returnType == java.lang.Boolean::class.java) &&
            types.size == 1 && types[0].isAssignableFrom(sceneClass)
    }

    private fun isNativeDispatcherGetter(method: Method, dispatcherClass: Class<*>): Boolean {
        return Modifier.isStatic(method.modifiers) &&
            method.parameterTypes.isEmpty() &&
            method.returnType == dispatcherClass
    }

    private fun locateEmojiRuntime(cacheKey: String): MassSendEmojiRuntime? {
        val cachedMethod = DexMethodCache.load(
            methodPrefs,
            cacheKey,
            context.hostClassLoader(),
            CACHE_EMOJI_CALLBACK
        )
        if (cachedMethod != null) {
            emojiRuntimeFromSpec(methodPrefs.getString(CACHE_EMOJI_SPEC, "").orEmpty())?.let { return it }
        }
        val candidates = runCatching {
            context.dexKitBridge().findMethod(
                FindMethod().apply {
                    matcher(MethodMatcher().apply {
                        usingStrings(listOf("MicroMsg.MassSendFooterEventImpl"))
                    })
                }
            ).mapNotNull { methodData ->
                val method = runCatching {
                    methodData.getMethodInstance(context.hostClassLoader())
                }.getOrNull() ?: return@mapNotNull null
                val resolved = emojiRuntimeFromMethodData(methodData) ?: return@mapNotNull null
                method to resolved
            }
        }.onFailure { logger("定位微信原生群发表情协议失败", it) }.getOrDefault(emptyList())
        val single = candidates.singleOrNull()
        if (single == null) {
            DexMethodCache.clear(methodPrefs, cacheKey, CACHE_EMOJI_CALLBACK)
            methodPrefs.edit().remove(CACHE_EMOJI_SPEC).apply()
            if (candidates.size > 1) logger("微信原生群发表情协议候选不唯一", null)
            return null
        }
        DexMethodCache.save(methodPrefs, cacheKey, CACHE_EMOJI_CALLBACK, single.first)
        methodPrefs.edit().putString(CACHE_EMOJI_SPEC, single.second.encode()).apply()
        return single.second
    }

    private fun emojiRuntimeFromMethodData(methodData: MethodData): MassSendEmojiRuntime? {
        val fieldsByClass = methodData.usingFields
            .mapNotNull { it.field }
            .groupBy { it.className }
        return fieldsByClass.mapNotNull { (className, usedFields) ->
            val payloadClass = KavaReflector.loadClass(className, context.hostClassLoader())
                ?: return@mapNotNull null
            val toByteArray = KavaReflector.findMethodRecursive(payloadClass, "toByteArray")
                ?.takeIf { it.parameterTypes.isEmpty() && it.returnType == ByteArray::class.java }
                ?: return@mapNotNull null
            val fields = usedFields.distinctBy { it.fieldName }.mapNotNull { fieldData ->
                KavaReflector.findFieldRecursive(payloadClass, fieldData.fieldName)
            }
            val stringFields = fields.filter { it.type == String::class.java }
            val intFields = fields.filter { it.type == Int::class.javaPrimitiveType }
            val bufferFields = fields.filter {
                !it.type.isPrimitive && it.type != String::class.java && !it.type.isArray
            }
            if (stringFields.size != 2 || intFields.size != 4 || bufferFields.size != 1) {
                return@mapNotNull null
            }
            val payloadConstructor = KavaReflector.declaredConstructors(payloadClass)
                .singleOrNull { it.parameterTypes.isEmpty() } ?: return@mapNotNull null
            val bufferConstructor = KavaReflector.declaredConstructors(bufferFields[0].type)
                .singleOrNull { it.parameterTypes.isEmpty() } ?: return@mapNotNull null
            MassSendEmojiRuntime(
                payloadConstructor = payloadConstructor,
                toByteArrayMethod = toByteArray,
                md5Field = stringFields[0],
                startField = intFields[0],
                sizeField = intFields[1],
                bufferField = bufferFields[0],
                bufferConstructor = bufferConstructor,
                typeField = intFields[2],
                contentField = stringFields[1],
                reservedField = intFields[3]
            )
        }.singleOrNull()
    }

    private fun emojiRuntimeFromSpec(value: String): MassSendEmojiRuntime? {
        val parts = value.split('\n')
        if (parts.size != 8) return null
        val payloadClass = KavaReflector.loadClass(parts[0], context.hostClassLoader()) ?: return null
        val payloadConstructor = KavaReflector.declaredConstructors(payloadClass)
            .singleOrNull { it.parameterTypes.isEmpty() } ?: return null
        val toByteArray = KavaReflector.findMethodRecursive(payloadClass, "toByteArray")
            ?.takeIf { it.parameterTypes.isEmpty() && it.returnType == ByteArray::class.java }
            ?: return null
        val fields = parts.drop(1).map { name ->
            KavaReflector.findFieldRecursive(payloadClass, name) ?: return null
        }
        if (fields[0].type != String::class.java ||
            fields[1].type != Int::class.javaPrimitiveType ||
            fields[2].type != Int::class.javaPrimitiveType ||
            fields[4].type != Int::class.javaPrimitiveType ||
            fields[5].type != String::class.java ||
            fields[6].type != Int::class.javaPrimitiveType
        ) return null
        val bufferConstructor = KavaReflector.declaredConstructors(fields[3].type)
            .singleOrNull { it.parameterTypes.isEmpty() } ?: return null
        return MassSendEmojiRuntime(
            payloadConstructor,
            toByteArray,
            fields[0],
            fields[1],
            fields[2],
            fields[3],
            bufferConstructor,
            fields[4],
            fields[5],
            fields[6]
        )
    }

    private fun runtimeFromAnchor(anchor: Method): MassSendRuntime? {
        val sceneClass = anchor.declaringClass
        val sceneConstructor = KavaReflector.declaredConstructors(sceneClass)
            .firstOrNull(::isSceneConstructor) ?: return null
        val infoClass = sceneConstructor.parameterTypes[0]
        val infoConstructor = KavaReflector.declaredConstructors(infoClass)
            .firstOrNull { it.parameterTypes.isEmpty() } ?: return null
        val packageName = sceneClass.name.substringBeforeLast('.', "")
        if (packageName.isBlank()) return null
        val storageClass = KavaReflector.loadClass("$packageName.z", context.hostClassLoader())
        val coreClass = KavaReflector.loadClass("$packageName.k0", context.hostClassLoader())
        val limitServiceClass = KavaReflector.loadClass("$packageName.a0", context.hostClassLoader())
        val storage = if (storageClass != null && coreClass != null) {
            KavaReflector.declaredMethods(coreClass)
                .firstOrNull { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.isEmpty() &&
                        method.returnType == storageClass
                }?.let { KavaReflector.invoke(it, null) }
        } else null
        val limitServiceGetter = if (limitServiceClass != null && coreClass != null) {
            KavaReflector.declaredMethods(coreClass).singleOrNull { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == limitServiceClass
            }
        } else null
        val limitMethod = limitServiceClass?.let { clazz ->
            KavaReflector.declaredMethods(clazz).singleOrNull { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.parameterTypes.isEmpty() &&
                    method.returnType == Int::class.javaPrimitiveType
            }
        }
        val imageBuilder = storageClass?.let { clazz ->
            KavaReflector.declaredMethods(clazz).singleOrNull { method ->
                val types = method.parameterTypes
                method.returnType == infoClass && types.size == 4 &&
                    types[0] == String::class.java && types[1] == String::class.java &&
                    types[2] == Int::class.javaPrimitiveType && types[3] == Int::class.javaPrimitiveType
            }
        }
        val sceneEndMethod = KavaReflector.declaredMethods(sceneClass).singleOrNull { method ->
            method.name == "onGYNetEnd" && method.returnType == Void.TYPE && method.parameterTypes.size == 6
        } ?: return null
        val statusField = KavaReflector.findFieldRecursive(infoClass, "e")
            ?.takeIf { it.type == Int::class.javaPrimitiveType } ?: return null
        val contactField = when {
            KavaReflector.findFieldRecursive(infoClass, "j")?.type == String::class.java -> "j"
            KavaReflector.findFieldRecursive(infoClass, "m")?.type == String::class.java -> "m"
            else -> return null
        }
        return MassSendRuntime(
            sceneConstructor = sceneConstructor,
            infoConstructor = infoConstructor,
            sceneEndMethod = sceneEndMethod,
            statusField = statusField,
            storage = storage,
            imageBuilder = imageBuilder,
            contactField = contactField,
            limitServiceGetter = limitServiceGetter,
            limitMethod = limitMethod
        )
    }

    private fun isSceneConstructor(constructor: Constructor<*>): Boolean {
        val types = constructor.parameterTypes
        return types.size == 3 &&
            types[1] == Boolean::class.javaPrimitiveType &&
            types[2] == Int::class.javaPrimitiveType &&
            types[0].name.substringBeforeLast('.', "") == constructor.declaringClass.name.substringBeforeLast('.', "")
    }

    private fun buildEmojiBytes(
        runtime: MassSendEmojiRuntime,
        payload: WeChatEmojiApi.MassSendPayload
    ): ByteArray? {
        val value = KavaReflector.newInstance(runtime.payloadConstructor) ?: return null
        val emptyBuffer = KavaReflector.newInstance(runtime.bufferConstructor) ?: return null
        KavaReflector.writeField(runtime.md5Field, value, payload.md5)
        KavaReflector.writeField(runtime.startField, value, 0)
        KavaReflector.writeField(runtime.sizeField, value, payload.size)
        KavaReflector.writeField(runtime.bufferField, value, emptyBuffer)
        KavaReflector.writeField(runtime.typeField, value, payload.type)
        KavaReflector.writeField(runtime.contentField, value, payload.content)
        KavaReflector.writeField(runtime.reservedField, value, 0)
        return KavaReflector.invoke(runtime.toByteArrayMethod, value) as? ByteArray
    }

    private fun write(target: Any, name: String, value: Any?) {
        KavaReflector.findFieldRecursive(target.javaClass, name)?.let { field ->
            KavaReflector.writeField(field, target, value)
        }
    }

    private data class MassSendRuntime(
        val sceneConstructor: Constructor<*>,
        val infoConstructor: Constructor<*>,
        val sceneEndMethod: Method,
        val statusField: Field,
        val storage: Any?,
        val imageBuilder: Method?,
        val contactField: String,
        val limitServiceGetter: Method?,
        val limitMethod: Method?,
        val emojiRuntime: MassSendEmojiRuntime? = null,
        val nativeSubmitRuntime: NativeSubmitRuntime? = null
    )

    private data class NativeSubmitRuntime(
        val dispatcherGetter: Method,
        val submitMethod: Method
    )

    private data class MassSendEmojiRuntime(
        val payloadConstructor: Constructor<*>,
        val toByteArrayMethod: Method,
        val md5Field: Field,
        val startField: Field,
        val sizeField: Field,
        val bufferField: Field,
        val bufferConstructor: Constructor<*>,
        val typeField: Field,
        val contentField: Field,
        val reservedField: Field
    ) {
        fun encode(): String = listOf(
            payloadConstructor.declaringClass.name,
            md5Field.name,
            startField.name,
            sizeField.name,
            bufferField.name,
            typeField.name,
            contentField.name,
            reservedField.name
        ).joinToString("\n")
    }

    private data class PreparedSnapshot(
        val snapshot: SelectedMessageSnapshot,
        val emojiPayload: WeChatEmojiApi.MassSendPayload?,
        val voicePayload: WeChatVoiceApi.MassSendPayload?
    )

    private data class SendJob(
        val snapshot: SelectedMessageSnapshot,
        val targets: List<String>,
        val emojiPayload: WeChatEmojiApi.MassSendPayload?,
        val voicePayload: WeChatVoiceApi.MassSendPayload?,
        val targetBatchIndex: Int
    )

    private data class SendBatch(
        val id: String,
        val jobs: List<SendJob>,
        val total: Int,
        val onComplete: ((Int, Int, Boolean) -> Unit)?,
        val targetBatchDelayMillis: Long,
        val itemDelayMillis: Long,
        var index: Int = 0,
        var success: Int = 0
    )

    companion object {
        private const val PREFS_NAME = "Hchat_selected_message_method_cache"
        private const val CACHE_ANCHOR = "official_mass_send_anchor_v1"
        private const val CACHE_EMOJI_CALLBACK = "official_mass_send_emoji_callback_v1"
        private const val CACHE_EMOJI_SPEC = "official_mass_send_emoji_spec_v1"
        private const val CACHE_NATIVE_DISPATCHER_GETTER = "official_mass_send_dispatcher_getter_v1"
        private const val CACHE_NATIVE_SUBMIT = "official_mass_send_submit_v1"
        private const val DEFAULT_CONTACT_LIMIT = 500
        private const val NEXT_SEND_DELAY_MS = 500L
        private const val IMAGE_ORIGINAL_MODE = 1
        private const val MASS_SEND_STATUS_DONE = 199
        private const val MASS_SEND_VIDEO_RESERVED = 2
    }
}
