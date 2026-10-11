package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

object CodexSettings {

    private const val PREFS_NAME = "Hchat_codex_config"
    private const val KEY_SAVED = "codex_saved"
    private const val KEY_BASE_URL = "codex_base_url"
    private const val KEY_API_KEY = "codex_api_key"
    private const val KEY_MODEL = "codex_model"
    private const val KEY_EFFORT = "codex_reasoning_effort"
    private const val KEY_PROFILES = "codex_profiles"
    private const val KEY_ACTIVE_PROFILE = "codex_active_profile"

    const val EFFORT_DEFAULT = "default"

    const val ENV_KEY_NAME = "a_API_KEY"

    const val MCP_MARKER = "# Hchat-mcp"

    val EFFORT_OPTIONS: List<Pair<String, String>> =
        (listOf(EFFORT_DEFAULT) + listOf("none", "minimal", "low", "medium", "high", "xhigh", "max"))
            .map { it to ScriptPluginAgentReasoning.label(it) }

    data class McpServer(
        val id: String = UUID.randomUUID().toString().replace("-", ""),
        val name: String = "",
        val enabled: Boolean = true,
        val url: String = "",
        val token: String = ""
    ) {
        val envName: String get() = "HCHAT_MCP_" + id.uppercase(Locale.ROOT)
    }

    data class Profile(
        val id: String = UUID.randomUUID().toString().replace("-", ""),
        val name: String = "",
        val baseUrl: String = "",
        val apiKey: String = "",
        val model: String = "",
        val effort: String = EFFORT_DEFAULT,
        val mcpServers: List<McpServer> = emptyList()
    ) {
        val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
    }

    fun normalizeEffort(value: String?): String {
        val effort = value?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return effort.takeIf { candidate -> EFFORT_OPTIONS.any { it.first == candidate } } ?: EFFORT_DEFAULT
    }

    fun profiles(context: Context): List<Profile> {
        val stored = readStored(context)
        if (stored.isNotEmpty()) return stored
        val legacy = legacyProfile(context)
        return listOf(
            if (legacy.baseUrl.isNotBlank() || legacy.apiKey.isNotBlank() || legacy.model.isNotBlank()) legacy
            else Profile(id = legacy.id, name = legacy.name)
        )
    }

    fun active(context: Context): Profile {
        val list = profiles(context)
        val activeId = prefs(context)?.getString(KEY_ACTIVE_PROFILE, "").orEmpty()
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun hasSaved(context: Context): Boolean = runCatching {
        prefs(context)?.getBoolean(KEY_SAVED, false) ?: false
    }.getOrDefault(false)

    fun isConfigured(context: Context): Boolean = active(context).configured

    fun apiKey(context: Context): String = active(context).apiKey

    fun createProfile(context: Context, name: String): Profile {
        val created = Profile(name = normalizedProfileName(context, name))
        writeProfiles(context, profiles(context) + created)
        setActive(context, created.id)
        return created
    }

    fun renameProfile(context: Context, id: String, name: String): Profile {
        val target = profiles(context).firstOrNull { it.id == id } ?: return active(context)
        val renamed = target.copy(name = normalizedProfileName(context, name, id))
        writeProfiles(context, profiles(context).map { if (it.id == id) renamed else it })
        return renamed
    }

    fun deleteProfile(context: Context, id: String): Profile {
        val list = profiles(context)
        if (list.size <= 1) return list.first()
        val remaining = list.filterNot { it.id == id }
        writeProfiles(context, remaining)
        val next = remaining.first()
        setActive(context, next.id)
        return next
    }

    fun setActive(context: Context, id: String) {
        runCatching {
            prefs(context)?.edit()?.putString(KEY_ACTIVE_PROFILE, id)?.apply()
        }.onFailure { HLog.e("[Hchat:codex] 切换配置失败: ${it.message}", it) }
    }

    fun adoptLegacyKey(context: Context, key: String) {
        if (key.isBlank() || apiKey(context).isNotBlank()) return
        saveProfile(context, active(context).copy(apiKey = key), setActiveProfile = false)
    }

    fun save(context: Context, profile: Profile) {
        saveProfile(context, profile, setActiveProfile = true)
    }

    private fun saveProfile(context: Context, profile: Profile, setActiveProfile: Boolean) {
        val next = cleaned(profile)
        val list = profiles(context)
        val exists = list.any { it.id == next.id }
        writeProfiles(context, if (exists) list.map { if (it.id == next.id) next else it } else list + next)
        runCatching {
            prefs(context)?.edit()?.putBoolean(KEY_SAVED, true)?.apply()
        }.onFailure { HLog.e("[Hchat:codex] 保存 codex 设置失败: ${it.message}", it) }
        if (setActiveProfile) setActive(context, next.id)
        apply(context)
    }

    fun env(context: Context): Map<String, String> {
        val profile = active(context)
        val pairs = LinkedHashMap<String, String>()
        profile.apiKey.takeIf { it.isNotBlank() }?.let { pairs[ENV_KEY_NAME] = it }
        profile.mcpServers
            .filter { it.enabled && it.url.isNotBlank() && it.token.isNotBlank() }
            .forEach { pairs[it.envName] = it.token }
        return pairs
    }

    fun apply(context: Context) {
        if (!hasSaved(context)) return
        val profile = active(context)
        if (!profile.configured) return
        val file = File(File(ProotEnvironment.homeDir(context), ".codex"), "config.toml")
        if (!file.exists()) return
        val existing = runCatching { file.readText() }.getOrDefault("")
        if (existing.isBlank()) return
        val patched = stripManagedMcp(patch(existing, profile))
        val blocks = profile.mcpServers
            .filter { it.enabled && it.url.isNotBlank() }
            .joinToString("\n\n") { mcpBlock(it) }
        val next = if (blocks.isBlank()) patched.trimEnd() + "\n" else patched.trimEnd() + "\n\n" + blocks + "\n"
        if (next == existing) return
        runCatching { file.writeText(next) }
            .onFailure { HLog.e("[Hchat:codex] 写 config.toml 失败: ${it.message}", it) }
    }

    fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> = runCatching {
        require(baseUrl.isNotBlank()) { "请先填写 API 地址" }
        var lastError: Throwable? = null
        var fetched: List<String>? = null
        for (url in modelUrls(baseUrl)) {
            val outcome = runCatching { httpGet(url, apiKey) }
            val text = outcome.getOrNull().orEmpty()
            if (outcome.isFailure) {
                lastError = outcome.exceptionOrNull()
                continue
            }
            val names = parseModelNames(text)
            if (names.isNotEmpty()) {
                fetched = names
                break
            }
        }
        fetched ?: lastError?.let { throw it } ?: emptyList()
    }.onFailure {
        HLog.e("[Hchat:codex] 拉取模型列表失败: ${it.message}", it)
    }

    fun testConnection(baseUrl: String, apiKey: String): Result<String> = runCatching {
        require(baseUrl.isNotBlank()) { "请先填写 API 地址" }
        require(apiKey.isNotBlank()) { "请先填写模型密钥" }
        var lastError: Throwable? = null
        for (url in modelUrls(baseUrl)) {
            val outcome = runCatching { httpGet(url, apiKey) }
            if (outcome.isFailure) {
                lastError = outcome.exceptionOrNull()
                continue
            }
            val names = parseModelNames(outcome.getOrNull().orEmpty())
            return@runCatching if (names.isEmpty()) {
                "连接成功（接口未返回模型列表）"
            } else {
                "连接成功，可用模型 ${names.size} 个"
            }
        }
        throw lastError ?: IllegalStateException("连接失败，请检查地址和网络")
    }.onFailure {
        HLog.e("[Hchat:codex] 测试连接失败: ${it.message}", it)
    }.recoverCatching {
        throw IllegalStateException(friendlyError(it))
    }

    fun friendlyError(throwable: Throwable): String {
        val message = throwable.message.orEmpty().trim()
        val code = message.removePrefix("HTTP ").trim().toIntOrNull()
        return when {
            code == null -> message.ifBlank { "连接失败，请检查地址和网络" }
            code == 401 || code == 403 -> "密钥无效或没有权限（HTTP $code）"
            code == 404 -> "地址不对，没有这个接口（HTTP 404）"
            code in 500..599 -> "服务器出错（HTTP $code）"
            else -> "请求失败（HTTP $code）"
        }
    }

    private fun mcpBlock(server: McpServer): String {
        val name = tomlName(server.name.ifBlank { "mcp" })
        val lines = mutableListOf(MCP_MARKER, "[mcp_servers.$name]")
        lines += "url = ${quote(server.url.trim())}"
        if (server.token.isNotBlank()) lines += "bearer_token_env_var = ${quote(server.envName)}"
        lines += "enabled = true"
        return lines.joinToString("\n")
    }

    private fun stripManagedMcp(text: String): String {
        val lines = text.lines()
        val out = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() != MCP_MARKER) {
                out += lines[index]
                index++
                continue
            }
            index++
            if (index < lines.size && lines[index].trimStart().startsWith("[")) index++
            while (index < lines.size && !lines[index].trimStart().startsWith("[")) index++
            while (index < lines.size && lines[index].isBlank()) index++
        }
        return out.joinToString("\n")
    }

    private fun patch(text: String, profile: Profile): String {
        var out = setTopLevelKey(text, "model", profile.model)
        out = if (profile.effort == EFFORT_DEFAULT) {
            removeTopLevelKey(out, "model_reasoning_effort")
        } else {
            setTopLevelKey(out, "model_reasoning_effort", profile.effort)
        }
        val base = resolvedBaseUrl(profile.baseUrl)
        return setBaseUrl(out, base.ifBlank { profile.baseUrl })
    }

    private fun setTopLevelKey(text: String, key: String, value: String): String {
        val line = "$key = ${quote(value)}"
        val regex = Regex("(?m)^[ \\t]*" + Regex.escape(key) + "[ \\t]*=.*$")
        if (regex.containsMatchIn(text)) return regex.replaceFirst(text, Regex.escapeReplacement(line))
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.trimStart().startsWith("[") }
        lines.add(if (at < 0) lines.size else at, line)
        return lines.joinToString("\n")
    }

    private fun removeTopLevelKey(text: String, key: String): String =
        Regex("(?m)^[ \\t]*" + Regex.escape(key) + "[ \\t]*=[^\\n]*\\n?").replaceFirst(text, "")

    private fun setBaseUrl(text: String, url: String): String {
        val line = "base_url = ${quote(url)}"
        val regex = Regex("(?m)^[ \\t]*base_url[ \\t]*=.*$")
        if (regex.containsMatchIn(text)) return regex.replaceFirst(text, Regex.escapeReplacement(line))
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.trimStart().startsWith("[model_providers") }
        if (at < 0) return text
        lines.add(at + 1, line)
        return lines.joinToString("\n")
    }

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun tomlName(value: String): String {
        val cleaned = value.trim().map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
        }.joinToString("").trim('_')
        return cleaned.ifBlank { "mcp" }
    }

    fun resolvedBaseUrl(baseUrl: String): String {
        var raw = baseUrl.trim()
        if (raw.isBlank()) return ""
        if (!raw.contains("://")) raw = "https://$raw"
        val hostEnd = raw.indexOf('/', raw.indexOf("://") + 3).takeIf { it >= 0 } ?: raw.length
        val authority = raw.substring(0, hostEnd)
        val rest = raw.substring(hostEnd)
        val cut = rest.indexOfFirst { it == '?' || it == '#' }
        val path = (if (cut >= 0) rest.substring(0, cut) else rest).trimEnd('/')
        val segments = path.split('/').filter { it.isNotBlank() }
        val versionAt = segments.indexOfLast { it.equals("v1", ignoreCase = true) }
        val endpointAt = segments.indexOfLast { isEndpointSegment(it) }
        val prefix = when {
            versionAt >= 0 -> segments.take(versionAt)
            endpointAt >= 0 -> segments.take(endpointAt)
            else -> segments
        }
        return authority + "/" + (prefix + "v1").joinToString("/")
    }

    private fun isEndpointSegment(segment: String): Boolean {
        val value = segment.lowercase(Locale.US)
        return listOf("chat", "completions", "responses", "models").any { it.startsWith(value) }
    }

    private fun modelUrls(baseUrl: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val resolved = resolvedBaseUrl(baseUrl)
        if (resolved.isNotBlank()) candidates += "$resolved/models"
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isNotBlank()) {
            candidates += "$trimmed/models"
            if (trimmed.endsWith("/chat/completions")) {
                candidates += trimmed.removeSuffix("/chat/completions") + "/models"
            }
            if (!trimmed.endsWith("/v1") && !trimmed.contains("/v1/")) {
                candidates += "$trimmed/v1/models"
            }
        }
        return candidates.toList()
    }

    private fun httpGet(url: String, apiKey: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseModelNames(text: String): List<String> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("data") ?: root.optJSONArray("models") ?: root.optJSONArray("result")
        if (array == null) return emptyList()
        val names = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index)
            val name = if (item != null) {
                item.optString("id").ifBlank { item.optString("name") }
            } else {
                array.optString(index)
            }
            if (name.isNotBlank()) names += name
        }
        return names.distinct()
    }

    private fun normalizedProfileName(context: Context, name: String, excludeId: String = ""): String {
        val base = name.trim().ifBlank { "新配置" }.take(32)
        val used = profiles(context).filter { it.id != excludeId }.map { it.name }
        if (base !in used) return base
        var index = 2
        while ("$base $index" in used) index++
        return "$base $index"
    }

    private fun cleaned(profile: Profile): Profile = profile.copy(
        name = profile.name.trim().ifBlank { "默认配置" }.take(32),
        baseUrl = profile.baseUrl.trim(),
        apiKey = profile.apiKey.trim(),
        model = profile.model.trim(),
        effort = normalizeEffort(profile.effort),
        mcpServers = profile.mcpServers.map {
            it.copy(name = it.name.trim().take(32), url = it.url.trim(), token = it.token.trim())
        }
    )

    private fun legacyProfile(context: Context): Profile {
        val sp = prefs(context)
        return Profile(
            id = "default",
            name = "默认配置",
            baseUrl = sp?.getString(KEY_BASE_URL, "").orEmpty(),
            apiKey = sp?.getString(KEY_API_KEY, "").orEmpty(),
            model = sp?.getString(KEY_MODEL, "").orEmpty(),
            effort = normalizeEffort(sp?.getString(KEY_EFFORT, EFFORT_DEFAULT))
        )
    }

    private fun readStored(context: Context): List<Profile> {
        val raw = prefs(context)?.getString(KEY_PROFILES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val list = mutableListOf<Profile>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            list += Profile(
                id = item.optString("id").ifBlank { UUID.randomUUID().toString().replace("-", "") },
                name = item.optString("name").ifBlank { "配置 ${index + 1}" },
                baseUrl = item.optString("baseUrl"),
                apiKey = item.optString("apiKey"),
                model = item.optString("model"),
                effort = normalizeEffort(item.optString("effort")),
                mcpServers = readStoredMcp(item)
            )
        }
        return list
    }

    private fun readStoredMcp(item: JSONObject): List<McpServer> {
        val servers = item.optJSONArray("mcpServers") ?: return emptyList()
        val list = mutableListOf<McpServer>()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            list += McpServer(
                id = server.optString("id").ifBlank { UUID.randomUUID().toString().replace("-", "") },
                name = server.optString("name"),
                enabled = server.optBoolean("enabled", true),
                url = server.optString("url"),
                token = server.optString("token")
            )
        }
        return list
    }

    private fun writeProfiles(context: Context, list: List<Profile>) {
        val array = JSONArray()
        list.forEach { profile ->
            array.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("baseUrl", profile.baseUrl)
                put("apiKey", profile.apiKey)
                put("model", profile.model)
                put("effort", profile.effort)
                put("mcpServers", JSONArray().apply {
                    profile.mcpServers.forEach { server ->
                        put(JSONObject().apply {
                            put("id", server.id)
                            put("name", server.name)
                            put("enabled", server.enabled)
                            put("url", server.url)
                            put("token", server.token)
                        })
                    }
                })
            })
        }
        runCatching {
            prefs(context)?.edit()?.putString(KEY_PROFILES, array.toString())?.apply()
        }.onFailure { HLog.e("[Hchat:codex] 写入 codex 配置失败: ${it.message}", it) }
    }

    private fun prefs(context: Context) =
        runCatching { HchatStorage.preferences(context, PREFS_NAME) }.getOrNull()
}
