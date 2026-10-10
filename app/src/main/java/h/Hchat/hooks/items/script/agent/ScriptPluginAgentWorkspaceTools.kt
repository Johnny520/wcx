package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.system.Os
import h.Hchat.hooks.items.script.ScriptPluginRuntime
import h.Hchat.hooks.items.script.ScriptPluginSettings
import h.Hchat.hooks.items.script.ScriptPluginTransactionCoordinator
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

object ScriptPluginAgentWorkspaceTools {
    private const val PREFIX = "hchat.workspace."
    private const val MAX_FILES = 512
    private const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
    private const val MAX_TEXT_BYTES = 2L * 1024L * 1024L
    private const val MAX_PATCH_BYTES = 4L * 1024L * 1024L
    private const val MAX_READ_CHARS = 128_000
    private const val MAX_SEARCH_RESULTS = 200
    private const val MAX_LIST_RESULTS = 500
    private const val MAX_ACCESS_RESULTS = 500
    private const val MAX_DIFF_CHARS = 96_000
    private const val WORKSPACE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    private const val TRANSACTION_READY_SUFFIX = ".ready"
    private const val TRANSACTION_COMMITTED_SUFFIX = ".committed"

    private val toolNames = setOf(
        "check_access",
        "runtime_diagnostics",
        "list_files",
        "read_file",
        "search_files",
        "create_directory",
        "write_file",
        "apply_patch",
        "replace_text",
        "run_shell",
        "run_command",
        "move_path",
        "delete_path",
        "restore_path",
        "reset_workspace",
        "delete_plugin",
        "show_diff",
        "workspace_status"
    )

    @JvmStatic
    fun toolCatalog(): String {
        val tools = JSONArray()
        tool(tools, "check_access", "检查插件文件是否可读、可写或可替换，并可尝试修复当前微信进程有权修改的文件权限", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名；必须使用插件列表中的准确 ID"),
            "path" to stringSchema("相对插件目录的路径，默认 .", minLength = 0),
            "recursive" to booleanSchema("是否递归检查子文件和目录", true),
            "repair" to booleanSchema("是否尝试补齐当前文件所有者的读写权限和目录进入权限", false)
        ), listOf("plugin_id"))
        tool(tools, "runtime_diagnostics", "只读指定已安装插件的当前进程加载状态与 log.txt；不创建工作区，不执行或启用插件。日志属于磁盘插件的历史输出，不能验证暂存新代码", linkedMapOf(
            "plugin_id" to stringSchema("插件目录的准确 ID，不接受显示名或模糊匹配"),
            "max_lines" to integerSchema("最多返回最新日志行数，单页同时限制为 64 KiB", 120, 1, 500),
            "before_offset" to integerSchema("向前读取日志的字节边界；-1 从末尾读取，续读使用上次 nextBeforeOffset", -1, -1)
        ), listOf("plugin_id"))
        tool(tools, "list_files", "列出插件工作区内的文件和目录", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名；修改现有插件时使用插件列表中的准确 ID"),
            "path" to stringSchema("相对插件目录的路径，默认 .", minLength = 0),
            "recursive" to booleanSchema("是否递归列出", false),
            "max_depth" to integerSchema("递归最大深度", 3, 1, 8)
        ), listOf("plugin_id"))
        tool(tools, "read_file", "按行读取插件工作区内的文本文件并返回稳定行号", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("相对插件目录的文件路径"),
            "start_line" to integerSchema("起始行，从 1 开始", 1, 1),
            "start_column" to integerSchema("起始行内的字符位置，从 1 开始", 1, 1),
            "end_line" to integerSchema("可选结束行，0 表示按 max_lines", 0, 0),
            "max_lines" to integerSchema("未指定 end_line 时最多返回行数", 400, 1, 2_000),
            "include_line_numbers" to booleanSchema("是否在内容前显示行号", true)
        ), listOf("plugin_id", "path"))
        tool(tools, "search_files", "搜索插件工作区内文本文件的内容", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "query" to stringSchema("搜索文本或正则表达式"),
            "path" to stringSchema("搜索起始目录，默认 .", minLength = 0),
            "regex" to booleanSchema("是否按正则表达式搜索", false),
            "case_sensitive" to booleanSchema("是否区分大小写", false),
            "file_pattern" to stringSchema("可选路径通配符，例如 **/*.java", minLength = 0),
            "exclude_pattern" to stringSchema("可选排除路径通配符", minLength = 0),
            "before_context" to integerSchema("返回匹配行之前的上下文行数", 0, 0, 10),
            "after_context" to integerSchema("返回匹配行之后的上下文行数", 0, 0, 10),
            "limit" to integerSchema("最多返回匹配数量", 50, 1, MAX_SEARCH_RESULTS)
        ), listOf("plugin_id", "query"))
        tool(tools, "create_directory", "在插件工作区内创建目录", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("要创建的相对目录路径")
        ), listOf("plugin_id", "path"))
        tool(tools, "write_file", "新建文本文件，或在 overwrite=true 时完整覆写文件", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("相对插件目录的文件路径"),
            "content" to stringSchema("完整文件内容", minLength = 0),
            "overwrite" to booleanSchema("是否允许覆写现有文件", false)
        ), listOf("plugin_id", "path", "content"))
        tool(tools, "apply_patch", "应用 Codex 风格统一补丁，可在一次调用中新增、更新、移动或删除多个文件", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "patch" to stringSchema("以 *** Begin Patch 开始、*** End Patch 结束的完整统一补丁")
        ), listOf("plugin_id", "patch"))
        tool(tools, "replace_text", "在单个文件中做精确字符串替换（局部修改首选，不依赖行号）；找不到或匹配不唯一会明确报错", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("相对插件目录的文件路径"),
            "search_string" to stringSchema("要查找的唯一原文片段（应包含足够上下文保证唯一）"),
            "replacement" to stringSchema("替换后的文本"),
            "occurrence" to integerSchema("替换第几次出现的匹配，从 1 开始；-1 表示全部替换", 1, -1, 100)
        ), listOf("plugin_id", "path", "search_string", "replacement"))
        tool(tools, "run_shell", "在插件工作区内执行 shell 命令（沙箱：工作目录锁定在插件目录，拦截危险命令，输出截断返回）。用于批量/复杂处理，如 sed、python 生成代码、查重等", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "command" to stringSchema("要执行的 shell 命令；只能操作插件目录内的文件，禁止访问系统/网络/其它应用数据"),
            "timeout_seconds" to integerSchema("命令超时秒数", 15, 1, 60)
        ), listOf("plugin_id", "command"))
        tool(tools, "run_command", "在完整 Ubuntu 终端环境（proot 容器，容器内 root）执行命令。支持 apt 装包、编译运行代码、curl 等；比 run_shell 强大。首次调用会触发环境下载安装（约 279MB，需联网，已内置 codex 与 DexClub 逆向工具）。工作目录为插件目录。", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "command" to stringSchema("要在 Ubuntu 容器内执行的 bash 命令"),
            "timeout_seconds" to integerSchema("命令超时秒数", 60, 1, 600)
        ), listOf("plugin_id", "command"))
        tool(tools, "move_path", "移动或重命名插件工作区内的文件或目录", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "source" to stringSchema("源相对路径"),
            "destination" to stringSchema("目标相对路径"),
            "overwrite" to booleanSchema("是否覆盖目标", false)
        ), listOf("plugin_id", "source", "destination"))
        tool(tools, "delete_path", "删除插件工作区内的文件或子目录；不能删除插件根目录", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("要删除的相对路径")
        ), listOf("plugin_id", "path"))
        tool(tools, "restore_path", "把文件或目录恢复到本轮开始时的状态；新建路径会被移除", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("要恢复的相对路径")
        ), listOf("plugin_id", "path"))
        tool(tools, "reset_workspace", "丢弃本轮全部暂存修改并恢复到任务开始状态", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名")
        ), listOf("plugin_id"))
        tool(tools, "delete_plugin", "标记删除整个现有插件；最终提交前客户端一定会要求用户确认", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名")
        ), listOf("plugin_id"))
        tool(tools, "show_diff", "显示当前暂存工作区相对原插件的标准统一 diff", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名"),
            "path" to stringSchema("可选相对路径，只显示该路径下的差异", minLength = 0)
        ), listOf("plugin_id"))
        tool(tools, "workspace_status", "检查工作区变更、必需文件和静态校验结果", linkedMapOf(
            "plugin_id" to stringSchema("插件目录名")
        ), listOf("plugin_id"))
        return JSONObject().apply {
            put("source", "Hchat 插件工作区工具")
            put("instructions", "所有路径均相对单个插件目录。权限异常或写入失败时先调用 check_access，必要时设置 repair=true；修改代码先 list/read/search。局部小改优先用 replace_text（给原文片段+新文本，不依赖行号）；多文件/新增/移动/整文件用 Codex 风格 apply_patch。完成前必须依次调用 workspace_status 和 show_diff。停止条件：当 workspace_status 返回 canApply=true 且 show_diff 结果符合你的意图时，立即提交，不要反复检查；若校验错误连续两次修改仍未改善，停止并说明原因。")
            put("tools", tools)
        }.toString()
    }

    @JvmStatic
    fun isKnownToolName(name: String): Boolean {
        val value = name.trim().removePrefix("local.")
        return value.startsWith(PREFIX) && normalize(value) in toolNames
    }

    @JvmStatic
    fun requiresWriteApproval(name: String): Boolean {
        return normalize(name) == "write_file" || normalize(name) == "apply_patch" || normalize(name) == "replace_text" || normalize(name) == "run_shell" || normalize(name) == "run_command"
    }

    @JvmStatic
    fun mutatesWorkspace(name: String): Boolean = normalize(name) in setOf(
        "create_directory",
        "write_file",
        "apply_patch",
        "replace_text",
        "run_shell",
        "run_command",
        "move_path",
        "delete_path",
        "restore_path",
        "reset_workspace",
        "delete_plugin"
    )

    @JvmStatic
    fun isPreWorkspaceTool(name: String): Boolean = normalize(name) in setOf("check_access", "runtime_diagnostics")

    @JvmStatic
    fun callPreWorkspaceTool(context: Context, name: String, args: JSONObject): String {
        require(isPreWorkspaceTool(name)) { "不是工作区预检工具: $name" }
        return when (normalize(name)) {
            "runtime_diagnostics" -> ScriptPluginAgentDiagnostics.read(context, args)
            else -> checkAccess(context, args)
        }
    }

    @JvmStatic
    fun displayName(name: String): String = when (normalize(name)) {
        "check_access" -> "检查插件文件权限"
        "runtime_diagnostics" -> "读取插件运行诊断"
        "list_files" -> "列出插件文件"
        "read_file" -> "读取插件文件"
        "search_files" -> "搜索插件文件"
        "create_directory" -> "创建插件目录"
        "write_file" -> "写入插件文件"
        "apply_patch" -> "修改插件文件"
        "replace_text" -> "替换插件文件文本"
        "run_shell" -> "在插件目录执行命令"
        "run_command" -> "在 Ubuntu 终端执行命令"
        "move_path" -> "移动插件路径"
        "delete_path" -> "删除插件路径"
        "restore_path" -> "恢复插件路径"
        "reset_workspace" -> "重置插件工作区"
        "delete_plugin" -> "删除整个插件"
        "show_diff" -> "查看代码差异"
        "workspace_status" -> "检查插件变更"
        else -> name
    }

    @JvmStatic
    fun open(context: Context, requestedId: String): Workspace {
        cleanupOldWorkspaces(context)
        val pluginRoot = ScriptPluginRuntime.ensureDirs(context).canonicalFile
        repairAccessEntry(pluginRoot)
        recoverInterruptedTransactions(context, pluginRoot)
        val target = requestedId.trim()
        require(target.isNotBlank()) { "plugin_id 不能为空" }
        val existing = ScriptPluginRuntime.listPlugins(context).firstOrNull {
            it.id.equals(target, ignoreCase = true) ||
                it.name.equals(target, ignoreCase = true) ||
                it.displayName?.equals(target, ignoreCase = true) == true
        }
        val pluginId = existing?.id ?: target
        require(pluginId == ScriptPluginAgentValidator.safePluginId(pluginId) && !pluginId.contains("..")) {
            "plugin_id 包含不允许的路径字符"
        }
        val requestedRoot = File(pluginRoot, pluginId).absoluteFile
        val originalRoot = requestedRoot.canonicalFile
        require(originalRoot.parentFile == pluginRoot && requestedRoot == originalRoot) {
            "插件目录不在脚本根目录内或使用了符号链接"
        }
        val existed = originalRoot.isDirectory
        if (existed) ensureReadableTree(originalRoot)
        val stageRoot = File(workspaceRoot(context), UUID.randomUUID().toString()).apply {
            check(mkdirs()) { "创建插件暂存工作区失败" }
        }
        val baseline = try {
            if (existed) {
                enforceLimits(originalRoot)
                copyTree(originalRoot, stageRoot)
                snapshot(stageRoot)
            } else {
                emptyMap()
            }
        } catch (error: Throwable) {
            runCatching { deleteTreeIfExists(stageRoot) }
            throw error
        }
        return Workspace(
            context = context.applicationContext ?: context,
            pluginId = pluginId,
            originalRoot = originalRoot,
            stageRoot = stageRoot,
            existed = existed,
            baseFingerprint = treeFingerprint(baseline),
            basePathStates = baseline.mapValues { it.value.serialized() },
            initialPluginName = existing?.displayName ?: existing?.name ?: pluginId
        )
    }

    @JvmStatic
    fun restore(context: Context, checkpoint: ScriptPluginAgentWorkspaceCheckpoint): Workspace {
        cleanupOldWorkspaces(context)
        require(checkpoint.updatedAt >= System.currentTimeMillis() - WORKSPACE_MAX_AGE_MS) {
            "插件工作区恢复点已过期"
        }
        val pluginRoot = ScriptPluginRuntime.ensureDirs(context).canonicalFile
        repairAccessEntry(pluginRoot)
        recoverInterruptedTransactions(context, pluginRoot)
        val pluginId = checkpoint.pluginId.trim()
        require(pluginId == ScriptPluginAgentValidator.safePluginId(pluginId) && !pluginId.contains("..")) {
            "插件工作区恢复点的 plugin_id 无效"
        }
        val requestedRoot = File(pluginRoot, pluginId).absoluteFile
        val originalRoot = requestedRoot.canonicalFile
        require(originalRoot.parentFile == pluginRoot && requestedRoot == originalRoot) {
            "插件工作区恢复点不在脚本根目录内"
        }
        require(checkpoint.baseFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "插件工作区恢复点缺少有效基线"
        }
        require(checkpoint.stageFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "插件工作区恢复点缺少有效内容指纹"
        }
        require(checkpoint.basePathStates.size <= MAX_FILES * 8) {
            "插件工作区恢复点包含过多路径"
        }
        require(checkpoint.basePathStates.all { (path, state) ->
            isSafeCheckpointPath(path) && state.length <= 256
        }) { "插件工作区恢复点包含无效路径" }
        require(checkpoint.revision >= 0 &&
            checkpoint.checkedRevision in -1..checkpoint.revision &&
            checkpoint.shownRevision in -1..checkpoint.revision
        ) { "插件工作区恢复点的 revision 无效" }
        val stageRoot = checkpointStage(context, checkpoint.stagingPath)
        ensureReadableTree(stageRoot)
        enforceLimits(stageRoot)
        require(treeFingerprint(stageRoot) == checkpoint.stageFingerprint) {
            "插件工作区内容与恢复点不一致"
        }
        stageRoot.setLastModified(System.currentTimeMillis())
        return Workspace(
            context = context.applicationContext ?: context,
            pluginId = pluginId,
            originalRoot = originalRoot,
            stageRoot = stageRoot,
            existed = checkpoint.existed,
            baseFingerprint = checkpoint.baseFingerprint,
            basePathStates = checkpoint.basePathStates,
            initialPluginName = checkpoint.initialPluginName.ifBlank { pluginId },
            initialRevision = checkpoint.revision,
            initialCheckedRevision = checkpoint.checkedRevision,
            initialShownRevision = checkpoint.shownRevision,
            initialDeletePlugin = checkpoint.deletePlugin
        )
    }

    @JvmStatic
    fun apply(context: Context, change: ScriptPluginAgentWorkspaceChange): Result<File> {
        return runCatching {
            ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(change.pluginId)) {
                applyLocked(context, change).getOrThrow()
            }
        }
    }

    private fun applyLocked(context: Context, change: ScriptPluginAgentWorkspaceChange): Result<File> {
        val result = runCatching {
            val stage = changeStage(context, change)
            if (!change.deletePlugin) {
                val validation = validateStage(change.pluginId, stage).validation
                check(validation.canSave) { validation.errors.joinToString("\n") { it.message } }
                enforceLimits(stage)
            }

            val root = ScriptPluginRuntime.ensureDirs(context).canonicalFile
            val requestedTarget = File(root, change.pluginId).absoluteFile
            val target = requestedTarget.canonicalFile
            check(target.parentFile == root && requestedTarget == target) {
                "插件目录不在脚本根目录内或使用了符号链接"
            }
            val changedPaths = (change.createdPaths + change.modifiedPaths + change.deletedPaths).distinct()
            if (change.existed) {
                check(target.isDirectory) { "目标插件已被删除，请重新生成修改" }
                ensureReadableTree(target)
                if (change.deletePlugin) {
                    check(treeFingerprint(target) == change.baseFingerprint) {
                        "目标插件已在生成后发生变化，请重新读取后删除"
                    }
                } else {
                    check(changedPaths.all { path ->
                        val currentState = pathState(target, path)
                        currentState == change.basePathStates[path] || currentState == pathState(stage, path)
                    }) {
                        "Agent 要修改的文件已在生成后发生变化，请重新读取后修改"
                    }
                }
            } else {
                check(!target.exists()) { "同名插件已存在，请重新读取后修改" }
            }

            ensureWritableDirectory(root, "脚本插件根目录")
            val suffix = UUID.randomUUID().toString().take(8)
            val prepared = File(root, ".${change.pluginId}.agent-new-$suffix")
            val movedBackup = File(root, ".${change.pluginId}.agent-old-$suffix")
            val copiedBackup = File(root, ".${change.pluginId}.agent-copy-$suffix")
            deleteTreeIfExists(prepared)
            deleteBackupArtifacts(movedBackup)
            deleteBackupArtifacts(copiedBackup)
            try {
                if (!change.deletePlugin) {
                    if (target.isDirectory) {
                        copyTree(target, prepared)
                    } else {
                        check(prepared.mkdirs()) { "创建提交准备目录失败" }
                    }
                    applyChangedPaths(stage, prepared, change)
                    val validation = validateStage(change.pluginId, prepared).validation
                    check(validation.canSave) { validation.errors.joinToString("\n") { it.message } }
                    enforceLimits(prepared)
                }
            } catch (error: Throwable) {
                runCatching { deleteTreeIfExists(prepared) }
                throw error
            }
            val wasEnabled = change.existed && ScriptPluginRuntime.isPluginEnabled(context, change.pluginId)
            var backup: File? = null
            var targetTouched = false
            var disabledForCommit = false
            try {
                ScriptPluginRuntime.setPluginEnabled(context, change.pluginId, false).getOrThrow()
                disabledForCommit = true
                if (target.exists()) {
                    val moveError = runCatching {
                        Os.rename(target.absolutePath, movedBackup.absolutePath)
                    }.exceptionOrNull()
                    if (moveError == null) {
                        backup = movedBackup
                        writeTransactionMarker(
                            transactionReadyMarker(movedBackup),
                            treeFingerprint(movedBackup)
                        )
                    } else {
                        backup = try {
                            ensureReplaceableTree(target, "原插件目录")
                            copyDirectorySnapshot(target, copiedBackup, "备份原插件目录失败")
                            copiedBackup
                        } catch (fallbackError: Throwable) {
                            fallbackError.addSuppressed(moveError)
                            throw fallbackError
                        }
                    }
                }
                targetTouched = true
                if (change.deletePlugin) {
                    deleteTreeIfExists(target)
                } else if (backup == copiedBackup && target.isDirectory) {
                    applyChangedPaths(stage, target, change)
                    check(treeFingerprint(target) == treeFingerprint(prepared)) {
                        "提交插件目录失败：目标目录内容校验不一致"
                    }
                } else {
                    replaceDirectory(prepared, target, "提交插件目录失败")
                }
                backup?.let { activeBackup ->
                    writeTransactionMarker(transactionCommittedMarker(activeBackup), "complete")
                }
            } catch (error: Throwable) {
                if (disabledForCommit) {
                    runCatching { ScriptPluginRuntime.setPluginEnabled(context, change.pluginId, false).getOrThrow() }
                }
                val activeBackup = backup
                var restoreFailure: Throwable? = null
                val restored = when {
                    activeBackup != null -> runCatching {
                        replaceDirectory(activeBackup, target, "恢复原插件目录失败")
                        true
                    }.getOrElse { restoreError ->
                        restoreFailure = restoreError
                        false
                    }
                    !targetTouched -> true
                    else -> runCatching {
                        deleteTreeIfExists(target)
                        true
                    }.getOrElse { restoreError ->
                        restoreFailure = restoreError
                        false
                    }
                }
                restoreFailure?.let { restoreError ->
                    HLog.e("[Hchat:ScriptAgent] 恢复插件目录失败: ${change.pluginId}", restoreError)
                }
                if (restored && activeBackup != null) {
                    runCatching {
                        ScriptPluginRuntime.refreshPluginObserver(context, change.pluginId)
                    }.onFailure { observerError ->
                        HLog.e("[Hchat:ScriptAgent] 恢复插件目录观察失败: ${change.pluginId}", observerError)
                    }
                    runCatching {
                        deleteBackupArtifacts(activeBackup)
                    }.onFailure { cleanupError ->
                        HLog.e("[Hchat:ScriptAgent] 清理已恢复的插件备份失败: ${activeBackup.path}", cleanupError)
                    }
                }
                val restoreLoadError = if (restored && wasEnabled) {
                    runCatching {
                        ScriptPluginRuntime.setPluginEnabled(context, change.pluginId, true).getOrThrow()
                    }.exceptionOrNull()
                } else {
                    null
                }
                runCatching { deleteTreeIfExists(prepared) }
                if (!restored) {
                    restoreFailure?.let(error::addSuppressed)
                    val backupPath = activeBackup?.path ?: "未创建备份"
                    throw IllegalStateException("提交失败且恢复原插件目录失败，备份位于 $backupPath", error)
                }
                if (restoreLoadError != null) {
                    error.addSuppressed(restoreLoadError)
                    throw IllegalStateException("提交失败，旧插件已恢复但重新启用失败", error)
                }
                throw error
            }
            runCatching { deleteTreeIfExists(prepared) }.onFailure { error ->
                HLog.e("[Hchat:ScriptAgent] 清理插件提交准备目录失败: ${prepared.path}", error)
            }
            runCatching { deleteBackupArtifacts(movedBackup) }.onFailure { error ->
                HLog.e("[Hchat:ScriptAgent] 清理插件事务备份失败: ${movedBackup.path}", error)
            }
            runCatching { deleteBackupArtifacts(copiedBackup) }.onFailure { error ->
                HLog.e("[Hchat:ScriptAgent] 清理插件事务备份失败: ${copiedBackup.path}", error)
            }
            runCatching {
                ScriptPluginRuntime.refreshPluginObserver(context, change.pluginId)
            }.onFailure { error ->
                HLog.e("[Hchat:ScriptAgent] 刷新插件目录观察失败: ${change.pluginId}", error)
            }
            HchatStorage.preferences(context, ScriptPluginSettings.PREFS_NAME).edit().apply {
                if (change.deletePlugin) {
                    remove(ScriptPluginSettings.pluginEnableKey(change.pluginId))
                } else {
                    putBoolean(ScriptPluginSettings.pluginEnableKey(change.pluginId), false)
                }
            }.commit()
            discard(context, change)
            target
        }
        return result
    }

    @JvmStatic
    fun discard(context: Context, change: ScriptPluginAgentWorkspaceChange) {
        runCatching { deleteTreeIfExists(changeStage(context, change)) }
    }

    @JvmStatic
    fun discard(context: Context, checkpoint: ScriptPluginAgentWorkspaceCheckpoint) {
        runCatching { deleteTreeIfExists(checkpointStage(context, checkpoint.stagingPath)) }
    }

    internal fun ensureLegacyWriteAccess(root: File, target: File) {
        repairAccessEntry(root)
        if (target.exists()) repairAccessTree(target)
        ensureWritableDirectory(root, "脚本插件根目录")
        if (target.isDirectory) ensureWritableDirectory(target, "插件目录 ${target.name}")
    }

    private fun checkAccess(context: Context, args: JSONObject): String {
        cleanupOldWorkspaces(context)
        val root = ScriptPluginRuntime.ensureDirs(context).canonicalFile
        recoverInterruptedTransactions(context, root)
        val pluginId = args.optString("plugin_id", "").trim()
        require(pluginId.isNotBlank()) { "plugin_id 不能为空" }
        require(pluginId == ScriptPluginAgentValidator.safePluginId(pluginId) && !pluginId.contains("..")) {
            "plugin_id 包含不允许的路径字符"
        }
        val requestedPlugin = File(root, pluginId).absoluteFile
        val pluginRoot = requestedPlugin.canonicalFile
        require(pluginRoot.parentFile == root && requestedPlugin == pluginRoot) {
            "插件目录不在脚本根目录内或使用了符号链接"
        }
        val relative = accessRelative(args.optString("path", "."))
        val requested = if (relative == ".") pluginRoot else File(pluginRoot, relative).absoluteFile
        val target = requested.canonicalFile
        require(
            target == pluginRoot || target.path.startsWith(pluginRoot.path + File.separator)
        ) { "检查路径超出插件目录" }
        // 注：符号链接允许，但 canonical 目标必须仍位于插件目录内（防逃逸）；
        // 插件目录内的符号链接（如 main.java → 共享源码）可正常读写

        val recursive = args.optBoolean("recursive", true)
        val repair = args.optBoolean("repair", false)
        if (repair) {
            repairAccessEntry(root)
            if (pluginRoot.exists()) {
                repairAccessTree(pluginRoot)
            } else {
                repairParents(target.parentFile, pluginRoot)
            }
        }

        val states = ArrayList<AccessState>()
        val traversalIssues = ArrayList<String>()
        val truncated = collectAccessStates(pluginRoot, target, recursive, states, traversalIssues)
        val rootProbe = probeDirectoryWrite(root)
        val commitProbe = probeTreeReplacement(pluginRoot)
        val rootWritable = rootProbe.success
        val canRead = !truncated && states.all { !it.exists || it.workspaceReadable } && traversalIssues.isEmpty()
        val canModify = !truncated && states.all { it.modifiable }
        val canCommitPlugin = rootWritable && commitProbe.success
        val issues = ArrayList<String>().apply {
            addAll(traversalIssues)
            states.filter { it.exists && !it.workspaceReadable }
                .forEach { add("${it.path} 当前不可读") }
            states.filter { !it.modifiable }
                .forEach { add("${it.path} 当前不可修改或替换") }
            if (truncated) add("权限检查结果超过 $MAX_ACCESS_RESULTS 项，请缩小 path 后继续检查")
            if (!rootWritable) add("脚本插件根目录不可写，无法提交插件目录：${rootProbe.detail}")
            addAll(commitProbe.issues.map { "插件目录无法安全替换：$it" })
        }.distinct()
        return JSONObject().apply {
            put("ok", true)
            put("pluginId", pluginId)
            put("path", relative)
            put("repairAttempted", repair)
            put("appUid", android.os.Process.myUid())
            put("scriptRootWritable", rootWritable)
            put("canRead", canRead)
            put("canModify", canModify)
            put("canCommitPlugin", canCommitPlugin)
            put("issues", JSONArray(issues))
            put("items", JSONArray().apply { states.forEach { put(it.toJson()) } })
            put("truncated", truncated)
            put(
                "recommendation",
                when {
                    issues.isEmpty() -> "当前路径可由插件 Agent 读取和修改"
                    !repair -> "请再次调用 check_access 并设置 repair=true"
                    else -> "当前微信进程无法修复这些权限，请用系统文件管理器重新复制该插件目录后再试"
                }
            )
        }.toString()
    }

    private fun collectAccessStates(
        pluginRoot: File,
        target: File,
        recursive: Boolean,
        result: MutableList<AccessState>,
        issues: MutableList<String>
    ): Boolean {
        var truncated = false
        fun visit(file: File) {
            if (result.size >= MAX_ACCESS_RESULTS) {
                truncated = true
                return
            }
            val state = accessState(pluginRoot, file)
            result += state
            if (state.symbolicLink) {
                // 符号链接：解析到真实文件继续遍历（插件目录内允许）
                if (!recursive || state.exists) return
            }
            if (!recursive || !file.isDirectory || truncated) return
            val children = file.listFiles()
            if (children == null) {
                issues += "${state.path} 无法列出目录内容"
                return
            }
            children.sortedBy { it.name }.forEach(::visit)
        }
        visit(target)
        return truncated
    }

    private fun accessState(pluginRoot: File, file: File): AccessState {
        val exists = file.exists()
        val symbolicLink = runCatching {
            file.absoluteFile != file.canonicalFile
        }.getOrDefault(false)
        // 符号链接解析到真实文件，检查真实文件的可操作性
        val realFile = if (symbolicLink && exists) {
            runCatching { file.canonicalFile }.getOrDefault(file)
        } else {
            file
        }
        val directory = realFile.isDirectory
        val parent = realFile.parentFile
        val parentWritable = parent?.let { it.isDirectory && it.canWrite() && it.canExecute() } == true
        val readable = realFile.exists() && realFile.canRead()
        val writable = realFile.exists() && realFile.canWrite()
        val executable = realFile.exists() && realFile.canExecute()
        val workspaceReadable = when {
            symbolicLink -> false
            !exists -> true
            directory -> readable && executable && realFile.listFiles() != null
            else -> readable && runCatching { FileInputStream(realFile).use { } }.isSuccess
        }
        val replaceable = parentWritable
        val modifiable = if (!exists) {
            parentWritable
        } else if (directory) {
            writable && executable
        } else {
            // 符号链接：解析到真实文件，检查真实文件是否可写
            writable || replaceable
        }
        val stat = runCatching { Os.stat(file.absolutePath) }.getOrNull()
        val relative = when {
            file == pluginRoot -> "."
            file.path.startsWith(pluginRoot.path + File.separator) ->
                file.relativeTo(pluginRoot).invariantSeparatorsPath
            else -> file.name
        }
        return AccessState(
            path = relative,
            exists = exists,
            type = when {
                symbolicLink -> "symbolic_link"
                !exists -> "missing"
                directory -> "directory"
                file.isFile -> "file"
                else -> "other"
            },
            readable = readable,
            writable = writable,
            executable = executable,
            symbolicLink = symbolicLink,
            parentWritable = parentWritable,
            replaceable = replaceable,
            workspaceReadable = workspaceReadable,
            modifiable = modifiable,
            mode = stat?.let { String.format(Locale.US, "%04o", it.st_mode and 0x0fff) }.orEmpty(),
            ownerUid = stat?.st_uid,
            ownerGid = stat?.st_gid
        )
    }

    private fun ensureReadableTree(root: File) {
        repairAccessTree(root)
        val failures = ArrayList<String>()
        fun inspect(file: File) {
            if (failures.size >= 8) return
            val real = runCatching { file.canonicalFile }.getOrDefault(file)
            // 符号链接解析到真实文件后继续检查其可读性（插件目录内链接允许）
            if (file.isDirectory) {
                if (!file.canRead() || !file.canExecute()) {
                    failures += "${file.path}: 目录不可读或不可进入"
                    return
                }
                val children = file.listFiles()
                if (children == null) {
                    failures += "${file.path}: 无法列出目录内容"
                    return
                }
                children.forEach(::inspect)
            } else if (!real.canRead() || runCatching { FileInputStream(real).use { } }.isFailure) {
                failures += "${file.path}: 文件不可读"
            }
        }
        inspect(root)
        check(failures.isEmpty()) {
            "插件文件权限不可用，当前微信进程无法修复：${failures.joinToString("；")}"
        }
    }

    private fun ensureWritableDirectory(directory: File, label: String) {
        repairAccessEntry(directory)
        val probe = probeDirectoryWrite(directory)
        check(probe.success) {
            val stat = runCatching { Os.stat(directory.absolutePath) }.getOrNull()
            val owner = stat?.let { "uid=${it.st_uid}, gid=${it.st_gid}" } ?: "无法读取所有者"
            "$label 不可写，当前微信进程 uid=${android.os.Process.myUid()}，$owner，${probe.detail}"
        }
    }

    private fun ensureReplaceableTree(root: File, label: String) {
        repairAccessTree(root)
        val probe = probeTreeReplacement(root)
        check(probe.success) {
            "$label 无法安全备份和替换：${probe.issues.joinToString("；")}"
        }
    }

    private fun repairParents(start: File?, boundary: File) {
        var current = start
        val chain = ArrayList<File>()
        while (current != null && current.path.startsWith(boundary.path)) {
            chain += current
            if (current == boundary) break
            current = current.parentFile
        }
        chain.asReversed().forEach(::repairAccessEntry)
    }

    private fun repairAccessTree(file: File) {
        if (!file.exists() || file.absoluteFile != file.canonicalFile) return
        repairAccessEntry(file)
        if (file.isDirectory) file.listFiles()?.forEach(::repairAccessTree)
    }

    private fun repairAccessEntry(file: File) {
        if (!file.exists()) return
        runCatching { file.setReadable(true, true) }
        runCatching { file.setWritable(true, true) }
        if (file.isDirectory) runCatching { file.setExecutable(true, true) }
        runCatching {
            val stat = Os.stat(file.absolutePath)
            val required = if (file.isDirectory) 0x1c0 else 0x180
            Os.chmod(file.absolutePath, (stat.st_mode and 0x0fff) or required)
        }
    }

    private fun probeDirectoryWrite(directory: File): DirectoryWriteProbe {
        if (!directory.isDirectory) return DirectoryWriteProbe(false, "目录不存在")
        if (!directory.canWrite() || !directory.canExecute()) {
            return DirectoryWriteProbe(false, "File.canWrite/canExecute=false")
        }
        val probe = File(directory, ".hchat-agent-access-${UUID.randomUUID().toString().take(8)}")
        return runCatching {
            FileOutputStream(probe).use { it.write(0) }
            check(probe.delete()) { "无法清理权限探针" }
            DirectoryWriteProbe(true, "写入探针成功")
        }.getOrElse { error ->
            runCatching { probe.delete() }
            DirectoryWriteProbe(
                false,
                "${error.javaClass.simpleName}: ${error.message.orEmpty().ifBlank { "未知写入错误" }}"
            )
        }
    }

    private fun probeTreeReplacement(root: File): TreeReplacementProbe {
        if (!root.exists()) return TreeReplacementProbe(true, emptyList())
        val issues = ArrayList<String>()
        var visited = 0
        fun relative(file: File): String = when {
            file == root -> "."
            file.path.startsWith(root.path + File.separator) ->
                file.relativeTo(root).invariantSeparatorsPath
            else -> file.name
        }
        fun inspect(file: File) {
            if (issues.size >= 8) return
            visited++
            if (visited > MAX_FILES * 8) {
                issues += "目录项过多，无法完成安全替换预检"
                return
            }
            val canonical = runCatching { file.canonicalFile }.getOrElse { error ->
                issues += "${relative(file)} 无法解析真实路径：${error.message.orEmpty()}"
                return
            }
            val isLink = file.absoluteFile != canonical
            val inspectTarget = if (isLink) canonical else file
            if (isLink && !inspectTarget.path.startsWith(root.path + File.separator)) {
                issues += "${relative(file)} 符号链接指向插件目录外"
                return
            }
            if (inspectTarget.isDirectory) {
                if (!file.canRead() || !file.canExecute()) {
                    issues += "${relative(file)} 目录不可读或不可进入"
                    return
                }
                val writeProbe = probeDirectoryWrite(file)
                if (!writeProbe.success) {
                    issues += "${relative(file)} 目录不可修改：${writeProbe.detail}"
                    return
                }
                val children = file.listFiles()
                if (children == null) {
                    issues += "${relative(file)} 无法列出目录内容"
                    return
                }
                children.forEach(::inspect)
            } else if (!file.isFile) {
                issues += "${relative(file)} 不是普通文件"
            } else if (!file.canRead() || runCatching { FileInputStream(file).use { } }.isFailure) {
                issues += "${relative(file)} 文件不可读，无法创建备份"
            }
        }
        inspect(root)
        return TreeReplacementProbe(issues.isEmpty(), issues)
    }

    private fun accessRelative(value: String): String {
        val clean = value.trim().replace('\\', '/').trim('/')
        if (clean.isBlank() || clean == ".") return "."
        require(!clean.startsWith('/') && !clean.contains('\u0000')) { "必须使用插件目录内的相对路径" }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) { "路径包含不允许的片段" }
        require(clean.length <= 240) { "路径过长" }
        return parts.joinToString("/")
    }

    private data class AccessState(
        val path: String,
        val exists: Boolean,
        val type: String,
        val readable: Boolean,
        val writable: Boolean,
        val executable: Boolean,
        val symbolicLink: Boolean,
        val parentWritable: Boolean,
        val replaceable: Boolean,
        val workspaceReadable: Boolean,
        val modifiable: Boolean,
        val mode: String,
        val ownerUid: Int?,
        val ownerGid: Int?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("path", path)
            put("exists", exists)
            put("type", type)
            put("readable", readable)
            put("writable", writable)
            put("executable", executable)
            put("symbolicLink", symbolicLink)
            put("parentWritable", parentWritable)
            put("replaceable", replaceable)
            put("workspaceReadable", workspaceReadable)
            put("modifiable", modifiable)
            if (mode.isNotBlank()) put("mode", mode)
            ownerUid?.let { put("ownerUid", it) }
            ownerGid?.let { put("ownerGid", it) }
        }
    }

    private data class TreeReplacementProbe(
        val success: Boolean,
        val issues: List<String>
    )

    private data class DirectoryWriteProbe(
        val success: Boolean,
        val detail: String
    )

    class Workspace internal constructor(
        private val context: Context,
        val pluginId: String,
        private val originalRoot: File,
        private val stageRoot: File,
        val existed: Boolean,
        private val baseFingerprint: String,
        private val basePathStates: Map<String, String>,
        private val initialPluginName: String,
        initialRevision: Int = 0,
        initialCheckedRevision: Int = -1,
        initialShownRevision: Int = -1,
        initialDeletePlugin: Boolean = false
    ) : AutoCloseable {
        var revision: Int = initialRevision
            private set
        private var deletePlugin = initialDeletePlugin
        private var checkedRevision = initialCheckedRevision
        private var shownRevision = initialShownRevision

        @Synchronized
        fun accepts(requestedId: String): Boolean {
            val value = requestedId.trim()
            if (value.equals(pluginId, ignoreCase = true)) return true
            return ScriptPluginRuntime.listPlugins(context).any {
                it.id.equals(pluginId, ignoreCase = true) &&
                    (it.name.equals(value, ignoreCase = true) ||
                        it.displayName?.equals(value, ignoreCase = true) == true)
            }
        }

        @Synchronized
        fun call(
            name: String,
            args: JSONObject,
            cancellation: ScriptPluginAgentCancellation?
        ): String {
            cancellation?.throwIfCancelled()
            require(accepts(args.optString("plugin_id", ""))) { "本轮只能操作插件 $pluginId" }
            val result = when (normalize(name)) {
                "list_files" -> listFiles(args, cancellation)
                "read_file" -> readFile(args)
                "search_files" -> searchFiles(args, cancellation)
                "create_directory" -> createDirectory(args)
                "write_file" -> writeFile(args)
                "apply_patch" -> applyPatch(args)
                "replace_text" -> replaceText(args)
                "run_shell" -> runShell(args)
                "run_command" -> runCommand(args)
                "move_path" -> movePath(args)
                "delete_path" -> deletePath(args)
                "restore_path" -> restorePath(args)
                "reset_workspace" -> resetWorkspace()
                "delete_plugin" -> deletePlugin()
                "show_diff" -> showDiff(args)
                "workspace_status" -> status()
                else -> error("未知插件工作区工具: $name")
            }
            cancellation?.throwIfCancelled()
            return result
        }

        @Synchronized
        fun ensureCommitReady() {
            val root = originalRoot.parentFile ?: error("插件根目录无效")
            repairAccessEntry(root)
            ensureWritableDirectory(root, "脚本插件根目录")
        }

        @Synchronized
        fun buildChange(): ScriptPluginAgentWorkspaceChange? {
            check(checkedRevision == revision) { "完成前必须对当前版本调用 workspace_status" }
            check(shownRevision == revision) { "完成前必须对当前版本调用 show_diff" }
            val summary = workspaceChangeSummary()
            if (!summary.hasChanges && !deletePlugin) return null
            val stageValidation = if (deletePlugin) null else validateStage(pluginId, stageRoot)
            val draft = stageValidation?.draft
            val validation = stageValidation?.validation
            check(validation?.canSave != false) { validation?.errors.orEmpty().joinToString("；") { it.message } }
            return ScriptPluginAgentWorkspaceChange(
                pluginId = pluginId,
                pluginName = draft?.pluginName ?: initialPluginName,
                stagingPath = stageRoot.canonicalPath,
                existed = existed,
                baseFingerprint = baseFingerprint,
                createdPaths = summary.created,
                modifiedPaths = summary.modified,
                deletedPaths = summary.deleted,
                diff = summary.diff,
                draft = draft,
                basePathStates = (summary.created + summary.modified + summary.deleted)
                    .distinct()
                    .associateWith { basePathStates[it] ?: "missing" },
                deletePlugin = deletePlugin,
                warnings = validation?.warnings.orEmpty()
            )
        }

        /**
         * 模型已经完成写入、但最终控制响应无法解析时，由客户端执行与
         * workspace_status + show_diff(path=".") 等价的只读收尾校验。
         * 不提交真实插件，只在校验通过后生成待用户确认的变更。
         */
        @Synchronized
        fun buildLocallyValidatedChange(): ScriptPluginAgentWorkspaceChange? {
            if (!hasChanges()) return null
            status()
            showDiff(JSONObject().put("path", "."))
            return buildChange()
        }

        @Synchronized
        fun hasChanges(): Boolean = deletePlugin || workspaceChangeSummary(includeDiff = false).hasChanges

        @Synchronized
        fun diffForTool(name: String, args: JSONObject): String {
            val relative = if (normalize(name) == "write_file") {
                normalizedRelative(args.optString("path", ""))
            } else {
                "."
            }
            return workspaceChangeSummary(relative).diff
        }

        @Synchronized
        fun checkpoint(): ScriptPluginAgentWorkspaceCheckpoint {
            val now = System.currentTimeMillis()
            stageRoot.setLastModified(now)
            return ScriptPluginAgentWorkspaceCheckpoint(
                stagingPath = stageRoot.canonicalPath,
                pluginId = pluginId,
                existed = existed,
                baseFingerprint = baseFingerprint,
                stageFingerprint = treeFingerprint(stageRoot),
                basePathStates = basePathStates,
                initialPluginName = initialPluginName,
                revision = revision,
                checkedRevision = checkedRevision,
                shownRevision = shownRevision,
                deletePlugin = deletePlugin,
                updatedAt = now
            )
        }

        override fun close() {
            runCatching { deleteTreeIfExists(stageRoot) }
        }

        private fun listFiles(args: JSONObject, cancellation: ScriptPluginAgentCancellation?): String {
            val relative = normalizedRelative(args.optString("path", "."), allowRoot = true)
            val target = resolve(relative, allowRoot = true)
            require(target.exists()) { "路径不存在: $relative" }
            val recursive = args.optBoolean("recursive", false)
            val maxDepth = args.optInt("max_depth", 3).coerceIn(1, 8)
            val items = JSONArray()
            if (target.isFile) {
                items.put(fileItem(target))
            } else {
                val baseDepth = depth(target)
                target.walkTopDown().onEnter { directory ->
                    cancellation?.throwIfCancelled()
                    recursive || directory == target
                }.filter { it != target }
                    .take(MAX_LIST_RESULTS)
                    .forEach { file ->
                        cancellation?.throwIfCancelled()
                        if (recursive && depth(file) - baseDepth > maxDepth) return@forEach
                        if (!recursive && file.parentFile != target) return@forEach
                        items.put(fileItem(file))
                    }
            }
            return ok().apply {
                put("path", relative)
                put("items", items)
                put("truncated", items.length() >= MAX_LIST_RESULTS)
            }.toString()
        }

        private fun readFile(args: JSONObject): String {
            val relative = normalizedRelative(args.optString("path", ""))
            val file = resolve(relative)
            require(file.isFile) { "文件不存在: $relative" }
            require(file.length() <= MAX_TEXT_BYTES) { "文件超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB，不能作为文本读取" }
            val bytes = file.readBytes()
            require(isText(bytes)) { "文件不是可读取的文本文件: $relative" }
            val text = bytes.toString(Charsets.UTF_8)
            val lines = text.replace("\r\n", "\n").replace('\r', '\n').let { normalized ->
                when {
                    normalized.isEmpty() -> emptyList()
                    normalized.endsWith('\n') -> normalized.dropLast(1).split('\n')
                    else -> normalized.split('\n')
                }
            }
            val startLine = args.optInt("start_line", 1).coerceAtLeast(1)
            val startColumn = args.optInt("start_column", 1).coerceAtLeast(1)
            val maxLines = args.optInt("max_lines", 2_000).coerceIn(1, 5_000)
            val requestedEnd = args.optInt("end_line", 0)
            // 文件较小（字符数在单次读取上限内）时一次返回全部，无需分页
            val wholeFile = if (requestedEnd <= 0 && startLine == 1 && startColumn == 1) {
                text.length <= MAX_READ_CHARS
            } else false
            val endLine = if (wholeFile) {
                lines.size
            } else if (requestedEnd > 0) {
                requestedEnd.coerceAtLeast(startLine).coerceAtMost(lines.size)
            } else {
                (startLine + maxLines - 1).coerceAtMost(lines.size)
            }
            val numbered = args.optBoolean("include_line_numbers", true)
            val content = StringBuilder()
            var nextLine = 0
            var nextColumn = 0
            if (startLine <= lines.size) {
                for (lineNumber in startLine..endLine) {
                    val original = lines[lineNumber - 1]
                    val column = if (lineNumber == startLine) startColumn.coerceAtMost(original.length + 1) else 1
                    val value = original.substring(column - 1)
                    val prefix = if (numbered) {
                        if (column > 1) "$lineNumber:$column | " else "$lineNumber | "
                    } else {
                        ""
                    }
                    val separatorLength = if (content.isEmpty()) 0 else 1
                    val capacity = MAX_READ_CHARS - content.length - separatorLength - prefix.length
                    if (capacity <= 0) {
                        nextLine = lineNumber
                        nextColumn = column
                        break
                    }
                    if (content.isNotEmpty()) content.append('\n')
                    content.append(prefix)
                    if (value.length > capacity) {
                        content.append(value.take(capacity))
                        nextLine = lineNumber
                        nextColumn = column + capacity
                        break
                    }
                    content.append(value)
                    if (lineNumber == endLine && endLine < lines.size) {
                        nextLine = endLine + 1
                        nextColumn = 1
                    }
                }
            }
            return ok().apply {
                put("path", relative)
                put("startLine", startLine)
                put("startColumn", startColumn)
                put("endLine", endLine)
                put("returnedLength", content.length)
                put("totalLength", text.length)
                put("totalLines", lines.size)
                put("truncated", nextLine > 0)
                if (nextLine > 0) {
                    put("nextLine", nextLine)
                    put("nextColumn", nextColumn)
                }
                put("content", content.toString())
            }.toString()
        }

        private fun searchFiles(args: JSONObject, cancellation: ScriptPluginAgentCancellation?): String {
            val query = args.optString("query", "")
            require(query.isNotBlank()) { "query 不能为空" }
            val relative = normalizedRelative(args.optString("path", "."), allowRoot = true)
            val target = resolve(relative, allowRoot = true)
            require(target.exists()) { "搜索路径不存在: $relative" }
            val caseSensitive = args.optBoolean("case_sensitive", false)
            val regexMode = args.optBoolean("regex", false)
            val limit = args.optInt("limit", 50).coerceIn(1, MAX_SEARCH_RESULTS)
            val filePattern = args.optString("file_pattern", "").trim()
            val excludePattern = args.optString("exclude_pattern", "").trim()
            val pathRegex = filePattern.takeIf { it.isNotBlank() }?.let(::wildcardRegex)
            val excludeRegex = excludePattern.takeIf { it.isNotBlank() }?.let(::wildcardRegex)
            val beforeContext = args.optInt("before_context", 0).coerceIn(0, 10)
            val afterContext = args.optInt("after_context", 0).coerceIn(0, 10)
            val regex = if (regexMode) {
                Regex(query, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
            } else null
            val expected = if (caseSensitive) query else query.lowercase()
            val matches = JSONArray()
            val files = if (target.isFile) sequenceOf(target) else target.walkTopDown().asSequence().filter { it.isFile }
            for (file in files) {
                cancellation?.throwIfCancelled()
                if (matches.length() >= limit) break
                val filePath = relativePath(file)
                if (pathRegex != null && !pathRegex.matches(filePath)) continue
                if (excludeRegex != null && excludeRegex.matches(filePath)) continue
                if (file.length() > MAX_TEXT_BYTES) continue
                val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
                if (!isText(bytes)) continue
                val lines = bytes.toString(Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').split('\n')
                var lineIndex = 0
                while (lineIndex < lines.size && matches.length() < limit) {
                    cancellation?.throwIfCancelled()
                    val line = lines[lineIndex]
                    val index = if (regex != null) {
                        regex.find(line)?.range?.first ?: -1
                    } else {
                        val actual = if (caseSensitive) line else line.lowercase()
                        actual.indexOf(expected)
                    }
                    if (index >= 0) {
                        matches.put(JSONObject().apply {
                            put("path", filePath)
                            put("line", lineIndex + 1)
                            put("column", index + 1)
                            put("preview", line.take(500))
                            if (beforeContext > 0) {
                                put("before", contextLines(lines, lineIndex - beforeContext, lineIndex))
                            }
                            if (afterContext > 0) {
                                put("after", contextLines(lines, lineIndex + 1, lineIndex + 1 + afterContext))
                            }
                        })
                    }
                    lineIndex++
                }
            }
            return ok().apply {
                put("query", query)
                put("matches", matches)
                put("truncated", matches.length() >= limit)
            }.toString()
        }

        private fun createDirectory(args: JSONObject): String {
            requireNotDeletingPlugin()
            val relative = normalizedRelative(args.optString("path", ""))
            val directory = resolve(relative)
            require(!directory.isFile) { "同名文件已存在: $relative" }
            val created = !directory.isDirectory
            if (created) check(directory.mkdirs()) { "创建目录失败: $relative" }
            if (created) changed()
            return stagedOk().apply {
                put("path", relative)
                put("created", created)
            }.toString()
        }

        private fun writeFile(args: JSONObject): String {
            requireNotDeletingPlugin()
            val relative = normalizedRelative(args.optString("path", ""))
            val file = resolve(relative).resolveSymlink()
            val content = args.optString("content", "")
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            require(contentBytes.size <= MAX_TEXT_BYTES) { "写入内容超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB" }
            require(!file.isDirectory) { "目标是目录: $relative" }
            val existed = file.exists()
            require(!existed || args.optBoolean("overwrite", false)) { "文件已存在；局部修改请使用 apply_patch，完整覆盖需传 overwrite=true" }
            ensureFileWithinLimits(file, contentBytes.size.toLong())
            file.parentFile?.let { parent -> if (!parent.isDirectory) check(parent.mkdirs()) { "创建父目录失败" } }
            atomicWrite(file, content)
            changed()
            return stagedOk().apply {
                put("path", relative)
                put("created", !existed)
                put("bytes", file.length())
            }.toString()
        }

        private fun applyPatch(args: JSONObject): String {
            requireNotDeletingPlugin()
            val warnings = mutableListOf<String>()
            val patch = args.optString("patch", "")
            require(patch.isNotBlank()) { "patch 不能为空" }
            require(patch.toByteArray(Charsets.UTF_8).size <= MAX_PATCH_BYTES) {
                "补丁超过 ${MAX_PATCH_BYTES / 1024 / 1024} MB"
            }
            val plan = ScriptPluginAgentUnifiedPatch.plan(
                rawPatch = patch,
                normalizePath = { normalizedRelative(it) },
                readText = ::readPatchText
            )
            ensurePlanWithinLimits(plan)
            plan.changes.filter { it.content == null }.forEach { change ->
                val target = resolve(change.path).resolveSymlink()
                if (target.exists()) {
                    require(target.isFile) { "统一补丁只能删除文件: ${change.path}" }
                    deleteTree(target)
                }
            }
            plan.changes.filter { it.content != null }.forEach { change ->
                val target = resolve(change.path).resolveSymlink()
                if (target.isDirectory && target.listFiles().orEmpty().isEmpty()) {
                    check(target.delete()) { "替换空目录失败: ${change.path}" }
                }
                require(!target.isDirectory) { "统一补丁目标是目录: ${change.path}" }
                target.parentFile?.let { parent ->
                    if (!parent.isDirectory) check(parent.mkdirs()) { "创建父目录失败: ${change.path}" }
                }
                val beforeLines = if (target.isFile) {
                    runCatching { target.readText(Charsets.UTF_8).lineSequence().count() }.getOrDefault(0)
                } else 0
                atomicWrite(target, change.content.orEmpty())
                val afterLines = change.content.orEmpty().lineSequence().count()
                if (beforeLines > 500) {
                    val pct = if (beforeLines > 0) {
                        ((afterLines - beforeLines).toDouble() / beforeLines * 100).toInt()
                    } else 0
                    if (kotlin.math.abs(pct) > 30) {
                        warnings += "${change.path} 行数变化 ${beforeLines} -> ${afterLines}（${pct}%），超过 30%，请确认 diff 是否异常"
                    }
                }
            }
            changed()
            return stagedOk().apply {
                put("files", JSONArray().apply {
                    plan.changes.forEach { change ->
                        put(JSONObject().apply {
                            put("path", change.path)
                            put("operation", change.operation)
                            if (change.sourcePath.isNotBlank()) put("sourcePath", change.sourcePath)
                            change.content?.let { put("bytes", it.toByteArray(Charsets.UTF_8).size) }
                        })
                    }
                })
                if (warnings.isNotEmpty()) put("warnings", JSONArray(warnings))
            }.toString()
        }

        private fun replaceText(args: JSONObject): String {
            requireNotDeletingPlugin()
            val warnings = mutableListOf<String>()
            val relative = normalizedRelative(args.optString("path", ""))
            val file = resolve(relative).resolveSymlink()
            require(file.isFile) { "文件不存在: $relative" }
            require(file.length() <= MAX_TEXT_BYTES) { "文件超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB" }
            val search = args.optString("search_string", "")
            require(search.isNotBlank()) { "search_string 不能为空" }
            val replacement = args.optString("replacement", "")
            val occurrence = args.optInt("occurrence", 1)
            val text = file.readText(Charsets.UTF_8)

            // 统计所有出现位置
            val positions = ArrayList<Int>()
            var from = 0
            while (true) {
                val idx = text.indexOf(search, from)
                if (idx < 0) break
                positions += idx
                from = idx + search.length
            }
            require(positions.isNotEmpty()) { "未找到匹配的 search_string，请检查原文" }
            if (occurrence != -1) {
                require(occurrence in 1..positions.size) {
                    "occurrence=$occurrence 超出匹配次数（共 ${positions.size} 次）"
                }
            }

            val beforeLines = text.lineSequence().count()
            val updated = if (occurrence == -1) {
                text.replace(search, replacement)
            } else {
                val idx = positions[occurrence - 1]
                text.substring(0, idx) + replacement + text.substring(idx + search.length)
            }
            val afterLines = updated.lineSequence().count()
            if (beforeLines > 500 && kotlin.math.abs(afterLines - beforeLines) * 100 / beforeLines > 30) {
                warnings += "$relative 行数变化 $beforeLines -> $afterLines，超过 30%，请确认替换是否异常"
            }
            atomicWrite(file, updated)
            changed()
            return stagedOk().apply {
                put("path", relative)
                put("occurrences", positions.size)
                put("replaced", if (occurrence == -1) positions.size else 1)
                if (warnings.isNotEmpty()) put("warnings", JSONArray(warnings))
            }.toString()
        }

        private fun runShell(args: JSONObject): String {
            requireNotDeletingPlugin()
            val command = args.optString("command", "").trim()
            require(command.isNotBlank()) { "command 不能为空" }
            unsafeShellCommand(command)?.let { reason ->
                return ok().apply {
                    put("error", "命令被沙箱拦截")
                    put("reason", reason)
                }.toString()
            }
            val timeout = args.optInt("timeout_seconds", 15).coerceIn(1, 60)
            val result = runShellProcess(command, timeout)
            changed()
            return stagedOk().apply {
                put("command", command)
                put("exit_info", result.exitInfo)
                put("output", result.text)
            }.toString()
        }

        private fun runShellProcess(command: String, timeoutSeconds: Int): ShellResult {
            val pb = ProcessBuilder("/system/bin/sh", "-c", command)
            pb.directory(stageRoot)
            pb.redirectErrorStream(true)
            val process = try {
                pb.start()
            } catch (e: Throwable) {
                return ShellResult("退出码: -1", "启动 shell 失败: ${e.message.orEmpty()}")
            }
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val partial = readShellOutput(process)
                return ShellResult("退出码: -1 (超时 ${timeoutSeconds}s)", "命令超时，已强制结束。\n部分输出:\n$partial")
            }
            val out = readShellOutput(process)
            return ShellResult("退出码: ${process.exitValue()}", out)
        }

        private fun readShellOutput(process: Process): String {
            return try {
                val buffer = java.io.ByteArrayOutputStream()
                val bytes = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = process.inputStream.read(bytes)
                    if (n < 0) break
                    buffer.write(bytes, 0, n)
                    total += n
                    if (total >= MAX_TEXT_BYTES) break
                }
                var text = String(buffer.toByteArray(), Charsets.UTF_8)
                if (total >= MAX_TEXT_BYTES) text += "\n...[输出已截断]"
                text
            } catch (e: Throwable) {
                "(读取输出失败: ${e.message.orEmpty()})"
            }
        }

        private data class ShellResult(val exitInfo: String, val text: String)

        private fun runCommand(args: JSONObject): String {
            requireNotDeletingPlugin()
            val command = args.optString("command", "").trim()
            require(command.isNotBlank()) { "command 不能为空" }
            unsafeShellCommand(command)?.let { reason ->
                return ok().apply {
                    put("error", "命令被沙箱拦截")
                    put("reason", reason)
                }.toString()
            }
            if (!ProotEnvironment.isReady(context)) {
                return ok().apply {
                    put("error", "Ubuntu 终端环境未安装")
                    put("hint", "请在「插件 Agent 设置 → 终端环境」中安装，或让用户确认后触发安装")
                    put("state", ProotEnvironment.currentStatus().state.name)
                }.toString()
            }
            if (ProotEnvironment.needsUpgrade(context)) {
                if (ProotEnvironment.refreshStatus(context).state != ProotEnvironment.State.INSTALLING) {
                    Thread { runCatching { ProotEnvironment.install(context) } }.start()
                }
                return ok().apply {
                    put("error", "Ubuntu 终端环境正在更新（新增内置 codex），请稍后重试")
                    put("hint", "环境更新在后台进行，约 1-3 分钟；完成后重试即可")
                    put("state", ProotEnvironment.currentStatus().state.name)
                }.toString()
            }
            val timeout = args.optInt("timeout_seconds", 60).coerceIn(1, 600)
            val result = runProotProcess(command, timeout)
            changed()
            return stagedOk().apply {
                put("command", command)
                put("exit_info", result.exitInfo)
                put("output", result.text)
            }.toString()
        }

        private fun runProotProcess(command: String, timeoutSeconds: Int): ShellResult {
            val proot = ProotEnvironment.prootBinary(context)
            if (!proot.canExecute()) {
                return ShellResult("退出码: -1", "proot 二进制不可执行: ${proot.absolutePath}")
            }
            val sandbox = ProotEnvironment.sandboxDir(context)
            val bin = ProotEnvironment.binDir(context)
            val home = ProotEnvironment.homeDir(context)
            val loaderLib = ProotEnvironment.loaderLib(context)
            val stageMount = "/plugin"

            val procArgs = mutableListOf(proot.absolutePath, "--kill-on-exit", "-w", stageMount)
            for (mnt in ProotEnvironment.systemBinds()) {
                val f = File(mnt)
                if (f.exists()) procArgs += listOf("-b", f.canonicalPath)
            }
            procArgs += listOf(
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys",
                "-b", "/dev/urandom:/dev/random",
                "-b", "${home.absolutePath}:/home",
                "-b", "${home.absolutePath}:/root",
                "-b", "${stageRoot.absolutePath}:$stageMount",
                "-r", sandbox.absolutePath,
                "-0",
                "--link2symlink",
                "--sysvipc",
                "-L",
                "/bin/bash", "-c",
                "export PATH=/bin:/sbin:/usr/bin:/usr/sbin:/usr/local/bin:/usr/local/sbin; " +
                    "export HOME=/root; cd $stageMount 2>/dev/null; $command",
            )
            val pb = ProcessBuilder(procArgs)
            pb.redirectErrorStream(true)
            pb.environment().apply {
                put("PROOT_TMP_DIR", File(ProotEnvironment.envRoot(context), "tmp").absolutePath)
                put("PROOT_LOADER", loaderLib.absolutePath)
            }
            val process = try {
                pb.start()
            } catch (e: Throwable) {
                return ShellResult("退出码: -1", "启动 proot 失败: ${e.message.orEmpty()}")
            }
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                val partial = readShellOutput(process)
                return ShellResult(
                    "退出码: -1 (超时 ${timeoutSeconds}s)",
                    "命令超时，已强制结束。\n部分输出:\n$partial",
                )
            }
            val out = readShellOutput(process)
            return ShellResult("退出码: ${process.exitValue()}", out)
        }


        /** 安全检查：只拦真正危险/破坏性的命令（系统目录和网络不做拦截），返回拦截原因，null 表示放行 */
        private fun unsafeShellCommand(command: String): String? {
            val lower = command.lowercase(Locale.ROOT)
            val dangerous = arrayOf(
                ":(){", "rm -rf /", "rm -rf /*", "rm -fr /", "mkfs", "dd if=/dev/", "fdisk", "parted",
                "reboot", "shutdown", "halt", "poweroff", "mount ", "umount ", "chmod 777 /",
                "chmod -r /", "chown root", "passwd ", "killall", "pkill", "kill -9", "kill -s"
            )
            for (d in dangerous) if (lower.contains(d)) return "拦截危险命令模式: ${d.trim()}"
            return null
        }

        private fun movePath(args: JSONObject): String {
            requireNotDeletingPlugin()
            val sourcePath = normalizedRelative(args.optString("source", ""))
            val destinationPath = normalizedRelative(args.optString("destination", ""))
            val source = resolve(sourcePath)
            val destination = resolve(destinationPath)
            require(source.exists()) { "源路径不存在: $sourcePath" }
            require(source != destination) { "源路径和目标路径相同" }
            if (source.isDirectory) {
                require(!destination.canonicalPath.startsWith(source.canonicalPath + File.separator)) { "不能把目录移动到自身内部" }
            }
            if (destination.exists()) {
                require(args.optBoolean("overwrite", false)) { "目标路径已存在: $destinationPath" }
                deleteTree(destination)
            }
            destination.parentFile?.let { parent -> if (!parent.isDirectory) check(parent.mkdirs()) { "创建目标父目录失败" } }
            check(source.renameTo(destination)) { "移动路径失败" }
            changed()
            return stagedOk().apply {
                put("source", sourcePath)
                put("destination", destinationPath)
            }.toString()
        }

        private fun deletePath(args: JSONObject): String {
            requireNotDeletingPlugin()
            val relative = normalizedRelative(args.optString("path", ""))
            val target = resolve(relative)
            require(target.exists()) { "路径不存在: $relative" }
            deleteTree(target)
            changed()
            return stagedOk().apply { put("path", relative) }.toString()
        }

        private fun restorePath(args: JSONObject): String {
            requireNotDeletingPlugin()
            requireOriginalUnchanged()
            val relative = normalizedRelative(args.optString("path", ""))
            val target = resolve(relative)
            val requestedOriginal = File(originalRoot, relative).absoluteFile
            val original = requestedOriginal.canonicalFile
            require(requestedOriginal == original &&
                (original == originalRoot || original.path.startsWith(originalRoot.path + File.separator))
            ) {
                "恢复路径超出原插件目录或使用了符号链接"
            }
            require(target.exists() || original.exists()) { "路径在原插件和工作区中都不存在: $relative" }
            if (target.exists()) deleteTree(target)
            if (original.exists()) copyPath(original, target)
            enforceLimits(stageRoot)
            changed()
            return stagedOk().apply {
                put("path", relative)
                put("restored", original.exists())
                put("removedNewPath", !original.exists())
            }.toString()
        }

        private fun resetWorkspace(): String {
            requireOriginalUnchanged()
            stageRoot.listFiles()?.forEach(::deleteTree)
            if (existed) copyTree(originalRoot, stageRoot)
            deletePlugin = false
            changed()
            return stagedOk().apply {
                put("reset", true)
                put("hasChanges", false)
            }.toString()
        }

        private fun requireOriginalUnchanged() {
            require(treeFingerprint(originalRoot) == baseFingerprint) {
                "原插件已在任务期间发生变化，请重新读取后修改"
            }
        }

        private fun deletePlugin(): String {
            require(existed) { "新插件尚未存在，不能删除整个插件" }
            val newlyMarked = !deletePlugin
            if (newlyMarked) stageRoot.listFiles()?.forEach(::deleteTree)
            deletePlugin = true
            if (newlyMarked) changed()
            return stagedOk().apply {
                put("deletePlugin", true)
                put("requiresConfirmation", true)
            }.toString()
        }

        private fun showDiff(args: JSONObject): String {
            val requested = args.optString("path", "").trim()
            val relative = if (requested.isBlank() || requested == ".") {
                "."
            } else {
                normalizedRelative(requested)
            }
            val summary = workspaceChangeSummary(relative)
            if (relative == ".") shownRevision = revision
            return ok().apply {
                put("path", relative)
                put("completeDiff", relative == ".")
                put("hasChanges", summary.hasChanges || deletePlugin)
                put("created", JSONArray(summary.created))
                put("modified", JSONArray(summary.modified))
                put("deleted", JSONArray(summary.deleted))
                put("diff", summary.diff)
                put("truncated", summary.diffTruncated)
            }.toString()
        }

        private var statusNoChangeCount = 0
        private var lastStatusRevision = -1

        private fun status(): String {
            val summary = workspaceChangeSummary(includeDiff = false)
            val validation = if (deletePlugin) {
                ScriptPluginAgentValidation(emptyList())
            } else {
                runCatching {
                    validateStage(pluginId, stageRoot).validation
                }.getOrElse {
                    ScriptPluginAgentValidation(
                        listOf(ScriptPluginAgentIssue(ScriptPluginAgentIssueLevel.ERROR, it.message ?: "插件文件不完整"))
                    )
                }
            }
            // 循环刹车：连续多次调用 status 但工作区没有新改动，提示模型停止空转
            val noProgress = revision == lastStatusRevision
            lastStatusRevision = revision
            statusNoChangeCount = if (noProgress) statusNoChangeCount + 1 else 0
            checkedRevision = revision
            return ok().apply {
                put("hasChanges", summary.hasChanges || deletePlugin)
                put("deletePlugin", deletePlugin)
                put("created", JSONArray(summary.created))
                put("modified", JSONArray(summary.modified))
                put("deleted", JSONArray(summary.deleted))
                put("canApply", validation.canSave)
                put("validationScope", if (deletePlugin) "plugin_deletion" else "files_and_beanshell_syntax")
                put("runtimeVerified", false)
                put("errors", JSONArray(validation.errors.map { it.message }))
                put("warnings", JSONArray(validation.warnings.map { it.message }))
                put("requiresDiff", true)
                if (statusNoChangeCount >= 3) {
                    put("loopWarning",
                        "你已连续 $statusNoChangeCount 次检查工作区但没有任何新改动，请停止空转：若 canApply=true 且 diff 符合预期，请立即提交；" +
                        "若存在无法修复的校验错误，请停止并说明原因，不要继续重复检查。")
                }
            }.toString()
        }

        private fun changed() {
            revision++
            checkedRevision = -1
            shownRevision = -1
            stageRoot.setLastModified(System.currentTimeMillis())
        }

        private fun workspaceChangeSummary(
            pathPrefix: String = ".",
            includeDiff: Boolean = true
        ): ChangeSummary {
            return changeSummary(
                beforeStates = basePathStates,
                beforeRoot = originalRoot,
                afterRoot = stageRoot,
                pathPrefix = pathPrefix,
                includeDiff = includeDiff
            )
        }

        private fun requireNotDeletingPlugin() {
            require(!deletePlugin) { "整个插件已标记删除，不能继续修改文件" }
        }

        private fun ensureFileWithinLimits(target: File, newSize: Long) {
            var files = 1
            var bytes = newSize
            stageRoot.walkTopDown().filter { it.isFile && it.canonicalFile != target.canonicalFile }.forEach { file ->
                files++
                bytes += file.length()
                require(files <= MAX_FILES) { "插件文件数量超过 $MAX_FILES" }
                require(bytes <= MAX_TOTAL_BYTES) { "插件总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB" }
            }
        }

        private fun readPatchText(relative: String): String? {
            val file = resolve(relative)
            if (!file.exists()) return null
            require(file.isFile) { "统一补丁只能操作文本文件: $relative" }
            require(file.length() <= MAX_TEXT_BYTES) { "文件超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB: $relative" }
            val bytes = file.readBytes()
            require(isText(bytes)) { "文件不是可修改的文本文件: $relative" }
            return bytes.toString(Charsets.UTF_8)
        }

        private fun ensurePlanWithinLimits(plan: ScriptPluginAgentUnifiedPatch.Plan) {
            val sizes = LinkedHashMap<String, Long>()
            stageRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                sizes[relativePath(file)] = file.length()
            }
            plan.changes.forEach { change ->
                val content = change.content
                if (content == null) {
                    sizes.remove(change.path)
                } else {
                    val size = content.toByteArray(Charsets.UTF_8).size.toLong()
                    require(size <= MAX_TEXT_BYTES) {
                        "${change.path} 超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB"
                    }
                    sizes[change.path] = size
                }
            }
            require(sizes.size <= MAX_FILES) { "插件文件数量超过 $MAX_FILES" }
            require(sizes.values.sum() <= MAX_TOTAL_BYTES) {
                "插件总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB"
            }
        }

        private fun resolve(relative: String, allowRoot: Boolean = false): File {
            val file = if (relative == ".") stageRoot else File(stageRoot, relative)
            val canonical = file.canonicalFile
            val root = stageRoot.canonicalFile
            require(canonical == root || canonical.path.startsWith(root.path + File.separator)) { "路径超出插件工作区" }
            require(allowRoot || canonical != root) { "不能操作插件工作区根目录" }
            return canonical
        }

        private fun normalizedRelative(value: String, allowRoot: Boolean = false): String {
            val clean = value.trim().replace('\\', '/').trim('/')
            if (clean.isBlank() || clean == ".") {
                require(allowRoot) { "path 不能为空" }
                return "."
            }
            require(!clean.startsWith('/') && !clean.contains('\u0000')) { "必须使用插件目录内的相对路径" }
            val parts = clean.split('/')
            require(parts.none { it.isBlank() || it == "." || it == ".." }) { "路径包含不允许的片段" }
            require(clean.length <= 240) { "路径过长" }
            return parts.joinToString("/")
        }

        private fun relativePath(file: File): String = file.relativeTo(stageRoot).invariantSeparatorsPath

        private fun fileItem(file: File): JSONObject = JSONObject().apply {
            put("path", relativePath(file))
            put("type", if (file.isDirectory) "directory" else "file")
            if (file.isFile) put("size", file.length())
        }

        private fun depth(file: File): Int = relativePath(file).count { it == '/' }

        private fun ok(): JSONObject = JSONObject().apply {
            put("ok", true)
            put("pluginId", pluginId)
            put("revision", revision)
        }

        private fun stagedOk(): JSONObject = ok().apply {
            put("staged", true)
            put("persisted", false)
            put("message", "变更仅写入暂存工作区，尚未提交到真实插件目录")
        }
    }

    private data class TreeEntry(val directory: Boolean, val size: Long, val digest: String)

    private fun TreeEntry?.serialized(): String = when (this) {
        null -> "missing"
        else -> "${if (directory) "directory" else "file"}:$size:$digest"
    }

    private fun pathState(root: File, relative: String): String {
        val requested = File(root, relative).absoluteFile
        val target = requested.canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) {
            "变更路径超出插件目录: $relative"
        }
        return when {
            !target.exists() -> null
            target.isDirectory -> TreeEntry(true, 0L, "")
            else -> TreeEntry(false, target.length(), fileDigest(target))
        }.serialized()
    }

    private fun applyChangedPaths(
        stage: File,
        prepared: File,
        change: ScriptPluginAgentWorkspaceChange
    ) {
        change.deletedPaths.sortedByDescending { it.count { char -> char == '/' } }.forEach { relative ->
            deleteTreeIfExists(File(prepared, relative))
        }
        (change.createdPaths + change.modifiedPaths)
            .distinct()
            .sortedBy { it.count { char -> char == '/' } }
            .forEach { relative ->
                val source = File(stage, relative)
                val destination = File(prepared, relative)
                require(source.exists()) { "暂存变更路径不存在: $relative" }
                if (source.isDirectory) {
                    if (destination.exists() && !destination.isDirectory) deleteTree(destination)
                    if (!destination.isDirectory) check(destination.mkdirs()) { "创建目录失败: $relative" }
                } else {
                    deleteTreeIfExists(destination)
                    copyPath(source, destination)
                }
            }
    }

    private data class StageValidation(
        val draft: ScriptPluginAgentDraft,
        val validation: ScriptPluginAgentValidation
    )

    private data class ChangeSummary(
        val created: List<String>,
        val modified: List<String>,
        val deleted: List<String>,
        val diff: String,
        val diffTruncated: Boolean
    ) {
        val hasChanges: Boolean get() = created.isNotEmpty() || modified.isNotEmpty() || deleted.isNotEmpty()
    }

    private fun changeSummary(
        beforeStates: Map<String, String>,
        beforeRoot: File,
        afterRoot: File,
        pathPrefix: String = ".",
        includeDiff: Boolean = true
    ): ChangeSummary {
        val afterStates = snapshot(afterRoot).mapValues { it.value.serialized() }
        fun included(path: String): Boolean {
            return pathPrefix == "." || path == pathPrefix || path.startsWith(pathPrefix + "/")
        }
        val created = (afterStates.keys - beforeStates.keys).filter(::included).sorted()
        val deleted = (beforeStates.keys - afterStates.keys).filter(::included).sorted()
        val modified = (beforeStates.keys intersect afterStates.keys)
            .filter { included(it) && beforeStates[it] != afterStates[it] }
            .sorted()
        val builder = StringBuilder()
        var truncated = false
        for (path in if (includeDiff) created + modified + deleted else emptyList()) {
            val oldFile = File(beforeRoot, path)
            val newFile = File(afterRoot, path)
            val section = when {
                path in created -> fileDiff(path, null, newFile)
                path in deleted -> fileDiff(path, oldFile, null)
                else -> fileDiff(path, oldFile, newFile)
            }
            val separator = if (builder.isEmpty()) "" else "\n\n"
            if (builder.length + separator.length + section.length > MAX_DIFF_CHARS) {
                val remaining = (MAX_DIFF_CHARS - builder.length - separator.length).coerceAtLeast(0)
                builder.append(separator).append(section.take(remaining))
                truncated = true
                break
            }
            builder.append(separator).append(section)
        }
        if (truncated) builder.append("\n... diff 已截断，请按 path 分段调用 show_diff ...")
        return ChangeSummary(created, modified, deleted, builder.toString(), truncated)
    }

    private fun fileDiff(path: String, before: File?, after: File?): String {
        if (before?.isDirectory == true || after?.isDirectory == true) {
            return when {
                before == null -> "diff --git a/$path b/$path\nnew directory $path"
                after == null -> "diff --git a/$path b/$path\ndeleted directory $path"
                else -> "diff --git a/$path b/$path\npath type changed $path"
            }
        }
        val oldText = before?.let(::readDiffText)
        val newText = after?.let(::readDiffText)
        if ((before != null && oldText == null) || (after != null && newText == null)) {
            return "diff --git a/$path b/$path\nBinary files differ"
        }
        return ScriptPluginAgentUnifiedDiff.textDiff(path, oldText, newText)
    }

    private fun readDiffText(file: File): String? {
        if (!file.isFile || file.length() > MAX_TEXT_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return bytes.toString(Charsets.UTF_8).takeIf { isText(bytes) }
    }

    private fun draftFromStage(pluginId: String, stage: File): ScriptPluginAgentDraft {
        val main = File(stage, "main.java")
        val info = File(stage, "info.prop")
        require(main.isFile) { "插件缺少 main.java" }
        require(info.isFile) { "插件缺少 info.prop" }
        require(main.length() <= MAX_TEXT_BYTES && info.length() <= MAX_TEXT_BYTES) { "插件主文件超过大小限制" }
        val infoText = info.readText(Charsets.UTF_8)
        val properties = Properties().apply { runCatching { load(infoText.reader()) } }
        return ScriptPluginAgentValidator.normalize(
            ScriptPluginAgentDraft(
                pluginName = properties.getProperty("name").orEmpty(),
                pluginId = pluginId,
                infoProp = infoText,
                mainJava = main.readText(Charsets.UTF_8),
                summary = ""
            )
        )
    }

    private fun validateStage(pluginId: String, stage: File): StageValidation {
        val draft = draftFromStage(pluginId, stage)
        val issues = ScriptPluginAgentValidator.validate(
            draft,
            mainSource = File(stage, "main.java").readText(Charsets.UTF_8)
        ).issues.toMutableList()
        stage.walkTopDown().filter { file ->
            file.isFile && file.relativeTo(stage).invariantSeparatorsPath != "main.java" &&
                file.extension.lowercase() in setOf("java", "bsh", "js", "kt")
        }.forEach { file ->
            val relative = file.relativeTo(stage).invariantSeparatorsPath
            if (file.length() > MAX_TEXT_BYTES) {
                issues += ScriptPluginAgentIssue(
                    ScriptPluginAgentIssueLevel.ERROR,
                    "$relative 超过 ${MAX_TEXT_BYTES / 1024 / 1024} MB，不能执行静态检查"
                )
                return@forEach
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null || !isText(bytes)) {
                issues += ScriptPluginAgentIssue(
                    ScriptPluginAgentIssueLevel.ERROR,
                    "$relative 不是可静态检查的文本代码"
                )
                return@forEach
            }
            issues += ScriptPluginAgentValidator.validateAdditionalCode(
                relative,
                bytes.toString(Charsets.UTF_8)
            )
        }
        return StageValidation(
            draft,
            ScriptPluginAgentValidation(issues.distinctBy { it.level to it.message })
        )
    }

    private fun snapshot(root: File): Map<String, TreeEntry> {
        if (!root.isDirectory) return emptyMap()
        val result = LinkedHashMap<String, TreeEntry>()
        root.walkTopDown().filter { it != root }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            result[relative] = if (file.isDirectory) {
                TreeEntry(true, 0L, "")
            } else {
                TreeEntry(false, file.length(), fileDigest(file))
            }
        }
        return result
    }

    private fun treeFingerprint(root: File): String {
        return treeFingerprint(snapshot(root))
    }

    private fun treeFingerprint(entries: Map<String, TreeEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.toSortedMap().forEach { (path, entry) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(if (entry.directory) 1.toByte() else 0.toByte())
            digest.update(entry.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(entry.digest.toByteArray(Charsets.US_ASCII))
        }
        return digest.digest().toHex()
    }

    private fun fileDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun copyTree(source: File, destination: File) {
        require(source.isDirectory) { "源目录不存在: ${source.path}" }
        source.walkTopDown().forEach { file ->
            val canonical = runCatching { file.canonicalFile }.getOrDefault(file)
            // 符号链接按链接目标内容复制为普通文件（防逃逸由上层 canonical 检查保证）
            val relative = file.relativeTo(source)
            val target = File(destination, relative.path)
            if (canonical.isDirectory) {
                if (!target.isDirectory) check(target.mkdirs()) { "创建目录失败: ${target.name}" }
            } else {
                target.parentFile?.mkdirs()
                FileInputStream(canonical).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            }
        }
        enforceLimits(destination)
    }

    private fun copyDirectorySnapshot(source: File, destination: File, errorMessage: String) {
        val sourceFingerprint = treeFingerprint(source)
        try {
            deleteBackupArtifacts(destination)
            copyTree(source, destination)
            check(treeFingerprint(source) == sourceFingerprint) { "源插件目录在备份时发生变化" }
            check(treeFingerprint(destination) == sourceFingerprint) { "备份目录内容不完整" }
            writeTransactionMarker(transactionReadyMarker(destination), sourceFingerprint)
        } catch (error: Throwable) {
            runCatching { deleteBackupArtifacts(destination) }
            throw IllegalStateException("$errorMessage：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun transactionReadyMarker(backup: File): File {
        return File(backup.parentFile, backup.name + TRANSACTION_READY_SUFFIX)
    }

    private fun transactionCommittedMarker(backup: File): File {
        return File(backup.parentFile, backup.name + TRANSACTION_COMMITTED_SUFFIX)
    }

    private fun writeTransactionMarker(marker: File, value: String) {
        FileOutputStream(marker).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun transactionMarkerValue(marker: File): String? {
        if (!marker.isFile || marker.length() !in 1L..128L) return null
        return runCatching { marker.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun hasValidReadyMarker(backup: File): Boolean {
        val expected = transactionMarkerValue(transactionReadyMarker(backup)) ?: return false
        if (!expected.matches(Regex("[0-9a-f]{64}"))) return false
        return runCatching { treeFingerprint(backup) == expected }.getOrDefault(false)
    }

    private fun isTransactionCommitted(backup: File): Boolean {
        return transactionMarkerValue(transactionCommittedMarker(backup)) == "complete"
    }

    private fun deleteBackupArtifacts(backup: File) {
        deleteTreeIfExists(backup)
        listOf(transactionReadyMarker(backup), transactionCommittedMarker(backup)).forEach { marker ->
            if (marker.exists()) check(marker.delete()) { "删除事务标记失败: ${marker.name}" }
        }
    }

    private fun replaceDirectory(source: File, target: File, errorMessage: String) {
        require(source.isDirectory) { "$errorMessage：源目录不存在" }
        if (!target.exists()) {
            val moved = runCatching { Os.rename(source.absolutePath, target.absolutePath) }.isSuccess
            if (moved) return
        }
        val sourceFingerprint = treeFingerprint(source)
        try {
            if (!target.exists()) {
                check(target.mkdirs()) { "$errorMessage：创建目标目录失败" }
            }
            require(target.isDirectory) { "$errorMessage：目标不是目录" }
            synchronizeDirectorySnapshot(source, target)
            check(treeFingerprint(source) == sourceFingerprint) { "源目录在复制时发生变化" }
            check(treeFingerprint(target) == sourceFingerprint) { "目标目录内容不完整" }
        } catch (error: Throwable) {
            throw IllegalStateException("$errorMessage：${error.message ?: error.javaClass.simpleName}", error)
        }
    }

    private fun synchronizeDirectorySnapshot(source: File, target: File) {
        val sourceEntries = snapshot(source)
        val targetEntries = snapshot(target)
        targetEntries.keys
            .filter { path ->
                val sourceEntry = sourceEntries[path]
                sourceEntry == null || sourceEntry.directory != targetEntries.getValue(path).directory
            }
            .sortedByDescending { it.count { char -> char == '/' } }
            .forEach { path -> deleteTreeIfExists(File(target, path)) }

        sourceEntries.entries
            .filter { it.value.directory }
            .sortedBy { it.key.count { char -> char == '/' } }
            .forEach { (path, _) ->
                val destination = File(target, path)
                if (!destination.isDirectory) check(destination.mkdirs()) { "创建目录失败: $path" }
            }
        sourceEntries.entries
            .filterNot { it.value.directory }
            .forEach { (path, sourceEntry) ->
                if (targetEntries[path] == sourceEntry) return@forEach
                val destination = File(target, path)
                deleteTreeIfExists(destination)
                copyPath(File(source, path), destination)
            }
        enforceLimits(target)
    }

    private fun copyPath(source: File, destination: File) {
        require(source.absoluteFile == source.canonicalFile) { "不支持恢复符号链接: ${source.name}" }
        if (source.isDirectory) {
            copyTree(source, destination)
        } else {
            destination.parentFile?.let { parent ->
                if (!parent.isDirectory) check(parent.mkdirs()) { "创建恢复目录失败: ${parent.name}" }
            }
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
    }

    private fun enforceLimits(root: File) {
        var files = 0
        var bytes = 0L
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            files++
            bytes += file.length()
            require(files <= MAX_FILES) { "插件文件数量超过 $MAX_FILES" }
            require(bytes <= MAX_TOTAL_BYTES) { "插件总大小超过 ${MAX_TOTAL_BYTES / 1024 / 1024} MB" }
        }
    }

    private fun File.resolveSymlink(): File {
        return runCatching {
            if (absoluteFile != canonicalFile) canonicalFile else this
        }.getOrDefault(this)
    }

    private fun atomicWrite(target: File, content: String) {
        val temp = File(target.parentFile, ".${target.name}.agent.tmp")
        FileOutputStream(temp).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Os.rename(temp.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            temp.delete()
            throw IllegalStateException("写入文件失败: ${target.name}", error)
        }
    }

    private fun deleteTree(target: File) {
        val absolute = target.absoluteFile
        val canonical = target.canonicalFile
        if (absolute != canonical) {
            // 符号链接：只删除链接本身，不递归（防止误删链接目标内容）
            check(target.delete()) { "删除失败: ${target.name}" }
            return
        }
        if (target.isDirectory) target.listFiles()?.forEach(::deleteTree)
        check(target.delete()) { "删除失败: ${target.name}" }
    }

    private fun deleteTreeIfExists(target: File) {
        if (target.exists()) deleteTree(target)
    }

    private fun isText(bytes: ByteArray): Boolean {
        val count = minOf(bytes.size, 8_192)
        var controls = 0
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            if (value == 0) return false
            if (value < 9 || value in 14..31) controls++
        }
        return count == 0 || controls * 10 < count
    }

    private fun contextLines(lines: List<String>, from: Int, until: Int): JSONArray {
        val result = JSONArray()
        for (index in from.coerceAtLeast(0) until until.coerceAtMost(lines.size)) {
            result.put(JSONObject().apply {
                put("line", index + 1)
                put("text", lines[index].take(500))
            })
        }
        return result
    }

    private fun wildcardRegex(pattern: String): Regex {
        val source = buildString {
            append('^')
            val value = pattern.replace('\\', '/')
            if (!value.contains('/')) append("(?:.*/)?")
            var index = 0
            while (index < value.length) {
                val char = value[index]
                when (char) {
                    '*' -> {
                        if (value.getOrNull(index + 1) == '*') {
                            if (value.getOrNull(index + 2) == '/') {
                                append("(?:.*/)?")
                                index += 2
                            } else {
                                append(".*")
                                index++
                            }
                        } else {
                            append("[^/]*")
                        }
                    }
                    '?' -> append("[^/]")
                    '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> append('\\').append(char)
                    else -> append(char)
                }
                index++
            }
            append('$')
        }
        return Regex(source, RegexOption.IGNORE_CASE)
    }

    private fun normalize(name: String): String = name.trim().removePrefix("local.").removePrefix(PREFIX)

    private fun tool(
        tools: JSONArray,
        shortName: String,
        description: String,
        properties: Map<String, JSONObject>,
        required: List<String>
    ) {
        tools.put(JSONObject().apply {
            put("name", PREFIX + shortName)
            put("description", description)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject(properties))
                put("required", JSONArray(required))
                put("additionalProperties", false)
            })
        })
    }

    private fun stringSchema(description: String, minLength: Int = 1): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", description)
        put("minLength", minLength)
    }

    private fun booleanSchema(description: String, default: Boolean): JSONObject = JSONObject().apply {
        put("type", "boolean")
        put("description", description)
        put("default", default)
    }

    private fun integerSchema(description: String, default: Int, minimum: Int, maximum: Int? = null): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", description)
        put("default", default)
        put("minimum", minimum)
        maximum?.let { put("maximum", it) }
    }

    private fun workspaceRoot(context: Context): File {
        // 用 filesDir（应用私有持久目录，系统不会清理）替代 cacheDir，
        // 否则缓存被系统清理会导致 Agent 暂存区丢失、会话恢复失败、Agent 反复重试死循环
        return File(context.filesDir, "Hchat_agent_plugin_workspaces").apply {
            if (!isDirectory) check(mkdirs()) { "创建 Agent 工作区目录失败" }
        }
    }

    private fun changeStage(context: Context, change: ScriptPluginAgentWorkspaceChange): File {
        val root = workspaceRoot(context).canonicalFile
        val stage = File(change.stagingPath).canonicalFile
        require(stage.parentFile == root && stage.isDirectory) { "插件暂存工作区无效或已失效" }
        return stage
    }

    private fun checkpointStage(context: Context, stagingPath: String): File {
        val root = workspaceRoot(context).canonicalFile
        val requested = File(stagingPath).absoluteFile
        val stage = requested.canonicalFile
        require(requested == stage && stage.parentFile == root && stage.isDirectory) {
            "插件暂存工作区无效或已失效"
        }
        return stage
    }

    private fun isSafeCheckpointPath(value: String): Boolean {
        if (value.isBlank() || value.length > 240 || value.contains('\u0000') || value.contains('\\')) return false
        if (value.startsWith('/') || value.endsWith('/')) return false
        return value.split('/').none { it.isBlank() || it == "." || it == ".." }
    }

    private fun cleanupOldWorkspaces(context: Context) {
        val cutoff = System.currentTimeMillis() - WORKSPACE_MAX_AGE_MS
        workspaceRoot(context).listFiles()?.filter { it.isDirectory && it.lastModified() < cutoff }
            ?.forEach { runCatching { deleteTree(it) } }
    }

    private fun recoverInterruptedTransactions(context: Context, root: File) {
        ScriptPluginTransactionCoordinator.withPluginLocks(context, emptyList()) {
            val pattern = Regex("^\\.(.+)\\.agent-(new|old|copy)-([A-Za-z0-9]+)$")
            val artifacts = root.listFiles()?.mapNotNull { file ->
                val match = pattern.matchEntire(file.name) ?: return@mapNotNull null
                val pluginId = match.groupValues[1]
                if (pluginId != ScriptPluginAgentValidator.safePluginId(pluginId)) return@mapNotNull null
                Triple(pluginId, match.groupValues[2], file)
            }.orEmpty()
            artifacts.groupBy { it.first }.forEach { (pluginId, entries) ->
                ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(pluginId)) pluginRecovery@ {
                    val requestedTarget = File(root, pluginId).absoluteFile
                    val target = runCatching { requestedTarget.canonicalFile }.getOrNull()
                        ?: return@pluginRecovery
                    if (target.parentFile != root || requestedTarget != target) return@pluginRecovery
                    val backups = entries.filter { it.second == "old" }.map { it.third }
                        .sortedByDescending { it.lastModified() }
                    val copies = entries.filter { it.second == "copy" }.map { it.third }
                        .sortedByDescending { it.lastModified() }
                    val allBackups = backups + copies
                    val corruptBackups = allBackups.filter { backup ->
                        !isTransactionCommitted(backup) &&
                            transactionReadyMarker(backup).isFile &&
                            !hasValidReadyMarker(backup)
                    }
                    if (corruptBackups.isNotEmpty()) {
                        HLog.e(
                            "[Hchat:ScriptAgent] 插件事务备份校验失败，已保留现场: " +
                                corruptBackups.joinToString { it.path }
                        )
                        return@pluginRecovery
                    }
                    val pendingBackups = allBackups.filter { backup ->
                        hasValidReadyMarker(backup) && !isTransactionCommitted(backup)
                    }.toMutableList()
                    if (!target.exists()) {
                        pendingBackups += backups.filter { backup ->
                            !transactionReadyMarker(backup).exists() && !isTransactionCommitted(backup)
                        }
                    }
                    val recoveryBackup = pendingBackups.distinct().maxByOrNull { backup ->
                        transactionReadyMarker(backup).lastModified().coerceAtLeast(backup.lastModified())
                    }
                    if (recoveryBackup != null) {
                        val recovered = runCatching {
                            replaceDirectory(recoveryBackup, target, "恢复插件事务备份失败")
                        }.onFailure { error ->
                            HLog.e("[Hchat:ScriptAgent] 恢复插件事务失败: ${recoveryBackup.path}", error)
                        }.isSuccess
                        if (!recovered) return@pluginRecovery
                    }
                    entries.filter { it.third != target }.forEach { (_, type, artifact) ->
                        runCatching {
                            if (type == "new") deleteTreeIfExists(artifact) else deleteBackupArtifacts(artifact)
                        }.onFailure { error ->
                            HLog.e("[Hchat:ScriptAgent] 清理插件事务残留失败: ${artifact.path}", error)
                        }
                    }
                }
            }
        }
    }
}
