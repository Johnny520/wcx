package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ScriptPluginAgentSettings {
    const val PREFS_NAME = ScriptPluginSettings.PREFS_NAME
    private const val KEY_API_BASE = "script_plugin_agent_api_base"
    private const val KEY_API_PATH = "script_plugin_agent_api_path"
    private const val KEY_API_KEY = "script_plugin_agent_api_key"
    private const val KEY_MODEL = "script_plugin_agent_model"
    private const val KEY_MCP_ENABLE = "script_plugin_agent_mcp_enable"
    private const val KEY_MCP_ENDPOINT = "script_plugin_agent_mcp_endpoint"
    private const val KEY_MCP_AUTHORIZATION = "script_plugin_agent_mcp_authorization"
    private const val KEY_AUTO_COMPACT = "script_plugin_agent_auto_compact"
    private const val KEY_COMPACT_TOKEN_THRESHOLD = "script_plugin_agent_compact_token_threshold"
    private const val KEY_WEB_SEARCH_ENABLED = "script_plugin_agent_web_search_enabled"
    private const val KEY_WORKSPACE_WRITE_APPROVAL = "script_plugin_agent_workspace_write_approval"
    private const val KEY_PROFILES = "script_plugin_agent_profiles_v1"
    private const val KEY_ACTIVE_PROFILE = "script_plugin_agent_active_profile"

    const val DEFAULT_API_BASE = ""
    const val DEFAULT_API_PATH = "/chat/completions"
    const val DEFAULT_MODEL = "deepseek-ai/DeepSeek-V3"
    const val DEFAULT_COMPACT_TOKEN_THRESHOLD = 24_000
    const val WRITE_APPROVAL_ASK = "ask"
    const val WRITE_APPROVAL_ALWAYS_ALLOW = "always_allow"
    const val PROMPT_CACHE_AUTO = "auto"
    const val PROMPT_CACHE_FORCE = "force"
    const val PROMPT_CACHE_OFF = "off"
    const val DEFAULT_PROMPT_CACHE_MODE = PROMPT_CACHE_FORCE
    const val ENDPOINT_MODE_OPENAI_COMPATIBLE = "openai_compatible"
    const val ENDPOINT_MODE_OPENAI = "openai"
    const val ENDPOINT_MODE_DEEPSEEK = "deepseek"
    const val ENDPOINT_MODE_OPENROUTER = "openrouter"
    const val ENDPOINT_MODE_SILICONFLOW = "siliconflow"
    const val ENDPOINT_MODE_GEMINI = "gemini"
    const val ENDPOINT_MODE_ANTHROPIC = "anthropic"
    const val ENDPOINT_MODE_CUSTOM_URL = "custom_url"

    fun load(context: Context): ScriptPluginAgentConfig = loadActiveProfile(context).config

    fun loadProfiles(context: Context): List<ScriptPluginAgentProfile> {
        return ensureProfiles(HchatStorage.preferences(context, PREFS_NAME))
    }

    fun loadActiveProfile(context: Context): ScriptPluginAgentProfile {
        val sp = HchatStorage.preferences(context, PREFS_NAME)
        val profiles = ensureProfiles(sp)
        val activeId = sp.getString(KEY_ACTIVE_PROFILE, "").orEmpty()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    }

    fun setActiveProfile(context: Context, id: String): ScriptPluginAgentProfile {
        val sp = HchatStorage.preferences(context, PREFS_NAME)
        val profiles = ensureProfiles(sp)
        val selected = profiles.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("配置不存在")
        writeProfiles(sp, profiles, selected.id)
        return selected
    }

    fun save(context: Context, config: ScriptPluginAgentConfig) {
        val current = loadActiveProfile(context)
        saveProfile(context, current.copy(config = normalizedConfig(config)))
    }

    fun saveProfile(context: Context, profile: ScriptPluginAgentProfile) {
        val sp = HchatStorage.preferences(context, PREFS_NAME)
        val profiles = ensureProfiles(sp).toMutableList()
        val normalized = profile.copy(
            name = cleanProfileName(profile.name),
            config = normalizedConfig(profile.config)
        )
        val index = profiles.indexOfFirst { it.id == normalized.id }
        if (index >= 0) profiles[index] = normalized else profiles += normalized
        writeProfiles(sp, profiles, normalized.id)
    }

    fun createProfile(
        context: Context,
        name: String
    ): ScriptPluginAgentProfile {
        val profiles = loadProfiles(context)
        val cleanName = cleanProfileName(name)
        require(profiles.none { it.name.equals(cleanName, ignoreCase = true) }) { "配置名称已存在" }
        val profile = ScriptPluginAgentProfile(
            id = UUID.randomUUID().toString().replace("-", ""),
            name = cleanName,
            config = ScriptPluginAgentConfig(
                apiBaseUrl = "",
                apiPath = "",
                apiKey = "",
                model = ""
            )
        )
        saveProfile(context, profile)
        return profile
    }

    fun renameProfile(context: Context, id: String, name: String): ScriptPluginAgentProfile {
        val profiles = loadProfiles(context)
        val current = profiles.firstOrNull { it.id == id } ?: throw IllegalArgumentException("配置不存在")
        val cleanName = cleanProfileName(name)
        require(profiles.none { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) {
            "配置名称已存在"
        }
        return current.copy(name = cleanName).also { saveProfile(context, it) }
    }

    fun deleteProfile(context: Context, id: String): ScriptPluginAgentProfile {
        val sp = HchatStorage.preferences(context, PREFS_NAME)
        val profiles = ensureProfiles(sp)
        require(profiles.size > 1) { "至少保留一个配置" }
        val remaining = profiles.filterNot { it.id == id }
        require(remaining.size != profiles.size) { "配置不存在" }
        val activeId = sp.getString(KEY_ACTIVE_PROFILE, "").orEmpty()
        val next = if (activeId == id) remaining.first() else remaining.firstOrNull { it.id == activeId } ?: remaining.first()
        writeProfiles(sp, remaining, next.id)
        return next
    }

    fun endpointUrl(apiAddress: String, legacyPath: String = ""): String {
        var raw = apiAddress.trim()
        if (raw.isBlank()) return ""
        if (!raw.contains("://")) raw = "https://$raw"
        if (legacyPath.isNotBlank() && !raw.trimEnd('/').endsWith("/chat/completions")) {
            raw = raw.trimEnd('/') + "/" + legacyPath.trim().trimStart('/')
        }
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return appendChatCompletions(raw)
        }
        val completedPath = normalizedEndpointPath(uri.encodedPath.orEmpty())
        return uri.buildUpon().encodedPath(completedPath).build().toString().trimEnd('/')
    }

    fun requestUrl(config: ScriptPluginAgentConfig): String {
        return requestUrl(config, stream = true)
    }

    fun requestUrl(config: ScriptPluginAgentConfig, stream: Boolean): String {
        return requestUrl(
            config.apiBaseUrl,
            config.endpointMode,
            config.model,
            stream,
            config.apiPath
        )
    }

    fun requestUrl(
        apiAddress: String,
        endpointMode: String,
        model: String = "",
        stream: Boolean = true,
        legacyPath: String = ""
    ): String {
        val mode = normalizedEndpointMode(endpointMode)
        val address = normalizedApiAddress(apiAddress, mode, legacyPath)
        return if (mode == ENDPOINT_MODE_GEMINI) {
            geminiRequestUrl(address, model, stream)
        } else {
            address
        }
    }

    fun normalizedApiAddress(apiAddress: String, endpointMode: String, legacyPath: String = ""): String {
        return when (normalizedEndpointMode(endpointMode)) {
            ENDPOINT_MODE_CUSTOM_URL -> apiAddress.trim()
            ENDPOINT_MODE_ANTHROPIC -> providerEndpointUrl(apiAddress, "messages")
            ENDPOINT_MODE_GEMINI -> geminiBaseUrl(apiAddress)
            ENDPOINT_MODE_DEEPSEEK -> deepSeekEndpointUrl(apiAddress)
            else -> endpointUrl(apiAddress, legacyPath)
        }
    }

    fun presetRequestUrl(endpointMode: String): String = when (endpointMode) {
        ENDPOINT_MODE_OPENAI -> "https://api.openai.com/v1/chat/completions"
        ENDPOINT_MODE_DEEPSEEK -> "https://api.deepseek.com/chat/completions"
        ENDPOINT_MODE_OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
        ENDPOINT_MODE_SILICONFLOW -> "https://api.siliconflow.cn/v1/chat/completions"
        ENDPOINT_MODE_GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        ENDPOINT_MODE_ANTHROPIC -> "https://api.anthropic.com/v1/messages"
        else -> ""
    }

    fun endpointModeLabel(endpointMode: String): String = when (endpointMode) {
        ENDPOINT_MODE_OPENAI -> "OpenAI"
        ENDPOINT_MODE_DEEPSEEK -> "DeepSeek"
        ENDPOINT_MODE_OPENROUTER -> "OpenRouter"
        ENDPOINT_MODE_SILICONFLOW -> "硅基流动"
        ENDPOINT_MODE_GEMINI -> "Gemini"
        ENDPOINT_MODE_ANTHROPIC -> "Anthropic"
        ENDPOINT_MODE_CUSTOM_URL -> "自定义请求链接"
        else -> "OpenAI 兼容"
    }

    fun isValidRequestUrl(config: ScriptPluginAgentConfig): Boolean {
        val uri = runCatching { Uri.parse(requestUrl(config)) }.getOrNull() ?: return false
        return (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrBlank()
    }

    private fun ensureProfiles(sp: SharedPreferences): List<ScriptPluginAgentProfile> {
        val saved = decodeProfiles(sp.getString(KEY_PROFILES, "").orEmpty())
        if (saved.isNotEmpty()) return saved
        val migrated = ScriptPluginAgentProfile(
            id = "default",
            name = "默认配置",
            config = loadLegacyConfig(sp)
        )
        writeProfiles(sp, listOf(migrated), migrated.id)
        return listOf(migrated)
    }

    private fun loadLegacyConfig(sp: SharedPreferences): ScriptPluginAgentConfig {
        return normalizedConfig(
            ScriptPluginAgentConfig(
                apiBaseUrl = sp.getString(KEY_API_BASE, DEFAULT_API_BASE).orEmpty(),
                apiPath = sp.getString(KEY_API_PATH, DEFAULT_API_PATH).orEmpty(),
                apiKey = sp.getString(KEY_API_KEY, "").orEmpty(),
                model = sp.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty(),
                mcpServers = legacyMcpServers(
                    enabled = sp.getBoolean(KEY_MCP_ENABLE, false),
                    endpoint = sp.getString(KEY_MCP_ENDPOINT, "").orEmpty(),
                    authorization = sp.getString(KEY_MCP_AUTHORIZATION, "").orEmpty()
                ),
                autoCompactEnabled = sp.getBoolean(KEY_AUTO_COMPACT, true),
                compactTokenThreshold = sp.getInt(
                    KEY_COMPACT_TOKEN_THRESHOLD,
                    DEFAULT_COMPACT_TOKEN_THRESHOLD
                ).coerceIn(2_000, 1_000_000),
                webSearchEnabled = sp.getBoolean(KEY_WEB_SEARCH_ENABLED, true),
                workspaceWriteApprovalMode = sp.getString(
                    KEY_WORKSPACE_WRITE_APPROVAL,
                    WRITE_APPROVAL_ASK
                ).orEmpty()
            )
        )
    }

    private fun normalizedConfig(config: ScriptPluginAgentConfig): ScriptPluginAgentConfig {
        val endpointMode = normalizedEndpointMode(config.endpointMode)
        return config.copy(
            apiBaseUrl = normalizedApiAddress(config.apiBaseUrl, endpointMode, config.apiPath),
            apiPath = "",
            apiKey = config.apiKey.trim(),
            model = config.model.trim(),
            reasoningEffort = ScriptPluginAgentReasoning.effectiveEffort(
                endpointMode, config.model, config.reasoningEffort
            ),
            mcpServers = config.mcpServers.mapIndexed { index, server ->
                server.copy(
                    id = server.id.trim().ifBlank { "mcp-${index + 1}" },
                    name = server.name.trim().replace(Regex("\\s+"), " ").take(32)
                        .ifBlank { "MCP ${index + 1}" },
                    endpoint = server.endpoint.trim(),
                    authorization = server.authorization.trim()
                )
            }.distinctBy { it.id },
            compactTokenThreshold = config.compactTokenThreshold.coerceIn(2_000, 1_000_000),
            workspaceWriteApprovalMode = config.workspaceWriteApprovalMode.takeIf {
                it == WRITE_APPROVAL_ASK || it == WRITE_APPROVAL_ALWAYS_ALLOW
            } ?: WRITE_APPROVAL_ASK,
            promptCacheMode = config.promptCacheMode.takeIf {
                it == PROMPT_CACHE_AUTO || it == PROMPT_CACHE_FORCE || it == PROMPT_CACHE_OFF
            } ?: DEFAULT_PROMPT_CACHE_MODE,
            endpointMode = endpointMode
        )
    }

    private fun writeProfiles(
        sp: SharedPreferences,
        profiles: List<ScriptPluginAgentProfile>,
        activeId: String
    ) {
        val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
        val legacyEndpoint = legacyEndpointParts(active.config.apiBaseUrl, active.config.endpointMode)
        val legacyMcp = active.config.mcpServers.firstOrNull { it.enabled }
            ?: active.config.mcpServers.firstOrNull()
        val array = JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id)
                    put("name", profile.name)
                    put("config", encodeConfig(profile.config))
                })
            }
        }
        sp.edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE_PROFILE, active.id)
            .putString(KEY_API_BASE, legacyEndpoint.first)
            .putString(KEY_API_PATH, legacyEndpoint.second)
            .putString(KEY_API_KEY, active.config.apiKey)
            .putString(KEY_MODEL, active.config.model)
            .putBoolean(KEY_MCP_ENABLE, legacyMcp?.enabled == true)
            .putString(KEY_MCP_ENDPOINT, legacyMcp?.endpoint.orEmpty())
            .putString(KEY_MCP_AUTHORIZATION, legacyMcp?.authorization.orEmpty())
            .putBoolean(KEY_AUTO_COMPACT, active.config.autoCompactEnabled)
            .putInt(KEY_COMPACT_TOKEN_THRESHOLD, active.config.compactTokenThreshold)
            .putBoolean(KEY_WEB_SEARCH_ENABLED, active.config.webSearchEnabled)
            .putString(KEY_WORKSPACE_WRITE_APPROVAL, active.config.workspaceWriteApprovalMode)
            .apply()
    }

    private fun decodeProfiles(text: String): List<ScriptPluginAgentProfile> {
        if (text.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id", "").trim()
                    val name = item.optString("name", "").trim()
                    val config = item.optJSONObject("config") ?: continue
                    if (id.isBlank() || name.isBlank()) continue
                    add(ScriptPluginAgentProfile(id, name, decodeConfig(config)))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeConfig(config: ScriptPluginAgentConfig): JSONObject {
        val normalized = normalizedConfig(config)
        val legacyMcp = normalized.mcpServers.firstOrNull { it.enabled }
            ?: normalized.mcpServers.firstOrNull()
        return JSONObject().apply {
            put("apiEndpoint", normalized.apiBaseUrl)
            put("endpointMode", normalized.endpointMode)
            put("apiKey", normalized.apiKey)
            put("model", normalized.model)
            put("reasoningEffort", normalized.reasoningEffort)
            put("mcpEnabled", legacyMcp?.enabled == true)
            put("mcpEndpoint", legacyMcp?.endpoint.orEmpty())
            put("mcpAuthorization", legacyMcp?.authorization.orEmpty())
            put("mcpServers", JSONArray().apply {
                normalized.mcpServers.forEach { server ->
                    put(JSONObject().apply {
                        put("id", server.id)
                        put("name", server.name)
                        put("enabled", server.enabled)
                        put("endpoint", server.endpoint)
                        put("authorization", server.authorization)
                    })
                }
            })
            put("autoCompactEnabled", normalized.autoCompactEnabled)
            put("compactTokenThreshold", normalized.compactTokenThreshold)
            put("webSearchEnabled", normalized.webSearchEnabled)
            put("workspaceWriteApprovalMode", normalized.workspaceWriteApprovalMode)
            put("promptCacheMode", normalized.promptCacheMode)
        }
    }

    private fun decodeConfig(json: JSONObject): ScriptPluginAgentConfig {
        val servers = json.optJSONArray("mcpServers")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        ScriptPluginAgentMcpServer(
                            id = item.optString("id", "mcp-${index + 1}"),
                            name = item.optString("name", "MCP ${index + 1}"),
                            enabled = item.optBoolean("enabled", true),
                            endpoint = item.optString("endpoint", ""),
                            authorization = item.optString("authorization", "")
                        )
                    )
                }
            }
        } ?: legacyMcpServers(
            enabled = json.optBoolean("mcpEnabled", false),
            endpoint = json.optString("mcpEndpoint", ""),
            authorization = json.optString("mcpAuthorization", "")
        )
        return normalizedConfig(
            ScriptPluginAgentConfig(
                apiBaseUrl = json.optString("apiEndpoint", json.optString("apiBaseUrl", DEFAULT_API_BASE)),
                apiPath = json.optString("apiPath", ""),
                apiKey = json.optString("apiKey", ""),
                model = json.optString("model", DEFAULT_MODEL),
                reasoningEffort = json.optString("reasoningEffort", "default"),
                mcpServers = servers,
                autoCompactEnabled = json.optBoolean("autoCompactEnabled", true),
                compactTokenThreshold = json.optInt(
                    "compactTokenThreshold",
                    DEFAULT_COMPACT_TOKEN_THRESHOLD
                ),
                webSearchEnabled = json.optBoolean("webSearchEnabled", true),
                workspaceWriteApprovalMode = json.optString(
                    "workspaceWriteApprovalMode",
                    WRITE_APPROVAL_ASK
                ),
                promptCacheMode = json.optString("promptCacheMode", DEFAULT_PROMPT_CACHE_MODE),
                endpointMode = json.optString(
                    "endpointMode",
                    ENDPOINT_MODE_OPENAI_COMPATIBLE
                )
            )
        )
    }

    private fun legacyMcpServers(
        enabled: Boolean,
        endpoint: String,
        authorization: String
    ): List<ScriptPluginAgentMcpServer> {
        if (endpoint.isBlank() && authorization.isBlank() && !enabled) return emptyList()
        return listOf(
            ScriptPluginAgentMcpServer(
                id = "legacy",
                name = "MCP 1",
                enabled = enabled,
                endpoint = endpoint,
                authorization = authorization
            )
        )
    }

    private fun cleanProfileName(name: String): String {
        return name.trim().replace(Regex("\\s+"), " ").take(32).ifBlank { "未命名配置" }
    }

    private fun normalizedEndpointMode(mode: String): String {
        return mode.takeIf {
            it == ENDPOINT_MODE_OPENAI_COMPATIBLE ||
                it == ENDPOINT_MODE_OPENAI ||
                it == ENDPOINT_MODE_DEEPSEEK ||
                it == ENDPOINT_MODE_OPENROUTER ||
                it == ENDPOINT_MODE_SILICONFLOW ||
                it == ENDPOINT_MODE_GEMINI ||
                it == ENDPOINT_MODE_ANTHROPIC ||
                it == ENDPOINT_MODE_CUSTOM_URL
        }
            ?: ENDPOINT_MODE_OPENAI_COMPATIBLE
    }

    private fun appendChatCompletions(value: String): String {
        val clean = value.trim().trimEnd('/')
        val schemeEnd = clean.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: 0
        val pathStart = clean.indexOf('/', schemeEnd)
        if (pathStart < 0) return "$clean/v1/chat/completions"
        return clean.substring(0, pathStart).trimEnd('/') + normalizedEndpointPath(clean.substring(pathStart))
    }

    private fun geminiRequestUrl(apiAddress: String, model: String, stream: Boolean): String {
        val base = apiAddress.trim()
        val cleanModel = model.trim().removePrefix("models/")
        if (base.isBlank() || cleanModel.isBlank()) return base
        val method = if (stream) "streamGenerateContent" else "generateContent"
        val source = Uri.parse(base)
        val path = source.encodedPath.orEmpty().trimEnd('/') + "/models/${Uri.encode(cleanModel)}:$method"
        val uri = source.buildUpon()
            .encodedPath(path)
            .apply { if (stream) appendQueryParameter("alt", "sse") }
            .build()
        return uri.toString()
    }

    private fun providerEndpointUrl(apiAddress: String, endpoint: String): String {
        var raw = apiAddress.trim()
        if (raw.isBlank()) return ""
        if (!raw.contains("://")) raw = "https://$raw"
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return raw.trimEnd('/') + "/v1/$endpoint"
        }
        val segments = uri.encodedPath.orEmpty().split('/').filter { it.isNotBlank() }
        val v1Index = segments.indexOfLast { it.equals("v1", ignoreCase = true) }
        val endpointIndex = segments.indices.lastOrNull { index ->
            endpoint.startsWith(segments[index], ignoreCase = true)
        }
        val prefix = when {
            v1Index >= 0 -> segments.take(v1Index)
            endpointIndex != null -> segments.take(endpointIndex)
            else -> segments
        }
        val path = "/" + (prefix + listOf("v1", endpoint)).joinToString("/")
        return uri.buildUpon().encodedPath(path).build().toString().trimEnd('/')
    }

    private fun geminiBaseUrl(apiAddress: String): String {
        var raw = apiAddress.trim()
        if (raw.isBlank()) return ""
        if (!raw.contains("://")) raw = "https://$raw"
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return raw.trimEnd('/') + "/v1beta"
        }
        val segments = uri.encodedPath.orEmpty().split('/').filter { it.isNotBlank() }
        val versionIndex = segments.indices.lastOrNull { index ->
            "v1beta".startsWith(segments[index], ignoreCase = true) ||
                segments[index].equals("v1", ignoreCase = true)
        }
        val prefix = versionIndex?.let { segments.take(it) } ?: segments
        val path = "/" + (prefix + "v1beta").joinToString("/")
        return uri.buildUpon().encodedPath(path).build().toString().trimEnd('/')
    }

    private fun deepSeekEndpointUrl(apiAddress: String): String {
        var raw = apiAddress.trim()
        if (raw.isBlank()) return ""
        if (!raw.contains("://")) raw = "https://$raw"
        val uri = runCatching { Uri.parse(raw) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return raw.trimEnd('/') + "/chat/completions"
        }
        val segments = uri.encodedPath.orEmpty().split('/').filter { it.isNotBlank() }
        val chatIndex = segments.indices.lastOrNull { index ->
            "chat".startsWith(segments[index], ignoreCase = true)
        }
        val prefix = chatIndex?.let { segments.take(it) } ?: segments
        val path = "/" + (prefix + listOf("chat", "completions")).joinToString("/")
        return uri.buildUpon().encodedPath(path).build().toString().trimEnd('/')
    }

    private fun normalizedEndpointPath(value: String): String {
        val segments = value.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return "/v1/chat/completions"

        val v1Index = segments.indexOfLast { it.equals("v1", ignoreCase = true) }
        if (v1Index >= 0) {
            return endpointPath(segments.take(v1Index))
        }

        val chatIndex = segments.indices.lastOrNull { index ->
            if (!segments[index].equals("chat", ignoreCase = true)) return@lastOrNull false
            val next = segments.getOrNull(index + 1) ?: return@lastOrNull true
            "completions".startsWith(next, ignoreCase = true)
        }
        return if (chatIndex != null) {
            endpointPath(segments.take(chatIndex))
        } else {
            endpointPath(segments)
        }
    }

    private fun endpointPath(prefix: List<String>): String {
        return "/" + (prefix + listOf("v1", "chat", "completions")).joinToString("/")
    }

    private fun legacyEndpointParts(endpoint: String, endpointMode: String): Pair<String, String> {
        val normalized = normalizedApiAddress(endpoint, endpointMode)
        val suffix = "/chat/completions"
        return if (endpointMode == ENDPOINT_MODE_OPENAI_COMPATIBLE && normalized.endsWith(suffix, ignoreCase = true)) {
            normalized.dropLast(suffix.length).trimEnd('/') to suffix
        } else {
            normalized to ""
        }
    }
}
