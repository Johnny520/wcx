package h.Hchat.hooks.items.script.agent

import bsh.ScriptSyntaxValidator
import h.Hchat.utils.HLog
import java.io.StringReader
import java.util.Properties

object ScriptPluginAgentValidator {
    private val invalidIdChars = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val unsafeFilePath = Regex(
        "(?:new\\s+File|FileOutputStream|FileWriter|RandomAccessFile|Paths\\.get)\\s*\\(\\s*[\\\"'](?:/|[A-Za-z]:[\\\\/]|[^\\\"']*\\.\\.[\\\\/])"
    )

    fun normalize(draft: ScriptPluginAgentDraft): ScriptPluginAgentDraft {
        val info = cleanFencedText(draft.infoProp)
        val parsed = parseInfo(info)
        val name = draft.pluginName.trim().ifBlank { parsed.getProperty("name").orEmpty() }
        val id = safePluginId(draft.pluginId.ifBlank { name })
        return draft.copy(
            pluginName = name,
            pluginId = id,
            infoProp = info.trim(),
            mainJava = cleanFencedText(draft.mainJava).trim(),
            summary = draft.summary.trim()
        )
    }

    fun safePluginId(value: String): String {
        val cleaned = value.trim()
            .replace(invalidIdChars, "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', ' ')
            .take(64)
        return cleaned.ifBlank { "ai_plugin" }
    }

    fun cleanFencedText(value: String): String {
        var result = value.trim()
        if (result.startsWith("```")) {
            result = result.substringAfter('\n', "")
        }
        if (result.endsWith("```")) {
            result = result.dropLast(3).trimEnd()
        }
        return result
    }

    fun validate(
        draft: ScriptPluginAgentDraft,
        mainSource: String = draft.mainJava
    ): ScriptPluginAgentValidation {
        val issues = ArrayList<ScriptPluginAgentIssue>()
        val id = draft.pluginId.trim()
        val code = mainSource
        val info = parseInfo(draft.infoProp)
        if (id.isBlank() || id == "." || id == "..") {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "插件目录名不能为空")
        }
        if (id != safePluginId(id)) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "插件目录名包含路径或文件名不允许的字符")
        }
        if (id.contains("..")) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "插件目录名不能包含 ..")
        }
        if (code.isBlank()) {
            issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "main.java 不能为空")
        }
        listOf("name", "version", "author").forEach { key ->
            if (info.getProperty(key).orEmpty().trim().isBlank()) {
                issues += ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "info.prop 缺少 $key")
            }
        }
        val processValues = info.getProperty("process").orEmpty()
            .lowercase()
            .split(Regex("[,;|\\s]+"))
            .filter { it.isNotBlank() }
        val invalidProcesses = processValues.filterNot { it == "main" || it == "appbrand" || it == "all" }
        if (invalidProcesses.isNotEmpty()) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "info.prop 的 process 只支持 main、appbrand 或 all"
            )
        }
        if (code.isNotBlank()) syntaxIssue("main.java", code)?.let { issues += it }
        if (unsafeFilePath.containsMatchIn(code)) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "代码包含绝对路径或 .. 路径，请改用 pluginDir、pluginDirFile 或 cacheDir"
            )
        }
        val callbackNames = listOf(
            "onLoad", "onUnload", "openSettings", "onClickSendBtn", "onLongClickSendBtn",
            "onHandleMsg", "onImageDownload", "onVideoDownload", "onFinderMediaDownload",
            "onMemberChange", "onNewFriend", "onProtobufPacket"
        )
        callbackNames.forEach { callback ->
            if (Regex("\\b$callback\\s*\\(").containsMatchIn(code) && !Regex("\\b$callback\\s*\\([^)]*\\)\\s*\\{").containsMatchIn(code)) {
                issues += ScriptPluginAgentIssue(
                    ScriptPluginAgentIssueLevel.WARNING,
                    "$callback 的定义看起来不完整，请确认回调签名和大括号"
                )
            }
        }
        issues += riskIssues(code)
        return ScriptPluginAgentValidation(issues.distinctBy { it.level to it.message })
    }

    fun validateAdditionalCode(path: String, code: String): List<ScriptPluginAgentIssue> {
        val issues = ArrayList<ScriptPluginAgentIssue>()
        if (path.substringAfterLast('.', "").lowercase() in setOf("java", "bsh")) {
            syntaxIssue(path, code)?.let { issues += it }
        }
        if (unsafeFilePath.containsMatchIn(code)) {
            issues += ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "$path 包含绝对路径或 .. 路径，请改用 pluginDir、pluginDirFile 或 cacheDir"
            )
        }
        issues += riskIssues(code).map { issue -> issue.copy(message = "$path: ${issue.message}") }
        return issues
    }

    private fun riskIssues(code: String): List<ScriptPluginAgentIssue> {
        return highRiskPatterns.mapNotNull { (pattern, message) ->
            if (pattern.containsMatchIn(code)) {
                ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.WARNING, message, risky = true)
            } else {
                null
            }
        }
    }

    private val highRiskPatterns = listOf(
        Regex("Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder") to "包含执行系统进程的代码，保存前请确认来源和用途",
        Regex("ClassLoader|DexClassLoader|createPackageContext") to "包含 ClassLoader 或跨包加载代码，保存前请确认来源和用途",
        Regex("System\\.load(?:Library)?|\\bloadSo\\s*\\(") to "包含Native库加载代码，保存前请确认来源和用途",
        Regex("java\\.lang\\.reflect|XposedBridge|XposedHelpers|hookBefore|hookAfter|hookReplace") to "包含反射或 Hook 代码，保存前请确认来源和用途",
        Regex("\\.delete\\s*\\(") to "包含删除文件的代码，保存前请确认来源和用途",
        Regex("OkHttpClient|new\\s+URL\\s*\\(|Socket|https?://|\\b(?:get|post|download)\\s*\\(\\s*\"https?://") to "包含网络访问代码，保存前请确认请求目标和数据范围"
    )

    private fun parseInfo(value: String): Properties {
        return Properties().also { properties ->
            runCatching { properties.load(StringReader(value)) }
        }
    }

    private fun syntaxIssue(path: String, code: String): ScriptPluginAgentIssue? {
        val result = try {
            ScriptSyntaxValidator.validate(code)
        } catch (error: Exception) {
            HLog.e("[Hchat:ScriptAgent] BeanShell 语法检查失败: $path", error)
            return ScriptPluginAgentIssue(
                ScriptPluginAgentIssueLevel.ERROR,
                "$path 无法完成 BeanShell 语法检查：${error.javaClass.simpleName}"
            )
        } catch (error: StackOverflowError) {
            HLog.e("[Hchat:ScriptAgent] BeanShell 语法嵌套过深: $path", error)
            return ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, "$path 语法嵌套过深，无法完成检查")
        }
        val error = result.error ?: return null
        val originalLines = code.lines()
        val parsedLine = result.parsedSource.lines().getOrNull(result.line - 1)
        // Preprocessors do not supply a source map. Only report an original location when confirmed;
        // otherwise label the parser's actual location and excerpt instead of inventing a source line.
        val sourceLine = when {
            result.line <= 0 -> null
            result.originalLineNumbers -> result.line
            !parsedLine.isNullOrBlank() -> originalLines.indices.filter { originalLines[it] == parsedLine }
                .singleOrNull()?.plus(1)
            else -> null
        }
        val location = when {
            sourceLine != null && parsedLine == originalLines.getOrNull(sourceLine - 1) && result.column > 0 ->
                "$path:$sourceLine:${result.column}"
            sourceLine != null -> "$path:$sourceLine"
            result.line > 0 -> "$path（预处理后第 ${result.line} 行，第 ${result.column} 列）"
            else -> path
        }
        val excerpt = parsedLine?.trim()?.take(180).orEmpty()
        return ScriptPluginAgentIssue(
            ScriptPluginAgentIssueLevel.ERROR,
            "$location: ${error.take(320)}" + if (excerpt.isBlank()) "" else "\n解析片段：$excerpt"
        )
    }
}
