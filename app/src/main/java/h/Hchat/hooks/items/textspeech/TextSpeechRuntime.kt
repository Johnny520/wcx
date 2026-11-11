package h.Hchat.hooks.items.textspeech

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.media.VoiceMessageDurationResolver
import h.Hchat.hooks.api.media.WeChatVoiceApi
import h.Hchat.hooks.api.message.WeChatMessageObserveApi
import h.Hchat.hooks.api.model.WeChatMessage
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.io.File
import java.util.ArrayDeque
import java.util.Calendar
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TextSpeechRuntime(context: Context) {
    private sealed class PlaybackItem

    private data class Speech(val text: String, var resumeOffset: Int = 0) : PlaybackItem()

    private data class VoiceSpeech(
        val path: String,
        val durationMillis: Int
    ) : PlaybackItem()

    private data class AnnouncementContext(
        val talker: String,
        val senderId: String,
        val senderNickname: String,
        val wechatId: String,
        val remarkName: String,
        val groupNickname: String,
        val groupName: String,
        val isGroup: Boolean,
        val messageTime: Long
    )

    private data class PendingVoice(
        val key: String,
        val talker: String,
        val msgId: Long,
        val msgSvrId: Long,
        val initialMessage: WeChatMessage?,
        val initialFileNames: List<String>,
        val announcement: AnnouncementContext
    ) {
        val candidates = linkedMapOf<String, String>().apply {
            initialFileNames.forEach { put(it, "initial") }
        }
        val messageBodies = linkedSetOf<String>().apply {
            initialMessage?.bodyContent()?.takeIf { it.isNotBlank() }?.let(::add)
        }
        @Volatile var lookupDiagnostic = "lookups=unattempted"
        @Volatile var diagnostic = "lookups=unattempted candidates=[]"
    }

    private data class ResolvedVoice(
        val path: String,
        val durationMillis: Int
    )

    private data class CandidateResolution(
        val voice: ResolvedVoice?,
        val diagnostic: String
    )

    private val appContext = context.applicationContext ?: context
    private val prefs = HchatStorage.preferences(appContext, TextSpeechSettings.PREFS_NAME)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<PlaybackItem>()
    private val pendingVoiceKeys = ConcurrentHashMap.newKeySet<String>()
    private val restartTtsRunnable = Runnable { restartTtsForEngineChange() }
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            TextSpeechSettings.KEY_ENABLE -> if (!settings().isEnabled()) {
                mainHandler.post {
                    stopAndClear()
                    safeShutdownTts()
                }
            }
            TextSpeechSettings.KEY_TTS_ENGINE,
            TextSpeechSettings.KEY_TTS_VOICE -> {
                mainHandler.removeCallbacks(restartTtsRunnable)
                mainHandler.post(restartTtsRunnable)
            }
            TextSpeechSettings.KEY_PLAY_VOICE_MESSAGES -> if (!settings().boolean(
                    TextSpeechSettings.KEY_PLAY_VOICE_MESSAGES,
                    TextSpeechSettings.DEFAULT_PLAY_VOICE_MESSAGES
                )
            ) {
                mainHandler.post(::disableVoicePlayback)
            }
            TextSpeechSettings.KEY_VOLUME_CONTROL -> mainHandler.post {
                if (settings().boolean(
                        TextSpeechSettings.KEY_VOLUME_CONTROL,
                        TextSpeechSettings.DEFAULT_VOLUME_CONTROL
                    ) && current != null
                ) {
                    activateVolumeSession()
                } else {
                    releaseVolumeSession()
                }
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var ttsGeneration = 0L
    private var ttsInitTimeoutRunnable: Runnable? = null
    private var initAttempts = 0
    private var configuredTtsEnginePackage = ""
    private var ttsEngineCandidates: List<String> = emptyList()
    private var ttsEngineCandidateIndex = 0
    private var current: PlaybackItem? = null
    private var activeUtteranceId = ""
    private var activeUtteranceBaseOffset = 0
    private var paused = false
    private var sequence = 0L
    private var consumedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
    private var mediaSession: MediaSession? = null
    private var volumeProvider: VolumeProvider? = null
    private var volumeChangeReceiver: BroadcastReceiver? = null
    private var ignoredVolumeValue: Int? = null
    private var voiceTimeoutRunnable: Runnable? = null
    @Volatile private var voicePreparationGeneration = 0L
    @Volatile private var destroyed = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun handleMessage(message: WeChatMessageObserveApi.ObservedMessage) {
        if (destroyed) return
        val currentSettings = settings()
        if (!currentSettings.isEnabled() || message.outgoing || message.isSend) return
        val playVoice = message.isVoice && currentSettings.boolean(
            TextSpeechSettings.KEY_PLAY_VOICE_MESSAGES,
            TextSpeechSettings.DEFAULT_PLAY_VOICE_MESSAGES
        )
        if (!message.isText && !playVoice) return
        val talker = message.talker.ifBlank { message.getTalker() }.trim()
        if (talker.isEmpty() || talker !in currentSettings.allowedContacts()) return
        if (isInQuietWindow(currentSettings) || shouldSuppressByWechatDoNotDisturb(currentSettings, talker)) return
        val announcement = resolveAnnouncementContext(message, talker)

        if (playVoice) {
            prepareVoiceMessage(message, talker, announcement)
            return
        }

        val content = message.message?.bodyContent().orEmpty()
            .ifBlank { message.content.ifBlank { message.getContent() } }
            .let(::normalizeText)
        if (content.isEmpty()) return

        val speechText = renderAnnouncement(
            currentSettings,
            announcement,
            content,
            MESSAGE_TYPE_TEXT,
            0
        )
        if (speechText.isEmpty()) return
        mainHandler.post { enqueue(Speech(speechText)) }
    }

    fun handleVolumeKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }
        if (event.action == KeyEvent.ACTION_UP && consumedVolumeKey == event.keyCode) {
            consumedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (consumedVolumeKey == event.keyCode && event.repeatCount > 0) return true
        if (event.repeatCount == 0) consumedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
        if (event.repeatCount != 0) return false
        val currentSettings = settings()
        if (!currentSettings.isEnabled() || !currentSettings.boolean(
                TextSpeechSettings.KEY_VOLUME_CONTROL,
                TextSpeechSettings.DEFAULT_VOLUME_CONTROL
            )
        ) {
            return false
        }
        if (current == null && !paused) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                consumedVolumeKey = event.keyCode
                mainHandler.post { if (paused) skipCurrent() else pauseCurrent() }
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!paused) return false
                consumedVolumeKey = event.keyCode
                mainHandler.post(::resumeCurrent)
                true
            }
            else -> false
        }
    }

    fun shutdown() {
        destroyed = true
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        mainHandler.post {
            stopAndClear()
            safeShutdownTts()
        }
    }

    private fun settings(): TextSpeechSettings = TextSpeechSettings(appContext)

    private fun prepareVoiceMessage(
        observed: WeChatMessageObserveApi.ObservedMessage,
        talker: String,
        announcement: AnnouncementContext
    ) {
        val message = observed.message ?: observed.storedMessage
        val msgId = observed.msgId.takeIf { it > 0L } ?: (message?.msgId ?: 0L)
        val msgSvrId = message?.msgSvrId ?: 0L
        val key = when {
            msgSvrId > 0L -> "svr:$talker:$msgSvrId"
            msgId > 0L -> "local:$msgId"
            else -> "transient:$talker:${message?.createTime ?: 0L}:${observed.content.hashCode()}"
        }
        if (!pendingVoiceKeys.add(key)) return
        val pending = PendingVoice(
            key,
            talker,
            msgId,
            msgSvrId,
            message,
            message?.let(::voiceFileNames).orEmpty(),
            announcement
        )
        val generation = voicePreparationGeneration
        scheduleVoiceResolution(pending, generation, 0, INITIAL_VOICE_RESOLVE_DELAY_MS)
    }

    private fun scheduleVoiceResolution(
        pending: PendingVoice,
        generation: Long,
        attempt: Int,
        delayMillis: Long
    ) {
        mainHandler.postDelayed({
            if (!canContinueVoicePreparation(pending, generation)) return@postDelayed
            val taskApi = WeChatApis.tasks()
            if (taskApi == null) {
                pendingVoiceKeys.remove(pending.key)
                HLog.e("$TAG 等待语音文件失败: 任务 API 未就绪")
                return@postDelayed
            }
            taskApi.runAsync {
                val resolved = try {
                    resolveVoice(pending, attempt)
                } catch (error: Throwable) {
                    pending.diagnostic = "resolveError=${diagnosticValue(error.javaClass.name + ":" + error.message)}"
                    null
                }
                mainHandler.post {
                    if (!canContinueVoicePreparation(pending, generation)) return@post
                    if (resolved != null) {
                        pendingVoiceKeys.remove(pending.key)
                        enqueueVoiceMessage(
                            renderAnnouncement(
                                settings(),
                                pending.announcement,
                                "",
                                MESSAGE_TYPE_VOICE,
                                resolved.durationMillis
                            ),
                            VoiceSpeech(resolved.path, resolved.durationMillis)
                        )
                    } else if (attempt + 1 < MAX_VOICE_RESOLVE_ATTEMPTS) {
                        scheduleVoiceResolution(
                            pending,
                            generation,
                            attempt + 1,
                            VOICE_RESOLVE_INTERVAL_MS
                        )
                    } else {
                        pendingVoiceKeys.remove(pending.key)
                        HLog.e(
                            "$TAG 等待语音文件超时: talker=${pending.talker} " +
                                "msgId=${pending.msgId} msgSvrId=${pending.msgSvrId} ${pending.diagnostic}"
                        )
                    }
                }
            }
        }, delayMillis)
    }

    private fun canContinueVoicePreparation(pending: PendingVoice, generation: Long): Boolean {
        if (destroyed || generation != voicePreparationGeneration || pending.key !in pendingVoiceKeys) return false
        val currentSettings = settings()
        val canContinue = currentSettings.isEnabled() && currentSettings.boolean(
            TextSpeechSettings.KEY_PLAY_VOICE_MESSAGES,
            TextSpeechSettings.DEFAULT_PLAY_VOICE_MESSAGES
        ) && pending.talker in currentSettings.allowedContacts() &&
            !isInQuietWindow(currentSettings) &&
            !shouldSuppressByWechatDoNotDisturb(currentSettings, pending.talker)
        if (!canContinue) pendingVoiceKeys.remove(pending.key)
        return canContinue
    }

    private fun resolveVoice(pending: PendingVoice, attempt: Int): ResolvedVoice? {
        val voiceApi = WeChatApis.media()?.voices()
            ?: run {
                pending.diagnostic = "voiceApi=false"
                return null
            }
        val cached = resolveCandidateFiles(pending, voiceApi)
        if (cached.voice != null) return cached.voice

        val finalAttempt = attempt + 1 >= MAX_VOICE_RESOLVE_ATTEMPTS
        val refreshDatabase = attempt == 0 ||
            attempt % VOICE_DATABASE_REFRESH_INTERVAL_ATTEMPTS == 0 || finalAttempt
        if (refreshDatabase) {
            refreshVoiceCandidates(pending, finalAttempt)
        }
        val current = if (refreshDatabase) resolveCandidateFiles(pending, voiceApi) else cached
        pending.diagnostic = "voiceApi=true ${pending.lookupDiagnostic} ${current.diagnostic}"
        return current.voice
    }

    private fun resolveCandidateFiles(
        pending: PendingVoice,
        voiceApi: WeChatVoiceApi
    ): CandidateResolution {
        val candidateState = ArrayList<String>(pending.candidates.size)
        val orderedCandidates = pending.candidates.entries.sortedBy { it.value == "initial" }
        for ((fileName, source) in orderedCandidates) {
            val directFile = File(fileName)
            if (directFile.isFile && directFile.length() > 0L) {
                return CandidateResolution(resolvedVoice(directFile, fileName, pending), "")
            }
            val resolvedPath = voiceApi.resolvePath(fileName)
            val resolvedFile = resolvedPath.takeIf { it.isNotBlank() }?.let(::File)
            if (resolvedFile != null && resolvedFile.isFile && resolvedFile.length() > 0L) {
                return CandidateResolution(resolvedVoice(resolvedFile, fileName, pending), "")
            }
            candidateState += "$source:${diagnosticValue(fileName)}" +
                "(direct=${fileState(directFile)},resolved=${diagnosticValue(resolvedPath)}:" +
                "${fileState(resolvedFile)})"
        }
        return CandidateResolution(null, "candidates=[${candidateState.joinToString(";")}]")
    }

    private fun refreshVoiceCandidates(pending: PendingVoice, finalAttempt: Boolean) {
        val store = WeChatApis.messageStore()
        val byMsgId = if (pending.msgId > 0L) store?.getMessageById(pending.msgId) else null
        val byTalkerSvrId = if (byMsgId == null && pending.msgSvrId > 0L) {
            store?.getMessageBySvrId(pending.talker, pending.msgSvrId)
        } else {
            null
        }
        val byGlobalSvrId = if (finalAttempt && pending.msgSvrId > 0L) {
            store?.getMessageBySvrId(pending.msgSvrId)
        } else {
            null
        }
        addVoiceMessageCandidates(pending, "msgId", byMsgId)
        addVoiceMessageCandidates(pending, "talkerSvrId", byTalkerSvrId)
        addVoiceMessageCandidates(pending, "globalSvrId", byGlobalSvrId)
        pending.lookupDiagnostic = "lookups=[store=${store?.isAvailable == true}," +
            "initial=${pending.initialMessage != null}," +
            "msgId=${if (pending.msgId > 0L) byMsgId != null else "skip"}," +
            "talkerSvrId=${if (byMsgId == null && pending.msgSvrId > 0L) byTalkerSvrId != null else "skip"}," +
            "globalSvrId=${if (finalAttempt && pending.msgSvrId > 0L) byGlobalSvrId != null else "skip"}]"
    }

    private fun addVoiceMessageCandidates(
        pending: PendingVoice,
        source: String,
        message: WeChatMessage?
    ) {
        if (message == null) return
        voiceFileNames(message).forEach { fileName ->
            if (pending.candidates[fileName] == null || pending.candidates[fileName] == "initial") {
                pending.candidates[fileName] = source
            }
        }
        message.bodyContent().takeIf { it.isNotBlank() }?.let(pending.messageBodies::add)
    }

    private fun resolvedVoice(
        file: File,
        fileName: String,
        pending: PendingVoice
    ): ResolvedVoice {
        val duration = VoiceMessageDurationResolver.resolve(
            null,
            fileName,
            pending.msgId,
            pending.messageBodies.toList(),
            DEFAULT_VOICE_DURATION_MS
        ).coerceAtLeast(MIN_VOICE_DURATION_MS)
        return ResolvedVoice(file.absolutePath, duration)
    }

    private fun fileState(file: File?): String {
        if (file == null) return "empty"
        if (!file.isFile) return "missing"
        return if (file.length() > 0L) "ready" else "empty"
    }

    private fun diagnosticValue(value: String): String {
        if (value.isBlank()) return "empty"
        return value.replace('\n', ' ').replace('\r', ' ').take(MAX_VOICE_DIAGNOSTIC_VALUE_LENGTH)
    }

    private fun voiceFileNames(message: WeChatMessage): List<String> {
        val result = linkedSetOf<String>()
        message.imagePath.takeIf { it.isNotBlank() }?.let(result::add)
        val body = message.bodyContent()
        val parts = body.trimEnd('\n', '\r').split(':')
        if (parts.size >= 3 && '<' !in body) {
            (if (parts.size == 4) parts[1] else parts[0])
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(result::add)
        }
        listOf(
            WeChatMessage.xmlAttr(body, "filename"),
            WeChatMessage.xmlAttr(body, "voiceurl"),
            WeChatMessage.xmlTag(body, "filename"),
            WeChatMessage.xmlTag(body, "voiceurl")
        ).filterTo(result) { it.isNotBlank() }
        return result.toList()
    }

    private fun enqueue(speech: PlaybackItem) {
        if (destroyed || !settings().isEnabled()) return
        queue.addLast(speech)
        playNext()
    }

    private fun enqueueVoiceMessage(prefix: String, voice: VoiceSpeech) {
        if (destroyed || !settings().isEnabled()) return
        if (prefix.isNotBlank()) queue.addLast(Speech(prefix))
        queue.addLast(voice)
        playNext()
    }

    private fun ensureTts() {
        if (destroyed || !settings().isEnabled()) return
        if (ttsReady) {
            playNext()
            return
        }
        if (ttsInitializing || tts != null) return
        ttsInitializing = true
        val generation = ++ttsGeneration
        val requestedPackage = settings().enginePackage()
        if (configuredTtsEnginePackage != requestedPackage || ttsEngineCandidates.isEmpty()) {
            configuredTtsEnginePackage = requestedPackage
            ttsEngineCandidates = TextSpeechEngineCatalog.connectionCandidates(appContext, requestedPackage)
            ttsEngineCandidateIndex = 0
        }
        val enginePackage = ttsEngineCandidates.getOrNull(ttsEngineCandidateIndex)
            ?: requestedPackage
        try {
            val listener = TextToSpeech.OnInitListener { status ->
                mainHandler.post {
                    onTtsInitialized(generation, requestedPackage, enginePackage, status)
                }
            }
            tts = if (enginePackage.isBlank()) {
                TextToSpeech(appContext, listener)
            } else {
                TextToSpeech(appContext, listener, enginePackage)
            }
            scheduleTtsInitTimeout(generation, enginePackage)
        } catch (error: Throwable) {
            ttsInitializing = false
            retryInitialization(error)
        }
    }

    private fun onTtsInitialized(
        generation: Long,
        requestedPackage: String,
        enginePackage: String,
        status: Int
    ) {
        if (generation != ttsGeneration) return
        cancelTtsInitTimeout()
        ttsInitializing = false
        if (destroyed || !settings().isEnabled()) {
            safeShutdownTts()
            return
        }
        if (requestedPackage != settings().enginePackage()) {
            restartTtsForEngineChange()
            return
        }
        if (status != TextToSpeech.SUCCESS || tts == null) {
            retryInitialization(null)
            return
        }
        val activeEngine = KavaReflector.readField(tts, "mCurrentEngine") as? String
        if (enginePackage.isNotBlank() && !activeEngine.isNullOrBlank() && activeEngine != enginePackage) {
            retryInitialization(
                IllegalStateException("引擎 $enginePackage 连接失败，系统回退到 $activeEngine")
            )
            return
        }
        initAttempts = 0
        val configurationError = configureTts()
        if (configurationError != null) {
            failInitialization(configurationError)
            return
        }
        ttsReady = true
        playNext()
    }

    private fun scheduleTtsInitTimeout(generation: Long, enginePackage: String) {
        cancelTtsInitTimeout()
        Runnable {
            if (generation != ttsGeneration || !ttsInitializing) return@Runnable
            ttsInitTimeoutRunnable = null
            retryInitialization(
                IllegalStateException("引擎 ${enginePackage.ifBlank { "系统默认" }} 初始化超时")
            )
        }.also { runnable ->
            ttsInitTimeoutRunnable = runnable
            mainHandler.postDelayed(runnable, TTS_INIT_TIMEOUT_MS)
        }
    }

    private fun cancelTtsInitTimeout() {
        ttsInitTimeoutRunnable?.let(mainHandler::removeCallbacks)
        ttsInitTimeoutRunnable = null
    }

    private fun retryInitialization(error: Throwable?) {
        safeShutdownTts()
        if (destroyed || !settings().isEnabled()) return
        initAttempts++
        val installedEngines = TextSpeechEngineCatalog.installed(appContext)
        if (installedEngines.isNotEmpty() && initAttempts <= MAX_INIT_RETRIES) {
            mainHandler.postDelayed(::ensureTts, INIT_RETRY_DELAY_MS)
        } else if (advanceTtsEngineCandidate()) {
            mainHandler.postDelayed(::ensureTts, INIT_RETRY_DELAY_MS)
        } else {
            val engine = settings().enginePackage().ifBlank { "系统默认" }
            val reason = if (installedEngines.isEmpty()) {
                TextSpeechEngineCatalog.unavailableReason(appContext)
            } else {
                "文字转语音引擎初始化失败: $engine"
            }
            HLog.e("$TAG $reason", error)
            resetTtsEngineCandidates()
            discardTextItemsAndContinue()
        }
    }

    private fun failInitialization(message: String) {
        safeShutdownTts()
        if (advanceTtsEngineCandidate()) {
            mainHandler.postDelayed(::ensureTts, INIT_RETRY_DELAY_MS)
            return
        }
        val engine = settings().enginePackage().ifBlank { "系统默认" }
        HLog.e("$TAG $message, engine=$engine")
        resetTtsEngineCandidates()
        discardTextItemsAndContinue()
    }

    private fun advanceTtsEngineCandidate(): Boolean {
        if (configuredTtsEnginePackage.isNotBlank()) return false
        if (ttsEngineCandidateIndex + 1 >= ttsEngineCandidates.size) return false
        ttsEngineCandidateIndex++
        initAttempts = 0
        return true
    }

    private fun resetTtsEngineCandidates() {
        configuredTtsEnginePackage = ""
        ttsEngineCandidates = emptyList()
        ttsEngineCandidateIndex = 0
        initAttempts = 0
    }

    private fun discardTextItemsAndContinue() {
        val retained = queue.filterNot { it is Speech }
        queue.clear()
        queue.addAll(retained)
        if (current is Speech) {
            current = null
            paused = false
            activeUtteranceId = ""
            activeUtteranceBaseOffset = 0
        }
        releaseVolumeSession()
        playNext()
    }

    private fun disableVoicePlayback() {
        voicePreparationGeneration++
        pendingVoiceKeys.clear()
        val retained = queue.filterNot { it is VoiceSpeech }
        queue.clear()
        queue.addAll(retained)
        if (current is VoiceSpeech) {
            current = null
            paused = false
            cancelVoiceTimeout()
            WeChatApis.media()?.voices()?.stopOriginalPlayback()
            playNext()
        }
    }

    private fun configureTts(): String? {
        val engine = tts ?: return "文字转语音引擎未初始化"
        val languageStatus = runCatching { engine.setLanguage(Locale.CHINA) }
            .getOrElse {
                HLog.e("$TAG 设置中文语音失败: ${it.message}", it)
                return "所选文字转语音引擎设置中文语音失败"
            }
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA ||
            languageStatus == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return "所选文字转语音引擎缺少中文语音数据或不支持中文"
        }
        val voiceName = settings().voiceName()
        if (voiceName.isNotBlank()) {
            val selectedVoice = runCatching {
                engine.voices.orEmpty().firstOrNull { it.name == voiceName }
            }.getOrNull() ?: return "所选播报角色已不可用: $voiceName"
            val voiceStatus = runCatching { engine.setVoice(selectedVoice) }
                .getOrElse {
                    HLog.e("$TAG 设置播报角色失败: voice=$voiceName, error=${it.message}", it)
                    return "所选播报角色设置失败: $voiceName"
                }
            if (voiceStatus != TextToSpeech.SUCCESS) {
                return "所选播报角色设置失败: $voiceName"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                mainHandler.post { completeUtterance(utteranceId.orEmpty()) }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                mainHandler.post { updateSpeechPosition(utteranceId.orEmpty(), start) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mainHandler.post { completeUtterance(utteranceId.orEmpty()) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                mainHandler.post { completeUtterance(utteranceId.orEmpty()) }
            }
        })
        return null
    }

    private fun playNext() {
        if (paused || current != null) return
        val next = queue.peekFirst() ?: run {
            releaseVolumeSession()
            return
        }
        when (next) {
            is Speech -> {
                if (!ttsReady) {
                    ensureTts()
                    return
                }
                queue.pollFirst()
                current = next
                if (!speakCurrent()) {
                    current = null
                    playNext()
                }
            }
            is VoiceSpeech -> {
                queue.pollFirst()
                current = next
                if (!playCurrentVoice(next)) {
                    current = null
                    playNext()
                }
            }
        }
    }

    private fun speakCurrent(): Boolean {
        val engine = tts ?: return false
        val speech = current as? Speech ?: return false
        activateVolumeSession()
        val baseOffset = speech.resumeOffset.coerceIn(0, speech.text.length)
        val remainingText = speech.text.substring(baseOffset)
        if (remainingText.isEmpty()) return false
        activeUtteranceBaseOffset = baseOffset
        val utteranceId = "hchat_text_speech_${++sequence}"
        activeUtteranceId = utteranceId
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                engine.speak(
                    remainingText,
                    TextToSpeech.QUEUE_FLUSH,
                    Bundle().apply {
                        putString(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
                    },
                    utteranceId
                )
            } else {
                @Suppress("DEPRECATION")
                engine.speak(
                    remainingText,
                    TextToSpeech.QUEUE_FLUSH,
                    HashMap<String, String>().apply {
                        put(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC.toString())
                        put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    }
                )
            }
        }.getOrElse {
            HLog.e("$TAG 播报失败: ${it.message}", it)
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) activeUtteranceId = ""
        return result != TextToSpeech.ERROR
    }

    private fun completeUtterance(utteranceId: String) {
        if (utteranceId.isEmpty() || utteranceId != activeUtteranceId || paused) return
        if (current !is Speech) return
        activeUtteranceId = ""
        activeUtteranceBaseOffset = 0
        current = null
        playNext()
    }

    private fun updateSpeechPosition(utteranceId: String, start: Int) {
        if (paused || utteranceId.isEmpty() || utteranceId != activeUtteranceId) return
        val speech = current as? Speech ?: return
        speech.resumeOffset = (activeUtteranceBaseOffset + start).coerceIn(0, speech.text.length)
    }

    private fun playCurrentVoice(voice: VoiceSpeech): Boolean {
        val voiceApi = WeChatApis.media()?.voices() ?: return false
        activateVolumeSession()
        val started = voiceApi.playOriginal(
            voice.path,
            object : WeChatVoiceApi.PlaybackListener {
                override fun onCompletion() {
                    mainHandler.post { completeVoice(voice, null) }
                }

                override fun onError(message: String?) {
                    mainHandler.post { completeVoice(voice, message.orEmpty()) }
                }
            }
        )
        if (started) scheduleVoiceTimeout(voice)
        return started
    }

    private fun completeVoice(voice: VoiceSpeech, error: String?) {
        if (current !== voice) return
        cancelVoiceTimeout()
        if (!error.isNullOrBlank()) HLog.e("$TAG 原语音播放失败: $error")
        current = null
        paused = false
        playNext()
    }

    private fun scheduleVoiceTimeout(voice: VoiceSpeech) {
        cancelVoiceTimeout()
        val timeout = (voice.durationMillis.toLong() + VOICE_PLAYBACK_TIMEOUT_GRACE_MS)
            .coerceIn(MIN_VOICE_PLAYBACK_TIMEOUT_MS, MAX_VOICE_PLAYBACK_TIMEOUT_MS)
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (voiceTimeoutRunnable !== runnable || current !== voice || paused) return@Runnable
            voiceTimeoutRunnable = null
            HLog.e("$TAG 原语音播放完成回调超时，已跳过当前语音")
            WeChatApis.media()?.voices()?.stopOriginalPlayback()
            current = null
            playNext()
        }
        voiceTimeoutRunnable = runnable
        mainHandler.postDelayed(runnable, timeout)
    }

    private fun cancelVoiceTimeout() {
        voiceTimeoutRunnable?.let(mainHandler::removeCallbacks)
        voiceTimeoutRunnable = null
    }

    private fun pauseCurrent() {
        if (paused) return
        when (current) {
            is Speech -> {
                paused = true
                activeUtteranceId = ""
                runCatching { tts?.stop() }
                updateVolumeSessionState()
            }
            is VoiceSpeech -> {
                if (WeChatApis.media()?.voices()?.pauseOriginalPlayback() == true) {
                    paused = true
                    cancelVoiceTimeout()
                    updateVolumeSessionState()
                }
            }
            null -> Unit
        }
    }

    private fun resumeCurrent() {
        if (!paused || current == null) return
        when (val item = current) {
            is Speech -> {
                paused = false
                if (!speakCurrent()) {
                    current = null
                    playNext()
                }
            }
            is VoiceSpeech -> {
                if (WeChatApis.media()?.voices()?.resumeOriginalPlayback() == true) {
                    paused = false
                    scheduleVoiceTimeout(item)
                    updateVolumeSessionState()
                }
            }
            null -> Unit
        }
    }

    private fun skipCurrent() {
        if (!paused && current == null) return
        val skipped = current
        activeUtteranceId = ""
        activeUtteranceBaseOffset = 0
        paused = false
        current = null
        when (skipped) {
            is Speech -> runCatching { tts?.stop() }
            is VoiceSpeech -> {
                cancelVoiceTimeout()
                WeChatApis.media()?.voices()?.stopOriginalPlayback()
            }
            null -> Unit
        }
        playNext()
    }

    private fun stopAndClear() {
        voicePreparationGeneration++
        pendingVoiceKeys.clear()
        activeUtteranceId = ""
        activeUtteranceBaseOffset = 0
        paused = false
        consumedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
        resetTtsEngineCandidates()
        current = null
        queue.clear()
        runCatching { tts?.stop() }
        cancelVoiceTimeout()
        WeChatApis.media()?.voices()?.stopOriginalPlayback()
        releaseVolumeSession()
    }

    private fun restartTtsForEngineChange() {
        if (destroyed) return
        if (current is VoiceSpeech) {
            runCatching { tts?.stop() }
            safeShutdownTts()
            resetTtsEngineCandidates()
            return
        }
        val pending = current as? Speech
        current = null
        activeUtteranceId = ""
        activeUtteranceBaseOffset = 0
        paused = false
        consumedVolumeKey = KeyEvent.KEYCODE_UNKNOWN
        runCatching { tts?.stop() }
        releaseVolumeSession()
        safeShutdownTts()
        resetTtsEngineCandidates()
        if (pending != null) queue.addFirst(pending)
        if (queue.isNotEmpty() && settings().isEnabled()) playNext()
    }

    private fun safeShutdownTts() {
        ttsGeneration++
        cancelTtsInitTimeout()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        ttsInitializing = false
        activeUtteranceId = ""
        activeUtteranceBaseOffset = 0
    }

    private fun activateVolumeSession() {
        if (!settings().boolean(
                TextSpeechSettings.KEY_VOLUME_CONTROL,
                TextSpeechSettings.DEFAULT_VOLUME_CONTROL
            )
        ) {
            releaseVolumeSession()
            return
        }
        registerVolumeChangeReceiver()
        if (mediaSession == null) {
            val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
            val provider = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, maxVolume, currentVolume) {
                override fun onAdjustVolume(direction: Int) {
                    mainHandler.post { handleBackgroundVolume(direction) }
                }
            }
            volumeProvider = provider
            mediaSession = runCatching {
                MediaSession(appContext, MEDIA_SESSION_TAG).apply {
                    setFlags(
                        MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
                    )
                    setCallback(object : MediaSession.Callback() {}, mainHandler)
                    setPlaybackToRemote(provider)
                }
            }.getOrElse {
                volumeProvider = null
                HLog.e("$TAG 后台音量控制初始化失败: ${it.message}", it)
                null
            }
        }
        updateVolumeSessionState()
        mediaSession?.isActive = true
    }

    private fun updateVolumeSessionState() {
        // STATE_PAUSED is excluded from Android's background volume-session routing.
        val state = if (paused) PlaybackState.STATE_BUFFERING else PlaybackState.STATE_PLAYING
        runCatching {
            mediaSession?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT
                    )
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (paused) 0f else 1f)
                    .build()
            )
        }
    }

    private fun handleBackgroundVolume(direction: Int) {
        val currentSettings = settings()
        if (!currentSettings.isEnabled() || !currentSettings.boolean(
                TextSpeechSettings.KEY_VOLUME_CONTROL,
                TextSpeechSettings.DEFAULT_VOLUME_CONTROL
            ) || current == null
        ) {
            releaseVolumeSession()
            return
        }
        when {
            direction < 0 && paused -> skipCurrent()
            direction < 0 -> pauseCurrent()
            direction > 0 && paused -> resumeCurrent()
            direction > 0 -> adjustMusicVolumeUp()
        }
    }

    private fun adjustMusicVolumeUp() {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val nextVolume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + 1).coerceAtMost(maxVolume)
        runCatching {
            ignoredVolumeValue = nextVolume
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVolume, AudioManager.FLAG_SHOW_UI)
            volumeProvider?.setCurrentVolume(nextVolume)
        }
    }

    private fun registerVolumeChangeReceiver() {
        if (volumeChangeReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_VOLUME_CHANGED) return
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val value = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                val previous = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
                if (value < 0 || previous < 0 || value == previous) return
                if (ignoredVolumeValue == value) {
                    ignoredVolumeValue = null
                    return
                }
                mainHandler.post { handleSystemVolumeChange(value, previous) }
            }
        }
        val registered = runCatching {
            val filter = IntentFilter(ACTION_VOLUME_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(receiver, filter)
            }
            true
        }.getOrElse {
            HLog.e("$TAG 后台音量变化监听初始化失败: ${it.message}", it)
            false
        }
        if (registered) volumeChangeReceiver = receiver
    }

    private fun handleSystemVolumeChange(value: Int, previous: Int) {
        if (current == null || !settings().isEnabled() || !settings().boolean(
                TextSpeechSettings.KEY_VOLUME_CONTROL,
                TextSpeechSettings.DEFAULT_VOLUME_CONTROL
            )
        ) {
            releaseVolumeSession()
            return
        }
        volumeProvider?.setCurrentVolume(value)
        if (value > previous && !paused) return

        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        ignoredVolumeValue = previous
        runCatching { audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0) }
        volumeProvider?.setCurrentVolume(previous)
        when {
            value < previous && paused -> skipCurrent()
            value < previous -> pauseCurrent()
            value > previous && paused -> resumeCurrent()
        }
    }

    private fun unregisterVolumeChangeReceiver() {
        val receiver = volumeChangeReceiver ?: return
        volumeChangeReceiver = null
        ignoredVolumeValue = null
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun releaseVolumeSession() {
        val session = mediaSession
        mediaSession = null
        volumeProvider = null
        runCatching { session?.isActive = false }
        runCatching { session?.release() }
        unregisterVolumeChangeReceiver()
    }

    private fun resolveAnnouncementContext(
        message: WeChatMessageObserveApi.ObservedMessage,
        talker: String
    ): AnnouncementContext {
        val isGroup = message.group || message.isGroupChat ||
            talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")
        var senderId = message.sender.ifBlank { message.getSendTalker() }.trim()
        if (isGroup && (senderId.isEmpty() || senderId == talker || senderId.endsWith("@chatroom"))) {
            senderId = extractGroupSender(message.content.ifBlank { message.getContent() })
        }
        if (!isGroup) senderId = senderId.ifBlank { talker }

        val contacts = WeChatApis.contacts()
        val senderContact = senderId.takeIf { it.isNotBlank() }?.let { contacts?.getContact(it) }
        val senderNickname = senderContact?.nickname.orEmpty().ifBlank { senderId }
        val wechatId = senderContact?.customWxId.orEmpty()
        val remarkName = senderContact?.remarkName.orEmpty()
        val groupName = if (isGroup) {
            contacts?.getContact(talker)?.nickname.orEmpty()
                .ifBlank { contacts?.getDisplayName(talker).orEmpty() }
                .ifBlank { talker }
        } else {
            ""
        }
        val groupNickname = if (isGroup && senderId.isNotBlank()) {
            contacts?.getGroupMemberRoomDisplayName(talker, senderId).orEmpty()
                .ifBlank { senderNickname }
                .ifBlank { senderId }
        } else {
            ""
        }
        return AnnouncementContext(
            talker = talker,
            senderId = senderId,
            senderNickname = senderNickname,
            wechatId = wechatId,
            remarkName = remarkName,
            groupNickname = groupNickname,
            groupName = groupName,
            isGroup = isGroup,
            messageTime = normalizeMessageTime(message.createTime)
        )
    }

    private fun renderAnnouncement(
        settings: TextSpeechSettings,
        context: AnnouncementContext,
        content: String,
        messageType: String,
        voiceDurationMillis: Int
    ): String {
        if (!settings.boolean(
                TextSpeechSettings.KEY_ANNOUNCE_SENDER,
                TextSpeechSettings.DEFAULT_ANNOUNCE_SENDER
            )
        ) {
            return content
        }
        val senderName = if (context.isGroup) context.groupNickname else context.senderNickname
        val conversationName = if (context.isGroup) context.groupName else context.senderNickname
        val source = if (context.isGroup) {
            when {
                context.groupName.isNotBlank() && senderName.isNotBlank() ->
                    "${context.groupName}里的${senderName}说"
                senderName.isNotBlank() -> "${senderName}说"
                else -> context.groupName
            }
        } else {
            senderName.takeIf { it.isNotBlank() }?.let { "${it}发了一条消息说" }.orEmpty()
        }
        val replacements = linkedMapOf(
            TextSpeechSettings.VAR_SENDER_NICKNAME to context.senderNickname,
            TextSpeechSettings.VAR_WECHAT_NICKNAME to context.senderNickname,
            TextSpeechSettings.VAR_WECHAT_ID to context.wechatId,
            TextSpeechSettings.VAR_REMARK_NAME to context.remarkName,
            TextSpeechSettings.VAR_GROUP_NICKNAME to context.groupNickname,
            TextSpeechSettings.VAR_GROUP_NAME to context.groupName,
            TextSpeechSettings.VAR_CONVERSATION_NAME to conversationName,
            TextSpeechSettings.VAR_ANNOUNCEMENT_SOURCE to source,
            TextSpeechSettings.VAR_MESSAGE_CONTENT to content,
            TextSpeechSettings.VAR_MESSAGE_TYPE to messageType,
            TextSpeechSettings.VAR_VOICE_DURATION to formatVoiceDuration(voiceDurationMillis),
            TextSpeechSettings.VAR_MESSAGE_TIME to TextSpeechSettings.formatTime(
                context.messageTime,
                settings.timeFormat()
            ),
            TextSpeechSettings.VAR_SENDER_ID to context.senderId,
            TextSpeechSettings.VAR_CONVERSATION_ID to context.talker
        )
        var result = settings.announcementTemplate()
        replacements.forEach { (token, value) -> result = result.replace(token, value) }
        return normalizeSpeechText(result)
    }

    private fun normalizeMessageTime(value: Long): Long {
        return when {
            value >= 100_000_000_000L -> value
            value > 0L -> value * 1000L
            else -> System.currentTimeMillis()
        }
    }

    private fun formatVoiceDuration(durationMillis: Int): String {
        if (durationMillis <= 0) return ""
        if (durationMillis % 1000 == 0) return "${durationMillis / 1000}秒"
        return String.format(Locale.CHINA, "%.1f秒", durationMillis / 1000f)
    }

    private fun normalizeSpeechText(value: String): String {
        return value.replace(Regex("\\s+"), " ").trim()
    }

    private fun normalizeText(value: String): String {
        val text = value.replace(GROUP_SENDER_PREFIX, "").trim()
        if (text.isEmpty() ||
            text.startsWith("<?xml", ignoreCase = true) ||
            text.startsWith("<msg", ignoreCase = true) ||
            text.startsWith("<appmsg", ignoreCase = true)
        ) {
            return ""
        }
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun extractGroupSender(value: String): String {
        return GROUP_SENDER_PREFIX.find(value.trim())?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun shouldSuppressByWechatDoNotDisturb(
        settings: TextSpeechSettings,
        talker: String
    ): Boolean {
        if (!settings.boolean(
                TextSpeechSettings.KEY_RESPECT_WECHAT_DND,
                TextSpeechSettings.DEFAULT_RESPECT_WECHAT_DND
            )
        ) {
            return false
        }
        return runCatching {
            WeChatApis.conversations()?.isWechatDoNotDisturb(talker) == true
        }.getOrDefault(false)
    }

    private fun isInQuietWindow(settings: TextSpeechSettings): Boolean {
        if (!settings.boolean(TextSpeechSettings.KEY_QUIET_ENABLE, TextSpeechSettings.DEFAULT_QUIET_ENABLE)) return false
        val start = parseMinute(settings.string(TextSpeechSettings.KEY_QUIET_START, TextSpeechSettings.DEFAULT_QUIET_START))
        val end = parseMinute(settings.string(TextSpeechSettings.KEY_QUIET_END, TextSpeechSettings.DEFAULT_QUIET_END))
        if (start < 0 || end < 0) return false
        if (start == end) return true
        val now = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
        return if (start < end) now in start until end else now >= start || now < end
    }

    private fun parseMinute(value: String): Int {
        val parts = value.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return -1
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return -1
        if (hour !in 0..23 || minute !in 0..59) return -1
        return hour * 60 + minute
    }

    companion object {
        private const val TAG = "[Hchat:TextSpeech]"
        private const val MESSAGE_TYPE_TEXT = "文字消息"
        private const val MESSAGE_TYPE_VOICE = "语音消息"
        private const val MAX_INIT_RETRIES = 1
        private const val INIT_RETRY_DELAY_MS = 800L
        private const val TTS_INIT_TIMEOUT_MS = 8_000L
        private const val INITIAL_VOICE_RESOLVE_DELAY_MS = 300L
        private const val VOICE_RESOLVE_INTERVAL_MS = 500L
        private const val MAX_VOICE_RESOLVE_ATTEMPTS = 120
        private const val VOICE_DATABASE_REFRESH_INTERVAL_ATTEMPTS = 4
        private const val MAX_VOICE_DIAGNOSTIC_VALUE_LENGTH = 160
        private const val MIN_VOICE_DURATION_MS = 1_000
        private const val DEFAULT_VOICE_DURATION_MS = 60_000
        private const val VOICE_PLAYBACK_TIMEOUT_GRACE_MS = 15_000L
        private const val MIN_VOICE_PLAYBACK_TIMEOUT_MS = 20_000L
        private const val MAX_VOICE_PLAYBACK_TIMEOUT_MS = 5 * 60_000L
        private const val MEDIA_SESSION_TAG = "HchatTextSpeech"
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        private val GROUP_SENDER_PREFIX = Regex("^([A-Za-z0-9_@.\\-]+?):\\n")
    }
}
