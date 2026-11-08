package h.Hchat.hooks.items.autoreply

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.utils.HLog
import me.yun.silk.AacCodec
import me.yun.silk.SilkCodec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.random.Random

object AutoReplyRuntime {
    private const val TAG = "[Hchat:AutoReply]"
    private const val AT_ALL_WXID = "notify@all"
    private const val XIAOZHI_INPUT_SAMPLE_RATE = 16000
    private const val XIAOZHI_INPUT_CHANNELS = 1
    private const val XIAOZHI_INPUT_FRAME_DURATION_MS = 60
    private const val XIAOZHI_WAKE_TEXT_MAX_LENGTH = 10
    private const val XIAOZHI_VOICE_STOP_GRACE_MS = 800L
    private const val XIAOZHI_SOCKET_IDLE_MS = 90_000L
    private const val XIAOZHI_OPUS_PCM_SAMPLE_RATE = 48000
    private const val XIAOZHI_SILK_SAMPLE_RATE = 24000
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Hchat-AutoReply").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "Hchat-AutoReply-Timer").apply { isDaemon = true }
    }
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val replyCounts = ConcurrentHashMap<String, Int>()
    private val lastReplyTimes = ConcurrentHashMap<String, Long>()
    private val aiHistories = ConcurrentHashMap<String, MutableList<AiMessage>>()
    private val xiaozhiSessions = ConcurrentHashMap<String, String>()
    private val xiaozhiSockets = ConcurrentHashMap<String, XiaozhiSocketSession>()
    private val xiaozhiMcpBridgeRef = AtomicReference<XiaozhiMcpBridge?>()
    private val xiaozhiMcpToolCallTimes = ConcurrentHashMap<String, Long>()
    private const val XIAOZHI_MCP_SESSION_TTL_MS = 10 * 60 * 1000L
    private const val XIAOZHI_MCP_RECONNECT_MS = 30_000L
    private const val XIAOZHI_MAX_VOICE_SEGMENTS_BEFORE_MERGE = 3
    private val OGG_CRC_TABLE = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) {
            value = if ((value and 0x80000000.toInt()) != 0) {
                (value shl 1) xor 0x04C11DB7
            } else {
                value shl 1
            }
        }
        value
    }

    fun clearAiHistories() {
        aiHistories.clear()
        xiaozhiSessions.clear()
        xiaozhiSockets.values.forEach { it.close("context cleared") }
        xiaozhiSockets.clear()
    }

    fun xiaozhiDeviceUuid(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "default_android_id"
        return UUID.nameUUIDFromBytes(androidId.toByteArray()).toString()
    }

    fun xiaozhiDeviceMac(context: Context): String {
        return runCatching {
            val uuid = UUID.fromString(xiaozhiDeviceUuid(context))
            val bytes = ByteArray(16)
            val most = uuid.mostSignificantBits
            val least = uuid.leastSignificantBits
            for (i in 0 until 8) bytes[i] = ((most ushr (8 * (7 - i))) and 0xFF).toByte()
            for (i in 8 until 16) bytes[i] = ((least ushr (8 * (15 - i))) and 0xFF).toByte()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            hash.take(6).joinToString(":") { "%02x".format(Locale.US, it.toInt() and 0xFF) }
        }.getOrElse {
            HLog.e("$TAG 生成小智设备 MAC 失败: ${it.message}", it)
            "00:00:00:00:00:00"
        }
    }

    fun bindXiaozhiDevice(context: Context, config: AutoReplyXiaozhiConfig): String {
        val otaUrl = config.otaUrl.trim()
        if (otaUrl.isBlank()) return "请先填写 OTA 地址"
        val uuid = xiaozhiDeviceUuid(context)
        val mac = xiaozhiDeviceMac(context)
        val body = JSONObject().apply {
            put("application", JSONObject().apply {
                put("name", "xiaozhi-web-test")
                put("version", "1.0.0")
                put("idf_version", "1.0.0")
            })
            put("ota", JSONObject().apply {
                put("label", "xiaozhi-web")
            })
            put("mac_address", mac)
        }
        return runCatching {
            val request = Request.Builder()
                .url(otaUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("client-id", uuid)
                .addHeader("device-id", mac)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful || text.isBlank()) {
                    return@use "请求失败: HTTP ${response.code}"
                }
                val obj = JSONObject(text)
                when {
                    obj.has("activation") -> {
                        val activation = obj.optJSONObject("activation")
                        val code = activation?.optString("code").orEmpty()
                        "验证码: $code\n控制台: ${config.consoleUrl.ifBlank { AutoReplySettings.DEFAULT_XIAOZHI_CONSOLE_URL }}"
                    }
                    obj.has("firmware") -> {
                        val version = obj.optJSONObject("firmware")?.optString("version").orEmpty()
                        if (version.isBlank()) "设备已绑定" else "设备已绑定\n固件版本: $version"
                    }
                    obj.has("error") -> "出现错误: ${obj.optString("error")}"
                    else -> text.take(500)
                }
            }
        }.getOrElse {
            HLog.e("$TAG 小智设备绑定失败: ${it.message}", it)
            "绑定失败: ${it.message}"
        }
    }

    fun fetchAiModels(apiBaseUrl: String, apiKey: String): List<String> {
        val urls = candidateModelUrls(apiBaseUrl)
        for (url in urls) {
            val models = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .apply {
                        if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey")
                    }
                    .get()
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful || text.isBlank()) return@use emptyList<String>()
                    parseModelList(text)
                }
            }.getOrElse {
                HLog.e("$TAG 拉取模型列表失败: ${it.message}", it)
                emptyList()
            }
            if (models.isNotEmpty()) return models
        }
        return emptyList()
    }

    fun testAiConnectivity(config: AutoReplyAiConfig): String {
        if (config.apiKey.isBlank() || config.apiBaseUrl.isBlank() || config.model.isBlank()) {
            return "请先填写 API Key、API 地址和模型"
        }
        val history = listOf(
            AiMessage("system", config.systemPrompt.ifBlank { AutoReplySettings.DEFAULT_AI_SYSTEM_PROMPT }),
            AiMessage("user", "请只回复 OK")
        )
        val nonStreamOk = callAiOnce(config.copy(stream = false), history).orEmpty().isNotBlank()
        val streamOk = callAiStream(config.copy(stream = true), history).orEmpty().isNotBlank()
        return when {
            nonStreamOk && streamOk -> "流式、非流式都可用"
            nonStreamOk -> "非流式可用，流式不可用"
            streamOk -> "流式可用，非流式不可用"
            else -> "流式、非流式都不可用"
        }
    }

    fun handleMessage(context: Context, message: WeChatMessageObserveApi.ObservedMessage) {
        val settings = AutoReplySettings(context)
        if (!settings.isEnabled()) return
        if (shouldSkipMessage(message)) return
        val talker = message.talker.ifBlank { message.getTalker() }
        if (settings.shouldExcludeTalker(talker)) return
        executor.execute {
            runCatching {
                handleFriendAccepted(context, settings, message)
                handleAutoReply(context, settings, message)
            }.onFailure {
                HLog.e("$TAG 处理消息失败: ${it.message}", it)
            }
        }
    }

    fun handleNewFriend(context: Context, wxid: String, ticket: String, scene: Int) {
        val settings = AutoReplySettings(context)
        if (!settings.getBoolean(AutoReplySettings.KEY_AUTO_ACCEPT_ENABLE, false)) return
        executor.execute {
            runCatching {
                val verifyWxid = h.Hchat.hooks.items.script.ScriptNewFriendHook.resolveVerifyUsername(wxid, ticket, scene)
                WeChatApis.contact().verifyUser()?.verifyUser(verifyWxid, ticket, scene)
                modifyLabelIfNeeded(
                    wxid,
                    settings.getBoolean(AutoReplySettings.KEY_AUTO_ACCEPT_TAG_ENABLE, false),
                    settings.getString(AutoReplySettings.KEY_AUTO_ACCEPT_TAG_NAME, "")
                )
                applyFriendLabels(settings, wxid, autoAcceptAutomationKeys)
                applyFriendRemark(settings, wxid, autoAcceptAutomationKeys)
                val delay = settings.getLong(AutoReplySettings.KEY_AUTO_ACCEPT_DELAY_MS, 2000L).coerceAtLeast(0L)
                executeSteps(context, wxid, settings.autoAcceptSteps(), MessageContext(talker = wxid, sender = wxid), delay)
            }.onFailure {
                HLog.e("$TAG 好友申请处理失败: ${it.message}", it)
            }
        }
    }

    private fun handleFriendAccepted(
        context: Context,
        settings: AutoReplySettings,
        message: WeChatMessageObserveApi.ObservedMessage
    ) {
        if (!settings.getBoolean(AutoReplySettings.KEY_GREET_ACCEPTED_ENABLE, false)) return
        val talker = message.talker.ifBlank { message.getTalker() }
        val content = plainContent(message).trim()
        if (talker.isBlank() || message.group || talker.endsWith("@chatroom")) return
        if (content != AutoReplySettings.FRIEND_ACCEPTED_KEYWORD) return
        modifyLabelIfNeeded(
            talker,
            settings.getBoolean(AutoReplySettings.KEY_GREET_ACCEPTED_TAG_ENABLE, false),
            settings.getString(AutoReplySettings.KEY_GREET_ACCEPTED_TAG_NAME, "")
        )
        applyFriendLabels(settings, talker, greetAcceptedAutomationKeys)
        applyFriendRemark(settings, talker, greetAcceptedAutomationKeys)
        val delay = settings.getLong(AutoReplySettings.KEY_GREET_ACCEPTED_DELAY_MS, 2000L).coerceAtLeast(0L)
        executeSteps(context, talker, settings.greetAcceptedSteps(), MessageContext(talker = talker, sender = talker, content = content), delay)
    }

    private fun handleAutoReply(
        context: Context,
        settings: AutoReplySettings,
        message: WeChatMessageObserveApi.ObservedMessage
    ) {
        val talker = message.talker.ifBlank { message.getTalker() }
        if (talker.isBlank()) return
        val group = message.group || message.isGroupChat() || talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
        val sender = resolveSender(message, talker, group)
        val content = plainContent(message)
        val isPat = message.isPat()
        val isText = message.isText()
        if (message.isSystem() && !isPat) return
        if (isText && content.isBlank() && !isPat) return

        val ctx = MessageContext(
            talker = talker,
            sender = sender,
            content = content,
            msgId = message.getMsgId(),
            group = group,
            atMe = message.isAtMe(),
            atAll = message.isNotifyAll(),
            patMe = isPat && message.getPatMsg()?.pattedUser == WeChatApis.contact().account()?.selfWxId()
        )
        for (rule in settings.rules()) {
            if (!rule.enabled || rule.steps.isEmpty()) continue
            if (!inTimeRange(rule.startTime, rule.endTime)) continue
            if (!targetMatches(rule, ctx, message)) continue
            if (!triggerMatches(rule, ctx, isPat)) continue
            if (!keywordMatches(rule, content, isText, isPat)) continue
            if (isReplyCoolingDown(rule, ctx.talker)) continue
            if (!countAndCheck(rule, ctx)) continue
            if (executeSteps(context, talker, rule.steps, ctx, 0L, rule.replyAsQuote)) {
                markReplyCooldown(rule, ctx.talker)
            }
        }
    }

    private fun executeSteps(
        context: Context,
        target: String,
        steps: List<AutoReplyStep>,
        message: MessageContext,
        initialDelayMs: Long = 0L,
        replyAsQuote: Boolean = false
    ): Boolean {
        if (steps.isEmpty()) return false
        if (initialDelayMs > 0) sleep(initialDelayMs)
        var sent = false
        for ((index, step) in steps.withIndex()) {
            val delay = step.delayMs + if (step.randomDelay) Random.nextLong(0L, 2001L) else 0L
            if (delay > 0) sleep(delay)
            sent = sendStep(context, target, step, message, replyAsQuote) || sent
            if (index < steps.lastIndex && step.delayMs <= 0L) sleep(300L)
        }
        return sent
    }

    private fun sendStep(
        context: Context,
        target: String,
        step: AutoReplyStep,
        message: MessageContext,
        replyAsQuote: Boolean
    ): Boolean {
        val content = format(step.content, message)
        val messageApi = WeChatApis.message().sender()
        val mediaApi = WeChatApis.media()
        return runCatching {
            when (step.mode) {
                AutoReplySettings.REPLY_TEXT -> {
                    val parts = splitReplies(content)
                    var sent = false
                    parts.forEachIndexed { index, text ->
                        val currentSent = if (replyAsQuote && message.msgId > 0 && !text.contains("[AtWx=")) {
                            messageApi?.sendQuote(target, message.msgId, text) == true
                        } else {
                            sendText(target, text)
                        }
                        sent = currentSent || sent
                        if (index < parts.lastIndex) sleep(300L)
                    }
                    sent
                }
                AutoReplySettings.REPLY_IMAGE -> sendPaths(content) { mediaApi?.sendImage(target, it) == true }
                AutoReplySettings.REPLY_VOICE -> sendPaths(content) { mediaApi?.sendVoice(target, it) == true }
                AutoReplySettings.REPLY_VOICE_RANDOM_FOLDER -> {
                    val file = randomAudioFile(content) ?: return@runCatching false
                    mediaApi?.sendVoice(target, file) == true
                }
                AutoReplySettings.REPLY_EMOJI -> sendPaths(content) { mediaApi?.sendEmoji(target, it) == true }
                AutoReplySettings.REPLY_VIDEO -> sendPaths(content) { mediaApi?.videos()?.send(target, it) == true }
                AutoReplySettings.REPLY_FILE -> sendPaths(content) { path ->
                    mediaApi?.sendFile(target, path, File(path).name) == true
                }
                AutoReplySettings.REPLY_FAVORITE -> sendFavorites(content) {
                    mediaApi?.favorites()?.send(target, it) == true
                }
                AutoReplySettings.REPLY_CARD -> splitMulti(content).any { messageApi?.sendShareCard(target, it) == true }
                AutoReplySettings.REPLY_INVITE_GROUP -> {
                    val member = if (message.group) message.sender else target
                    splitMulti(content).any { WeChatApis.contact().chatrooms()?.inviteChatroomMember(it, member) == true }
                }
                AutoReplySettings.REPLY_XML -> messageApi?.sendXml(target, ensureXml(content)) == true
                AutoReplySettings.REPLY_ZHILIA_AI -> sendZhiliaAiReply(context, target, message, replyAsQuote)
                AutoReplySettings.REPLY_XIAOZHI_AI -> sendXiaozhiAiReply(context, target, message, replyAsQuote)
                AutoReplySettings.REPLY_XIAOZHI_VOICE -> sendXiaozhiVoiceReply(context, target, message)
                else -> false
            }
        }.onFailure {
            HLog.e("$TAG 发送回复失败: ${it.message}", it)
        }.getOrDefault(false)
    }

    private fun sendZhiliaAiReply(
        context: Context,
        target: String,
        message: MessageContext,
        replyAsQuote: Boolean
    ): Boolean {
        val config = AutoReplySettings(context).aiConfig()
        if (config.apiKey.isBlank()) {
            showToast(context, "请先配置自动回复 AI Key")
            return false
        }
        val question = aiQuestion(message)
        if (question.isBlank()) return false
        val answer = requestAi(target, config, question)
        if (answer.isBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        return if (replyAsQuote && message.msgId > 0L) {
            sender.sendQuote(target, message.msgId, answer)
        } else {
            sendText(target, answer)
        }
    }

    private fun sendXiaozhiAiReply(
        context: Context,
        target: String,
        message: MessageContext,
        replyAsQuote: Boolean
    ): Boolean {
        val question = aiQuestion(message)
        if (question.isBlank()) return false
        val reply = requestXiaozhiReply(context, target, question)
        val answer = reply.text
        if (!reply.mergeVoiceSegments && reply.voiceSegments.isNotEmpty()) {
            val media = WeChatApis.media()
            var sentAny = false
            try {
                reply.voiceSegments.forEachIndexed { index, segment ->
                    val path = segment.file.absolutePath
                    if (isXiaozhiVoiceSendable(path) || path.endsWith(".silk", ignoreCase = true)) {
                        val sent = media?.sendVoice(target, path, segment.durationMs.coerceAtLeast(1000)) == true
                        if (sent) sentAny = true
                        if (index < reply.voiceSegments.lastIndex) sleep(300L)
                    }
                }
            } finally {
                reply.voiceSegments.forEach { segment -> runCatching { segment.file.delete() } }
            }
            if (sentAny) return true
        }
        if (reply.voicePath.isNotBlank()) {
            if (isXiaozhiVoiceSendable(reply.voicePath) || reply.voicePath.endsWith(".silk", ignoreCase = true)) {
                val sent = WeChatApis.media()?.sendVoice(target, reply.voicePath, reply.durationMs.coerceAtLeast(1000)) == true
                runCatching { File(reply.voicePath).delete() }
                if (sent) return true
            }
        }
        if (answer.isBlank()) {
            return false
        }
        val sender = WeChatApis.message().sender() ?: return false
        return if (replyAsQuote && message.msgId > 0L) {
            sender.sendQuote(target, message.msgId, answer)
        } else {
            sendText(target, answer)
        }
    }

    private fun sendXiaozhiVoiceReply(
        context: Context,
        target: String,
        message: MessageContext
    ): Boolean {
        val question = aiQuestion(message)
        if (question.isBlank()) return false
        val reply = requestXiaozhiReply(context, target, question, forceVoice = true)
        val voiceSegments = reply.voiceSegments.ifEmpty {
            if (reply.voicePath.isNotBlank()) {
                listOf(XiaozhiVoiceSegment(File(reply.voicePath), reply.durationMs.coerceAtLeast(1000)))
            } else {
                synthesizeXiaozhiReplyVoice(context, reply.text)?.let { listOf(it) }.orEmpty()
            }
        }
        if (voiceSegments.isEmpty()) return false
        var sent = false
        return try {
            val media = WeChatApis.media() ?: return false
            voiceSegments.forEachIndexed { index, segment ->
                if (media.sendVoice(target, segment.file.absolutePath, segment.durationMs.coerceAtLeast(1000))) {
                    sent = true
                }
                if (index < voiceSegments.lastIndex) sleep(300L)
            }
            sent
        } finally {
            voiceSegments.forEach { segment ->
                runCatching { segment.file.delete() }
            }
        }
    }

    private fun aiQuestion(message: MessageContext): String =
        message.content.replace(Regex("""@[^\s]+\s+"""), "").trim()

    private fun isXiaozhiVoiceSendable(path: String): Boolean {
        val file = File(path)
        if (!file.isFile || file.length() < 512L) return false
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            !duration.isNullOrBlank() && (duration.toLongOrNull() ?: 0L) > 0L
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun requestAi(talker: String, config: AutoReplyAiConfig, question: String): String {
        val history = aiHistories.getOrPut(talker) {
            mutableListOf<AiMessage>().also {
                if (config.systemPrompt.isNotBlank()) it += AiMessage("system", config.systemPrompt)
            }
        }
        synchronized(history) {
            if (history.firstOrNull()?.role == "system" && history.first().content != config.systemPrompt) {
                if (config.systemPrompt.isBlank()) history.removeAt(0) else history[0] = AiMessage("system", config.systemPrompt)
            } else if (history.none { it.role == "system" } && config.systemPrompt.isNotBlank()) {
                history.add(0, AiMessage("system", config.systemPrompt))
            }
            history += AiMessage("user", question)
            trimHistory(history, config.contextLimit)
            val answer = if (config.stream) {
                callAiStream(config, history) ?: callAiOnce(config, history)
            } else {
                callAiOnce(config, history) ?: callAiStream(config, history)
            }.orEmpty().cleanAiText()
            if (answer.isNotBlank()) {
                history += AiMessage("assistant", answer)
                trimHistory(history, config.contextLimit)
            }
            return answer
        }
    }

    private fun requestXiaozhiReply(
        context: Context,
        talker: String,
        question: String,
        forceVoice: Boolean = false
    ): XiaozhiReply {
        val config = AutoReplySettings(context).xiaozhiConfig()
        val collectVoice = forceVoice
        val serveUrl = config.serveUrl.trim()
        if (serveUrl.isBlank()) {
            showToast(context, "请先配置小智 WebSocket 地址")
            return XiaozhiReply()
        }
        val requestStartAt = System.currentTimeMillis()
        val sessionKey = xiaozhiSessionKey(config, talker)
        val sessionId = xiaozhiSessionId(sessionKey, talker)
        val actualQuestion = xiaozhiToolAwareQuestion(question, config)
        val mergeVoiceSegments = shouldMergeXiaozhiVoiceSegments(question, config)
        val mcpEnabledForRequest = config.mcpBridgeEnabled
        val mcpBridgeForRequest = if (mcpEnabledForRequest) {
            ensureXiaozhiMcpBridge(config, sessionId, talker)?.also {
                it.waitReady(config.mcpReadySeconds.coerceIn(1, 30) * 1000L)
            }
        } else {
            null
        }
        val answerRef = AtomicReference("")
        val answerBuilder = StringBuilder()
        val audioFrames = mutableListOf<ByteArray>()
        val audioSegments = mutableListOf<List<ByteArray>>()
        val latch = CountDownLatch(1)
        val socketSessionRef = AtomicReference<XiaozhiSocketSession?>()
        val finished = AtomicBoolean(false)
        val receivingAudio = AtomicBoolean(false)
        val ttsStarted = AtomicBoolean(false)
        val fallbackRef = AtomicReference<ScheduledFuture<*>?>()
        val voiceStopRef = AtomicReference<ScheduledFuture<*>?>()
        val requestRef = AtomicReference<XiaozhiSocketRequest?>()
        val textCompletedEarly = AtomicBoolean(false)
        var sampleRate = 24000
        var channels = 1
        var frameDurationMs = 60
        fun audioBytes(): Long {
            val current = synchronized(audioFrames) { audioFrames.sumOf { it.size.toLong() } }
            val segments = synchronized(audioSegments) { audioSegments.flatten().sumOf { it.size.toLong() } }
            return current + segments
        }
        fun appendAudio(bytes: ByteArray) {
            if (!collectVoice || bytes.isEmpty()) return
            synchronized(audioFrames) {
                audioFrames += bytes
            }
        }
        fun flushAudioSegment() {
            if (!collectVoice) return
            synchronized(audioFrames) {
                if (audioFrames.isEmpty()) return
                synchronized(audioSegments) {
                    audioSegments += audioFrames.toList()
                }
                audioFrames.clear()
            }
        }
        fun currentAudioSegments(): List<List<ByteArray>> {
            flushAudioSegment()
            return synchronized(audioSegments) { audioSegments.toList() }
        }
        fun finish(closeSocket: Boolean = false, completeSocket: Boolean = true) {
            if (finished.compareAndSet(false, true)) {
                fallbackRef.getAndSet(null)?.cancel(false)
                voiceStopRef.getAndSet(null)?.cancel(false)
                latch.countDown()
                val request = requestRef.get()
                if (closeSocket) {
                    socketSessionRef.get()?.close("reply failed")
                } else if (completeSocket && request != null) {
                    socketSessionRef.get()?.complete(request)
                } else {
                    socketSessionRef.get()?.touch()
                }
            }
        }
        lateinit var socketRequest: XiaozhiSocketRequest
        fun applyAudioParams(audio: JSONObject?) {
            audio?.let {
                sampleRate = it.optInt("sample_rate", sampleRate).coerceAtLeast(8000)
                channels = it.optInt("channels", channels).coerceAtLeast(1)
                frameDurationMs = it.optInt("frame_duration", frameDurationMs).coerceAtLeast(20)
            }
        }
        socketRequest = XiaozhiSocketRequest(
            onReady = { webSocket, audio ->
                mcpBridgeForRequest?.touch()
                applyAudioParams(audio)
                sendXiaozhiQuestionInput(
                    context,
                    webSocket,
                    sessionId,
                    actualQuestion,
                    config,
                    ttsStarted,
                    finished,
                    fallbackRef
                )
            },
            onText = { webSocket, obj ->
                mcpBridgeForRequest?.touch()
                if (obj.optString("type") == "alert") {
                    finish(closeSocket = true)
                } else {
                    when (obj.optString("type")) {
                        "mcp" -> {
                            val payload = obj.optJSONObject("payload")
                            val bridge = xiaozhiMcpBridgeRef.get()
                            val responsePayload = bridge?.handleMcpPayload(payload, "main-ws")
                            if (responsePayload != null) {
                                val replySessionId = obj.optString("session_id").ifBlank { sessionId }
                                webSocket.send(JSONObject().apply {
                                    put("session_id", replySessionId)
                                    put("type", "mcp")
                                    put("payload", responsePayload)
                                }.toString())
                            }
                        }
                        "tts" -> {
                            val state = obj.optString("state")
                            when (state) {
                                "start" -> {
                                    ttsStarted.set(true)
                                    fallbackRef.getAndSet(null)?.cancel(false)
                                    voiceStopRef.getAndSet(null)?.cancel(false)
                                    receivingAudio.set(true)
                                }
                                "sentence_start" -> {
                                    ttsStarted.set(true)
                                    fallbackRef.getAndSet(null)?.cancel(false)
                                    voiceStopRef.getAndSet(null)?.cancel(false)
                                    flushAudioSegment()
                                    obj.optString("text").cleanAiText().takeIf { it.isNotBlank() }?.let {
                                        synchronized(answerBuilder) {
                                            if (answerBuilder.isNotEmpty()) answerBuilder.append('\n')
                                            answerBuilder.append(it)
                                            answerRef.set(answerBuilder.toString())
                                        }
                                        if (!collectVoice) {
                                            textCompletedEarly.set(true)
                                            finish(completeSocket = false)
                                            scheduler.schedule({
                                                if (textCompletedEarly.get()) {
                                                    socketSessionRef.get()?.complete(socketRequest)
                                                }
                                            }, 10, TimeUnit.SECONDS)
                                        }
                                    }
                                }
                                "sentence_end" -> {
                                    flushAudioSegment()
                                }
                                "stop" -> {
                                    receivingAudio.set(false)
                                    flushAudioSegment()
                                    if (collectVoice) {
                                        voiceStopRef.getAndSet(null)?.cancel(false)
                                        voiceStopRef.set(scheduler.schedule({
                                            finish()
                                        }, XIAOZHI_VOICE_STOP_GRACE_MS, TimeUnit.MILLISECONDS))
                                    } else {
                                        if (textCompletedEarly.get() && finished.get()) {
                                            socketSessionRef.get()?.complete(socketRequest)
                                        } else {
                                            finish()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            onBytes = { bytes ->
                mcpBridgeForRequest?.touch()
                if (receivingAudio.get()) appendAudio(bytes.toByteArray())
            },
            onFailure = { t, response ->
                val responseText = response?.let { " code=${it.code} msg=${it.message}" }.orEmpty()
                HLog.e("$TAG 小智AI WebSocket 失败: ${t.javaClass.simpleName} ${t.message}$responseText", t)
                finish(closeSocket = true)
            },
            onClosed = {
                if (!finished.get()) finish(closeSocket = true)
            },
            onCancel = {
                finish()
            }
        )
        requestRef.set(socketRequest)
        return runCatching {
            val socketSession = xiaozhiSocketSession(context, sessionKey, serveUrl, config, mcpEnabledForRequest)
            socketSessionRef.set(socketSession)
            socketSession.submit(socketRequest)
            if (!latch.await(60, TimeUnit.SECONDS)) {
                finish(closeSocket = true)
            }
            if (!textCompletedEarly.get()) {
                socketSession.complete(socketRequest)
            }
            val rawSegments = if (collectVoice) currentAudioSegments() else emptyList()
            var segmentConvertFailed = false
            val voiceSegments = rawSegments.mapNotNull { frames ->
                val file = writeXiaozhiVoiceFile(context, frames, sampleRate, channels, frameDurationMs)
                if (file == null) {
                    segmentConvertFailed = true
                    null
                } else {
                    XiaozhiVoiceSegment(file, frames.size * frameDurationMs)
                }
            }
            val fallbackFrames = rawSegments.flatten()
            val mergeBySegmentCount = voiceSegments.size >= XIAOZHI_MAX_VOICE_SEGMENTS_BEFORE_MERGE
            val finalMergeVoiceSegments = mergeVoiceSegments || mergeBySegmentCount || segmentConvertFailed
            val file = if (collectVoice && (finalMergeVoiceSegments || voiceSegments.isEmpty() || segmentConvertFailed) && fallbackFrames.isNotEmpty()) {
                if (segmentConvertFailed) voiceSegments.forEach { segment -> runCatching { segment.file.delete() } }
                writeXiaozhiVoiceFile(context, fallbackFrames, sampleRate, channels, frameDurationMs)
            } else {
                null
            }
            if (finalMergeVoiceSegments && file != null) {
                voiceSegments.forEach { segment -> runCatching { segment.file.delete() } }
            }
            val durationMs = fallbackFrames.size * frameDurationMs
            maybeFallbackKugouTool(config, talker, question, requestStartAt)
            XiaozhiReply(
                text = answerRef.get(),
                voicePath = file?.takeIf { it.isFile && it.length() > 0L }?.absolutePath.orEmpty(),
                voiceBytes = file?.length() ?: audioBytes(),
                durationMs = durationMs,
                voiceSegments = if (segmentConvertFailed || finalMergeVoiceSegments) emptyList() else voiceSegments,
                mergeVoiceSegments = finalMergeVoiceSegments,
                mergedSegmentCount = if (finalMergeVoiceSegments) voiceSegments.size else 0
            )
        }.getOrElse {
            HLog.e("$TAG 小智AI 请求失败: ${it.message}", it)
            XiaozhiReply()
        }
    }

    private fun xiaozhiHelloPayload(config: AutoReplyXiaozhiConfig, mcpEnabledForRequest: Boolean): JSONObject {
        return JSONObject().apply {
            put("type", "hello")
            put("version", 1)
            if (mcpEnabledForRequest && config.mcpEndpointUrl.trim().isBlank()) {
                put("features", JSONObject().apply {
                    put("mcp", true)
                })
            }
            put("transport", "websocket")
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", 60)
            })
            putXiaozhiVoiceRole(config)
        }
    }

    private fun xiaozhiSessionKey(config: AutoReplyXiaozhiConfig, talker: String): String {
        return "${config.serveUrl.trim()}|${config.consoleAgentId.trim()}|$talker"
    }

    private fun xiaozhiSessionId(sessionKey: String, talker: String): String {
        return xiaozhiSessions.getOrPut(sessionKey) { newXiaozhiSessionId(talker) }
    }

    private fun newXiaozhiSessionId(talker: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$talker:${System.currentTimeMillis()}:${Random.nextLong()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "hchat_$digest"
    }

    private fun xiaozhiSocketSession(
        context: Context,
        sessionKey: String,
        serveUrl: String,
        config: AutoReplyXiaozhiConfig,
        mcpEnabledForRequest: Boolean
    ): XiaozhiSocketSession {
        val signature = xiaozhiSocketSignature(config, mcpEnabledForRequest)
        while (true) {
            val current = xiaozhiSockets[sessionKey]
            if (current != null && !current.isClosed() && !current.isBusy() && current.signature == signature) {
                current.touch()
                return current
            }
            val request = Request.Builder()
                .url(serveUrl)
                .addHeader("Authorization", "Bearer test-token")
                .addHeader("Device-Id", xiaozhiDeviceMac(context))
                .addHeader("Client-Id", xiaozhiDeviceUuid(context))
                .addHeader("Protocol-Version", "1")
                .build()
            val next = XiaozhiSocketSession(sessionKey, signature, config, mcpEnabledForRequest)
            val installed = if (current == null) {
                xiaozhiSockets.putIfAbsent(sessionKey, next) == null
            } else {
                xiaozhiSockets.replace(sessionKey, current, next)
            }
            if (installed) {
                current?.close("replaced")
                next.connect(request)
                return next
            }
        }
    }

    private fun xiaozhiSocketSignature(config: AutoReplyXiaozhiConfig, mcpEnabledForRequest: Boolean): String {
        return listOf(
            config.serveUrl.trim(),
            config.consoleAgentId.trim(),
            config.voiceRole.trim(),
            mcpEnabledForRequest.toString(),
            config.mcpEndpointUrl.trim().isBlank().toString()
        ).joinToString("|")
    }

    private class XiaozhiSocketRequest(
        val onReady: (WebSocket, JSONObject?) -> Unit,
        val onText: (WebSocket, JSONObject) -> Unit,
        val onBytes: (ByteString) -> Unit,
        val onFailure: (Throwable, Response?) -> Unit,
        val onClosed: () -> Unit,
        val onCancel: () -> Unit
    ) {
        private val started = AtomicBoolean(false)

        fun start(webSocket: WebSocket, audioParams: JSONObject?) {
            if (started.compareAndSet(false, true)) {
                onReady(webSocket, audioParams)
            }
        }
    }

    private class XiaozhiSocketSession(
        private val sessionKey: String,
        val signature: String,
        private val config: AutoReplyXiaozhiConfig,
        private val mcpEnabledForRequest: Boolean
    ) {
        private val socketRef = AtomicReference<WebSocket?>()
        private val activeRequest = AtomicReference<XiaozhiSocketRequest?>()
        private val closed = AtomicBoolean(false)
        private val ready = AtomicBoolean(false)
        private val lastActiveAt = AtomicLong(System.currentTimeMillis())
        private val audioParamsRef = AtomicReference<JSONObject?>()
        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                touch()
                webSocket.send(xiaozhiHelloPayload(config, mcpEnabledForRequest).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                touch()
                runCatching {
                    val obj = JSONObject(text)
                    if (obj.optString("type") == "hello") {
                        val audioParams = obj.optJSONObject("audio_params")
                        audioParamsRef.set(audioParams)
                        ready.set(true)
                        activeRequest.get()?.start(webSocket, audioParams)
                    } else {
                        activeRequest.get()?.onText?.invoke(webSocket, obj)
                    }
                }.onFailure {
                    HLog.e("$TAG 小智AI 数据解析失败: ${it.message}", it)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                touch()
                activeRequest.get()?.onBytes?.invoke(bytes)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                markClosed(webSocket)
                activeRequest.getAndSet(null)?.onFailure?.invoke(t, response)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markClosed(webSocket)
                activeRequest.getAndSet(null)?.onClosed?.invoke()
            }
        }

        fun connect(request: Request) {
            socketRef.set(httpClient.newWebSocket(request, listener))
            scheduleIdleClose()
        }

        fun submit(request: XiaozhiSocketRequest) {
            activeRequest.getAndSet(request)?.onCancel?.invoke()
            touch()
            val socket = socketRef.get()
            if (socket != null && ready.get()) {
                request.start(socket, audioParamsRef.get())
            }
        }

        fun complete(request: XiaozhiSocketRequest) {
            activeRequest.compareAndSet(request, null)
            scheduleIdleClose()
        }

        fun touch() {
            lastActiveAt.set(System.currentTimeMillis())
        }

        fun isClosed(): Boolean {
            return closed.get() || socketRef.get() == null
        }

        fun isBusy(): Boolean {
            return activeRequest.get() != null
        }

        fun scheduleIdleClose() {
            touch()
            scheduler.schedule({
                if (closed.get()) return@schedule
                if (activeRequest.get() != null) return@schedule
                if (System.currentTimeMillis() - lastActiveAt.get() < XIAOZHI_SOCKET_IDLE_MS) return@schedule
                close("idle timeout")
            }, XIAOZHI_SOCKET_IDLE_MS, TimeUnit.MILLISECONDS)
        }

        fun markClosed(webSocket: WebSocket) {
            if (socketRef.compareAndSet(webSocket, null)) {
                closed.set(true)
                ready.set(false)
                xiaozhiSockets.remove(sessionKey, this)
            }
        }

        fun close(reason: String) {
            if (closed.compareAndSet(false, true)) {
                xiaozhiSockets.remove(sessionKey, this)
                socketRef.getAndSet(null)?.close(1000, reason)
            }
        }
    }

    private fun xiaozhiDetectPayload(
        sessionId: String,
        question: String,
        config: AutoReplyXiaozhiConfig
    ): JSONObject {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("mode", "manual")
            put("text", question)
            putXiaozhiVoiceRole(config)
        }
    }

    private fun xiaozhiListenStatePayload(
        sessionId: String,
        state: String,
        config: AutoReplyXiaozhiConfig
    ): JSONObject {
        return JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", state)
            put("mode", "manual")
            putXiaozhiVoiceRole(config)
        }
    }

    private fun JSONObject.putXiaozhiVoiceRole(config: AutoReplyXiaozhiConfig) {
        config.voiceRole.trim().takeIf { it.isNotBlank() }?.let {
            put("tts_voice", it)
            put("voice", it)
        }
    }

    private fun sendXiaozhiQuestionInput(
        context: Context,
        webSocket: WebSocket,
        sessionId: String,
        question: String,
        config: AutoReplyXiaozhiConfig,
        ttsStarted: AtomicBoolean,
        finished: AtomicBoolean,
        fallbackRef: AtomicReference<ScheduledFuture<*>?>
    ) {
        fallbackRef.getAndSet(null)?.cancel(false)
        if (finished.get() || ttsStarted.get()) return
        if (question.length <= XIAOZHI_WAKE_TEXT_MAX_LENGTH) {
            webSocket.send(xiaozhiDetectPayload(sessionId, question, config).toString())
            return
        }
        val future = scheduler.schedule({
            if (finished.get() || ttsStarted.get()) return@schedule
            val frames = synthesizeQuestionOpusFrames(context, question)
            if (frames.isEmpty()) {
                HLog.e("$TAG 小智AI长文本输入音频为空: questionLen=${question.length}")
                return@schedule
            }
            webSocket.send(xiaozhiListenStatePayload(sessionId, "start", config).toString())
            frames.forEach { frame -> webSocket.send(ByteString.of(*frame)) }
            webSocket.send(xiaozhiListenStatePayload(sessionId, "stop", config).toString())
        }, 0L, TimeUnit.MILLISECONDS)
        fallbackRef.set(future)
    }

    private fun synthesizeQuestionOpusFrames(context: Context, question: String): List<ByteArray> {
        val dir = File(context.cacheDir, "hchat_xiaozhi_question").apply { mkdirs() }
        val wavFile = File.createTempFile("question_", ".wav", dir)
        return try {
            val wavOk = synthesizeQuestionWav(context.applicationContext, question, wavFile)
            if (!wavOk) {
                HLog.e("$TAG 小智AI文本转WAV失败: questionLen=${question.length}")
                return emptyList()
            }
            val pcm = wavToMono16kPcm(wavFile)
            if (pcm.isEmpty()) {
                HLog.e("$TAG 小智AI输入PCM为空: wavLen=${wavFile.length()} questionLen=${question.length}")
                return emptyList()
            }
            val frames = encodePcmToOpusFrames(
                pcm,
                XIAOZHI_INPUT_SAMPLE_RATE,
                XIAOZHI_INPUT_CHANNELS,
                XIAOZHI_INPUT_FRAME_DURATION_MS
            )
            frames
        } catch (t: Throwable) {
            HLog.e("$TAG 小智AI文本转音频失败: ${t.message}", t)
            emptyList()
        } finally {
            runCatching { wavFile.delete() }
        }
    }

    private fun synthesizeXiaozhiReplyVoice(context: Context, text: String): XiaozhiVoiceSegment? {
        val content = text.cleanAiText()
        if (content.isBlank()) return null
        val dir = File(context.cacheDir, "hchat_xiaozhi_reply").apply { mkdirs() }
        val wavFile = File.createTempFile("reply_", ".wav", dir)
        val pcmFile = File.createTempFile("reply_", ".pcm", dir)
        val silkFile = File.createTempFile("reply_", ".silk", dir)
        return try {
            if (!synthesizeQuestionWav(context.applicationContext, content, wavFile)) return null
            val pcm = wavToMono16kPcm(wavFile).takeIf { it.isNotEmpty() } ?: return null
            pcmFile.writeBytes(pcm)
            val convertResult = SilkCodec().pcmToSilk(
                pcmFile.absolutePath,
                silkFile.absolutePath,
                XIAOZHI_SILK_SAMPLE_RATE,
                XIAOZHI_INPUT_SAMPLE_RATE,
                XIAOZHI_INPUT_CHANNELS
            )
            if (convertResult == 0 && silkFile.isFile && silkFile.length() > 0L) {
                val durationMs = ((pcm.size / 2) * 1000L / XIAOZHI_INPUT_SAMPLE_RATE)
                    .coerceAtLeast(1000L)
                    .toInt()
                XiaozhiVoiceSegment(silkFile, durationMs)
            } else {
                HLog.e("$TAG 小智语音本地 TTS 转 Silk 失败: $convertResult")
                runCatching { silkFile.delete() }
                null
            }
        } catch (t: Throwable) {
            HLog.e("$TAG 小智语音本地 TTS 失败: ${t.message}", t)
            runCatching { silkFile.delete() }
            null
        } finally {
            runCatching { wavFile.delete() }
            runCatching { pcmFile.delete() }
        }
    }

    private fun synthesizeQuestionWav(context: Context, question: String, outFile: File): Boolean {
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val utteranceId = "hchat_xiaozhi_${System.nanoTime()}"
        var initStatus = TextToSpeech.ERROR
        val tts = TextToSpeech(context) { status ->
            initStatus = status
            ready.countDown()
        }
        return try {
            if (!ready.await(8, TimeUnit.SECONDS)) return false
            if (initStatus != TextToSpeech.SUCCESS) return false
            runCatching { tts.language = Locale.CHINA }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    ok.set(true)
                    done.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    done.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    done.countDown()
                }
            })
            val result = tts.synthesizeToFile(
                question,
                Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) },
                outFile,
                utteranceId
            )
            if (result == TextToSpeech.ERROR) return false
            done.await(20, TimeUnit.SECONDS) && ok.get() && outFile.isFile && outFile.length() > 44L
        } finally {
            runCatching { tts.shutdown() }
        }
    }

    private fun wavToMono16kPcm(file: File): ByteArray {
        val bytes = file.readBytes()
        if (bytes.size <= 44 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return ByteArray(0)
        var offset = 12
        var channels = 1
        var sampleRate = XIAOZHI_INPUT_SAMPLE_RATE
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = le32(bytes, offset + 4)
            val chunkData = offset + 8
            if (chunkData + size > bytes.size) break
            when (id) {
                "fmt " -> {
                    if (size >= 16) {
                        channels = le16(bytes, chunkData + 2).coerceAtLeast(1)
                        sampleRate = le32(bytes, chunkData + 4).coerceAtLeast(8000)
                        bitsPerSample = le16(bytes, chunkData + 14)
                    }
                }
                "data" -> {
                    dataOffset = chunkData
                    dataSize = size
                    break
                }
            }
            offset = chunkData + size + (size and 1)
        }
        if (dataOffset < 0 || dataSize <= 0 || bitsPerSample != 16) return ByteArray(0)
        val samples = dataSize / 2 / channels
        if (samples <= 0) return ByteArray(0)
        val mono = ShortArray(samples)
        var input = dataOffset
        for (i in 0 until samples) {
            var sum = 0
            repeat(channels) {
                sum += le16Signed(bytes, input)
                input += 2
            }
            mono[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val resampled = if (sampleRate == XIAOZHI_INPUT_SAMPLE_RATE) {
            mono
        } else {
            resamplePcm16(mono, sampleRate, XIAOZHI_INPUT_SAMPLE_RATE)
        }
        val out = ByteArray(resampled.size * 2)
        resampled.forEachIndexed { index, sample ->
            val value = sample.toInt()
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun resamplePcm16(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        if (input.isEmpty() || inputRate <= 0 || outputRate <= 0) return ShortArray(0)
        if (inputRate == outputRate) return input
        val outSize = ((input.size.toLong() * outputRate) / inputRate).coerceAtLeast(1L).toInt()
        val out = ShortArray(outSize)
        for (i in out.indices) {
            val source = i.toDouble() * inputRate / outputRate
            val left = source.toInt().coerceIn(0, input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val fraction = source - left
            out[i] = (input[left] * (1.0 - fraction) + input[right] * fraction)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
        return out
    }

    private fun encodePcmToOpusFrames(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        frameDurationMs: Int
    ): List<ByteArray> {
        if (pcm.isEmpty()) return emptyList()
        val frameBytes = sampleRate * frameDurationMs / 1000 * channels * 2
        if (frameBytes <= 0) return emptyList()
        val paddedSize = ((pcm.size + frameBytes - 1) / frameBytes) * frameBytes
        val inputPcm = if (paddedSize == pcm.size) pcm else pcm.copyOf(paddedSize)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val info = MediaCodec.BufferInfo()
        val frames = mutableListOf<ByteArray>()
        var inputOffset = 0
        var sawInputEos = false
        var sawOutputEos = false
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        val size = (inputPcm.size - inputOffset).coerceAtMost(frameBytes)
                        if (size > 0 && inputBuffer != null) {
                            inputBuffer.put(inputPcm, inputOffset, size)
                            val ptsUs = inputOffset / (channels * 2) * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0)
                            inputOffset += size
                        } else {
                            val ptsUs = inputOffset / (channels * 2) * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null &&
                            info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            val frame = ByteArray(info.size)
                            outputBuffer.get(frame)
                            frames += frame
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEos = true
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return frames
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le16Signed(bytes: ByteArray, offset: Int): Int = le16(bytes, offset).toShort().toInt()

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun maybeFallbackKugouTool(
        config: AutoReplyXiaozhiConfig,
        talker: String,
        question: String,
        requestStartAt: Long
    ) {
        if (!config.mcpKugouEnabled || config.mcpKugouPluginId.trim().isBlank()) return
        val functionName = xiaozhiKugouFunctionName(config)
        val keyword = extractMusicKeyword(question)
        if (keyword.isBlank()) return
        val lastCallAt = xiaozhiMcpToolCallTimes[talker] ?: 0L
        if (lastCallAt >= requestStartAt) return
        ScriptPluginRuntime.callPluginFunction(
            config.mcpKugouPluginId.trim(),
            functionName,
            talker,
            keyword
        )
    }

    private fun xiaozhiKugouFunctionName(config: AutoReplyXiaozhiConfig): String =
        config.mcpKugouFunctionName.trim().ifBlank { AutoReplySettings.DEFAULT_XIAOZHI_MCP_KUGOU_FUNCTION }

    private fun xiaozhiToolAwareQuestion(question: String, config: AutoReplyXiaozhiConfig): String {
        val keyword = extractMusicKeyword(question)
        if (keyword.isBlank() || (!config.musicMcpEnabled && !config.mcpKugouEnabled)) return question
        val tools = buildList {
            if (config.musicMcpEnabled) add("官方 Music MCP")
            if (config.mcpKugouEnabled && config.mcpKugouPluginId.trim().isNotBlank()) add("Hchat 点歌工具")
        }.joinToString(" 或 ")
        if (tools.isBlank()) return question
        return "用户想听歌，关键词是「$keyword」。请优先调用${tools}播放或发送音乐卡片；不要先说你不能播放、没有找到或需要用户自己打开播放器。原始请求：$question"
    }

    private fun shouldMergeXiaozhiVoiceSegments(question: String, config: AutoReplyXiaozhiConfig): Boolean {
        return config.musicMcpEnabled && isOfficialXiaozhiSkillQuestion(question)
    }

    private fun isOfficialXiaozhiSkillQuestion(text: String): Boolean {
        val raw = text.trim()
        if (raw.isBlank()) return false
        if (extractMusicKeyword(raw).isNotBlank()) return true
        val skillKeywords = listOf(
            "笑话", "段子", "讲个笑话", "讲笑话",
            "新闻", "资讯", "热点", "头条",
            "天气", "气温", "下雨", "下雪", "空气质量",
            "知识库", "知识", "资料", "百科", "查询", "查一下", "搜索"
        )
        return skillKeywords.any { raw.contains(it) }
    }

    private fun extractMusicKeyword(text: String): String {
        val raw = text.trim()
        if (raw.isBlank()) return ""
        val musicIntent = listOf("点歌", "放首", "放一首", "播放", "听歌", "听首", "来首", "来一首", "音乐")
            .any { raw.contains(it) }
        if (!musicIntent) return ""
        var value = raw
            .replace("帮我", "")
            .replace("给我", "")
            .replace("一下", "")
            .replace("吧", "")
            .replace("可以", "")
            .trim()
        val prefixes = listOf("点歌", "放一首", "放首", "播放一下", "播放", "听一首", "听首", "听歌", "来一首", "来首")
        prefixes.forEach { prefix ->
            if (value.startsWith(prefix)) value = value.removePrefix(prefix).trim()
        }
        val suffixes = listOf("这首歌", "这首", "音乐", "歌曲", "歌")
        suffixes.forEach { suffix ->
            if (value.endsWith(suffix) && value.length > suffix.length) value = value.removeSuffix(suffix).trim()
        }
        return value.take(80)
    }

    private fun writeXiaozhiVoiceFile(
        context: Context,
        frames: List<ByteArray>,
        sampleRate: Int,
        channels: Int,
        frameDurationMs: Int
    ): File? {
        return runCatching {
            val dir = File(context.cacheDir, "hchat_xiaozhi_voice").apply { mkdirs() }
            val oggFile = File.createTempFile("xiaozhi_", ".ogg", dir)
            val pcmFile = File.createTempFile("xiaozhi_", ".pcm", dir)
            val silkFile = File.createTempFile("xiaozhi_", ".silk", dir)
            val oggData = buildOggOpus(frames, sampleRate, channels, frameDurationMs)
            oggFile.writeBytes(oggData)
            val decodeResult = AacCodec.decodeAacFile(oggFile.absolutePath, pcmFile.absolutePath, null)
            if (decodeResult != 0 || !pcmFile.isFile || pcmFile.length() <= 0L) {
                HLog.e("$TAG 小智语音解码失败: code=$decodeResult oggLen=${oggFile.length()}")
                runCatching { pcmFile.delete() }
                runCatching { silkFile.delete() }
                return@runCatching null
            }
            val convertResult = SilkCodec().pcmToSilk(
                pcmFile.absolutePath,
                silkFile.absolutePath,
                XIAOZHI_SILK_SAMPLE_RATE,
                XIAOZHI_OPUS_PCM_SAMPLE_RATE,
                1
            )
            runCatching { oggFile.delete() }
            runCatching { pcmFile.delete() }
            if (convertResult == 0 && silkFile.isFile && silkFile.length() > 0L) {
                silkFile
            } else {
                HLog.e("$TAG 小智语音转Silk失败: code=$convertResult silkLen=${silkFile.length()}")
                runCatching { silkFile.delete() }
                null
            }
        }.getOrElse {
            HLog.e("$TAG 生成小智语音文件失败: ${it.message}", it)
            null
        }
    }

    private fun buildOggOpus(
        frames: List<ByteArray>,
        sampleRate: Int,
        channels: Int,
        frameDurationMs: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val serial = Random.nextInt()
        var sequence = 0
        writeOggPage(
            out = out,
            headerType = 0x02,
            granulePosition = 0L,
            serial = serial,
            sequence = sequence++,
            packets = listOf(opusHead(sampleRate, channels))
        )
        writeOggPage(
            out = out,
            headerType = 0,
            granulePosition = 0L,
            serial = serial,
            sequence = sequence++,
            packets = listOf(opusTags())
        )
        var granule = 0L
        val frameSamples = (48_000L * frameDurationMs / 1000L).coerceAtLeast(960L)
        frames.forEachIndexed { index, frame ->
            granule += frameSamples
            writeOggPage(
                out = out,
                headerType = if (index == frames.lastIndex) 0x04 else 0,
                granulePosition = granule,
                serial = serial,
                sequence = sequence++,
                packets = listOf(frame)
            )
        }
        return out.toByteArray()
    }

    private fun opusHead(sampleRate: Int, channels: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeAscii("OpusHead")
        out.write(1)
        out.write(channels.coerceIn(1, 2))
        out.writeLe16(312)
        out.writeLe32(sampleRate.coerceAtLeast(8000))
        out.writeLe16(0)
        out.write(0)
        return out.toByteArray()
    }

    private fun opusTags(): ByteArray {
        val vendor = "Hchat Xiaozhi".toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.writeAscii("OpusTags")
        out.writeLe32(vendor.size)
        out.write(vendor)
        out.writeLe32(0)
        return out.toByteArray()
    }

    private fun writeOggPage(
        out: ByteArrayOutputStream,
        headerType: Int,
        granulePosition: Long,
        serial: Int,
        sequence: Int,
        packets: List<ByteArray>
    ) {
        val segments = mutableListOf<Int>()
        packets.forEach { packet ->
            var remaining = packet.size
            while (remaining >= 255) {
                segments += 255
                remaining -= 255
            }
            segments += remaining
        }
        val page = ByteArrayOutputStream()
        page.writeAscii("OggS")
        page.write(0)
        page.write(headerType)
        page.writeLe64(granulePosition)
        page.writeLe32(serial)
        page.writeLe32(sequence)
        page.writeLe32(0)
        page.write(segments.size)
        segments.forEach { page.write(it) }
        packets.forEach { page.write(it) }
        val bytes = page.toByteArray()
        val crc = oggCrc(bytes)
        bytes[22] = (crc and 0xFF).toByte()
        bytes[23] = ((crc ushr 8) and 0xFF).toByte()
        bytes[24] = ((crc ushr 16) and 0xFF).toByte()
        bytes[25] = ((crc ushr 24) and 0xFF).toByte()
        out.write(bytes)
    }

    private fun oggCrc(bytes: ByteArray): Int {
        var crc = 0
        bytes.forEach { value ->
            crc = (crc shl 8) xor OGG_CRC_TABLE[((crc ushr 24) xor (value.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe64(value: Long) {
        for (i in 0 until 8) {
            write(((value ushr (8 * i)) and 0xFF).toInt())
        }
    }

    private fun ensureXiaozhiMcpBridge(config: AutoReplyXiaozhiConfig, sessionId: String, target: String): XiaozhiMcpBridge? {
        val endpoint = config.mcpEndpointUrl.trim()
        if (!config.mcpBridgeEnabled) {
            xiaozhiMcpBridgeRef.getAndSet(null)?.close("disabled")
            return null
        }
        val current = xiaozhiMcpBridgeRef.get()
        val kugouEnabled = config.mcpKugouEnabled
        val kugouPluginId = config.mcpKugouPluginId.trim()
        val kugouFunctionName = xiaozhiKugouFunctionName(config)
        val idleCloseMs = config.mcpIdleSeconds.coerceIn(10, 600) * 1000L
        if (current != null &&
            !current.isClosed() &&
            current.endpoint == endpoint &&
            current.kugouEnabled == kugouEnabled &&
            current.kugouPluginId == kugouPluginId &&
            current.kugouFunctionName == kugouFunctionName &&
            current.idleCloseMs == idleCloseMs
        ) {
            current.target.set(target)
            current.registerSession(sessionId, target)
            return current
        }
        current?.close("endpoint changed")
        val bridge = XiaozhiMcpBridge(endpoint, target, kugouEnabled, kugouPluginId, kugouFunctionName, idleCloseMs)
        bridge.registerSession(sessionId, target)
        if (xiaozhiMcpBridgeRef.compareAndSet(current, bridge)) {
            bridge.connect()
            return bridge
        } else {
            bridge.close("replaced")
            return xiaozhiMcpBridgeRef.get()
        }
    }

    private class XiaozhiMcpBridge(
        val endpoint: String,
        initialTarget: String,
        val kugouEnabled: Boolean,
        val kugouPluginId: String,
        val kugouFunctionName: String,
        val idleCloseMs: Long
    ) {
        private data class SessionTarget(
            val target: String,
            val timestamp: Long
        )

        val target = AtomicReference(initialTarget)
        private val socketRef = AtomicReference<WebSocket?>()
        private val sessionTargets = ConcurrentHashMap<String, SessionTarget>()
        private val closed = AtomicBoolean(false)
        private val reconnecting = AtomicBoolean(false)
        private val idleClosing = AtomicBoolean(false)
        private val lastActiveAt = AtomicLong(System.currentTimeMillis())
        private val readyLatch = CountDownLatch(if (endpoint.isBlank()) 0 else 1)

        fun registerSession(sessionId: String, target: String) {
            val now = System.currentTimeMillis()
            lastActiveAt.set(now)
            sessionTargets[sessionId] = SessionTarget(target, now)
            sessionTargets.entries.removeIf { now - it.value.timestamp > XIAOZHI_MCP_SESSION_TTL_MS }
            scheduleIdleClose()
        }

        fun isClosed(): Boolean {
            return closed.get()
        }

        fun waitReady(timeoutMs: Long): Boolean {
            if (endpoint.isBlank()) return true
            return runCatching { readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        }

        fun touch() {
            if (closed.get()) return
            lastActiveAt.set(System.currentTimeMillis())
            scheduleIdleClose()
        }

        fun connect() {
            if (closed.get()) return
            runCatching {
                if (endpoint.isBlank()) {
                    socketRef.getAndSet(null)?.close(1000, "main websocket mcp only")
                    reconnecting.set(false)
                    return
                }
                val request = Request.Builder().url(endpoint).build()
                val socket = AutoReplyRuntime.httpClient.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        lastActiveAt.set(System.currentTimeMillis())
                        scheduleIdleClose()
                        reconnecting.set(false)
                        webSocket.send(mcpClientNotification("notifications/initialized").toString())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        handleMessage(webSocket, text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        socketRef.compareAndSet(webSocket, null)
                        reconnecting.set(false)
                        scheduleReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        socketRef.compareAndSet(webSocket, null)
                        reconnecting.set(false)
                        if (!closed.get()) scheduleReconnect()
                    }
                })
                socketRef.set(socket)
            }.onFailure {
                reconnecting.set(false)
                HLog.e("$TAG 小智MCP桥接连接失败: ${it.message}", it)
            }
        }

        fun close(reason: String) {
            closed.set(true)
            sessionTargets.clear()
            socketRef.getAndSet(null)?.close(1000, reason)
        }

        private fun scheduleIdleClose() {
            if (closed.get()) return
            if (!idleClosing.compareAndSet(false, true)) return
            val bridge = this
            Thread({
                runCatching {
                    while (!closed.get()) {
                        val idleMs = System.currentTimeMillis() - lastActiveAt.get()
                        val waitMs = idleCloseMs - idleMs
                        if (waitMs <= 0L) {
                            if (AutoReplyRuntime.xiaozhiMcpBridgeRef.compareAndSet(bridge, null)) {
                                close("idle timeout")
                            } else {
                                close("idle replaced")
                            }
                            return@runCatching
                        }
                        Thread.sleep(waitMs.coerceAtLeast(1000L))
                    }
                }.also {
                    idleClosing.set(false)
                }.onFailure {
                    idleClosing.set(false)
                    HLog.e("$TAG 小智MCP空闲检查失败: ${it.message}", it)
                }
            }, "Hchat-Xiaozhi-MCP-Idle").apply { isDaemon = true }.start()
        }

        private fun scheduleReconnect() {
            if (closed.get()) return
            if (!reconnecting.compareAndSet(false, true)) return
            Thread({
                runCatching {
                    Thread.sleep(XIAOZHI_MCP_RECONNECT_MS)
                    if (!closed.get()) connect()
                }.onFailure {
                    reconnecting.set(false)
                    HLog.e("$TAG 小智MCP桥接重连失败: ${it.message}", it)
                }
            }, "Hchat-Xiaozhi-MCP-Reconnect").apply { isDaemon = true }.start()
        }

        private fun handleMessage(webSocket: WebSocket, text: String) {
            runCatching {
                val request = JSONObject(text)
                val method = request.optString("method")
                if (method != "ping" && method != "notifications/initialized") {
                    lastActiveAt.set(System.currentTimeMillis())
                }
                scheduleIdleClose()
                val response = handleMcpPayload(request, "endpoint") ?: return
                webSocket.send(response.toString())
            }.onFailure {
                HLog.e("$TAG 小智MCP桥接消息处理失败: ${it.message}", it)
            }
        }

        fun handleMcpPayload(payload: JSONObject?, source: String): JSONObject? {
            if (payload == null) return null
            if (!payload.has("id")) return null
            val id = payload.opt("id")
            val method = payload.optString("method")
            val result = when (method) {
                "initialize" -> mcpInitializeResult()
                "tools/list" -> mcpToolsListResult().also {
                    readyLatch.countDown()
                }
                "tools/call" -> mcpToolsCallResult(payload.optJSONObject("params"))
                "ping" -> JSONObject()
                else -> null
            }
            return if (result == null) {
                mcpError(id, -32601, "Method not found: $method")
            } else {
                mcpResult(id, result)
            }
        }

        private fun mcpInitializeResult(): JSONObject {
            return JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject().apply {
                    put("tools", JSONObject())
                })
                put("serverInfo", JSONObject().apply {
                    put("name", "Hchat")
                    put("version", "1.0")
                })
            }
        }

        private fun mcpToolsListResult(): JSONObject {
            return JSONObject().apply {
                put("tools", JSONArray().apply {
                    put(mcpTool(
                        "hchat_send_text",
                        "当用户要求你通过微信发送、通知、回复文字时，使用此工具把文字发送到当前触发自动回复的微信会话。不能指定其他会话。",
                        JSONObject().apply {
                            put("text", JSONObject().apply {
                                put("type", "string")
                                put("description", "要发送到当前微信会话的文字内容，尽量少于1024字节")
                            })
                            put("session_id", JSONObject().apply {
                                put("type", "string")
                                put("description", "当前 Hchat 会话 session_id；如果你拿得到，请使用用户消息里的原值")
                            })
                        },
                        JSONArray().put("text")
                    ))
                    put(mcpTool(
                        "hchat_share_music",
                        "当你已经获得歌曲播放链接时，使用此工具发送微信音乐卡片到当前触发自动回复的微信会话。不能指定其他会话。",
                        JSONObject().apply {
                            put("title", JSONObject().apply {
                                put("type", "string")
                                put("description", "歌曲标题")
                            })
                            put("description", JSONObject().apply {
                                put("type", "string")
                                put("description", "歌手或描述")
                            })
                            put("musicUrl", JSONObject().apply {
                                put("type", "string")
                                put("description", "音乐详情页或分享页 URL")
                            })
                            put("musicDataUrl", JSONObject().apply {
                                put("type", "string")
                                put("description", "可播放的音频直链 URL")
                            })
                            put("appId", JSONObject().apply {
                                put("type", "string")
                                put("description", "微信 appid，可留空")
                            })
                            put("session_id", JSONObject().apply {
                                put("type", "string")
                                put("description", "当前 Hchat 会话 session_id；如果你拿得到，请使用用户消息里的原值")
                            })
                        },
                        JSONArray().put("title").put("musicUrl").put("musicDataUrl")
                    ))
                    if (kugouEnabled && kugouPluginId.isNotBlank()) {
                        put(mcpTool(
                            "hchat_kugou_order_music",
                            "当用户表达想听歌、放一首歌、播放音乐、来一首某歌手或某歌曲时，先从用户话里提取歌曲名、歌手名或组合关键词，然后使用此工具调用 Hchat 配置的点歌工具，在当前微信会话搜索并发送音乐卡片。",
                            JSONObject().apply {
                                put("keyword", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "从用户请求中提取出的歌曲名、歌手名或组合关键词，例如 晴天、周杰伦 晴天、林俊杰 黑夜问白天")
                                })
                                put("session_id", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "当前 Hchat 会话 session_id；如果你拿得到，请使用用户消息里的原值")
                                })
                            },
                            JSONArray().put("keyword")
                        ))
                    }
                })
            }
        }

        private fun mcpTool(
            name: String,
            description: String,
            properties: JSONObject,
            required: JSONArray
        ): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("description", description)
                put("inputSchema", JSONObject().apply {
                    put("type", "object")
                    put("properties", properties)
                    put("required", required)
                })
            }
        }

        private fun mcpToolsCallResult(params: JSONObject?): JSONObject {
            val name = params?.optString("name").orEmpty()
            val args = params?.optJSONObject("arguments") ?: JSONObject()
            val ok = when (name) {
                "hchat_send_text" -> {
                    val text = args.optString("text").trim()
                    val target = targetFor(args)
                    text.isNotBlank() && AutoReplyRuntime.sendText(target, text)
                }
                "hchat_share_music" -> {
                    val title = args.optString("title").trim()
                    val description = args.optString("description").trim()
                    val musicUrl = args.optString("musicUrl").trim()
                    val musicDataUrl = args.optString("musicDataUrl").trim()
                    val appId = args.optString("appId").trim()
                    val target = targetFor(args)
                    title.isNotBlank() && musicUrl.isNotBlank() && musicDataUrl.isNotBlank() &&
                        WeChatApis.media()?.shareMusic(
                            target,
                            title,
                            description.ifBlank { "音乐" },
                            musicUrl,
                            musicDataUrl,
                            null,
                            appId
                        ) == true
                }
                "hchat_kugou_order_music" -> {
                    val keyword = args.optString("keyword").trim()
                    keyword.isNotBlank() && invokeKugouOrderMusic(args, keyword)
                }
                else -> false
            }
            return JSONObject().apply {
                put("content", JSONArray().put(JSONObject().apply {
                    put("type", "text")
                    put("text", if (ok) "ok" else "failed")
                }))
                put("isError", !ok)
            }
        }

        private fun targetFor(args: JSONObject): String {
            val sessionId = args.optString("session_id").trim()
            val resolved = sessionTargets[sessionId]?.target ?: target.get()
            xiaozhiMcpToolCallTimes[resolved] = System.currentTimeMillis()
            return resolved
        }

        private fun invokeKugouOrderMusic(args: JSONObject, keyword: String): Boolean {
            if (kugouEnabled && kugouPluginId.isNotBlank()) {
                val result = ScriptPluginRuntime.callPluginFunction(
                    kugouPluginId,
                    kugouFunctionName,
                    targetFor(args),
                    keyword
                )
                if (result.isSuccess) return true
            }
            return false
        }

        private fun mcpClientNotification(method: String): JSONObject {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", JSONObject())
            }
        }

        private fun mcpResult(id: Any?, result: JSONObject): JSONObject {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id ?: JSONObject.NULL)
                put("result", result)
            }
        }

        private fun mcpError(id: Any?, code: Int, message: String): JSONObject {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id ?: JSONObject.NULL)
                put("error", JSONObject().apply {
                    put("code", code)
                    put("message", message)
                })
            }
        }
    }

    private fun callAiOnce(config: AutoReplyAiConfig, history: List<AiMessage>): String? {
        return runCatching {
            val body = aiRequestBody(config, history, false)
            val response = httpClient.newCall(
                Request.Builder()
                    .url(finalAiUrl(config))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            response.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful || text.isBlank()) return@runCatching null
                JSONObject(text).optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { value -> value.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun callAiStream(config: AutoReplyAiConfig, history: List<AiMessage>): String? {
        return runCatching {
            val body = aiRequestBody(config, history, true)
            val response = httpClient.newCall(
                Request.Builder()
                    .url(finalAiUrl(config))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer ${config.apiKey}")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            ).execute()
            response.use {
                if (!it.isSuccessful) return@runCatching null
                val reader = it.body?.byteStream()?.bufferedReader(Charsets.UTF_8) ?: return@runCatching null
                val out = StringBuilder()
                reader.useLines { lines ->
                    lines.forEach { line ->
                        val data = line.trim().removePrefix("data:").trim()
                        if (data.isBlank() || data == line.trim() || data == "[DONE]") return@forEach
                        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return@forEach
                        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return@forEach
                        val delta = choice.optJSONObject("delta")
                        val piece = delta?.optString("content").orEmpty()
                        if (piece.isNotBlank() && !piece.equals("null", ignoreCase = true)) out.append(piece)
                    }
                }
                out.toString().takeIf { value -> value.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun aiRequestBody(config: AutoReplyAiConfig, history: List<AiMessage>, stream: Boolean): JSONObject {
        return JSONObject().apply {
            put("model", config.model)
            put("temperature", 0.7)
            put("stream", stream)
            put("messages", JSONArray().apply {
                history.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
        }
    }

    private fun finalAiUrl(config: AutoReplyAiConfig): String {
        val base = config.apiBaseUrl.trim().trimEnd('/')
        val path = config.apiPath.trim().ifBlank { AutoReplySettings.DEFAULT_AI_API_PATH }
        return base + if (path.startsWith("/")) path else "/$path"
    }

    private fun candidateModelUrls(apiBaseUrl: String): List<String> {
        val base = apiBaseUrl.trim().trimEnd('/')
        if (base.isBlank()) return emptyList()
        val primary = if (base.endsWith("/chat/completions")) {
            base.removeSuffix("/chat/completions") + "/models"
        } else if (base.endsWith("/models")) {
            base
        } else {
            "$base/models"
        }
        val alt = primary.removeSuffix("/models").removeSuffix("/v1") + "/v1/models"
        return listOf(primary, alt).distinct()
    }

    private fun parseModelList(text: String): List<String> {
        return runCatching {
            val obj = JSONObject(text)
            val result = linkedSetOf<String>()
            fun addArray(array: JSONArray?) {
                if (array == null) return
                for (i in 0 until array.length()) {
                    val item = array.opt(i)
                    val id = when (item) {
                        is JSONObject -> item.optString("id").ifBlank { item.optString("name") }
                        is String -> item
                        else -> ""
                    }.trim()
                    if (id.isNotEmpty()) result += id
                }
            }
            addArray(obj.optJSONArray("data"))
            addArray(obj.optJSONArray("models"))
            addArray(obj.optJSONArray("result"))
            result.sorted()
        }.getOrDefault(emptyList())
    }

    private fun trimHistory(history: MutableList<AiMessage>, contextLimit: Int) {
        val max = contextLimit.coerceAtLeast(0) * 2 + if (history.firstOrNull()?.role == "system") 1 else 0
        if (max <= 0) {
            history.clear()
            return
        }
        while (history.size > max) {
            val removeIndex = if (history.firstOrNull()?.role == "system") 1 else 0
            if (removeIndex in history.indices) history.removeAt(removeIndex) else break
        }
    }

    private fun targetMatches(
        rule: AutoReplyRule,
        ctx: MessageContext,
        message: WeChatMessageObserveApi.ObservedMessage
    ): Boolean {
        if (rule.excludedIds.contains(ctx.talker) ||
            rule.excludedIds.contains(ctx.sender) ||
            (ctx.group && rule.excludedIds.contains("${ctx.talker}/${ctx.sender}"))
        ) return false
        if (ctx.group && (
                rule.excludedGroupMembers.contains(ctx.sender) ||
                    rule.excludedGroupMembers.contains("${ctx.talker}/${ctx.sender}")
                )
        ) return false
        if (rule.targetMode == AutoReplySettings.TARGET_SPECIFIC) {
            return includedTargetMatches(rule, ctx)
        }
        return when (rule.targetMode) {
            AutoReplySettings.TARGET_PRIVATE -> !ctx.group && !isOfficialTalker(message, ctx.talker)
            AutoReplySettings.TARGET_GROUP -> ctx.group
            AutoReplySettings.TARGET_OFFICIAL -> isOfficialTalker(message, ctx.talker)
            else -> true
        }
    }

    private fun isOfficialTalker(
        message: WeChatMessageObserveApi.ObservedMessage,
        talker: String
    ): Boolean {
        return message.isOfficialAccount() ||
            talker.startsWith("gh_", ignoreCase = true) ||
            talker.endsWith("@app", ignoreCase = true) ||
            talker.equals("newsapp", ignoreCase = true)
    }

    private fun shouldSkipMessage(message: WeChatMessageObserveApi.ObservedMessage): Boolean {
        if (message.isSend()) return true
        val selfWxId = WeChatApis.contact().account()?.selfWxId().orEmpty()
        if (selfWxId.isBlank()) return message.source != "message_db"
        return message.sender.equals(selfWxId, ignoreCase = true) ||
            message.getSendTalker().equals(selfWxId, ignoreCase = true)
    }

    private fun includedTargetMatches(rule: AutoReplyRule, ctx: MessageContext): Boolean {
        val targetIds = rule.targetIds.filter { isValidTargetId(it) }.toSet()
        val includedMembers = rule.includedGroupMembers.filter { isValidTargetId(it) }.toSet()
        return targetIds.contains(ctx.talker) ||
            (!ctx.group && targetIds.contains(ctx.sender)) ||
            (ctx.group && targetIds.contains("${ctx.talker}/${ctx.sender}")) ||
            (ctx.group && includedMembers.contains("${ctx.talker}/${ctx.sender}")) ||
            (ctx.group && includedMembers.contains(ctx.sender))
    }

    private fun isValidTargetId(value: String): Boolean {
        return value.split('/').all { part ->
            val id = part.trim()
            id.isNotEmpty() && !id.contains("@@")
        }
    }

    private fun triggerMatches(rule: AutoReplyRule, ctx: MessageContext, isPat: Boolean): Boolean {
        if (ctx.group) {
            if (rule.atTrigger == AutoReplySettings.AT_ME && !ctx.atMe) return false
            if (rule.atTrigger == AutoReplySettings.AT_ALL && !ctx.atAll) return false
        } else if (rule.atTrigger != AutoReplySettings.AT_NONE) {
            return false
        }
        if (rule.patTrigger == AutoReplySettings.PAT_ME && !ctx.patMe) return false
        return rule.patTrigger != AutoReplySettings.PAT_ME || isPat
    }

    private fun keywordMatches(
        rule: AutoReplyRule,
        content: String,
        isText: Boolean,
        isPat: Boolean
    ): Boolean {
        if (isPat && rule.patTrigger == AutoReplySettings.PAT_ME) return true
        val keyword = rule.keyword
        return when (rule.matchType) {
            AutoReplySettings.MATCH_ANY -> !isText ||
                splitKeywords(rule.excludedKeywords).none { content.contains(it) }
            AutoReplySettings.MATCH_EXACT -> isText && splitKeywords(keyword).any { content == it }
            AutoReplySettings.MATCH_REGEX -> isText &&
                runCatching { Pattern.compile(keyword).matcher(content).find() }.getOrDefault(false)
            else -> isText && splitKeywords(keyword).any { content.contains(it) || it.isBlank() }
        }
    }

    private fun countAndCheck(rule: AutoReplyRule, ctx: MessageContext): Boolean {
        if (rule.maxReplyCount <= 0) return true
        val key = "${rule.id}|${ctx.talker}|${ctx.sender}"
        val current = replyCounts[key] ?: 0
        if (current >= rule.maxReplyCount) return false
        replyCounts[key] = current + 1
        return true
    }

    private fun isReplyCoolingDown(rule: AutoReplyRule, talker: String): Boolean {
        if (rule.cooldownSeconds <= 0L) return false
        val key = replyCooldownKey(rule, talker)
        val lastReplyAt = lastReplyTimes[key] ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now >= lastReplyAt && now - lastReplyAt < replyCooldownMillis(rule)) return true
        lastReplyTimes.remove(key, lastReplyAt)
        return false
    }

    private fun markReplyCooldown(rule: AutoReplyRule, talker: String) {
        if (rule.cooldownSeconds <= 0L) return
        lastReplyTimes[replyCooldownKey(rule, talker)] = SystemClock.elapsedRealtime()
    }

    private fun replyCooldownKey(rule: AutoReplyRule, talker: String): String =
        "${rule.id}|$talker"

    private fun replyCooldownMillis(rule: AutoReplyRule): Long =
        rule.cooldownSeconds.coerceAtMost(Long.MAX_VALUE / 1000L) * 1000L

    private fun plainContent(message: WeChatMessageObserveApi.ObservedMessage): String {
        val raw = message.content.ifBlank { message.getContent() }.ifBlank { message.xml }
        val groupPrefix = Regex("""^[^\s:]{3,80}:\n""")
        return raw.replace(groupPrefix, "").trim()
    }

    private fun resolveSender(message: WeChatMessageObserveApi.ObservedMessage, talker: String, group: Boolean): String {
        val direct = message.sender.ifBlank { message.getSendTalker() }
        if (direct.isNotBlank()) return direct
        if (!group) return talker
        return Regex("""^([^\s:]{3,80}):\n""")
            .find(message.content.ifBlank { message.getContent() })
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
    }

    private fun format(template: String, ctx: MessageContext): String {
        val contacts = WeChatApis.contact().contacts()
        val senderName = if (ctx.group) {
            contacts?.getGroupMemberDisplayName(ctx.talker, ctx.sender).orEmpty()
        } else {
            contacts?.getDisplayName(ctx.sender).orEmpty()
        }.ifBlank { ctx.sender }
        val groupName = if (ctx.group) contacts?.getDisplayName(ctx.talker).orEmpty() else ""
        return template
            .replace("%friendName%", senderName)
            .replace("%senderName%", senderName)
            .replace("%senderWxid%", ctx.sender)
            .replace("%talker%", ctx.talker)
            .replace("%groupName%", groupName)
            .replace("%content%", ctx.content)
            .replace("%atSender%", "[AtWx=${ctx.sender}]")
            .replace("%atAll%", if (ctx.group) "[AtWx=$AT_ALL_WXID]" else "@所有人")
    }

    private fun sendText(talker: String, content: String): Boolean {
        if (talker.isBlank() || content.isBlank()) return false
        val sender = WeChatApis.message().sender() ?: return false
        val atRegex = Regex("""\[AtWx=([^\]]+)]""")
        val atList = atRegex.findAll(content).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()
        val text = atRegex.replace(content) { match ->
            val wxid = match.groupValues[1].trim()
            val name = if (wxid == AT_ALL_WXID) {
                "所有人"
            } else {
                val contacts = WeChatApis.contact().contacts()
                contacts?.getGroupMemberDisplayName(talker, wxid).orEmpty()
                    .ifBlank { contacts?.getDisplayName(wxid).orEmpty() }
                    .ifBlank { wxid }
            }
            "@$name\u2005"
        }
        return if (atList.isEmpty()) sender.sendText(talker, text) else sender.sendTextWithAtList(talker, text, atList)
    }

    private fun sendPaths(value: String, sender: (String) -> Boolean): Boolean {
        var ok = false
        for (path in splitMulti(value)) {
            val file = File(path)
            if (file.isFile && sender(path)) ok = true
            sleep(300L)
        }
        return ok
    }

    private fun sendFavorites(value: String, sender: (String) -> Boolean): Boolean {
        var ok = false
        for (localId in splitMulti(value)) {
            if (sender(localId)) ok = true
            sleep(300L)
        }
        return ok
    }

    private fun randomAudioFile(folderPath: String): String? {
        val directFiles = splitMulti(folderPath)
            .map { File(it) }
            .filter { it.isFile && it.extension.lowercase() in audioExtensions }
        if (directFiles.isNotEmpty()) return directFiles.random().absolutePath
        val folder = File(folderPath)
        if (!folder.isDirectory) return null
        val files = folder.listFiles { file ->
            file.isFile && file.extension.lowercase() in audioExtensions
        }.orEmpty()
        return files.randomOrNull()?.absolutePath
    }

    private fun splitReplies(value: String): List<String> =
        value.split('|').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(value).filter { it.isNotBlank() } }

    private fun splitKeywords(value: String): List<String> =
        value.split('|', '，', ',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun splitMulti(value: String): List<String> =
        value.split(";;;")
            .flatMap { it.split('\n') }
            .flatMap { it.split('|') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val audioExtensions = setOf("mp3", "wav", "ogg", "aac", "m4a", "silk")

    private fun ensureXml(content: String): String {
        val text = content.trim()
        if (text.startsWith("<")) return content
        val safe = content.replace("]]>", "]]]]><![CDATA[>")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><msg><appmsg appid=\"\" sdkver=\"0\"><title><![CDATA[$safe]]></title><des><![CDATA[$safe]]></des><type>1</type><content><![CDATA[$safe]]></content></appmsg></msg>"
    }

    private fun inTimeRange(start: String, end: String): Boolean {
        if (start.isBlank() || end.isBlank()) return true
        val startSecond = parseSecondOfDay(start) ?: return true
        val endSecond = parseSecondOfDay(end) ?: return true
        val now = Calendar.getInstance()
        val current = now.get(Calendar.HOUR_OF_DAY) * 3600 +
            now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)
        return if (endSecond < startSecond) {
            current >= startSecond || current < endSecond
        } else {
            current >= startSecond && current < endSecond
        }
    }

    private fun parseSecondOfDay(value: String): Int? {
        val parts = value.split(':')
        if (parts.size !in 2..3) return null
        val hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: return null
        val minute = parts[1].toIntOrNull()?.coerceIn(0, 59) ?: return null
        val second = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        return hour * 3600 + minute * 60 + second
    }

    private fun modifyLabelIfNeeded(wxid: String, enabled: Boolean, label: String) {
        if (!enabled || wxid.isBlank() || label.isBlank()) return
        runCatching { WeChatApis.contact().contacts()?.modifyContactLabelList(wxid, label) }
    }

    private fun applyFriendLabels(settings: AutoReplySettings, wxid: String, keys: FriendAutomationKeys) {
        if (wxid.isBlank()) return
        val labels = linkedSetOf<String>()
        if (settings.getBoolean(keys.labelNewFriendEnable, false)) {
            labels += "新加好友"
        }
        if (settings.getBoolean(keys.labelDateEnable, false)) {
            labels += formatDate(settings.getString(keys.labelDateFormat, "yyyy-MM-dd"), "yyyy-MM-dd")
        }
        if (settings.getBoolean(keys.labelExistingEnable, false)) {
            labels += splitSavedNames(settings.getString(keys.labelSelectedNames, ""))
        }
        if (labels.isEmpty()) return
        val contacts = WeChatApis.contact().contacts() ?: return
        labels.forEach { label ->
            runCatching { contacts.addContactLabel(label) }
                .onFailure { HLog.e("$TAG 创建好友标签失败: $label ${it.message}", it) }
        }
        runCatching { contacts.modifyContactLabelList(wxid, labels.toList()) }
            .onFailure { HLog.e("$TAG 修改好友标签失败: ${it.message}", it) }
    }

    private fun applyFriendRemark(settings: AutoReplySettings, wxid: String, keys: FriendAutomationKeys) {
        if (wxid.isBlank()) return
        val parts = mutableListOf<String>()
        if (settings.getBoolean(keys.remarkNewFriendEnable, false)) {
            parts += "新加好友"
        }
        if (settings.getBoolean(keys.remarkDateEnable, false)) {
            parts += formatDate(settings.getString(keys.remarkDateFormat, "yyMMdd"), "yyMMdd")
        }
        if (settings.getBoolean(keys.remarkCustomEnable, false)) {
            settings.getString(keys.remarkCustomText, "").trim()
                .takeIf { it.isNotBlank() }
                ?.let { parts += it }
        }
        if (parts.isEmpty()) return
        val contacts = WeChatApis.contact().contacts() ?: return
        val suffix = parts.joinToString("")
        val nickname = contacts.getContact(wxid)?.let { contact ->
            contact.nickname.ifBlank { contact.displayName() }.ifBlank { wxid }
        } ?: wxid
        val remark = if (settings.getBoolean(keys.remarkNicknameSuffixEnable, false)) {
            nickname + suffix
        } else {
            suffix
        }
        runCatching { contacts.modifyContactRemark(wxid, remark) }
            .onFailure { HLog.e("$TAG 修改好友备注失败: ${it.message}", it) }
    }

    private fun splitSavedNames(value: String): List<String> =
        value.split(";;;", "|", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    private fun formatDate(pattern: String, fallback: String): String {
        val clean = pattern.trim().ifBlank { fallback }
        return runCatching { SimpleDateFormat(clean, Locale.getDefault()).format(Date()) }
            .getOrElse { SimpleDateFormat(fallback, Locale.getDefault()).format(Date()) }
    }

    private fun showToast(context: Context, text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun sleep(ms: Long) {
        if (ms <= 0L) return
        runCatching { Thread.sleep(ms) }
    }

    private fun String.cleanAiText(): String = replace("\u0000", "")
        .replace(Regex("(?i)^null"), "")
        .replace(Regex("(?i)null$"), "")
        .trim()

    private data class MessageContext(
        val talker: String,
        val sender: String,
        val content: String = "",
        val msgId: Long = 0L,
        val group: Boolean = false,
        val atMe: Boolean = false,
        val atAll: Boolean = false,
        val patMe: Boolean = false
    )

    private data class AiMessage(val role: String, val content: String)

    private data class XiaozhiReply(
        val text: String = "",
        val voicePath: String = "",
        val voiceBytes: Long = 0L,
        val durationMs: Int = 0,
        val voiceSegments: List<XiaozhiVoiceSegment> = emptyList(),
        val mergeVoiceSegments: Boolean = false,
        val mergedSegmentCount: Int = 0
    )

    private data class XiaozhiVoiceSegment(
        val file: File,
        val durationMs: Int
    )

    private data class FriendAutomationKeys(
        val labelNewFriendEnable: String,
        val labelDateEnable: String,
        val labelDateFormat: String,
        val labelExistingEnable: String,
        val labelSelectedNames: String,
        val remarkNewFriendEnable: String,
        val remarkNicknameSuffixEnable: String,
        val remarkDateEnable: String,
        val remarkDateFormat: String,
        val remarkCustomEnable: String,
        val remarkCustomText: String
    )

    private val autoAcceptAutomationKeys = FriendAutomationKeys(
        AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_NEW_FRIEND_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_DATE_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_DATE_FORMAT,
        AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_EXISTING_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_LABEL_SELECTED_NAMES,
        AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_NEW_FRIEND_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_NICKNAME_SUFFIX_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_DATE_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_DATE_FORMAT,
        AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_CUSTOM_ENABLE,
        AutoReplySettings.KEY_AUTO_ACCEPT_REMARK_CUSTOM_TEXT
    )

    private val greetAcceptedAutomationKeys = FriendAutomationKeys(
        AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_NEW_FRIEND_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_DATE_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_DATE_FORMAT,
        AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_EXISTING_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_LABEL_SELECTED_NAMES,
        AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_NEW_FRIEND_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_NICKNAME_SUFFIX_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_DATE_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_DATE_FORMAT,
        AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_CUSTOM_ENABLE,
        AutoReplySettings.KEY_GREET_ACCEPTED_REMARK_CUSTOM_TEXT
    )
}

data class XiaozhiMcpStatus(
    val connected: Boolean,
    val label: String,
    val detail: String
)
