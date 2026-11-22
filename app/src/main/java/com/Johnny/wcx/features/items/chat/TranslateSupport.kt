package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.collections.LruCache
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/**
 * 「消息翻译 / 边写边译 / 聊天记录 AI 总结」共用的网络与配置工具。
 *
 * 设计约束（与 P0 规范一致）：
 * 1. **不新增依赖**：只用 [HttpURLConnection] + kotlinx.serialization（项目已有），与
 *    `AIAutoReply.callAI()` 的实现方式保持一致；
 * 2. **不另建密钥**：直接复用 `AIAutoReply` 的三个配置键
 *    `ai_reply_api_url` / `ai_reply_api_key` / `ai_reply_model`，
 *    用户在「AI 自动回复」里配一次，翻译与总结就都能用；本文件不保存任何 Key；
 * 3. **全部方法都是同步阻塞的**，调用方必须放在 IO 线程（Dispatchers.IO）里，
 *    内部超时固定 [TIMEOUT_MS]（15s）；
 * 4. 失败一律返回 [Result.failure]，异常消息已经是可直接展示给用户的中文文案。
 */
object TranslateSupport {

    private const val TAG = "WcxTranslate"

    /** 单次网络请求超时（毫秒）。规范要求 15s。 */
    const val TIMEOUT_MS = 15_000L

    /** 免费 Google 翻译接口单次能承受的最大字符数（URL 长度限制），超出直接提示换引擎。 */
    private const val FREE_ENGINE_MAX_CHARS = 1500

    /** 译文内存缓存上限；同一文本（同引擎同语言）不重复请求。 */
    private const val CACHE_LIMIT = 128

    // ─── 与 AIAutoReply 共享的配置键（禁止在这里写死任何 Key） ───────────────────
    private var aiApiUrl by prefOption("ai_reply_api_url", "https://api.deepseek.com/chat/completions")
    private var aiApiKey by prefOption("ai_reply_api_key", "")
    private var aiModel by prefOption("ai_reply_model", "deepseek-chat")

    /** 与 AIAutoReply.callAI() 相同的 Json 配置，忽略未知字段。 */
    private val json = Json { ignoreUnknownKeys = true }

    private val cache = LruCache<String, String>(maxLimit = CACHE_LIMIT)

    private const val SYSTEM_TRANSLATE_PROMPT = "你是一个翻译引擎，只输出译文本身，不要解释、不要加引号。"

    const val SYSTEM_SUMMARY_PROMPT =
        "你是一个聊天记录整理助手，擅长把零散的对话压缩成要点。只输出总结正文，不要复述原文。"

    // ─── 引擎 ────────────────────────────────────────────────────────────────

    /** 翻译引擎。只能二选一，见设置项「引擎」。 */
    enum class Engine(val value: Int, val label: String) {
        AI(0, "AI 模型（复用 AI 自动回复的接口）"),
        FREE(1, "免费翻译接口（Google，无需 Key）"),
        ;

        companion object {
            fun fromValue(value: Int): Engine = entries.firstOrNull { it.value == value } ?: AI
        }
    }

    // ─── 目标语言 ─────────────────────────────────────────────────────────────

    /** 目标语言。源语言固定自动识别（`sl=auto` / 交给 AI）。 */
    enum class Lang(val label: String, val aiName: String, val googleCode: String) {
        CHINESE("中文", "简体中文", "zh-CN"),
        TRADITIONAL_CHINESE("繁体中文", "繁体中文", "zh-TW"),
        ENGLISH("英语", "英语", "en"),
        JAPANESE("日语", "日语", "ja"),
        KOREAN("韩语", "韩语", "ko"),
        RUSSIAN("俄语", "俄语", "ru"),
        FRENCH("法语", "法语", "fr"),
        GERMAN("德语", "德语", "de"),
        SPANISH("西班牙语", "西班牙语", "es"),
        ;

        companion object {
            fun fromLabel(label: String?): Lang =
                entries.firstOrNull { it.label == label } ?: CHINESE
        }
    }

    // ─── AI 配置 ──────────────────────────────────────────────────────────────

    data class AiConfig(val apiUrl: String, val apiKey: String, val model: String) {
        val isUsable: Boolean get() = apiKey.isNotBlank() && apiUrl.isNotBlank()
    }

    /** 读取与 AIAutoReply 共享的配置（同一个 SharedPreferences 键，没有第二套密钥）。 */
    fun aiConfig(): AiConfig = AiConfig(
        apiUrl = runCatching { aiApiUrl }.getOrDefault(""),
        apiKey = runCatching { aiApiKey }.getOrDefault(""),
        model = runCatching { aiModel }.getOrDefault(""),
    )

    /**
     * 把用户填的地址补全成 OpenAI 兼容的 /chat/completions 地址。
     * 与 AIAutoReply.normalizeApiUrl() 的规则一致：
     * `https://api.deepseek.com` → `https://api.deepseek.com/v1/chat/completions`；
     * 已经带 `/chat/completions` 或 `/completions` 的原样返回。
     */
    fun normalizeApiUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.endsWith("/chat/completions", ignoreCase = true) -> trimmed
            trimmed.endsWith("/completions", ignoreCase = true) -> trimmed
            trimmed.endsWith("/v1", ignoreCase = true) -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    // ─── 对外：翻译 ───────────────────────────────────────────────────────────

    /**
     * 翻译单段文本。命中内存缓存时不会发起网络请求。
     * 必须在 IO 线程调用（内部是阻塞 socket）。
     */
    fun translate(text: String, engine: Engine, lang: Lang): Result<String> {
        val content = text.trim()
        if (content.isEmpty()) return Result.failure(IOException("没有可翻译的内容"))

        val key = "${engine.value}|${lang.googleCode}|$content"
        cache[key]?.let { return Result.success(it) }

        val result = when (engine) {
            Engine.AI -> aiTranslate(content, lang)
            Engine.FREE -> googleTranslate(content, lang)
        }
        result.onSuccess { cache[key] = it }
        return result
    }

    /** AI 引擎翻译：POST chat/completions，prompt「把下面内容翻译成X，只输出译文」。 */
    private fun aiTranslate(text: String, lang: Lang): Result<String> {
        val userPrompt = "把下面内容翻译成${lang.aiName}，只输出译文，不要解释、不要加引号：\n\n$text"
        return chatCompletion(SYSTEM_TRANSLATE_PROMPT, userPrompt)
            .mapCatching { cleanAiOutput(it) }
    }

    /**
     * 免费引擎翻译：`translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=xx&dt=t`。
     * 无需 Key；失败时由调用方提示用户切换 AI 引擎。
     */
    fun googleTranslate(text: String, lang: Lang): Result<String> {
        val content = text.trim()
        if (content.length > FREE_ENGINE_MAX_CHARS) {
            return Result.failure(
                IOException("文本过长（${content.length} 字），免费接口单次上限约 $FREE_ENGINE_MAX_CHARS 字，请切换到 AI 引擎")
            )
        }
        val encoded = runCatching { URLEncoder.encode(content, "UTF-8") }
            .getOrElse { return Result.failure(IOException("文本编码失败", it)) }

        val url = "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=${lang.googleCode}&dt=t&q=$encoded"

        return request(url, "GET", null, emptyMap(), TIMEOUT_MS).mapCatching { body ->
            // 返回结构：[[["译文","原文",null,null,...], ...], null, "en", ...]
            val segments = json.parseToJsonElement(body).jsonArray
                .firstOrNull()?.jsonArray
                ?: throw IOException("免费翻译接口返回格式异常，建议切换到 AI 引擎")
            val translated = segments.joinToString(separator = "") { segment ->
                segment.jsonArray.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            if (translated.isBlank()) throw IOException("免费翻译接口返回空译文，建议切换到 AI 引擎")
            translated.trim()
        }
    }

    // ─── 对外：AI 对话 ────────────────────────────────────────────────────────

    /**
     * OpenAI 兼容 `chat/completions` 调用。
     * 复用 AIAutoReply 的接口地址 / Key / 模型，没有配置时返回可直接展示的失败文案。
     */
    fun chatCompletion(
        systemPrompt: String,
        userPrompt: String,
        config: AiConfig = aiConfig(),
        timeoutMs: Long = TIMEOUT_MS,
    ): Result<String> {
        if (!config.isUsable) {
            return Result.failure(
                IOException("未配置 AI 接口，请先到「AI 自动回复」里填写接口地址与 API Key")
            )
        }
        val url = normalizeApiUrl(config.apiUrl)
        if (url.isEmpty()) {
            return Result.failure(IOException("AI 接口地址为空，请到「AI 自动回复」里重新填写"))
        }

        val body = runCatching {
            buildJsonObject {
                put("model", config.model.ifBlank { "deepseek-chat" })
                put("temperature", 0.3)
                put("messages", buildJsonArray {
                    if (systemPrompt.isNotBlank()) {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
            }.toString()
        }.getOrElse { return Result.failure(IOException("请求体构造失败", it)) }

        val headers = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Authorization" to "Bearer ${config.apiKey}",
        )

        return request(url, "POST", body, headers, timeoutMs).mapCatching { response ->
            val content = parseChatContent(response)
            if (content.isBlank()) throw IOException("AI 返回内容为空，请检查模型名是否正确")
            content
        }
    }

    private fun parseChatContent(body: String): String {
        val root = json.parseToJsonElement(body).jsonObject
        val choices = root["choices"]?.jsonArray
        val first = choices?.firstOrNull()?.jsonObject
        return first?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    // ─── HTTP 基础 ────────────────────────────────────────────────────────────

    /**
     * 同步 HTTP 请求（阻塞，必须在 IO 线程）。2xx 返回响应体，其它情况返回中文失败文案。
     * 连接一定在 finally 里 disconnect，避免 fd 泄漏。
     */
    private fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Long,
    ): Result<String> {
        val timeoutInt = timeoutMs.coerceIn(3_000L, 60_000L).toInt()
        val result = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
                connection.connectTimeout = timeoutInt
                connection.readTimeout = timeoutInt
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { stream ->
                        stream.write(body.toByteArray(Charsets.UTF_8))
                    }
                }
                val code = connection.responseCode
                val text = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    runCatching {
                        connection.errorStream?.bufferedReader()?.use { it.readText() }
                    }.getOrNull().orEmpty()
                }
                if (code !in 200..299) throw IOException(httpErrorMessage(code, text))
                text
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        return result.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(friendlyError(it, timeoutInt)) },
        )
    }

    private fun httpErrorMessage(code: Int, body: String): String {
        val base = when (code) {
            400 -> "400 请求格式错误"
            401 -> "401 未授权 — API Key 无效或已过期"
            403 -> "403 无访问权限"
            404 -> "404 接口地址不存在"
            408 -> "408 请求超时"
            413 -> "413 请求内容过大"
            429 -> "429 请求过于频繁，请稍后再试"
            in 500..599 -> "$code 服务器错误"
            else -> "HTTP $code"
        }
        val snippet = body.trim().replace('\n', ' ').take(120)
        return if (snippet.isBlank()) base else "$base：$snippet"
    }

    /** 把底层异常翻译成用户能看懂的中文。 */
    private fun friendlyError(error: Throwable, timeoutMs: Int): Exception = when (error) {
        // 注意顺序：SocketTimeout / UnknownHost / ConnectException 都是 IOException 的子类，
        // 必须先判断，否则会被下面的 IOException 分支吃掉，用户看到的是英文的 "Read timed out"。
        is SocketTimeoutException -> IOException("请求超时（${timeoutMs / 1000}s），请检查网络后重试", error)
        is UnknownHostException -> IOException("DNS 解析失败，请检查网络", error)
        is java.net.ConnectException -> IOException("无法连接服务器，请检查网络或接口地址", error)
        is IOException -> if (error.message.isNullOrBlank()) IOException("网络异常，请稍后重试", error) else error
        else -> {
            WeLogger.e(TAG, "网络请求失败", error)
            IOException(error.message?.takeIf { it.isNotBlank() } ?: "未知错误（${error.javaClass.simpleName}）", error)
        }
    }

    /** 去掉 AI 常见的「译文：」「」引号包裹，避免把说明文字塞进译文框。 */
    private fun cleanAiOutput(raw: String): String {
        var text = raw.trim()
        listOf("译文：", "译文:", "翻译：", "翻译:").forEach { prefix ->
            if (text.startsWith(prefix)) text = text.removePrefix(prefix).trim()
        }
        if (text.length >= 2) {
            val wrappedByAscii = text.startsWith("\"") && text.endsWith("\"")
            val wrappedByCjk = text.startsWith("“") && text.endsWith("”")
            if (wrappedByAscii || wrappedByCjk) text = text.substring(1, text.length - 1).trim()
        }
        if (text.isBlank()) throw IOException("AI 返回的译文为空，请重试或切换引擎")
        return text
    }

    /** 清空译文缓存（设置变更时可选调用）。 */
    fun clearCache() {
        runCatching { cache.clear() }
    }
}
