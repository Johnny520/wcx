package h.Hchat.hooks.items.script.agent

import org.json.JSONObject
import java.util.Locale

/** Provider effort values are intentionally not translated into invented token budgets. */
object ScriptPluginAgentReasoning {
    private val standardEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    fun normalizedEffort(value: String): String {
        return value.trim().lowercase(Locale.ROOT).takeIf { it in standardEfforts } ?: "default"
    }

    fun label(value: String): String = when (normalizedEffort(value)) {
        "none" -> "关闭（none）"
        "minimal" -> "最低（minimal）"
        "low" -> "低（low）"
        "medium" -> "中（medium）"
        "high" -> "高（high）"
        "xhigh" -> "超高（xhigh）"
        "max" -> "最高（max）"
        else -> "默认（由服务端决定）"
    }

    fun options(endpointMode: String, model: String): List<Pair<String, String>> {
        val id = model.trim().lowercase(Locale.ROOT).removePrefix("models/")
        val efforts = when (endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> when {
                matchesClaude(id, "claude-opus-4-7") || matchesClaude(id, "claude-opus-4-8") ->
                    listOf("low", "medium", "high", "xhigh", "max")
                matchesClaude(id, "claude-opus-4-6") || matchesClaude(id, "claude-sonnet-4-6") ->
                    listOf("low", "medium", "high", "max")
                matchesClaude(id, "claude-opus-4-5") -> listOf("low", "medium", "high")
                else -> emptyList()
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> when (id) {
                "gemini-3-pro-preview" -> listOf("low", "high")
                "gemini-3.1-pro-preview", "gemini-3.8-flash", "gemini-3.7-flash" ->
                    listOf("low", "medium", "high")
                "gemini-3-flash-preview", "gemini-3.1-flash-lite", "gemini-3.6-flash", "gemini-3.5-flash" ->
                    listOf("minimal", "low", "medium", "high")
                else -> emptyList()
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_DEEPSEEK -> when (id) {
                "deepseek-flash", "deepseek-v4-pro" -> listOf("none", "low", "high", "max")
                else -> emptyList()
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_SILICONFLOW -> when (id) {
                "pro/deepseek-ai/deepseek-v4", "deepseek-ai/deepseek-v4-flash", "pro/zai-org/glm-5.2" ->
                    listOf("high", "max")
                else -> emptyList()
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_OPENROUTER ->
                if (id.startsWith("anthropic/") || id.contains("claude")) emptyList() else standardEfforts
            else -> standardEfforts
        }
        return (listOf("default") + efforts).map { it to label(it) }
    }

    fun effectiveEffort(endpointMode: String, model: String, value: String): String {
        val effort = normalizedEffort(value)
        return effort.takeIf { candidate -> options(endpointMode, model).any { it.first == candidate } } ?: "default"
    }

    fun description(endpointMode: String, model: String): String {
        if (options(endpointMode, model).size == 1) {
            if (endpointMode == ScriptPluginAgentSettings.ENDPOINT_MODE_OPENROUTER) {
                return "此模型经 OpenRouter 调整思考需要完整签名回传，暂保留默认；可改用 Anthropic 原生接口。"
            }
            return "当前接口与模型暂无已适配的思考档位，保持服务端默认。"
        }
        return when (endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC ->
                "控制模型的回答与推理投入，不额外开启思考输出；默认不发送参数。"
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI ->
                "按模型支持的思考档位发送；最低档仍可能思考，默认由服务端决定。"
            else -> "默认不发送参数；非默认档位需模型和服务端支持，不支持时接口会报错。"
        }
    }

    internal fun apply(config: ScriptPluginAgentConfig, body: JSONObject) {
        val effort = normalizedEffort(config.reasoningEffort)
        if (effort == "default") return
        require(effort == effectiveEffort(config.endpointMode, config.model, effort)) {
            "当前接口与模型不支持所选思考档位，请重新选择或恢复默认"
        }
        // Explicit effort uses provider sampling defaults, avoiding temperature/reasoning conflicts.
        body.remove("temperature")
        when (config.endpointMode) {
            ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC -> {
                body.put("output_config", JSONObject().put("effort", effort))
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI -> {
                val generation = body.optJSONObject("generationConfig") ?: JSONObject().also {
                    body.put("generationConfig", it)
                }
                generation.remove("temperature")
                generation.put("thinkingConfig", JSONObject().put("thinkingLevel", effort))
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_DEEPSEEK -> {
                body.put("thinking", JSONObject().put("type", if (effort == "none") "disabled" else "enabled"))
                if (effort != "none") body.put("reasoning_effort", effort)
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_SILICONFLOW -> {
                body.put("enable_thinking", true)
                body.put("reasoning_effort", effort)
            }
            ScriptPluginAgentSettings.ENDPOINT_MODE_OPENROUTER -> {
                body.put("reasoning", JSONObject().put("effort", effort))
            }
            else -> body.put("reasoning_effort", effort)
        }
    }

    private fun matchesClaude(model: String, base: String): Boolean {
        return model == base || (model.startsWith("$base-") &&
            model.removePrefix("$base-").matches(Regex("\\d{8}")))
    }
}
