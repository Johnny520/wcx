package h.Hchat.hooks.items.textspeech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TextSpeechSettings(context: Context?) {
    private val prefs = context?.let { HchatStorage.preferences(it, PREFS_NAME) }

    fun isEnabled(): Boolean = boolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun boolean(key: String, defaultValue: Boolean): Boolean = runCatching {
        prefs?.getBoolean(key, defaultValue) ?: defaultValue
    }.getOrDefault(defaultValue)

    fun string(key: String, defaultValue: String): String = runCatching {
        prefs?.getString(key, defaultValue) ?: defaultValue
    }.getOrDefault(defaultValue)

    fun enginePackage(): String = string(KEY_TTS_ENGINE, DEFAULT_TTS_ENGINE).trim()

    fun voiceName(): String = string(KEY_TTS_VOICE, DEFAULT_TTS_VOICE).trim()

    fun announcementTemplate(): String = string(KEY_ANNOUNCEMENT_TEMPLATE, DEFAULT_ANNOUNCEMENT_TEMPLATE)

    fun timeFormat(): String = string(KEY_TIME_FORMAT, DEFAULT_TIME_FORMAT)

    fun allowedContacts(): Set<String> = parseStringSet(string(KEY_ALLOWED_CONTACTS, ""))

    fun saveAllowedContacts(values: Set<String>) {
        prefs?.edit()?.putString(KEY_ALLOWED_CONTACTS, encodeStringSet(values))?.apply()
    }

    companion object {
        const val PREFS_NAME = "Hchat_text_speech_config"

        const val KEY_ENABLE = "text_speech_enable"
        const val KEY_TTS_ENGINE = "text_speech_tts_engine"
        const val KEY_TTS_VOICE = "text_speech_tts_voice"
        const val KEY_PLAY_VOICE_MESSAGES = "text_speech_play_voice_messages"
        const val KEY_VOLUME_CONTROL = "text_speech_volume_control"
        const val KEY_ANNOUNCE_SENDER = "text_speech_announce_sender"
        const val KEY_ANNOUNCEMENT_TEMPLATE = "text_speech_announcement_template"
        const val KEY_TIME_FORMAT = "text_speech_time_format"
        const val KEY_RESPECT_WECHAT_DND = "text_speech_respect_wechat_dnd"
        const val KEY_QUIET_ENABLE = "text_speech_quiet_enable"
        const val KEY_QUIET_START = "text_speech_quiet_start"
        const val KEY_QUIET_END = "text_speech_quiet_end"
        const val KEY_ALLOWED_CONTACTS = "text_speech_allowed_contacts"

        const val DEFAULT_ENABLE = false
        const val DEFAULT_TTS_ENGINE = ""
        const val DEFAULT_TTS_VOICE = ""
        const val DEFAULT_PLAY_VOICE_MESSAGES = false
        const val DEFAULT_VOLUME_CONTROL = false
        const val DEFAULT_ANNOUNCE_SENDER = false
        const val DEFAULT_ANNOUNCEMENT_TEMPLATE = "{播报来源}{消息正文}"
        const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"
        const val DEFAULT_RESPECT_WECHAT_DND = true
        const val DEFAULT_QUIET_ENABLE = false
        const val DEFAULT_QUIET_START = "23:00"
        const val DEFAULT_QUIET_END = "08:00"

        const val VAR_SENDER_NICKNAME = "{发送者昵称}"
        const val VAR_WECHAT_NICKNAME = "{微信昵称}"
        const val VAR_WECHAT_ID = "{发送者微信号}"
        const val VAR_REMARK_NAME = "{备注}"
        const val VAR_GROUP_NICKNAME = "{群内昵称}"
        const val VAR_GROUP_NAME = "{群聊名称}"
        const val VAR_CONVERSATION_NAME = "{会话名称}"
        const val VAR_ANNOUNCEMENT_SOURCE = "{播报来源}"
        const val VAR_MESSAGE_CONTENT = "{消息正文}"
        const val VAR_MESSAGE_TYPE = "{消息类型}"
        const val VAR_VOICE_DURATION = "{语音时长}"
        const val VAR_MESSAGE_TIME = "{消息时间}"
        const val VAR_SENDER_ID = "{发送者ID}"
        const val VAR_CONVERSATION_ID = "{会话ID}"

        @JvmStatic
        fun normalizeTimeFormat(value: String?): String {
            return value.orEmpty().trim().ifBlank { DEFAULT_TIME_FORMAT }
        }

        @JvmStatic
        fun isValidTimeFormat(value: String?): Boolean {
            return runCatching {
                SimpleDateFormat(normalizeTimeFormat(value), Locale.getDefault())
            }.isSuccess
        }

        @JvmStatic
        fun formatTime(value: Long, format: String?): String {
            val timestamp = value.takeIf { it > 0L } ?: System.currentTimeMillis()
            return runCatching {
                SimpleDateFormat(normalizeTimeFormat(format), Locale.getDefault()).format(Date(timestamp))
            }.getOrElse {
                SimpleDateFormat(DEFAULT_TIME_FORMAT, Locale.getDefault()).format(Date(timestamp))
            }
        }

        @JvmStatic
        fun parseStringSet(value: String?): Set<String> {
            if (value.isNullOrBlank()) return emptySet()
            return runCatching {
                val array = JSONArray(value)
                buildSet {
                    for (index in 0 until array.length()) {
                        val id = array.optString(index).trim()
                        if (id.isNotEmpty()) add(id)
                    }
                }
            }.getOrDefault(emptySet())
        }

        @JvmStatic
        fun encodeStringSet(values: Set<String>): String {
            return JSONArray().apply {
                values.asSequence()
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
                    .forEach { put(it) }
            }.toString()
        }
    }
}

data class TextSpeechEngineOption(
    val packageName: String,
    val label: String
)

data class TextSpeechVoiceOption(
    val name: String,
    val label: String
)

data class TextSpeechVoiceLoadResult(
    val options: List<TextSpeechVoiceOption>,
    val error: String = "",
    val activeEnginePackage: String = "",
    val usedFallback: Boolean = false
)

class TextSpeechVoiceLoadHandle internal constructor(
    private val cancelAction: () -> Unit
) {
    fun cancel() = cancelAction()
}

object TextSpeechEngineCatalog {
    fun installed(context: Context): List<TextSpeechEngineOption> {
        return runCatching {
            val packageManager = context.packageManager
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val services = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentServices(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
            }
            services.asSequence()
                .mapNotNull { resolveInfo ->
                    val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                    val packageName = serviceInfo.packageName?.trim().orEmpty()
                    if (packageName.isEmpty() ||
                        !serviceInfo.enabled ||
                        !serviceInfo.exported ||
                        serviceInfo.applicationInfo?.enabled == false
                    ) {
                        return@mapNotNull null
                    }
                    val label = runCatching { resolveInfo.loadLabel(packageManager)?.toString()?.trim() }
                        .getOrNull()
                        .orEmpty()
                        .ifBlank { packageName }
                    TextSpeechEngineOption(packageName, label)
                }
                .distinctBy(TextSpeechEngineOption::packageName)
                .sortedWith(
                    Comparator { left, right ->
                        val labelOrder = String.CASE_INSENSITIVE_ORDER.compare(left.label, right.label)
                        if (labelOrder != 0) {
                            labelOrder
                        } else {
                            String.CASE_INSENSITIVE_ORDER.compare(left.packageName, right.packageName)
                        }
                    }
                )
                .toList()
        }.getOrDefault(emptyList())
    }

    fun systemDefaultPackage(context: Context): String = runCatching {
        Settings.Secure.getString(context.contentResolver, TTS_DEFAULT_SYNTH_SETTING)
            .orEmpty()
            .trim()
    }.getOrDefault("")

    fun connectionCandidates(context: Context, requestedPackage: String): List<String> {
        val requested = requestedPackage.trim()
        if (requested.isNotEmpty()) return listOf(requested)
        val installed = installed(context)
        val installedPackages = installed.mapTo(hashSetOf()) { it.packageName }
        return buildList {
            add("")
            systemDefaultPackage(context)
                .takeIf { it.isNotBlank() && it in installedPackages }
                ?.let(::add)
            installed.forEach { add(it.packageName) }
        }.distinct()
    }

    fun unavailableReason(context: Context): String {
        val secondaryUser = android.os.Process.myUid() / ANDROID_UID_PER_USER > 0
        return if (secondaryUser) {
            "微信分身所在用户未安装或未启用可用 TTS 引擎"
        } else {
            "当前用户未安装或未启用可用 TTS 引擎"
        }
    }

    private const val TTS_DEFAULT_SYNTH_SETTING = "tts_default_synth"
    private const val ANDROID_UID_PER_USER = 100_000
}

object TextSpeechVoiceCatalog {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(
        context: Context,
        enginePackage: String,
        callback: (TextSpeechVoiceLoadResult) -> Unit
    ): TextSpeechVoiceLoadHandle {
        val appContext = context.applicationContext ?: context
        val cancelled = AtomicBoolean(false)
        val candidates = TextSpeechEngineCatalog.connectionCandidates(appContext, enginePackage)
        val systemDefault = TextSpeechEngineCatalog.systemDefaultPackage(appContext)
        val failedAttempts = arrayListOf<String>()
        var engine: TextToSpeech? = null
        var candidateIndex = 0
        var generation = 0L
        var timeoutRunnable: Runnable? = null

        fun shutdownEngine() {
            timeoutRunnable?.let(mainHandler::removeCallbacks)
            timeoutRunnable = null
            val target = engine
            engine = null
            runCatching { target?.shutdown() }
        }

        fun finish(result: TextSpeechVoiceLoadResult) {
            generation++
            shutdownEngine()
            if (!cancelled.get()) callback(result)
        }

        lateinit var startNext: () -> Unit

        fun finishFailure() {
            val error = if (enginePackage.isNotBlank()) {
                "所选 TTS 引擎初始化失败：$enginePackage"
            } else if (TextSpeechEngineCatalog.installed(appContext).isEmpty()) {
                TextSpeechEngineCatalog.unavailableReason(appContext)
            } else {
                "系统默认及其它可用 TTS 引擎均初始化失败"
            }
            val detail = failedAttempts.takeLast(3).joinToString("；")
            finish(TextSpeechVoiceLoadResult(emptyList(), if (detail.isBlank()) error else "$error（$detail）"))
        }

        fun failAttempt(candidate: String, reason: String, attemptGeneration: Long) {
            if (attemptGeneration != generation || cancelled.get()) return
            generation++
            failedAttempts += "${candidate.ifBlank { "系统默认" }}：$reason"
            shutdownEngine()
            if (candidateIndex < candidates.size) {
                mainHandler.postDelayed({ startNext() }, RETRY_DELAY_MS)
            } else {
                finishFailure()
            }
        }

        startNext = startNext@{
            if (cancelled.get()) return@startNext
            val candidate = candidates.getOrNull(candidateIndex++)
            if (candidate == null) {
                finishFailure()
                return@startNext
            }
            val attemptGeneration = ++generation
            val listener = TextToSpeech.OnInitListener { status ->
                mainHandler.post callback@{
                    if (cancelled.get() || attemptGeneration != generation) return@callback
                    val target = engine
                    if (status != TextToSpeech.SUCCESS || target == null) {
                        failAttempt(candidate, "状态 $status", attemptGeneration)
                        return@callback
                    }
                    val activeEngine = (KavaReflector.readField(target, "mCurrentEngine") as? String)
                        .orEmpty()
                        .trim()
                    if (candidate.isNotBlank() && activeEngine.isNotBlank() && activeEngine != candidate) {
                        failAttempt(candidate, "系统回退到 $activeEngine", attemptGeneration)
                        return@callback
                    }
                    val options = runCatching { voiceOptions(target) }.getOrElse {
                        failAttempt(candidate, it.message ?: "读取角色失败", attemptGeneration)
                        return@callback
                    }
                    val resolvedEngine = activeEngine.ifBlank { candidate }.ifBlank { systemDefault }
                    val usedFallback = enginePackage.isBlank() &&
                        candidate.isNotBlank() &&
                        candidate != systemDefault
                    finish(
                        TextSpeechVoiceLoadResult(
                            options = options,
                            activeEnginePackage = resolvedEngine,
                            usedFallback = usedFallback
                        )
                    )
                }
            }
            engine = runCatching {
                if (candidate.isBlank()) {
                    TextToSpeech(appContext, listener)
                } else {
                    TextToSpeech(appContext, listener, candidate)
                }
            }.getOrElse {
                failAttempt(candidate, it.message ?: "创建失败", attemptGeneration)
                null
            }
            if (engine != null && attemptGeneration == generation) {
                Runnable {
                    failAttempt(candidate, "连接超时", attemptGeneration)
                }.also { runnable ->
                    timeoutRunnable = runnable
                    mainHandler.postDelayed(runnable, INIT_TIMEOUT_MS)
                }
            }
        }

        mainHandler.post { startNext() }

        return TextSpeechVoiceLoadHandle {
            if (cancelled.compareAndSet(false, true)) {
                mainHandler.post {
                    generation++
                    shutdownEngine()
                }
            }
        }
    }

    private fun voiceOptions(engine: TextToSpeech): List<TextSpeechVoiceOption> {
        val all = engine.voices.orEmpty()
            .asSequence()
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name }
            .toList()
        val chinese = all.filter {
            it.locale?.language.equals(Locale.CHINESE.language, ignoreCase = true)
        }
        return (chinese.ifEmpty { all })
            .sortedWith(
                Comparator { left, right ->
                    val localeOrder = String.CASE_INSENSITIVE_ORDER.compare(
                        left.locale?.toLanguageTag().orEmpty(),
                        right.locale?.toLanguageTag().orEmpty()
                    )
                    if (localeOrder != 0) {
                        localeOrder
                    } else {
                        String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name)
                    }
                }
            )
            .map { voice ->
                val localeName = voice.locale
                    ?.getDisplayName(Locale.SIMPLIFIED_CHINESE)
                    .orEmpty()
                    .ifBlank { voice.locale?.toLanguageTag().orEmpty() }
                val source = if (voice.isNetworkConnectionRequired) "联网" else "本地"
                val detail = listOf(localeName, source)
                    .filter(String::isNotBlank)
                    .joinToString(" · ")
                TextSpeechVoiceOption(
                    name = voice.name,
                    label = if (detail.isEmpty()) voice.name else "${voice.name}（$detail）"
                )
            }
    }

    private const val RETRY_DELAY_MS = 300L
    private const val INIT_TIMEOUT_MS = 8_000L
}
