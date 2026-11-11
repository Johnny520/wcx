package h.Hchat.hooks.items.autoreply

import h.Hchat.utils.HLog
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object XiaozhiConsoleApi {
    private const val TAG = "[Hchat:XiaozhiConsole]"
    private const val BASE_URL = "https://xiaozhi.me"
    private const val LOGIN_REFERER = "https://xiaozhi.me/login"
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0 Mobile Safari/537.36"
    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchCaptchaSvg(): String {
        val result = getText("/api/auth/captcha", token = "")
        if (!result.successful) error("图形验证码请求失败: HTTP ${result.code}")
        return result.text.also {
            if (!it.contains("<svg", ignoreCase = true)) error("图形验证码格式异常")
        }
    }

    fun sendSmsCode(phone: String, captchaCode: String): String {
        val normalizedPhone = normalizePhone(phone)
        val body = JSONObject().apply {
            put("phone", normalizedPhone)
            put("captcha_code", captchaCode.trim())
        }
        val result = postText("/api/auth/send-code", token = "", body = body)
        if (!result.successful) {
            log("短信验证码请求失败: HTTP ${result.code} phoneLen=${normalizedPhone.length} body=${sanitizeResponse(result.text)}")
            error("短信验证码请求失败: HTTP ${result.code} ${responseMessage(result.text)}")
        }
        val obj = JSONObject(result.text.ifBlank { "{}" })
        if (!obj.optBoolean("success", false)) {
            log("短信验证码业务失败: phoneLen=${normalizedPhone.length} body=${sanitizeResponse(result.text)}")
            error(obj.optString("message").ifBlank { "短信验证码请求失败" })
        }
        return "短信验证码已发送"
    }

    fun phoneLogin(phone: String, smsCode: String): String {
        val normalizedPhone = normalizePhone(phone)
        val body = JSONObject().apply {
            put("phone", normalizedPhone)
            put("code", smsCode.trim())
        }
        val result = postText("/api/auth/phone-login", token = "", body = body)
        if (!result.successful) {
            log("手机号登录失败: HTTP ${result.code} phoneLen=${normalizedPhone.length} body=${sanitizeResponse(result.text)}")
            error("登录失败: HTTP ${result.code} ${responseMessage(result.text)}")
        }
        val obj = JSONObject(result.text.ifBlank { "{}" })
        if (!obj.optBoolean("success", false)) {
            log("手机号登录业务失败: phoneLen=${normalizedPhone.length} body=${sanitizeResponse(result.text)}")
            error(obj.optString("message").ifBlank { "登录失败" })
        }
        val token = obj.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isBlank()) error("登录成功但未返回 token")
        return token
    }

    fun fetchAgents(token: String): List<XiaozhiAgentOption> {
        val result = getText("/api/agents?page=1&pageSize=24", token)
        if (!result.successful) error("拉取智能体失败: HTTP ${result.code}")
        val obj = JSONObject(result.text.ifBlank { "{}" })
        if (!obj.optBoolean("success", false)) error(obj.optString("message").ifBlank { "拉取智能体失败" })
        val data = obj.optJSONArray("data") ?: JSONArray()
        return buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id").ifBlank { item.optLong("id", 0L).takeIf { it > 0 }?.toString().orEmpty() }
                if (id.isBlank()) continue
                add(
                    XiaozhiAgentOption(
                        id = id,
                        name = item.optString("agent_name").ifBlank { "智能体 $id" },
                        assistantName = item.optString("assistant_name"),
                        model = item.optString("llm_model"),
                        voice = item.optString("tts_voice")
                    )
                )
            }
        }
    }

    fun fetchModels(token: String): List<XiaozhiModelOption> {
        val result = getText("/api/roles/model-list", token)
        if (!result.successful) error("拉取模型列表失败: HTTP ${result.code}")
        val obj = JSONObject(result.text.ifBlank { "{}" })
        val list = obj.optJSONObject("data")?.optJSONArray("modelList") ?: JSONArray()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val name = item.optString("name").ifBlank { item.optString("model") }
                if (name.isBlank()) continue
                add(XiaozhiModelOption(name, item.optString("description").ifBlank { name }))
            }
        }
    }

    fun fetchVoices(token: String): List<XiaozhiVoiceOption> {
        val result = getText("/api/roles/tts-list", token)
        if (!result.successful) error("拉取语音角色失败: HTTP ${result.code}")
        val obj = JSONObject(result.text.ifBlank { "{}" })
        val list = obj.optJSONObject("data")?.optJSONArray("ttsList") ?: JSONArray()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val voiceId = item.optString("voice_id")
                if (voiceId.isBlank()) continue
                val languages = item.optJSONArray("languages")?.let { array ->
                    buildList {
                        for (j in 0 until array.length()) array.optString(j).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }.orEmpty()
                add(XiaozhiVoiceOption(voiceId, item.optString("name").ifBlank { voiceId }, languages))
            }
        }
    }

    fun fetchAgentConfig(token: String, agentId: String): JSONObject {
        val result = getText("/api/agents/${agentId.trim()}", token)
        if (!result.successful) error("拉取智能体配置失败: HTTP ${result.code}")
        val obj = JSONObject(result.text.ifBlank { "{}" })
        if (!obj.optBoolean("success", false)) error(obj.optString("message").ifBlank { "拉取智能体配置失败" })
        return obj.optJSONObject("data")?.optJSONObject("agent") ?: error("智能体配置为空")
    }

    fun saveAgentConfig(token: String, agentId: String, model: String?, voice: String?): XiaozhiAgentOption {
        val agent = fetchAgentConfig(token, agentId)
        val body = JSONObject().apply {
            put("agent_name", agent.optString("agent_name"))
            put("assistant_name", agent.optString("assistant_name"))
            put("llm_model", model?.trim().takeUnless { it.isNullOrBlank() } ?: agent.optString("llm_model"))
            put("tts_voice", voice?.trim().takeUnless { it.isNullOrBlank() } ?: agent.optString("tts_voice"))
            put("tts_speech_speed", agent.optString("tts_speech_speed").ifBlank { "normal" })
            put("tts_pitch", agent.opt("tts_pitch") ?: 0)
            put("asr_speed", agent.optString("asr_speed"))
            put("language", agent.optString("language").ifBlank { agent.optString("lang_code").ifBlank { "zh" } })
            put("character", agent.optString("character"))
            put("memory", agent.optString("memory"))
            put("memory_by_speaker", agent.opt("memory_by_speaker") ?: false)
            put("mcp_endpoints", agent.optJSONArray("mcp_endpoints") ?: JSONArray())
            put("memory_type", agent.optString("memory_type"))
            put("teen_mode", agent.opt("teen_mode") ?: false)
            put("knowledge_base_ids", agent.optJSONArray("knowledge_base_ids") ?: JSONArray())
        }
        val result = postText("/api/agents/${agentId.trim()}/config", token, body)
        if (!result.successful) error("保存智能体配置失败: HTTP ${result.code}")
        val obj = JSONObject(result.text.ifBlank { "{}" })
        if (!obj.optBoolean("success", false)) error(obj.optString("message").ifBlank { "保存智能体配置失败" })
        val updated = fetchAgentConfig(token, agentId)
        return XiaozhiAgentOption(
            id = agentId.trim(),
            name = updated.optString("agent_name").ifBlank { "智能体 ${agentId.trim()}" },
            assistantName = updated.optString("assistant_name"),
            model = updated.optString("llm_model"),
            voice = updated.optString("tts_voice")
        )
    }

    fun fetchMcpEndpointStatus(token: String, agentId: String): XiaozhiMcpStatus {
        val cleanAgentId = agentId.trim()
        if (cleanAgentId.isBlank()) error("请先选择小智智能体")
        val endpointId = if (cleanAgentId.startsWith("agent_")) cleanAgentId else "agent_$cleanAgentId"
        val result = getText(
            "https://api.xiaozhi.me/mcp/endpoints/list?endpoint_ids=$endpointId",
            token
        )
        if (!result.successful && result.code != 304) error("查询 MCP 状态失败: HTTP ${result.code}")
        if (result.text.isBlank()) {
            return XiaozhiMcpStatus(
                connected = false,
                label = "未知",
                detail = "控制台返回空内容，请稍后刷新"
            )
        }
        val obj = JSONObject(result.text)
        val endpoint = obj.optJSONArray("endpoints")?.optJSONObject(0)
            ?: return XiaozhiMcpStatus(false, "离线", "控制台未返回接入点")
        val connectionCount = endpoint.optInt("connectionCount", 0)
        val status = endpoint.optString("status")
        val toolsCount = endpoint.optJSONArray("tools")?.length() ?: 0
        val connected = connectionCount > 0 || status.equals("connected", true) || status.equals("online", true)
        return XiaozhiMcpStatus(
            connected = connected,
            label = if (connected) "在线" else "离线",
            detail = "控制台 status=$status，连接数=$connectionCount，工具=$toolsCount"
        )
    }

    private fun getText(path: String, token: String): HttpTextResult {
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else "$BASE_URL$path"
        client.newCall(
            Request.Builder()
                .url(url)
                .applyBrowserHeaders(path)
                .applyAuth(token)
                .get()
                .build()
        ).execute().use { response ->
            return HttpTextResult(response.code, response.isSuccessful, response.body?.string().orEmpty())
        }
    }

    private fun postText(path: String, token: String, body: JSONObject): HttpTextResult {
        client.newCall(
            Request.Builder()
                .url("$BASE_URL$path")
                .applyBrowserHeaders(path)
                .applyAuth(token)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().use { response ->
            return HttpTextResult(response.code, response.isSuccessful, response.body?.string().orEmpty())
        }
    }

    private fun Request.Builder.applyAuth(token: String): Request.Builder {
        val clean = token.trim()
        if (clean.isNotBlank()) addHeader("Authorization", if (clean.startsWith("Bearer ")) clean else "Bearer $clean")
        return this
    }

    private fun Request.Builder.applyBrowserHeaders(path: String): Request.Builder {
        header("User-Agent", BROWSER_USER_AGENT)
        header("Accept", "*/*")
        header("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
        when {
            path.startsWith("/api/auth/") -> header("Referer", LOGIN_REFERER)
            else -> header("Referer", "$BASE_URL/console/agents")
        }
        if (path == "/api/auth/send-code" || path == "/api/auth/phone-login") {
            header("Origin", BASE_URL)
        }
        return this
    }

    private fun normalizePhone(phone: String): String {
        val value = phone.trim().replace(" ", "").replace("-", "")
        if (value.startsWith("+")) return value
        if (value.startsWith("86") && value.length == 13) return "+$value"
        return "+86$value"
    }

    private fun responseMessage(text: String): String {
        if (text.isBlank()) return ""
        return runCatching {
            val obj = JSONObject(text)
            obj.optString("message")
                .ifBlank { obj.optString("msg") }
                .ifBlank { obj.optString("code") }
        }.getOrDefault("").take(80)
    }

    private fun sanitizeResponse(text: String): String {
        return text
            .replace(Regex("\"token\"\\s*:\\s*\"[^\"]+\""), "\"token\":\"***\"")
            .replace(Regex("\"phone\"\\s*:\\s*\"[^\"]+\""), "\"phone\":\"***\"")
            .take(500)
    }

    private fun log(message: String) {
        HLog.e("$TAG $message")
    }

    private data class HttpTextResult(
        val code: Int,
        val successful: Boolean,
        val text: String
    )

    private class MemoryCookieJar : CookieJar {
        private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            store.compute(url.host) { _, old ->
                val merged = old.orEmpty()
                    .filter { oldCookie -> cookies.none { it.name == oldCookie.name && it.domain == oldCookie.domain && it.path == oldCookie.path } }
                    .toMutableList()
                merged.addAll(cookies)
                merged
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return store[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
        }
    }
}

data class XiaozhiAgentOption(
    val id: String,
    val name: String,
    val assistantName: String,
    val model: String,
    val voice: String
)

data class XiaozhiModelOption(
    val id: String,
    val name: String
)

data class XiaozhiVoiceOption(
    val id: String,
    val name: String,
    val languages: List<String>
)
