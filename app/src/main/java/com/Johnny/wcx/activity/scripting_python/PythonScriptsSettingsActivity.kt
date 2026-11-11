package com.Johnny.wcx.activity.scripting_python

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Archive
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Content_copy
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Folder
import com.composables.icons.materialsymbols.outlined.Refresh
import com.composables.icons.materialsymbols.outlined.Restart_alt
import com.composables.icons.materialsymbols.outlined.Save
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Wrap_text
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.extensions.ExtensionPackDialogs
import com.Johnny.wcx.extensions.PythonRuntimePack
import com.Johnny.wcx.features.items.scripting_python.plugin.PythonCrashGuard
import com.Johnny.wcx.features.items.scripting_python.plugin.PythonPluginManager
import com.Johnny.wcx.features.items.scripting_python.plugin.PythonPluginManifest
import com.Johnny.wcx.features.items.scripting_python.plugin.PythonPluginRecord
import com.Johnny.wcx.features.items.scripting_python.plugin.PythonPluginStatus
import com.Johnny.wcx.features.items.scripting_python.runtime.PythonRuntimeLoader
import com.Johnny.wcx.ui.content.m3.BaseWidget
import com.Johnny.wcx.ui.content.m3.ExpressiveBackButton
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.content.m3.SettingsActionRow
import com.Johnny.wcx.ui.content.m3.SettingsConfirmDialog
import com.Johnny.wcx.ui.content.m3.SettingsListActionButton
import com.Johnny.wcx.ui.content.m3.SettingsScaffold
import com.Johnny.wcx.ui.content.m3.SettingsEditorScaffold
import com.Johnny.wcx.ui.content.m3.SwitchWidget
import com.Johnny.wcx.ui.content.m3.TextFieldDialogWidget
import com.Johnny.wcx.ui.content.m3.rememberCreationBackGuard







import com.Johnny.wcx.ui.utils.theme.ColorosThemeProvider
import com.Johnny.wcx.ui.utils.theme.ModuleTheme
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.Johnny.wcx.ui.content.MiuixStackNavigator
import kotlin.io.path.div

@Keep
class PythonScriptsSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ModuleTheme {
                ColorosThemeProvider {
                    PythonSettingsRoot(this@PythonScriptsSettingsActivity, ::finish)
                }
            }
        }
    }
}

private sealed interface PythonSettingsRoute {
    data object Home : PythonSettingsRoute
    data class PluginInfo(val pluginId: String?) : PythonSettingsRoute
    data class Detail(val pluginId: String) : PythonSettingsRoute
    data class Edit(val pluginId: String) : PythonSettingsRoute
    data class Diagnostics(val pluginId: String) : PythonSettingsRoute
}

@Composable
private fun PythonSettingsRoot(activity: ComponentActivity, onFinish: () -> Unit) {
    val backStack = remember { mutableStateListOf<PythonSettingsRoute>(PythonSettingsRoute.Home) }
    MiuixStackNavigator(
        stack = backStack,
        onExitRoot = onFinish,
    ) { screen, push, pop ->
        when (screen) {
            PythonSettingsRoute.Home -> PythonHomeScreen(
                activity,
                onFinish,
                { push(PythonSettingsRoute.PluginInfo(null)) },
            ) { push(PythonSettingsRoute.Detail(it)) }
            is PythonSettingsRoute.PluginInfo -> PythonPluginInfoScreen(screen.pluginId, pop)
            is PythonSettingsRoute.Detail -> PythonDetailScreen(
                screen.pluginId,
                pop,
                { push(PythonSettingsRoute.PluginInfo(screen.pluginId)) },
                { push(PythonSettingsRoute.Edit(screen.pluginId)) },
                { push(PythonSettingsRoute.Diagnostics(screen.pluginId)) },
                pop,
            )
            is PythonSettingsRoute.Edit -> PythonEditorScreen(screen.pluginId, pop)
            is PythonSettingsRoute.Diagnostics -> PythonDiagnosticsScreen(screen.pluginId, pop)
        }
    }
}
@Composable
private fun PythonHomeScreen(
    activity: ComponentActivity,
    onBack: () -> Unit,
    onNewPlugin: () -> Unit,
    openPlugin: (String) -> Unit,
) {
    val records by PythonPluginManager.records.collectAsState()
    val runtime by PythonRuntimeLoader.status.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedContext = LocalContext.current
    var pendingTrustPlugin by remember { mutableStateOf<String?>(null) }
    var showImportWarning by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val importSuccessText = "插件导入成功"
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PythonPluginManager.discover() } }

    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            importing = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val temp = java.io.File(context.cacheDir, "plugin-import-${System.currentTimeMillis()}.zip")
                    try {
                        context.contentResolver.openInputStream(uri)!!.use { input ->
                            temp.outputStream().use(input::copyTo)
                        }
                        PythonPluginManager.importPlugin(temp)
                    } finally {
                        temp.delete()
                    }
                }
            }
            importing = false
            result.fold(
                onSuccess = { showToast(context, importSuccessText) },
                onFailure = {
                    showToast(context, "导入失败：" + (it.message ?: ""))
                },
            )
        }
    }

    SettingsScaffold("Python 插件", onBack) {
        item {
            SegmentedColumn(title = "运行时") {
                item {
                    BaseWidget(
                        title = "Python 运行时",
                        description = runtime.state.label + (runtime.version?.let { " · $it" } ?: ""),
                        onClick = { ExtensionPackDialogs.openExtensions(activity, PythonRuntimePack, false) },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = "插件") {
                item {
                    BaseWidget(
                        title = "新建插件",
                        icon = MaterialSymbols.Outlined.Add,
                        enabled = !importing,
                        onClick = onNewPlugin,
                    )
                }
                item {
                    BaseWidget(
                        title = "从 ZIP 导入",
                        icon = MaterialSymbols.Outlined.Archive,
                        enabled = !importing,
                        onClick = { showImportWarning = true },
                    )
                }
                if (records.isEmpty()) {
                    item { BaseWidget(title = "未发现 Python 插件") }
                } else {
                    records.values.forEach { record ->
                        item(key = record.id) {
                            SwitchWidget(
                                title = record.manifest?.name ?: record.id,
                                description = "${record.id} · ${record.status.name}" +
                                    (record.lastError?.let { "\n$it" } ?: ""),
                                checked = record.desiredEnabled,
                                enabled = record.manifest != null &&
                                    record.status != PythonPluginStatus.LOADING &&
                                    record.status != PythonPluginStatus.UNLOADING,
                                onClick = { openPlugin(record.id) },
                                trailingDivider = true,
                                onCheckedChange = { enabled ->
                                    if (enabled && !PythonPluginManager.isTrustWarningAccepted()) {
                                        pendingTrustPlugin = record.id
                                    } else {
                                        coroutineScope.launch(Dispatchers.IO) {
                                            PythonPluginManager.setDesiredEnabled(record.id, enabled)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            SegmentedColumn(title = "安全") {
                item {
                    BaseWidget(
                        title = "仅运行可信代码",
                        description = "插件在微信进程内运行且没有沙箱，可以读取或修改进程内存与文件、访问网络、安装 Hook，并可导致微信崩溃或死锁。禁用插件无法撤销任意副作用。",
                        isError = true,
                    )
                }
            }
        }
    }
    SettingsConfirmDialog(
        show = pendingTrustPlugin != null,
        title = "仅运行可信代码",
        message = "插件在微信进程内运行且没有沙箱，可以读取或修改进程内存与文件、访问网络、安装 Hook，并可导致微信崩溃或死锁。禁用插件无法撤销任意副作用。",
        confirmLabel = "确定",
        dismissLabel = "取消",
        destructive = true,
        onConfirm = {
            val enabledPluginId = pendingTrustPlugin ?: return@SettingsConfirmDialog
            pendingTrustPlugin = null
            PythonPluginManager.acceptTrustWarning()
            coroutineScope.launch(Dispatchers.IO) {
                PythonPluginManager.setDesiredEnabled(enabledPluginId, true)
            }
        },
        onDismiss = { pendingTrustPlugin = null },
    )
    // 导入的 zip 携带任意代码，无论之前是否接受过警告，每次导入都要重新确认。
    SettingsConfirmDialog(
        show = showImportWarning,
        title = "仅运行可信代码",
        message = "插件在微信进程内运行且没有沙箱，可以读取或修改进程内存与文件、访问网络、安装 Hook，并可导致微信崩溃或死锁。禁用插件无法撤销任意副作用。",
        confirmLabel = "确定",
        dismissLabel = "取消",
        destructive = true,
        onConfirm = {
            showImportWarning = false
            importZipLauncher.launch(
                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
            )
        },
        onDismiss = { showImportWarning = false },
    )
}

/**
 * Create (pluginId == null) / basic-info edit page for a plugin, mirroring
 * [dev.ujhhgtg.wekit.ui.agent.settings.ModelProviderDetailScreen]: draft fields + one save action
 * in creation mode, instant-apply per row in edit mode. The plugin ID doubles as its directory
 * name, so it is only editable before creation.
 */
@Composable
private fun PythonPluginInfoScreen(pluginId: String?, onBack: () -> Unit) {
    val creating = pluginId == null
    val records by PythonPluginManager.records.collectAsState()
    val manifest = pluginId?.let(records::get)?.manifest
    if (!creating && manifest == null) return
    val scope = rememberCoroutineScope()
    val localizedContext by rememberUpdatedState(LocalContext.current)
    var draftId by remember { mutableStateOf("") }
    var draftName by remember { mutableStateOf("") }
    var draftVersion by remember { mutableStateOf("1.0.0") }
    var draftAuthor by remember { mutableStateOf("") }
    var draftDescription by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    val savable = PythonPluginManager.isValidPluginId(draftId.trim()) &&
        draftName.isNotBlank() && draftVersion.isNotBlank()
    val guardedBack = rememberCreationBackGuard(creating && savable, onBack)
    val confirmLabel = "确定"
    val cancelLabel = "取消"

    fun commitInfo(transform: (PythonPluginManifest) -> PythonPluginManifest) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PythonPluginManager.updatePluginInfo(pluginId!!, transform(manifest!!)) }
            }
            result.onFailure {
                showToast("插件保存失败：" + (it.message ?: ""))
            }
        }
    }

    SettingsScaffold(
        title = if (creating) "新建插件" else manifest?.name ?: pluginId,
        onBack = guardedBack,
    ) {
        item {
            SegmentedColumn(title = "插件") {
                if (creating) {
                    item {
                        TextFieldDialogWidget(
                            title = "插件 ID",
                            value = draftId,
                            onValueChange = { draftId = it },
                            dialogTitle = "插件 ID",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                            valueHint = "仅限小写字母、数字、点、下划线和连字符",
                            filter = { it.trim() },
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "名称",
                            value = draftName,
                            onValueChange = { draftName = it },
                            dialogTitle = "名称",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "版本",
                            value = draftVersion,
                            onValueChange = { draftVersion = it },
                            dialogTitle = "版本",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "作者（可选）",
                            value = draftAuthor,
                            onValueChange = { draftAuthor = it },
                            dialogTitle = "作者（可选）",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "描述（可选）",
                            value = draftDescription,
                            onValueChange = { draftDescription = it },
                            dialogTitle = "描述（可选）",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                            singleLine = false,
                        )
                    }
                } else {
                    val m = manifest!!
                    item { BaseWidget(title = "插件 ID", description = pluginId) }
                    item {
                        TextFieldDialogWidget(
                            title = "名称",
                            value = m.name,
                            onValueChange = { value -> commitInfo { info -> info.copy(name = value.trim()) } },
                            dialogTitle = "名称",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "版本",
                            value = m.version,
                            onValueChange = { value -> commitInfo { info -> info.copy(version = value.trim()) } },
                            dialogTitle = "版本",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "作者（可选）",
                            value = m.author,
                            onValueChange = { value -> commitInfo { info -> info.copy(author = value.trim()) } },
                            dialogTitle = "作者（可选）",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                        )
                    }
                    item {
                        TextFieldDialogWidget(
                            title = "描述（可选）",
                            value = m.description,
                            onValueChange = { value -> commitInfo { info -> info.copy(description = value.trim()) } },
                            dialogTitle = "描述（可选）",
                            confirmLabel = confirmLabel,
                            dismissLabel = cancelLabel,
                            singleLine = false,
                        )
                    }
                }
            }
        }
        if (creating) {
            item {
                SettingsActionRow {
                    SettingsListActionButton(
                        label = "保存",
                        icon = MaterialSymbols.Outlined.Save,
                        loading = saving,
                        enabled = savable,
                        onClick = {
                            saving = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        PythonPluginManager.createPlugin(
                                            draftId.trim(),
                                            draftName.trim(),
                                            draftVersion.trim(),
                                            draftAuthor.trim(),
                                            draftDescription.trim(),
                                        )
                                    }
                                }
                                saving = false
                                result.fold(
                                    onSuccess = { onBack() },
                                    onFailure = {
                                        showToast("插件保存失败：" + (it.message ?: ""))
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PythonDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
    openInfoEdit: () -> Unit,
    openEditor: () -> Unit,
    openDiagnostics: () -> Unit,
    onDeleted: () -> Unit,
) {
    val records by PythonPluginManager.records.collectAsState()
    val record = records[pluginId] ?: return
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedContext = LocalContext.current
    var pendingTrustPlugin by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val exportSuccessText = "插件导出成功"
    val exportZipLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                            PythonPluginManager.exportPlugin(pluginId, output)
                        }
                    }
                }
                result.fold(
                    onSuccess = { showToast(context, exportSuccessText) },
                    onFailure = {
                        showToast(context, "导出失败：" + (it.message ?: ""))
                    },
                )
            }
        }
    val inFlight = record.status == PythonPluginStatus.LOADING ||
        record.status == PythonPluginStatus.UNLOADING
    // 基本信息写入 plugin.json,插件运行中禁止编辑;开关本身保持可用以便停用。
    val infoEditable = !inFlight && record.status != PythonPluginStatus.ACTIVE
    SettingsScaffold(record.manifest?.name ?: pluginId, onBack) {
        item {
            SegmentedColumn(title = "插件") {
                item {
                    SwitchWidget(
                        title = record.manifest?.name ?: record.id,
                        description = record.description(),
                        checked = record.desiredEnabled,
                        enabled = record.manifest != null && !inFlight,
                        trailingDivider = true,
                        onClick = {
                            if (infoEditable) {
                                openInfoEdit()
                            } else {
                                showToast(
                                    context,
                                    "插件运行中，请先停用再编辑",
                                )
                            }
                        },
                        onCheckedChange = { enabled ->
                            if (enabled && !PythonPluginManager.isTrustWarningAccepted()) {
                                pendingTrustPlugin = pluginId
                            } else {
                                coroutineScope.launch(Dispatchers.IO) {
                                    PythonPluginManager.setDesiredEnabled(pluginId, enabled)
                                }
                            }
                        },
                    )
                }
                item {
                    BaseWidget(
                        title = "编辑入口脚本",
                        icon = MaterialSymbols.Outlined.Edit,
                        enabled = !inFlight,
                        onClick = openEditor,
                    )
                }
                item {
                    BaseWidget(
                        title = "打开插件目录",
                        icon = MaterialSymbols.Outlined.Folder,
                        onClick = {
                            val relative = record.root.relativeTo(Environment.getExternalStorageDirectory())
                                .path.replace(java.io.File.separatorChar, '/')
                            val uri = DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:$relative",
                            )
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                                type = DocumentsContract.Document.MIME_TYPE_DIR
                            })
                        },
                    )
                }
            }
        }
        item {
            SegmentedColumn(title = "操作") {
                item {
                    BaseWidget(
                        title = "重新加载插件",
                        icon = MaterialSymbols.Outlined.Refresh,
                        enabled = record.desiredEnabled && !inFlight,
                        onClick = { coroutineScope.launch(Dispatchers.IO) { PythonPluginManager.reload(pluginId) } },
                    )
                }
                item {
                    BaseWidget(
                        title = "查看诊断",
                        icon = MaterialSymbols.Outlined.Bug_report,
                        onClick = openDiagnostics,
                    )
                }
                item {
                    BaseWidget(
                        title = "导出为 ZIP",
                        icon = MaterialSymbols.Outlined.Upload,
                        onClick = { exportZipLauncher.launch("$pluginId.zip") },
                    )
                }
                item {
                    BaseWidget(
                        title = "清除插件数据",
                        icon = MaterialSymbols.Outlined.Delete,
                        isError = true,
                        enabled = record.status != PythonPluginStatus.ACTIVE && !inFlight,
                        onClick = { confirmClear = true },
                    )
                }
                item {
                    BaseWidget(
                        title = "删除插件",
                        icon = MaterialSymbols.Outlined.Delete_forever,
                        isError = true,
                        enabled = !inFlight,
                        onClick = { confirmDelete = true },
                    )
                }
                item {
                    BaseWidget(
                        title = "重启微信",
                        icon = MaterialSymbols.Outlined.Restart_alt,
                        onClick = { Process.killProcess(Process.myPid()) },
                    )
                }
            }
        }
    }
    SettingsConfirmDialog(
        show = confirmClear,
        title = "清除插件数据",
        message = "删除此插件的持久数据？",
        confirmLabel = "清除插件数据",
        dismissLabel = "取消",
        destructive = true,
        onConfirm = {
            confirmClear = false
            coroutineScope.launch(Dispatchers.IO) {
                (KnownPaths.moduleRoot / "python" / "data" / pluginId).toFile().deleteRecursively()
            }
        },
        onDismiss = { confirmClear = false },
    )
    SettingsConfirmDialog(
        show = confirmDelete,
        title = "删除插件",
        message = "将永久删除该插件的脚本与数据，且无法恢复。确定删除？",
        confirmLabel = "删除插件",
        dismissLabel = "取消",
        destructive = true,
        onConfirm = {
            confirmDelete = false
            coroutineScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { PythonPluginManager.deletePlugin(pluginId) }
                }
                result.fold(
                    onSuccess = { onDeleted() },
                    onFailure = {
                        showToast(
                            context,
                            "插件删除失败：" + (it.message ?: ""),
                        )
                    },
                )
            }
        },
        onDismiss = { confirmDelete = false },
    )
    SettingsConfirmDialog(
        show = pendingTrustPlugin != null,
        title = "仅运行可信代码",
        message = "插件在微信进程内运行且没有沙箱，可以读取或修改进程内存与文件、访问网络、安装 Hook，并可导致微信崩溃或死锁。禁用插件无法撤销任意副作用。",
        confirmLabel = "确定",
        dismissLabel = "取消",
        destructive = true,
        onConfirm = {
            pendingTrustPlugin = null
            PythonPluginManager.acceptTrustWarning()
            coroutineScope.launch(Dispatchers.IO) {
                PythonPluginManager.setDesiredEnabled(pluginId, true)
            }
        },
        onDismiss = { pendingTrustPlugin = null },
    )
}

@Composable
private fun PythonEditorScreen(pluginId: String, onBack: () -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val record = records[pluginId] ?: return
    val entry = record.manifest?.entry ?: return
    val entryPath = java.io.File(record.root, entry.replace('.', java.io.File.separatorChar))
    val sourceFile = java.io.File(entryPath.path + ".py").takeIf { it.isFile }
        ?: java.io.File(entryPath, "__init__.py")
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var saving by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var softWrap by rememberSaveable { mutableStateOf(WePrefs.getBoolOrFalse("python_editor_soft_wrap")) }
    val savedText = "已保存"
    val saveFailedText = "保存失败"
    val discardTitle = "放弃更改？"
    val discardMessage = "编辑后的脚本尚未保存，放弃后将丢失更改。"
    val discardConfirm = "放弃"
    val modified = text != original
    val guardedBack = rememberCreationBackGuard(
        modified,
        onBack,
        dialogTitle = discardTitle,
        dialogMessage = discardMessage,
        confirmLabel = discardConfirm,
    )
    LaunchedEffect(sourceFile) {
        val loaded = withContext(Dispatchers.IO) { if (sourceFile.isFile) sourceFile.readText() else "" }
        original = loaded
        text = loaded
    }

    SettingsEditorScaffold(
        title = "编辑入口脚本",
        onBack = guardedBack,
        actions = {
            IconButton(onClick = {
                softWrap = !softWrap
                WePrefs.putBool("python_editor_soft_wrap", softWrap)
            }) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Wrap_text,
                    contentDescription = "自动换行",
                )
            }
            IconButton(
                enabled = modified && !saving,
                onClick = {
                    saving = true
                    val snapshot = text
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                sourceFile.parentFile?.mkdirs()
                                sourceFile.writeText(snapshot)
                            }
                        }
                        saving = false
                        result.fold(
                            onSuccess = {
                                original = snapshot
                                showToast(context, savedText)
                            },
                            onFailure = { showToast(context, it.message ?: saveFailedText) },
                        )
                    }
                },
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Save,
                        contentDescription = "保存",
                    )
                }
            }
        },
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        )
    }
}
@Composable
private fun PythonDiagnosticsScreen(pluginId: String, onBack: () -> Unit) {
    val records by PythonPluginManager.records.collectAsState()
    val runtime by PythonRuntimeLoader.status.collectAsState()
    val record = records[pluginId] ?: return
    val crashMarker = remember(pluginId) { PythonCrashGuard.suspect()?.takeIf { it.pluginId == pluginId } }
    val mountedRuntime = PythonRuntimePack.mounted()
    val context = LocalContext.current
    val environment = remember {
        "process=${TargetProcesses.currentName}\n" +
            "abi=${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "pageSize=${Os.sysconf(OsConstants._SC_PAGESIZE)}\n" +
            "loader=xposed\n" +
            "moduleClassLoader=${PythonRuntimeLoader::class.java.classLoader}"
    }
    val runtimePackText = mountedRuntime?.let {
        "version=${it.manifest.version}\nsha256=${it.manifest.sha256}\napk=${it.runtimeApk}\nnative=${it.nativeDirectory}"
    } ?: "未挂载"
    val runtimeTitle = "运行时"
    val runtimePackTitle = "运行时包"
    val environmentTitle = "环境"
    val pluginTitle = "插件"
    val tracebackTitle = "回溯"
    val diagnosticsText = buildString {
        appendLine(runtimeTitle); appendLine(runtime)
        appendLine()
        appendLine(runtimePackTitle); appendLine(runtimePackText)
        appendLine()
        appendLine(environmentTitle); appendLine(environment)
        appendLine()
        appendLine(pluginTitle); appendLine(record.status.name)
        crashMarker?.let {
            appendLine()
            appendLine("CrashGuard"); appendLine(it)
        }
        record.traceback?.let {
            appendLine()
            appendLine(tracebackTitle); appendLine(it)
        }
    }
    val copiedText = "已复制"
    SettingsScaffold(
        title = "Python 诊断",
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                copyToClipboard(context, diagnosticsText)
                showToast(context, copiedText)
            }) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Content_copy,
                    contentDescription = "复制诊断信息",
                )
            }
        },
    ) {
        item {
            SegmentedColumn {
                item { BaseWidget(title = "运行时", description = runtime.toString()) }
                item {
                    BaseWidget(
                        title = "运行时包",
                        description = mountedRuntime?.let {
                            "version=${it.manifest.version}\nsha256=${it.manifest.sha256}\napk=${it.runtimeApk}\nnative=${it.nativeDirectory}"
                        } ?: "未挂载",
                    )
                }
                item { BaseWidget(title = "环境", description = environment) }
                item { BaseWidget(title = "插件", description = record.status.name) }
                crashMarker?.let { marker ->
                    item { BaseWidget(title = "CrashGuard", description = marker.toString(), isError = true) }
                }
                record.traceback?.let { traceback ->
                    item { BaseWidget(title = "回溯", description = traceback, isError = true) }
                }
            }
        }
    }
}

private fun PythonPluginRecord.description(): String = buildList {
    manifest?.version?.let { add(it) }
    manifest?.author?.takeIf(String::isNotBlank)?.let(::add)
    manifest?.description?.takeIf(String::isNotBlank)?.let(::add)
    add(status.name)
}.joinToString(" · ")
