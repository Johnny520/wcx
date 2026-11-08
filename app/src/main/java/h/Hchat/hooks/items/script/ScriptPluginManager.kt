package h.Hchat.hooks.items.script

import android.content.Context
import android.content.SharedPreferences
import android.system.Os
import h.Hchat.hooks.items.script.agent.ScriptPluginAgentWorkspaceTools
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ScriptPluginManager {
    private const val TAG = "[Hchat:ScriptManager]"
    private const val DISPLAY_STATE_VERSION = 1
    private const val EXPORT_FORMAT = "hchat-script-plugins"
    private const val EXPORT_VERSION = 1
    private const val EXPORT_MANIFEST = "hchat-plugin-manifest.json"
    private const val EXPORT_PLUGIN_ROOT = "plugins"
    private const val IMPORT_STAGE_PREFIX = ".hchat-plugin-import-stage-"
    private const val DELETE_TRANSACTION_PREFIX = ".hchat-plugin-delete-"
    private const val IMPORT_TRANSACTION_PREFIX = ".hchat-plugin-import-apply-"
    private const val TRANSACTION_JOURNAL = "operation.json"
    private const val TRANSACTION_COMMITTED = "committed"
    private const val DELETE_BACKUP_ROOT = "deleted"
    private const val TRANSACTION_TYPE_DELETE = "delete"
    private const val TRANSACTION_TYPE_IMPORT = "import"
    private const val STALE_OPERATION_AGE_MS = 24L * 60L * 60L * 1_000L
    private const val MAX_PLUGIN_ID_LENGTH = 128
    private const val MAX_PLUGIN_NAME_LENGTH = 100
    private const val MAX_ZIP_ENTRY_NAME_LENGTH = 1024
    private const val MAX_INFO_FILE_BYTES = 1024L * 1024L
    private const val MAX_TRANSACTION_JOURNAL_BYTES = 8L * 1024L * 1024L

    const val MAX_IMPORT_ENTRY_COUNT = 4096
    const val MAX_IMPORT_FILE_BYTES = 128L * 1024L * 1024L
    const val MAX_IMPORT_TOTAL_BYTES = 512L * 1024L * 1024L
    private val activeImportSessions = ConcurrentHashMap.newKeySet<String>()

    data class DisplayState(
        val orderedPluginIds: List<String>,
        val pinnedPluginIds: Set<String>
    )

    data class ManagedPlugin(
        val plugin: ScriptPluginRuntime.ScriptPlugin,
        val pinned: Boolean
    )

    data class DisplayCleanupResult(
        val removedOrderIds: Set<String>,
        val removedPinnedIds: Set<String>
    )

    data class DeleteResult(
        val deletedPluginIds: List<String>,
        val cleanupPendingPath: String? = null
    )

    data class ExportResult(
        val exportedPluginIds: List<String>,
        val fileCount: Int,
        val totalBytes: Long
    )

    data class ImportPlugin(
        val pluginId: String,
        val name: String,
        val conflict: Boolean,
        val pinned: Boolean?,
        val order: Int?
    )

    data class ImportInspection(
        val sessionId: String,
        val plugins: List<ImportPlugin>,
        val conflicts: List<String>,
        val manifestVersion: Int?
    )

    enum class ImportConflictAction {
        SKIP,
        OVERWRITE
    }

    data class ImportApplyResult(
        val importedPluginIds: List<String>,
        val overwrittenPluginIds: List<String>,
        val skippedPluginIds: List<String>,
        val cleanupPendingPath: String? = null
    )

    private data class StoredDisplayState(
        val order: List<String> = emptyList(),
        val pinned: Set<String> = emptySet()
    )

    private data class ManifestPlugin(
        val id: String,
        val path: String,
        val pinned: Boolean,
        val order: Int
    )

    private data class ImportManifest(
        val version: Int,
        val pluginsByPath: Map<String, ManifestPlugin>
    )

    private data class ImportCandidate(
        val id: String,
        val name: String,
        val sourceDir: File,
        val relativePath: String,
        val manifest: ManifestPlugin?
    )

    private data class ImportSelection(
        val candidate: ImportCandidate,
        val target: File,
        val existed: Boolean,
        val enableState: PluginEnableState
    )

    private data class PluginEnableState(
        val configured: Boolean,
        val enabled: Boolean,
        val directoryExisted: Boolean
    )

    private data class ExportStats(
        var entryCount: Int = 0,
        var fileCount: Int = 0,
        var totalBytes: Long = 0L
    )

    private data class OperationJournal(
        val type: String,
        val pluginIds: List<String>,
        val existingPluginIds: Set<String>,
        val configuredPluginIds: Set<String>,
        val enabledPluginIds: Set<String>,
        val displayStateRaw: String?,
        val installedFingerprints: Map<String, String> = emptyMap()
    )

    @JvmStatic
    fun getDisplayState(context: Context): DisplayState {
        val state = readDisplayState(context)
        return DisplayState(state.order.toList(), state.pinned.toSet())
    }

    @JvmStatic
    @Synchronized
    fun recoverInterruptedOperations(
        context: Context,
        cleanupOrphanImportStages: Boolean = false
    ): Result<Unit> = runCatching {
        ScriptPluginTransactionCoordinator.withPluginLocks(context, emptyList()) {
            val root = securePluginRoot(context)
            val parent = root.parentFile?.canonicalFile ?: error("脚本插件根目录没有父目录")
            val entries = parent.listFiles().orEmpty()
            entries.filter { file ->
                file.isDirectory &&
                    (file.name.startsWith(DELETE_TRANSACTION_PREFIX) ||
                        file.name.startsWith(IMPORT_TRANSACTION_PREFIX))
            }.sortedBy { it.lastModified() }.forEach { transaction ->
                require(!isSymbolicLink(transaction) && transaction.canonicalFile.parentFile == parent) {
                    "插件事务目录路径不安全: ${transaction.path}"
                }
                val journal = readOperationJournal(transaction)
                if (journal == null) {
                    if (transaction.lastModified() < System.currentTimeMillis() - STALE_OPERATION_AGE_MS) {
                        runCatching { deleteSecureTree(transaction) }
                            .onFailure { error ->
                                HLog.e("$TAG 清理无效事务目录失败: ${transaction.path}", error)
                            }
                    }
                    return@forEach
                }
                if (isOperationCommitted(transaction)) {
                    deleteSecureTree(transaction)
                } else {
                    when (journal.type) {
                        TRANSACTION_TYPE_DELETE -> recoverDeleteTransaction(context, root, transaction, journal)
                        TRANSACTION_TYPE_IMPORT -> recoverImportTransaction(context, root, transaction, journal)
                        else -> error("未知插件事务类型: ${journal.type}")
                    }
                }
            }
            val staleCutoff = System.currentTimeMillis() - STALE_OPERATION_AGE_MS
            entries.filter { file ->
                if (!file.isDirectory || !file.name.startsWith(IMPORT_STAGE_PREFIX)) return@filter false
                val sessionId = file.name.removePrefix(IMPORT_STAGE_PREFIX)
                sessionId !in activeImportSessions &&
                    (cleanupOrphanImportStages || file.lastModified() < staleCutoff)
            }.forEach { stage ->
                runCatching { deleteSecureTree(stage) }
                    .onFailure { error -> HLog.e("$TAG 清理过期导入暂存目录失败: ${stage.path}", error) }
            }
        }
    }

    @JvmStatic
    fun listForDisplay(context: Context): List<ManagedPlugin> {
        val state = readDisplayState(context)
        return sortForDisplay(ScriptPluginRuntime.listPlugins(context), state).map { plugin ->
            ManagedPlugin(plugin, plugin.id in state.pinned)
        }
    }

    @JvmStatic
    fun sortForDisplay(
        context: Context,
        plugins: List<ScriptPluginRuntime.ScriptPlugin>
    ): List<ScriptPluginRuntime.ScriptPlugin> {
        return sortForDisplay(plugins, readDisplayState(context))
    }

    private fun sortForDisplay(
        plugins: List<ScriptPluginRuntime.ScriptPlugin>,
        state: StoredDisplayState
    ): List<ScriptPluginRuntime.ScriptPlugin> {
        val positions = state.order.withIndex().associate { it.value to it.index }
        val alphabetical = compareBy<ScriptPluginRuntime.ScriptPlugin> {
            it.id.lowercase(Locale.ROOT)
        }.thenBy { it.id }
        val known = plugins.filter { it.id in positions }
            .sortedBy { positions.getValue(it.id) }
        val appended = plugins.filterNot { it.id in positions }.sortedWith(alphabetical)
        val appendedPositions = appended.withIndex().associate { it.value.id to it.index }
        return (known + appended).sortedWith(
            compareBy<ScriptPluginRuntime.ScriptPlugin> { it.id !in state.pinned }
                .thenBy { plugin ->
                    positions[plugin.id] ?: (state.order.size + appendedPositions.getValue(plugin.id))
                }
        )
    }

    @JvmStatic
    @Synchronized
    fun setPinned(context: Context, pluginIds: Collection<String>, pinned: Boolean): Result<Unit> {
        return runCatching {
            ScriptPluginTransactionCoordinator.withPluginLocks(context, pluginIds) {
                val existingIds = ScriptPluginRuntime.listPlugins(context).map { it.id }.toSet()
                val requestedIds = pluginIds.toCollection(LinkedHashSet())
                require(requestedIds.isNotEmpty()) { "未选择插件" }
                require(requestedIds.all { it in existingIds }) {
                    "包含不存在的插件: ${(requestedIds - existingIds).joinToString()}"
                }
                val state = readDisplayState(context)
                val updatedPinned = state.pinned.toMutableSet().apply {
                    if (pinned) addAll(requestedIds) else removeAll(requestedIds)
                }
                writeDisplayState(context, state.copy(pinned = updatedPinned))
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun saveDisplayOrder(context: Context, orderedPluginIds: List<String>): Result<Unit> {
        return runCatching {
            ScriptPluginTransactionCoordinator.withPluginLocks(context, orderedPluginIds) {
                require(orderedPluginIds.size == orderedPluginIds.toSet().size) { "插件顺序包含重复 ID" }
                val plugins = ScriptPluginRuntime.listPlugins(context)
                val existingIds = plugins.map { it.id }.toSet()
                require(orderedPluginIds.all { it in existingIds }) {
                    "插件顺序包含不存在的 ID: ${(orderedPluginIds.toSet() - existingIds).joinToString()}"
                }
                val state = readDisplayState(context)
                val omitted = sortForDisplay(context, plugins)
                    .map { it.id }
                    .filterNot { it in orderedPluginIds }
                writeDisplayState(context, state.copy(order = orderedPluginIds + omitted))
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun cleanupStaleDisplayIds(context: Context): Result<DisplayCleanupResult> {
        return runCatching {
            ScriptPluginTransactionCoordinator.withPluginLocks(context, emptyList()) {
                recoverInterruptedOperations(context).getOrThrow()
                val existingIds = ScriptPluginRuntime.listPlugins(context).map { it.id }.toSet()
                val state = readDisplayState(context)
                val staleOrder = state.order.filterNot { it in existingIds }.toSet()
                val stalePinned = state.pinned.filterNot { it in existingIds }.toSet()
                if (staleOrder.isNotEmpty() || stalePinned.isNotEmpty()) {
                    writeDisplayState(
                        context,
                        state.copy(
                            order = state.order.filter { it in existingIds },
                            pinned = state.pinned.filter { it in existingIds }.toSet()
                        )
                    )
                }
                DisplayCleanupResult(staleOrder, stalePinned)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun renamePlugin(
        context: Context,
        pluginId: String,
        newName: String
    ): Result<ScriptPluginRuntime.ScriptPlugin> = runCatching {
        recoverInterruptedOperations(context).getOrThrow()
        ScriptPluginTransactionCoordinator.withPluginLocks(context, listOf(pluginId)) {
            val normalizedName = newName.trim()
            require(normalizedName.isNotEmpty()) { "插件名称不能为空" }
            require(normalizedName.length <= MAX_PLUGIN_NAME_LENGTH) {
                "插件名称不能超过 $MAX_PLUGIN_NAME_LENGTH 个字符"
            }
            require(normalizedName.none { it.code < 0x20 || it.code == 0x7f }) {
                "插件名称不能包含控制字符"
            }
            val root = securePluginRoot(context)
            val target = requirePluginDirectory(root, pluginId)
            ScriptPluginAgentWorkspaceTools.ensureLegacyWriteAccess(root, target)
            val info = File(target, "info.prop")
            require(!isSymbolicLink(info)) { "info.prop 不能是符号链接" }
            if (info.exists()) {
                require(info.isFile) { "info.prop 不是普通文件" }
                require(info.length() <= MAX_INFO_FILE_BYTES) { "info.prop 文件过大" }
            }
            val properties = Properties()
            if (info.isFile) {
                info.reader(Charsets.UTF_8).use { reader -> properties.load(reader) }
            }
            properties.setProperty("name", normalizedName)
            writePropertiesAtomically(info, properties)
            ScriptPluginRuntime.refreshPluginObserver(context, pluginId)
            ScriptPluginRuntime.listPlugins(context).firstOrNull { it.id == pluginId }
                ?: error("重命名后未找到插件: $pluginId")
        }
    }

    @JvmStatic
    fun deletePlugin(context: Context, pluginId: String): Result<DeleteResult> {
        return deletePlugins(context, listOf(pluginId))
    }

    @JvmStatic
    @Synchronized
    fun deletePlugins(context: Context, pluginIds: Collection<String>): Result<DeleteResult> {
        return runCatching {
            val ids = pluginIds.toList()
            require(ids.isNotEmpty()) { "未选择插件" }
            require(ids.size == ids.toSet().size) { "待删除插件包含重复 ID" }
            recoverInterruptedOperations(context).getOrThrow()
            ScriptPluginTransactionCoordinator.withPluginLocks(context, ids) {
                val root = securePluginRoot(context)
                val targets = ids.associateWith { id -> requirePluginDirectory(root, id) }
                targets.values.forEach { target ->
                    ScriptPluginAgentWorkspaceTools.ensureLegacyWriteAccess(root, target)
                }
                val enabledBefore = capturePluginEnableStates(context, ids, ids.toSet())
                val displayStateBefore = readDisplayStateRaw(context)
                val transaction = createSiblingDirectory(root, DELETE_TRANSACTION_PREFIX)
                try {
                    writeOperationJournal(
                        transaction,
                        OperationJournal(
                            TRANSACTION_TYPE_DELETE,
                            ids,
                            ids.toSet(),
                            enabledBefore.filterValues { it.configured }.keys,
                            enabledBefore.filterValues { it.enabled }.keys,
                            displayStateBefore
                        )
                    )
                } catch (error: Throwable) {
                    runCatching { deleteSecureTree(transaction) }
                    throw error
                }
                val deletedRoot = try {
                    File(transaction, DELETE_BACKUP_ROOT).apply {
                        check(mkdir()) { "创建待删除插件备份目录失败" }
                    }
                } catch (error: Throwable) {
                    runCatching { deleteSecureTree(transaction) }
                    throw error
                }
                val moved = LinkedHashMap<String, File>()
                try {
                    disablePlugins(context, ids, enabledBefore)
                    for ((id, target) in targets) {
                        val movedTarget = File(deletedRoot, id)
                        moveAtomically(target, movedTarget, "移出待删除插件失败: $id")
                        moved[id] = movedTarget
                    }
                    removePluginSettings(context, ids)
                    markOperationCommitted(transaction)
                } catch (error: Throwable) {
                    restoreMovedPlugins(
                        context,
                        root,
                        transaction,
                        moved,
                        enabledBefore,
                        displayStateBefore
                    )
                    throw error
                }
                ids.forEach { id ->
                    runCatching { ScriptPluginRuntime.refreshPluginObserver(context, id) }
                        .onFailure { error -> HLog.e("$TAG 刷新已删除插件观察器失败: $id", error) }
                }

                var cleanupPending: String? = null
                runCatching { deleteSecureTree(transaction) }
                    .onFailure { error ->
                        cleanupPending = transaction.absolutePath
                        HLog.e("$TAG 删除插件事务目录失败: ${transaction.absolutePath}", error)
                    }
                DeleteResult(ids, cleanupPending)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun exportPlugins(
        context: Context,
        pluginIds: Collection<String>,
        output: OutputStream
    ): Result<ExportResult> = runCatching {
        val requestedIds = pluginIds.toList()
        require(requestedIds.isNotEmpty()) { "未选择插件" }
        require(requestedIds.size == requestedIds.toSet().size) { "导出插件包含重复 ID" }
        recoverInterruptedOperations(context).getOrThrow()
        ScriptPluginTransactionCoordinator.withPluginLocks(context, requestedIds) {
            val root = securePluginRoot(context)
            val pluginsById = ScriptPluginRuntime.listPlugins(context).associateBy { it.id }
            require(requestedIds.all { it in pluginsById }) {
                "包含不存在的插件: ${(requestedIds.toSet() - pluginsById.keys).joinToString()}"
            }
            val orderedPlugins = sortForDisplay(
                context,
                requestedIds.map { id -> requireNotNull(pluginsById[id]) }
            )
            val state = readDisplayState(context)
            val manifest = JSONObject().apply {
                put("format", EXPORT_FORMAT)
                put("version", EXPORT_VERSION)
                put("plugins", JSONArray().apply {
                    orderedPlugins.forEachIndexed { index, plugin ->
                        put(JSONObject().apply {
                            put("id", plugin.id)
                            put("path", "$EXPORT_PLUGIN_ROOT/${plugin.id}")
                            put("pinned", plugin.id in state.pinned)
                            put("order", index)
                        })
                    }
                })
            }
            val stats = ExportStats()
            ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(output))).use { zip ->
                val manifestBytes = manifest.toString(2).toByteArray(StandardCharsets.UTF_8)
                require(manifestBytes.size.toLong() <= MAX_INFO_FILE_BYTES) {
                    "导出插件过多，ZIP manifest 超过限制"
                }
                recordExportEntry(stats, manifestBytes.size.toLong(), countAsFile = false)
                writeZipBytes(zip, EXPORT_MANIFEST, manifestBytes)
                for (plugin in orderedPlugins) {
                    val target = requirePluginDirectory(root, plugin.id)
                    writePluginTree(zip, target, "$EXPORT_PLUGIN_ROOT/${plugin.id}", stats)
                }
            }
            ExportResult(orderedPlugins.map { it.id }, stats.fileCount, stats.totalBytes)
        }
    }

    @JvmStatic
    @Synchronized
    fun inspectImport(context: Context, input: InputStream): Result<ImportInspection> {
        return runCatching {
            recoverInterruptedOperations(context).getOrThrow()
            val root = securePluginRoot(context)
            val sessionId = UUID.randomUUID().toString()
            activeImportSessions += sessionId
            val stage = importStage(root, sessionId)
            try {
                check(stage.mkdir()) { "创建插件导入暂存目录失败" }
                val archive = File(stage, "archive")
                check(archive.mkdir()) { "创建插件解压目录失败" }
                extractZip(input, archive)
                val (manifest, candidates) = inspectImportCandidates(archive)
                val pluginViews = candidates.map { candidate ->
                    ImportPlugin(
                        pluginId = candidate.id,
                        name = candidate.name,
                        conflict = resolvePluginTarget(root, candidate.id).exists(),
                        pinned = candidate.manifest?.pinned,
                        order = candidate.manifest?.order
                    )
                }
                ImportInspection(
                    sessionId = sessionId,
                    plugins = pluginViews,
                    conflicts = pluginViews.filter { it.conflict }.map { it.pluginId },
                    manifestVersion = manifest?.version
                )
            } catch (error: Throwable) {
                activeImportSessions -= sessionId
                runCatching { deleteSecureTree(stage) }
                throw error
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun applyImport(
        context: Context,
        sessionId: String,
        conflictActions: Map<String, ImportConflictAction>
    ): Result<ImportApplyResult> = runCatching {
        recoverInterruptedOperations(context).getOrThrow()
        val root = securePluginRoot(context)
        val stage = requireImportStage(root, sessionId)
        val archive = File(stage, "archive")
        requireSecureDirectory(archive, stage)
        val (_, candidates) = inspectImportCandidates(archive)
        val candidateIds = candidates.map { it.id }
        require(conflictActions.keys.all { it in candidateIds }) {
            "冲突选择包含导入包中不存在的插件"
        }
        ScriptPluginTransactionCoordinator.withPluginLocks(context, candidateIds) {
            val skipped = ArrayList<String>()
            val selected = ArrayList<ImportSelection>()
            for (candidate in candidates) {
                val target = resolvePluginTarget(root, candidate.id)
                val existed = target.exists()
                if (existed) requirePluginDirectory(root, candidate.id)
                val action = conflictActions[candidate.id]
                if (action == ImportConflictAction.SKIP) {
                    skipped += candidate.id
                    continue
                }
                if (existed && action != ImportConflictAction.OVERWRITE) {
                    error("插件 ${candidate.id} 已存在，需要选择跳过或覆盖")
                }
                selected += ImportSelection(
                    candidate = candidate,
                    target = target,
                    existed = existed,
                    enableState = capturePluginEnableState(context, candidate.id, existed)
                )
            }
            if (selected.isEmpty()) {
                deleteSecureTree(stage)
                activeImportSessions -= sessionId
                return@withPluginLocks ImportApplyResult(emptyList(), emptyList(), skipped)
            }

            val selectedIds = selected.map { it.candidate.id }
            val enabledBefore = selected.associate { it.candidate.id to it.enableState }
            val transaction = createSiblingDirectory(root, IMPORT_TRANSACTION_PREFIX)
            var journal = OperationJournal(
                TRANSACTION_TYPE_IMPORT,
                selectedIds,
                selected.filter { it.existed }.mapTo(LinkedHashSet()) { it.candidate.id },
                enabledBefore.filterValues { it.configured }.keys,
                enabledBefore.filterValues { it.enabled }.keys,
                readDisplayStateRaw(context)
            )
            try {
                writeOperationJournal(transaction, journal)
            } catch (error: Throwable) {
                runCatching { deleteSecureTree(transaction) }
                throw error
            }
            val (preparedRoot, backupRoot) = try {
                val prepared = File(transaction, "prepared").apply {
                    check(mkdir()) { "创建导入准备目录失败" }
                }
                val backup = File(transaction, "backup").apply {
                    check(mkdir()) { "创建导入备份目录失败" }
                }
                prepared to backup
            } catch (error: Throwable) {
                runCatching { deleteSecureTree(transaction) }
                throw error
            }
            val installedIds = ArrayList<String>()
            val movedBackups = LinkedHashMap<String, File>()
            var mutationStarted = false
            try {
                for (selection in selected) {
                    val prepared = File(preparedRoot, selection.candidate.id)
                    copySecureTree(selection.candidate.sourceDir, prepared)
                    require(File(prepared, "main.java").isFile) {
                        "插件缺少 main.java: ${selection.candidate.id}"
                    }
                }
                journal = journal.copy(
                    installedFingerprints = selected.associate { selection ->
                        val prepared = File(preparedRoot, selection.candidate.id)
                        selection.candidate.id to pluginTreeFingerprint(prepared)
                    }
                )
                writeOperationJournal(transaction, journal)
                disablePlugins(context, selectedIds, enabledBefore)
                mutationStarted = true
                for (selection in selected) {
                    if (selection.existed) {
                        val backup = File(backupRoot, selection.candidate.id)
                        moveAtomically(selection.target, backup, "备份旧插件失败: ${selection.candidate.id}")
                        movedBackups[selection.candidate.id] = backup
                    }
                    val prepared = File(preparedRoot, selection.candidate.id)
                    moveAtomically(prepared, selection.target, "安装导入插件失败: ${selection.candidate.id}")
                    installedIds += selection.candidate.id
                }
                commitImportedSettings(context, selected)
                markOperationCommitted(transaction)
            } catch (error: Throwable) {
                if (mutationStarted || installedIds.isNotEmpty() || movedBackups.isNotEmpty()) {
                    rollbackImport(
                        context,
                        transaction,
                        selected,
                        installedIds,
                        movedBackups,
                        enabledBefore,
                        journal.displayStateRaw,
                        journal.installedFingerprints
                    )
                } else {
                    runCatching { deleteSecureTree(transaction) }
                }
                throw error
            }
            selected.forEach { selection ->
                runCatching { ScriptPluginRuntime.refreshPluginObserver(context, selection.candidate.id) }
                    .onFailure { error ->
                        HLog.e("$TAG 刷新导入插件观察器失败: ${selection.candidate.id}", error)
                    }
            }

            var cleanupPending: String? = null
            runCatching { deleteSecureTree(transaction) }
                .onFailure { error ->
                    cleanupPending = transaction.absolutePath
                    HLog.e("$TAG 清理插件导入事务失败: ${transaction.absolutePath}", error)
                }
            runCatching { deleteSecureTree(stage) }
                .onFailure { error ->
                    HLog.e("$TAG 清理插件导入暂存区失败: ${stage.absolutePath}", error)
                    if (cleanupPending == null) cleanupPending = stage.absolutePath
                }
            activeImportSessions -= sessionId
            ImportApplyResult(
                importedPluginIds = selectedIds,
                overwrittenPluginIds = selected.filter { it.existed }.map { it.candidate.id },
                skippedPluginIds = skipped,
                cleanupPendingPath = cleanupPending
            )
        }
    }

    @JvmStatic
    @Synchronized
    fun discardImport(context: Context, sessionId: String): Result<Unit> = runCatching {
        try {
            ScriptPluginTransactionCoordinator.withPluginLocks(context, emptyList()) {
                val root = securePluginRoot(context)
                val stage = requireImportStage(root, sessionId)
                deleteSecureTree(stage)
            }
        } finally {
            activeImportSessions -= sessionId
        }
    }

    private fun readDisplayState(context: Context): StoredDisplayState {
        val raw = readDisplayStateRaw(context)
            ?.takeIf { it.isNotBlank() }
            ?: return StoredDisplayState()
        return runCatching {
            val root = JSONObject(raw)
            require(root.optInt("version", 0) == DISPLAY_STATE_VERSION) {
                "不支持的插件显示配置版本"
            }
            val order = jsonStringList(root.optJSONArray("order"))
            val pinned = jsonStringList(root.optJSONArray("pinned")).toSet()
            StoredDisplayState(order.distinct(), pinned)
        }.getOrElse { error ->
            HLog.e("$TAG 读取插件显示配置失败，已使用默认顺序", error)
            StoredDisplayState()
        }
    }

    private fun readDisplayStateRaw(context: Context): String? {
        return preferences(context).getString(ScriptPluginSettings.KEY_PLUGIN_DISPLAY_STATE, null)
    }

    private fun restoreDisplayStateRaw(context: Context, raw: String?) {
        val editor = preferences(context).edit()
        if (raw == null) editor.remove(ScriptPluginSettings.KEY_PLUGIN_DISPLAY_STATE)
        else editor.putString(ScriptPluginSettings.KEY_PLUGIN_DISPLAY_STATE, raw)
        check(editor.commit()) { "恢复插件显示配置失败" }
    }

    private fun writeDisplayState(context: Context, state: StoredDisplayState) {
        val editor = preferences(context).edit()
        putDisplayState(editor, state)
        check(editor.commit()) { "保存插件显示配置失败" }
    }

    private fun putDisplayState(editor: SharedPreferences.Editor, state: StoredDisplayState) {
        val json = JSONObject().apply {
            put("version", DISPLAY_STATE_VERSION)
            put("order", JSONArray().apply { state.order.distinct().forEach { id -> put(id) } })
            put("pinned", JSONArray().apply { state.pinned.sorted().forEach { id -> put(id) } })
        }
        editor.putString(ScriptPluginSettings.KEY_PLUGIN_DISPLAY_STATE, json.toString())
    }

    private fun removePluginSettings(context: Context, pluginIds: Collection<String>) {
        val removeIds = pluginIds.toSet()
        val state = readDisplayState(context)
        val editor = preferences(context).edit()
        removeIds.forEach { editor.remove(ScriptPluginSettings.pluginEnableKey(it)) }
        putDisplayState(
            editor,
            state.copy(
                order = state.order.filterNot { it in removeIds },
                pinned = state.pinned.filterNot { it in removeIds }.toSet()
            )
        )
        check(editor.commit()) { "清理插件配置失败" }
    }

    private fun commitImportedSettings(context: Context, selections: List<ImportSelection>) {
        val importedIds = selections.map { it.candidate.id }.toSet()
        val oldState = readDisplayState(context)
        val order = oldState.order.toMutableList()
        val pinned = oldState.pinned.toMutableSet()
        val controlledIds = selections.filter { it.candidate.manifest != null }.map { it.candidate.id }.toSet()
        order.removeAll(controlledIds)
        pinned.removeAll(controlledIds)
        selections.filter { it.candidate.manifest != null }
            .sortedWith(compareBy<ImportSelection> { it.candidate.manifest?.order ?: Int.MAX_VALUE }
                .thenBy { it.candidate.id.lowercase(Locale.ROOT) })
            .forEach { selection ->
                order += selection.candidate.id
                if (selection.candidate.manifest?.pinned == true) pinned += selection.candidate.id
            }
        selections.filter { it.candidate.manifest == null && it.candidate.id !in order }
            .map { it.candidate.id }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .forEach { id -> order.add(id) }

        val editor = preferences(context).edit()
        importedIds.forEach { id ->
            editor.putBoolean(ScriptPluginSettings.pluginEnableKey(id), false)
        }
        putDisplayState(editor, StoredDisplayState(order.distinct(), pinned))
        check(editor.commit()) { "保存导入插件配置失败" }
    }

    private fun disablePlugins(
        context: Context,
        pluginIds: Collection<String>,
        enabledBefore: Map<String, PluginEnableState>
    ) {
        val disabled = ArrayList<String>()
        try {
            for (id in pluginIds) {
                ScriptPluginRuntime.setPluginEnabled(context, id, false).getOrThrow()
                disabled += id
            }
        } catch (error: Throwable) {
            restorePluginEnableStates(context, enabledBefore.filterKeys { it in disabled })
            throw error
        }
    }

    private fun restoreMovedPlugins(
        context: Context,
        root: File,
        transaction: File,
        moved: Map<String, File>,
        enabledBefore: Map<String, PluginEnableState>,
        displayStateRaw: String?
    ) {
        var firstFailure: Throwable? = null
        moved.entries.toList().asReversed().forEach { (id, source) ->
            val target = File(root, id)
            runCatching { moveAtomically(source, target, "恢复插件目录失败: $id") }
                .onFailure { if (firstFailure == null) firstFailure = it }
            runCatching { ScriptPluginRuntime.refreshPluginObserver(context, id) }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        if (firstFailure == null) {
            runCatching { restorePluginEnableStates(context, enabledBefore) }
                .onFailure { if (firstFailure == null) firstFailure = it }
            runCatching { restoreDisplayStateRaw(context, displayStateRaw) }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        if (firstFailure != null) {
            throw IllegalStateException(
                "删除失败且部分插件未能自动恢复，事务目录保留在 ${transaction.absolutePath}",
                firstFailure
            )
        }
        runCatching { deleteSecureTree(transaction) }
    }

    private fun rollbackImport(
        context: Context,
        transaction: File,
        selections: List<ImportSelection>,
        installedIds: List<String>,
        movedBackups: Map<String, File>,
        enabledBefore: Map<String, PluginEnableState>,
        displayStateRaw: String?,
        installedFingerprints: Map<String, String>
    ) {
        var firstFailure: Throwable? = null
        installedIds.asReversed().forEach { id ->
            val target = selections.first { it.candidate.id == id }.target
            val failed = File(transaction, "failed-$id")
            runCatching {
                if (target.exists()) {
                    val expected = installedFingerprints[id] ?: error("缺少导入插件指纹: $id")
                    check(pluginTreeFingerprint(target) == expected) {
                        "导入插件已被外部修改，已保留当前目录: $id"
                    }
                    moveAtomically(target, failed, "移除失败导入插件失败: $id")
                }
            }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        movedBackups.entries.toList().asReversed().forEach { (id, backup) ->
            val target = selections.first { it.candidate.id == id }.target
            runCatching { moveAtomically(backup, target, "恢复旧插件失败: $id") }
                .onFailure { if (firstFailure == null) firstFailure = it }
            runCatching { ScriptPluginRuntime.refreshPluginObserver(context, id) }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        if (firstFailure == null) {
            runCatching { restorePluginEnableStates(context, enabledBefore) }
                .onFailure { if (firstFailure == null) firstFailure = it }
            runCatching { restoreDisplayStateRaw(context, displayStateRaw) }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        if (firstFailure == null) {
            runCatching { deleteSecureTree(transaction) }
        } else {
            throw IllegalStateException(
                "导入失败且部分旧插件未能自动恢复，事务目录保留在 ${transaction.absolutePath}",
                firstFailure
            )
        }
    }

    private fun recoverDeleteTransaction(
        context: Context,
        root: File,
        transaction: File,
        journal: OperationJournal
    ) {
        val deletedRoot = File(transaction, DELETE_BACKUP_ROOT)
        for (id in journal.pluginIds) {
            val moved = resolveTransactionChild(deletedRoot, id)
            if (!moved.exists()) continue
            requireSecureDirectory(moved, transaction)
            val target = resolvePluginTarget(root, id)
            check(!target.exists()) {
                "恢复删除事务时目标插件已存在，已保留事务目录: ${transaction.path}"
            }
            moveAtomically(moved, target, "恢复被中断的删除事务失败: $id")
        }
        restoreJournalPluginSettings(context, journal)
        journal.pluginIds.forEach { id ->
            runCatching { ScriptPluginRuntime.refreshPluginObserver(context, id) }
                .onFailure { error -> HLog.e("$TAG 刷新恢复插件观察器失败: $id", error) }
        }
        deleteSecureTree(transaction)
    }

    private fun recoverImportTransaction(
        context: Context,
        root: File,
        transaction: File,
        journal: OperationJournal
    ) {
        val backupRoot = File(transaction, "backup")
        for (id in journal.pluginIds) {
            val target = resolvePluginTarget(root, id)
            val backup = resolveTransactionChild(backupRoot, id)
            if (backup.exists() && target.exists()) {
                val expected = journal.installedFingerprints[id]
                    ?: error("导入事务缺少目标指纹，已保留现场: $id")
                check(pluginTreeFingerprint(requirePluginDirectory(root, id)) == expected) {
                    "导入后的插件已被外部修改，已保留当前目录和事务备份: $id"
                }
            } else if (!backup.exists() && id !in journal.existingPluginIds && target.exists()) {
                val expected = journal.installedFingerprints[id]
                    ?: error("导入事务缺少目标指纹，已保留现场: $id")
                check(pluginTreeFingerprint(requirePluginDirectory(root, id)) == expected) {
                    "导入后的新插件已被外部修改，已保留当前目录: $id"
                }
            } else if (!backup.exists() && id in journal.existingPluginIds && !target.exists()) {
                error("导入事务的原插件目录和备份均不存在，已保留现场: $id")
            }
        }
        for (id in journal.pluginIds.asReversed()) {
            val target = resolvePluginTarget(root, id)
            val backup = resolveTransactionChild(backupRoot, id)
            if (backup.exists()) {
                requireSecureDirectory(backup, transaction)
                if (target.exists()) deleteSecureTree(requirePluginDirectory(root, id))
                moveAtomically(backup, target, "恢复被覆盖的插件失败: $id")
            } else if (id !in journal.existingPluginIds && target.exists()) {
                deleteSecureTree(requirePluginDirectory(root, id))
            }
        }
        restoreJournalPluginSettings(context, journal)
        journal.pluginIds.forEach { id ->
            runCatching { ScriptPluginRuntime.refreshPluginObserver(context, id) }
                .onFailure { error -> HLog.e("$TAG 刷新恢复插件观察器失败: $id", error) }
        }
        deleteSecureTree(transaction)
    }

    private fun restoreJournalPluginSettings(context: Context, journal: OperationJournal) {
        val editor = preferences(context).edit()
        journal.pluginIds.forEach { id ->
            val key = ScriptPluginSettings.pluginEnableKey(id)
            if (id in journal.configuredPluginIds) {
                editor.putBoolean(key, id in journal.enabledPluginIds)
            } else {
                editor.remove(key)
            }
        }
        if (journal.displayStateRaw == null) {
            editor.remove(ScriptPluginSettings.KEY_PLUGIN_DISPLAY_STATE)
        } else {
            editor.putString(ScriptPluginSettings.KEY_PLUGIN_DISPLAY_STATE, journal.displayStateRaw)
        }
        check(editor.commit()) { "恢复插件启用配置失败" }
    }

    private fun writeOperationJournal(transaction: File, journal: OperationJournal) {
        val json = JSONObject().apply {
            put("type", journal.type)
            put("pluginIds", JSONArray().apply { journal.pluginIds.forEach { id -> put(id) } })
            put("existingPluginIds", JSONArray().apply { journal.existingPluginIds.sorted().forEach { id -> put(id) } })
            put("configuredPluginIds", JSONArray().apply { journal.configuredPluginIds.sorted().forEach { id -> put(id) } })
            put("enabledPluginIds", JSONArray().apply { journal.enabledPluginIds.sorted().forEach { id -> put(id) } })
            put("installedFingerprints", JSONObject().apply {
                journal.installedFingerprints.toSortedMap().forEach { (id, fingerprint) ->
                    put(id, fingerprint)
                }
            })
            if (journal.displayStateRaw == null) put("displayState", JSONObject.NULL)
            else put("displayState", journal.displayStateRaw)
        }
        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size.toLong() <= MAX_TRANSACTION_JOURNAL_BYTES) { "插件事务日志过大" }
        writeSyncedFileAtomically(File(transaction, TRANSACTION_JOURNAL), bytes)
    }

    private fun readOperationJournal(transaction: File): OperationJournal? {
        val file = File(transaction, TRANSACTION_JOURNAL)
        if (!file.exists()) return null
        require(file.isFile && !isSymbolicLink(file) && file.length() <= MAX_TRANSACTION_JOURNAL_BYTES) {
            "插件事务日志无效: ${transaction.path}"
        }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        val type = json.optString("type")
        require(type == TRANSACTION_TYPE_DELETE || type == TRANSACTION_TYPE_IMPORT) {
            "插件事务类型无效"
        }
        val pluginIds = jsonStringList(json.optJSONArray("pluginIds")).map(::validatePluginId).distinct()
        require(pluginIds.isNotEmpty()) { "插件事务缺少插件 ID" }
        val existing = jsonStringList(json.optJSONArray("existingPluginIds")).map(::validatePluginId).toSet()
        val configured = jsonStringList(json.optJSONArray("configuredPluginIds")).map(::validatePluginId).toSet()
        val enabled = jsonStringList(json.optJSONArray("enabledPluginIds")).map(::validatePluginId).toSet()
        require(existing.all { it in pluginIds } && configured.all { it in pluginIds } && enabled.all { it in pluginIds }) {
            "插件事务日志包含未知插件 ID"
        }
        val installedFingerprints = LinkedHashMap<String, String>()
        json.optJSONObject("installedFingerprints")?.let { fingerprints ->
            val keys = fingerprints.keys()
            while (keys.hasNext()) {
                val id = validatePluginId(keys.next())
                val fingerprint = fingerprints.optString(id)
                require(id in pluginIds && fingerprint.matches(Regex("[0-9a-f]{64}"))) {
                    "插件事务包含无效目标指纹"
                }
                installedFingerprints[id] = fingerprint
            }
        }
        val displayStateRaw = if (json.isNull("displayState")) null else json.optString("displayState")
        require(displayStateRaw == null || displayStateRaw.length.toLong() <= MAX_INFO_FILE_BYTES) {
            "插件事务显示配置过大"
        }
        return OperationJournal(
            type,
            pluginIds,
            existing,
            configured,
            enabled,
            displayStateRaw,
            installedFingerprints
        )
    }

    private fun markOperationCommitted(transaction: File) {
        writeSyncedFileAtomically(
            File(transaction, TRANSACTION_COMMITTED),
            "complete".toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun isOperationCommitted(transaction: File): Boolean {
        val marker = File(transaction, TRANSACTION_COMMITTED)
        if (!marker.isFile || isSymbolicLink(marker) || marker.length() !in 1L..32L) return false
        return runCatching { marker.readText(Charsets.UTF_8) == "complete" }.getOrDefault(false)
    }

    private fun writeSyncedFile(target: File, bytes: ByteArray) {
        require(target.canonicalFile.parentFile == target.parentFile.canonicalFile) { "事务标记路径越界" }
        FileOutputStream(target).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun writeSyncedFileAtomically(target: File, bytes: ByteArray) {
        val parent = target.parentFile?.canonicalFile ?: error("事务日志没有父目录")
        require(target.canonicalFile.parentFile == parent) { "事务日志路径越界" }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.tmp")
        try {
            writeSyncedFile(temporary, bytes)
            Os.rename(temporary.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            temporary.delete()
            throw IllegalStateException("写入插件事务日志失败", error)
        }
    }

    private fun resolveTransactionChild(parent: File, name: String): File {
        validatePluginId(name)
        val child = File(parent, name).canonicalFile
        require(child.parentFile == parent.canonicalFile) { "插件事务路径越界: $name" }
        require(!isSymbolicLink(child)) { "插件事务目录不能是符号链接: $name" }
        return child
    }

    private fun capturePluginEnableStates(
        context: Context,
        pluginIds: Collection<String>,
        existingIds: Set<String>
    ): Map<String, PluginEnableState> {
        val prefs = preferences(context)
        return pluginIds.associateWith { id ->
            val key = ScriptPluginSettings.pluginEnableKey(id)
            PluginEnableState(
                configured = prefs.contains(key),
                enabled = prefs.getBoolean(key, ScriptPluginSettings.DEFAULT_PLUGIN_ENABLE),
                directoryExisted = id in existingIds
            )
        }
    }

    private fun capturePluginEnableState(
        context: Context,
        pluginId: String,
        directoryExisted: Boolean
    ): PluginEnableState {
        return capturePluginEnableStates(
            context,
            listOf(pluginId),
            if (directoryExisted) setOf(pluginId) else emptySet()
        ).getValue(pluginId)
    }

    private fun restorePluginEnableStates(
        context: Context,
        states: Map<String, PluginEnableState>
    ) {
        if (states.isEmpty()) return
        val editor = preferences(context).edit()
        states.forEach { (id, state) ->
            val key = ScriptPluginSettings.pluginEnableKey(id)
            if (state.configured) editor.putBoolean(key, state.enabled) else editor.remove(key)
        }
        check(editor.commit()) { "恢复插件启用配置失败" }
        var firstFailure: Throwable? = null
        states.filterValues { it.enabled && it.directoryExisted }.keys.forEach { id ->
            runCatching { ScriptPluginRuntime.setPluginEnabled(context, id, true).getOrThrow() }
                .onFailure { error ->
                    HLog.e("$TAG 恢复插件启用状态失败: $id", error)
                    if (firstFailure == null) firstFailure = error
                }
        }
        if (firstFailure != null) throw IllegalStateException("部分插件未能恢复运行状态", firstFailure)
    }

    private fun extractZip(input: InputStream, destinationRoot: File) {
        val seen = HashSet<String>()
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(NonClosingInputStream(input))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_IMPORT_ENTRY_COUNT) {
                    "ZIP 条目数量超过 $MAX_IMPORT_ENTRY_COUNT"
                }
                val relative = normalizeZipEntry(entry.name)
                require(seen.add(relative)) { "ZIP 包含重复路径: $relative" }
                val target = resolveArchivePath(destinationRoot, relative)
                if (entry.isDirectory) {
                    if (!target.isDirectory) check(target.mkdirs()) { "创建导入目录失败: $relative" }
                } else {
                    require(entry.size < 0L || entry.size <= MAX_IMPORT_FILE_BYTES) {
                        "ZIP 单文件超过限制: $relative"
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.isDirectory) check(parent.mkdirs()) { "创建导入父目录失败: $relative" }
                    }
                    require(!target.exists()) { "ZIP 路径冲突: $relative" }
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var fileBytes = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            totalBytes += count
                            require(fileBytes <= MAX_IMPORT_FILE_BYTES) {
                                "ZIP 单文件超过限制: $relative"
                            }
                            require(totalBytes <= MAX_IMPORT_TOTAL_BYTES) {
                                "ZIP 解压总大小超过限制"
                            }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                zip.closeEntry()
            }
        }
        ensureSecureTree(destinationRoot)
    }

    private fun inspectImportCandidates(
        archiveRoot: File
    ): Pair<ImportManifest?, List<ImportCandidate>> {
        ensureSecureTree(archiveRoot)
        val manifest = readImportManifest(File(archiveRoot, EXPORT_MANIFEST), archiveRoot)
        val sourceDirs = if (manifest != null) {
            manifest.pluginsByPath.keys.map { relative ->
                resolveArchivePath(archiveRoot, relative).also { pluginDir ->
                    require(File(pluginDir, "main.java").isFile) {
                        "ZIP manifest 插件缺少 main.java: $relative"
                    }
                }
            }
        } else {
            val mainFiles = archiveRoot.walkTopDown()
                .filter { it.isFile && it.name == "main.java" }
                .toList()
            require(mainFiles.isNotEmpty()) { "ZIP 中未找到包含 main.java 的插件目录" }
            mainFiles.mapNotNull { it.parentFile }.distinctBy { it.canonicalPath }
        }
        for (index in sourceDirs.indices) {
            for (otherIndex in index + 1 until sourceDirs.size) {
                val first = sourceDirs[index].canonicalPath + File.separator
                val second = sourceDirs[otherIndex].canonicalPath + File.separator
                require(!first.startsWith(second) && !second.startsWith(first)) {
                    "ZIP 中的插件目录相互嵌套，无法安全导入"
                }
            }
        }

        val candidates = sourceDirs.map { sourceDir ->
            requireSecureDirectory(sourceDir, archiveRoot)
            val relative = sourceDir.relativeTo(archiveRoot).invariantSeparatorsPath.ifBlank { "." }
            val manifestPlugin = manifest?.pluginsByPath?.get(relative)
            val id = manifestPlugin?.id ?: if (sourceDir != archiveRoot) {
                sourceDir.name
            } else {
                derivePluginId(readPluginName(sourceDir).ifBlank { "imported-plugin" })
            }
            validatePluginId(id)
            ImportCandidate(
                id = id,
                name = readPluginName(sourceDir).ifBlank { id },
                sourceDir = sourceDir,
                relativePath = relative,
                manifest = manifestPlugin
            )
        }
        require(candidates.map { it.id }.size == candidates.map { it.id }.toSet().size) {
            "ZIP 中存在重复插件 ID"
        }
        if (manifest != null) {
            val candidatePaths = candidates.map { it.relativePath }.toSet()
            require(manifest.pluginsByPath.keys == candidatePaths) {
                "ZIP manifest 与实际插件目录不一致"
            }
        }
        return manifest to candidates.sortedWith(
            compareBy<ImportCandidate> { it.manifest?.order ?: Int.MAX_VALUE }
                .thenBy { it.id.lowercase(Locale.ROOT) }
        )
    }

    private fun readImportManifest(file: File, archiveRoot: File): ImportManifest? {
        if (!file.exists()) return null
        require(file.isFile && !isSymbolicLink(file)) { "ZIP manifest 不是普通文件" }
        require(file.canonicalFile.parentFile == archiveRoot.canonicalFile) { "ZIP manifest 路径越界" }
        require(file.length() <= MAX_INFO_FILE_BYTES) { "ZIP manifest 文件过大" }
        val json = JSONObject(file.readText(Charsets.UTF_8))
        require(json.optString("format") == EXPORT_FORMAT) { "不支持的插件 ZIP 格式" }
        val version = json.optInt("version", 0)
        require(version == EXPORT_VERSION) { "不支持的插件 ZIP 版本: $version" }
        val array = json.optJSONArray("plugins") ?: error("ZIP manifest 缺少插件列表")
        val plugins = LinkedHashMap<String, ManifestPlugin>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: error("ZIP manifest 插件条目无效")
            val id = validatePluginId(item.optString("id"))
            val path = normalizeManifestPath(item.optString("path"), archiveRoot)
            require(!plugins.containsKey(path)) { "ZIP manifest 包含重复插件路径" }
            plugins[path] = ManifestPlugin(
                id = id,
                path = path,
                pinned = item.optBoolean("pinned", false),
                order = item.optInt("order", index).coerceAtLeast(0)
            )
        }
        require(plugins.isNotEmpty()) { "ZIP manifest 插件列表为空" }
        require(plugins.values.map { it.id }.size == plugins.values.map { it.id }.toSet().size) {
            "ZIP manifest 包含重复插件 ID"
        }
        return ImportManifest(version, plugins)
    }

    private fun writePluginTree(
        zip: ZipOutputStream,
        pluginDir: File,
        entryRoot: String,
        stats: ExportStats
    ) {
        ensureSecureTree(pluginDir)
        val children = pluginDir.listFiles() ?: error("无法读取插件目录: ${pluginDir.name}")
        recordExportEntry(stats, 0L, countAsFile = false)
        writeZipDirectory(zip, "$entryRoot/")
        children.sortedBy { it.name }.forEach { child ->
            writeFileTree(zip, pluginDir, child, entryRoot, stats)
        }
    }

    private fun writeFileTree(
        zip: ZipOutputStream,
        pluginRoot: File,
        source: File,
        entryRoot: String,
        stats: ExportStats
    ) {
        require(!isSymbolicLink(source)) { "插件包含符号链接: ${source.name}" }
        val canonical = source.canonicalFile
        require(canonical.path.startsWith(pluginRoot.canonicalPath + File.separator)) {
            "插件文件路径越界: ${source.name}"
        }
        val relative = canonical.relativeTo(pluginRoot).invariantSeparatorsPath
        val entryName = "$entryRoot/$relative"
        if (canonical.isDirectory) {
            recordExportEntry(stats, 0L, countAsFile = false)
            writeZipDirectory(zip, "$entryName/")
            val children = canonical.listFiles() ?: error("无法读取插件子目录: $relative")
            children.sortedBy { it.name }.forEach { child ->
                writeFileTree(zip, pluginRoot, child, entryRoot, stats)
            }
        } else {
            require(canonical.isFile) { "插件包含非普通文件: $relative" }
            val expectedBytes = canonical.length()
            recordExportEntry(stats, expectedBytes, countAsFile = true)
            zip.putNextEntry(ZipEntry(entryName))
            FileInputStream(canonical).use { input ->
                val copied = input.copyTo(zip)
                check(copied == expectedBytes) { "导出时插件文件发生变化: $relative" }
            }
            zip.closeEntry()
        }
    }

    private fun recordExportEntry(stats: ExportStats, bytes: Long, countAsFile: Boolean) {
        require(bytes in 0L..MAX_IMPORT_FILE_BYTES) { "导出文件超过单文件限制" }
        stats.entryCount++
        require(stats.entryCount <= MAX_IMPORT_ENTRY_COUNT) {
            "导出 ZIP 条目数量超过 $MAX_IMPORT_ENTRY_COUNT"
        }
        stats.totalBytes += bytes
        require(stats.totalBytes <= MAX_IMPORT_TOTAL_BYTES) { "导出插件总大小超过限制" }
        if (countAsFile) stats.fileCount++
    }

    private fun pluginTreeFingerprint(pluginDir: File): String {
        requireSecureDirectory(pluginDir, pluginDir.parentFile ?: error("插件目录没有父目录"))
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = pluginDir.walkTopDown()
            .drop(1)
            .sortedBy { it.relativeTo(pluginDir).invariantSeparatorsPath }
            .toList()
        entries.forEach { entry ->
            val relative = entry.relativeTo(pluginDir).invariantSeparatorsPath
            val size = if (entry.isFile) entry.length() else -1L
            val header = "${if (entry.isDirectory) 'd' else 'f'}:$relative:$size\u0000"
            digest.update(header.toByteArray(StandardCharsets.UTF_8))
            if (entry.isFile) {
                FileInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun copySecureTree(source: File, destination: File) {
        ensureSecureTree(source)
        require(!destination.exists()) { "导入准备目录已存在: ${destination.name}" }
        check(destination.mkdirs()) { "创建导入插件目录失败: ${destination.name}" }
        val children = source.listFiles() ?: error("无法读取导入插件目录: ${source.name}")
        children.forEach { child -> copySecureEntry(source, child, destination) }
        ensureSecureTree(destination)
    }

    private fun copySecureEntry(sourceRoot: File, source: File, destinationRoot: File) {
        require(!isSymbolicLink(source)) { "导入插件包含符号链接: ${source.name}" }
        val relative = source.canonicalFile.relativeTo(sourceRoot.canonicalFile)
        val destination = File(destinationRoot, relative.path)
        require(destination.canonicalPath.startsWith(destinationRoot.canonicalPath + File.separator)) {
            "复制导入插件时路径越界"
        }
        if (source.isDirectory) {
            if (!destination.isDirectory) check(destination.mkdirs()) { "创建导入子目录失败" }
            val children = source.listFiles() ?: error("无法读取导入子目录: ${source.name}")
            children.forEach { child -> copySecureEntry(sourceRoot, child, destinationRoot) }
        } else {
            require(source.isFile) { "导入插件包含非普通文件: ${source.name}" }
            destination.parentFile?.let { if (!it.isDirectory) check(it.mkdirs()) { "创建导入父目录失败" } }
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
        }
    }

    private fun securePluginRoot(context: Context): File {
        val requestedRoot = ScriptPluginRuntime.ensureDirs(context).absoluteFile
        require(!isSymbolicLink(requestedRoot)) { "脚本插件根目录不能是符号链接" }
        val root = requestedRoot.canonicalFile
        require(requestedRoot == root) { "脚本插件根目录路径不安全" }
        require(root.isDirectory) { "脚本插件根目录不可用" }
        return root
    }

    private fun requirePluginDirectory(root: File, pluginId: String): File {
        val target = resolvePluginTarget(root, pluginId)
        require(target.isDirectory) { "未找到插件目录: $pluginId" }
        ensureSecureTree(target)
        require(File(target, "main.java").isFile) { "插件缺少 main.java: $pluginId" }
        return target
    }

    private fun resolvePluginTarget(root: File, pluginId: String): File {
        val id = validatePluginId(pluginId)
        val requested = File(root, id).absoluteFile
        val canonical = requested.canonicalFile
        require(requested == canonical && canonical.parentFile == root) {
            "插件目录路径越界或使用了符号链接: $id"
        }
        require(!isSymbolicLink(requested)) { "插件目录不能是符号链接: $id" }
        return canonical
    }

    private fun validatePluginId(pluginId: String): String {
        require(pluginId.isNotBlank() && pluginId != "." && pluginId != "..") { "插件 ID 不能为空" }
        require(pluginId.length <= MAX_PLUGIN_ID_LENGTH) {
            "插件 ID 不能超过 $MAX_PLUGIN_ID_LENGTH 个字符"
        }
        require(pluginId.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
            "插件 ID 包含不允许的路径字符"
        }
        return pluginId
    }

    private fun derivePluginId(name: String): String {
        val value = buildString {
            name.trim().forEach { character ->
                append(
                    when {
                        character == '/' || character == '\\' -> '_'
                        character.code < 0x20 || character.code == 0x7f -> '_'
                        else -> character
                    }
                )
            }
        }.trim().trim('.')
        return value.ifBlank { "imported-plugin" }.take(MAX_PLUGIN_ID_LENGTH)
    }

    private fun readPluginName(pluginDir: File): String {
        val info = File(pluginDir, "info.prop")
        if (!info.isFile || isSymbolicLink(info) || info.length() > MAX_INFO_FILE_BYTES) return ""
        return runCatching {
            Properties().apply {
                info.reader(Charsets.UTF_8).use { reader -> load(reader) }
            }
                .getProperty("name")
                .orEmpty()
                .trim()
                .take(MAX_PLUGIN_NAME_LENGTH)
        }.getOrDefault("")
    }

    private fun requireSecureDirectory(directory: File, parent: File) {
        require(directory.isDirectory && !isSymbolicLink(directory)) { "导入目录无效" }
        val parentPath = parent.canonicalPath
        val path = directory.canonicalPath
        require(path == parentPath || path.startsWith(parentPath + File.separator)) { "导入目录路径越界" }
        ensureSecureTree(directory)
    }

    private fun ensureSecureTree(root: File) {
        require(root.exists()) { "文件不存在: ${root.name}" }
        require(!isSymbolicLink(root)) { "不支持符号链接: ${root.name}" }
        val rootPath = root.canonicalPath
        require(root.absoluteFile.canonicalPath == rootPath) { "文件路径无效: ${root.name}" }
        if (!root.isDirectory) {
            require(root.isFile) { "不支持非普通文件: ${root.name}" }
            return
        }
        val children = root.listFiles() ?: error("无法读取目录: ${root.name}")
        for (child in children) {
            require(!isSymbolicLink(child)) { "目录包含符号链接: ${child.name}" }
            val childPath = child.canonicalPath
            require(childPath.startsWith(rootPath + File.separator)) { "目录内容路径越界: ${child.name}" }
            ensureSecureTree(child)
        }
    }

    private fun isSymbolicLink(file: File): Boolean {
        return Files.isSymbolicLink(file.toPath())
    }

    private fun createSiblingDirectory(root: File, prefix: String): File {
        val parent = root.parentFile?.canonicalFile ?: error("脚本插件根目录没有父目录")
        require(parent.isDirectory && !isSymbolicLink(parent)) { "脚本插件父目录不可用" }
        val directory = File(parent, prefix + UUID.randomUUID())
        require(directory.canonicalFile.parentFile == parent) { "事务目录路径越界" }
        check(directory.mkdir()) { "创建插件事务目录失败" }
        return directory.canonicalFile
    }

    private fun importStage(root: File, sessionId: String): File {
        val parent = root.parentFile?.canonicalFile ?: error("脚本插件根目录没有父目录")
        return File(parent, IMPORT_STAGE_PREFIX + sessionId).canonicalFile.also { stage ->
            require(stage.parentFile == parent) { "导入暂存目录路径越界" }
        }
    }

    private fun requireImportStage(root: File, sessionId: String): File {
        require(runCatching { UUID.fromString(sessionId) }.isSuccess) { "导入 sessionId 无效" }
        val stage = importStage(root, sessionId)
        requireSecureDirectory(stage, root.parentFile.canonicalFile)
        return stage
    }

    private fun normalizeZipEntry(rawName: String): String {
        require(rawName.isNotBlank() && rawName.length <= MAX_ZIP_ENTRY_NAME_LENGTH) {
            "ZIP 条目名称无效"
        }
        require(!rawName.startsWith('/') && '\\' !in rawName && '\u0000' !in rawName) {
            "ZIP 条目路径不安全: $rawName"
        }
        val parts = rawName.trimEnd('/').split('/')
        require(parts.isNotEmpty() && parts.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "ZIP 条目路径不安全: $rawName"
        }
        return parts.joinToString("/")
    }

    private fun normalizeManifestPath(path: String, archiveRoot: File): String {
        val normalized = normalizeZipEntry(path)
        val target = resolveArchivePath(archiveRoot, normalized)
        require(target.isDirectory) { "ZIP manifest 插件目录不存在: $normalized" }
        return normalized
    }

    private fun resolveArchivePath(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        require(target.path.startsWith(root.canonicalPath + File.separator)) {
            "ZIP 条目路径越界: $relative"
        }
        return target
    }

    private fun moveAtomically(source: File, target: File, message: String) {
        require(source.exists()) { "$message，源目录不存在" }
        require(!target.exists()) { "$message，目标已存在" }
        target.parentFile?.let { parent ->
            if (!parent.isDirectory) check(parent.mkdirs()) { "$message，创建目标父目录失败" }
        }
        try {
            Os.rename(source.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            throw IllegalStateException(message, error)
        }
    }

    private fun writePropertiesAtomically(target: File, properties: Properties) {
        val temp = File(target.parentFile, ".${target.name}.manage-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { output ->
                val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
                properties.store(writer, null)
                writer.flush()
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
        } catch (error: Throwable) {
            temp.delete()
            throw IllegalStateException("更新 info.prop 失败", error)
        }
    }

    private fun deleteSecureTree(target: File) {
        require(!isSymbolicLink(target)) { "拒绝删除符号链接: ${target.name}" }
        if (!target.exists()) return
        if (target.isDirectory) {
            val children = target.listFiles() ?: error("无法读取待删除目录: ${target.name}")
            children.forEach(::deleteSecureTree)
        } else {
            require(target.isFile) { "拒绝删除非普通文件: ${target.name}" }
        }
        check(target.delete()) { "删除失败: ${target.name}" }
    }

    private fun writeZipBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeZipDirectory(zip: ZipOutputStream, name: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.closeEntry()
    }

    private fun jsonStringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { id -> add(id) }
            }
        }
    }

    private fun preferences(context: Context) = HchatStorage.preferences(
        context.applicationContext ?: context,
        ScriptPluginSettings.PREFS_NAME
    )

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() {
            flush()
        }
    }
}
