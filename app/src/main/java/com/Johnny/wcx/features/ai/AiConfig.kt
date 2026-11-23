package com.Johnny.wcx.features.ai

import androidx.compose.runtime.mutableStateOf
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 全局 AI 配置（OpenAI 兼容接口）。
 *
 * 模块里所有 AI 功能（AI 自动回复 / 长按智能回复 …）**只读这一份配置**，
 * 不再各自维护 API 地址、Key、模型。设置入口只有主页「AI」卡片一个。
 *
 * 状态判定（唯一的「已激活」口径，[Status.verified]）：
 *  - 配置齐全（地址 / Key / 模型都有值）**且**最近一次真实请求成功 → 已激活；
 *  - 任何一次真实调用失败（非 2xx、超时、DNS、解析失败…）→ 立即置为未激活并记录原因；
 *  - 保存配置时必须先做一次真实连通性校验（[verify]），校验不通过**不落库**。
 *
 * 存储：MMKV `wekit_prefs`，键见 [KEY_URL] 等。旧版本分功能存储的
 * `ai_reply_api_*` / `smart_reply_api_*` 会在首次读取时自动迁移过来，
 * 迁移后不再写入旧键（保留它们只是为了回退旧版本时配置还在）。
 */
object AiConfig {

    private const val TAG = "AiConfig"

    // ── 全局配置键：所有 AI 功能共用 ────────────────────────────────────────
    const val KEY_URL = "ai_global_api_url"
    const val KEY_API_KEY = "ai_global_api_key"
    const val KEY_MODEL = "ai_global_model"
    private const val KEY_ACTIVE = "ai_global_active"
    private const val KEY_DETAIL = "ai_global_status_detail"
    private const val KEY_CHECKED_AT = "ai_global_checked_at"
    private const val KEY_MIGRATED = "ai_global_migrated"

    /**
     * 深度思考开关。与 API 地址 / Key / 模型分开存：
     * 它只是「这次请求要不要带思考参数」，切换不需要重新做连通性校验。
     */
    const val KEY_THINKING = "ai_global_thinking"

    /**
     * 思考强度。id 直接作为 `reasoning_effort` 下发，四档与 OpenAI 的取值一致
     * （low / medium / high / max）。
     *
     * 存 id 而不是序号：序号会随枚举增删错位。
     */
    enum class ThinkingLevel(val id: String, val label: String) {
        LOW("low", "低"),
        MEDIUM("medium", "中"),
        HIGH("high", "高"),
        MAX("max", "极限");

        companion object {
            fun of(id: String?): ThinkingLevel = values().firstOrNull { it.id == id } ?: HIGH
        }
    }

    /**
     * 输出上限与说话温度。
     *
     * `max_tokens` 默认给一个很大的值（393216）：它只是把服务商的默认上限抬高，不会让请求变贵；
     * 服务商不认这个值时 [chatWithTools] 会去掉它重试，所以填了也不会把链路打死。
     *
     * 温度只作用于**说话类**请求（建议 / 自动回复）；整理、审查、侧写汇总各自传固定低温度，
     * 让「分类判断」稳定，不随用户调「说话友好度」一起漂。
     *
     * 输入不设上限：协议里没有这个字段，输入规模由我们自己的切片控制。
     */
    const val KEY_MAX_TOKENS = "ai_global_max_tokens_v2"
    const val KEY_TEMPERATURE = "ai_global_temperature"
    const val KEY_THINKING_EFFORT = "ai_global_thinking_effort"

    /**
     * 是否把聊天里的图片传给模型。
     *
     * 关着时图片只显示 `[图片]` 占位；打开后把**对方这一轮最近一张**已经落到磁盘的图片
     * 附在 user 消息里，模型能看懂图并据此回复。默认关：识图要模型支持视觉输入，
     * 而且每张图会多几百 token。
     */
    const val KEY_RECOGNIZE_IMAGES = "ai_recognize_images"

    /** 默认输出上限：393216。服务商支持多少就是多少，超出它会自己报错，我们降级重试。 */
    const val DEFAULT_MAX_TOKENS = 393_216

    /** 默认温度：0.9，句子更活络，贴近日常说话。 */
    const val DEFAULT_TEMPERATURE = 0.9

    /** 默认思考强度：高。 */
    const val DEFAULT_THINKING_EFFORT = "high"

    /**
     * 双击提示音：开关 + 音色。
     *
     * 音色存 **id 字符串**而不是编号：编号会随枚举增删而错位（「水泡」被删掉时，
     * 存着 2 的用户会从「低沉」掉回别的音色）。旧键 [KEY_CUE_STYLE] 只在没写过 id 时读一次。
     */
    const val KEY_CUE_SOUND = "ai_global_cue_sound"
    const val KEY_CUE_STYLE = "ai_global_cue_style"
    const val KEY_CUE_STYLE_NAME = "ai_global_cue_style_name"

    /**
     * 双击手势总开关（默认开）。
     *
     * 关掉后聊天页双击完全不被识别 —— 手势是每条触摸事件都要跑的分发路径，
     * 关掉就该彻底不挂 hook，而不是挂着再判断。
     */
    const val KEY_SUGGEST_GESTURE = "ai_suggest_gesture"

    /**
     * 自动回复的静默延迟（秒）：对方最后一条消息之后等这么久没新消息才回。
     *
     * 六档固定值（3/5/8/12/20/30），默认 8 秒 —— 短了会抢话，长了回得慢。
     */
    const val KEY_REPLY_DELAY = "ai_reply_delay_seconds"

    const val DEFAULT_REPLY_DELAY_SECONDS = 8

    val REPLY_DELAY_OPTIONS = intArrayOf(3, 5, 8, 12, 20, 30)

    /** 音色 id。与 `AiCueSound.Style` 一一对应。 */
    const val CUE_STYLE_WOOD = "wood"
    const val CUE_STYLE_DEEP = "deep"

    const val DEFAULT_URL = "https://api.deepseek.com/chat/completions"
    const val DEFAULT_MODEL = "deepseek-chat"

    /**
     * 旧版本的分功能配置键，顺序与 [LEGACY_URL_KEYS] / [LEGACY_MODEL_KEYS] 对应。
     * 只在「全局配置为空」时作为迁移来源，之后不再写入。
     */
    private val LEGACY_URL_KEYS = listOf("ai_reply_api_url", "smart_reply_api_url")
    private val LEGACY_KEY_KEYS = listOf("ai_reply_api_key", "smart_reply_api_key")
    private val LEGACY_MODEL_KEYS = listOf("ai_reply_model", "smart_reply_model")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 偏好是否可读。MMKV 只在微信进程由 `NativeLoader.init()` 初始化；
     * 模块自己的 APK 进程读不到（会抛 `You should Call MMKV.initialize() first.`），
     * 这里做一次探测后缓存，避免每次读状态都抛异常。
     */
    private val prefsAvailable: Boolean by lazy { runCatching { WePrefs.default }.isSuccess }

    /**
     * 卡片 / 各功能共享的状态快照。用 Compose 的 [mutableStateOf] 保存：
     * 设置页与 AI 功能通常同处微信进程，[markActive] / [markInactive] 一写就能让卡片立刻重绘。
     * 跨进程时由设置页在 onResume 调 [refreshStatus] 重新读盘。
     */
    data class Status(
        val configured: Boolean,
        val active: Boolean,
        val model: String,
        val detail: String?,
        val checkedAt: Long,
        /** false 表示当前进程读不到偏好（配置不可用），UI 需要换一种文案。 */
        val readable: Boolean = true,
        /** 深度思考开关；默认关。 */
        val thinking: Boolean = false,
    ) {
        /** 唯一判定：配置齐全 + 最近一次真实请求成功。 */
        val verified: Boolean get() = readable && configured && active
    }

    private val _status = mutableStateOf(
        Status(
            configured = false,
            active = false,
            model = "",
            detail = null,
            checkedAt = 0L,
            readable = true,
        )
    )

    /** Compose 可观察的状态；直接 `val s = AiConfig.status` 即可订阅。 */
    val status: Status get() = _status.value

    init {
        // 类加载时读一次，让主页卡片第一次组合就有正确值。
        runCatching { refreshStatus() }
    }

    // ── 配置读取 ─────────────────────────────────────────────────────────────

    private fun readString(key: String): String =
        if (!prefsAvailable) "" else runCatching { WePrefs.getStringOrDef(key, "") }.getOrDefault("")

    /** 用户在对话框里填的原始地址（未配置时为空，界面可回落到 [DEFAULT_URL]）。 */
    val rawUrl: String get() = readString(KEY_URL)

    val rawApiKey: String get() = readString(KEY_API_KEY)

    val rawModel: String get() = readString(KEY_MODEL)

    /** 可直接用于请求的 chat/completions 地址。 */
    val chatUrl: String
        get() = normalizeChatUrl(rawUrl.ifBlank { DEFAULT_URL })

    val apiKey: String get() = rawApiKey.trim()

    val model: String get() = rawModel.trim().ifBlank { DEFAULT_MODEL }

    /** 配置是否齐全（不等于「已激活」——是否连通由 [status] 决定）。 */
    val isConfigured: Boolean
        get() = prefsAvailable && runCatching {
            filled(
                WePrefs.getString(KEY_URL).orEmpty(),
                WePrefs.getString(KEY_API_KEY).orEmpty(),
                WePrefs.getString(KEY_MODEL).orEmpty(),
            )
        }.getOrDefault(false)

    /** 「配置齐全」的唯一判定：三个字段都非空。连通性另由 [status] 决定。 */
    private fun filled(url: String, key: String, model: String): Boolean =
        url.isNotBlank() && key.isNotBlank() && model.isNotBlank()

    /**
     * 深度思考是否开启。它是请求参数，不参与「已激活」判定 ——
     * 关掉思考不影响接口是否连通，打开也不会让一个连不通的配置变可用。
     */
    val isThinkingEnabled: Boolean
        get() = prefsAvailable && runCatching { WePrefs.getBoolOrFalse(KEY_THINKING) }
            .getOrDefault(false)

    /** 切换深度思考。即时生效（下一次请求就带上），不需要重新校验接口。 */
    fun setThinking(enabled: Boolean) {
        if (!prefsAvailable) return
        runCatching { WePrefs.putBool(KEY_THINKING, enabled) }
            .onFailure { WeLogger.e(TAG, "save thinking failed", it) }
        refreshStatus()
        WeLogger.i(TAG, "深度思考 -> ${if (enabled) "开 · ${thinkingLevel.id}" else "关"}")
    }

    /** 思考强度。只在思考打开时下发；服务商不认这个取值时 [chatWithTools] 会去掉它重试。 */
    val thinkingLevel: ThinkingLevel
        get() {
            if (!prefsAvailable) return ThinkingLevel.of(DEFAULT_THINKING_EFFORT)
            val saved = runCatching { WePrefs.getStringOrDef(KEY_THINKING_EFFORT, "") }
                .getOrDefault("")
            return ThinkingLevel.of(saved.ifBlank { DEFAULT_THINKING_EFFORT })
        }

    fun setThinkingLevel(level: ThinkingLevel) {
        if (!prefsAvailable) return
        runCatching { WePrefs.putString(KEY_THINKING_EFFORT, level.id) }
            .onFailure { WeLogger.e(TAG, "save thinking level failed", it) }
        WeLogger.i(TAG, "思考强度 -> ${level.id}（${level.label}）")
    }

    /**
     * 单次响应的输出上限（`max_tokens`）。0 = 不传。
     *
     * 默认 393216：它只抬高服务商的默认上限，不会让请求变贵。填得比服务商支持的大时，
     * [chatWithTools] 会去掉它重试，所以不需要用户先查服务商文档。
     *
     * **键名带 `_v2`**：上一版默认是 0，只要用户点过那个输入框就会把 0 写进存储，
     * 而 `getIntOrDef` 分不出「没设置过」和「显式设成 0」—— 换一个键，被污染的旧值自然失效。
     */
    val maxTokens: Int
        get() {
            if (!prefsAvailable) return DEFAULT_MAX_TOKENS
            if (!runCatching { WePrefs.containsKey(KEY_MAX_TOKENS) }.getOrDefault(false)) {
                return DEFAULT_MAX_TOKENS
            }
            return runCatching { WePrefs.getIntOrDef(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS) }
                .getOrDefault(DEFAULT_MAX_TOKENS)
        }

    fun setMaxTokens(value: Int) {
        if (!prefsAvailable) return
        val safe = value.coerceIn(0, 4_000_000)
        runCatching { WePrefs.putInt(KEY_MAX_TOKENS, safe) }
            .onFailure { WeLogger.e(TAG, "save maxTokens failed", it) }
        WeLogger.i(TAG, "输出上限 -> ${if (safe == 0) "不传" else safe.toString()}")
    }

    /**
     * 说话类请求的温度。0~2：低更稳定板正，高更活络。默认 0.9。
     *
     * 存字符串而不是 float：MMKV 的 float 精度在不同版本上不一致，0.9 偶尔读成 0.8999999。
     */
    val temperature: Double
        get() {
            if (!prefsAvailable) return DEFAULT_TEMPERATURE
            val saved = runCatching { WePrefs.getStringOrDef(KEY_TEMPERATURE, "") }
                .getOrDefault("")
            return saved.toDoubleOrNull()?.takeIf { it in 0.0..2.0 } ?: DEFAULT_TEMPERATURE
        }

    fun setTemperature(value: Double) {
        if (!prefsAvailable) return
        val safe = value.coerceIn(0.0, 2.0)
        runCatching { WePrefs.putString(KEY_TEMPERATURE, safe.toString()) }
            .onFailure { WeLogger.e(TAG, "save temperature failed", it) }
        WeLogger.i(TAG, "温度 -> $safe")
    }

    /** 是否让模型看聊天里的图片。默认关。 */
    val recognizeImages: Boolean
        get() = prefsAvailable && runCatching { WePrefs.getBoolOrDef(KEY_RECOGNIZE_IMAGES, false) }
            .getOrDefault(false)

    fun setRecognizeImages(enabled: Boolean) {
        if (!prefsAvailable) return
        runCatching { WePrefs.putBool(KEY_RECOGNIZE_IMAGES, enabled) }
            .onFailure { WeLogger.e(TAG, "save recognizeImages failed", it) }
        WeLogger.i(TAG, "识别图片 -> ${if (enabled) "开" else "关"}")
    }

    /** 双击提示音是否开启。默认开 —— 这是双击手势唯一的即时反馈。 */
    val isCueSoundEnabled: Boolean
        get() = prefsAvailable && runCatching { WePrefs.getBoolOrDef(KEY_CUE_SOUND, true) }
            .getOrDefault(true)

    /** 双击建议手势是否启用（默认开，未初始化存储时也视为开）。 */
    val isSuggestGestureEnabled: Boolean
        get() {
            if (!prefsAvailable) return true
            return runCatching { WePrefs.getBoolOrDef(KEY_SUGGEST_GESTURE, true) }.getOrDefault(true)
        }

    fun setSuggestGestureEnabled(enabled: Boolean) {
        if (!prefsAvailable) return
        runCatching { WePrefs.putBool(KEY_SUGGEST_GESTURE, enabled) }
            .onFailure { WeLogger.e(TAG, "save suggest gesture failed", it) }
        WeLogger.i(TAG, "双击建议 -> ${if (enabled) "开" else "关"}")
    }

    /** 自动回复延迟（秒），不在六档内时回落到默认 8 秒。 */
    val replyDelaySeconds: Int
        get() {
            if (!prefsAvailable) return DEFAULT_REPLY_DELAY_SECONDS
            val saved = runCatching { WePrefs.getIntOrDef(KEY_REPLY_DELAY, DEFAULT_REPLY_DELAY_SECONDS) }
                .getOrDefault(DEFAULT_REPLY_DELAY_SECONDS)
            return if (saved in REPLY_DELAY_OPTIONS) saved else DEFAULT_REPLY_DELAY_SECONDS
        }

    /** 自动回复延迟（毫秒），直接给静默窗口用。 */
    val replyDelayMs: Long get() = replyDelaySeconds * 1000L

    fun setReplyDelaySeconds(seconds: Int) {
        if (!prefsAvailable) return
        val value = if (seconds in REPLY_DELAY_OPTIONS) seconds else DEFAULT_REPLY_DELAY_SECONDS
        runCatching { WePrefs.putInt(KEY_REPLY_DELAY, value) }
            .onFailure { WeLogger.e(TAG, "save reply delay failed", it) }
        WeLogger.i(TAG, "自动回复延迟 -> ${value}s")
    }

    fun setCueSoundEnabled(enabled: Boolean) {
        if (!prefsAvailable) return
        runCatching { WePrefs.putBool(KEY_CUE_SOUND, enabled) }
            .onFailure { WeLogger.e(TAG, "save cue sound failed", it) }
        WeLogger.i(TAG, "双击提示音 -> ${if (enabled) "开" else "关"}")
    }

    /** 提示音音色 id：`wood` / `deep`。 */
    val cueSoundId: String
        get() {
            if (!prefsAvailable) return CUE_STYLE_WOOD
            val saved = runCatching { WePrefs.getStringOrDef(KEY_CUE_STYLE_NAME, "") }
                .getOrDefault("")
            if (saved.isNotBlank()) return saved
            // 旧键是编号：0 水泡（已删） / 1 木质 / 2 低沉。没有 id 时按它迁移一次。
            val legacy = runCatching { WePrefs.getIntOrDef(KEY_CUE_STYLE, 0) }.getOrDefault(0)
            return if (legacy >= 2) CUE_STYLE_DEEP else CUE_STYLE_WOOD
        }

    fun setCueSoundId(id: String) {
        if (!prefsAvailable) return
        runCatching { WePrefs.putString(KEY_CUE_STYLE_NAME, id) }
            .onFailure { WeLogger.e(TAG, "save cue style failed", it) }
        WeLogger.i(TAG, "双击提示音音色 -> $id")
    }

    // ── 状态读取与写入 ───────────────────────────────────────────────────────

    fun refreshStatus(): Status {
        val next = loadStatus()
        _status.value = next
        return next
    }

    private fun loadStatus(): Status {
        if (!prefsAvailable) {
            return Status(
                configured = false,
                active = false,
                model = "",
                detail = "读不到配置",
                checkedAt = 0L,
                readable = false,
            )
        }
        return runCatching {
            migrateLegacyIfNeeded()
            val url = WePrefs.getString(KEY_URL).orEmpty()
            val key = WePrefs.getString(KEY_API_KEY).orEmpty()
            val modelName = WePrefs.getString(KEY_MODEL).orEmpty()
            val configured = filled(url, key, modelName)
            Status(
                configured = configured,
                active = configured && WePrefs.getBoolOrFalse(KEY_ACTIVE),
                model = modelName,
                detail = WePrefs.getString(KEY_DETAIL)?.takeIf { it.isNotBlank() },
                checkedAt = WePrefs.getLongOrDef(KEY_CHECKED_AT, 0L),
                thinking = WePrefs.getBoolOrFalse(KEY_THINKING),
            )
        }.getOrElse { error ->
            WeLogger.w(TAG, "read status failed: ${error.message}")
            Status(false, false, "", "读取失败", 0L, readable = false)
        }
    }

    /**
     * 旧配置迁移。只在「全局 Key 为空且没迁移过」时执行一次：
     * 取第一个填过 Key 的旧功能配置，复制到全局键。
     *
     * 迁移过来的配置**默认未激活** —— 旧版本从未保存过连通性结论，
     * 必须等下一次真实调用成功（或用户在设置里点「测试并保存」）才算激活。
     */
    private fun migrateLegacyIfNeeded() {
        if (WePrefs.getBoolOrDef(KEY_MIGRATED, false)) return
        if (WePrefs.getString(KEY_API_KEY).orEmpty().isNotBlank()) {
            WePrefs.putBool(KEY_MIGRATED, true)
            return
        }
        for (index in LEGACY_KEY_KEYS.indices) {
            val key = WePrefs.getString(LEGACY_KEY_KEYS[index]).orEmpty().trim()
            if (key.isEmpty()) continue
            val url = WePrefs.getString(LEGACY_URL_KEYS[index]).orEmpty().trim()
                .ifBlank { DEFAULT_URL }
            val modelName = WePrefs.getString(LEGACY_MODEL_KEYS[index]).orEmpty().trim()
                .ifBlank { DEFAULT_MODEL }
            WePrefs.putString(KEY_URL, url)
            WePrefs.putString(KEY_API_KEY, key)
            WePrefs.putString(KEY_MODEL, modelName)
            WePrefs.putBool(KEY_ACTIVE, false)
            WePrefs.putString(KEY_DETAIL, "待验证")
            WePrefs.putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            WePrefs.putBool(KEY_MIGRATED, true)
            WeLogger.i(TAG, "migrated legacy AI config: model=$modelName")
            return
        }
        WePrefs.putBool(KEY_MIGRATED, true)
    }

    /**
     * 保存**已通过连通性校验**的配置。
     * 对话框必须在 [verify] 成功之后才调用它 —— 校验失败时不允许落库。
     */
    fun saveVerified(url: String, key: String, modelName: String) {
        if (!prefsAvailable) return
        runCatching {
            WePrefs.putString(KEY_URL, url.trim())
            WePrefs.putString(KEY_API_KEY, key.trim())
            WePrefs.putString(KEY_MODEL, modelName.trim())
            WePrefs.putBool(KEY_MIGRATED, true)
        }.onFailure { WeLogger.e(TAG, "save AI config failed", it) }
        // 先读回新配置（model 可能变了），再统一走 markActive 写状态。
        _status.value = loadStatus()
        markActive()
    }

    /** 任意一次真实调用成功 → 卡片显示「AI 已激活」。 */
    fun markActive() {
        if (!prefsAvailable) return
        // 已经是激活态就不重复落盘：自动回复可能每条消息都调一次，没必要每次都写 MMKV。
        val current = _status.value
        if (current.readable && current.configured && current.active) return
        runCatching {
            WePrefs.putBool(KEY_ACTIVE, true)
            WePrefs.putString(KEY_DETAIL, "")
            WePrefs.putLong(KEY_CHECKED_AT, System.currentTimeMillis())
        }
        refreshStatus()
        WeLogger.i(TAG, "AI status -> 已激活（model=$model）")
    }

    /** 任意一次真实调用失败 → 立即降级为「AI 未激活」，并记下失败原因。 */
    fun markInactive(reason: String?) {
        if (!prefsAvailable) return
        val detail = reason?.trim().orEmpty()
        val current = _status.value
        // 已经是同一个未激活原因就不重复写盘。
        if (current.readable && current.configured && !current.active && current.detail == detail) return
        runCatching {
            WePrefs.putBool(KEY_ACTIVE, false)
            WePrefs.putString(KEY_DETAIL, detail)
            WePrefs.putLong(KEY_CHECKED_AT, System.currentTimeMillis())
        }
        refreshStatus()
        WeLogger.w(TAG, "AI status -> 未激活：${reason ?: "调用失败"}")
    }

    /** 清除全局配置（回到「未配置」），不影响各功能自己的提示词/触发条件。 */
    fun clear() {
        if (!prefsAvailable) return
        runCatching {
            WePrefs.remove(KEY_URL)
            WePrefs.remove(KEY_API_KEY)
            WePrefs.remove(KEY_MODEL)
            WePrefs.remove(KEY_THINKING)
            WePrefs.putBool(KEY_ACTIVE, false)
            WePrefs.putString(KEY_DETAIL, "配置已清除")
            WePrefs.putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            WePrefs.putBool(KEY_MIGRATED, true)
        }
        refreshStatus()
        WeLogger.i(TAG, "AI config cleared")
    }

    // ── 地址与错误解析（各功能共用，避免每个功能各写一套） ──────────────────

    /**
     * 归一化为 chat/completions 完整地址。兼容：
     *  - 只填域名          → 补 `/v1/chat/completions`
     *  - 填到 `/v1`、`/v3`、`/v4` → 补 `/chat/completions`
     *  - 填到 `/models`    → 换成 `/chat/completions`
     *  - 已经完整          → 原样返回
     */
    fun normalizeChatUrl(raw: String): String {
        var trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            trimmed = "https://$trimmed"
        }
        trimmed = trimmed.trimEnd('/')
        val lower = trimmed.lowercase()
        return when {
            lower.endsWith("/chat/completions") -> trimmed
            lower.endsWith("/models") -> trimmed.dropLast("/models".length) + "/chat/completions"
            Regex("/v\\d+$").containsMatchIn(lower) -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    /** 由 chat/completions 地址推导 OpenAI 兼容的 /models 地址。 */
    fun modelsUrl(raw: String): String {
        val trimmed = normalizeChatUrl(raw).trimEnd('/')
        val lower = trimmed.lowercase()
        return when {
            lower.endsWith("/chat/completions") ->
                trimmed.dropLast("/chat/completions".length) + "/models"
            lower.endsWith("/models") -> trimmed
            else -> "$trimmed/models"
        }
    }

    /** 从错误响应里提取服务端文案（兼容 `error.message` / `message`）。 */
    fun extractServerError(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(body).jsonObject
            val msg = obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
            msg?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun httpErrorText(code: Int, body: String): String {
        val base = when (code) {
            400 -> "400 · 参数错误"
            401 -> "401 · Key 无效"
            403 -> "403 · 无权限"
            404 -> "404 · 地址或模型不存在"
            429 -> "429 · 请求过频"
            500, 502, 503, 504 -> "$code · 服务器错误"
            else -> "HTTP $code"
        }
        val server = extractServerError(body)
        return if (server != null) "$base；服务端: $server" else base
    }

    private fun networkErrorText(error: Throwable, timeoutSeconds: Int): String = when (error) {
        is SocketTimeoutException -> "超时（${timeoutSeconds}s）"
        is UnknownHostException -> "DNS 解析失败"
        is ConnectException -> "无法连接服务器"
        else -> "请求异常"
    }

    // ── 连通性校验（保存前必须通过） ─────────────────────────────────────────

    data class ProbeResult(
        val ok: Boolean,
        val message: String,
        val models: List<String> = emptyList(),
        /** 鉴权类失败（401/403）：无需再用对话接口重试。 */
        val authFailure: Boolean = false,
    )

    /**
     * 真实连通性校验：
     * 1. 先请求 `GET /models`（不消耗 token，还能顺带拿到模型列表）；
     * 2. 若该服务没有 `/models`（404/405/超时/空列表），退回一次最小对话请求
     *    （`max_tokens=1`）—— 只要接口真的能返回 choices，就说明链接是通的；
     * 3. 401/403 直接判定失败，不做二次尝试。
     */
    fun verify(url: String, key: String, modelName: String): ProbeResult {
        val u = url.trim()
        val k = key.trim()
        val m = modelName.trim()
        if (u.isBlank()) return ProbeResult(false, "请填写 API 地址")
        if (k.isBlank()) return ProbeResult(false, "请填写 API Key")
        if (m.isBlank()) return ProbeResult(false, "请填写模型名称")

        val models = fetchModels(u, k)
        if (models.ok) return ProbeResult(true, "连接成功", models.models)
        if (models.authFailure) return models

        val probe = chatProbe(u, k, m)
        return if (probe.ok) {
            ProbeResult(
                true,
                "连接成功",
                models.models,
            )
        } else {
            probe
        }
    }

    /** 读取可用模型列表；失败时 [ProbeResult.message] 直接可展示。 */
    fun fetchModels(url: String, key: String): ProbeResult {
        val target = modelsUrl(url)
        if (target.isBlank()) return ProbeResult(false, "请填写 API 地址")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = connection.responseCode
            val body = readBody(connection, code)
            if (code !in 200..299) {
                ProbeResult(
                    ok = false,
                    message = httpErrorText(code, body),
                    authFailure = code == 401 || code == 403,
                )
            } else {
                val ids = parseModelIds(body)
                if (ids.isEmpty()) {
                    ProbeResult(false, "接口返回异常")
                } else {
                    ProbeResult(true, "获取成功", ids)
                }
            }
        } catch (error: Exception) {
            ProbeResult(false, networkErrorText(error, 15))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parseModelIds(body: String): List<String> = runCatching {
        val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
            ?: return emptyList()
        data.mapNotNull { item ->
            item.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.distinct()
    }.getOrDefault(emptyList())

    /** 最小对话请求：只发一个 "ping"，`max_tokens=1`，用来验证 chat/completions 是否真的可用。 */
    private fun chatProbe(url: String, key: String, modelName: String): ProbeResult {
        val target = normalizeChatUrl(url)
        if (target.isBlank()) return ProbeResult(false, "请填写 API 地址")
        val body = buildJsonObject {
            put("model", modelName)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", "ping")
                })
            })
            put("max_tokens", 1)
            put("temperature", 0)
        }.toString()

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${key.trim()}")
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val response = readBody(connection, code)
            if (code !in 200..299) {
                val server = extractServerError(response)
                // 少数新服务（如 OpenAI o 系列）不接受 max_tokens，只认 max_completion_tokens。
                // 这种 400 说明地址与鉴权都已通过、只是探测参数不被接受，按「连接成功」处理，
                // 否则用户会因为一个仅用于探测的参数被误判成配置无效。
                val paramOnly = code == 400 && server != null &&
                    listOf("max_tokens", "max_completion_tokens", "unsupported parameter")
                        .any { server.contains(it, ignoreCase = true) }
                if (paramOnly) {
                    ProbeResult(true, "连接成功")
                } else {
                    ProbeResult(
                        ok = false,
                        message = httpErrorText(code, response),
                        authFailure = code == 401 || code == 403,
                    )
                }
            } else {
                val hasChoices = runCatching {
                    json.parseToJsonElement(response).jsonObject["choices"]?.jsonArray?.isNotEmpty() == true
                }.getOrDefault(false)
                if (hasChoices) {
                    ProbeResult(true, "连接成功")
                } else {
                    val server = extractServerError(response)
                    ProbeResult(false, server ?: "响应格式异常")
                }
            }
        } catch (error: Exception) {
            ProbeResult(false, networkErrorText(error, 30))
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    // ── 各功能共用的对话调用 ─────────────────────────────────────────────────

    /**
     * 一次工具调用请求（assistant 发起）。
     *
     * [arguments] 保留模型给的原始 JSON 字符串，不做解析 —— 解析失败是调用方要处理的事，
     * 原样留着才能把坏参数写进日志，定位是模型问题还是 schema 写错。
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    )

    /**
     * 带 function calling 的对话消息。
     *
     * 比 `Pair<String, String>` 多出两处协议必需的信息：
     * - assistant 消息可能没有正文，只有 [toolCalls]；
     * - 工具结果必须带 [toolCallId] 且 `role = "tool"`，否则服务端直接 400。
     */
    data class ChatMessage(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        /**
         * 图片，`data:image/jpeg;base64,…`。
         *
         * **只对 `role = "user"` 生效**：服务端把图片放 system / assistant 里会直接 400。
         * 为空时走原来的纯文本路径，所有旧调用点不受影响。
         */
        val images: List<String> = emptyList(),
    )

    data class ChatResult(
        /** 成功时为模型返回的文本（已 trim）；失败为 null。只有工具调用时也为 null。 */
        val text: String?,
        /** 失败原因（可直接展示 / 记日志）；成功为 null。 */
        val error: String?,
        val responseCode: Int = 0,
        /** assistant 本轮发起的工具调用；为空表示普通文本回复。 */
        val toolCalls: List<ToolCall> = emptyList(),
        /** 服务端结束原因：stop / tool_calls / length / content_filter。 */
        val finishReason: String? = null,
        /**
         * 深度思考的思考内容（`reasoning_content`）。**只用于查看 / 调试，不回传**：
         * 带思考的多轮请求里把上一轮的思考塞回 messages，服务端会直接 400。
         */
        val reasoning: String? = null,
    ) {
        /** 有工具调用时调用方应执行工具并回填结果，而不是当作最终答复。 */
        val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
    }

    /**
     * 统一的 chat/completions 调用（支持 function calling）。
     *
     * 状态联动在这里做，各功能不需要自己判断：
     *  - 成功 → [markActive]（卡片回到「AI 已激活」）；
     *  - 失败 → [markInactive]（卡片立刻变「AI 未激活」并显示原因）。
     *
     * @param tools OpenAI 的 `tools` 数组；null 表示普通对话。调用方负责按场景裁剪，
     *              全量注入会每轮多消耗约 3k token。
     * @param thinking null = 跟随全局开关。
     * @param temperature null = 用配置里的「说话温度」；建议 / 自动回复取 [temperature]，
     *              整理 / 审查 / 侧写汇总传固定 0.2~0.3，让分类判断稳定。
     * @param affectStatus false = 不改首页的「AI 已激活」状态，**成功与失败两个方向都不改**。
     *              后台任务（群总结）用：一次网络抖动不该让用户看到「AI 未激活」；
     *              反过来，后台成功也不该把用户明知有问题的配置刷成「已激活」。
     */
    fun chatWithTools(
        messages: List<ChatMessage>,
        tools: JsonArray? = null,
        temperature: Double? = null,
        readTimeoutMs: Int = 60_000,
        thinking: Boolean? = null,
        affectStatus: Boolean = true,
    ): ChatResult {
        if (!prefsAvailable) {
            return ChatResult(null, "读不到配置")
        }
        if (!isConfigured) {
            val error = "未配置 AI 接口"
            if (affectStatus) markInactive(error)
            return ChatResult(null, error)
        }

        val requestUrl = chatUrl
        val timeoutSeconds = readTimeoutMs / 1000

        // 思考参数没有统一标准：DeepSeek 认 `thinking`，OpenAI 系认 `reasoning_effort`。
        // 开启时两个都带上，服务端明确说「不认这个参数」就逐级去掉：
        //   两个都发 → 只发 thinking → 只发 reasoning_effort → 关。
        // 少了「只发 reasoning_effort」这一档时，OpenAI 系服务商永远开不了思考（两档都 400）。
        // 降级只影响本次请求，不改用户的开关值 —— 换个支持的服务商下次照样开着。
        // 最后一档不带 max_tokens：服务商不认这个参数或嫌它太大时，链路仍能跑完。
        val useThinking = thinking ?: isThinkingEnabled
        val level = thinkingLevel
        val tokens = maxTokens
        val plans = buildList {
            if (useThinking) {
                add(RequestPlan(thinking = true, effort = level, maxTokens = tokens))
                add(RequestPlan(thinking = true, effort = null, maxTokens = tokens))
                add(RequestPlan(thinking = false, effort = level, maxTokens = tokens))
                add(RequestPlan(thinking = false, effort = null, maxTokens = tokens))
            } else {
                add(RequestPlan(thinking = false, effort = null, maxTokens = tokens))
            }
            add(RequestPlan(thinking = false, effort = null, maxTokens = 0))
        }.distinct()
        // 说话类用配置里的温度；整理 / 审查自己传固定的低温度。
        val effectiveTemperature = temperature ?: this.temperature
        // 每次请求一行：温度没有别的核对处，设置页改了之后只有这里能确认它真的进了请求体。
        WeLogger.d(
            TAG,
            "请求 $model temperature=$effectiveTemperature " +
                "thinking=" + (if (useThinking) level.id else "关") +
                " max_tokens=$tokens tools=${tools?.size ?: 0}",
        )

        var requestBody = ""
        var lastCode = 0
        var lastRaw = ""
        var lastError: String? = null
        var parsed: ParsedMessage? = null

        var index = 0
        while (index < plans.size) {
            val plan = plans[index]
            requestBody = buildBody(
                messages, tools, effectiveTemperature,
                plan.maxTokens, plan.thinking, plan.effort,
            )
            val http = try {
                post(requestUrl, requestBody, readTimeoutMs)
            } catch (error: Exception) {
                val message = networkErrorText(error, timeoutSeconds)
                WeLogger.e(TAG, "chat failed", error)
                if (affectStatus) markInactive(message)
                return ChatResult(null, message)
            }
            lastCode = http.code
            lastRaw = http.body
            if (http.code in 200..299) {
                parsed = parseResponse(http.body)
                lastError = null
                break
            }
            lastError = httpErrorText(http.code, http.body)
            if (plan.maxTokens > 0 && http.code == 400 && mentionsMaxTokens(http.body)) {
                // 剩下几档带的是同一个值，逐档重试只会多烧请求：直接跳到不带 max_tokens 的最后一档。
                WeLogger.i(TAG, "服务端不接受 max_tokens=${plan.maxTokens}，本次去掉重试")
                index = plans.lastIndex
                continue
            }
            if (isOptionalParamRejected(http.code, http.body, plan)) {
                WeLogger.i(
                    TAG,
                    "服务端不接受 " + listOfNotNull(
                        "thinking".takeIf { plan.thinking },
                        "reasoning_effort".takeIf { plan.effort != null },
                    ).joinToString(" + ") + "，本次降级重试",
                )
                index++
                continue
            }
            break
        }

        val text = parsed?.text
        val toolCalls = parsed?.toolCalls.orEmpty()
        val error = when {
            lastCode !in 200..299 -> lastError ?: httpErrorText(lastCode, lastRaw)
            parsed == null -> extractServerError(lastRaw) ?: "响应格式异常"
            // 既没正文也没工具调用才算空响应：带 tool_calls 的回复本来就没有 content。
            text.isNullOrBlank() && toolCalls.isEmpty() ->
                extractServerError(lastRaw) ?: "响应为空"
            else -> null
        }

        return if (error != null) {
            WeLogger.e(TAG, "chat failed: HTTP $lastCode · $error")
            if (affectStatus) markInactive(error)
            ChatResult(null, error, lastCode, toolCalls, parsed?.finishReason, parsed?.reasoning)
        } else {
            if (affectStatus) markActive()
            // 有思考内容说明服务端的思考模式真的生效了；D 级只在 debug 落盘，不会随次数刷日志。
            parsed?.reasoning?.let { WeLogger.d(TAG, "思考 ${it.length} 字") }
            ChatResult(text, null, lastCode, toolCalls, parsed?.finishReason, parsed?.reasoning)
        }
    }

    /**
     * 一次请求的可选参数组合。
     *
     * [effort] 为 null 表示这一档不带 `reasoning_effort`；[maxTokens] 为 0 表示不带 `max_tokens`。
     */
    private data class RequestPlan(
        val thinking: Boolean,
        val effort: ThinkingLevel?,
        val maxTokens: Int,
    )

    /** 服务端是否明确在抱怨 `max_tokens`（不认这个名字，或嫌值太大）。 */
    private fun mentionsMaxTokens(body: String): Boolean {
        val text = (extractServerError(body) ?: body).lowercase()
        return text.contains("max_tokens") || text.contains("max_completion_tokens")
    }

    /**
     * 服务端是否在抱怨「不认识这次带的思考参数」，而不是别的问题。
     *
     * 只检查**本次请求真的带了**的参数名：`(false, null)` 这一档两个都没带，
     * 若把 "thinking" 也算进关键词，模型名里带 thinking 的 400 会被误判成参数问题。
     * 其它 400（messages 格式、模型不存在……）重试只会多烧一次请求。
     */
    private fun isOptionalParamRejected(code: Int, body: String, plan: RequestPlan): Boolean {
        if (!plan.thinking && plan.effort == null) return false
        if (code != 400) return false
        val text = (extractServerError(body) ?: body).lowercase()
        val words = buildList {
            if (plan.thinking) add("thinking")
            if (plan.effort != null) add("reasoning")
            add("不支持")
        }
        if (words.any { text.contains(it) }) return true
        return (text.contains("unknown") || text.contains("unsupported") || text.contains("unrecognized")) &&
            text.contains("param")
    }

    private fun buildBody(
        messages: List<ChatMessage>,
        tools: JsonArray?,
        temperature: Double,
        maxTokens: Int,
        thinking: Boolean,
        effort: ThinkingLevel?,
    ): String = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    // content 为 null 时**不能**写空串：assistant 只带 tool_calls 的消息
                    // 一旦带上 "content":""，部分兼容服务端会当成空回复而忽略工具调用。
                    if (message.images.isNotEmpty() && message.role == "user") {
                        // 图文混排：content 变成数组，文字在前、图片在后。
                        // 图片只放 user：system / assistant 里带图会被服务端拒绝。
                        put("content", buildJsonArray {
                            message.content?.let { text ->
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                })
                            }
                            message.images.forEach { url ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject { put("url", url) })
                                })
                            }
                        })
                    } else {
                        message.content?.let { put("content", it) }
                    }
                    message.toolCallId?.let { put("tool_call_id", it) }
                    message.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
                        put("tool_calls", buildJsonArray {
                            calls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
        put("temperature", temperature)
        if (maxTokens > 0) put("max_tokens", maxTokens)
        if (tools != null && tools.isNotEmpty()) {
            put("tools", tools)
            // auto：由模型决定要不要调工具。强制 required 会让「只想闲聊」的场景也去查库。
            put("tool_choice", "auto")
        }
        if (thinking) {
            put("thinking", buildJsonObject { put("type", "enabled") })
        }
        // 独立下发：`thinking=false` 但带 reasoning_effort 是 OpenAI 系的唯一可用形态。
        effort?.let { put("reasoning_effort", it.id) }
    }.toString()

    private class HttpResponse(val code: Int, val body: String)

    /**
     * 读响应体：2xx 走 `inputStream`，其余走 `errorStream`（可能为空）。
     *
     * 三处 HTTP 调用（列模型、探活、正式请求）原本各写一遍这五行；抽出来是为了
     * 「错误体也要读」这件事只有一处实现 —— 漏掉 `errorStream` 的话，4xx 的服务端
     * 原因会变成一句空字符串。
     *
     * 2xx 分支不包 `runCatching`：读流失败属于网络错误，应该抛给调用方转成文案，
     * 吞成空串会被当成「服务端返回空」。
     */
    private fun readBody(connection: HttpURLConnection, code: Int): String =
        if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                .getOrNull().orEmpty()
        }

    /** 发一次 POST，返回状态码与原始响应体；网络异常往外抛，由 [chatWithTools] 统一转文案。 */
    private fun post(url: String, body: String, readTimeoutMs: Int): HttpResponse {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = readTimeoutMs
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val response = readBody(connection, code)
            return HttpResponse(code, response)
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private data class ParsedMessage(
        val text: String?,
        val toolCalls: List<ToolCall>,
        val finishReason: String?,
        val reasoning: String?,
    )

    /**
     * 解析一条 assistant 回复。
     *
     * 与旧版 `parseFirstMessage` 的差别：正文和 tool_calls 是并列关系，二者有其一就算成功；
     * 两者都拿不到才返回 null，由调用方判定为「响应格式异常」。
     */
    private fun parseResponse(body: String): ParsedMessage? = runCatching {
        val choice = json.parseToJsonElement(body).jsonObject["choices"]
            ?.jsonArray?.firstOrNull()?.jsonObject ?: return@runCatching null
        val message = choice["message"]?.jsonObject ?: return@runCatching null

        val text = message["content"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf { it.isNotEmpty() }
        val calls = message["tool_calls"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject
            val function = obj["function"]?.jsonObject ?: return@mapNotNull null
            val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            ToolCall(
                // 少数兼容服务端不回 id，补一个本地 id，回填 tool 消息时才能对上。
                id = id.ifBlank { "call_${name}_${System.nanoTime()}" },
                name = name,
                arguments = function["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }.orEmpty()

        if (text == null && calls.isEmpty()) return@runCatching null
        ParsedMessage(
            text = text,
            toolCalls = calls,
            finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull,
            // 思考内容只带出来给人看，不进 messages —— 回传会让服务端 400。
            reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() },
        )
    }.getOrNull()
}
